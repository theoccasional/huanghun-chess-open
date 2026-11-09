package com.yk.xiangqi;

import com.yk.xiangqi.core.GameAction;
import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Side;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * 应用进程内共享的运行期棋局。
 *
 * <p>{@link GameState} 依然是不可变纯状态；此类只在 UI、连线和引擎之间串行发布
 * action 的归约结果，并持有与棋局协作有关的 AI 开关。</p>
 */
public final class GameRuntime {
    public enum Origin {LOCAL, EXTERNAL}

    public interface Listener {
        void onGameChanged(GameState state, GameAction action, Origin origin);
    }

    public interface AiListener {
        void onAiChanged(Side side, boolean enabled);
    }

    /** 当前分析主变的轻量投影，供连线小棋盘绘制箭头，不属于核心棋局状态。 */
    public interface ArrowListener {
        void onArrowsChanged(List<Move> arrows);
    }

    /** 引擎思考细节的订阅者，连线悬浮窗用它展示分值、深度与中文主变。 */
    public interface AnalysisListener {
        void onAnalysisChanged(Analysis analysis);
    }

    /** 连线运行开关变化，ViewModel 借此在连线期间持续分析当前局面。 */
    public interface LinkListener {
        void onLinkActiveChanged(boolean active);
    }

    /** 当前加载引擎变化，主界面与连线悬浮窗据此刷新“正在使用哪个引擎”。 */
    public interface EngineListener {
        void onEngineChanged(EngineInfo info);
    }

    /**
     * 连线悬浮窗向界面下达的控制指令：切换引擎档案、修改搜索上限。
     *
     * <p>引擎进程与搜索循环都由界面持有，悬浮窗只发号施令，不越权直接改。</p>
     */
    public interface LinkControlListener {
        /** 悬浮窗选了某个引擎档案；界面负责真正重启引擎。 */
        void onLinkEngineSelected(String engineId);

        /** 悬浮窗改了层数/步时；界面按新参数重开分析。 */
        void onLinkSearchChanged();

        /** 悬浮窗切换云库开关；界面负责落盘并重新查询着法。 */
        default void onLinkCloudToggled(boolean enabled) {
        }

        /** 悬浮窗勾选/取消勾选某个本地开局库；界面负责落盘并重新加载。 */
        default void onLinkBookSelectionChanged(String bookId, boolean selected) {
        }

        /** 悬浮窗要求重启引擎；界面负责重置为安全局面并重新拉起进程。 */
        default void onLinkRestartEngine() {
        }
    }

    /**
     * 当前加载引擎的身份与配置摘要。
     *
     * <p>{@code uciName} 是引擎握手时自报的 {@code id name}，通常含版本号与构建日期；
     * {@code name} 是用户在设置里给的本地名称。两者并列展示，用户就能分辨当前加载的
     * 到底是哪一个引擎、哪一个版本。</p>
     */
    public static final class EngineInfo {
        public final String id;
        public final String name;
        public final String uciName;
        public final String author;
        public final String path;
        public final int threads;
        public final int hashMb;
        /** 当前引擎实际加载的权重文件名；没有权重时为空。 */
        public final String networkName;
        /** 权重体积，例如「48.4 MB」；没有权重时为「0 MB」。 */
        public final String networkSize;

        public EngineInfo(String id, String name, String uciName, String author, String path, int threads, int hashMb) {
            this(id, name, uciName, author, path, threads, hashMb, "", "");
        }

        public EngineInfo(String id, String name, String uciName, String author, String path, int threads, int hashMb,
                          String networkName, String networkSize) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.uciName = uciName == null ? "" : uciName;
            this.author = author == null ? "" : author;
            this.path = path == null ? "" : path;
            this.threads = threads;
            this.hashMb = hashMb;
            this.networkName = networkName == null ? "" : networkName;
            this.networkSize = networkSize == null ? "" : networkSize;
        }

        public static final EngineInfo EMPTY = new EngineInfo("", "", "", "", "", 0, 0);

        public boolean isEmpty() {
            return name.isEmpty() && uciName.isEmpty();
        }

        /** 单行标题：优先引擎自报名称，缺失时退回本地名称。 */
        public String title() {
            if (!uciName.isEmpty())
                return name.isEmpty() || name.equals(uciName) ? uciName : name + "（" + uciName + "）";
            return name;
        }
    }

    /**
     * 引擎分析的轻量投影：字段均已格式化为可直接显示的文本，主变为中文记谱。
     * 分值统一换算到红方视角，正值表示红方占优。
     *
     * <p>{@code source} 是当前行棋方上一着走子来源的标注：主变（引擎思考）、
     * 库招（本地开局库）、云库（在线云库）、均有（本地库与云库都有这一着）。
     * 手工走子或开局时为空。</p>
     */
    public static final class Analysis {
        public final String score;
        public final String verdict;
        public final String depth;
        public final String time;
        public final String nodes;
        public final String nps;
        public final Side sideToMove;
        public final List<String> pv;
        public final String source;

        public Analysis(String score, String verdict, String depth, String time, String nodes, String nps,
                        Side sideToMove, List<String> pv) {
            this(score, verdict, depth, time, nodes, nps, sideToMove, pv, null);
        }

        public Analysis(String score, String verdict, String depth, String time, String nodes, String nps,
                        Side sideToMove, List<String> pv, String source) {
            this.score = score;
            this.verdict = verdict;
            this.depth = depth;
            this.time = time;
            this.nodes = nodes;
            this.nps = nps;
            this.sideToMove = sideToMove;
            this.pv = pv == null ? List.of() : List.copyOf(pv);
            this.source = source;
        }

        public static final Analysis EMPTY =
                new Analysis(null, null, null, null, null, null, null, List.of(), null);

        public boolean isEmpty() {
            return score == null && verdict == null && pv.isEmpty();
        }
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AiListener> aiListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ArrowListener> arrowListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AnalysisListener> analysisListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LinkListener> linkListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EngineListener> engineListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LinkControlListener> linkControlListeners = new CopyOnWriteArrayList<>();
    private GameState state = new GameState();
    private List<Move> arrows = List.of();
    private Analysis analysis = Analysis.EMPTY;
    private EngineInfo engineInfo = EngineInfo.EMPTY;
    private boolean linkActive;
    private boolean redAi;
    private boolean blackAi;

    public synchronized GameState state() {
        return state;
    }

    public boolean reduce(GameAction action, Origin origin) {
        GameState next;
        synchronized (this) {
            next = state.reduce(action);
            if (next == state)
                return false;
            state = next;
        }
        for (Listener listener : listeners)
            listener.onGameChanged(next, action, origin);
        return true;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized boolean aiEnabled(Side side) {
        return side == Side.RED ? redAi : blackAi;
    }

    public void setAi(Side side, boolean enabled) {
        synchronized (this) {
            if ((side == Side.RED ? redAi : blackAi) == enabled)
                return;
            if (side == Side.RED)
                redAi = enabled;
            else
                blackAi = enabled;
        }
        for (AiListener listener : aiListeners)
            listener.onAiChanged(side, enabled);
    }

    public void addAiListener(AiListener listener) {
        aiListeners.add(listener);
    }

    public void removeAiListener(AiListener listener) {
        aiListeners.remove(listener);
    }

    public synchronized List<Move> arrows() {
        return arrows;
    }

    public void setArrows(List<Move> value) {
        List<Move> next = List.copyOf(value);
        synchronized (this) {
            if (arrows.equals(next))
                return;
            arrows = next;
        }
        for (ArrowListener listener : arrowListeners)
            listener.onArrowsChanged(next);
    }

    public void addArrowListener(ArrowListener listener) {
        arrowListeners.add(listener);
    }

    public void removeArrowListener(ArrowListener listener) {
        arrowListeners.remove(listener);
    }

    public synchronized Analysis analysis() {
        return analysis;
    }

    public void setAnalysis(Analysis value) {
        if (value == null)
            value = Analysis.EMPTY;
        synchronized (this) {
            if (analysis == value)
                return;
            analysis = value;
        }
        for (AnalysisListener listener : analysisListeners)
            listener.onAnalysisChanged(value);
    }

    public void addAnalysisListener(AnalysisListener listener) {
        analysisListeners.add(listener);
    }

    public void removeAnalysisListener(AnalysisListener listener) {
        analysisListeners.remove(listener);
    }

    public synchronized boolean linkActive() {
        return linkActive;
    }

    public void setLinkActive(boolean value) {
        synchronized (this) {
            if (linkActive == value)
                return;
            linkActive = value;
        }
        for (LinkListener listener : linkListeners)
            listener.onLinkActiveChanged(value);
    }

    public void addLinkListener(LinkListener listener) {
        linkListeners.add(listener);
    }

    public void removeLinkListener(LinkListener listener) {
        linkListeners.remove(listener);
    }

    public synchronized EngineInfo engineInfo() {
        return engineInfo;
    }

    public void setEngineInfo(EngineInfo value) {
        if (value == null)
            value = EngineInfo.EMPTY;
        synchronized (this) {
            if (engineInfo == value)
                return;
            engineInfo = value;
        }
        for (EngineListener listener : engineListeners)
            listener.onEngineChanged(value);
    }

    public void addEngineListener(EngineListener listener) {
        engineListeners.add(listener);
    }

    public void removeEngineListener(EngineListener listener) {
        engineListeners.remove(listener);
    }

    public void addLinkControlListener(LinkControlListener listener) {
        linkControlListeners.add(listener);
    }

    public void removeLinkControlListener(LinkControlListener listener) {
        linkControlListeners.remove(listener);
    }

    /** 悬浮窗请求切到某个引擎档案；真正重启引擎由界面完成。 */
    public void requestLinkEngine(String engineId) {
        if (engineId == null || engineId.isEmpty())
            return;
        for (LinkControlListener listener : linkControlListeners)
            listener.onLinkEngineSelected(engineId);
    }

    /** 悬浮窗改了层数/步时；界面据此重开分析。 */
    public void notifyLinkSearchChanged() {
        for (LinkControlListener listener : linkControlListeners)
            listener.onLinkSearchChanged();
    }

    /** 悬浮窗切了云库开关；界面落盘并重新查询着法。 */
    public void notifyLinkCloudToggled(boolean enabled) {
        for (LinkControlListener listener : linkControlListeners)
            listener.onLinkCloudToggled(enabled);
    }

    /** 悬浮窗勾选/取消勾选某本地开局库；界面落盘并重新加载。 */
    public void notifyLinkBookSelectionChanged(String bookId, boolean selected) {
        for (LinkControlListener listener : linkControlListeners)
            listener.onLinkBookSelectionChanged(bookId, selected);
    }

    /** 悬浮窗要求重启引擎；界面重置为安全局面并重新拉起进程。 */
    public void notifyLinkRestartEngine() {
        for (LinkControlListener listener : linkControlListeners)
            listener.onLinkRestartEngine();
    }
}
