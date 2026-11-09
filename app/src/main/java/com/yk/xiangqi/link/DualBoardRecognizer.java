package com.yk.xiangqi.link;

import android.content.Context;
import android.util.Log;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Side;

/**
 * ★★★ 双路识别仲裁（「强强联合」版，纯 Java 实现）。★★★
 *
 * <p><b>主路径（基线，一行不改）</b>：{@link PieceRecognizer} —— 整盘 SwinV2 + 逐格分类融合的
 * ONNX 识别，仍是默认结果。</p>
 *
 * <p><b>第二意见</b>：{@link LinkColorScan} 用纯 Java 逐格判「空/红/黑」。它不判兵种，只判结构，
 * 因此对「多认出一个子」「红黑认反」「空格里凭空冒子」这类事故特别灵敏——而这恰恰是让
 * Pikafish 报 {@code Unsupported position} 直接退出、把连线永久卡在「等待引擎思考」的元凶。</p>
 *
 * <h3>仲裁规则（保证「只会更好，不会更差」）</h3>
 * <ol>
 *   <li>ONNX 结果合法（将帅齐全、无兵种超额）→ <b>原样采用</b>，行为与旧版完全一致。</li>
 *   <li>ONNX 结果不合法 → 用颜色结构就地纠偏：<br>
 *       ① 颜色说「空」而 ONNX 有子 → 清空该格；<br>
 *       ② 颜色说「红/黑」与 ONNX 的颜色相反 → 换成同兵种的另一色（保留兵种，只改颜色）。<br>
 *       纠偏后再做一次 {@link ObservedBoard#legalized()}（兵种超额与非法位置也一并归位）。</li>
 *   <li>纠偏后仍不合法 → 退回 {@code ONNX.legalized()}，至少保证引擎不因非法局面崩掉。</li>
 *   <li>颜色扫描异常 / 无帧 → 一律退回基线 ONNX，绝不影响既有功能。</li>
 * </ol>
 *
 * <p>与参考实现（OpenCV 霍夫圆 + 模板匹配）相比：我们不需要找圆（框选已给出精确网格），
 * 也不需要 OpenCV 那 20~30MB 原生库；兵种仍由 ONNX 负责，只在结构层面做纠偏。</p>
 */
public final class DualBoardRecognizer implements AutoCloseable {

    private static final String TAG = "XiangqiLink";

    private final PieceRecognizer onnx;

    /** 累计：因结构纠偏而救回一局的次数。 */
    private long rescues;
    /** 累计：主路径非法、触发纠偏的次数。 */
    private long illegals;
    /** 累计：纠偏也失败、退回 legalized 的次数。 */
    private long falls;

    public DualBoardRecognizer(Context context, int modelThreads) throws Exception {
        this.onnx = new PieceRecognizer(context, modelThreads);
    }

    /** 与 {@link PieceRecognizer.Aligned} 同形，连线管线替换类型即可。 */
    public record Aligned(ObservedBoard board, BoardGeometry geometry) {}

    public void reset() {
        onnx.reset();
    }

    public void setBottomSide(Side side) {
        onnx.setBottomSide(side);
    }

    public ObservedBoard recognize(Frame frame, BoardGeometry geometry) throws Exception {
        ObservedBoard base = onnx.recognize(frame, geometry);
        return arbitrate(base, frame, geometry);
    }

    public Aligned recognizeAligned(Frame frame, BoardGeometry geometry) throws Exception {
        PieceRecognizer.Aligned result = onnx.recognizeAligned(frame, geometry);
        return new Aligned(arbitrate(result.board(), frame, geometry), result.geometry());
    }

    /** 静止画面复跑：没有独立帧可做颜色扫描，直接沿用基线结果。 */
    public ObservedBoard recognizeLastInput() throws Exception {
        return onnx.recognizeLastInput();
    }

    /** 一行统计，供日志排查。 */
    public String stats() {
        return "双路识别：触发纠偏=" + illegals + " 救回=" + rescues + " 退回=" + falls;
    }

    /**
     * 主路径与第二意见的仲裁入口。
     *
     * @return 永远返回非 null 的「尽量合法」棋盘（除基线本身为 null）
     */
    private ObservedBoard arbitrate(ObservedBoard base, Frame frame, BoardGeometry geometry) {
        if (base == null)
            return null;
        if (legal(base))
            return base; // ★ 基线合法：一行不动，保持旧行为

        illegals++;
        try {
            byte[] structure = LinkColorScan.scan(frame, geometry);
            byte[] cells = new byte[Board.SQUARES];
            for (int square = 0; square < Board.SQUARES; square++)
                cells[square] = correct(base.at(square), structure[square]);

            ObservedBoard candidate = new ObservedBoard(cells).legalized();
            if (legal(candidate)) {
                rescues++;
                Log.w(TAG, "★ 双路识别纠偏成功（" + stats() + "）");
                return candidate;
            }
        } catch (Throwable error) {
            Log.w(TAG, "双路识别纠偏异常，退回基线", error);
        }

        falls++;
        ObservedBoard repaired = base.legalized();
        Log.w(TAG, "双路识别纠偏未成，退回基线合法化（" + stats() + "）");
        return repaired;
    }

    /**
     * 单格纠偏：
     * <ul>
     *   <li>颜色说空、模型有子 → 清空（多认出来的子）；</li>
     *   <li>颜色与模型颜色相反 → 换成同兵种的另一色；</li>
     *   <li>其余（含颜色没扫到）→ 保留模型判断。</li>
     * </ul>
     */
    private static byte correct(byte label, byte structure) {
        if (structure == LinkColorScan.EMPTY)
            return label == ObservedBoard.EMPTY ? ObservedBoard.EMPTY : (byte) 0;
        if (label == ObservedBoard.EMPTY)
            return 0; // 只判结构，不凭颜色凭空造子（兵种仍需模型给出）
        boolean modelRed = label <= 7;
        boolean colorRed = structure == LinkColorScan.RED;
        if (modelRed == colorRed)
            return label;
        // 红黑认反：同兵种换色（红 1..7 ↔ 黑 8..14）
        return modelRed ? (byte) (label + 7) : (byte) (label - 7);
    }

    /** 合法 = 能摆成局面（将帅齐全）且无兵种超额。 */
    private static boolean legal(ObservedBoard board) {
        return board != null && board.canBuildPosition() && board.surplusCount() == 0;
    }

    @Override
    public void close() {
        Log.i(TAG, stats());
        onnx.close();
    }
}
