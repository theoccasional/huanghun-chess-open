package com.yk.xiangqi.engine

import android.os.Handler
import android.os.Looper
import com.yk.xiangqi.GameRuntime
import com.yk.xiangqi.core.GameAction
import com.yk.xiangqi.core.GameResult
import com.yk.xiangqi.core.Move
import com.yk.xiangqi.core.Side
import java.io.File

/**
 * 双引擎比赛：两个独立 UCI 进程分执红黑轮流走子，直到分出胜负。
 *
 * <p>所有落子都通过 {@link GameRuntime} 归约，因此棋盘、连线与音效都按普通走子处理。
 * 比赛期间调用方必须暂停自身用于界面分析的引擎，避免多个进程争用同一枚棋子。</p>
 */
class EngineMatch(
    private val red: Participant,
    private val black: Participant,
    private val runtime: GameRuntime,
    private val onStatus: (String) -> Unit,
    private val onFinished: (String) -> Unit,
) {
    /** 参赛一方：引擎可执行文件、配置与逐步搜索限制。 */
    data class Participant(
        val label: String,
        val executable: File,
        val workingDir: File,
        val network: File?,
        val threads: Int,
        val hashMb: Int,
        val options: Map<String, String>,
        val goParams: () -> GoParams,
    )

    private companion object {
        /** 远超正常对局长度的保护上限，防止任何一方卡死时无限对弈。 */
        const val MAX_PLIES = 600
    }

    private val handler = Handler(Looper.getMainLooper())
    private val redRunner = Runner(Side.RED, red)
    private val blackRunner = Runner(Side.BLACK, black)
    private var started = false
    private var stopped = false
    private var finished = false
    private var released = false
    private var plies = 0

    /** 参赛一方的独立引擎进程；回调都在该引擎的工作线程发出，再切回主线程处理。 */
    private inner class Runner(val side: Side, val participant: Participant) : UciEngine.Listener {
        val engine = UciEngine(this)
        var expected = -1L
        var ready = false

        fun shutdown() = engine.close()

        /** 请求本方走子；同一时刻每方最多只有一个未完成搜索。 */
        fun request() {
            expected = engine.go(runtime.state(), participant.goParams())
        }

        override fun onUciOptions(options: List<UciOption>) {
            val values = linkedMapOf(
                "Threads" to participant.threads.toString(),
                "Hash" to participant.hashMb.toString(),
            )
            values.putAll(participant.options)
            participant.network?.let { network ->
                options.firstOrNull { it.name.equals("EvalFile", true) }
                    ?.let { values[it.name] = network.absolutePath }
            }
            engine.configure(values)
        }

        override fun onEngineIdentity(name: String?, author: String?) {
            handler.post { if (!stopped) onStatus("${participant.label}：${name ?: "引擎未上报版本"}") }
        }

        override fun onEngineFailed(message: String) {
            handler.post { fail("${participant.label} $message") }
        }

        override fun onReady() {
            handler.post {
                if (stopped) return@post
                ready = true
                onStatus("${participant.label} 已就绪")
                maybeBegin()
            }
        }

        override fun onInfo(searchId: Long, info: ThinkingInfo) = Unit

        override fun onBestMove(searchId: Long, info: BestMoveInfo) {
            handler.post {
                if (stopped || finished || searchId != expected) return@post
                play(side, info.bestMove)
            }
        }

        override fun onSearchFailed(searchId: Long, message: String) {
            handler.post {
                if (!stopped && searchId == expected) fail("${participant.label} 搜索失败：$message")
            }
        }
    }

    /** 启动两个引擎；双方握手配置完成后再由红方走出第一步。 */
    fun start() {
        handler.post {
            if (stopped) return@post
            onStatus("正在启动比赛引擎…")
            redRunner.engine.start(red.executable, red.workingDir)
            blackRunner.engine.start(black.executable, black.workingDir)
        }
    }

    /** 由调用方在主线程请求终止比赛；之后不再产生任何落子或结果回调。 */
    fun stop() {
        handler.post {
            if (stopped) return@post
            stopped = true
            redRunner.shutdown()
            blackRunner.shutdown()
        }
    }

    private fun maybeBegin() {
        if (started || stopped || !redRunner.ready || !blackRunner.ready) return
        started = true
        onStatus("比赛开始：${red.label} 对 ${black.label}")
        request(Side.RED)
    }

    private fun request(side: Side) {
        if (stopped || finished) return
        if (plies >= MAX_PLIES) {
            finish("步数已达上限，判和")
            return
        }
        if (side == Side.RED) redRunner.request() else blackRunner.request()
    }

    /** 引擎给出着法：校验后落子，并裁决是否已经分出胜负。 */
    private fun play(side: Side, uci: String) {
        if (!Move.isUciCoordinate(uci)) {
            fail("引擎返回无效着法：$uci")
            return
        }
        val move = Move.parse(uci)
        if (!runtime.state().position().isLegal(move)) {
            fail("引擎返回非法着法：$uci")
            return
        }
        runtime.reduce(GameAction.Play(move), GameRuntime.Origin.LOCAL)
        plies++
        val result = runtime.state().computeGameResult()
        if (result != GameResult.UNDECIDED) {
            finish(resultText(result))
            return
        }
        onStatus("第 ${runtime.state().moves().size} 手 ${uci}（${runnerLabel(side)}）")
        request(side.opposite())
    }

    private fun runnerLabel(side: Side): String = if (side == Side.RED) red.label else black.label

    /** 结果文本同时给出胜方与所用引擎，便于分辨是哪一个引擎赢的。 */
    private fun resultText(result: GameResult): String = when (result) {
        GameResult.RED_WON -> "红方胜：${red.label}"
        GameResult.BLACK_WON -> "黑方胜：${black.label}"
        GameResult.DRAW -> "和棋"
        else -> "未分胜负"
    }

    private fun finish(text: String) {
        if (finished || stopped) return
        finished = true
        onFinished(text)
        shutdown()
    }

    private fun fail(message: String) {
        if (finished || stopped) return
        finished = true
        onFinished("比赛中止：$message")
        shutdown()
    }

    /** 两个引擎进程只关闭一次；UciEngine 关闭后不可再提交任务。 */
    private fun shutdown() {
        if (released) return
        released = true
        redRunner.shutdown()
        blackRunner.shutdown()
    }
}