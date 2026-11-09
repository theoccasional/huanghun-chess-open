package com.pikafish.chess.heavy;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * ★★★ 辎重包「远程直读」：把放在电脑或另一台手机上的辎重包当本地 ZIP 一样按需读取，不整份拷回本机。★★★
 *
 * <p>辎重包本身就是一个 ZIP。本类不下载整份文件，而是通过 HTTP 的 <b>Range 分段请求</b>
 * 只取用得到的那几段：</p>
 * <ol>
 *   <li>先探测对方是否支持 Range（{@code Accept-Ranges}）与文件总长；</li>
 *   <li>只读文件尾部那点字节拿到 ZIP 中央目录（EOCD），建立「条目名 → 偏移/大小/压缩方式」索引；</li>
 *   <li>{@link #open(String)} 时，只按需拉取该条目对应的一小段压缩数据，就地 inflate 还原。</li>
 * </ol>
 *
 * <p>因此读一个模型就是拉一个模型那么大，读一个小棋库就是拉一个小棋库那么大 —— 本机不留整份副本，
 * 与「辎重包放在别的设备上、操作包跨设备直读」完全对齐。</p>
 *
 * <p>若对方不支持 Range（返回 200 而非 206），{@link #probe()} 会标记 {@link #rangeSupported()} 为 false，
 * 由上层改走「整份下载到本机」的兜底路径。</p>
 */
public final class RemoteHeavy {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long EOCD_SIG = 0x06054b50L;
    private static final long CD_SIG = 0x02014b50L;
    private static final long LFH_SIG = 0x04034b50L;
    /** 读 ZIP 尾部找 EOCD 的窗口：22 字节固定尾 + 最多 65535 字节注释。 */
    private static final int TAIL_WINDOW = 66_000;

    /** 中央目录里的一条记录。 */
    private static final class Entry {
        final String name;
        final int method;
        final long compSize;
        final long uncompSize;
        final long localOffset;

        Entry(String name, int method, long compSize, long uncompSize, long localOffset) {
            this.name = name;
            this.method = method;
            this.compSize = compSize;
            this.uncompSize = uncompSize;
            this.localOffset = localOffset;
        }
    }

    private final String url;
    private long totalSize = -1L;
    private boolean rangeSupported;
    private boolean probed;
    private Map<String, Entry> index;

    public RemoteHeavy(String url) {
        this.url = url;
    }

    public String url() {
        return url;
    }

    public boolean rangeSupported() {
        return rangeSupported;
    }

    public long totalSize() {
        return totalSize;
    }

    // ═════════════════ 探测 / 建索引 ═════════════════

    /** 探测对方是否支持 Range 与辎重包总长；重复调用只做一次。 */
    public synchronized void probe() throws IOException {
        if (probed) {
            return;
        }
        HttpURLConnection connection = connect(0, 0);
        try {
            int code = connection.getResponseCode();
            if (code == 206) {
                totalSize = parseTotal(connection.getHeaderField("Content-Range"));
                rangeSupported = totalSize > 0;
            } else if (code == 200) {
                // 对方不支持 Range：取到总长即可，上层会改走整份下载兜底。
                totalSize = connection.getContentLengthLong();
                rangeSupported = false;
            } else {
                throw new IOException("辎重包地址无法访问（HTTP " + code + "）");
            }
        } finally {
            connection.disconnect();
        }
        if (totalSize <= 0) {
            throw new IOException("无法获取辎重包大小");
        }
        probed = true;
    }

    /** 只读尾部 + 中央目录区，建立条目索引。 */
    public synchronized void loadIndex() throws IOException {
        probe();
        if (!rangeSupported) {
            throw new IOException("对方未开启分段读取（Range），无法直读");
        }
        if (index != null) {
            return;
        }
        int tailLen = (int) Math.min(totalSize, (long) TAIL_WINDOW);
        byte[] tail = readRange(totalSize - tailLen, totalSize - 1);
        int eocd = lastIndexOf(tail, EOCD_SIG);
        if (eocd < 0) {
            throw new IOException("辎重包不是有效的 ZIP（缺少目录结尾）");
        }
        long cdSize = u32(tail, eocd + 12);
        long cdOffset = u32(tail, eocd + 16);
        if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
            throw new IOException("辎重包为 ZIP64，无法直读，请改用「下载到本机」");
        }
        byte[] cd = readRange(cdOffset, cdOffset + cdSize - 1);
        Map<String, Entry> map = new LinkedHashMap<>();
        int p = 0;
        while (p + 46 <= cd.length && u32(cd, p) == CD_SIG) {
            int method = (int) u16(cd, p + 10);
            long compSize = u32(cd, p + 20);
            long uncompSize = u32(cd, p + 24);
            int nameLen = (int) u16(cd, p + 28);
            int extraLen = (int) u16(cd, p + 30);
            int commentLen = (int) u16(cd, p + 32);
            long localOffset = u32(cd, p + 42);
            if (p + 46 + nameLen > cd.length) {
                break;
            }
            String name = new String(cd, p + 46, nameLen, UTF8);
            map.put(name, new Entry(name, method, compSize, uncompSize, localOffset));
            p += 46 + nameLen + extraLen + commentLen;
        }
        if (map.isEmpty()) {
            throw new IOException("辎重包目录为空");
        }
        index = map;
    }

    // ═════════════════ 读取入口 ═════════════════

    /** 按条目名读取（只拉该条目那一小段）。 */
    public synchronized InputStream open(String name) throws IOException {
        loadIndex();
        Entry entry = index.get(name);
        if (entry == null) {
            throw new FileNotFoundException("辎重包内无此条目: " + name);
        }
        if (entry.compSize <= 0) {
            return new BufferedInputStream(new ByteArrayInputStream(new byte[0]));
        }
        byte[] header = readRange(entry.localOffset, entry.localOffset + 29);
        if (u32(header, 0) != LFH_SIG) {
            throw new IOException("条目头损坏: " + name);
        }
        int nameLen = (int) u16(header, 26);
        int extraLen = (int) u16(header, 28);
        long dataStart = entry.localOffset + 30 + nameLen + extraLen;
        byte[] data = readRange(dataStart, dataStart + entry.compSize - 1);
        InputStream raw = new ByteArrayInputStream(data);
        if (entry.method == 0) {
            return new BufferedInputStream(raw);
        }
        if (entry.method == 8) {
            return new BufferedInputStream(new InflaterInputStream(raw, new Inflater(true), 64 * 1024));
        }
        throw new IOException("不支持的压缩方式: " + entry.method);
    }

    public synchronized boolean exists(String name) {
        return index != null && index.containsKey(name);
    }

    /** 条目解压后大小；索引未建立返回 -1。 */
    public synchronized long size(String name) {
        if (index == null) {
            return -1L;
        }
        Entry entry = index.get(name);
        return entry == null ? -1L : entry.uncompSize;
    }

    /** 列出某目录下的直接子项（与 AssetManager.list 语义一致）。 */
    public synchronized String[] list(String dir) {
        if (index == null) {
            return new String[0];
        }
        String clean = dir == null ? "" : dir;
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        String prefix = clean.isEmpty() ? "" : clean + "/";
        List<String> names = new ArrayList<>();
        for (String name : index.keySet()) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            String rest = name.substring(prefix.length());
            if (rest.isEmpty()) {
                continue;
            }
            int slash = rest.indexOf('/');
            String child = slash < 0 ? rest : rest.substring(0, slash);
            if (!child.isEmpty() && !names.contains(child)) {
                names.add(child);
            }
        }
        return names.toArray(new String[0]);
    }

    // ═════════════════ HTTP Range 底层 ═════════════════

    private HttpURLConnection connect(long start, long end) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        // ★ 连接超时收紧到 5 秒：辎重包主机不在线时快速失败，交由上层回退，
        //   绝不长时间僵住（原来的 12 秒会让「远程直读」在对方离线时像卡死一样）。
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (start >= 0) {
            connection.setRequestProperty("Range", "bytes=" + start + "-" + (end >= 0 ? end : ""));
        }
        return connection;
    }

    private byte[] readRange(long start, long end) throws IOException {
        if (end < start) {
            throw new IOException("读取范围无效");
        }
        long want = end - start + 1;
        HttpURLConnection connection = connect(start, end);
        try {
            int code = connection.getResponseCode();
            if (code == 200 && start > 0) {
                // 对方忽略了 Range，直接回整份 —— 这里必须拒绝，否则会读到错位数据。
                throw new IOException("对方未按范围返回（HTTP 200）");
            }
            if (code != 206 && code != 200) {
                throw new IOException("直读失败（HTTP " + code + "）");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(want, 1L << 20));
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                long got = 0L;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    int take = (int) Math.min((long) read, want - got);
                    if (take > 0) {
                        out.write(buffer, 0, take);
                    }
                    got += read;
                    if (got >= want) {
                        break;
                    }
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    // ═════════════════ 小工具 ═════════════════

    /** ZIP 字段为小端、无符号。 */
    private static long u32(byte[] b, int i) {
        return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8)
                | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24);
    }

    private static long u16(byte[] b, int i) {
        return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8);
    }

    private static long parseTotal(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1L;
        }
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static int lastIndexOf(byte[] b, long sig) {
        for (int i = b.length - 4; i >= 0; i--) {
            if ((b[i] & 0xFF) == (sig & 0xFF)
                    && (b[i + 1] & 0xFF) == ((sig >> 8) & 0xFF)
                    && (b[i + 2] & 0xFF) == ((sig >> 16) & 0xFF)
                    && (b[i + 3] & 0xFF) == ((sig >> 24) & 0xFF)) {
                return i;
            }
        }
        return -1;
    }
}
