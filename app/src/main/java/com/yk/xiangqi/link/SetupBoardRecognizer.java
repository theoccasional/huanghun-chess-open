package com.yk.xiangqi.link;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.pikafish.chess.BoardRecognizer;
import com.pikafish.chess.ImageTransforms;
import com.pikafish.chess.OnnxBoardRecognizer;
import com.pikafish.chess.model.PieceColor;
import com.pikafish.chess.model.PieceType;
import com.yk.xiangqi.core.Board;

import java.util.List;

/**
 * 观棋（连线）模式复用「摆子模式」的 ONNX 识别系统。
 *
 * <p>原逐格模型（{@link PieceRecognizer}）对「车/卒」这类字形相近的棋子容易张冠李戴，
 * 摆子模式的 {@link OnnxBoardRecognizer} 有整盘 SwinV2 + YOLO 辅助 + 字形校验 + 位置纠错，
 * 判得更准。但它较重、较慢，绝不能塞进逐帧热路径；因此只把它用在<b>建盘/同步/校准</b>
 * 这类一次性、用户可见「请稍候」的路径上，逐帧变化检测仍沿用快的原识别器。</p>
 *
 * <p>这里直接吃校准几何的四角（左上/右上/左下/右下交叉点）做透视变换，输出与连线一致的
 * 屏幕朝向 90 格观测棋盘；识别不出来时返回 {@code null}，由调用方回退到原识别器，保证
 * 任何情况下都能建盘（不猴子掰玉米）。</p>
 */
public final class SetupBoardRecognizer implements AutoCloseable {
    private static final String TAG = "XiangqiLink";

    private final OnnxBoardRecognizer recognizer;

    public SetupBoardRecognizer(Context context) {
        recognizer = new OnnxBoardRecognizer(context);
        if (!recognizer.initialize())
            throw new IllegalStateException("摆子识别模型初始化失败");
    }

    /**
     * 用已知棋盘几何识别整盘。
     *
     * @return 屏幕朝向（左上为第 0 行第 0 列）的观测棋盘；模型不可用或识别为空时返回 null。
     */
    public ObservedBoard recognize(Frame frame, BoardGeometry geometry) {
        Bitmap bitmap = frame == null ? null : frame.toBitmap();
        if (bitmap == null)
            return null;
        try {
            ImageTransforms.Point[] corners = {
                new ImageTransforms.Point(geometry.left(), geometry.top()),
                new ImageTransforms.Point(geometry.right(), geometry.top()),
                new ImageTransforms.Point(geometry.left(), geometry.bottom()),
                new ImageTransforms.Point(geometry.right(), geometry.bottom()),
            };
            List<BoardRecognizer.RecognizedPiece> pieces = recognizer.recognizeWithCorners(bitmap, corners);
            if (pieces == null || pieces.isEmpty())
                return null;
            byte[] cells = new byte[Board.SQUARES];
            for (BoardRecognizer.RecognizedPiece piece : pieces) {
                int row = piece.getRow();
                int col = piece.getCol();
                if (row < 0 || row >= Board.RANKS || col < 0 || col >= Board.FILES)
                    continue;
                byte label = label(piece.getType(), piece.getColor());
                if (label != 0)
                    cells[Board.square(col, row)] = label;
            }
            return new ObservedBoard(cells);
        } catch (Exception error) {
            Log.e(TAG, "摆子识别系统识别失败", error);
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    /** 摆子模型的兵种/颜色映射到连线 15 类标签（红 1..7、黑 8..14）。 */
    private static byte label(PieceType type, PieceColor color) {
        int kind = switch (type) {
            case KING -> 1;
            case ADVISOR -> 2;
            case ELEPHANT -> 3;
            case KNIGHT -> 4;
            case ROOK -> 5;
            case CANNON -> 6;
            case PAWN -> 7;
        };
        return (byte) (color == PieceColor.RED ? kind : kind + 7);
    }

    /**
     * 全屏自动定位棋盘：用摆子模式的关键点模型（pose_model，RTMPose 四角检测）在整张
     * 截屏里直接找出棋盘四角，换算成连线用的 {@link BoardGeometry}，<b>完全不需要用户框选</b>。
     *
     * <p>这是“懒人方针”的关键一步：模型认的是棋盘本身，而不是“屏幕正中那块区域”，
     * 因此棋盘偏上、偏下、不居中（棋谱、皮卡丘/皮卡鱼网页版等）同样能定位到。
     * 关键点就是左上/右上/左下/右下四个交叉点，与 {@link #recognize(Frame, BoardGeometry)}
     * 使用的几何完全一致，可直接喂给识别器。</p>
     *
     * @return 检测到的棋盘几何；模型不可用、未检出、或几何明显不合理时返回 null。
     */
    public BoardGeometry detectGeometry(Frame frame) {
        Bitmap bitmap = frame == null ? null : frame.toBitmap();
        if (bitmap == null)
            return null;
        try {
            ImageTransforms.Point[] corners = recognizer.detectBoardCornersWithCrop(bitmap);
            return geometryFromCorners(corners, bitmap.getWidth(), bitmap.getHeight());
        } catch (Exception error) {
            Log.e(TAG, "神经角点检测失败", error);
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * 四角（左上/右上/左下/右下四个网格交叉点）→ 轴对齐 {@link BoardGeometry}。
     *
     * <p>手机截屏里的棋盘没有透视畸变，四角构成一个矩形；这里用左右两组点的均值定出
     * 上下边、用上下两组点的均值定出左右边，天然抹掉模型一两像素的抖动。</p>
     *
     * <p>还会做几何体检：边长、格距、宽高比（象棋棋盘 8 格宽 9 格高，格宽≈格高，
     * 故宽高比约 0.89）都在合理范围才采信，避免把“误检成一小坨”当成棋盘。</p>
     */
    private static BoardGeometry geometryFromCorners(ImageTransforms.Point[] corners,
                                                     int width, int height) {
        if (corners == null || corners.length != 4)
            return null;
        // 模型语义顺序固定为 [左上, 右上, 左下, 右下]（detectBoardCorners 内部已精修排序）。
        ImageTransforms.Point tl = corners[0];
        ImageTransforms.Point tr = corners[1];
        ImageTransforms.Point bl = corners[2];
        ImageTransforms.Point br = corners[3];

        float left = (tl.getX() + bl.getX()) / 2f;
        float right = (tr.getX() + br.getX()) / 2f;
        float top = (tl.getY() + tr.getY()) / 2f;
        float bottom = (bl.getY() + br.getY()) / 2f;

        float boardW = right - left;
        float boardH = bottom - top;
        if (boardW < 60f || boardH < 60f)
            return null;
        // 四角都得落在画面内（允许 1 像素越界）。
        if (left < -1f || top < -1f || right > width + 1f || bottom > height + 1f)
            return null;

        // 象棋棋盘：横向 8 格、纵向 9 格，格宽≈格高 → 整体宽高比约 8/9≈0.89。
        float ratio = boardW / boardH;
        if (ratio < 0.55f || ratio > 1.45f)
            return null;

        try {
            BoardGeometry geometry = new BoardGeometry(left, top, right, bottom);
            Log.i(TAG, "神经角点定位棋盘：" + Math.round(left) + "," + Math.round(top)
                + " – " + Math.round(right) + "," + Math.round(bottom)
                + "（" + Math.round(boardW) + "×" + Math.round(boardH) + "）");
            return geometry;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    @Override
    public void close() {
        recognizer.close();
    }
}