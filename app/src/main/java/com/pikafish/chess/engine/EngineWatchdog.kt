package com.pikafish.chess.engine

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.pikafish.chess.model.PieceColor

/**
 * ★★★ 独立通道巡检引擎（EngineWatchdog）★★★
 *
 * 职责：专门监视"红色引擎 / 黑色引擎"的运行健康，与象棋搜索算法完全无关——
 * 它不参与任何走棋决策，只用一条独立的前台巡检线程周期性检查每台引擎
 * 是否真正存活、是否正常响应。一旦发现异常，立即走"抢救序列"，把引擎
 * 从卡死状态重新拉活。
 *
 * 为什么用独立的巡检线程而非引擎自带检测：
 *  - 引擎自己一旦卡死/崩溃，其内部的一切计时与自检都不可信（"自己不能
 *    当自己病情的医生"）。巡检器是独立的旁观者，只观测引擎外部可观测的
 *    存活信号，因此即使引擎内部死循环了，巡检器也能第一时间发现并接管。
 *
 * 抢救序列（对应"先切人脑释放、再切回引擎继续"的稳健做法，全程对外不暴露该动作）：
 *  1. 巡检发现某一方引擎"异常"（进程死了 / isReady 为 false / 该方引擎挂起）
 *  2. 立即请求上层复位该方"引擎思考"总开关（=释放死锁，等效于人脑接管）
 *  3. 真正重启该方引擎子进程（restartEngineFor）
 *  4. 重启成功后通知上层恢复 AI 走棋（等效于人脑切回引擎）
 *  5. 全程不在界面暴露"切人脑/切AI"字样，恢复后状态栏立即恢复正常。
 *
 * 心跳模型：
 *  - 每次走棋成功后上层调用 beat()，记录最新心跳时间；引擎无任何新输出时由
 *    JniEngine 内部硬超时兜底。巡检器另设"深呼吸"慢轮询：若引擎已就绪但本轮
 *    本轮没有发起任何搜索，巡检器不做干扰；若该方引擎应就绪却 isReady()==false，
 *    或其进程已死，则快速介入。
 */

/**
 * 巡检回调接口（由 ChessGame 实现，巡检器不直接触碰局面/引擎，保持独立）
 */
interface WatchdogHost {
    /** 当前是否正轮到某方为引擎（AI） */
    fun watchdogIsEngineTurn(color: PieceColor): Boolean
    /** 该方引擎是否就绪 */
    fun watchdogIsReady(color: PieceColor): Boolean
    /** 该方引擎是否正在搜索 */
    fun watchdogIsSearching(color: PieceColor): Boolean
    /**
     * ★ 该方引擎是否"挂起"应介入抢救（心跳检测核心）：
     * 引擎已就绪、轮到它走、但在搜索上一味超时仍未落子（步时到点没出棋）。
     * 由 ChessGame 判断（它知道步时 engineTimeMs / 心跳），巡检器只负责周期询问。
     */
    fun watchdogShouldRescue(color: PieceColor): Boolean
    /** 当前对局是否已结束 */
    fun watchdogIsGameOver(): Boolean
    /** 上层复位该方"引擎思考"开关（=人脑接管），返回是否进行 */
    fun watchdogResetThinking(color: PieceColor): Boolean
    /** 上层真正重启该方引擎进程并恢复 AI */
    fun watchdogRescueEngine(color: PieceColor)
    /** 巡检循环是否应暂停（如 Activity 未在前台） */
    fun watchdogIsPaused(): Boolean
}

class EngineWatchdog(
    private val host: WatchdogHost
) {
    companion object {
        private const val TAG = "EngineWatchdog"
        /** 巡检周期：太密费电，太疏发现不及。1.5s 为实测均衡值 */
        private const val TICK_MS = 1500L
        /** 该方引擎就绪标记异常达到该次数才介入（避免瞬时抖动误杀） */
        private const val THRESHOLD = 2
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val failCount = IntArray(2) { 0 }  // [0]=RED [1]=BLACK 连续异常计数
    private var started = false

    /** 启动巡检线程 */
    fun start() {
        if (started) return
        started = true
        val t = HandlerThread("EngineWatchdog")
        t.start()
        thread = t
        handler = Handler(t.looper)
        handler?.post(tickRunnable)
        Log.i(TAG, "独立通道巡检引擎已启动（周期 ${TICK_MS}ms）")
    }

    /** 停止巡检线程 */
    fun stop() {
        started = false
        handler?.removeCallbacks(tickRunnable)
        thread?.quitSafely()
        thread = null
        handler = null
        for (i in failCount.indices) failCount[i] = 0
        Log.i(TAG, "独立通道巡检引擎已停止")
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                if (!started) return
                val h = handler ?: return
                if (host.watchdogIsPaused()) {
                    h.postDelayed(this, TICK_MS)
                    return
                }
                // 遍历红黑两方，分别巡检
                checkColor(PieceColor.RED)
                checkColor(PieceColor.BLACK)
                h.postDelayed(this, TICK_MS)
            } catch (e: Exception) {
                Log.w(TAG, "巡检 tick 异常: ${e.message}")
                val h = handler
                if (started && h != null) h.postDelayed(this, TICK_MS)
            }
        }
    }

    private fun checkColor(color: PieceColor) {
        if (host.watchdogIsGameOver()) return
        // 只处理引擎该管事的时候：若当前轮到人力，引擎闲着是正常的，不巡检。
        if (!host.watchdogIsEngineTurn(color)) {
            failCount[color.ordinal] = 0
            return
        }
        val ready = host.watchdogIsReady(color)
        val hung = host.watchdogShouldRescue(color)
        if (!ready || hung) {
            // 该方引擎应就绪却未就绪 / 或"活着但挂起"（轮值却超时未落子）——
            // 连续到阈值即介入（抢救）。心跳检测：不止盯子进程死没死，也盯"赖着不走"。
            failCount[color.ordinal]++
            if (failCount[color.ordinal] >= THRESHOLD) {
                val reason = if (!ready) "未就绪(进程异常)" else "挂起(步时到点未落子)"
                Log.w(TAG, "巡检发现${name(color)}引擎$reason，触发抢救")
                failCount[color.ordinal] = 0
                host.watchdogResetThinking(color)
                // 真正重启该方引擎进程并恢复 AI（对外不暴露"切人脑/切AI"）
                host.watchdogRescueEngine(color)
            }
        } else {
            failCount[color.ordinal] = 0
        }
    }

    private fun name(color: PieceColor) = if (color == PieceColor.RED) "红方" else "黑方"
}