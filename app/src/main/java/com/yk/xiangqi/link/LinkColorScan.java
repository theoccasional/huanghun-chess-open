package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Board;

import java.nio.ByteBuffer;

/**
 * ★★★ 「第二意见」：纯 Java 的颜色结构扫描（不依赖 OpenCV）。★★★
 *
 * <p>参考的「小飞鲨」识别栈用 OpenCV 霍夫圆找棋子中心，再吸附到交叉点。我们的连线本来就由
 * 用户框选出了精确的 {@link BoardGeometry}，<b>根本不需要找圆</b>——直接按交叉点逐格采样即可，
 * 既省掉 OpenCV 那 20~30MB 的原生库，也少一层误差。</p>
 *
 * <p>本类只回答一个问题：这一格是<b>空 / 红子 / 黑子</b>。它不判兵种（兵种交给 ONNX 模型），
 * 所以判据简单而稳定：格中央 70% 区域内「偏红像素」「偏黑像素」的占比。</p>
 *
 * <p>用途见 {@link DualBoardRecognizer}：当 ONNX 给出的棋盘明显不合法（缺帅缺将、兵种超额）时，
 * 用这里的结构把「多认出来的子」清掉、把「红黑认反」的子纠正过来，让引擎不至于因为
 * 非法局面直接退出、把整条连线卡死。</p>
 */
public final class LinkColorScan {

    public static final byte EMPTY = 0;
    public static final byte RED = 1;
    public static final byte BLACK = 2;

    /** 每格每轴采样点数（13×13=169 点，足够稳定又不费时）。 */
    private static final int SAMPLES = 13;
    /** 只取格中央这一比例的区域，避开棋盘线与相邻棋子。 */
    private static final float INNER = 0.35f;
    /** 判「有红子」的下限占比。 */
    private static final double RED_MIN = 0.055d;
    /** 判「有黑子」的下限占比（黑字覆盖面积更大，阈值略高）。 */
    private static final double BLACK_MIN = 0.075d;

    private LinkColorScan() {
    }

    /**
     * 扫出 90 格的「空/红/黑」结构，行列顺序与识别模型一致（行 0 = 画面最上一排）。
     * 任何异常都退化为全空，绝不影响主识别路径。
     */
    public static byte[] scan(Frame frame, BoardGeometry geometry) {
        byte[] out = new byte[Board.SQUARES];
        if (frame == null || geometry == null)
            return out;
        try {
            ByteBuffer buffer = frame.buffer();
            int width = frame.width();
            int height = frame.height();
            int rowStride = frame.rowStride();
            int pixelStride = frame.pixelStride();
            int capacity = buffer.capacity();

            for (int row = 0; row < Board.RANKS; row++) {
                for (int column = 0; column < Board.FILES; column++) {
                    BoardGeometry.Rect cell = geometry.cell(column, row);
                    float centerX = (cell.left() + cell.right()) / 2f;
                    float centerY = (cell.top() + cell.bottom()) / 2f;
                    float halfWidth = (cell.right() - cell.left()) * INNER;
                    float halfHeight = (cell.bottom() - cell.top()) * INNER;

                    int red = 0;
                    int black = 0;
                    int total = 0;
                    for (int sy = 0; sy < SAMPLES; sy++) {
                        int y = (int) (centerY - halfHeight + (2 * halfHeight) * (sy + .5f) / SAMPLES);
                        if (y < 0 || y >= height)
                            continue;
                        for (int sx = 0; sx < SAMPLES; sx++) {
                            int x = (int) (centerX - halfWidth + (2 * halfWidth) * (sx + .5f) / SAMPLES);
                            if (x < 0 || x >= width)
                                continue;
                            int at = y * rowStride + x * pixelStride;
                            if (at < 0 || at + 2 >= capacity)
                                continue;
                            int r = buffer.get(at) & 0xFF;
                            int g = buffer.get(at + 1) & 0xFF;
                            int b = buffer.get(at + 2) & 0xFF;
                            total++;
                            int max = Math.max(r, Math.max(g, b));
                            if (r > 100 && r - g > 40 && r - b > 30) {
                                red++;
                            } else if (max < 95 && Math.abs(r - g) < 45 && Math.abs(g - b) < 45) {
                                black++;
                            }
                        }
                    }
                    if (total <= 0)
                        continue;
                    double redRatio = (double) red / total;
                    double blackRatio = (double) black / total;
                    if (redRatio >= RED_MIN && redRatio >= blackRatio * 1.4d) {
                        out[row * Board.FILES + column] = RED;
                    } else if (blackRatio >= BLACK_MIN) {
                        out[row * Board.FILES + column] = BLACK;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 第二意见失败就当作没扫到：主路径照旧
        }
        return out;
    }
}
