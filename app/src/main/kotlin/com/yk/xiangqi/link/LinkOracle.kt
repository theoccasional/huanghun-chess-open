package com.yk.xiangqi.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pikafish.chess.engine.JniEngine
import com.pikafish.chess.engine.SandboxOracle
import com.pikafish.chess.model.Board as HhBoard
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position as HhPosition
import com.pikafish.chess.model.RepetitionRule
import com.yk.xiangqi.GameRuntime
import com.yk.xiangqi.Settings
import com.yk.xiangqi.core.GameState
import com.yk.xiangqi.core.Move
import com.yk.xiangqi.core.Position
import com.yk.xiangqi.core.Side
import com.yk.xiangqi.notation.Notation
import java.util.Locale

/**
 * ★★★ 连线自带的「沙盒询招」通道（观棋/半自动连线的算棋主体）★★★
 *
 * <p>此前连线的出招完全依赖主界面 {@code GameViewModel}：引擎进程归主界面持有，
 * 箭头也由主界面引擎产出。一旦主界面被系统回收、或它那条「开局库异步查询 + 看门狗」
 * 把自己卡成空转，连线就既不出招、也不刷箭头，非得手点棋子＋校准才勉强恢复。</p>
 *
 * <p>这条通道把算棋整个搬进连线自身：直接复用黄昏象棋主对弈「一次一问、问完即消、
 * 绝不驻留」的沙盒询招纪律（{@link SandboxOracle}）——它与主界面引擎互不相干、
 * 互不抢锁、也不随主界面存活与否而消失。连线期间它就是箭头的唯一权威来源。</p>
 *
 * <p>纪律：</p>
 * <ul>
 *   <li>只在<b>轮到指定方走子</b>时出招；对方面前一律不分析、不虚构局面，只清掉旧箭头等待。</li>
 *   <li>一次一问：同一局面一轮询招未收，绝不叠第二问；收招后再按最新局面复查。</li>
 *   <li>超时/未就绪都会在有限时间内空手而归，随后按节拍自动重问——绝不无限等、绝不卡死。</li>
 * </ul>
 */
class LinkOracle(
    context: Context,
    private val listener: Listener,
) {
    fun interface Listener {
        /** 本通道算出的主变箭头（0~2 步）与思考细节，请渲染到小棋盘与盖盘。 */
        fun onOracleUpdate(arrows: List<Move>, analysis: GameRuntime.Analysis)
    }

    private companion object {
        private const val TAG = "LinkOracle"
        /**
         * 同一局面两次询招的最小间隔：结果已出、局面未变时，这段时间内不重复问。
         * 看门狗按更快的节拍敲门，真正落地的节奏因此约为「一回合一次」，既不空转也不漏拍。
         */
        private const val REFRESH_INTERVAL_MS = 2500L
    }

    private val appContext = context.applicationContext
    private val settings = Settings(appContext)
    private val main = Handler(Looper.getMainLooper())

    /**
     * 亚洲象棋联合会棋例判定器：与主界面 {@code ChessGame} 用的是同一个类，一字不改。
     * 连线用它提前把「会走回近历史、又属照将/捉子」的着法从候选里剔掉，
     * 逼引擎把计算花在真正有进展的正招上，绝不原地磨棋盘。
     */
    private val repetitionRule = RepetitionRule()

    @Volatile private var running = false

    /** 最新主变箭头与思考细节；连线期间它盖过主界面引擎的输出。 */
    @Volatile private var arrows: List<Move> = emptyList()
    @Volatile private var analysis: GameRuntime.Analysis = GameRuntime.Analysis.EMPTY

    // 以下字段只在主线程读写。
    private var latestState: GameState? = null
    private var latestAssigned: Side? = null
    /** 是否正处在「对方该走、我方等待」的窗口，避免同一窗口反复清空箭头。 */
    private var awaiting = false
    private var inFlight = false
    private var inFlightFen: String? = null
    private var answeredFen: String? = null
    private var answeredAtMs = 0L

    // 一轮询招的中间产物（onThinking 逐条刷新，收招时兜底）。
    private var pendingMoveUci: String? = null
    private var pendingScore = 0
    private var pendingDepth = 0
    private var pendingNodes = 0L
    private var pendingNps = 0L
    private var pendingPv: List<String> = emptyList()

    /** 连线开始：拉起通道并预热沙盒引擎（冷启动握手放后台，免得首次询招现场加载权重卡住）。 */
    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                SandboxOracle.start(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "预热沙盒引擎异常", t)
            }
        }.apply { isDaemon = true; name = "link-oracle-warm" }.start()
        Log.i(TAG, "连线沙盒询招通道已启动")
    }

    /** 连线结束：停掉在途问题，清空箭头与思考细节。 */
    fun stop() {
        if (!running) return
        running = false
        main.post {
            inFlight = false
            arrows = emptyList()
            analysis = GameRuntime.Analysis.EMPTY
            try {
                SandboxOracle.stopAsk()
            } catch (_: Throwable) {
            }
        }
        Log.i(TAG, "连线沙盒询招通道已停止")
    }

    fun isRunning(): Boolean = running

    fun arrows(): List<Move> = arrows

    fun analysis(): GameRuntime.Analysis = analysis

    /**
     * 局面 / 执子方变化（含看门狗节拍）时由服务调用，任意线程皆可。
     * 只看最新一次请求：过时的请求会被后到的覆盖，绝不把旧局面当新局算。
     */
    fun request(state: GameState, assigned: Side?) {
        if (!running) return
        main.post {
            if (!running) return@post
            latestState = state
            latestAssigned = assigned
            evaluate()
        }
    }

    /** 核心裁决：此刻该不该出招、该按哪个局面出招。只在主线程执行。 */
    private fun evaluate() {
        val state = latestState
        if (!running || state == null) return
        if (state.isOver()) {
            publish(GameRuntime.Analysis.EMPTY, emptyList())
            return
        }
        val position = state.position()
        val side = position.sideToMove()
        val assigned = latestAssigned
        if (assigned != null && side != assigned) {
            // 还没轮到我们走：不分析、不虚构，清掉上一次的箭头，等对方落子后再算。
            if (!awaiting) {
                awaiting = true
                publish(GameRuntime.Analysis.EMPTY, emptyList())
            }
            return
        }
        awaiting = false
        if (inFlight) return
        val fen = position.toFen()
        val now = SystemClock.elapsedRealtime()
        if (fen == answeredFen && now - answeredAtMs < REFRESH_INTERVAL_MS) return
        ask(state, position, fen)
    }

    private fun ask(state: GameState, position: Position, fen: String) {
        val board = HhBoard().apply { loadFromFen(fen) }
        val (timeMs, depth) = searchParams()
        // ★ 棋规落地：与主界面同源的「提前排除磨棋盘走法」——把会走回近历史、又属照将/捉子的
        //   着法剔出候选，引擎自然会把「车将一下、再将一下」的往复换成两步到位的正招。
        val excludedUci = repetitionViolatingMoves(state)
        inFlight = true
        inFlightFen = fen
        pendingMoveUci = null
        pendingScore = 0
        pendingDepth = 0
        pendingNodes = 0L
        pendingNps = 0L
        pendingPv = emptyList()
        try {
            SandboxOracle.askMove(
                board = board,
                timeMs = timeMs,
                depth = depth,
                restrictToUci = null,
                excludedUci = excludedUci,
                onThinking = { d, _sd, sc, nodes, nps, pv ->
                    onThinking(fen, position, d, sc, nodes, nps, pv)
                },
                onResult = { move -> onResult(fen, position, move?.toUci()) },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "沙盒询招异常", t)
            finish(fen)
        }
    }

    private fun onThinking(
        fen: String, position: Position, depth: Int, score: Int, nodes: Long, nps: Long, pv: String,
    ) {
        // 过时的思考信息（上一局面的残余）一律丢弃。
        if (!inFlight || fen != inFlightFen) return
        pendingDepth = depth
        pendingScore = score
        pendingNodes = nodes
        pendingNps = nps
        pendingPv = pv.split(' ').filter { Move.isUciCoordinate(it) }
        pendingMoveUci = pendingPv.firstOrNull() ?: pendingMoveUci
        publish(
            buildAnalysis(position, score, depth, nodes, nps, pendingPv),
            arrowsOf(pendingPv),
        )
    }

    private fun onResult(fen: String, position: Position, moveUci: String?) {
        if (fen != inFlightFen) return
        if (moveUci != null && Move.isUciCoordinate(moveUci)) {
            if (pendingMoveUci == null) pendingMoveUci = moveUci
            // 引擎只吐 bestmove、没吐带 pv 的 info 时，至少把这一手标出来。
            if (pendingPv.isEmpty()) pendingPv = listOf(moveUci)
            publish(
                buildAnalysis(position, pendingScore, pendingDepth, pendingNodes, pendingNps, pendingPv),
                arrowsOf(pendingPv),
            )
        }
        finish(fen)
    }

    private fun finish(fen: String) {
        inFlight = false
        answeredFen = fen
        answeredAtMs = SystemClock.elapsedRealtime()
        // 询招期间局面又变了（对方接着走、或校准重建）：立刻按最新局面再来一轮。
        evaluate()
    }

    private fun arrowsOf(pv: List<String>): List<Move> =
        pv.take(2).mapNotNull { runCatching { Move.parse(it) }.getOrNull() }

    private fun publish(next: GameRuntime.Analysis, nextArrows: List<Move>) {
        arrows = nextArrows
        analysis = next
        listener.onOracleUpdate(nextArrows, next)
    }

    /** 把沙盒一行 info 投影成悬浮窗可直接显示的分值/深度/主变（分值统一换算到红方视角）。 */
    private fun buildAnalysis(
        position: Position, score: Int, depth: Int, nodes: Long, nps: Long, pv: List<String>,
    ): GameRuntime.Analysis {
        val side = position.sideToMove()
        val toRed = if (side == Side.RED) 1 else -1
        val red = score * toRed
        val mate = JniEngine.isMateScore(score)
        val scoreText = if (mate) {
            val plies = JniEngine.matePliesOf(score) ?: 1
            "绝杀 ${if (red > 0) "+" else "-"}$plies"
        } else {
            String.format(Locale.US, "%+.2f", red / 100.0)
        }
        val verdict = if (mate) {
            if (red > 0) "红方胜势" else "黑方胜势"
        } else when {
            red > 30 -> "红方占优"
            red < -30 -> "黑方占优"
            else -> "均势"
        }
        return GameRuntime.Analysis(
            scoreText,
            verdict,
            depth.toString(),
            null,
            nodes.toString(),
            nps.toString(),
            side,
            Notation.chineseVariation(position, pv),
            "主变",
        )
    }

    /**
     * ★ 棋规落地：AI 主动避免循环违规（长照 / 长捉）。
     *
     * <p>与主界面 {@code ChessGame.getRepetitionViolatingMoves} 同源同逻辑，只是把历史快照
     * 换成连线自身 {@link GameState} 的局面序列：只要走完这步会把局面送回近 16 步内的历史、
     * 且这步本身属照将或捉子（将帅兵卒长捉不犯例，不计），就提前把这步剔掉。这样
     * 「车将一下再把老将调回去」这种无用往复在第一步就被钳住，两步之内就能定位、随即变招。</p>
     *
     * <p>安全阀：若所有走法都被剔光（极端情形），放弃排除、交给引擎自由选择——宁可走一步
     * 也比无棋可走强。</p>
     */
    private fun repetitionViolatingMoves(state: GameState): Set<String> {
        val excluded = mutableSetOf<String>()
        val cursor = state.cursor()
        if (cursor < 8) return excluded  // 开局前几手不检查

        val boardFen = state.position().toFen()
        val board = HhBoard().apply { loadFromFen(boardFen) }
        val aiColor = board.currentPlayer
        val legalMoves = board.getLegalMoves(aiColor)
        if (legalMoves.isEmpty()) return excluded

        // 近窗口快照（最后 16 步）：只在这圈子里找循环，避免很旧的「复原局面」被误判成磨棋盘。
        val window = (maxOf(0, cursor - 15)..cursor).map { state.positionAt(it).toFen() }
        val currentKey = repetitionRule.positionKey(boardFen)
        val currentOcc = window.count { repetitionRule.positionKey(it) == currentKey }
        val attacksBefore = attackTargets(board, aiColor)

        for (move in legalMoves) {
            val uci = move.toUci()
            val tempBoard = HhBoard().apply { loadFromFen(boardFen) }
            tempBoard.makeMoveUnchecked(move)
            val afterKey = repetitionRule.positionKey(tempBoard.toFen())
            val afterOcc = window.count { repetitionRule.positionKey(it) == afterKey }

            // 走后局面一回近历史即介入排查；不回到历史 → 不构成循环，放行。
            if (currentOcc < 1 && afterOcc < 1) continue
            if (afterOcc < 1) continue

            val boardAfter = HhBoard().apply { loadFromFen(boardFen) }
            boardAfter.makeMoveUnchecked(move)
            val isCheck = boardAfter.isInCheck(boardAfter.currentPlayer)
            val newAttacks = attackTargets(boardAfter, aiColor) - attacksBefore
            val isChase = newAttacks.isNotEmpty() &&
                move.piece.type != PieceType.KING &&
                move.piece.type != PieceType.PAWN

            if (isCheck || isChase) {
                Log.d(TAG, "★ 连线提前排除磨棋盘走法: $uci (${if (isCheck) "照将" else "捉子"})")
                excluded.add(uci)
            }
        }

        if (excluded.size >= legalMoves.size) {
            Log.w(TAG, "★ 所有走法都会循环，放弃排除，交引擎自由选择")
            return emptySet()
        }
        return excluded
    }

    /** 某方能吃到的对方棋子位置（将/帅除外）——用于重复违规检测，与主界面同义。 */
    private fun attackTargets(board: HhBoard, attackerColor: PieceColor): Set<HhPosition> {
        val targets = mutableSetOf<HhPosition>()
        for (m in board.getLegalMoves(attackerColor)) {
            if (m.captured != null) {
                val captured = board.getPiece(m.to)
                if (captured != null && captured.type != PieceType.KING) targets.add(m.to)
            }
        }
        return targets
    }

    /**
     * 连线悬浮窗设的搜索上限：层数模式按层问、步时模式按秒问。
     * 未设上限或类型不认识时交给沙盒默认预算——一次一问、必然有出口，绝不无限思考。
     */
    private fun searchParams(): Pair<Long?, Int?> {
        val kind = settings.linkSearchKind()
        val value = settings.linkSearchValue()
        if (value <= 0L) return null to null
        return when (kind) {
            "DEPTH" -> null to value.toInt().coerceIn(1, 40)
            "MOVETIME" -> value.coerceIn(100L, 60_000L) to null
            // 沙盒按层/按秒问，节点模式折算成一段等价步时，规矩仍跟用户设置走。
            "NODES" -> value.coerceAtLeast(500L) to null
            else -> null to null
        }
    }
}
