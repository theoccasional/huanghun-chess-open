package com.pikafish.chess.qipu

/** 轻量棋局包装（批量工具专用；与主 App 的 QipuGame 同构，去掉 Android 依赖） */
class QipuGame(
    private val meta: CblDecoder.CblGame,
    private val fullLoader: (() -> CblDecoder.CblGame)? = null,
    val isFenPuzzle: Boolean = false
) {
    val cblGame: CblDecoder.CblGame by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (fullLoader != null && meta.movesUci.isEmpty()) {
            try { fullLoader.invoke() } catch (e: Exception) { meta }
        } else meta
    }

    fun meta(): CblDecoder.CblGame = meta
    fun title(): String = meta.title()
    fun pgn(): String = cblGame.toPgn()
}
