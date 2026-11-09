package com.yk.xiangqi.link;

import android.content.Context;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Side;

import com.pikafish.chess.heavy.HeavyStore;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 内置 FP32 MobileNetV3 逐格模型的 ONNX Runtime CPU 推理器。
 *
 * <p>叠加识别：逐格模型（piece_classifier.onnx）与整盘 SwinV2 模型（layout_model.onnx）
 * 各自给出 90 格概率分布，再按权重融合后取最大值。逐格模型对局部笔画敏感、整盘模型带
 * 全局上下文，两者互补能明显压低“张冠李戴”。整盘模型缺失时自动退化为逐格模型。</p>
 *
 * <p>建盘、同步与快捷校准使用 {@link #recognizeAligned(Frame, BoardGeometry)}：框选时手指
 * 拖拽的几像素误差会让同一块棋盘时而认对时而认错，该方法在框选附近做一次亚格偏移搜索，
 * 取第一个「将帅齐全且无超额」的合法对齐；一个都没有时，退而取子力超额最少、置信度最高
 * 的那一个。</p>
 *
 * <p><b>阴影/渐变兜底：</b>部分棋盘的棋子带金属银灰外圈、3D 斜面、软投影与渐变底色，
 * 标准 ImageNet 归一化无法消除这些低频光照差异，模型时而认对时而认飞。识别器在标准
 * 预处理未能给出合法棋盘时，自动用「逐格 z-score 局部对比归一」兜底：对每格每通道减去
 * 该格均值、除以该格标准差，从而抹掉投影/渐变/斜面带来的整体偏置与对比差异，只保留
 * 笔画本身的高频信号。兜底仅在标准路径失败时启用，已经能认的棋盘不受影响。</p>
 */
public final class PieceRecognizer implements AutoCloseable {
    private static final String TAG = "XiangqiLink";
    private static final int INPUT_SIZE = 48;
    private static final int CHANNELS = 3;
    private static final int PIXELS_PER_CELL = INPUT_SIZE * INPUT_SIZE;
    private static final long[] SHAPE = {90, CHANNELS, INPUT_SIZE, INPUT_SIZE};
    private static final int INPUT_FLOATS = Board.SQUARES * CHANNELS * PIXELS_PER_CELL;
    private static final float[] MEAN = {.485f, .456f, .406f};
    private static final float[] INV_STD = {1f / .229f, 1f / .224f, 1f / .225f};
    /** 融合权重：整盘模型更强、逐格模型校正局部。 */
    private static final float LAYOUT_WEIGHT = .6f;
    private static final float PIECE_WEIGHT = .4f;
    /** 相邻候选的间隔与最大偏移；±0.30 格能拉回手指拖拽的框选误差，也覆盖常见的小比例缩框。 */
    private static final float ALIGN_SPAN = .30f;
    private static final int ALIGN_STEPS = 5;
    /**
     * 粗搜之后的「半步细调」：粗搜间隔是 0.15 格，细调再以 0.05 格为粒度在最优候选附近
     * 走一圈。专门处理「框选只差一点点、粗搜五个档位都刚好认不正」的难局面 —— 人眼看得出
     * 该局面是对的，但模型在 0.15 格的错位下就是认不出来。细调只在粗搜（标准 + 增强）
     * 都没得到合法棋盘时启用，因此已经能认的棋盘毫发无损。
     */
    private static final float FINE_SPAN = .10f;
    private static final int FINE_STEPS = 5;
    private static final float[][] FINE_OFFSETS = fineOffsets();
    /** 局部对比归一后的幅值上限，避免离群像素把整格拉飞。 */
    private static final float ENHANCE_CLAMP = 3f;
    /** 局部标准差下限，避免平坦格除以极小值放大噪声。 */
    private static final float ENHANCE_MIN_STD = .5f;
    /** 亚格对齐候选（单位：格），按偏移由小到大排列。 */
    private static final float[][] ALIGN_OFFSETS = alignOffsets();
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    /** 整盘 SwinV2 模型；加载失败时为 null，此时只用逐格模型。 */
    private final LayoutRecognizer layout;
    /**
     * 远程识别器：异地引擎档位下，把 ONNX 前向交给配对上的电脑跑（手机不发烫）。
     * 未配对 / 不可用时为 null，识别照旧在本机完成。
     */
    private final RemoteRecognizer remote;
    /** 会话级自学习模板：校准确认合法棋盘后学习，用于纠正逐格模型的兵种混淆。 */
    private final SpriteMatcher matcher = new SpriteMatcher();
    /** 重用的 ONNX 输入缓冲；识别调用仅发生在帧 worker。 */
    private final FloatBuffer modelInput = ByteBuffer.allocateDirect(INPUT_FLOATS * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** Java 预处理先写入普通连续数组，完成后一次性复制到 ONNX 的 direct buffer。 */
    private final float[] preparedInput = new float[INPUT_FLOATS];
    /** 静止画面手动同步时重跑的最后模型输入。 */
    private final FloatBuffer lastInput = ByteBuffer.allocateDirect(INPUT_FLOATS * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** 局部对比归一后的输入缓冲；标准路径失败时启用，避免覆盖标准输入。 */
    private final float[] enhancedInput = new float[INPUT_FLOATS];
    /** 标记 lastInput 是否来自增强预处理，同步重跑时据此决定整盘模型走哪条路。 */
    private boolean lastInputEnhanced;
    /** 框选和投屏缓冲尺寸不变时复用，避免每帧重新计算双线性采样坐标。 */
    private SamplingPlan samplingPlan;
    private boolean hasLastInput;

    public PieceRecognizer(Context context, int modelThreads) throws Exception {
        if (modelThreads < 1 || modelThreads > 8)
            throw new IllegalArgumentException("模型线程数必须在 1 到 8 之间");
        byte[] model;
        try (InputStream input = HeavyStore.openLocalFirst(context, "link/piece_classifier.onnx");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; )
                output.write(buffer, 0, read);
            model = output.toByteArray();
        }
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(modelThreads);
        session = environment.createSession(model, options);
        // ★★★ 识别一律走本地：不再把 ONNX 前向发到辎重包端去跑。★★★
        //   实测：异地档位下远程推理要额外等网络（连不上还要等 1.5s 连接 + 8s 读超时），
        //   一旦对方慢/休眠，识别就整体卡住甚至装不上模型——表现为"连线连不动、缺组件"。
        //   画面像素本来就在本机，模型也在本机（单包自带），就地取材才稳、才快。
        //   算棋可以异地（引擎在对方算），识别不再异地。
        remote = null;
        LayoutRecognizer created = null;
        try {
            created = new LayoutRecognizer(context, modelThreads, null);
        } catch (Exception error) {
            // 整盘模型只是叠加项，缺失或损坏时逐格模型仍可独立工作。
            Log.e(TAG, "整盘识别模型加载失败，仅使用逐格模型", error);
        }
        layout = created;
    }

    public void reset() {
        hasLastInput = false;
        lastInputEnhanced = false;
        if (layout != null)
            layout.reset();
        matcher.reset();
    }

    /** 底边方向变化时同步给整盘模型，保证其输入恒为红方在下。 */
    public void setBottomSide(Side bottomSide) {
        if (layout != null)
            layout.setBottomSide(bottomSide);
    }

    public ObservedBoard recognize(Frame frame, BoardGeometry geometry) throws Exception {
        Fused standard = evaluate(frame, geometry);
        if (legal(standard.board()))
            return standard.board();
        // 标准预处理在这类带阴影/斜面/渐变的棋盘上不稳定：先试一次增强预处理，
        // 仍不行就按「合法性优先、超额次之、置信度再次之」取较优者；标准更优时
        // 需要回填一次 lastInput，避免同步重跑用到增强输入。
        Fused enhanced = evaluateEnhanced(frame, geometry);
        if (better(enhanced, standard))
            return enhanced.board();
        evaluate(frame, geometry);
        return standard.board();
    }

    /**
     * 建盘、同步与快捷校准使用的识别：先在框选几何上识别一次，结果合法（将帅齐全且无子力
     * 超额）就直接采用；否则在框选附近做亚格偏移搜索，按「先合法、再少超额、后高置信」取最优。
     * 返回采用的棋盘与搜索后的几何，调用方应把该几何沿用到后续整局。
     *
     * <p>标准亚格搜索全空时，自动用「逐格 z-score 局部对比归一」重跑一次同样的搜索：
     * 它专门处理带阴影/斜面/渐变底色的棋盘——这类棋盘标准 ImageNet 归一化认得吃力，
     * 局部对比归一能抹掉光照偏置、把笔画凸显出来。增强路径只在标准失败时启用，
     * 已经能稳定识别的棋盘不受影响。</p>
     */
    public Aligned recognizeAligned(Frame frame, BoardGeometry geometry) throws Exception {
        Fused best = evaluate(frame, geometry);
        BoardGeometry bestGeometry = geometry;
        boolean bestEnhanced = false;
        boolean enhancedSearchRan = false;
        if (!legal(best.board())) {
            BoardGeometry evaluated = geometry;
            for (float[] offset : ALIGN_OFFSETS) {
                if (offset[0] == 0f && offset[1] == 0f)
                    continue;
                BoardGeometry candidate = shift(geometry, offset[0], offset[1]);
                Fused fused = evaluate(frame, candidate);
                evaluated = candidate;
                // 合法性优先：第一个「将帅齐全且无超额」的对齐就是可信解，立即停手。
                // 再往下搜只会拿别的偏移去换掉它，并且白白多跑十几次整盘推理。
                if (legal(fused.board())) {
                    best = fused;
                    bestGeometry = candidate;
                    bestEnhanced = false;
                    break;
                }
                if (better(fused, best)) {
                    best = fused;
                    bestGeometry = candidate;
                    bestEnhanced = false;
                }
            }
            // 标准亚格搜索全空：换增强预处理再搜一遍，专治阴影/斜面/渐变底色。
            if (!legal(best.board())) {
                enhancedSearchRan = true;
                Fused enhancedBest = evaluateEnhanced(frame, geometry);
                BoardGeometry enhancedBestGeometry = geometry;
                if (legal(enhancedBest.board())) {
                    best = enhancedBest;
                    bestGeometry = enhancedBestGeometry;
                    bestEnhanced = true;
                } else {
                    for (float[] offset : ALIGN_OFFSETS) {
                        if (offset[0] == 0f && offset[1] == 0f)
                            continue;
                        BoardGeometry candidate = shift(geometry, offset[0], offset[1]);
                        Fused fused = evaluateEnhanced(frame, candidate);
                        if (legal(fused.board())) {
                            enhancedBest = fused;
                            enhancedBestGeometry = candidate;
                            break;
                        }
                        if (better(fused, enhancedBest)) {
                            enhancedBest = fused;
                            enhancedBestGeometry = candidate;
                        }
                    }
                    if (better(enhancedBest, best)) {
                        best = enhancedBest;
                        bestGeometry = enhancedBestGeometry;
                        bestEnhanced = true;
                    }
                }
            }
            // 粗搜（标准 + 增强）都没拿到合法棋盘：在最优候选附近做一次半步细调。
            // 只认「合法」这一条硬指标——找到就换，找不到维持原判；因此不会把已认对的
            // 棋盘改坏，只多给那些「框选差一点点」的难局面一个翻盘机会。
            if (!legal(best.board())) {
                for (float[] offset : FINE_OFFSETS) {
                    if (offset[0] == 0f && offset[1] == 0f)
                        continue;
                    BoardGeometry candidate = shift(bestGeometry, offset[0], offset[1]);
                    Fused fused = bestEnhanced
                            ? evaluateEnhanced(frame, candidate)
                            : evaluate(frame, candidate);
                    if (legal(fused.board())) {
                        best = fused;
                        bestGeometry = candidate;
                        break;
                    }
                }
            }
            // 候选循环会不断覆写 lastInput；胜者若不是最后一次推理的那个，补跑一次，
            // 保证紧随其后的整局识别与 recognizeLastInput 都对齐到最终采用的几何与预处理。
            // 增强搜索跑过后 lastInput 一定被增强路径写过：标准胜出时也必须回填标准输入。
            if (bestEnhanced)
                evaluateEnhanced(frame, bestGeometry);
            else if (enhancedSearchRan || evaluated != bestGeometry)
                evaluate(frame, bestGeometry);
        }
        lastInputEnhanced = bestEnhanced;
        // 对齐已确认合法：把当前屏幕的真实字形学进模板库。标准开局就已含全部 14 类棋子，
        // 一次校准就能给每个兵种建立本平台的形状基准，后续帧据此纠正兵种混淆。
        if (legal(best.board())) {
            if (!bestEnhanced) {
                System.arraycopy(preparedInput, 0, enhancedInput, 0, INPUT_FLOATS);
                enhancePreparedInput(enhancedInput);
            }
            matcher.learn(best.board(), best.probabilities(), enhancedInput);
        }
        return new Aligned(best.board(), bestGeometry);
    }

    private Fused evaluate(Frame frame, BoardGeometry geometry) throws Exception {
        float[] layoutProbabilities = layout == null ? null : recognizeLayout(frame, geometry);
        FloatBuffer input = prepare(frame, geometry);
        lastInput.clear();
        lastInput.put(input);
        lastInput.flip();
        hasLastInput = true;
        lastInputEnhanced = false;
        Fused fused = fuse(runPiece(lastInput), layoutProbabilities);
        return refineWithTemplates(fused);
    }

    /**
     * 用「逐格 z-score 局部对比归一」预处理跑一次推理：每格每通道减去该格均值、除以该格
     * 标准差，抹掉投影/渐变/斜面带来的整体偏置与对比差异，只保留笔画高频信号。
     * 输入仍按 ImageNet 范围归一后送入模型，分布不至于完全跑偏。
     */
    private Fused evaluateEnhanced(Frame frame, BoardGeometry geometry) throws Exception {
        float[] layoutProbabilities = layout == null ? null : recognizeLayoutEnhanced(frame, geometry);
        prepare(frame, geometry); // 填好 preparedInput 与 modelInput，下面只取 preparedInput
        System.arraycopy(preparedInput, 0, enhancedInput, 0, INPUT_FLOATS);
        enhancePreparedInput(enhancedInput);
        lastInput.clear();
        lastInput.put(enhancedInput);
        lastInput.flip();
        hasLastInput = true;
        lastInputEnhanced = true;
        Fused fused = fuse(runPiece(lastInput), layoutProbabilities);
        return refineWithTemplates(fused);
    }

    /**
     * 用模板校验含糊格。只在本会话已学到模板时启用，模板数据直接来自本棋盘增强输入，
     * 因此对任意平台的棋子风格都成立；纠正后的棋盘若破坏合法性（将帅缺位或超额增加），
     * 说明这次模板意见不可信，退回模型原判。
     */
    private Fused refineWithTemplates(Fused fused) {
        if (!matcher.hasTemplates())
            return fused;
        if (!lastInputEnhanced) {
            System.arraycopy(preparedInput, 0, enhancedInput, 0, INPUT_FLOATS);
            enhancePreparedInput(enhancedInput);
        }
        ObservedBoard refined = matcher.refine(fused.board(), fused.probabilities(), enhancedInput);
        if (refined == fused.board())
            return fused;
        if (!refined.canBuildPosition() || refined.surplusCount() > fused.board().surplusCount())
            return fused;
        return new Fused(refined, fused.confidence(), fused.probabilities());
    }

    /**
     * 对 [90, 3, 48, 48] 的 ImageNet 归一输入逐格逐通道做 z-score 归一：减均值、除标准差，
     * 并把幅值夹在 [-3, 3] 内。它消去每格的低频光照（阴影、渐变、斜面高光），让笔画本身
     * 成为每格的主信号，模型对这类棋盘的判别因此稳定下来。
     */
    private static void enhancePreparedInput(float[] input) {
        for (int cell = 0; cell < Board.SQUARES; cell++) {
            int cellBase = cell * CHANNELS * PIXELS_PER_CELL;
            for (int channel = 0; channel < CHANNELS; channel++) {
                int channelBase = cellBase + channel * PIXELS_PER_CELL;
                float sum = 0f;
                for (int i = 0; i < PIXELS_PER_CELL; i++)
                    sum += input[channelBase + i];
                float mean = sum / PIXELS_PER_CELL;
                float sumSq = 0f;
                for (int i = 0; i < PIXELS_PER_CELL; i++) {
                    float diff = input[channelBase + i] - mean;
                    sumSq += diff * diff;
                }
                float std = (float) Math.sqrt(sumSq / PIXELS_PER_CELL);
                if (std < ENHANCE_MIN_STD)
                    std = ENHANCE_MIN_STD;
                for (int i = 0; i < PIXELS_PER_CELL; i++) {
                    float z = (input[channelBase + i] - mean) / std;
                    if (z > ENHANCE_CLAMP)
                        z = ENHANCE_CLAMP;
                    else if (z < -ENHANCE_CLAMP)
                        z = -ENHANCE_CLAMP;
                    input[channelBase + i] = z;
                }
            }
        }
    }

    /** 恰好一个红帅一个黑将，且没有兵种超额，才算一次可信的对齐。 */
    private static boolean legal(ObservedBoard board) {
        return board.canBuildPosition() && board.surplusCount() == 0;
    }

    /** 合法性优先、超额次之、置信度再次之；偏移大小由候选顺序保证。 */
    private static boolean better(Fused first, Fused second) {
        boolean firstLegal = legal(first.board());
        if (firstLegal != legal(second.board()))
            return firstLegal;
        int firstSurplus = first.board().surplusCount();
        int secondSurplus = second.board().surplusCount();
        if (firstSurplus != secondSurplus)
            return firstSurplus < secondSurplus;
        return first.confidence() > second.confidence();
    }

    /** 把框选几何按格数与格宽平移。 */
    private static BoardGeometry shift(BoardGeometry geometry, float dx, float dy) {
        float offsetX = dx * geometry.stepX();
        float offsetY = dy * geometry.stepY();
        return new BoardGeometry(geometry.left() + offsetX, geometry.top() + offsetY,
            geometry.right() + offsetX, geometry.bottom() + offsetY);
    }

    private static float[][] alignOffsets() {
        List<float[]> candidates = new ArrayList<>();
        for (int row = 0; row < ALIGN_STEPS; row++)
            for (int column = 0; column < ALIGN_STEPS; column++)
                candidates.add(new float[]{
                    -ALIGN_SPAN + column * (2 * ALIGN_SPAN / (ALIGN_STEPS - 1)),
                    -ALIGN_SPAN + row * (2 * ALIGN_SPAN / (ALIGN_STEPS - 1))});
        candidates.sort(Comparator.comparingDouble((float[] offset) -> offset[0] * offset[0] + offset[1] * offset[1])
            .thenComparingDouble(offset -> Math.abs(offset[0]))
            .thenComparingDouble(offset -> Math.abs(offset[1])));
        return candidates.toArray(new float[0][]);
    }

    /** 半步细调候选（单位：格），同样按偏移由小到大排列，0 偏移最先短路跳过。 */
    private static float[][] fineOffsets() {
        List<float[]> candidates = new ArrayList<>();
        for (int row = 0; row < FINE_STEPS; row++)
            for (int column = 0; column < FINE_STEPS; column++)
                candidates.add(new float[]{
                    -FINE_SPAN + column * (2 * FINE_SPAN / (FINE_STEPS - 1)),
                    -FINE_SPAN + row * (2 * FINE_SPAN / (FINE_STEPS - 1))});
        candidates.sort(Comparator.comparingDouble((float[] offset) -> offset[0] * offset[0] + offset[1] * offset[1])
            .thenComparingDouble(offset -> Math.abs(offset[0]))
            .thenComparingDouble(offset -> Math.abs(offset[1])));
        return candidates.toArray(new float[0][]);
    }

    public ObservedBoard recognizeLastInput() throws Exception {
        if (!hasLastInput)
            return null;
        float[] pieceLogits = runPiece(lastInput);
        float[] layoutProbabilities = layout == null ? null : recognizeLayoutLastInput();
        return fuse(pieceLogits, layoutProbabilities).board();
    }

    /**
     * 整盘模型只是叠加项：它一旦推理失败（模型损坏、输入异常等），必须退化回逐格模型，
     * 否则会让整条连线识别直接失败、同步与校准看起来“点了没反应”。
     */
    private float[] recognizeLayout(Frame frame, BoardGeometry geometry) {
        try {
            return layout.recognize(frame, geometry);
        } catch (Exception error) {
            Log.e(TAG, "整盘识别失败，回退逐格模型", error);
            return null;
        }
    }

    /** 整盘模型的增强预处理路径，与逐格模型的 evaluateEnhanced 配对使用。 */
    private float[] recognizeLayoutEnhanced(Frame frame, BoardGeometry geometry) {
        try {
            return layout.recognizeEnhanced(frame, geometry);
        } catch (Exception error) {
            Log.e(TAG, "整盘识别失败，回退逐格模型", error);
            return null;
        }
    }

    private float[] recognizeLayoutLastInput() {
        try {
            return layout.recognizeLastInput();
        } catch (Exception error) {
            Log.e(TAG, "整盘识别失败，回退逐格模型", error);
            return null;
        }
    }

    private float[] runPiece(FloatBuffer input) throws Exception {
        // 优先走远程：电脑端跑同一份模型，结果与本机完全等价，而手机不再为推理耗电。
        // 远程返回 null（未配对 / 超时 / 失败）时，照旧退回下面的本机推理。
        if (remote != null) {
            float[] remoteLogits = remote.run(RemoteRecognizer.KIND_PIECE, input,
                Board.SQUARES * ObservedBoard.LABELS);
            if (remoteLogits != null)
                return remoteLogits;
        }
        input.rewind();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input, SHAPE);
             OrtSession.Result output = session.run(Collections.singletonMap("input", tensor))) {
            float[][] logits = (float[][]) output.get("logits").get().getValue();
            float[] flat = new float[Board.SQUARES * ObservedBoard.LABELS];
            for (int index = 0; index < logits.length; index++)
                System.arraycopy(logits[index], 0, flat, index * ObservedBoard.LABELS, ObservedBoard.LABELS);
            return flat;
        }
    }

    /**
     * 逐格模型先做每格 softmax 得到 15 类概率，再叠加整盘模型的 16 类概率，最后逐格取最大。
     * 整盘模型的 0 空点与 1 遮挡都归为空，2..8 红方、9..15 黑方对应本项目的 1..7、8..14。
     * 概率数组随结果返回，供模板校验与学习使用。
     */
    private static Fused fuse(float[] pieceLogits, float[] layoutProbabilities) {
        float[] fused = new float[Board.SQUARES * ObservedBoard.LABELS];
        for (int square = 0; square < Board.SQUARES; square++) {
            int offset = square * ObservedBoard.LABELS;
            float max = pieceLogits[offset];
            for (int label = 1; label < ObservedBoard.LABELS; label++)
                max = Math.max(max, pieceLogits[offset + label]);
            float sum = 0f;
            for (int label = 0; label < ObservedBoard.LABELS; label++) {
                float value = (float) Math.exp(pieceLogits[offset + label] - max);
                fused[offset + label] = value;
                sum += value;
            }
            for (int label = 0; label < ObservedBoard.LABELS; label++)
                fused[offset + label] = fused[offset + label] / sum * PIECE_WEIGHT;
        }
        if (layoutProbabilities != null)
            for (int square = 0; square < Board.SQUARES; square++) {
                int layoutOffset = square * LayoutRecognizer.CLASSES;
                int offset = square * ObservedBoard.LABELS;
                for (int label = 0; label < LayoutRecognizer.CLASSES; label++) {
                    int target = label < 2 ? 0 : label - 1;
                    fused[offset + target] += layoutProbabilities[layoutOffset + label] * LAYOUT_WEIGHT;
                }
            }
        float confidence = 0f;
        for (int square = 0; square < Board.SQUARES; square++) {
            int offset = square * ObservedBoard.LABELS;
            float max = fused[offset];
            for (int label = 1; label < ObservedBoard.LABELS; label++)
                max = Math.max(max, fused[offset + label]);
            confidence += max;
        }
        return new Fused(ObservedBoard.fromLogits(fused), confidence / Board.SQUARES, fused);
    }

    /** 一次融合识别的棋盘、按格平均的置信度与逐格概率（模板校验/学习用）。 */
    private record Fused(ObservedBoard board, float confidence, float[] probabilities) {
    }

    /** 对齐识别结果：采用的棋盘，以及搜索后应沿用的几何。 */
    public record Aligned(ObservedBoard board, BoardGeometry geometry) {
    }

    /** 直接从一帧 RGBA 像素填充 90 格模型输入，不创建 Bitmap 或单格对象。 */
    private FloatBuffer prepare(Frame frame, BoardGeometry geometry) {
        int width = frame.width();
        int height = frame.height();
        int rowStride = frame.rowStride();
        int pixelStride = frame.pixelStride();
        if (samplingPlan == null)
            samplingPlan = new SamplingPlan(geometry, width, height, rowStride, pixelStride);
        else if (!samplingPlan.matches(geometry, width, height, rowStride, pixelStride))
            samplingPlan.retarget(geometry, width, height, rowStride, pixelStride);
        samplingPlan.fill(frame.buffer(), preparedInput);
        modelInput.clear();
        modelInput.put(preparedInput);
        modelInput.rewind();
        return modelInput;
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    /**
     * 一个固定棋盘几何对应的双线性采样表。横向和纵向坐标彼此独立，故只需缓存
     * 9×48 与 10×48 项；填充阶段不再计算坐标、floor 或边界裁剪。
     */
    static final class SamplingPlan {
        private final int[] x0 = new int[Board.FILES * INPUT_SIZE];
        private final int[] x1 = new int[Board.FILES * INPUT_SIZE];
        private final int[] y0 = new int[Board.RANKS * INPUT_SIZE];
        private final int[] y1 = new int[Board.RANKS * INPUT_SIZE];
        private final float[] dx = new float[Board.FILES * INPUT_SIZE];
        private final float[] dy = new float[Board.RANKS * INPUT_SIZE];
        private float left, top, stepX, stepY;
        private BoardGeometry geometry;
        private int width, height, rowStride, pixelStride;
        /** 覆盖全部采样点的紧凑 RGBA 区域；每帧只覆写内容，不重新分配。 */
        private byte[] roi;
        private int sourceOffset, roiRowBytes, roiRows;

        SamplingPlan(BoardGeometry geometry, int width, int height, int rowStride, int pixelStride) {
            retarget(geometry, width, height, rowStride, pixelStride);
        }

        /**
         * 用一个新几何重算采样表。
         *
         * <p>亚格对齐搜索会在同一步长下反复平移几何，每次新建计划都会分配一块数 MB 的
         * ROI，24 个候选就是上百 MB 的短命垃圾。这里原地重算并复用容量足够的 ROI，
         * 把整个搜索的额外分配降到一次。</p>
         */
        void retarget(BoardGeometry geometry, int width, int height, int rowStride, int pixelStride) {
            this.geometry = geometry;
            this.left = geometry.left();
            this.top = geometry.top();
            this.stepX = geometry.stepX();
            this.stepY = geometry.stepY();
            this.width = width;
            this.height = height;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
            for (int column = 0; column < Board.FILES; column++) {
                BoardGeometry.Rect cell = geometry.cell(column, 0);
                for (int pixel = 0; pixel < INPUT_SIZE; pixel++)
                    sampleAxis(cell.left() + (pixel + .5f) * (cell.right() - cell.left()) / INPUT_SIZE,
                        width, pixelStride, x0, x1, dx, column * INPUT_SIZE + pixel);
            }
            for (int row = 0; row < Board.RANKS; row++) {
                BoardGeometry.Rect cell = geometry.cell(0, row);
                for (int pixel = 0; pixel < INPUT_SIZE; pixel++)
                    sampleAxis(cell.top() + (pixel + .5f) * (cell.bottom() - cell.top()) / INPUT_SIZE,
                        height, rowStride, y0, y1, dy, row * INPUT_SIZE + pixel);
            }
            int minX = min(x0, x1), maxX = max(x0, x1);
            int minY = min(y0, y1), maxY = max(y0, y1);
            sourceOffset = minY + minX;
            roiRowBytes = maxX - minX + pixelStride;
            roiRows = (maxY - minY) / rowStride + 1;
            int needed = Math.multiplyExact(roiRowBytes, roiRows);
            if (roi == null || roi.length < needed)
                roi = new byte[needed];
            for (int index = 0; index < x0.length; index++) {
                x0[index] -= minX;
                x1[index] -= minX;
            }
            for (int index = 0; index < y0.length; index++) {
                y0[index] = (y0[index] - minY) / rowStride * roiRowBytes;
                y1[index] = (y1[index] - minY) / rowStride * roiRowBytes;
            }
        }

        boolean matches(BoardGeometry geometry, int width, int height, int rowStride, int pixelStride) {
            return left == geometry.left() && top == geometry.top()
                && stepX == geometry.stepX() && stepY == geometry.stepY()
                && this.width == width && this.height == height
                && this.rowStride == rowStride && this.pixelStride == pixelStride;
        }

        /** 一次坐标/插值计算同时填充 RGB 三通道，输出仍为每格 NCHW。 */
        void fill(ByteBuffer pixels, float[] output) {
            copyRoi(pixels);
            for (int row = 0; row < Board.RANKS; row++)
                for (int column = 0; column < Board.FILES; column++) {
                    int base = (row * Board.FILES + column) * CHANNELS * PIXELS_PER_CELL;
                    for (int y = 0; y < INPUT_SIZE; y++) {
                        int yi = row * INPUT_SIZE + y;
                        for (int x = 0; x < INPUT_SIZE; x++) {
                            int xi = column * INPUT_SIZE + x;
                            int a = y0[yi] + x0[xi], b = y0[yi] + x1[xi];
                            int c = y1[yi] + x0[xi], d = y1[yi] + x1[xi];
                            int index = y * INPUT_SIZE + x;
                            for (int channel = 0; channel < CHANNELS; channel++) {
                                float va = pixel(roi, a + channel), vb = pixel(roi, b + channel);
                                float vc = pixel(roi, c + channel), vd = pixel(roi, d + channel);
                                float top = va + (vb - va) * dx[xi];
                                float bottom = vc + (vd - vc) * dx[xi];
                                float value = top + (bottom - top) * dy[yi];
                                output[base + channel * PIXELS_PER_CELL + index] =
                                    (value / 255f - MEAN[channel]) * INV_STD[channel];
                            }
                        }
                    }
            }
        }

        private void copyRoi(ByteBuffer pixels) {
            for (int row = 0; row < roiRows; row++) {
                pixels.position(sourceOffset + row * rowStride);
                pixels.get(roi, row * roiRowBytes, roiRowBytes);
            }
        }

        private static void sampleAxis(float value, int limit, int stride, int[] first, int[] second,
                                       float[] fraction, int index) {
            int lower = clamp((int) Math.floor(value), 0, limit - 1);
            first[index] = lower * stride;
            second[index] = Math.min(lower + 1, limit - 1) * stride;
            fraction[index] = value - lower;
        }

        private static int pixel(byte[] pixels, int offset) {
            return pixels[offset] & 255;
        }

        private static int min(int[] first, int[] second) {
            int result = Integer.MAX_VALUE;
            for (int value : first) result = Math.min(result, value);
            for (int value : second) result = Math.min(result, value);
            return result;
        }

        private static int max(int[] first, int[] second) {
            int result = Integer.MIN_VALUE;
            for (int value : first) result = Math.max(result, value);
            for (int value : second) result = Math.max(result, value);
            return result;
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
            // 关闭阶段不应阻止前台服务继续释放其余 Android 资源。
        }
        if (layout != null)
            layout.close();
    }
}
