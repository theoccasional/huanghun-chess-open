package com.yk.xiangqi.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import androidx.core.content.IntentCompat;

import com.pikafish.chess.engine.EngineIdentity;
import com.pikafish.chess.heavy.EngineLink;
import com.pikafish.chess.heavy.HeavyStore;
import com.yk.xiangqi.Settings;
import com.yk.xiangqi.GameRuntime;
import com.yk.xiangqi.XiangqiApplication;
import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.GameAction;
import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.PieceType;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;
import com.yk.xiangqi.ui.link.LinkOverlay;

import java.util.ArrayList;
import java.util.List;

/**
 * 连线的后台生命周期所有者。录屏、帧工作线程和普通悬浮窗将在这里统一关闭。
 */
public final class LinkForegroundService extends Service {
    private static final String TAG = "XiangqiLink";
    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 17;
    public static final String ACTION_START = "com.yk.xiangqi.link.START";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    // 录屏与帧处理资源。
    private HandlerThread workerThread;
    private Handler worker;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay display;
    private ImageReader reader;
    /**
     * ★★★ 识别器（双路仲裁）：对外接口与旧 {@link PieceRecognizer} 完全同形。★★★
     * 主路径仍是 ONNX；只有 ONNX 给出明显不合法的棋盘（缺帅缺将 / 兵种超额）时，才用
     * {@link LinkColorScan} 的颜色结构就地纠偏——这正是「引擎因非法局面直接退出、连线
     * 永久卡在等待思考」的根治点。基线合法时行为与旧版一字不差。
     */
    private DualBoardRecognizer recognizer;
    /**
     * 免框选识别器：仅在建盘/免框选/校准这类一次性路径上惰性加载，不进逐帧热路径。
     * 摆子整盘识别（pose 角点模型 + 整盘模型）比逐格识别更准，作为自动定位后的认子校验。
     */
    private SetupBoardRecognizer setupRecognizer;
    /**
     * 免框选棋盘定位器（YOLOv11）：一次前向即给出棋盘框，不依赖网格投影、不受棋盘大小与
     * 屏幕位置影响。作为自动识别的<b>首选定位</b>，未命中自动回退神经角点/梳齿检测。
     */
    private YoloBoardLocator yoloLocator;
    /** 观棋走子存档：只记用户实打在棋盘上走出的着法，跨校准保留，用来判定 ECCO 开局。 */
    private final OpeningArchive openingArchive = new OpeningArchive();
    /** 存档是否已从第一着对齐；未对齐时宁可不显示开局名，也不报错名。 */
    private boolean openingAligned;
    /**
     * 是否允许用当前局面重建开局判定。只在「重新框选棋盘 / 套用方案 / 免框选」这类
     * 明确的重新建盘操作后置位一次，供随后的首个建盘动作消费；
     * 校准、红必走/黑必走、切引擎等操作一律不动它——只要用户没点重启、没重新框选，
     * ECCO 开局名就牢牢锁定，绝不因为校准之类的操作被掐断。
     */
    private boolean allowOpeningReset;
    /**
     * 棋谱重新对齐时往回检查的着数上限。用户悔棋、或连着几手不按引擎推荐走都落在这个
     * 窗口内；再远就整段重建，窗口开大反而拖慢走子回调。
     */
    private static final int ALIGN_LOOKBACK = 6;
    /** 标准红先开局，用于识别“真正的新对局”。 */
    private static final ObservedBoard STANDARD_START = ObservedBoard.from(Position.start());
    private volatile LinkState linkState;
    private BoardGeometry geometry;
    // ★ 指标：手动同步次数（用于衡量「要不要每回合点同步」）/ 自动同步次数 / 投票命中
    private int manualSyncTotal = 0, autoSyncTotal = 0;
    private long minuteAt = 0L; private int manualSyncThisMinute = 0;
    /**
     * 由 worker 持有，延迟识别完成、替换或服务关闭时释放。
     * 两种取帧来源（录屏 {@link Image} / 无障碍截屏 {@link android.graphics.Bitmap}）
     * 都统一成 {@link Frame}，下游识别与检测只认 Frame。
     */
    private Frame pendingFrame;
    /** 只比较框选棋盘内的少量亮度样本，避免静态合成帧重复触发 ONNX。 */
    private final FrameGate frameGate = new FrameGate();

    // 应用与悬浮窗。
    private GameRuntime gameRuntime;
    private GameRuntime.Listener gameListener;
    private GameRuntime.ArrowListener arrowListener;
    private GameRuntime.AnalysisListener analysisListener;
    private Handler main;
    private Settings settings;
    private LinkOverlay overlay;

    // 连线过程状态。仅由 worker 写入。
    private Side pendingSyncSide;
    /** 棋盘区域出现实质视觉变化时重置；到期时只识别最后保存的一张帧。 */
    private final Runnable settledFrame = this::processSettledFrame;
    /**
     * ★ 主动取帧节拍（呼吸式）。
     *
     * <p>旧路径是<b>被动</b>取帧：只在合成器产帧（画面变化）时才回调识别。画面一静止，
     * 取帧就断了——点「同步/校准/初始化」没反应，引擎也停着不动，非得用户先走两步把画面
     * 催动起来。这里改成固定拍主动取帧：只要在连线里，就不管画面动不动，按
     * {@link #FRAME_POLL_MS} 一拍不停地把当前画面要来；来得了就以它为准，来不了就退一步
     * 硬取（无障碍按需截屏 / 抖帧逼合成器），从而让「取帧」像呼吸一样持续，绝不休眠。</p>
     */
    private final Runnable keepAliveTick = this::keepAlive;
    /** 最近一次成功拿到一张投屏帧的时刻（仅诊断用）。 */
    private long lastFrameMs;
    /** ★ 供连点器判断「是否真的卡住」：最近一次画面推进时间 & 引擎是否在思考 */
    private static volatile long sLastProgressMs = 0L;
    private static volatile boolean sEngineThinking = false;
    private static volatile boolean sDesynced = false;

    /** 画面静止了多久（毫秒）。连点器只在它够大时才允许补点。 */
    public static long idleMillis() {
        long last = sLastProgressMs;
        return last == 0L ? Long.MAX_VALUE : (System.currentTimeMillis() - last);
    }

    /** ★ 供连点器判断是否已失步（失步即立刻补点一次） */
    public static boolean isDesynced() {
        return sDesynced;
    }

    public static boolean isEngineThinking() {
        return sEngineThinking;
    }
    /** ★ 失步自动重同步：上次时间 / 次数 / 最小间隔 */
    private long lastAutoResyncMs = 0L;
    private int autoResyncCount = 0;
    private static final long AUTO_RESYNC_MIN_INTERVAL_MS = 400L;
    /**
     * 最近一次「画面确有推进」的时刻：真实变化被接住、或静止期主动硬认过。
     * 距它超过 {@link #STATIC_RECOGNIZE_MS} 就说明画面已彻底静止，该主动硬认一次了。
     */
    private long lastCaptureProgressMs;
    /** 主动取帧节拍：50–60ms 一拍，既贴着屏幕刷新，又不至于把 ONNX 逼到每拍都跑。 */
    private static final long FRAME_POLL_MS = 55L;
    /** 画面静止多久就主动硬认一次当前棋盘，喂给引擎。 */
    private static final long STATIC_RECOGNIZE_MS = 900L;
    /** 窗口变化事件的最小处理间隔，防止棋盘动画期间疯狂重认。 */
    private static final long EVENT_RECONNECT_MIN_INTERVAL_MS = 250L;
    /** 最近一次因窗口变化而重认的时刻，用于事件节流。 */
    private long lastEventReconnectMs;

    /**
     * ★ 周期性强制校准（无差别覆盖）：不论画面动静，每 {@link #FORCED_SYNC_MS} 就把大棋盘与
     * 小棋盘强制对齐一次——和人工点「校准」一模一样。
     *
     * <p>这是「轮到引擎走子却不动」的兜底正解：一次强制对齐会触发
     * 「重建小棋盘 → 外部走子事件 → 引擎复盘 → 出招 → 回写」整条链路重新跑一遍，
     * 于是哪怕某个回合漏了校准、该搬子时处于休眠，也会在一两秒内被重新唤醒，而不是一直卡着。</p>
     */
    private static final long FORCED_SYNC_MS = 2_000L;
    /** 最近一次强制校准的时刻。 */
    private long lastForcedSyncMs;
    /** 是否有一拍待执行的强制校准（走子落地后立刻补一次「走后校准」）。 */
    private boolean forceSyncRequested;
    /**
     * 轮次停滞阈值：轮到引擎走子后盘面静止超过这么久仍无出招，就判为停滞并强制重对齐点火。
     * 取 6 秒，高于常见单步思考时长，既不会打断正常搜索，又能在真卡住时较快救回。
     */
    private static final long TURN_STALL_MS = 6_000L;

    /**
     * ★ 回写看门狗：一次自动走子从发出到落地若超过 {@link #WRITEBACK_TIMEOUT_MS} 仍未收到
     * 无障碍回调，连线会永久停在「等待回写」进入休眠——正是“小棋盘对得上、轮到自己却不动”。
     * 超时即强制取消回写并恢复呼吸，下一拍重新取帧、重新对齐。
     */
    private static final long WRITEBACK_TIMEOUT_MS = 1_500L;
    /** 当前一次自动走子开始等待回写的时刻；0 表示没有进行中的回写。 */
    private long writebackStartedMs;
    /** 「走后校准」在落子动画后的等待时长：等棋子落定再强制对齐，免得读到达动画中途。 */
    private static final long POST_MOVE_SYNC_DELAY_MS = 450L;

    /**
     * ★ 免框选预热定位：用户还在看菜单、尚未框选时，后台就用 YOLO 悄悄把棋盘框定位好。
     * 这样点「免框选」时第一步定位已经就绪，只剩一次整盘认子，真正做到“一触即发”。
     */
    private BoardGeometry precomputedGeometry;
    private long precomputedGeometryMs;
    /** 后台预定位的刷新间隔与结果有效期。 */
    private static final long PRECOMPUTE_MS = 400L;
    private static final long PRECOMPUTE_TTL_MS = 3_000L;
    private long lastPrecomputeMs;

    /** 当前候选棋盘变化的首尾时刻，仅在最终产生连线动作时输出分段耗时。 */
    private long firstChangeMs = -1L;
    private long lastChangeMs = -1L;
    private int writtenPly = -1;
    private volatile boolean stopping;
    /**
     * 一次性操作（免框选/同步/校准/初始化）的互斥闸。
     *
     * <p>一次识别要跑全盘 ONNX，本就要花上几百毫秒到一秒。用户等不及连点，会把好几份
     * 「全屏识别」排进 worker 队列逐个跑完，表现为“小棋盘按点击次数反复刷新”。这里保证
     * 同一时刻只跑一份：进行中再点只回显一行状态，不排队、不重复建盘。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean oneShotBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 当前一次性操作的名称与起始时刻，供「已用 Ns」倒计时回显。 */
    private volatile String oneShotLabel;
    private long oneShotStartMs;
    /**
     * 一次性操作期间的秒级回显节拍：每秒把「已用 Ns」刷进悬浮窗。
     *
     * <p>「点了没反应」的另一半是「不知道要等多久」。这里给正在跑的操作配一个可见的
     * 用时读数，用户一按就看到它在走秒，等多久心里有数，而不是干瞪眼。</p>
     */
    private final Runnable oneShotTicker = new Runnable() {
        @Override
        public void run() {
            String label = oneShotLabel;
            if (label == null || !oneShotBusy.get())
                return;
            long elapsed = (SystemClock.elapsedRealtime() - oneShotStartMs) / 1000L;
            showStatus(label + "…已用 " + elapsed + "s");
            main.postDelayed(this, 1_000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        settings = ((XiangqiApplication) getApplication()).settings();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "象棋连线", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification("点击应用可查看棋局，通知可用于保持连线服务。"));
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("YK 象棋连线运行中")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction()))
            begin(intent);
        return START_NOT_STICKY;
    }

    private void begin(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent.class);
        if (data == null)
            return;
        // 已经在连线时再次点击“连线”视为重连：先彻底释放旧录屏与识别资源，
        // 否则旧的 projection 会让 begin 直接返回，表现为“重新连起不动”。
        if (projection != null)
            disposePreviousCapture();
        if (workerThread == null) {
            workerThread = new HandlerThread("xiangqi-link");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, data);
        Log.i(TAG, "屏幕录制已授权，启动帧读取");
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!stopping)
                    stopSelf();
            }
        };
        projection.registerCallback(projectionCallback, worker);
        DisplayMetrics metrics = captureMetrics();
        createCapture(metrics);
        gameRuntime = ((XiangqiApplication) getApplication()).gameRuntime();
        // ★★ 识别模型一律改到 worker 线程惰性装载（原先是主线程直接 new，一旦识别模型要从
        //   辎重包装载、尤其是「远程直读」要连局域网，就会把主线程连同整个连线入口一起冻住，
        //   表现为「连线菜单弹不出来 / 配对后卡死」。挪到后台就再也不会挡住悬浮窗与菜单。
        //   装载失败也不再 stopSelf() 终止整个连线服务（那会让菜单彻底消失），只回显一行提示，
        //   菜单照常可用，稍后轻轻一点「同步/校准/免框选」即会重试装载。
        postToWorker(() -> {
            if (!ensureRecognizer()) {
                showStatus("识别模型未就绪：请确认辎重包已配对（或改用全量包），再点『同步/免框选』重试");
                Log.w(TAG, "识别模型装载未成功，连线菜单保留；待辎重包就绪后可重试");
            }
            // ★ 预加载免框选识别器与定位器（ONNX 会话）。若留到首次点「免框选」现场构造，
            //   要现场加载几十 MB 模型，表现为“点了半天没反应”。这里在连线起来的当口就热好，
            //   等用户点「免框选」时模型已就绪、一步到位；与 PieceRecognizer 同队列顺序构造，
            //   避免两个 ONNX 会话同时加载造成内存峰值。
            ensureSetupRecognizer();
            ensureYoloLocator();
            // ★ 免框选预热：连线一起就在后台按拍定位棋盘框（尚未框选时每 400ms 一拍），
            //   用户点「免框选」时几何已就绪，一步到位、不再现场干等模型检测。
            if (worker != null && !stopping) {
                worker.removeCallbacks(keepAliveTick);
                worker.postDelayed(keepAliveTick, FRAME_POLL_MS);
            }
        });
        // ★ 无障碍在线时，棋盘窗口一有变化就立刻回调重认：不再被动等录屏虚拟屏产帧，
        //   也不再需要用户自己先走几步把画面催动起来。
        LinkAccessibilityService.setWindowChangeListener(this::onWindowChanged);
        gameListener = (state, action, origin) -> {
            refreshMiniBoard(state);
            // ECCO 开局判定：只依据用户实打在棋盘上走出的着法，独立于单帧识别与校准。
            trackOpening(state, action);
            if (origin == GameRuntime.Origin.LOCAL && action instanceof GameAction.Play && worker != null)
                worker.post(() -> writeLocalMove(state));
        };
        arrowListener = ignored -> refreshMiniBoard(gameRuntime.state());
        analysisListener = this::refreshThinking;
        gameRuntime.addListener(gameListener);
        gameRuntime.addArrowListener(arrowListener);
        gameRuntime.addAnalysisListener(analysisListener);
        overlay = new LinkOverlay(this, new LinkOverlay.Listener() {
            @Override
            public void onConfigured(BoardGeometry geometry, Side sideToMove) {
                configure(geometry, sideToMove);
            }

            @Override
            public void onReconfigured(BoardGeometry geometry, Side sideToMove) {
                reconfigure(geometry, sideToMove);
            }

            @Override
            public void onSelectionCancelled() {
                overlay.showReady();
            }

            @Override
            public void onAiSideChanged(Side side) {
                // 红黑互斥：选了红必走就同时取消黑必走，引擎只会替这一方思考。
                gameRuntime.setAi(Side.RED, side == Side.RED);
                gameRuntime.setAi(Side.BLACK, side == Side.BLACK);
                Log.i(TAG, "引擎执子：" + (side == null ? "交还手动" : side)
                        + "，当前行棋=" + gameRuntime.state().position().sideToMove());
            }

            @Override
            public void onNewGameSideChanged(Side sideToMove) {
                postToWorker(() -> {
                    if (linkState != null)
                        linkState = linkState.withNewGameSide(sideToMove);
                });
            }

            @Override
            public void onSynchronize(Side sideToMove) {
                postToWorker(() -> synchronizeNow(sideToMove));
            }

            @Override
            public void onQuickCalibrate(Side sideToMove) {
                BoardGeometry remembered = geometry;
                if (remembered == null) {
                    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                            notification("还没有框选过棋盘，请先框选一次"));
                    return;
                }
                quickCalibrate(remembered, sideToMove);
            }

            @Override
            public void onAutoCalibrate() {
                postToWorker(() -> autoCalibrateNow());
            }

            @Override
            public void onInitialize() {
                postToWorker(() -> initializeNow());
            }

            @Override
            public List<Settings.Scheme> schemes() {
                return settings.schemes();
            }

            @Override
            public void onSchemeSaved(String id, String name, String app, BoardGeometry geometry, Side sideToMove) {
                saveScheme(id, name, app, geometry, sideToMove);
            }

            @Override
            public void onSchemeApplied(Settings.Scheme scheme) {
                configureFromScheme(scheme);
            }

            @Override
            public void onSchemeDeleted(String id) {
                settings.removeScheme(id);
            }

            @Override
            public int savedMiniBoardSize() {
                return settings.miniBoardSizeDp();
            }

            @Override
            public void onMiniBoardSizeChanged(int sizeDp) {
                settings.setMiniBoardSizeDp(sizeDp);
            }

            @Override
            public List<Settings.Engine> engines() {
                // ★ 换引擎列表按「本机装载的档案 + 电脑端异地引擎」两块拼：
                //   只要配对过辎重包端（记下了对方引擎地址），就把那台「电脑上的引擎」
                //   单列一行排在最前，名称后标「（电脑）」。选了它，长期连线也由电脑代算，
                //   手机不发热；选本机档案则切回本机自算。
                List<Settings.Engine> list = new ArrayList<>();
                if (EngineLink.endpoint(LinkForegroundService.this) != null)
                    list.add(remoteEngineEntry());
                list.addAll(settings.engines());
                return list;
            }

            /** 列表头部那台「电脑端异地引擎」：名称取辎重包端引擎自报的真实身份，名称后标「（电脑）」。 */
            private Settings.Engine remoteEngineEntry() {
                EngineIdentity identity = EngineIdentity.INSTANCE;
                String name;
                if (identity.getRemote() && !identity.getIdName().isEmpty()) {
                    String raw = identity.getShortName();
                    String ver = identity.getVersionText();
                    // ★ 名称后标「（电脑）」——取到电脑自报真名时，直接带上电脑标记，
                    //   一眼就知道这台引擎在电脑上、可拿来连线代算（手机不发热）。
                    name = (ver.isEmpty() ? raw : raw + " " + ver) + "（电脑）";
                } else {
                    // 本轮还没真正连过辎重包端，取不到对方自报名字，先用通用名。
                    name = "电脑端引擎（电脑）";
                }
                Settings.Engine active = settings.activeEngine();
                return new Settings.Engine(
                        EngineLink.REMOTE_ENGINE_ID,
                        name,
                        "电脑",
                        Math.max(1, active.threads),
                        Math.max(16, active.hashMb),
                        java.util.Collections.<String, String>emptyMap());
            }

            @Override
            public String activeEngineId() {
                // 异地档位生效时，「当前」就是电脑端那台；否则是本机采用的档案。
                if (EngineLink.remoteUsable(LinkForegroundService.this))
                    return EngineLink.REMOTE_ENGINE_ID;
                return settings.activeEngine().id;
            }

            @Override
            public void onEngineSelected(String id) {
                // 引擎进程与 activeEngine 都归界面所有；这里只转达请求，
                // 由界面统一落盘并重启，避免「已是当前引擎」被误判成无需切换。
                gameRuntime.requestLinkEngine(id);
            }

            @Override
            public String searchLimitKind() {
                return settings.linkSearchKind();
            }

            @Override
            public long searchLimitValue() {
                return settings.linkSearchValue();
            }

            @Override
            public void onSearchLimitChanged(String kind, long value) {
                settings.setLinkSearch(kind, value);
                gameRuntime.notifyLinkSearchChanged();
            }

            @Override
            public List<String> linkInfoLines() {
                List<String> lines = new ArrayList<>();
                Settings.Scheme scheme = settings.activeScheme();
                if (scheme == null)
                    lines.add("连线软件：未选择连线方案");
                else
                    lines.add("连线软件：" + (scheme.app.isEmpty() ? scheme.name : scheme.app)
                            + "（方案：" + scheme.name + "）");
                GameRuntime runtime = gameRuntime;
                GameRuntime.EngineInfo info = runtime == null ? GameRuntime.EngineInfo.EMPTY : runtime.engineInfo();
                if (info.isEmpty()) {
                    lines.add("引擎：未加载");
                } else {
                    lines.add("引擎：" + info.title());
                    if (!info.uciName.isEmpty())
                        lines.add("引擎自报：" + info.uciName);
                    if (!info.author.isEmpty())
                        lines.add("作者：" + info.author);
                    if (!info.path.isEmpty())
                        lines.add("可执行文件：" + info.path);
                    lines.add("NNUE：" + (info.networkName.isEmpty() ? "未加载" : info.networkName)
                            + "（" + (info.networkSize.isEmpty() ? "0 MB" : info.networkSize) + "）");
                    lines.add("线程 " + info.threads + " · 哈希 " + info.hashMb + " MB");
                }
                if (geometry != null)
                    lines.add("框选：" + Math.round(geometry.left()) + "," + Math.round(geometry.top())
                            + " – " + Math.round(geometry.right()) + "," + Math.round(geometry.bottom()));
                Side side = engineSide();
                lines.add("引擎执子：" + (side == null ? "未接管（纯手动）" : side == Side.RED ? "红方" : "黑方"));
                LinkState state = linkState;
                if (state != null)
                    lines.add("底边方向：" + (state.bottomSide() == Side.RED ? "红方在下" : "黑方在下"));
                return lines;
            }

            @Override
            public List<String> linkEngineLines() {
                // ★ 第二页：如实说清算棋到底交给谁 + 辎重包与开局库的来路。
                //   这一页只做「看」，不做复制；所有查询都只用本地状态与内置资源（绝不联网），
                //   因此翻到这一页也不会卡。
                List<String> lines = new ArrayList<>();
                // 1) 算棋归属：本地引擎（本机自算） / 异地引擎（跨设备、辎重包端代算）。
                if (EngineLink.remoteUsable(LinkForegroundService.this))
                    lines.add("算棋方式：异地引擎（跨设备）");
                else if (EngineLink.MODE_REMOTE.equals(EngineLink.mode(LinkForegroundService.this)))
                    lines.add("算棋方式：异地引擎（尚未配到辎重包端，暂按本地兜底）");
                else
                    lines.add("算棋方式：本地引擎（本机自算）");
                lines.add("详情：" + EngineLink.describe(LinkForegroundService.this));
                // ★ 识别归属：异地引擎档位下，识别模型也由辎重包端代跑（手机不发烫）。
                lines.add("识别方式：" + RemoteRecognizer.describe(LinkForegroundService.this));
                // ★ 节点探针：只读看对面棋盘是"真节点"还是"自绘像素"，用于判断能否零截屏出 FEN。
                lines.add("节点探针：" + LinkAccessibilityService.probeNodes());
                // ★ 指标：手动同步频率就是「要不要每回合点同步」的量化答案
                lines.add("手动同步：" + manualSyncTotal + " 次（本分钟 " + manualSyncThisMinute + " 次）");
                lines.add("自动重同步：" + autoResyncCount + " 次");
                // 2) 辎重包来源与是否就绪。
                lines.add("辎重包：" + HeavyStore.describe(LinkForegroundService.this));
                lines.add("辎重包状态：" + (HeavyStore.hasPackage(LinkForegroundService.this)
                        ? "已配对就绪" : "未就绪（轻量包缺少大件，请先配对辎重包）"));
                // 3) 引擎二进制与 NNUE 权重来自哪里。
                GameRuntime runtime = gameRuntime;
                GameRuntime.EngineInfo info = runtime == null ? GameRuntime.EngineInfo.EMPTY : runtime.engineInfo();
                if (info.isEmpty()) {
                    lines.add("引擎：未加载");
                } else {
                    lines.add("引擎：" + info.title());
                    lines.add("引擎来源：" + (EngineLink.remoteUsable(LinkForegroundService.this)
                            ? "远程（辎重包端提供，本机不加载引擎）"
                            : "本机（" + (info.path.isEmpty() ? "内置" : info.path) + "）"));
                    lines.add("权重(NNUE)：" + (info.networkName.isEmpty() ? "未加载" : info.networkName)
                            + "（" + (info.networkSize.isEmpty() ? "0 MB" : info.networkSize) + "）");
                    lines.add("线程 " + info.threads + " · 哈希 " + info.hashMb + " MB");
                }
                // 4) 开局库（CBL 谱库）：本机内置 / 由辎重包提供 / 未找到。
                boolean builtinBook = HeavyStore.builtinExists(LinkForegroundService.this, "qipu_cbl/index.json");
                if (builtinBook)
                    lines.add("开局库：CBL 谱库（随本机内置）");
                else if (HeavyStore.hasPackage(LinkForegroundService.this))
                    lines.add("开局库：CBL 谱库（由辎重包提供）");
                else
                    lines.add("开局库：未找到（请配对辎重包，或改用全量包）");
                // 5) 观棋实时判出的当前开局。
                OpeningArchive.Label label = openingAligned ? openingArchive.label() : null;
                if (label != null && label.opening != null && !label.opening.isEmpty()) {
                    StringBuilder opening = new StringBuilder("当前开局：");
                    if (label.code != null && !label.code.isEmpty())
                        opening.append(label.code).append(' ');
                    opening.append(label.opening);
                    if (label.variation != null && !label.variation.isEmpty())
                        opening.append(" · 变例 ").append(label.variation);
                    lines.add(opening.toString());
                } else {
                    lines.add("当前开局：尚未判出");
                }
                return lines;
            }

            @Override
            public void onStop() {
                stopSelf();
            }
        });
        overlay.showReady();
        Settings.Scheme active = settings.activeScheme();
        if (active != null) {
            // 有历史方案就直接套用，重连不必再拉一次网格线。
            Log.i(TAG, "自动套用连线方案：" + active.name);
            configureFromScheme(active);
        } else {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                notification("请切到第三方棋盘后框选棋盘"));
        }
    }

    /**
     * 释放上一轮连线占用的录屏、帧线程与识别器。所有 Image/ONNX 资源都必须在
     * 原帧线程关闭，因此先把清理任务排进旧 worker，再让线程退出。
     */
    private void disposePreviousCapture() {
        HandlerThread oldThread = workerThread;
        Handler oldWorker = worker;
        ImageReader oldReader = reader;
        VirtualDisplay oldDisplay = display;
        DualBoardRecognizer oldRecognizer = recognizer;
        MediaProjection oldProjection = projection;
        MediaProjection.Callback oldCallback = projectionCallback;
        Frame oldFrame = pendingFrame;
        LinkAccessibilityService.setWindowChangeListener(null);
        if (oldProjection != null && oldCallback != null)
            oldProjection.unregisterCallback(oldCallback);
        Runnable cleanup = () -> {
            if (oldFrame != null) oldFrame.close();
            if (oldReader != null) oldReader.close();
            if (oldDisplay != null) oldDisplay.release();
            if (oldRecognizer != null) oldRecognizer.close();
            if (oldProjection != null) oldProjection.stop();
        };
        if (oldWorker != null) {
            oldWorker.removeCallbacks(settledFrame);
            oldWorker.post(cleanup);
        } else {
            cleanup.run();
        }
        if (oldThread != null)
            oldThread.quitSafely();
        if (overlay != null) {
            overlay.close();
            overlay = null;
        }
        if (gameRuntime != null && gameListener != null)
            gameRuntime.removeListener(gameListener);
        if (gameRuntime != null && arrowListener != null)
            gameRuntime.removeArrowListener(arrowListener);
        if (gameRuntime != null && analysisListener != null)
            gameRuntime.removeAnalysisListener(analysisListener);
        if (gameRuntime != null)
            gameRuntime.setLinkActive(false);
        analysisListener = null;
        workerThread = null;
        worker = null;
        reader = null;
        display = null;
        recognizer = null;
        projection = null;
        projectionCallback = null;
        pendingFrame = null;
        linkState = null;
        geometry = null;
        pendingSyncSide = null;
        stopping = false;
    }

    private void configure(BoardGeometry geometry, Side sideToMove) {
        Side bottom = linkState != null ? linkState.bottomSide() : Side.RED;
        startLink(geometry, sideToMove, bottom);
        main.post(() -> {
            if (overlay != null) overlay.offerSchemeSave();
        });
    }

    /** 套用历史方案：直接使用保存的几何、底边与先手，重连不再需要重新框选。 */
    private void configureFromScheme(Settings.Scheme scheme) {
        BoardGeometry geometry = new BoardGeometry(scheme.left, scheme.top, scheme.right, scheme.bottom);
        startLink(geometry, scheme.sideToMove, scheme.bottomSide);
        Log.i(TAG, "套用连线方案：" + scheme.name + "（" + scheme.app + "）");
    }

    /**
     * 开始（或重连）一轮连线。先手决定半腰连线时该由谁走子，底边只作为
     * 首帧前的方向初值，首帧归约仍会从将帅位置重新推断。
     */
    private void startLink(BoardGeometry geometry, Side sideToMove, Side bottom) {
        if (overlay != null) {
            overlay.showControls(engineSide());
            overlay.showThinking(gameRuntime.analysis());
        }
        // 开局名常驻思考细节窗口：重连/重开时也立刻回填当前存档判定出的开局名。
        publishOpening();
        // ★ 这是明确的“重新建盘”（重新框选/套用方案/免框选）：允许随后的首个建盘
        //   动作按新局面重建开局判定；此后校准等操作不会再动存档，ECCO 牢牢锁定。
        allowOpeningReset = true;
        // 连线期间由 ViewModel 持续分析当前局面，思考细节才能随外部走子刷新。
        gameRuntime.setLinkActive(true);
        refreshMiniBoard(gameRuntime.state());
        postToWorker(() -> {
            if (worker == null || !running())
                return;
            worker.removeCallbacks(settledFrame);
            startRecognition(geometry, bottom, sideToMove);
            Log.i(TAG, "开始连线，底边=" + bottom + "，先手=" + sideToMove);
            writtenPly = gameRuntime.state().moves().size();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("连线已开始"));
            long now = SystemClock.elapsedRealtime();
            lastFrameMs = now;
            lastCaptureProgressMs = now;
            lastForcedSyncMs = now;
            writebackStartedMs = 0;
            forceSyncRequested = false;
            worker.removeCallbacks(keepAliveTick);
            worker.postDelayed(keepAliveTick, FRAME_POLL_MS);
            queueLatestFrame(reader);
        });
    }

    /** 保存方案：同名 id 覆盖，否则新建；立即设为当前方案以便下次直接套用。 */
    private void saveScheme(String id, String name, String app, BoardGeometry geometry, Side sideToMove) {
        Side bottom = linkState != null ? linkState.bottomSide() : Side.RED;
        String schemeId = id != null ? id : java.util.UUID.randomUUID().toString();
        Settings.Scheme scheme = new Settings.Scheme(schemeId, name, app,
                geometry.left(), geometry.top(), geometry.right(), geometry.bottom(),
                bottom, sideToMove, System.currentTimeMillis());
        settings.saveScheme(scheme, true);
        Log.i(TAG, "已保存连线方案：" + name + "（" + app + "）");
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("已保存连线方案：" + name));
    }

    /**
     * MediaProjection 镜像的是完整物理显示，而应用 DisplayMetrics 可能排除状态栏、
     * 导航栏。两者尺寸不同时，系统会等比缩放并在两侧补黑边，导致框选坐标失真。
     */
    @SuppressWarnings("deprecation")
    private DisplayMetrics captureMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null)
            throw new IllegalStateException("无法取得主屏幕尺寸");
        display.getRealMetrics(metrics);
        return metrics;
    }

    /**
     * 创建一个与完整物理屏幕同尺寸的投屏缓冲区。
     */
    private void createCapture(DisplayMetrics metrics) {
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, android.graphics.PixelFormat.RGBA_8888, 3);
        display = projection.createVirtualDisplay("xiangqi-link", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
        reader.setOnImageAvailableListener(this::queueLatestFrame, worker);
    }

    /**
     * 手动同步：先逼出一张当前屏幕帧再据它同步，静止画面也照样能同步。
     *
     * <p>录屏虚拟屏只在画面变化时产帧；用户点「同步」时画面往往已经静止，直接要帧常一张
     * 也拿不到——表现为“点了没反应”，非要先在棋盘上点一下、让画面动一动才有用。这里先抖
     * 悬浮窗逼合成器产帧，再边等边取直到同步落地；真的一张都要不到时，才退回最后一张已处理
     * 输入。无论是否处于失步状态都必须能重新建盘，避免“断了线就再也同步不上”。</p>
     */
    private void synchronizeNow(Side sideToMove) {
        // 识别模型可能在连线入口时尚未就绪（辎重包当时取不到）。这里按需再试一次，
        // 装载成功即继续同步；仍失败也只回显一行提示，绝不终止连线、也不让按钮“点了没反应”。
        if (!ensureRecognizer()) {
            notify("识别模型未就绪：请确认辎重包已配对（或改用全量包）后重试");
            showStatus("识别模型未就绪…（请检查辎重包配对）");
            return;
        }
        if (!running() || linkState == null) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("尚未开始连线，无法同步"));
            return;
        }
        if (!beginOneShot("同步"))
            return;
        try {
            pendingSyncSide = sideToMove;
            Log.i(TAG, "请求使用当前帧同步：行棋=" + sideToMove);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("正在按当前屏幕同步局面"));
            if (!synchronizeFromScreen(sideToMove))
                synchronizeLastInput(sideToMove);
            if (pendingSyncSide != null) {
                pendingSyncSide = null;
                recoverFromDesync(sideToMove);
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("同步失败：请确认框选范围与先手"));
                showStatus("同步失败：请确认框选范围与先手");
            } else if (linkState != null && linkState.phase() == LinkState.Phase.DESYNCED) {
                // 识别结果不完整（缺将帅等）时归约只回流 DESYNCED；回到 INITIAL 让下一帧自动重试，
                // 否则连线会停在失步态，用户感觉“点了没反应、也不跟着同步”。
                recoverFromDesync(sideToMove);
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                        notification("同步未成功：识别结果不完整，将在下一帧自动重试"));
                showStatus("同步未完成：识别不完整，正在按新帧自动重试");
            } else {
                showStatus("同步完成：小棋盘已强制对齐当前大棋盘");
            }
        } finally {
            endOneShot();
        }
    }

    /** 把连线状态从失步态拉回可自动重试的 INITIAL，保留已推断的底边方向。 */
    private void recoverFromDesync(Side sideToMove) {
        LinkState state = linkState;
        if (state != null)
            linkState = LinkState.start(state.bottomSide(), sideToMove);
    }

    private boolean running() {
        return !stopping && reader != null && recognizer != null;
    }

    /**
     * 没有新帧时重新推理最后一次已处理的屏幕输入，不重新申请屏幕录制授权。
     */
    private void synchronizeLastInput(Side sideToMove) {
        countManualSync();
        if (linkState == null || geometry == null)
            return;
        try {
            ObservedBoard board = recognizer.recognizeLastInput();
            if (board != null)
                completeSync(linkState.synchronize(board, sideToMove), "使用最后已处理屏幕帧同步：");
        } catch (Exception error) {
            Log.e(TAG, "最近屏幕输入同步失败", error);
        }
    }

    /**
     * 手动同步优先消耗尚在等待稳定的最新帧。
     */
    private void synchronizePendingFrame(Side sideToMove) {
        countManualSync();
        if (linkState == null || geometry == null)
            return;
        Frame frame = takePendingFrame();
        if (frame == null)
            return;
        try {
            completeSync(linkState.synchronize(recognizer.recognize(frame, geometry), sideToMove), "使用等待中的屏幕帧同步：");
        } catch (Exception error) {
            Log.e(TAG, "最近帧同步失败", error);
        } finally {
            frame.close();
        }
    }

    private void countManualSync() {
        long now = System.currentTimeMillis();
        if (now - minuteAt > 60_000L) { minuteAt = now; manualSyncThisMinute = 0; }
        manualSyncTotal++; manualSyncThisMinute++;
    }

    private void completeSync(LinkResult result, String message) {
        if (result == null)
            return;
        pendingSyncSide = null;
        linkState = result.state();
        Log.i(TAG, message + result.action().kind());
        apply(result.action());
    }

    /**
     * 重新框选后不等待自动首帧判定，而是用下一帧强制同步当前局面。
     */
    private void reconfigure(BoardGeometry geometry, Side sideToMove) {
        if (overlay != null) {
            overlay.showControls(engineSide());
            overlay.showThinking(gameRuntime.analysis());
        }
        main.post(() -> {
            if (overlay != null) overlay.offerSchemeSave();
        });
        postToWorker(() -> {
            if (worker == null || !running())
                return;
            Log.i(TAG, "重新框选后请求同步：行棋方=" + sideToMove);
            worker.removeCallbacks(settledFrame);
            LinkState state = linkState;
            Side bottom = state != null ? state.bottomSide() : Side.RED;
            startRecognition(geometry, bottom, sideToMove);
            pendingSyncSide = sideToMove;
            writtenPly = gameRuntime.state().moves().size();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("棋盘框选已更新，等待同步"));
            queueLatestFrame(reader);
        });
    }

    /**
     * 快捷校准：不回框选，直接复用已记住的 9×10 框选几何，重新识别当前屏幕并重建棋盘。
     *
     * 它与“同步”共用同一套识别路径，区别在于不重新拉线、不改几何，只把这次已对齐的
     * 框选原样复刻一遍，用来纠正小棋盘上认错的棋子。同样先逼一张当前屏幕帧再识别，
     * 所以画面静止时点它也一样有效，不必先在棋盘上点一下。
     */
    private void quickCalibrate(BoardGeometry geometry, Side sideToMove) {
        postToWorker(() -> {
            // 同上：连线入口若未装载成功，校准这一步按需再试一次装载。
            if (!ensureRecognizer()) {
                showStatus("识别模型未就绪…（请检查辎重包配对）");
                return;
            }
            if (worker == null || !running())
                return;
            if (!beginOneShot("校准"))
                return;
            try {
                Log.i(TAG, "快捷校准：复用已记住的框选重新识别，行棋=" + sideToMove);
                worker.removeCallbacks(settledFrame);
                LinkState state = linkState;
                Side bottom = state != null ? state.bottomSide() : Side.RED;
                startRecognition(geometry, bottom, sideToMove);
                pendingSyncSide = sideToMove;
                writtenPly = gameRuntime.state().moves().size();
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                        notification("快捷校准：正在用已记住的框选重新识别"));
                if (!synchronizeFromScreen(sideToMove))
                    synchronizeLastInput(sideToMove);
                showStatus(pendingSyncSide == null
                        ? "校准完成：已强制对齐当前大棋盘"
                        : "校准未完成：识别不完整，正在按新帧自动重试");
            } finally {
                endOneShot();
            }
        });
    }

    /**
     * 初始化：不停止连线，只把上一盘遗留的缓存与惯性统统清干净，让下一盘从零开始。
     *
     * <p>上一盘下完，用户常直接开新一局接着连，可应用内部还记着上一盘的走子历史与开局
     * 判定——「同步/校准」会不由自主拿它去凑旧局面，越凑越拧、半天解不开，ECCO 开局名也
     * 抱着上一盘不放。这里把三样都归零：</p>
     * <ul>
     *   <li>应用棋局回到标准开局，甩掉上一盘的走子历史（小棋盘随之刷新）；</li>
     *   <li>观棋走子存档清空、开局判定失效，开局名立刻收起；</li>
     *   <li>连线进程内状态归零，丢待同步先手、重置识别缓存与失步计数，拉回 INITIAL。</li>
     * </ul>
     * <p>并把 {@code allowOpeningReset} 置位：随后不管用户先「同步」还是先「校准」，都会按
     * 大棋盘上的真实局面重新建盘、重新判定开局——开头连就正常报 ECCO，中局残局连就如实跟着
     * 局面走，绝不抱着上一盘的开局名不放。</p>
     */
    private void initializeNow() {
        if (!beginOneShot("初始化"))
            return;
        try {
            // 1) 应用棋局回标准开局，甩掉上一盘的走子历史。
            gameRuntime.reduce(new GameAction.Reset(Position.start()), GameRuntime.Origin.EXTERNAL);
            // 2) 观棋走子存档与开局名清空；置位允许随后的重新建盘按真实局面重建。
            openingArchive.clear();
            openingAligned = false;
            allowOpeningReset = true;
            // 3) 连线进程内状态归零。
            writtenPly = -1;
            pendingSyncSide = null;
            frameGate.reset();
            if (recognizer != null)
                recognizer.reset();
            LinkState state = linkState;
            Side bottom = state != null ? state.bottomSide() : Side.RED;
            Side side = state != null ? state.newGameSide() : Side.RED;
            linkState = LinkState.start(bottom, side);
            publishOpening();
            notify("已初始化：上一盘的缓存与开局判定已清空");
            Log.i(TAG, "连线已初始化：清空开局存档与连线状态，按当前局面重新同步");
            // 4) ★ 立即按当前大棋盘强制对齐一次，不必再等用户先走两步把画面催动起来。
            //    有框选几何就直接用当前屏幕帧重建小棋盘；取不到帧就交给下一帧自动对齐。
            if (running() && geometry != null) {
                pendingSyncSide = side;
                worker.removeCallbacks(settledFrame);
                if (!synchronizeFromScreen(side))
                    synchronizeLastInput(side);
                if (pendingSyncSide != null) {
                    pendingSyncSide = null;
                    recoverFromDesync(side);
                    showStatus("初始化完成：未取到当前画面，将在下一帧自动对齐");
                } else {
                    showStatus("初始化完成：已按当前大棋盘强制对齐");
                }
            } else {
                showStatus("初始化完成，等待按当前大棋盘重新同步");
            }
        } finally {
            endOneShot();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  免框选：全屏自动定位棋盘 + 认出整盘，一步上手（与「框选」并行的一条路）
    // ══════════════════════════════════════════════════════════════════════

    private void notify(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    /**
     * 在悬浮窗「思考细节」顶部回显一行即时状态。这是「点了没反应」的正解：
     * 按钮按下的那一刻就先亮出一行「正在…」，让用户知道点到了、在跑，而不是干等。
     * 可在 worker 线程任意调用，内部切回主线程更新。
     */
    private void showStatus(String text) {
        LinkOverlay target = overlay;
        if (target == null)
            return;
        main.post(() -> target.showStatus(text));
    }

    /**
     * 弹一个气泡（Toast）给用户看：免框选/同步/校准这类一次性操作一有结果就短促提示，
     * 让人不用去盯小棋盘右侧那行状态也心里有数。任意线程可调，内部切回主线程弹。
     */
    private void bubble(String text) {
        main.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    /**
     * 抢占一次性操作闸（免框选 / 同步 / 校准 / 初始化）。
     *
     * <p>一次全盘识别要跑 ONNX，几百毫秒到一秒不等。用户等不及会连点，把好几份识别排进
     * worker 队列逐个跑完，表现为「小棋盘按点击次数反复刷新」。这里保证同一时刻只跑一份：
     * 抢到返回 true（调用方必须 finally 释放），被占用则回显一行状态并返回 false，
     * 既不排队也不重复建盘——连点多少次都只会有一次真正的识别。</p>
     */
    private boolean beginOneShot(String label) {
        if (!oneShotBusy.compareAndSet(false, true)) {
            showStatus(label + "正在进行，请稍候…");
            return false;
        }
        showStatus(label + "…");
        notify(label + "：已开始");
        return true;
    }

    /** 释放一次性操作闸。 */
    private void endOneShot() {
        oneShotBusy.set(false);
    }

    /**
     * 免框选：无需手动框选，直接全屏扫描当前帧定位棋盘，成功后用检测到的几何直接
     * 开始连线（configure），一步到位。在 worker 线程调用。
     */
    private void autoCalibrateNow() {
        if (reader == null || stopping) {
            notify("录屏未就绪，无法免框选");
            bubble("录屏未就绪，无法免框选");
            return;
        }
        // 免框选要靠识别模型认整盘；模型未就绪时按需再试一次装载，仍失败只回显提示。
        if (!ensureRecognizer()) {
            notify("识别模型未就绪：请确认辎重包已配对（或改用全量包）后重试");
            showStatus("识别模型未就绪…（请检查辎重包配对）");
            bubble("免框选失败：识别模型未就绪");
            return;
        }
        if (!beginOneShot("免框选"))
            return;
        try {
            boolean ok = attemptAutoDetect(true);
            showStatus(ok ? "免框选完成，已进入连线" : "免框选未成功，请重试或改用框选");
            // ★ 气泡回执：识别有效 / 免框选失败，一眼即知，不用去盯小棋盘那行小字。
            bubble(ok ? "免框选成功：已识别并进入连线" : "免框选失败：未识别到棋盘，请重试或改用框选");
        } finally {
            endOneShot();
        }
    }

    /**
     * 免框选自动识别一次：全屏找棋盘 → 认出将帅 → 直接上手（configure → startLink）。
     *
     * <p>检测顺序：免框选定位器（YOLOv11 棋盘检测）优先，其次神经角点模型（认棋盘本身，
     * 不认“屏幕正中”），未命中再用边缘密度投影兜底。找到的几何必须能在整盘上认出一局棋
     * （将帅齐全、无兵种超额）才算数——这既过滤误检，也避免把本应用自己的界面当成棋盘。</p>
     *
     * @param manual 是否用户手动点「免框选」触发，仅决定要不要弹通知给用户看。
     * @return 是否成功上手（已发起连线）。
     */
    private boolean attemptAutoDetect(boolean manual) {
        if (reader == null || stopping)
            return false;
        // ★ acquireFrameWithRetry 已优先用无障碍按需截屏、无碍时再抖帧等录屏产帧，
        //   因此静止画面下也能立刻拿到一帧，免框选不再“点了没反应”。
        Frame frame = acquireFrameWithRetry();
        if (frame == null) {
            if (manual)
                notify("未获取到画面，请确保棋盘可见后重试");
            return false;
        }
        BoardGeometry detected;
        ObservedBoard board = null;
        try {
            long now = SystemClock.elapsedRealtime();
            // ★ 一触即发：若后台预热已把棋盘框定位好且尚新，直接跳过全屏检测（YOLO/角点/投影），
            //   只做一次整盘认子即可上手——用户点「免框选」看到的“慢腾腾”大多耗在重复的全屏检测上。
            if (manual && precomputedGeometry != null && now - precomputedGeometryMs <= PRECOMPUTE_TTL_MS) {
                detected = precomputedGeometry;
                board = validateGeometry(frame, detected);
                if (board != null) {
                    Log.i(TAG, "自动识别：命中后台预热几何，直接整盘认子 " + describe(detected));
                } else {
                    Log.w(TAG, "自动识别：预热几何识别不完整，回退完整检测");
                    detected = null;
                }
            } else {
                detected = null;
            }
            if (detected == null) {
                detected = detectGeometryAuto(frame, true);
                if (detected == null) {
                    if (manual)
                        notify("未检测到棋盘，请确保棋盘完整可见后重试");
                    Log.w(TAG, "自动识别：未检测到棋盘" + (manual ? "（手动）" : "（后台）"));
                    return false;
                }
                board = validateGeometry(frame, detected);
            }
        } catch (Exception error) {
            Log.e(TAG, "自动识别异常", error);
            if (manual)
                notify("免框选异常：" + error.getMessage());
            return false;
        } finally {
            frame.close();
        }
        if (board == null) {
            if (manual)
                notify("已找到棋盘，但局面没认全，请点「框选」手动框选");
            Log.w(TAG, "自动识别：检到棋盘但识别不完整，不自动上手 " + describe(detected));
            return false;
        }
        Log.i(TAG, "自动识别成功，免框选直接上手：" + describe(detected));
        // configure → startLink 会在主线程做 UI 操作（showControls、refreshMiniBoard），
        // 不能在 worker 线程直接调，必须 post 到主线程。
        // ★ 先手遵循界面当前默认：用户没另点「红必走/黑必走」就沿用菜单上已显示的「红先/黑先」，
        //   绝不一律当红先——否则界面显示黑先时引擎会照着错的一方思考。（合并自另一路实现）
        final BoardGeometry g = detected;
        final Side defaultSide = overlay != null ? overlay.currentNewGameSide() : Side.RED;
        main.post(() -> configure(g, defaultSide));
        return true;
    }

    /**
     * 全屏定位棋盘：免框选定位器（YOLOv11）优先，神经角点次之，边缘密度投影兜底。
     * 它只负责定位，认子仍走原有的校验路径；三条路任一条命中即可，全不中才判失败。
     */
    private BoardGeometry detectGeometryAuto(Frame frame, boolean allowCenterFallback) {
        YoloBoardLocator locator = ensureYoloLocator();
        if (locator != null) {
            try {
                BoardGeometry detected = toBoardGeometry(locator.detectBoardRect(frame));
                if (detected != null) {
                    Log.i(TAG, "自动识别：免框选 YOLO 命中 " + describe(detected));
                    return detected;
                }
                Log.w(TAG, "自动识别：免框选 YOLO 未命中，回退神经角点");
            } catch (Exception error) {
                Log.e(TAG, "自动识别：免框选 YOLO 异常，回退神经角点", error);
            }
        }
        SetupBoardRecognizer engine = ensureSetupRecognizer();
        if (engine != null) {
            try {
                BoardGeometry detected = engine.detectGeometry(frame);
                if (detected != null) {
                    Log.i(TAG, "自动识别：神经角点命中 " + describe(detected));
                    return detected;
                }
                Log.w(TAG, "自动识别：神经角点未命中，回退边缘密度投影");
            } catch (Exception error) {
                Log.e(TAG, "自动识别：神经角点异常，回退边缘密度投影", error);
            }
        }
        AutoBoardDetector.DetectResult result = AutoBoardDetector.detect(frame, allowCenterFallback);
        if (result.success()) {
            Log.i(TAG, "自动识别：边缘密度投影 " + result.detail);
            return result.geometry;
        }
        Log.w(TAG, "自动识别：边缘密度投影也未命中 " + result.detail);
        return null;
    }

    /**
     * 在候选几何上整盘认一次，够“像一盘棋”（将帅齐全、没有兵种超额）才算检测可信。
     * 摆子识别（更准）优先，不可用或认不完整时回退内置对齐识别。
     */
    private ObservedBoard validateGeometry(Frame frame, BoardGeometry candidate) throws Exception {
        SetupBoardRecognizer engine = setupRecognizer;
        if (engine != null) {
            try {
                ObservedBoard setup = engine.recognize(frame, candidate);
                if (setup != null && setup.canBuildPosition() && setup.surplusCount() == 0)
                    return setup;
            } catch (Exception error) {
                Log.e(TAG, "自动识别：摆子校验异常，回退内置识别", error);
            }
        }
        if (recognizer != null) {
            DualBoardRecognizer.Aligned aligned = recognizer.recognizeAligned(frame, candidate);
            ObservedBoard board = aligned.board();
            if (board.canBuildPosition() && board.surplusCount() == 0)
                return board;
        }
        return null;
    }

    /**
     * 惰性装载逐格识别模型（只在帧 worker 线程调用）。
     *
     * <p>识别模型可能来自辎重包（本机 ZIP 或局域网远程直读），装载过程含磁盘/网络 I/O，
     * 因此绝不在主线程构造——否则辎重包一时取不到会把界面连同连线菜单一起冻住。</p>
     *
     * <p>失败不终止连线服务：返回 false，由调用方回显一行提示；菜单照常可用，
     * 之后每次「同步/校准/免框选」都会再试一次，辎重包一就绪即自动接上。</p>
     *
     * @return 是否已就绪
     */
    private boolean ensureRecognizer() {
        if (recognizer != null)
            return true;
        try {
            recognizer = new DualBoardRecognizer(this, settings.link().modelThreads);
            return true;
        } catch (Exception error) {
            Log.e(TAG, "识别模型装载失败（连线菜单保留，识别类操作暂不可用）", error);
            recognizer = null;
            return false;
        }
    }

    /** 惰性建摆子识别系统（含 pose 角点模型）；不可用返回 null，调用方自行兜底。 */
    private SetupBoardRecognizer ensureSetupRecognizer() {
        try {
            SetupBoardRecognizer engine = setupRecognizer;
            if (engine == null) {
                engine = new SetupBoardRecognizer(this);
                setupRecognizer = engine;
            }
            return engine;
        } catch (Exception error) {
            Log.e(TAG, "摆子识别系统不可用（自动识别将只用边缘密度）", error);
            return null;
        }
    }

    /** 惰性建免框选定位器（YOLOv11 棋盘检测）；不可用返回 null，调用方自行兜底。 */
    private YoloBoardLocator ensureYoloLocator() {
        try {
            YoloBoardLocator locator = yoloLocator;
            if (locator == null) {
                locator = new YoloBoardLocator(this);
                yoloLocator = locator;
            }
            return locator;
        } catch (Exception error) {
            Log.e(TAG, "免框选定位器不可用（自动识别将只用角点/梳齿）", error);
            return null;
        }
    }

    /** 把 YOLO 检出的棋盘框 [left, top, right, bottom] 转成本项目统一的 BoardGeometry。 */
    private static BoardGeometry toBoardGeometry(float[] rect) {
        if (rect == null || rect.length < 4)
            return null;
        try {
            return new BoardGeometry(rect[0], rect[1], rect[2], rect[3]);
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "YOLO 棋盘框几何非法 " + java.util.Arrays.toString(rect));
            return null;
        }
    }

    private static String describe(BoardGeometry geometry) {
        return Math.round(geometry.left()) + "," + Math.round(geometry.top())
            + " – " + Math.round(geometry.right()) + "," + Math.round(geometry.bottom());
    }

    /** 请求悬浮窗做一次几乎看不见的视觉微变，逼合成器重新产出一帧。 */
    private void nudgeForFrame() {
        LinkOverlay target = overlay;
        if (target == null)
            return;
        main.post(target::nudgeForFreshFrame);
    }

    /**
     * 取一帧当前屏幕用于一次性识别。
     *
     * <p>无障碍在线时优先「按需截屏」——不依赖合成器产帧，静止画面也一张到位；无障碍不可用
     * 时才退回录屏路径：先抖一下悬浮窗逼合成器产帧，再连续几次向 ImageReader 要帧再放弃
     * （录屏缓冲常被帧回调恰好取空，稍等片刻通常就有下一张）。等待发生在 worker 线程上，
     * 此间帧回调也在同一 Looper 排队，于是新帧会留在缓冲里等着被取走。</p>
     */
    private Frame acquireFrameWithRetry() {
        Frame shot = captureAccessibilityFrame();
        if (shot != null)
            return shot;
        nudgeForFrame();
        for (int attempt = 0; attempt < 10; attempt++) {
            Image image = reader.acquireLatestImage();
            if (image != null)
                return Frame.owning(image);
            SystemClock.sleep(80);
        }
        return null;
    }

    /**
     * 用当前屏幕的一张新帧完成同步，静止画面也能成。
     *
     * <p>录屏虚拟屏只在画面变化时产帧；用户点「同步/校准」时画面往往是静的，直接要帧
     * 常一张也拿不到，表现为“点了没反应”，非要先在棋盘上点一下、让画面动一动才有用。
     * 这里先抖一下悬浮窗逼合成器产帧，再边等边取，直到这次同步落地或取帧超时——
     * 否则用户会以为按钮坏了。</p>
     *
     * @return 是否已用屏幕帧完成同步；false 表示取帧超时，调用方应退回最后一张已处理输入。
     */
    private boolean synchronizeFromScreen(Side sideToMove) {
        // ★ 无障碍在线时直接按需截屏同步：画面再静止也一张到位，不必抖帧、不必等录屏产帧——
        //   这正是「点了同步/校准没反应，非得先手动点棋盘」的解药。
        Frame shot = captureAccessibilityFrame();
        if (shot != null) {
            try {
                completeSync(linkState.synchronize(recognizer.recognize(shot, geometry), sideToMove),
                        "使用无障碍截屏同步：");
            } catch (Exception error) {
                Log.e(TAG, "无障碍截屏同步失败", error);
            } finally {
                shot.close();
            }
            return pendingSyncSide == null;
        }
        // 无障碍不可用：退回归「抖帧 + 等录屏产帧」的旧路径。
        nudgeForFrame();
        for (int attempt = 0; attempt < 16 && pendingSyncSide != null && !stopping; attempt++) {
            // 半程还没逼出帧，多半是那一抖没让合成器动，再补一抖。
            if (attempt == 8)
                nudgeForFrame();
            if (!queueLatestFrame(reader))
                SystemClock.sleep(80);
        }
        // 缓冲里仍有等待稳定的帧时，最后再据它同步一次。
        if (pendingSyncSide != null)
            synchronizePendingFrame(sideToMove);
        return pendingSyncSide == null;
    }

    /**
     * 无障碍在线时按需截一张当前屏幕，包成统一帧；不可用（未启用 / Android 11 以下 /
     * 系统拒绝）时返回 {@code null}，由调用方回退到录屏帧。在 worker 线程调用。
     */
    private Frame captureAccessibilityFrame() {
        android.graphics.Bitmap shot = LinkAccessibilityService.capture();
        if (shot == null)
            return null;
        return Frame.of(shot);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ECCO 开局判定：独立走子存档，跨校准锁定，不受单帧识别抖动影响
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 依据应用棋局的每次变化维护观棋走子存档：
     * <ul>
     *   <li>实走着法 → 追加入档（按“变化前”的局面上那一格取该着的兵种与颜色）；</li>
     *   <li>局面被重置回标准开局 → 视为真正的新对局，清空存档；</li>
     *   <li>建盘/校准落到开局中途 → 让存档与该局面重新对齐（必要时补回被首次建盘吞掉的头几着）。</li>
     * </ul>
     * 存档只增不显，开局名由它单独判定，因此不受单帧识别与校准影响。
     */
    private void trackOpening(GameState state, GameAction action) {
        if (action instanceof GameAction.Play play) {
            int cursor = state.cursor();
            if (cursor <= 0)
                return;
            Position before = state.positionAt(cursor - 1);
            final PieceType[] type = {null};
            final Side[] side = {null};
            int from = play.move().from;
            before.forEachPiece((square, pieceSide, pieceType) -> {
                if (square == from) {
                    type[0] = pieceType;
                    side[0] = pieceSide;
                }
            });
            if (type[0] != null) {
                openingArchive.append(play.move(), type[0], side[0]);
                publishOpening();
            }
        } else if (action instanceof GameAction.Reset) {
            reconcileOpening(state.position());
        }
    }

    /**
     * 建盘/校准落到某个局面时，让走子存档与之一致，且<b>绝不无谓掐断开局名</b>：
     * <ul>
     *   <li>回到标准开局 → 真正的新对局，清空重来；</li>
     *   <li>存档与棋盘一致 → 原样保留（校准到同一局面属于这种情况）；</li>
     *   <li>存档落后于棋盘（用户在大盘走的着没被识别跟上）→ 顺着存档把差的那几着补上；</li>
     *   <li>已在明确的重新建盘（重新框选/套用方案/免框选）之后 → 才允许彻底重建判定；</li>
     *   <li>其余情况（校准、红必走/黑必走、切引擎等）→ 保留原判定，宁可不动，也不清空。</li>
     * </ul>
     */
    private void reconcileOpening(Position position) {
        boolean mayReset = allowOpeningReset;
        allowOpeningReset = false;
        if (isStandardStart(position)) {
            openingArchive.clear();
            openingAligned = true;
        } else if (openingArchive.matches(position)) {
            openingAligned = true;
        } else if (realignArchive(position)) {
            // ★ 用户悔棋、或没按引擎推荐走自己的着法：把作废的那一段着法整段撤销，
            //   棋谱改记用户真实走出来的着法，ECCO 从此报的是用户自己的谱。
            openingAligned = true;
            Log.i(TAG, "棋谱对齐：撤销作废着法，改记用户走法");
        } else if (mayReset) {
            // 重新框选/套用方案/免框选后，允许按新局面从头重建判定。
            openingArchive.clear();
            openingAligned = extendArchiveTo(position);
        }
        // 否则：保留原判定，绝不因校准之类的操作把开局名掐断。
        publishOpening();
    }

    /**
     * 把走子存档重新对齐到用户真实走出来的局面——<b>棋谱始终跟着用户走</b>。
     *
     * <p>用户悔棋、或用户走了和引擎推荐不一样的一着时，应用棋局里会残留一步“用户并不承认”
     * 的着法。这里在最近几着里挑一个「剩余需要补的着法最少」的回退点，把那之后作废的着法
     * 整段撤销，再按用户实际走出来的着法补齐。</p>
     */
    private boolean realignArchive(Position position) {
        int size = openingArchive.size();
        int bestKeep = -1;
        int bestTail = Integer.MAX_VALUE;
        List<Move> bestTailMoves = null;
        int lowest = Math.max(0, size - ALIGN_LOOKBACK);
        for (int keep = size; keep >= lowest; keep--) {
            List<Move> tail = OpeningReconstructor.path(openingArchive.prefixPosition(keep), position);
            if (tail == null || tail.size() >= bestTail)
                continue;
            bestTail = tail.size();
            bestKeep = keep;
            bestTailMoves = tail;
            if (bestTail <= 1)
                break;
        }
        if (bestKeep < 0 || bestTailMoves == null)
            return false;
        openingArchive.truncate(bestKeep);
        return appendPath(openingArchive.replayPosition(), bestTailMoves);
    }

    /**
     * 从走子存档重放到的局面，有界搜索出走到 {@code position} 的着法并按序补入存档；
     * 成功返回 true。存档为空时即等价于“从标准开局补齐前缀”。
     */
    private boolean extendArchiveTo(Position position) {
        Position replay = openingArchive.replayPosition();
        List<Move> tail = OpeningReconstructor.path(replay, position);
        if (tail == null)
            return false;
        return appendPath(replay, tail);
    }

    /** 从 {@code from} 起把 {@code path} 逐着补进存档；任一步取不到起子兵种即整体失败。 */
    private boolean appendPath(Position from, List<Move> path) {
        Position step = from;
        for (Move move : path) {
            final PieceType[] type = {null};
            final Side[] side = {null};
            int fromSquare = move.from;
            step.forEachPiece((square, pieceSide, pieceType) -> {
                if (square == fromSquare) {
                    type[0] = pieceType;
                    side[0] = pieceSide;
                }
            });
            if (type[0] == null)
                return false;
            openingArchive.append(move, type[0], side[0]);
            step = step.play(move);
        }
        return true;
    }

    /** 局面是否恰为标准红先开局（用于识别“真正的新对局”）。 */
    private static boolean isStandardStart(Position position) {
        ObservedBoard observed = ObservedBoard.from(position);
        for (int square = 0; square < Board.SQUARES; square++)
            if (observed.at(square) != STANDARD_START.at(square))
                return false;
        return true;
    }

    /** 把最新开局名推给悬浮窗；未对齐或判不出时传 null，由界面显示占位符。 */
    private void publishOpening() {
        final OpeningArchive.Label label = openingAligned ? openingArchive.label() : null;
        if (main != null)
            main.post(() -> {
                if (overlay != null)
                    overlay.showOpening(label == null ? null : label.code,
                        label == null ? null : label.opening,
                        label == null ? null : label.variation);
            });
    }

    /** 引擎当前执子方；两边都没接管时为 null。 */
    private Side engineSide() {
        if (gameRuntime.aiEnabled(Side.RED))
            return Side.RED;
        if (gameRuntime.aiEnabled(Side.BLACK))
            return Side.BLACK;
        return null;
    }

    private void startRecognition(BoardGeometry geometry, Side bottomSide, Side sideToMove) {
        discardPendingFrame();
        this.geometry = geometry;
        linkState = LinkState.start(bottomSide, sideToMove);
        frameGate.reset();
        firstChangeMs = -1L;
        lastChangeMs = -1L;
        recognizer.reset();
        recognizer.setBottomSide(bottomSide);
    }

    /**
     * 仅在帧 worker 写入连线状态与走子注入状态。
     */
    private void postToWorker(Runnable task) {
        if (worker != null)
            worker.post(task);
    }

    /**
     * 所有 WindowManager 操作都回到主线程；棋局与箭头可来自 UI 或帧 worker。
     */
    private void refreshMiniBoard(com.yk.xiangqi.core.GameState state) {
        if (main != null)
            main.post(() -> {
                if (overlay != null)
                    overlay.showMiniBoard(state, gameRuntime.arrows());
            });
    }

    /** 引擎思考细节更新时推送到主线程悬浮窗。 */
    private void refreshThinking(GameRuntime.Analysis analysis) {
        if (main != null)
            main.post(() -> {
                if (overlay != null)
                    overlay.showThinking(analysis);
            });
    }

    private void writeLocalMove(com.yk.xiangqi.core.GameState state) {
        if (geometry == null || linkState == null)
            return;
        java.util.List<com.yk.xiangqi.core.Move> moves = state.moves();
        if (moves.isEmpty() || moves.size() == writtenPly)
            return;
        writtenPly = moves.size();
        com.yk.xiangqi.core.Move move = moves.get(moves.size() - 1);
        Settings.Link config = settings.link();
        GesturePlan plan = GesturePlan.forMove(geometry, linkState.bottomSide(), move,
                config.tapDurationMs, config.tapIntervalMs);
        long writebackAt = SystemClock.elapsedRealtime();
        linkState = linkState.awaitWriteback(writebackAt);
        writebackStartedMs = writebackAt;
        boolean dispatched = LinkAccessibilityService.dispatch(plan, new LinkAccessibilityService.GestureResult() {
            @Override
            public void completed() {
                Log.i(TAG, "已回写走子：" + move.uci());
                // ★ 一步一校准（走后）：等落子动画落定，立刻强制对齐一次，确认这步真落到了大棋盘上，
                //   并把接力棒交回下一拍——避免“走完了却以为没走/走了”而漏掉后续回合。
                postToWorker(() -> {
                    writebackStartedMs = 0;
                    if (worker != null)
                        worker.postDelayed(() -> {
                            forceSyncRequested = true;
                            lastForcedSyncMs = 0L;
                        }, POST_MOVE_SYNC_DELAY_MS);
                });
            }

            @Override
            public void cancelled() {
                postToWorker(() -> {
                    writebackStartedMs = 0;
                    linkState = linkState.cancelWriteback();
                });
                Log.w(TAG, "回写走子被系统取消：" + move.uci());
            }
        });
        if (!dispatched) {
            linkState = linkState.cancelWriteback();
            writebackStartedMs = 0;
            Log.w(TAG, "无法回写走子：无障碍服务未启用，走子=" + move.uci());
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                    notification("无法自动走子：请在系统无障碍设置中启用 YK 象棋"));
        } else {
            Log.i(TAG, "请求回写走子：" + move.uci());
        }
    }

    /**
     * 接管 ImageReader 的最新帧。只有棋盘区域的轻量指纹发生实质变化，才保存该帧并
     * 重置稳定等待；静态合成帧直接释放，不进入预处理或 ONNX。
     *
     * @return 是否实际消费了一张投屏帧。
     */
    private boolean queueLatestFrame(ImageReader source) {
        if (source != reader || recognizer == null || geometry == null || linkState == null || stopping)
            return false;
        Frame frame = null;
        try {
            // 首帧会重新推断底边方向；每帧机会同步给整盘识别模型。
            recognizer.setBottomSide(linkState.bottomSide());
            Image image = source.acquireLatestImage();
            if (image == null)
                return false;
            frame = Frame.owning(image);
            // 拿得到帧就说明画面在动——静止的合成器根本不推帧。据此驱动自愈节拍。
            lastFrameMs = SystemClock.elapsedRealtime();
            // ★ 失步不再「冻结」：过去这里会把每一帧直接丢掉，导致必须手动点同步/校准
            //   才恢复（一步一油门）。现在改为限频自动重对齐：DESYNCED 时每 ≥400ms 允许
            //   一帧进入识别，交给归约/自愈链路自己纠回来，用户不必再手动点。
            if (pendingSyncSide == null && linkState.phase() == LinkState.Phase.DESYNCED) {
                long nowAuto = SystemClock.elapsedRealtime();
                if (nowAuto - lastAutoResyncMs < AUTO_RESYNC_MIN_INTERVAL_MS) {
                    frame.close();
                    return true;
                }
                lastAutoResyncMs = nowAuto;
                autoResyncCount++;
                sDesynced = true;
                Log.i(TAG, "失步自动重同步（第 " + autoResyncCount + " 次）");
            }
            if (pendingSyncSide == null) {
                if (!frameGate.changed(frame, geometry)) {
                    frame.close();
                    return true;
                }
                long now = SystemClock.elapsedRealtime();
                if (pendingFrame == null)
                    firstChangeMs = now;
                lastChangeMs = now;
                // 画面确有推进：刷新「静止」计时，免得刚走完子又被当成静止期重复硬认。
                lastCaptureProgressMs = now;
                sLastProgressMs = System.currentTimeMillis();
                frameGate.accept();
            } else {
                frameGate.accept(frame, geometry);
            }
            replacePendingFrame(frame);
            frame = null;
            worker.removeCallbacks(settledFrame);
            if (pendingSyncSide != null) {
                Side side = pendingSyncSide;
                synchronizePendingFrame(side);
            } else {
                // ★ 稳定帧下限 300ms：识别必须等走子动画/选中框落定，否则会抓到半动画帧，
                //   表现为「车忽隐忽现 / 某子认丢」（2.9.271 及更早默认只有 100ms）。
                worker.postDelayed(settledFrame, Math.max(settings.link().frameSettleMs, 300L));
            }
            return true;
        } catch (Exception error) {
            if (frame != null)
                frame.close();
            failRecognition(error);
            return true;
        }
    }

    /** frameSettleMs 内没有更晚的实质棋盘变化时，处理暂存的最终帧。 */
    private void processSettledFrame() {
        if (recognizer == null || stopping)
            return;
        Frame frame = takePendingFrame();
        if (frame == null)
            return;
        try {
            long inferenceStartedMs = SystemClock.elapsedRealtime();
            reduceLink(recognizer.recognize(frame, geometry), inferenceStartedMs);
        } catch (Exception error) {
            failRecognition(error);
        } finally {
            frame.close();
        }
    }

    private void reduceLink(ObservedBoard board, long inferenceStartedMs) {
        LinkResult result = linkState.reduce(gameRuntime.state().position(), board, SystemClock.elapsedRealtime());
        linkState = result.state();
        long now = SystemClock.elapsedRealtime();
        if (result.action().kind() != LinkAction.Kind.NONE) {
            // ★ 任何“真正推动了局面”的动作都刷新进度：连点器据此判断是否已卡住
            sLastProgressMs = System.currentTimeMillis();
        }
        if (result.action().kind() != LinkAction.Kind.NONE && firstChangeMs >= 0L)
            Log.i(TAG, "识别链路：首差异→末差异=" + (lastChangeMs - firstChangeMs)
                + "ms，末差异→推理=" + (inferenceStartedMs - lastChangeMs)
                + "ms，推理与归约=" + (now - inferenceStartedMs) + "ms，动作=" + result.action().kind());
        firstChangeMs = -1L;
        lastChangeMs = -1L;
        apply(result.action());
    }

    /**
     * 主动取帧节拍入口：按 {@link #FRAME_POLL_MS} 无限自我续拍，直到连线结束。
     *
     * <p>这是把「被动等帧回调」翻成「主动呼吸式取帧」的开关。每一拍都硬要一张当前画面，
     * 拿不到就在静止期兜一次硬认，绝不因画面不动而停摆——连线因此始终跟得上大棋盘，
     * 也少让用户手按同步/校准。</p>
     */
    private void keepAlive() {
        if (stopping || worker == null)
            return;
        try {
            captureOnce();
        } catch (Exception error) {
            Log.w(TAG, "主动取帧异常", error);
        } finally {
            if (!stopping && worker != null)
                worker.postDelayed(keepAliveTick, FRAME_POLL_MS);
        }
    }

    /**
     * 一拍主动取帧（呼吸式）：
     * <ol>
     *   <li>先把合成器当下的最新帧要过来，走既有的「变化门 → 稳定等待 → 识别」链路。
     *       画面在动时这一条就足够，识别随走子即时刷新。</li>
     *   <li>画面彻底静止（合成器不产帧、单纯要帧要不到）时，也按固定节拍主动硬认一次：
     *       无障碍在线就按需截屏（静止画面一张到位），不可用就退回抖帧逼合成器产帧。
     *       如此「取帧」像呼吸一样不间断，静止局面下引擎也总能拿到最新 FEN。</li>
     * </ol>
     * 只在 worker 线程运行；{@link #keepAliveTick} 到点自我续拍，连线结束随线程一起停。
     */
    private void captureOnce() {
        if (!running() || stopping)
            return;
        long now = SystemClock.elapsedRealtime();
        // 尚未框选/免框选：趁用户还在看菜单，后台用 YOLO 悄悄把棋盘框定位好，
        // 点「免框选」时即可跳过全屏检测，只做一次整盘认子——“一触即发”靠的就是这一步预热。
        if (geometry == null || linkState == null) {
            precomputeGeometry(now);
            return;
        }
        // ★ 回写看门狗：一次自动走子若迟迟不落地（无障碍回调丢了），连线会永久停在
        //   「等待回写」进入休眠——正是“小棋盘对得上、轮到自己却不动”。超时即强制取消、恢复呼吸。
        if (linkState.phase() == LinkState.Phase.WAITING_WRITEBACK) {
            if (writebackStartedMs > 0 && now - writebackStartedMs >= WRITEBACK_TIMEOUT_MS) {
                linkState = linkState.cancelWriteback();
                writebackStartedMs = 0;
                Log.w(TAG, "回写超时（连线休眠），强制取消并恢复呼吸");
            } else {
                return;
            }
        }
        queueLatestFrame(reader);
        if (pendingSyncSide != null)
            return;
        // ★ 一步一校准 / 2 秒无差别覆盖：把大棋盘与小棋盘强制对齐一次（与人工点「校准」同效），
        //   顺带重新点火「对齐→刷新→引擎→走子」整条链路，杜绝某回合因漏了校准而休眠。
        Side assigned = engineSide();
        boolean engineThinking = assigned != null
                && gameRuntime.state().position().sideToMove() == assigned;
        boolean due = forceSyncRequested || now - lastForcedSyncMs >= FORCED_SYNC_MS;
        boolean stable = now - lastCaptureProgressMs >= STATIC_RECOGNIZE_MS;
        if (due && stable && !engineThinking) {
            // 轮到人走（或无人执子）时定期无差别校准，绝不因画面静止而漏掉某回合的对齐。
            forceSyncRequested = false;
            lastForcedSyncMs = now;
            forceAlign(forcedAlignSide(), "周期性强制校准");
        }
        sEngineThinking = engineThinking;
        if (engineThinking && stable && now - lastForcedSyncMs >= TURN_STALL_MS) {
            // ★ 轮次停滞兜底：轮到引擎走子却久不出招（小棋盘对得上、大棋盘却不动），
            //   强制重对齐一次以重新点火整条链路，而不是一直枯等。
            lastForcedSyncMs = now;
            forceAlign(assigned, "轮次停滞兜底");
        } else if (now - lastCaptureProgressMs >= STATIC_RECOGNIZE_MS) {
            lastCaptureProgressMs = now;
            Log.i(TAG, "静止期主动硬认当前棋盘，结果喂给引擎");
            autoReconnect();
        }
    }

    /**
     * 强制校准：把大棋盘与小棋盘重新对齐一次，效果与人工点「校准」一模一样。
     * 用于「走后校准」与「2 秒无差别覆盖」两条兜底，确保任何回合都不会因漏了校准而休眠。
     */
    private void forceAlign(Side side, String reason) {
        if (stopping || linkState == null || geometry == null || recognizer == null)
            return;
        if (pendingSyncSide != null)
            return;
        if (linkState.phase() == LinkState.Phase.WAITING_WRITEBACK)
            return;
        pendingSyncSide = side;
        Log.i(TAG, "强制校准（" + reason + "）：行棋=" + side);
        if (!synchronizeFromScreen(side)) {
            pendingSyncSide = null;
            Log.w(TAG, "强制校准未取到帧，留待下一拍重试");
        }
    }

    /**
     * 强制校准该按哪一方行棋：标准开局按界面默认先手（用户没另选就沿用已显示的「红先/黑先」），
     * 其余局面按小棋盘当前行棋方——与人工点「校准」时按钮传的先手同一口径。
     */
    private Side forcedAlignSide() {
        Position position = gameRuntime.state().position();
        if (isStandardStart(position) && overlay != null) {
            Side declared = overlay.currentNewGameSide();
            if (declared != null)
                return declared;
        }
        return position.sideToMove();
    }

    /**
     * 免框选预热定位：尚未框选时，后台悄悄用 YOLO 定位棋盘框，缓存几何与时刻。
     * 点「免框选」时若缓存尚新，即可跳过耗时的全屏检测，只做一次整盘认子。
     */
    private void precomputeGeometry(long now) {
        if (now - lastPrecomputeMs < PRECOMPUTE_MS)
            return;
        lastPrecomputeMs = now;
        Frame frame = captureAccessibilityFrame();
        if (frame == null)
            return;
        try {
            YoloBoardLocator locator = ensureYoloLocator();
            if (locator == null)
                return;
            BoardGeometry detected = toBoardGeometry(locator.detectBoardRect(frame));
            if (detected != null) {
                precomputedGeometry = detected;
                precomputedGeometryMs = now;
            }
        } catch (Exception error) {
            Log.w(TAG, "免框选预热定位失败", error);
        } finally {
            frame.close();
        }
    }

    /**
     * 静默自愈：抖一帧、认出整盘、按当前局面归约一次。
     *
     * <p>归约本身已经能容忍单帧误读、并在失步后自动对齐，所以这里只需把「静止期缺的那一帧」
     * 补上即可，不改先手、不动几何、不打扰用户。认出即归约，认不出就静待下一拍。</p>
     */
    private void autoReconnect() {
        if (stopping || linkState == null || geometry == null || recognizer == null
                || pendingSyncSide != null)
            return;
        if (linkState.phase() == LinkState.Phase.WAITING_WRITEBACK)
            // 正在等一次自动走子落地，让盘面先稳下来，别在这个节骨眼上抢帧。
            return;
        try {
            Frame frame = acquireFrameWithRetry();
            if (frame == null)
                return;
            try {
                reduceLink(recognizer.recognize(frame, geometry), SystemClock.elapsedRealtime());
            } finally {
                frame.close();
            }
        } catch (Exception error) {
            // 自愈失败不该打断连线，静待下一拍再试。
            Log.w(TAG, "自愈重连失败", error);
        }
    }

    /**
     * 无障碍窗口变化回调：棋盘一动就立刻重认，把「等节拍」压缩成「随动即认」。
     *
     * <p>由无障碍事件线程调用，只做节流与投递，识别仍在帧 worker 上跑。节流窗口内的抖动
     * （走子动画、UI 刷新）合并成一次重认，既不丢响应，又不会疯狂重跑 ONNX。</p>
     */
    private void onWindowChanged() {
        Handler target = worker;
        if (target == null || stopping)
            return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastEventReconnectMs < EVENT_RECONNECT_MIN_INTERVAL_MS)
            return;
        lastEventReconnectMs = now;
        target.post(() -> {
            if (stopping || worker == null)
                return;
            // 画面刚动过就省一次：正常识别路径（queueLatestFrame → settledFrame）会自己接住。
            if (SystemClock.elapsedRealtime() - lastFrameMs < EVENT_RECONNECT_MIN_INTERVAL_MS)
                return;
            lastCaptureProgressMs = SystemClock.elapsedRealtime();
            autoReconnect();
        });
    }

    private void replacePendingFrame(Frame frame) {
        discardPendingFrame();
        pendingFrame = frame;
    }

    private Frame takePendingFrame() {
        Frame frame = pendingFrame;
        pendingFrame = null;
        return frame;
    }

    private void discardPendingFrame() {
        Frame frame = takePendingFrame();
        if (frame != null)
            frame.close();
    }

    private void failRecognition(Exception error) {
        if (linkState != null)
            linkState = linkState.stopRecognition();
        discardPendingFrame();
        Log.e(TAG, "帧处理失败", error);
        if (!stopping)
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("连线识别失败，请同步局面"));
    }

    /**
     * 将连线纯归约输出明确写回应用棋局；外部来源不会再次注入无障碍点击。
     */
    private void apply(LinkAction action) {
        if (stopping || action == null)
            return;
        switch (action.kind()) {
            case PLAY -> {
                Log.i(TAG, "识别到外部走子：" + action.move().uci());
                gameRuntime.reduce(new GameAction.Play(action.move()), GameRuntime.Origin.EXTERNAL);
            }
            case RESET -> {
                gameRuntime.reduce(new GameAction.Reset(action.position()), GameRuntime.Origin.EXTERNAL);
                writtenPly = 0;
                Log.i(TAG, "已同步棋盘：行棋=" + action.position().sideToMove()
                        + "，棋子=" + pieceCount(action.position()));
            }
            case DESYNCED ->
                    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("连线失步，请同步局面"));
            case NONE -> {
            }
        }
    }

    private static int pieceCount(com.yk.xiangqi.core.Position position) {
        final int[] count = {0};
        position.forEachPiece((square, side, type) -> count[0]++);
        return count[0];
    }

    /**
     * ImageReader 只能提供最新合成帧，不能判断棋盘是否真的改变。这里以每个交叉点
     * 周围的五个亮度样本做固定阈值比较；它远轻于预处理和 ONNX，只负责决定是否重置
     * 稳定等待，不负责识别棋子。
     */
    private static final class FrameGate {
        private static final float[] OFFSETS = {0f, 0f, -.18f, 0f, .18f, 0f, 0f, -.18f, 0f, .18f};
        private static final int SAMPLE_COUNT = BoardGeometry.FILES * BoardGeometry.RANKS * (OFFSETS.length / 2);
        private static final int LUMA_DELTA = 20;
        private static final int CHANGED_SAMPLES = 3;
        private final byte[] previous = new byte[SAMPLE_COUNT];
        private final byte[] sampled = new byte[SAMPLE_COUNT];
        private boolean initialized;

        void reset() {
            initialized = false;
        }

        boolean changed(Frame frame, BoardGeometry geometry) {
            sample(frame, geometry);
            if (!initialized)
                return true;
            int changed = 0;
            for (int index = 0; index < SAMPLE_COUNT; index++) {
                int difference = Math.abs(Byte.toUnsignedInt(sampled[index]) - Byte.toUnsignedInt(previous[index]));
                if (difference >= LUMA_DELTA && ++changed >= CHANGED_SAMPLES)
                    return true;
            }
            return false;
        }

        void accept() {
            System.arraycopy(sampled, 0, previous, 0, SAMPLE_COUNT);
            initialized = true;
        }

        void accept(Frame frame, BoardGeometry geometry) {
            sample(frame, geometry);
            accept();
        }

        private void sample(Frame frame, BoardGeometry geometry) {
            java.nio.ByteBuffer pixels = frame.buffer();
            int width = frame.width();
            int height = frame.height();
            int rowStride = frame.rowStride();
            int pixelStride = frame.pixelStride();
            int index = 0;
            for (int row = 0; row < BoardGeometry.RANKS; row++)
                for (int column = 0; column < BoardGeometry.FILES; column++)
                    for (int offset = 0; offset < OFFSETS.length; offset += 2) {
                        int x = clamp(Math.round(geometry.left() + (column + OFFSETS[offset]) * geometry.stepX()), 0, width - 1);
                        int y = clamp(Math.round(geometry.top() + (row + OFFSETS[offset + 1]) * geometry.stepY()), 0, height - 1);
                        int at = y * rowStride + x * pixelStride;
                        int red = pixels.get(at) & 255;
                        int green = pixels.get(at + 1) & 255;
                        int blue = pixels.get(at + 2) & 255;
                        sampled[index++] = (byte) ((red * 77 + green * 150 + blue * 29) >>> 8);
                    }
        }

        private static int clamp(int value, int lower, int upper) {
            return Math.max(lower, Math.min(upper, value));
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        LinkAccessibilityService.setWindowChangeListener(null);
        if (worker != null) {
            worker.removeCallbacks(settledFrame);
            worker.removeCallbacks(keepAliveTick);
        }
        if (overlay != null) overlay.close();
        if (gameRuntime != null && gameListener != null) gameRuntime.removeListener(gameListener);
        if (gameRuntime != null && arrowListener != null)
            gameRuntime.removeArrowListener(arrowListener);
        if (gameRuntime != null && analysisListener != null)
            gameRuntime.removeAnalysisListener(analysisListener);
        if (gameRuntime != null)
            gameRuntime.setLinkActive(false);
        if (worker != null && worker.getLooper().getThread().isAlive())
            worker.post(this::closeCapture);
        else
            closeCapture();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    /**
     * 录屏帧、ONNX 会话均由帧 worker 使用，因此也必须在该线程关闭。
     * onDestroy 不等待正在运行的推理；本任务会排在它之后执行。
     */
    private void closeCapture() {
        discardPendingFrame();
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (display != null) {
            display.release();
            display = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (setupRecognizer != null) {
            setupRecognizer.close();
            setupRecognizer = null;
        }
        if (yoloLocator != null) {
            yoloLocator.close();
            yoloLocator = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (workerThread != null)
            workerThread.quitSafely();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context, int resultCode, Intent resultData) {
        Intent intent = new Intent(context, LinkForegroundService.class).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, resultData);
        context.startForegroundService(intent);
    }

}
