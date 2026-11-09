package com.yisi.xiangqicoach

import android.util.Log

/**
 * Pikafish JNI 原生接口
 *
 * 直接在进程内调用 Pikafish 引擎，无需子进程通信。
 * 消除了 IPC 管道通信的所有不稳定性问题。
 *
 * JNI 方法签名与 libpikafish_jni.so 中的导出符号严格匹配：
 *   Java_com_yisi_xiangqicoach_PikafishNative_initialize
 *   Java_com_yisi_xiangqicoach_PikafishNative_analyze
 *   Java_com_yisi_xiangqicoach_PikafishNative_applyMove
 *   Java_com_yisi_xiangqicoach_PikafishNative_legalMoves
 *   Java_com_yisi_xiangqicoach_PikafishNative_stop
 */
object PikafishNative {
    private const val TAG = "PikafishNative"

    init {
        try {
            System.loadLibrary("pikafish_jni")
            Log.i(TAG, "libpikafish_jni.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libpikafish_jni.so", e)
            throw e
        }
    }

    /**
     * 初始化引擎
     * @param nnuePath NNUE 权重文件路径
     * @param threads 线程数
     * @param hash 哈希表大小（MB）
     * @return JSON 字符串，成功返回 {"error":null}，失败返回 {"error":"..."}
     */
    @JvmStatic
    external fun initialize(nnuePath: String, threads: Int, hash: Int): String

    /**
     * 分析局面
     * @param fen FEN 局面字符串
     * @param depth 搜索深度（传 255 表示无限深度，配合 stop() 实现按秒计时）
     * @param multipv 多主要变着数量（1=只返回最佳走法）
     * @param searchMoves 限制搜索的走法（空字符串=不限制，"h2e2 b9c7"=只搜索这些走法）
     * @return JSON 字符串：
     *   成功: {"lines":[{"depth":15,"selDepth":20,"score":"cp 42","nodes":1234567,"nps":100000,"multipv":1,"pv":"h2e2 b9c7 ..."}],"error":null}
     *   失败: {"lines":[],"error":"..."}
     */
    @JvmStatic
    external fun analyze(fen: String, depth: Int, multipv: Int, searchMoves: String): String

    /**
     * 应用走法，返回新局面的 FEN
     * @param fen 当前局面 FEN
     * @param uciMove UCI 格式走法（如 "h2e2"）
     * @return JSON 字符串
     */
    @JvmStatic
    external fun applyMove(fen: String, uciMove: String): String

    /**
     * 获取合法走法列表
     * @param fen 当前局面 FEN
     * @return JSON 字符串
     */
    @JvmStatic
    external fun legalMoves(fen: String): String

    /**
     * 停止当前搜索
     * 调用后，正在执行的 analyze() 会尽快返回当前最佳结果
     */
    @JvmStatic
    external fun stop()
}
