package com.yk.xiangqi.link;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务：既执行已经规划好的手势，也提供「按需截屏」与「窗口变化事件」。
 *
 * <p>无障碍是可选的，但一旦启用，它就从「只能点棋盘」升级成竞品那种<b>能自己看屏幕</b>的
 * 完整通道：</p>
 * <ul>
 *   <li><b>按需截屏</b>（{@link #capture()}，Android 11+）：不依赖录屏虚拟屏产帧，
 *       画面再静止也能立刻拿到当前屏幕，这正是「点了同步/校准半天没反应」的解药；</li>
 *   <li><b>窗口变化事件</b>（{@link #setWindowChangeListener}）：棋盘一有变化就立刻通知
 *       连线服务重认，不必再等 600ms 节拍，也不再需要用户自己先走几步把画面催动起来。</li>
 * </ul>
 *
 * <p>未启用无障碍时连线照常识别与分析，只是不再回写点击、且退回归「抖帧 + 等录屏产帧」
 * 的旧路径，应用会自动降级为「只识别」模式。</p>
 */
public final class LinkAccessibilityService extends AccessibilityService {
    private static final String TAG = "XiangqiLink";
    /** 无障碍截屏超时：超过则视为本次截屏不可用，由调用方回退到录屏帧。 */
    private static final long CAPTURE_TIMEOUT_MS = 2_000L;
    /** 截屏回调的执行线程：独立后台线程，避免在主线程调用时把 await 卡死。 */
    private static final Executor CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor();

    private static volatile LinkAccessibilityService active;
    private static volatile WindowChangeListener windowChangeListener;
    private final Handler main = new Handler(Looper.getMainLooper());


    // ══════════ 节点探针（只读，不注入、不点击） ══════════
    // 用途：先看对面棋盘到底是"真节点"还是"自绘像素"。
    //   · 读到带棋子信息的文本 → 以后可以直接用节点出 FEN（零截屏、零推理，操作端几乎不发热）；
    //   · 读不到（自绘 Canvas 的常见情形）→ 仍然走原来的截屏识别，行为完全不变。
    private static volatile String nodeSummary = "未探测";
    private static volatile long nodeSummaryAt = 0L;

    public static String probeNodes() {
        if (System.currentTimeMillis() - nodeSummaryAt < 5000L) return nodeSummary;
        nodeSummaryAt = System.currentTimeMillis();
        LinkAccessibilityService svc = active;
        if (svc == null) { nodeSummary = "无障碍未启用"; return nodeSummary; }
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) { nodeSummary = "读不到窗口（对面可能不是普通 View）"; return nodeSummary; }
            int total = 0, texted = 0;
            StringBuilder sample = new StringBuilder();
            java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty() && total < 400) {
                AccessibilityNodeInfo n = queue.poll();
                if (n == null) continue;
                total++;
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                String s1 = t == null ? "" : t.toString().trim();
                String s2 = d == null ? "" : d.toString().trim();
                String pick = !s1.isEmpty() ? s1 : s2;
                if (!pick.isEmpty()) {
                    texted++;
                    if (sample.length() < 60) sample.append(sample.length() == 0 ? "" : "／").append(pick);
                }
                for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
            }
            nodeSummary = texted == 0
                    ? ("可读节点 " + total + " 个，但全是无文字的容器（自绘棋盘，只能截屏）")
                    : ("可读文本节点 " + texted + " 个（" + total + " 个节点），样例：" + sample);
        } catch (Throwable e) {
            nodeSummary = "探针异常：" + e.getClass().getSimpleName();
        }
        return nodeSummary;
    }

    /** 棋盘所在窗口发生实质变化时的回调（在无障碍事件线程上调用，应尽量轻）。 */
    public interface WindowChangeListener {
        void onWindowChanged();
    }

    /**
     * 连线开始前确认无障碍服务已经实际连接，避免只能识别却无法回写走子。
     */
    public static boolean isActive() {
        return active != null;
    }

    /**
     * 无障碍是否已开启：服务已连上，或系统设置里勾选了本服务（可能尚未完成连接）。
     *
     * <p>判断依据是系统设置里的已启用列表，因此刚打开开关、服务还没连上时也算开启，
     * 不会因为时序差误判成「没开」。此方法可在任意线程调用。</p>
     */
    public static boolean isEnabled(Context context) {
        if (active != null)
            return true;
        if (context == null)
            return false;
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty())
            return false;
        ComponentName component = new ComponentName(context, LinkAccessibilityService.class);
        String full = component.flattenToString();
        String shortName = component.flattenToShortString();
        for (String entry : enabled.split(":")) {
            if (entry.equalsIgnoreCase(full) || entry.equalsIgnoreCase(shortName))
                return true;
        }
        return false;
    }

    /**
     * 注册窗口变化监听（连线服务用）。传 {@code null} 注销。
     */
    public static void setWindowChangeListener(WindowChangeListener listener) {
        windowChangeListener = listener;
    }

    /**
     * 无障碍在线时「按需」截取当前屏幕，返回一张软件位图（ARGB_8888），失败或 Android 11
     * 以下返回 {@code null}。
     *
     * <p>与录屏虚拟屏不同，这里不依赖合成器产帧：画面完全静止也能立刻取到当前画面，
     * 因此「同步/校准/免框选」点下去就能马上识别，不必先手动点一下棋盘把画面催动。</p>
     *
     * <p>可在任意线程调用（内部用后台线程等回调，不会阻塞主线程）。</p>
     */
    public static Bitmap capture() {
        LinkAccessibilityService service = active;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return null;
        return service.captureNow();
    }

    @Override
    public void onServiceConnected() {
        active = this;
    }

    @Override
    public void onDestroy() {
        if (active == this)
            active = null;
        if (windowChangeListener != null)
            windowChangeListener = null;
        super.onDestroy();
    }

    /**
     * 窗口变化即回调。这里只转发「别的应用」的窗口变化——本应用自己的悬浮窗（抖帧、
     * 小棋盘刷新）也会产生事件，若不加过滤会与本服务的识别形成回环，所以直接跳过。
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null)
            return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && getPackageName().contentEquals(pkg))
            return;
        WindowChangeListener listener = windowChangeListener;
        if (listener != null)
            listener.onWindowChanged();
    }

    @Override
    public void onInterrupt() {
    }

    private Bitmap captureNow() {
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] holder = new Bitmap[1];
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, CAPTURE_EXECUTOR,
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        try {
                            Bitmap hardware =
                                Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                            if (hardware != null)
                                holder[0] = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        } catch (Exception error) {
                            Log.w(TAG, "无障碍截屏转位图失败", error);
                        } finally {
                            buffer.close();
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.w(TAG, "无障碍截屏失败 code=" + errorCode);
                        latch.countDown();
                    }
                });
            latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            Log.w(TAG, "无障碍截屏异常", error);
        }
        return holder[0];
    }

    /** ★★ 连点器：按屏幕上的文字找到按钮并点它（同步/校准），无需小球定位。 */
    public static boolean tapByText(String text) {
        LinkAccessibilityService service = active;
        if (service == null || text == null || text.isEmpty())
            return false;
        try {
            android.view.accessibility.AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                return false;
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByText(text);
            if (nodes == null || nodes.isEmpty())
                return false;
            for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                android.graphics.Rect r = new android.graphics.Rect();
                node.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) {
                    return service.dispatchTapAt(r.centerX(), r.centerY());
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** ★★ 连点器：在任意屏幕坐标做一次物理点击（与手点完全同路径，确定性最高）。 */
    public static boolean tapAt(float x, float y) {
        LinkAccessibilityService service = active;
        if (service == null)
            return false;
        return service.dispatchTapAt(x, y);
    }

    private boolean dispatchTapAt(float x, float y) {
        if (!isActive())
            return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 40));
        return dispatchGesture(builder.build(), null, null);
    }

    public static boolean dispatch(GesturePlan plan, GestureResult result) {
        LinkAccessibilityService service = active;
        if (service == null)
            return false;
        return service.dispatchFirstTap(plan, result);
    }

    /**
     * 两次点击必须是两个独立手势：部分应用会忽略同一 GestureDescription 中的第二个
     * Stroke，或将持续 1ms 的 Stroke 视作无效输入。
     */
    private boolean dispatchFirstTap(GesturePlan plan, GestureResult result) {
        return dispatchTap(plan.from(), plan.tapDurationMs(), new GestureResult() {
            @Override
            public void completed() {
                main.postDelayed(() -> dispatchSecondTap(plan, result), plan.tapIntervalMs());
            }

            @Override
            public void cancelled() {
                result.cancelled();
            }
        });
    }

    private void dispatchSecondTap(GesturePlan plan, GestureResult result) {
        if (active != this || !dispatchTap(plan.to(), plan.tapDurationMs(), result))
            result.cancelled();
    }

    private boolean dispatchTap(BoardGeometry.Point point, long durationMs, GestureResult result) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke(point, durationMs));
        return dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                result.completed();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                result.cancelled();
            }
        }, main);
    }

    private static GestureDescription.StrokeDescription stroke(BoardGeometry.Point point, long durationMs) {
        Path path = new Path();
        path.moveTo(point.x(), point.y());
        return new GestureDescription.StrokeDescription(path, 0, durationMs);
    }

    public interface GestureResult {
        void completed();

        void cancelled();
    }
}
