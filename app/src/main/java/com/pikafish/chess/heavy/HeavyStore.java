package com.pikafish.chess.heavy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ★★★ 统一资产读取层：操作包（轻量）优先从「辎重包」读大件，读不到再回退 APK 内置 assets。★★★
 *
 * <p>辎重包就是一个 ZIP（扩展名可写成 .apk／.zip，本质相同），内部条目路径与
 * {@code assets/} 下的相对路径一一对应，例如：</p>
 *
 * <pre>
 *   models/pose_model.onnx
 *   models/layout_model.onnx
 *   models/yolo_model.onnx
 *   engine/pikafish.nnue
 *   ccstockfish/entry.nnue
 *   fairy/xiangqi.nnue
 *   link/yolov11.onnx
 *   link/piece_classifier.onnx
 *   qipu_cbl/index.json
 *   qipu_cbl/data/开局_0.cbl
 * </pre>
 *
 * <p>同一份文件三处通用：手机上由本 App 直接读；电脑上任何解压工具都能打开；
 * 不同设备之间整份拷贝。不必为不同设备维护多套格式。</p>
 *
 * <p>读取顺序：</p>
 * <ol>
 *   <li><b>用户指定的辎重包</b> —— SAF 选择的文件（落一份到私有目录）、本地绝对路径、
 *       或局域网 {@code http(s)://} 地址（<b>远程直读</b>：按需只拉所需片段，不整份取回本机）。</li>
 *   <li><b>APK 内置 assets</b> —— 全量包自带，轻量包没有。二者共用同一套读取代码，
 *       所以外置／内置对上层业务完全透明。</li>
 * </ol>
 *
 * <p>跨设备协同：本机能对外共享辎重包（{@link HeavyHttpServer}），也能从别的设备
 * 直读（{@link RemoteHeavy}）。同一份辎重包既可留在电脑上，也可留在另一台手机上，操作包照常运行。</p>
 */
public final class HeavyStore {
    private HeavyStore() {}

    private static final String PREF = "heavy_store";
    private static final String KEY_SOURCE = "source";
    private static final String DIR_NAME = "heavy";
    private static final String SLOT_NAME = "heavy_pack.zip";

    /** ★ 配对同步时，辎重包主机对外提供整份文件的统一路径（服务端忽略路径、只认这一份文件）。 */
    public static final String REMOTE_PACK_PATH = "/heavy.zip";

    /** 下载/拷贝进度回调（可为 null）。 */
    public interface Progress {
        void onProgress(long done, long total);
    }

    private static final Object LOCK = new Object();
    private static ZipFile zip;
    private static File zipFile;
    /** 远程直读实例（URL 变化时重建）；探测／读取失败置 remoteFailed，本次会话不再重试。 */
    private static RemoteHeavy remote;
    private static String remoteKey;
    private static boolean remoteFailed;

    // ═════════════════ 辎重包来源 ═════════════════

    /** 当前的辎重包来源描述（本地路径 / content:// / http(s)://），未设置返回空串。 */
    public static String source(Context context) {
        return prefs(context).getString(KEY_SOURCE, "");
    }

    /** 记录辎重包来源并让缓存失效（下次读取重新打开）。 */
    public static void setSource(Context context, String source) {
        prefs(context).edit().putString(KEY_SOURCE, source == null ? "" : source).apply();
        synchronized (LOCK) {
            closeLocked();
            remote = null;
            remoteKey = null;
            remoteFailed = false;
        }
    }

    /** 清除辎重包来源（回到"完全依赖内置 assets"状态）。 */
    public static void clear(Context context) {
        setSource(context, "");
    }

    /** 辎重包私有落盘位置（用户经 SAF 选择或网络获取时，都规整到这一份上）。 */
    public static File localSlot(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // 目录建不出来时退到 filesDir 根，尽量不中断
            return new File(context.getFilesDir(), SLOT_NAME);
        }
        return new File(dir, SLOT_NAME);
    }

    /**
     * 本机上的辎重包文件（真实文件系统上的普通文件，可直接 ZipFile 随机读）。
     *
     * <p>未设置、文件缺失、或来源是「远程直读」时返回 {@code null} —— 远程直读不占本机。</p>
     */
    public static File localFile(Context context) {
        String src = source(context);
        if (src == null || src.isEmpty() || isRemote(src)) {
            return null;
        }
        File file;
        try {
            if (src.startsWith("file://")) {
                String path = Uri.parse(src).getPath();
                file = path == null ? null : new File(path);
            } else if (src.startsWith("content://")) {
                // SAF 导入的流式文件已在导入时规整到私有目录
                file = localSlot(context);
            } else {
                file = new File(src);
            }
        } catch (Exception e) {
            return null;
        }
        return (file != null && file.isFile() && file.length() > 0) ? file : null;
    }

    /** 兼容旧调用：当前可用的本机辎重包文件。 */
    public static File resolvedFile(Context context) {
        return localFile(context);
    }

    /** 远程直读地址；来源为 {@code http(s)://} 时返回，否则 {@code null}。 */
    public static String remoteUrl(Context context) {
        String src = source(context);
        return isRemote(src) ? src : null;
    }

    /** 是否已经就绪一个可用辎重包（本机文件或远程直读）。 */
    public static boolean hasPackage(Context context) {
        return localFile(context) != null || (remoteUrl(context) != null && !remoteFailed);
    }

    // ═════════════════ 导入辎重包 ═════════════════

    /**
     * 从 SAF 选择的 URI 导入辎重包。
     *
     * <p>{@code file://} 直接引用原路径（不复制）；{@code content://} 因为是流式访问、
     * 无法随机读 ZIP，规整一份到私有目录后再用。</p>
     */
    public static File importUri(Context context, Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("未选择文件");
        }
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            File direct = new File(uri.getPath() == null ? "" : uri.getPath());
            verifyPackage(direct);
            setSource(context, direct.getAbsolutePath());
            return direct;
        }
        File slot = localSlot(context);
        ensureParent(slot);
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("无法读取所选文件");
        }
        try (InputStream in = input; OutputStream out = new FileOutputStream(slot)) {
            copyStream(in, out, null);
        }
        verifyPackage(slot);
        setSource(context, slot.getAbsolutePath());
        return slot;
    }

    /** 从局域网／网络地址下载辎重包到私有目录（跨设备协同：电脑或另一台手机提供文件即可）。 */
    public static File importUrl(Context context, String url, Progress progress) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("请填写网络地址");
        }
        String target = url.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "http://" + target;
        }
        File slot = localSlot(context);
        ensureParent(slot);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("下载失败，HTTP " + code);
            }
            long total = connection.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(slot)) {
                copyStream(in, out, progress, total);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        verifyPackage(slot);
        setSource(context, slot.getAbsolutePath());
        return slot;
    }

    /**
     * 指向别的设备上的辎重包并「远程直读」：不整份下载，只按需拉取所需片段。
     *
     * <p>适用于「辎重包留在电脑／另一台手机上，操作包跨设备直读」的场景。要求对方支持
     * HTTP Range 分段读取（本 App 自带的共享服务、电脑侧 {@code caddy file-server} 等均支持）；
     * 对方不支持时会抛出提示，改走 {@link #importUrl}「下载到本机」。</p>
     */
    public static void attachRemote(Context context, String url) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("请填写地址");
        }
        String target = normalizeUrl(url.trim());
        RemoteHeavy probe = new RemoteHeavy(target);
        probe.probe();
        if (!probe.rangeSupported()) {
            throw new IOException("对方未开启分段读取，无法直读；可改用「下载到本机」");
        }
        probe.loadIndex();
        setSource(context, target);
    }

    // ═════════════════ 读取入口（所有大件资产统一走这里） ═════════════════

    /**
     * 打开某个资产（相对 {@code assets/} 的路径）。
     *
     * <p>辎重包里命中就用辎重包，否则回退 APK 内置 assets；两边都没有时抛
     * {@link java.io.FileNotFoundException}，与 {@code AssetManager.open} 行为一致，
     * 上层原有的 try/catch 逻辑无需改动。</p>
     */
    public static InputStream open(Context context, String path) throws IOException {
        String clean = normalize(path);
        InputStream remoteIn = openRemote(context, clean);
        if (remoteIn != null) {
            return remoteIn;
        }
        ZipFile archive = archive(context);
        if (archive != null) {
            ZipEntry entry = entryOf(archive, clean);
            if (entry != null) {
                return new BufferedInputStream(archive.getInputStream(entry));
            }
        }
        return context.getAssets().open(clean);
    }

    /**
     * ★★★ 本地优先读取：内置 assets → 本机辎重包 → 远程直读。★★★
     *
     * <p>谁该用它：<b>连线识别这类"必须在本地立刻拿到"的组件</b>。</p>
     *
     * <p>为什么要有它：{@link #open} 是「远程优先」，本意是让轻量包按需只拉需要的片段。
     * 但对识别模型来说这很危险——一旦处在"异地引擎 + 远程直读辎重包"档位，而对方电脑
     * 网络慢/休眠/没开，模型加载就会卡住或失败，连线直接报"缺组件、连不动"。
     * 而识别（取帧 → 采样 → 推理）本来就该<b>就地取材</b>：画面像素在本地，模型也该在本地。
     * 因此识别栈一律走本方法：本地有就立刻用，实在没有才退到远程。</p>
     *
     * <p>算棋则相反——引擎可以异地（那边算、这边收着法），这一层不受本方法影响。</p>
     */
    public static InputStream openLocalFirst(Context context, String path) throws IOException {
        String clean = normalize(path);
        try {
            return context.getAssets().open(clean);
        } catch (IOException missing) {
            // 内置没有，继续往下找
        }
        ZipFile archive = archive(context);
        if (archive != null) {
            ZipEntry entry = entryOf(archive, clean);
            if (entry != null) {
                return new BufferedInputStream(archive.getInputStream(entry));
            }
        }
        InputStream remoteIn = openRemote(context, clean);
        if (remoteIn != null) {
            return remoteIn;
        }
        throw new IOException("资产缺失：" + clean);
    }

    /** 辎重包（远程或本机）或内置 assets 里是否存在该资产。 */
    public static boolean exists(Context context, String path) {
        String clean = normalize(path);
        RemoteHeavy remote = remote(context);
        if (remote != null && remoteIndexed(remote)
                && (remote.exists(clean) || remote.exists("assets/" + clean))) {
            return true;
        }
        ZipFile archive = archive(context);
        if (archive != null && entryOf(archive, clean) != null) {
            return true;
        }
        return builtinExists(context, clean);
    }

    /** 该资产是否来自内置 assets（全量包为 true，轻量包为 false）。 */
    public static boolean builtinExists(Context context, String path) {
        try (InputStream in = context.getAssets().open(normalize(path))) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** 资产字节数；取不到返回 -1。 */
    public static long size(Context context, String path) {
        String clean = normalize(path);
        RemoteHeavy remote = remote(context);
        if (remote != null && remoteIndexed(remote)) {
            long remoteSize = remote.size(clean);
            if (remoteSize < 0) {
                remoteSize = remote.size("assets/" + clean);
            }
            if (remoteSize >= 0) {
                return remoteSize;
            }
        }
        ZipFile archive = archive(context);
        if (archive != null) {
            ZipEntry entry = entryOf(archive, clean);
            if (entry != null) {
                long size = entry.getSize();
                if (size >= 0) {
                    return size;
                }
            }
        }
        try (InputStream in = context.getAssets().open(clean, 2)) {
            return in.available();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 列出某目录下的直接子项（与 {@code AssetManager.list} 语义一致），
     * 把远程辎重包、本机辎重包与内置 assets 的条目合并去重。
     */
    public static String[] list(Context context, String dir) {
        String clean = normalize(dir);
        List<String> names = new ArrayList<>();
        RemoteHeavy remote = remote(context);
        if (remote != null && remoteIndexed(remote)) {
            collectChildren(remote.list(clean), names);
        }
        ZipFile archive = archive(context);
        if (archive != null) {
            String prefix = clean.isEmpty() ? "" : clean + "/";
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                collectChild(name, prefix, names);
            }
        }
        try {
            String[] builtin = context.getAssets().list(clean);
            if (builtin != null) {
                for (String name : builtin) {
                    if (!names.contains(name)) {
                        names.add(name);
                    }
                }
            }
        } catch (IOException ignored) {
            // 内置目录不存在（轻量包）时只返回辎重包内容
        }
        return names.toArray(new String[0]);
    }

    /** 把 ZIP 条目名按目录层级归并出直接子项。 */
    private static void collectChild(String name, String prefix, List<String> names) {
        if (!name.startsWith(prefix)) {
            return;
        }
        String rest = name.substring(prefix.length());
        if (rest.isEmpty()) {
            return;
        }
        int slash = rest.indexOf('/');
        String child = slash < 0 ? rest : rest.substring(0, slash);
        if (!child.isEmpty() && !names.contains(child)) {
            names.add(child);
        }
    }

    private static void collectChildren(String[] children, List<String> names) {
        if (children == null) {
            return;
        }
        for (String child : children) {
            if (child != null && !child.isEmpty() && !names.contains(child)) {
                names.add(child);
            }
        }
    }

    /** 把某资产整份拷到 {@code out}；用于需要按文件路径加载的模型/权重。 */
    public static void copyTo(Context context, String path, File out) throws IOException {
        InputStream in = open(context, path);
        try (InputStream input = in; OutputStream output = new FileOutputStream(out)) {
            copyStream(input, output, null);
        }
    }

    /** 人类可读的辎重包状态（供界面展示）。 */
    public static String describe(Context context) {
        String url = remoteUrl(context);
        if (url != null && !remoteFailed) {
            return "远程直读：" + url;
        }
        File file = localFile(context);
        if (file == null) {
            return "未设置辎重包";
        }
        return file.getAbsolutePath() + "（" + String.format(Locale.ROOT, "%.1f MB", file.length() / 1048576.0) + "）";
    }

    // ═════════════════ 远程直读 ═════════════════

    private static boolean isRemote(String src) {
        return src != null && (src.startsWith("http://") || src.startsWith("https://"));
    }

    private static String normalizeUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "http://" + url;
    }

    /** 取当前来源对应的远程直读实例（URL 变化时重建）。 */
    private static RemoteHeavy remote(Context context) {
        String url = remoteUrl(context);
        if (url == null) {
            return null;
        }
        synchronized (LOCK) {
            if (remote != null && url.equals(remoteKey)) {
                return remote;
            }
            remote = new RemoteHeavy(url);
            remoteKey = url;
            remoteFailed = false;
            return remote;
        }
    }

    /** 确保远程索引就绪；失败则标记 {@code remoteFailed} 并返回 false（本次会话不再重试）。 */
    private static boolean remoteIndexed(RemoteHeavy target) {
        if (remoteFailed) {
            return false;
        }
        try {
            target.loadIndex();
            return true;
        } catch (IOException e) {
            remoteFailed = true;
            return false;
        }
    }

    /** 从远程辎重包打开条目；未命中或远程不可用返回 {@code null}（交由上层回退）。 */
    private static InputStream openRemote(Context context, String clean) {
        RemoteHeavy target = remote(context);
        if (target == null || !remoteIndexed(target)) {
            return null;
        }
        try {
            if (target.exists(clean)) {
                return target.open(clean);
            }
            String alt = "assets/" + clean;
            if (target.exists(alt)) {
                return target.open(alt);
            }
        } catch (IOException e) {
            // 读取途中失败：不阻断，交回上层回退
        }
        return null;
    }

    // ═════════════════ 内部实现 ═════════════════

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static ZipFile archive(Context context) {
        File file = resolvedFile(context);
        if (file == null) {
            return null;
        }
        synchronized (LOCK) {
            if (zip != null && zipFile != null && zipFile.equals(file)) {
                return zip;
            }
            closeLocked();
            try {
                zip = new ZipFile(file);
                zipFile = file;
            } catch (IOException e) {
                zip = null;
                zipFile = null;
            }
            return zip;
        }
    }

    private static void closeLocked() {
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // 关闭失败无碍，GC 时兜底
            }
        }
        zip = null;
        zipFile = null;
    }

    /** 条目查找：优先按 assets 相对路径，其次兼容多带一层 {@code assets/} 前缀的打包方式。 */
    private static ZipEntry entryOf(ZipFile archive, String clean) {
        ZipEntry entry = archive.getEntry(clean);
        if (entry == null) {
            entry = archive.getEntry("assets/" + clean);
        }
        return entry;
    }

    private static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String clean = path.replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
    }

    /** 校验目标真是 ZIP（魔数 PK），避免把随便一个文件当辎重包。 */
    private static void verifyPackage(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() == 0) {
            throw new IOException("辎重包文件缺失或为空");
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            int read = 0;
            while (read < 4) {
                int n = in.read(magic, read, 4 - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < 4 || magic[0] != 0x50 || magic[1] != 0x4b) {
                throw new IOException("这不是有效的辎重包（应为 ZIP/APK 格式）");
            }
        }
    }

    private static void copyStream(InputStream input, OutputStream output, Progress progress) throws IOException {
        copyStream(input, output, progress, -1L);
    }

    private static void copyStream(InputStream input, OutputStream output, Progress progress, long total)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long done = 0L;
        int n;
        while ((n = input.read(buffer)) >= 0) {
            output.write(buffer, 0, n);
            done += n;
            if (progress != null) {
                progress.onProgress(done, total);
            }
        }
        output.flush();
    }
}
