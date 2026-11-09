package com.pikafish.chess

/**
 * ★ 「打开其他应用」入口：由 QQ / 文件管理器等外部应用经 ACTION_VIEW 唤起。
 *
 * 与 [SplashActivity] 的区别只在清单里的两行：
 *   - `taskAffinity = "com.pikafish.chess.openwith"`（独立任务亲和度）
 *   - `launchMode   = "singleTask"`（自成一体、永远作为任务根）
 *
 * 这样从 QQ 里点开棋谱 / PDF 时，黄昏象棋会**单独起一个自己的任务**，
 * 不再被塞进 QQ 的任务栈里 —— 最近任务列表里不会再「套在 QQ 壳子里」。
 * 业务逻辑一行不改，全部继承 SplashActivity：读外部文件 → 进主界面 → 入宝库直接开谱。
 */
class OpenWithActivity : SplashActivity()
