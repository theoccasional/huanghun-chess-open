package com.pikafish.chess.ui

/**
 * 棋盘装饰的共享几何：楚河汉界断开、九宫米字、炮位与兵卒位标记。
 *
 * <p>坐标一律使用“格”为单位：x 为列单位（0..8），y 为行单位（0..9），
 * 行 0 在棋盘上方。Compose 与 View 两套绘制各自把格单位换算成像素。</p>
 */
internal object BoardDecor {
    /** 河界所在的相邻两行，行间的竖线只在 0、8 两条边线上连通。 */
    const val RIVER_TOP_ROW = 4
    const val RIVER_BOTTOM_ROW = 5

    /** 九宫左右列与上下两座宫的行区间。 */
    const val PALACE_LEFT_FILE = 3
    const val PALACE_RIGHT_FILE = 5
    val BLACK_PALACE_ROWS = 0..2
    val RED_PALACE_ROWS = 7..9

    /**
     * 定位标记的折线尺寸（单位：格）。
     *
     * <p>角点紧贴交叉点，两臂朝外张开，与传统棋盘的“炮位/兵卒位”一致：
     * 四个直角正对交叉点，而不是围成一个方框。</p>
     */
    const val MARKER_GAP = 0.05f
    const val MARKER_ARM = 0.19f

    data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    /** 炮位：双方第二横线的左右两肋。 */
    val CANNON_POINTS = listOf(1 to 2, 7 to 2, 1 to 7, 7 to 7)

    /** 兵卒位：双方第四横线的五个起点。 */
    val PAWN_POINTS = listOf(0 to 3, 2 to 3, 4 to 3, 6 to 3, 8 to 3, 0 to 6, 2 to 6, 4 to 6, 6 to 6, 8 to 6)

    /** 竖向格线；河界处断开，边线贯通。 */
    fun fileLines(): List<Segment> {
        val lines = ArrayList<Segment>(11)
        for (file in 0..8) {
            if (file == 0 || file == 8) {
                lines.add(Segment(file.toFloat(), 0f, file.toFloat(), 9f))
            } else {
                lines.add(Segment(file.toFloat(), 0f, file.toFloat(), RIVER_TOP_ROW.toFloat()))
                lines.add(Segment(file.toFloat(), RIVER_BOTTOM_ROW.toFloat(), file.toFloat(), 9f))
            }
        }
        return lines
    }

    /** 横向格线：十条贯通。 */
    fun rankLines(): List<Segment> = (0..9).map { Segment(0f, it.toFloat(), 8f, it.toFloat()) }

    /** 九宫米字：两座宫各两条对角线。 */
    fun palaceLines(): List<Segment> = listOf(
        Segment(3f, 0f, 5f, 2f), Segment(5f, 0f, 3f, 2f),
        Segment(3f, 7f, 5f, 9f), Segment(5f, 7f, 3f, 9f),
    )

    /**
     * 交叉点定位标记：四个直角折线，直角顶点朝向该交叉点，两臂向外张开。
     * 左右边线上的点只画内侧两角。
     */
    fun markerSegments(file: Int, row: Int, gap: Float, arm: Float): List<Segment> {
        val x = file.toFloat()
        val y = row.toFloat()
        val segments = ArrayList<Segment>(8)
        fun corner(cx: Float, cy: Float, horizontalSign: Float, verticalSign: Float) {
            segments.add(Segment(cx, cy, cx + arm * horizontalSign, cy))
            segments.add(Segment(cx, cy, cx, cy + arm * verticalSign))
        }
        if (file > 0) {
            corner(x - gap, y - gap, -1f, -1f)
            corner(x - gap, y + gap, -1f, 1f)
        }
        if (file < 8) {
            corner(x + gap, y - gap, 1f, -1f)
            corner(x + gap, y + gap, 1f, 1f)
        }
        return segments
    }
}