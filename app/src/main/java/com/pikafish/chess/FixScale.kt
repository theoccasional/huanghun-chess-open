package com.pikafish.chess

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * ★★★ 渲染缩放工具：把系统"字体大小 / 字体风格"的影响钉死，做到"八风不动"。★★★
 *
 * 问题：
 *   手机上把系统的显示大小 / 字体大小 / 字体粗细调来调去，App 的字分分钟被带偏，
 *   布局被撑破、串行。
 *
 * 解法：
 *   在 Application 与每个 Activity 的 attachBaseContext 里，把配置强制改回
 *   fontScale = 1.0（字体大小固定）且 fontWeightAdjustment = 0（关闭新系统加粗），
 *   再据此建一个独立配置上下文。这样不论系统怎么改，App 内部渲染始终用我们自己的
 *   字号设置，外部怎么动它都不受影响。
 */
object FixScale {

    /** 返回一个"字体缩放已钉死"的配置上下文，用于 attachBaseContext 时替换基础上下文 */
    fun fixedContext(base: Context): Context {
        // 深拷贝当前系统配置，绝不改动系统全局配置，仅在当前进程内使用
        val cfg = Configuration(base.resources.configuration)
        cfg.fontScale = 1f                 // 字体大小固定为 1.0，不受系统显示大小/字体大小影响
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cfg.fontWeightAdjustment = 0   // Android 12+ 的全局"字体粗细调整"归零，不被加粗带偏
        }
        return base.createConfigurationContext(cfg)
    }
}