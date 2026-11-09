package com.yk.xiangqi.engine;

import android.content.Context;
import android.net.Uri;

import com.pikafish.chess.heavy.HeavyStore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 将内置引擎安装、并把用户选择的资源导入应用私有目录。 */
public final class EngineFiles {
    private EngineFiles() {}

    /** 单个压缩条目的读取上限，只用于挡住把巨大无关文件整块读进来。 */
    private static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;

    /** 非可执行文件至少这么大才当成 NNUE 权重，免得把说明文档误认成权重。 */
    private static final long MIN_NNUE_BYTES = 1024L * 1024;

    /** 压缩包里明确不是引擎/权重的杂项，直接跳过。 */
    private static final String[] DOCUMENT_SUFFIXES = {
        ".txt", ".md", ".pdf", ".html", ".htm", ".json", ".xml", ".ini", ".bat", ".cmd", ".sh",
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
    };

    /** 用户这一次选中的东西属于哪一类。 */
    public enum Kind { ENGINE, NETWORK }

    /** 一个引擎目录的成员：可执行文件，以及它配套的权重（可能没有）。 */
    public static final class Bundle {
        public final File engine;
        /** 引擎实际要读的权重文件；压缩包里没有权重时为 {@code null}。 */
        public final File network;

        Bundle(File engine, File network) {
            this.engine = engine;
            this.network = network;
        }
    }

    /** 一次导入的结果：是引擎还是权重，以及落在哪里。 */
    public static final class Imported {
        public final Kind kind;
        public final File directory;
        public final File engine;
        public final File network;

        Imported(Kind kind, File directory, File engine, File network) {
            this.kind = kind;
            this.directory = directory;
            this.engine = engine;
            this.network = network;
        }
    }

    public static File installBundled(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "engines/pikafish");
        ensureDirectory(directory);

        File executable = new File(directory, "pikafish");
        copyAsset(context, "engine/pikafish", executable);
        copyAsset(context, "engine/pikafish.nnue", new File(directory, "pikafish.nnue"));
        if (!isElf(executable))
            throw new IOException("内置引擎不是可执行文件");
        makeExecutable(executable);
        return directory;
    }

    /**
     * 一次导入同时解决引擎和权重：用户只管选一个文件，剩下的按内容自己认。
     *
     * <ul>
     *   <li>压缩包（ZIP/APK）：里面的可执行文件当引擎，权重<b>保持原文件名</b>放在引擎旁边，
     *       引擎照它自己认的名字就能找到；</li>
     *   <li>裸可执行文件（ELF）：直接当引擎；</li>
     *   <li>既不是引擎也不是压缩包（例如 .nnue 权重）：放进当前引擎的权重坑位，不新建引擎。</li>
     * </ul>
     *
     * <p>压缩包按魔数认人、不看扩展名；一个包里有多套架构时，按设备支持的 ABI 挑对应的那一套。
     * 因此换过名字、换过目录结构、或打包了多个架构的引擎包都能照收。</p>
     */
    public static Imported importSelection(Context context, Uri uri, File currentEngine, List<String> abis) throws IOException {
        File probe = newProbe(context);
        try {
            copyUri(context, uri, probe);
            if (!isZip(probe) && !isElf(probe)) {
                String picked = uri.getLastPathSegment();
                String base = (picked == null ? "" : baseName(picked)).toLowerCase(Locale.ROOT);
                // 权重没有可靠魔数，用体积和扩展名兜底，避免随手点中的杂文件覆盖掉已有权重。
                if (probe.length() < MIN_NNUE_BYTES && !base.endsWith(".nnue"))
                    throw new IOException("所选文件既不是引擎（ELF 或压缩包），也不像 NNUE 权重");
                if (currentEngine == null || locate(currentEngine) == null)
                    throw new IOException("这看起来是权重文件，但还没有引擎，请先导入引擎压缩包");
                File network = new File(currentEngine, "network.nnue");
                replace(probe, network);
                return new Imported(Kind.NETWORK, currentEngine, locate(currentEngine).engine, network);
            }
            File directory = newCustomDirectory(context);
            boolean complete = false;
            try {
                Bundle bundle;
                if (isZip(probe)) {
                    bundle = extractArchive(probe, directory, abis);
                } else {
                    File executable = new File(directory, "engine");
                    replace(probe, executable);
                    makeExecutable(executable);
                    bundle = new Bundle(executable, null);
                }
                complete = true;
                return new Imported(Kind.ENGINE, directory, bundle.engine, bundle.network);
            } finally {
                if (!complete)
                    deleteIncompleteBundle(directory);
            }
        } finally {
            probe.delete();
        }
    }

    /**
     * 在已导入的引擎目录里找出可执行文件与它的权重。
     *
     * <p>权重保持原文件名，所以这里同样按内容与后缀认人，不依赖固定文件名。</p>
     */
    public static Bundle locate(File directory) {
        if (directory == null || !directory.isDirectory())
            return null;
        File[] files = directory.listFiles();
        if (files == null)
            return null;
        File engine = null;
        File network = null;
        for (File file : files) {
            if (!file.isFile())
                continue;
            if (isElf(file)) {
                // 依赖库（.so）优先让位给真正的可执行文件；同优先级再比体积。
                if (engine == null || betterEngine(file, engine))
                    engine = file;
            } else if (file.length() >= MIN_NNUE_BYTES || file.getName().toLowerCase(Locale.ROOT).endsWith(".nnue")) {
                if (network == null || preferNetwork(file, network))
                    network = file;
            }
        }
        return engine == null ? null : new Bundle(engine, network);
    }

    private static boolean betterEngine(File candidate, File current) {
        boolean candidateSo = candidate.getName().toLowerCase(Locale.ROOT).endsWith(".so");
        boolean currentSo = current.getName().toLowerCase(Locale.ROOT).endsWith(".so");
        if (candidateSo != currentSo)
            return currentSo;
        return candidate.length() > current.length();
    }

    /** 明确叫 .nnue 的比只是「够大」的更靠谱；同类里取体积大的。 */
    private static boolean preferNetwork(File candidate, File current) {
        boolean candidateNamed = candidate.getName().toLowerCase(Locale.ROOT).endsWith(".nnue");
        boolean currentNamed = current.getName().toLowerCase(Locale.ROOT).endsWith(".nnue");
        if (candidateNamed != currentNamed)
            return candidateNamed;
        return candidate.length() > current.length();
    }

    /** 探测文件与原包同处一个存储卷，保证随后的搬运是同卷改名而不是整块复制。 */
    private static File newProbe(Context context) throws IOException {
        File parent = new File(context.getFilesDir(), "engines");
        ensureDirectory(parent);
        return File.createTempFile("selection", ".tmp", parent);
    }

    /** 引擎架构说明（如「64 位 ARM（arm64-v8a）」）；读不出来时如实回报，不假装支持。 */
    public static String architecture(File executable) {
        if (executable == null || !executable.isFile())
            return "未知";
        try (InputStream input = new FileInputStream(executable)) {
            byte[] header = new byte[20];
            if (readFully(input, header) != 20 || !isElf(header))
                return "非 ELF 文件";
            int bits = header[4] == 2 ? 64 : header[4] == 1 ? 32 : 0;
            int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
            String name = switch (machine) {
                case 183 -> "ARM（arm64-v8a）";
                case 40 -> "ARM（armeabi-v7a）";
                case 62 -> "x86_64";
                case 3 -> "x86";
                default -> "machine " + machine;
            };
            return (bits == 0 ? "" : bits + " 位 ") + name;
        } catch (IOException e) {
            return "未知";
        }
    }

    /** 按字节数给出便于核对的权重体积，例如「48.4 MB」。 */
    public static String sizeText(File file) {
        if (file == null || !file.isFile())
            return "0 MB";
        return String.format(Locale.ROOT, "%.1f MB", file.length() / 1048576.0);
    }

    /** 只删除本应用创建的自定义引擎目录，不接受任意路径。 */
    public static void deleteBundle(Context context, File directory) throws IOException {
        File parent = new File(context.getFilesDir(), "engines").getCanonicalFile();
        File target = directory.getCanonicalFile();
        if (!parent.equals(target.getParentFile()) || !target.getName().startsWith("custom-"))
            throw new IOException("不是可删除的自定义引擎目录");
        File[] files = target.listFiles();
        if (files != null)
            for (File file : files)
                if (!file.delete())
                    throw new IOException("无法删除引擎文件: " + file.getName());
        if (target.exists() && !target.delete())
            throw new IOException("无法删除引擎目录");
    }

    /** 压缩包条目的候选信息：解出来之后才按内容判断它到底是什么。 */
    private static final class Candidate {
        final String name;
        final String base;
        final String parent;
        final File file;
        final long size;
        final boolean elf;

        Candidate(String name, String base, String parent, File file, long size, boolean elf) {
            this.name = name;
            this.base = base;
            this.parent = parent;
            this.file = file;
            this.size = size;
            this.elf = elf;
        }
    }

    /**
     * 逐个读压缩包条目，按内容而不是文件名决定谁是引擎、谁是权重。
     *
     * <p>选中之后一律以<b>压缩包里的原始文件名</b>落盘，因为很多引擎是按自己认的名字
     * 去旁边找权重（例如 Pikafish 找 pikafish.nnue、CCStockfish 找 entry.nnue），
     * 改名会让它们找不到权重而直接退出。</p>
     */
    private static Bundle extractArchive(File archive, File directory, List<String> abis) throws IOException {
        List<Candidate> candidates = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                if (entry.isDirectory())
                    continue;
                String base = baseName(entry.getName());
                if (base.isEmpty() || isDocumentation(base))
                    continue;
                File part = File.createTempFile("part", ".bin", directory);
                long size = copyCapped(zip, part, MAX_ENTRY_BYTES);
                if (size <= 0) {
                    part.delete();
                    continue;
                }
                candidates.add(new Candidate(entry.getName(), base, parentPath(entry.getName()), part, size, isElf(part)));
            }
        }
        try {
            Candidate engine = pickEngine(candidates, abis);
            if (engine == null)
                throw new IOException("压缩包里没找到安卓可执行文件（ELF）");
            Candidate network = pickNetwork(candidates);

            File executable = new File(directory, engine.base);
            replace(engine.file, executable);
            makeExecutable(executable);

            File weight = null;
            if (network != null) {
                weight = new File(directory, network.base);
                replace(network.file, weight);
            }
            // 与引擎同目录的动态库要一起搬过去，否则动态链接的引擎起不来。
            for (Candidate other : candidates) {
                if (other == engine || other == network)
                    continue;
                if (other.elf && other.parent.equals(engine.parent) && other.base.endsWith(".so"))
                    replace(other.file, new File(directory, other.base));
            }
            return new Bundle(executable, weight);
        } finally {
            for (Candidate candidate : candidates)
                candidate.file.delete();
        }
    }

    /** 多架构包里挑设备能跑的那一套：优先 ABI 命中，其次非 .so，最后比体积。 */
    private static Candidate pickEngine(List<Candidate> candidates, List<String> abis) {
        Candidate best = null;
        boolean bestIsSo = true;
        int bestAbi = -1;
        for (Candidate candidate : candidates) {
            if (!candidate.elf)
                continue;
            boolean isSo = candidate.base.toLowerCase(Locale.ROOT).endsWith(".so");
            int abi = abiRank(candidate.name, abis);
            if (best == null || betterEngine(abi, isSo, candidate.size, bestAbi, bestIsSo, best.size)) {
                best = candidate;
                bestIsSo = isSo;
                bestAbi = abi;
            }
        }
        return best;
    }

    private static boolean betterEngine(int abi, boolean isSo, long size, int bestAbi, boolean bestIsSo, long bestSize) {
        if (abi != bestAbi)
            return abi > bestAbi;
        if (isSo != bestIsSo)
            return bestIsSo;
        return size > bestSize;
    }

    /** 权重按后缀优先、体积其次；都不叫 .nnue 时，退回体积足够大的非可执行文件。 */
    private static Candidate pickNetwork(List<Candidate> candidates) {
        Candidate best = null;
        boolean bestNamed = false;
        for (Candidate candidate : candidates) {
            if (candidate.elf)
                continue;
            boolean named = candidate.base.toLowerCase(Locale.ROOT).endsWith(".nnue");
            if (!named && candidate.size < MIN_NNUE_BYTES)
                continue;
            if (best == null || (named != bestNamed ? named : candidate.size > best.size)) {
                best = candidate;
                bestNamed = named;
            }
        }
        return best;
    }

    /** 路径里出现的 ABI 越靠前、越贴合设备，排名越高；认不出时为 0。 */
    private static int abiRank(String name, List<String> abis) {
        if (abis == null)
            return 0;
        String lower = name.toLowerCase(Locale.ROOT);
        for (int index = 0; index < abis.size(); index++)
            for (String token : tokensFor(abis.get(index)))
                if (lower.contains(token))
                    return abis.size() - index;
        return 0;
    }

    private static String[] tokensFor(String abi) {
        return switch (abi) {
            case "arm64-v8a" -> new String[] {"arm64-v8a", "arm64", "aarch64"};
            case "armeabi-v7a" -> new String[] {"armeabi-v7a", "armeabi", "armv7", "v7a"};
            case "x86_64" -> new String[] {"x86_64", "x64"};
            case "x86" -> new String[] {"x86"};
            default -> new String[] {abi};
        };
    }

    private static boolean isDocumentation(String base) {
        String lower = base.toLowerCase(Locale.ROOT);
        for (String suffix : DOCUMENT_SUFFIXES)
            if (lower.endsWith(suffix))
                return true;
        return false;
    }

    private static String baseName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return (slash >= 0 ? name.substring(slash + 1) : name).trim();
    }

    private static String parentPath(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(0, slash) : "";
    }

    private static File newCustomDirectory(Context context) throws IOException {
        File parent = new File(context.getFilesDir(), "engines");
        ensureDirectory(parent);
        File directory = new File(parent, "custom-" + UUID.randomUUID());
        ensureDirectory(directory);
        return directory;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory())
            return;
        if (directory.exists() || !directory.mkdirs())
            throw new IOException("无法创建引擎目录: " + directory);
    }

    private static void copyAsset(Context context, String asset, File output) throws IOException {
        if (output.isFile() && output.length() > 0)
            return;
        // ★ 大件（引擎权重等）优先取自辎重包，读不到再回退 APK 内置 assets。
        try (InputStream input = HeavyStore.openLocalFirst(context, asset)) {
            copy(input, output);
        }
    }

    private static void copyUri(Context context, Uri uri, File output) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null)
            throw new IOException("无法读取文件");
        try (input) {
            copy(input, output);
        }
    }

    /** 先写同目录临时文件，再替换目标，避免中断时留下半个资源。 */
    private static void copy(InputStream input, File output) throws IOException {
        File directory = output.getParentFile();
        if (directory == null)
            throw new IOException("资源目标没有父目录");
        ensureDirectory(directory);

        File temporary = File.createTempFile(output.getName() + ".", ".tmp", directory);
        try {
            try (OutputStream stream = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;)
                    stream.write(buffer, 0, read);
            }
            replace(temporary, output);
        } finally {
            if (temporary.exists())
                temporary.delete();
        }
    }

    /** 读出上限之内的内容；超限返回 -1，表示这不是我们要的东西。 */
    private static long copyCapped(InputStream input, File output, long cap) throws IOException {
        long total = 0;
        try (OutputStream stream = new FileOutputStream(output)) {
            byte[] buffer = new byte[32 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                total += read;
                if (total > cap)
                    return -1;
                stream.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static void replace(File temporary, File output) throws IOException {
        try {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void makeExecutable(File executable) throws IOException {
        if (!executable.setExecutable(true, true) || !executable.canExecute())
            throw new IOException("无法设置引擎可执行权限");
    }

    /** 只要真是 ELF 就收下：架构不匹配时由启动阶段如实报错，而不是在这里一概拒收。 */
    private static boolean isElf(File file) {
        try (InputStream input = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            return readFully(input, magic) == 4 && isElf(magic);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isElf(byte[] bytes) {
        return bytes.length >= 4
            && bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F';
    }

    private static boolean isZip(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            if (readFully(input, magic) != 4 || magic[0] != 0x50 || magic[1] != 0x4b)
                return false;
            return (magic[2] == 3 && magic[3] == 4)
                || (magic[2] == 5 && magic[3] == 6)
                || (magic[2] == 7 && magic[3] == 8);
        }
    }

    private static int readFully(InputStream input, byte[] buffer) throws IOException {
        int size = 0;
        while (size < buffer.length) {
            int read = input.read(buffer, size, buffer.length - size);
            if (read < 0)
                break;
            size += read;
        }
        return size;
    }

    private static void deleteIncompleteBundle(File directory) {
        File[] files = directory.listFiles();
        if (files != null)
            for (File file : files)
                file.delete();
        directory.delete();
    }
}
