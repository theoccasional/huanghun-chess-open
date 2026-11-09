package com.pikafish.chess.heavy;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ★★★ 「本地引擎 / 异地引擎」这一档选择的落点。★★★
 *
 * <p>用户可以在两条路里挑一条：</p>
 * <ul>
 *   <li><b>本地引擎</b>（{@link #MODE_LOCAL}）：引擎权重在本机加载，本机自己算棋。传统做法，
 *       不依赖任何别的设备，但手机要出力、会发热。</li>
 *   <li><b>异地引擎</b>（{@link #MODE_REMOTE}）：本机只管界面与触摸，算棋整个交给配对上的
 *       辎重包端（电脑 / 另一台手机）——那边把算好的着法送回来，本机不加载权重、不跑引擎，
 *       因而明显不发热。也就是把大件「遥控」到别的设备上去用。</li>
 * </ul>
 *
 * <p>本类只负责记住「选了哪条 + 对方（辎重包端）的引擎地址」。地址由「配对码」配对成功后
 * 自动写入，用户无需手填。</p>
 */
public final class EngineLink {

    /** 本地引擎：本机自己算。 */
    public static final String MODE_LOCAL = "local";
    /** 异地引擎：辎重包端替本机算。 */
    public static final String MODE_REMOTE = "remote";

    /**
     * ★★★ 换引擎列表里「电脑端异地引擎」这一行的固定 id。★★★
     *
     * <p>它不是本机装载的任何一台引擎档案，而是「配对上的辎重包端（电脑 / 另一台手机）
     * 正在加载的那台引擎」——选它即把算棋整个交给对方代算（本机不发热）。换引擎弹窗
     * 据本 id 在列表头部单列一行，名称后标「（电脑）」。</p>
     */
    public static final String REMOTE_ENGINE_ID = "__remote_computer__";

    private static final String PREF = "engine_link";
    private static final String KEY_MODE = "mode";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";

    private EngineLink() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 当前引擎档位；未选过时默认本地引擎（老用户行为不变）。 */
    public static String mode(Context context) {
        String mode = prefs(context).getString(KEY_MODE, MODE_LOCAL);
        return MODE_REMOTE.equals(mode) ? MODE_REMOTE : MODE_LOCAL;
    }

    public static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, MODE_REMOTE.equals(mode) ? MODE_REMOTE : MODE_LOCAL).apply();
    }

    /** 记住辎重包端的引擎地址（配对成功时调用）。 */
    public static void setEndpoint(Context context, String host, int port) {
        prefs(context).edit()
                .putString(KEY_HOST, host == null ? "" : host)
                .putInt(KEY_PORT, port)
                .apply();
    }

    /** 辎重包端的引擎地址，形如 {@code 192.168.1.5:45322}；未配对过返回 {@code null}。 */
    public static String endpoint(Context context) {
        SharedPreferences p = prefs(context);
        String host = p.getString(KEY_HOST, "");
        int port = p.getInt(KEY_PORT, 0);
        if (host.isEmpty() || port <= 0) {
            return null;
        }
        return host + ":" + port;
    }

    /**
     * ★★★ 强制回到本地引擎（用户亲手点了「官方默认 / 本地引擎」时调用）。★★★
     *
     * <p>为什么需要它：以前只看"档位"，只要档位还停在异地，就算用户选了本地引擎也照旧
     * 一遍遍去连那个已经关掉的对方，表现为"点了也没用、一直撞墙"。这里把话说明白：
     * 用户一旦选定本地，就<b>立刻</b>清掉异地档位与远程地址，并断开旧的异地连接，
     * 之后所有询招一律走本机引擎——听指挥，不再重连。</p>
     */
    public static void forceLocal(Context context) {
        prefs(context).edit()
                .putString(KEY_MODE, MODE_LOCAL)
                .remove(KEY_HOST)
                .remove(KEY_PORT)
                .apply();
        try {
            // 把仍在跑的（异地）询招引擎关掉；下一招按本地引擎重新拉起。
            com.pikafish.chess.engine.SandboxOracle.INSTANCE.shutdown();
        } catch (Throwable ignored) {
        }
    }

    /** 是否应当走异地引擎（档位选了异地，且已配到过对方引擎地址）。 */
    public static boolean remoteUsable(Context context) {
        return MODE_REMOTE.equals(mode(context)) && endpoint(context) != null;
    }

    /** 人类可读的当前档位（供界面展示）。 */
    public static String describe(Context context) {
        if (MODE_REMOTE.equals(mode(context))) {
            String ep = endpoint(context);
            return ep == null ? "异地引擎（尚未配对辎重包引擎）" : "异地引擎（辎重包端代算：" + ep + "）";
        }
        return "本地引擎（本机自算）";
    }
}
