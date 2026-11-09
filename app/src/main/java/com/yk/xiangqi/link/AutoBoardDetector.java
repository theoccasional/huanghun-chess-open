package com.yk.xiangqi.link;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * 自动棋盘检测器：从整张屏幕截图自动定位 9×10 棋盘几何，免去用户手动框选。
 *
 * <p>采用<b>网格梳齿周期匹配法</b>。象棋棋盘的本质是一组<b>等距平行线</b>：横向 9 条竖线、
 * 纵向 10 条横线，格距相等（格宽≈格高）。于是把整屏梯度分别沿行、列投影，再拿
 * 「9 条等距竖线 / 10 条等距横线」这把梳齿去和投影做匹配，匹配度最高的一组便是棋盘。</p>
 *
 * <p>为什么换成梳齿法：此前的<b>边缘密度投影法</b>只找「最长的一段高密度区间」，没有任何
 * 周期约束。屏幕上按钮、文字、本应用自己的悬浮面板梯度同样很高，于是小棋盘、竞品棋盘这些
 * 反倒被旁边的 UI 盖过——这正是「免框选把自家悬浮窗当棋盘认」的根因。梳齿法把「等距周期性」
 * 这一棋盘独有的硬约束摆上台面：没有周期结构的区域，梯度再高也拼不过真正格距一致的棋盘。</p>
 *
 * <p>另外用一个稳定的先验收窄搜索：手机竖屏象棋 App 的棋盘<b>水平居中</b>。列方向只在屏幕
 * 中心带（±{@link #CENTER_FRACTION}）内搜索，既快又不会被左右两侧的 UI 带偏；行方向不设限，
 * 棋盘偏上偏下都能跟上。</p>
 *
 * <p>若梳齿匹配未命中（整屏几乎无周期结构，例如极简启动页），回退到中心区域估算：
 * 棋盘总在屏幕中心，取屏幕中心 80% 区域作为棋盘几何。</p>
 */
final class AutoBoardDetector {
    private static final String TAG = "XiangqiLink";
    private static final int FILES = BoardGeometry.FILES;   // 9 列
    private static final int RANKS = BoardGeometry.RANKS;   // 10 行
    /**
     * 分析用缩图宽度。必须足够大：缩得太狠会把棋盘的细网格线抹平，只留下大块 UI 的
     * 粗边缘，梳齿反而会锁到错误周期上（实测 200px 会误锁、360px 稳定命中）。
     */
    private static final int ANALYSIS_WIDTH = 360;
    /** 梳齿峰值取样半径：投影在峰值 ±1 像素内取最大，抵消缩放取整抖动。 */
    private static final int LINE_RADIUS = 1;
    /** 梳齿惩罚系数：命中线均值减去 {@code PENALTY ×} 半格中场均值，压制「半周期」假象。 */
    private static final float PENALTY = 0.5f;
    /** 列方向只在屏幕中心 ±此比例内搜索（棋盘水平居中的先验）。 */
    private static final float CENTER_FRACTION = 0.18f;
    /** 格距搜索步长（缩图像素）。 */
    private static final float STEP_QUANTUM = 0.25f;
    /**
     * 最小格距下限（占缩图宽度的比例，除以 8 格）：棋盘整体最小约 {@code 8×} 该比例。
     *
     * <p>取 0.10 表示能认到「棋盘宽度约为屏幕 10%」的小棋盘。此前取 0.28，等于
     * 棋盘窄于屏幕 28% 就搜不到——这正是「小棋盘识别不了」的根因。
     * 实测把它放宽到 0.10：31 张历史截图结果完全不变（大棋盘本就以更大格距取胜），
     * 而 12%~30% 宽的小棋盘从「认不出」变为「稳定命中」，是纯增量改进。</p>
     */
    private static final float MIN_STEP_FRACTION = 0.10f;
    /** 格距的绝对下限（缩图像素），兜住极窄棋盘又不至于退化成逐像素噪声。 */
    private static final float MIN_STEP_PIXELS = 4.0f;

    private AutoBoardDetector() {}

    static final class DetectResult {
        final BoardGeometry geometry;
        final String detail;

        private DetectResult(BoardGeometry g, String d) {
            geometry = g; detail = d;
        }

        boolean success() { return geometry != null; }
    }

    static DetectResult detect(Frame frame) {
        return detect(frame, true);
    }

    /**
     * @param allowCenterFallback 梳齿匹配未命中时，是否回退到“屏幕中心区域估算”。
     *        手动点「校准」传 true——保证任何情况下都能给出一个可用几何；
     *        「连线后自动识别」传 false——必须真正检出棋盘才采信，免得把本应用自己的
     *        界面当成棋盘，糊里糊涂就自动上手。
     */
    static DetectResult detect(Frame frame, boolean allowCenterFallback) {
        if (frame == null)
            return new DetectResult(null, "帧为空");
        Bitmap bitmap = frame.toBitmap();
        if (bitmap == null)
            return new DetectResult(null, "帧转换失败");
        try {
            return detectOnBitmap(bitmap, allowCenterFallback);
        } finally {
            bitmap.recycle();
        }
    }

    /** 梳齿匹配未命中时的中心区域兜底；{@code allowCenterFallback=false} 时不采信。 */
    private static DetectResult centerFallback(int origW, int origH, String why,
                                               boolean allowCenterFallback) {
        if (!allowCenterFallback)
            return new DetectResult(null, why + "（未启用中心兜底）");
        float cLeft = origW * 0.10f;
        float cRight = origW * 0.90f;
        float cTop = origH * 0.15f;
        float cBottom = origH * 0.85f;
        try {
            BoardGeometry g = new BoardGeometry(cLeft, cTop, cRight, cBottom);
            return new DetectResult(g, why + "，回退中心区域估算 "
                + Math.round(cLeft) + "," + Math.round(cTop)
                + " – " + Math.round(cRight) + "," + Math.round(cBottom));
        } catch (IllegalArgumentException e) {
            return new DetectResult(null, "中心估算异常: " + e.getMessage());
        }
    }

    private static DetectResult detectOnBitmap(Bitmap source, boolean allowCenterFallback) {
        int origW = source.getWidth();
        int origH = source.getHeight();
        if (origW <= 0 || origH <= 0)
            return new DetectResult(null, "帧尺寸无效");

        int smallW = Math.min(ANALYSIS_WIDTH, origW);
        float scale = (float) smallW / origW;
        int smallH = Math.max(1, Math.round(origH * scale));
        Bitmap small = Bitmap.createScaledBitmap(source, smallW, smallH, true);

        try {
            int[] pixels = new int[smallW * smallH];
            small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH);
            int[] gray = new int[smallW * smallH];
            for (int i = 0; i < pixels.length; i++) {
                int px = pixels[i];
                gray[i] = (((px >> 16) & 0xFF) * 299 + ((px >> 8) & 0xFF) * 587 + (px & 0xFF) * 114) / 1000;
            }

            // 梯度投影：竖线（横向变化 gx）累到列投影，横线（纵向变化 gy）累到行投影。
            int[] colProj = new int[smallW];
            int[] rowProj = new int[smallH];
            for (int y = 1; y < smallH - 1; y++) {
                int base = y * smallW;
                for (int x = 1; x < smallW - 1; x++) {
                    int idx = base + x;
                    int gx = Math.abs(gray[idx + 1] - gray[idx - 1]);
                    int gy = Math.abs(gray[idx + smallW] - gray[idx - smallW]);
                    colProj[x] += gx;
                    rowProj[y] += gy;
                }
            }

            // 格距搜索范围：棋盘 8 格宽、9 格高，且整体不超过缩图的约 93%。
            float smax = Math.min(smallW / (FILES - 1 + 0.6f), smallH / (RANKS - 1 + 0.6f));
            float smin = Math.max(MIN_STEP_PIXELS, MIN_STEP_FRACTION * smallW / (FILES - 1));
            if (smin > smax) {
                Log.w(TAG, "梳齿搜索区间无效 smin=" + smin + " smax=" + smax);
                return centerFallback(origW, origH, "梳齿搜索区间无效", allowCenterFallback);
            }

            float bestScore = Float.NEGATIVE_INFINITY;
            float bestStep = 0f;
            int bestCol = -1, bestRow = -1;
            boolean found = false;

            for (float step = smin; step <= smax + 1e-4f; step += STEP_QUANTUM) {
                int colSpan = Math.round((FILES - 1) * step);
                int rowSpan = Math.round((RANKS - 1) * step);
                int colMax = smallW - 1 - colSpan;
                int rowMax = smallH - 1 - rowSpan;
                if (colMax < 0 || rowMax < 0)
                    continue;

                // 列方向：只在“棋盘水平居中”对应的偏移带内搜。
                int centeredCol = Math.round(smallW / 2f - colSpan / 2f);
                int winLo = Math.max(0, Math.round(centeredCol - CENTER_FRACTION * smallW));
                int winHi = Math.min(colMax, Math.round(centeredCol + CENTER_FRACTION * smallW));
                if (winLo > winHi)
                    continue;

                float colScore = Float.NEGATIVE_INFINITY;
                int colOff = -1;
                for (int o = winLo; o <= winHi; o++) {
                    float sc = scoreAxis(colProj, FILES, step, o);
                    if (sc > colScore) {
                        colScore = sc;
                        colOff = o;
                    }
                }

                // 行方向：整幅高度内搜，棋盘偏上偏下都不设限。
                float rowScore = Float.NEGATIVE_INFINITY;
                int rowOff = 0;
                for (int o = 0; o <= rowMax; o++) {
                    float sc = scoreAxis(rowProj, RANKS, step, o);
                    if (sc > rowScore) {
                        rowScore = sc;
                        rowOff = o;
                    }
                }

                float total = colScore + rowScore;
                if (total > bestScore) {
                    bestScore = total;
                    bestStep = step;
                    bestCol = colOff;
                    bestRow = rowOff;
                    found = true;
                }
            }

            if (!found || bestCol < 0) {
                Log.w(TAG, "梳齿匹配未命中");
                return centerFallback(origW, origH, "梳齿匹配未命中", allowCenterFallback);
            }

            // 映射回原图坐标。梳齿命中的就是网格交叉线本身，直接就是四条参考线，无需内缩。
            float scaleX = origW / (float) smallW;
            float scaleY = origH / (float) smallH;
            float left = bestCol * scaleX;
            float top = bestRow * scaleY;
            float right = (bestCol + (FILES - 1) * bestStep) * scaleX;
            float bottom = (bestRow + (RANKS - 1) * bestStep) * scaleY;

            try {
                BoardGeometry g = new BoardGeometry(left, top, right, bottom);
                return new DetectResult(g,
                    "棋盘 " + Math.round(left) + "," + Math.round(top)
                    + " – " + Math.round(right) + "," + Math.round(bottom)
                    + "（梳齿 格距" + String.format("%.1f", bestStep * scaleX)
                    + " 打分" + Math.round(bestScore) + "）");
            } catch (IllegalArgumentException e) {
                return centerFallback(origW, origH, "梳齿几何异常: " + e.getMessage(),
                    allowCenterFallback);
            }
        } finally {
            small.recycle();
        }
    }

    /**
     * 单轴梳齿打分：{@code n} 条等距线处的投影均值，减去 {@code PENALTY ×} 半格中场均值。
     * 命中线取峰值附近最大值，中场惩罚压制“把格距取成一半”的假匹配。
     */
    private static float scoreAxis(int[] proj, int lines, float step, int offset) {
        float on = 0f;
        for (int i = 0; i < lines; i++)
            on += windowMax(proj, Math.round(offset + i * step));
        on /= lines;

        float off = 0f;
        for (int i = 0; i < lines - 1; i++)
            off += windowMax(proj, Math.round(offset + (i + 0.5f) * step));
        off /= (lines - 1);
        return on - PENALTY * off;
    }

    /** 投影数组在 {@code center ± LINE_RADIUS} 内的最大值（越界自动收边）。 */
    private static float windowMax(int[] proj, int center) {
        int lo = Math.max(0, center - LINE_RADIUS);
        int hi = Math.min(proj.length - 1, center + LINE_RADIUS);
        int best = 0;
        for (int i = lo; i <= hi; i++)
            if (proj[i] > best)
                best = proj[i];
        return best;
    }
}