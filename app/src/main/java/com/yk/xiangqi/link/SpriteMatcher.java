package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Board;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话级自学习棋子模板匹配器。
 *
 * <p>校准/同步确认出合法棋盘后，把每个已占格的「逐格 z-score 局部对比归一」笔画信号存成模板；
 * 后续帧中当逐格+整盘融合概率含糊（次优标签紧咬最优标签）时，用模板做一次形状校验，纠正
 * “黑车读成黑卒”这类兵种混淆。模板直接取自当前屏幕，天然适配任意平台的棋子风格、简体/繁体
 * 字形与横竖排布，不需要为每个平台重新训练或追加素材。</p>
 *
 * <p>模板与查询都来自同一份增强输入（{@code enhancedCells}），度量是掩码归一化互相关（NCC），
 * 先各自在掩码区域内去均值再归一化，能抹掉光照、对比与棋盘底色差异，只比较笔画形状本身。</p>
 */
final class SpriteMatcher {
    static final int CELL = 48;         // ONNX 逐格输入边长
    static final int GLYPH = 24;        // 模板边长（2×2 降采样）
    static final int LABELS = 15;
    private static final int MAX_PER_LABEL = 4;
    /** 与既有模板足够相似（NCC 高于该值）时不再新增，避免同一字形存一堆重复模板。 */
    private static final float NOVEL_TEMPLATE_NCC = 0.97f;
    /** 融合概率含糊判定：最优与次优的差距小于该值，或最优本身低于该值。 */
    private static final float AMBIGUOUS_GAP = 0.15f;
    private static final float AMBIGUOUS_MIN = 0.45f;
    /** 学习模板时要求该格融合概率足够强，防止把 ONNX 的自信误判当成模板固化下来。 */
    private static final float LEARN_MIN_PROB = 0.62f;
    /** 校验翻转判定：模板选中类的最高 NCC 至少高出原标签自身模板那么多，才敢翻转。 */
    private static final float REFINE_EDGE = 0.06f;
    private static final float REFINE_MIN_SCORE = 0.40f;

    /** 模板按 ObservedBoard 标签 1..14 存放，每类最多 MAX_PER_LABEL 个 24×24 灰度笔画。 */
    private final List<float[]>[] templates = new List[LABELS];
    /** 中央圆盘掩码：只保留字形区域，剔除格子边线、棋盘背景。 */
    private final float[] mask = new float[GLYPH * GLYPH];

    SpriteMatcher() {
        for (int label = 0; label < LABELS; label++)
            templates[label] = new ArrayList<>();
        int cx = (GLYPH - 1) / 2;
        int cy = (GLYPH - 1) / 2;
        float radius = GLYPH * 0.40f;
        for (int y = 0; y < GLYPH; y++)
            for (int x = 0; x < GLYPH; x++) {
                float dx = x - cx;
                float dy = y - cy;
                mask[y * GLYPH + x] = dx * dx + dy * dy <= radius * radius ? 1f : 0f;
            }
    }

    void reset() {
        for (int label = 0; label < LABELS; label++)
            templates[label].clear();
    }

    boolean readyFor(int label) {
        return label >= 1 && label <= 14 && !templates[label].isEmpty();
    }

    /** 是否已有任何模板：仅在校准学习过之后，逐帧校验才有意义。 */
    boolean hasTemplates() {
        for (int label = 1; label < LABELS; label++)
            if (!templates[label].isEmpty())
                return true;
        return false;
    }

    /**
     * 从一块已确认的棋盘学习模板。只吸收融合概率较强的格，避免把模型自信但错误的
     * 兵种当成模板；每类最多保留 {@link #MAX_PER_LABEL} 个互不相似的字形。
     */
    void learn(ObservedBoard board, float[] fusedProbabilities, float[] enhancedCells) {
        for (int square = 0; square < Board.SQUARES; square++) {
            int label = board.at(square);
            if (label == 0 || !confident(fusedProbabilities, square, label))
                continue;
            float[] glyph = cellGlyph(enhancedCells, square);
            List<float[]> list = templates[label];
            float best = -2f;
            for (float[] template : list)
                best = Math.max(best, ncc(template, glyph));
            if (list.size() < MAX_PER_LABEL) {
                if (best < NOVEL_TEMPLATE_NCC)
                    list.add(glyph);
            } else if (best < NOVEL_TEMPLATE_NCC) {
                // 池满：换掉与新增字形最不相似的那一个。
                int worst = 0;
                float worstScore = Float.MAX_VALUE;
                for (int index = 0; index < list.size(); index++) {
                    float score = ncc(list.get(index), glyph);
                    if (score < worstScore) {
                        worstScore = score;
                        worst = index;
                    }
                }
                list.set(worst, glyph);
            }
        }
    }

    /**
     * 校验并纠正含糊格：返回按模板修正后的棋盘；没有可纠正的格时原样返回。
     * 空格不参与校验（模板没有“空”类），兵种混淆只会发生在同一侧内部。
     */
    ObservedBoard refine(ObservedBoard board, float[] fusedProbabilities, float[] enhancedCells) {
        byte[] cells = null;
        for (int square = 0; square < Board.SQUARES; square++) {
            int label = board.at(square);
            if (label == 0 || !ambiguous(fusedProbabilities, square))
                continue;
            float[] glyph = cellGlyph(enhancedCells, square);
            int corrected = verify(label, glyph);
            if (corrected != label) {
                if (cells == null)
                    cells = boardCells(board);
                cells[square] = (byte) corrected;
            }
        }
        return cells == null ? board : new ObservedBoard(cells);
    }

    /** 取某格增强输入中的中央 24×24 字形：先 2×2 块均值降采样到 24×24，再平均三通道。 */
    private static float[] cellGlyph(float[] enhancedCells, int square) {
        int base = square * 3 * CELL * CELL;
        float[] glyph = new float[GLYPH * GLYPH];
        for (int gy = 0; gy < GLYPH; gy++)
            for (int gx = 0; gx < GLYPH; gx++) {
                float sum = 0f;
                for (int channel = 0; channel < 3; channel++) {
                    int cellBase = base + channel * CELL * CELL;
                    int y0 = gy * 2;
                    int x0 = gx * 2;
                    sum += (enhancedCells[cellBase + y0 * CELL + x0]
                        + enhancedCells[cellBase + y0 * CELL + x0 + 1]
                        + enhancedCells[cellBase + (y0 + 1) * CELL + x0]
                        + enhancedCells[cellBase + (y0 + 1) * CELL + x0 + 1]) / 4f;
                }
                glyph[gy * GLYPH + gx] = sum / 3f;
            }
        return glyph;
    }

    /** 原标签与候选标签各自模板库的最高 NCC；原标签无模板时不翻转。 */
    private int verify(int label, float[] glyph) {
        if (!readyFor(label))
            return label;
        float original = maxNcc(templates[label], glyph);
        int best = label;
        float bestScore = original;
        int from = label <= 7 ? 1 : 8;
        int to = label <= 7 ? 7 : 14;
        for (int candidate = from; candidate <= to; candidate++) {
            if (candidate == label)
                continue;
            List<float[]> pool = templates[candidate];
            if (pool.isEmpty())
                continue;
            float score = maxNcc(pool, glyph);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best != label && bestScore - original >= REFINE_EDGE && bestScore >= REFINE_MIN_SCORE
            ? best : label;
    }

    private float maxNcc(List<float[]> pool, float[] glyph) {
        float best = -2f;
        for (float[] template : pool)
            best = Math.max(best, ncc(template, glyph));
        return best;
    }

    private static boolean confident(float[] probabilities, int square, int label) {
        return probabilities[square * LABELS + label] >= LEARN_MIN_PROB;
    }

    private static boolean ambiguous(float[] probabilities, int square) {
        int offset = square * LABELS;
        int best = 0;
        for (int label = 1; label < LABELS; label++)
            if (probabilities[offset + label] > probabilities[offset + best])
                best = label;
        int second = best == 0 ? 1 : 0;
        for (int label = 0; label < LABELS; label++) {
            if (label == best)
                continue;
            if (probabilities[offset + label] > probabilities[offset + second])
                second = label;
        }
        float top = probabilities[offset + best];
        float gap = top - probabilities[offset + second];
        return gap < AMBIGUOUS_GAP || top < AMBIGUOUS_MIN;
    }

    private static byte[] boardCells(ObservedBoard board) {
        byte[] cells = new byte[Board.SQUARES];
        for (int square = 0; square < Board.SQUARES; square++)
            cells[square] = board.at(square);
        return cells;
    }

    /**
     * 掩码归一化互相关：模板与查询各自在掩码区域内去均值、归一化后再点积。
     * 增强输入已经做过整格 z-score，但掩码区域均值并不为零，仍需按掩码重新去均值。
     */
    private float ncc(float[] template, float[] glyph) {
        float masked = 0f;
        for (int index = 0; index < mask.length; index++)
            masked += mask[index];
        float meanTemplate = 0f;
        float meanGlyph = 0f;
        for (int index = 0; index < mask.length; index++) {
            if (mask[index] != 0f) {
                meanTemplate += template[index];
                meanGlyph += glyph[index];
            }
        }
        meanTemplate /= masked;
        meanGlyph /= masked;
        float numerator = 0f;
        float templateEnergy = 0f;
        float glyphEnergy = 0f;
        for (int index = 0; index < mask.length; index++) {
            if (mask[index] == 0f)
                continue;
            float dt = template[index] - meanTemplate;
            float dg = glyph[index] - meanGlyph;
            numerator += dt * dg;
            templateEnergy += dt * dt;
            glyphEnergy += dg * dg;
        }
        if (templateEnergy < 1e-6f || glyphEnergy < 1e-6f)
            return 0f;
        return numerator / (float) Math.sqrt(templateEnergy * glyphEnergy);
    }
}
