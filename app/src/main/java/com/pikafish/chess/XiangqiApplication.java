package com.pikafish.chess;

import android.content.Context;

import com.pikafish.chess.engine.SandboxOracle;

/**
 * ★ 应用进程入口：黄昏象棋的宿主 Application。
 *
 * <p>它直接继承 YK 象棋逐字移植过来的 {@link com.yk.xiangqi.XiangqiApplication}，
 * 于是 YK 的连线模块（{@code com.yk.xiangqi.link.LinkForegroundService} 等）以
 * {@code ((com.yk.xiangqi.XiangqiApplication) getApplication()).settings()/gameRuntime()}
 * 取到的，正是这里这一个全局单例——没有任何中间适配层，YK 的 Settings/GameRuntime
 * 原样生效。</p>
 *
 * <p>黄昏独有的两件事在此并入：{@link FixScale}（系统字体大小/风格不影响界面）与
 * {@link SandboxOracle}（主对弈询招沙盒的全局接线）。</p>
 */
public final class XiangqiApplication extends com.yk.xiangqi.XiangqiApplication {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(FixScale.INSTANCE.fixedContext(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // ★ 询招沙盒全局接线：供主对弈的询招引擎懒启动。
        SandboxOracle.INSTANCE.bind(this);
    }
}