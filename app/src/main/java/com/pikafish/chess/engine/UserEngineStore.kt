package com.pikafish.chess.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pikafish.chess.heavy.EngineLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * ★★★ 引擎仓库（多引擎列表）★★★
 *
 * 用户点「外置引擎」按钮选一个 ZIP（例如 Pikafish260925.zip，内含 Pikafish + Pikafish.nnue），
 * 这里负责：解压 → 校验（ELF 可执行 + NNUE 权重）→ 落到 App 私有目录
 * （filesDir/engines/<slug>/）→ 启动子进程跑一次 UCI 握手，读它自报的 id name / id author。
 *
 * 与旧版的区别：旧版只有"一个"外置引擎槽位；现在改成"一份引擎清单"——
 *   1) 始终包含一条内置默认引擎（id=builtin，展示名"皮卡鱼"，标记默认）；
 *   2) 每导入一个 ZIP 就追加一条外置引擎（同名 id 覆盖旧条目）；
 *   3) 「采用」决定全局真正生效的那台（主对弈/解惑/复盘/分析…全走它）；
 *   4) 「测试」可挑两台不同的引擎做红黑对弈（PK），互不干扰。
 *
 * 兼容：旧的 isActive / engineFile / nnueFile / install / clear 语义保留——
 *   - isActive  = 当前采用的不是内置默认
 *   - engineFile/nnueFile = 当前采用的那台外置引擎的二进制/权重（内置时返回 null，由 JniEngine 回退内置）
 *   - install   = 导入 + 立刻采用（保持「外置引擎」按钮原有"装上就全局生效"的手感）
 *   - clear     = 采用切回内置默认（清单里的外置引擎保留）
 */
object UserEngineStore {

    private const val TAG = "UserEngineStore"
    private const val PREFS = "pikafish_chess_prefs"

    private const val KEY_ACTIVE_ID = "engine_active_id"
    private const val KEY_ENTRIES = "engine_entries"
    private const val KEY_RESULTS = "engine_results"

    /** 内置默认引擎的固定 id */
    const val BUILTIN_ID = "builtin"

    private const val DIR_NAME = "engines"
    private const val LEGACY_DIR = "user_engine"
    private const val MIN_NNUE_BYTES = 1_000_000L
    private const val MAX_ENGINE_BYTES = 40L * 1024L * 1024L   // 引擎二进制体积上限（防误吞大文件）
    private const val PROBE_TIMEOUT_MS = 8000L

    /** 明确当「说明/杂项」跳过的扩展名；其余文件一律读出来验魔数，不靠扩展名猜（宁可多试不放过） */
    private val DOC_EXTS = listOf(
        ".txt", ".md", ".json", ".ini", ".cfg", ".log", ".html", ".htm", ".xml",
        ".csv", ".bat", ".sh", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".pdf",
        ".zip", ".rar", ".7z", ".apk", ".xqf", ".pgn"
    )

    /** 一台引擎的清单条目 */
    data class Entry(
        val id: String,
        val idName: String,
        val author: String,
        val dirName: String,       // engines/<dirName>；内置为空
        val engineFileName: String,
        val nnueFileName: String,
        val engineSize: Long,
        val weightSize: Long,
        val packageName: String,
        val addedAt: Long
    ) {
        val isBuiltin: Boolean get() = id == BUILTIN_ID
        /** 界面展示名：内置=皮卡鱼；外置=认识的说中文，不认识的说英文原名 */
        val displayName: String
            get() = if (isBuiltin) "皮卡鱼" else EngineIdentity.displayNameOf(idName, engineFileName)
    }

    data class InstallResult(
        val ok: Boolean,
        val message: String,
        val idName: String = "",
        val author: String = "",
        val hasNnue: Boolean = false,
        val id: String = ""
    )

    /** 对弈结果（PK 记分） */
    enum class Outcome { WIN, LOSS, DRAW }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 引擎仓库根目录 */
    fun dir(ctx: Context): File = File(ctx.filesDir, DIR_NAME)

    // ══════════════ 清单读写 ══════════════

    private fun readEntries(ctx: Context): MutableList<Entry> {
        val out = ArrayList<Entry>()
        val raw = prefs(ctx).getString(KEY_ENTRIES, null)
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("id") == BUILTIN_ID) continue   // 内置条目不入库，运行时补
                    out.add(
                        Entry(
                            id = o.optString("id"),
                            idName = o.optString("idName"),
                            author = o.optString("author"),
                            dirName = o.optString("dirName"),
                            engineFileName = o.optString("engineFileName"),
                            nnueFileName = o.optString("nnueFileName"),
                            engineSize = o.optLong("engineSize"),
                            weightSize = o.optLong("weightSize"),
                            packageName = o.optString("packageName"),
                            addedAt = o.optLong("addedAt")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析引擎清单失败: ${e.message}")
            }
        }
        out.removeAll { it.id.isBlank() || it.dirName.isBlank() }
        return out
    }

    private fun writeEntries(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            if (e.isBuiltin) continue
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("idName", e.idName)
                put("author", e.author)
                put("dirName", e.dirName)
                put("engineFileName", e.engineFileName)
                put("nnueFileName", e.nnueFileName)
                put("engineSize", e.engineSize)
                put("weightSize", e.weightSize)
                put("packageName", e.packageName)
                put("addedAt", e.addedAt)
            })
        }
        prefs(ctx).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun builtinEntry(): Entry = Entry(
        id = BUILTIN_ID, idName = "", author = "",
        dirName = "", engineFileName = "", nnueFileName = "",
        engineSize = 0L, weightSize = 0L, packageName = "", addedAt = 0L
    )

    /** 完整引擎清单：内置默认排在首位，其余按加入时间。 */
    fun list(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        out.add(builtinEntry())
        out.addAll(readEntries(ctx).sortedBy { it.addedAt })
        return out
    }

    fun entry(ctx: Context, id: String?): Entry? {
        if (id.isNullOrBlank()) return null
        if (id == BUILTIN_ID) return builtinEntry()
        return readEntries(ctx).firstOrNull { it.id == id }
    }

    /** 当前「采用」的引擎 id（默认内置） */
    fun activeId(ctx: Context): String {
        val id = prefs(ctx).getString(KEY_ACTIVE_ID, BUILTIN_ID) ?: BUILTIN_ID
        return if (id == BUILTIN_ID) BUILTIN_ID else if (entry(ctx, id) != null) id else BUILTIN_ID
    }

    fun activeEntry(ctx: Context): Entry = entry(ctx, activeId(ctx)) ?: builtinEntry()

    /** 采用某台引擎（切主引擎），并同步 EngineIdentity。 */
    fun setActive(ctx: Context, id: String) {
        val appCtx = ctx.applicationContext
        val e = entry(appCtx, id) ?: builtinEntry()
        // ★★★ 「本地引擎 / 异地引擎」这一档由用户显式选择唯一决定，绝不隐式篡改。★★★
        //   仅当【真的换了引擎】（新 id ≠ 当前采用）才把档位拨回本地自算（用户确实改用
        //   本机某台引擎来算棋）；同一台的重复钉（id == activeId，例如点「恢复」、退出 PK、
        //   进入辎重包页时的重同步）一律不碰档位——否则用户辛苦勾好的「异地引擎」会被
        //   无端拨回本地，正是用户反馈的"勾选跳脱、配对后进去却变回默认引擎"之一。
        //   档位的显式切换入口：辎重包页的「本地/异地」问答、换引擎里选「电脑端异地引擎」。
        // ★★★ 用户亲手"采用"一台引擎 = 明确表态要用它。★★★
        //   内置（官方默认）尤其要强制回到本地：以前若档位还停在异地，选内置也不生效，
        //   于是程序继续一遍遍连那个已关闭的发送端，像撞墙一样。现在选了就是选了。
        if (e.isBuiltin) {
            EngineLink.forceLocal(appCtx)
        } else if (e.id != activeId(appCtx)) {
            EngineLink.setMode(appCtx, EngineLink.MODE_LOCAL)
        }
        // ★ 从异地切回本地：异地模式下红黑本地引擎被停掉了，这里重新拉起来；
        //   否则用户选完本地引擎，后台预想/校验会一直"未就绪"。
        try { EngineManager.active?.restartLocalEngines() } catch (_: Throwable) {}
        prefs(appCtx).edit().putString(KEY_ACTIVE_ID, e.id).apply()
        // ★★★ 出借引擎跟着走：本机「采用」换了一台，就把正在对外借出的热引擎作废——
        //   接收端会自动重连、重新握手，从而跟着发送端当前采用的这台引擎走。
        try {
            com.pikafish.chess.heavy.EngineShareServer.shared().resetEngine()
        } catch (_: Throwable) {
        }
        EngineIdentity.external = !e.isBuiltin
        EngineIdentity.packageName = e.packageName
        EngineIdentity.idName = e.idName
        EngineIdentity.author = e.author
        EngineIdentity.engineFileName = e.engineFileName
        EngineIdentity.weightFileName = e.nnueFileName
        EngineIdentity.enginePath = binaryFor(appCtx, e)?.absolutePath ?: ""
        EngineIdentity.weightPath = weightFor(appCtx, e)?.absolutePath ?: ""
        EngineIdentity.engineSize = e.engineSize
        EngineIdentity.weightSize = e.weightSize
        EngineIdentity.known = e.isBuiltin || e.idName.isNotBlank()
        EngineIdentity.updatedAt = System.currentTimeMillis()
        Log.i(TAG, "采用引擎: ${e.displayName} (id=${e.id})")
    }

    // ══════════════ 旧 API 兼容层 ══════════════

    fun isActive(ctx: Context): Boolean = activeId(ctx) != BUILTIN_ID

    fun packageName(ctx: Context): String = activeEntry(ctx).packageName

    /** 当前采用的外置引擎二进制（内置时返回 null → JniEngine 回退内置） */
    fun engineFile(ctx: Context): File? {
        val e = activeEntry(ctx)
        if (e.isBuiltin) return null
        return binaryFor(ctx, e)
    }

    /** 当前采用的外置权重（内置时返回 null → JniEngine 回退内置权重） */
    fun nnueFile(ctx: Context): File? {
        val e = activeEntry(ctx)
        if (e.isBuiltin) return null
        return weightFor(ctx, e)
    }

    fun binaryFor(ctx: Context, e: Entry): File? {
        if (e.isBuiltin || e.dirName.isBlank() || e.engineFileName.isBlank()) return null
        val f = File(File(dir(ctx), e.dirName), e.engineFileName)
        return if (f.isFile && f.length() > 0) f else null
    }

    fun weightFor(ctx: Context, e: Entry): File? {
        if (e.isBuiltin || e.dirName.isBlank() || e.nnueFileName.isBlank()) return null
        val f = File(File(dir(ctx), e.dirName), e.nnueFileName)
        return if (f.isFile && f.length() >= MIN_NNUE_BYTES) f else null
    }

    /** 卸回内置默认（清单里的外置引擎保留，可在「引擎列表」里再采用）。 */
    fun clear(ctx: Context) {
        setActive(ctx, BUILTIN_ID)
        Log.i(TAG, "已切回内置皮卡鱼引擎")
    }

    /** 删除一台外置引擎（内置不可删）。 */
    fun remove(ctx: Context, id: String) {
        val appCtx = ctx.applicationContext
        if (id == BUILTIN_ID) return
        val list = readEntries(appCtx)
        val target = list.firstOrNull { it.id == id } ?: return
        try { File(dir(appCtx), target.dirName).deleteRecursively() } catch (_: Exception) {}
        list.removeAll { it.id == id }
        writeEntries(appCtx, list)
        // 若删掉的正是当前采用的 → 切回内置
        if (activeId(appCtx) == id) setActive(appCtx, BUILTIN_ID)
        Log.i(TAG, "已删除引擎: ${target.displayName}")
    }

    // ══════════════ 导入 ══════════════

    /**
     * ★ 导入：解压用户选的 ZIP、校验、探明身份、写入清单，并【立刻采用】。
     * 阻塞 IO，请在后台线程调用。
     */
    fun install(ctx: Context, uri: Uri): InstallResult {
        val appCtx = ctx.applicationContext
        val root = dir(appCtx)
        try {
            if (!root.exists() && !root.mkdirs()) return InstallResult(false, "无法创建引擎目录")

            val tmp = File(appCtx.cacheDir, "engine_import_tmp")
            try { tmp.deleteRecursively() } catch (_: Exception) {}
            if (!tmp.mkdirs()) return InstallResult(false, "无法创建解压目录")

            var nnueFile: File? = null
            val engineFiles = ArrayList<File>()      // ★ 包内所有"安卓能跑"的引擎（可能不止一台）
            var engineBytes = 0L
            var engine32Arm = false      // 32 位 ARM 引擎（部分新机不含 32 位运行库，跑不起来）
            // ★ 包里出现过、但安卓跑不了的引擎（PC 的 .exe / x86 ELF）：只用于给用户一句明白话
            var pcEngines = 0
            var x86Engines = 0

            // ★★★ 先认容器格式：RAR / 7z 无法用 ZIP 流解，直接给一句明白话，别让人猜。★★★
            val magic = appCtx.contentResolver.openInputStream(uri)?.use { ins ->
                val head = ByteArray(8)
                val n = ins.read(head)
                if (n >= 4) head.copyOf(n) else null
            }
            if (magic != null && magic.size >= 4 &&
                magic[0] == 'R'.code.toByte() && magic[1] == 'a'.code.toByte() &&
                magic[2] == 'r'.code.toByte() && magic[3] == '!'.code.toByte()
            ) {
                return InstallResult(
                    false,
                    "这是 RAR 压缩包，安卓端不能直接解。\n" +
                        "如果是「电脑版象棋软件包」（界面+引擎），请直接在本机电脑上打开\n" +
                        "「黄昏象棋 发送端 EXE」→ 按 i 导入，它会自动挑出真正的引擎，\n" +
                        "再让手机配对借这台引擎算棋——手机只收着法，不用装 PC 引擎。\n" +
                        "如果确实是给手机的安卓引擎：转成 ZIP 再传即可。"
                )
            }
            if (magic != null && magic.size >= 6 &&
                magic[0] == 0x37.toByte() && magic[1] == 0x7A.toByte() &&
                magic[2] == 0xBC.toByte() && magic[3] == 0xAF.toByte() &&
                magic[4] == 0x27.toByte() && magic[5] == 0x1C.toByte()
            ) {
                return InstallResult(false, "这是 7z 压缩包，安卓端不能直接解。请先转成 ZIP 再传。")
            }

            appCtx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val base = entry.name.substringAfterLast('/').trim()
                        if (!entry.isDirectory && base.isNotEmpty()) {
                            val lower = base.lowercase()
                            if (DOC_EXTS.any { lower.endsWith(it) }) {
                                // 说明/图片等杂项：明确跳过
                            } else if (lower.endsWith(".nnue")) {
                                // ① 明牌权重：.nnue 流式落盘（不设体积上限，动辄几十 MB），取最大的那一份
                                val target = File(tmp, sanitize(base))
                                try {
                                    FileOutputStream(target).use { out -> zis.copyTo(out) }
                                    val prev = nnueFile
                                    if (target.length() >= MIN_NNUE_BYTES &&
                                        (prev == null || target.length() > prev.length())
                                    ) {
                                        nnueFile = target
                                    } else if (target.length() < MIN_NNUE_BYTES) {
                                        target.delete()
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "解压权重失败: $base", e)
                                }
                            } else {
                                // ② 其余文件一律读出来验魔数（不看扩展名——"张冠李戴"的名字也照收）：
                                //    ELF            → 引擎候选（最大者胜）
                                //    非 ELF 且够大   → 权重候选（最大者胜），绝不悄悄让位给内置权重
                                val bytes = readEntryCapped(zis, MAX_ENGINE_BYTES)
                                if (bytes.isNotEmpty() && isElf(bytes)) {
                                    // ★★★ 认 ELF 还要认 CPU 架构：安卓只跑 ARM。★★★
                                    //   之前的毛病：只要是 ELF 就收，x86/x86_64 的电脑版引擎也照收，
                                    //   装进去后 exec 必然失败（exec format error），引擎等于没起来，
                                    //   界面却显示"已装上"——随后只能靠云库/兜底出招，走出"炮打马"那种怪棋。
                                    when (elfAbi(bytes)) {
                                        "arm64", "arm32" -> {
                                            val target = File(tmp, sanitize(base))
                                            try {
                                                FileOutputStream(target).use { it.write(bytes) }
                                                target.setReadable(true, false)
                                                target.setExecutable(true, false)
                                                engineFiles.add(target)
                                                if (bytes.size.toLong() > engineBytes) engineBytes = bytes.size.toLong()
                                                if (elfAbi(bytes) == "arm32") engine32Arm = true
                                                Log.i(TAG, "引擎候选：$base（${elfAbi(bytes)}，" +
                                                    "${EngineIdentity.humanSize(target.length())}）")
                                            } catch (e: Exception) {
                                                Log.w(TAG, "写出引擎失败: $base", e)
                                            }
                                        }
                                        else -> x86Engines++
                                    }
                                } else if (bytes.isNotEmpty() && isWindowsPe(bytes)) {
                                    // PC 的 .exe / .dll：安卓永远跑不了，只记账、不落盘
                                    pcEngines++
                                } else if (bytes.size >= MIN_NNUE_BYTES) {
                                    val prev = nnueFile
                                    if (prev == null || bytes.size.toLong() > prev.length()) {
                                        val target = File(tmp, sanitize(base))
                                        try {
                                            FileOutputStream(target).use { it.write(bytes) }
                                            nnueFile = target
                                        } catch (e: Exception) {
                                            Log.w(TAG, "写出权重失败: $base", e)
                                        }
                                    }
                                }
                            }
                        }
                        try { zis.closeEntry() } catch (_: Exception) {}
                        entry = zis.nextEntry
                    }
                }
            } ?: return InstallResult(false, "无法读取所选文件")

            if (engineFiles.isEmpty()) {
                val why = StringBuilder("压缩包里没有安卓能跑的引擎（必须 Android ARM 版）。")
                if (pcEngines > 0) why.append("\n· 发现 $pcEngines 个 Windows 程序（.exe/.dll）—— 手机不能运行 PC 版引擎。")
                if (x86Engines > 0) why.append("\n· 发现 $x86Engines 个 x86 引擎 —— 那是给电脑用的，手机跑不了。")
                if (pcEngines > 0 || x86Engines > 0) why.append("\n请换用 Android 版（arm64-v8a）引擎，或直接用内置皮卡鱼。")
                return InstallResult(false, why.toString())
            }

            // ★★★ 一包多引擎：全部收下（一台一个条目），体积大的排在前面。★★★
            engineFiles.sortByDescending { it.length() }
            val pkg = queryDisplayName(appCtx, uri)
            val names = ArrayList<String>()
            var lastId = ""
            var lastIdName = ""
            var lastAuthor = ""
            for (eng in engineFiles) {
                if (!eng.canExecute()) eng.setExecutable(true, false)
                val reg = registerEngine(appCtx, root, eng, nnueFile, pkg) ?: continue
                names.add(reg.first)
                lastId = reg.second
                val idAndAuthor = reg.third.split('\u0000')
                lastIdName = idAndAuthor.getOrElse(0) { "" }
                lastAuthor = idAndAuthor.getOrElse(1) { "" }
            }
            if (names.isEmpty()) return InstallResult(false, "引擎装载失败（可能是 32 位 ARM，本机无 32 位运行库）")

            setActive(appCtx, lastId)
            try { tmp.deleteRecursively() } catch (_: Exception) {}

            val head = if (names.size == 1) "已装载：${names[0]}"
                       else "已装载 ${names.size} 台：${names.joinToString("、")}"
            val weight = nnueFile
            val weightText = if (weight != null)
                "，权重 ${weight.name}（${EngineIdentity.humanSize(weight.length())}）"
            else "（包内未带权重）"
            val extra = buildString {
                if (engine32Arm) append(" · 含 32 位 ARM 引擎")
                if (pcEngines > 0) append(" · 忽略 $pcEngines 个 Windows 程序")
                if (x86Engines > 0) append(" · 忽略 $x86Engines 个 x86 引擎")
            }
            Log.i(TAG, "引擎导入成功: $head, nnue=${nnueFile?.name}, 共 ${names.size} 台")
            return InstallResult(true, "$head$weightText$extra", lastIdName, lastAuthor,
                nnueFile != null, lastId)
        } catch (t: Throwable) {
            Log.e(TAG, "引擎导入失败", t)
            return InstallResult(false, "装载失败：${t.message}")
        }
    }

    /** 兼容旧单槽目录：若存在旧 user_engine 且清单为空 → 登记为一条外置引擎。 */
    fun migrateLegacy(ctx: Context) {
        try {
            val appCtx = ctx.applicationContext
            if (readEntries(appCtx).isNotEmpty()) return
            val legacy = File(appCtx.filesDir, LEGACY_DIR)
            if (!legacy.isDirectory) return
            val active = prefs(appCtx).getBoolean("user_engine_active", false)
            val engName = prefs(appCtx).getString("user_engine_file", null) ?: return
            val nnName = prefs(appCtx).getString("user_engine_nnue", null) ?: ""
            val pkg = prefs(appCtx).getString("user_engine_package", "") ?: ""
            val eng = File(legacy, engName)
            if (!eng.isFile) return
            val root = dir(appCtx)
            if (!root.exists() && !root.mkdirs()) return
            val slug = sanitize(engName).lowercase().ifBlank { "engine" }
            val target = File(root, slug)
            try {
                target.deleteRecursively()
                if (!target.mkdirs()) return
                eng.copyTo(File(target, eng.name), overwrite = true)
                File(target, eng.name).setExecutable(true, false)
                if (nnName.isNotBlank()) {
                    val n = File(legacy, nnName)
                    if (n.isFile) n.copyTo(File(target, n.name), overwrite = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "迁移旧引擎失败: ${e.message}")
                return
            }
            val entry = Entry(
                id = slug, idName = "", author = "", dirName = slug,
                engineFileName = eng.name, nnueFileName = nnName,
                engineSize = eng.length(), weightSize = 0L,
                packageName = pkg, addedAt = System.currentTimeMillis()
            )
            writeEntries(appCtx, mutableListOf(entry))
            if (active) setActive(appCtx, slug) else setActive(appCtx, BUILTIN_ID)
            legacy.deleteRecursively()
            Log.i(TAG, "已迁移旧版外置引擎到多引擎清单: $slug")
        } catch (_: Exception) {}
    }

    // ══════════════ 成绩（PK 记分） ══════════════

    /**
     * 记一笔对局结果。engineId 视角：赢了对方 / 输给对方 / 与对方和棋。
     */
    fun recordResult(ctx: Context, engineId: String, opponentId: String, outcome: Outcome) {
        if (engineId.isBlank() || opponentId.isBlank() || engineId == opponentId) return
        val appCtx = ctx.applicationContext
        try {
            val root = JSONObject(prefs(appCtx).getString(KEY_RESULTS, "{}") ?: "{}")
            fun bump(id: String, opp: String, idx: Int) {
                val mine = root.optJSONObject(id) ?: JSONObject()
                val arr = mine.optJSONArray(opp) ?: JSONArray().apply { put(0); put(0); put(0) }
                arr.put(idx, arr.optInt(idx) + 1)
                mine.put(opp, arr)
                root.put(id, mine)
            }
            // [胜, 负, 和]
            when (outcome) {
                Outcome.WIN -> { bump(engineId, opponentId, 0); bump(opponentId, engineId, 1) }
                Outcome.LOSS -> { bump(engineId, opponentId, 1); bump(opponentId, engineId, 0) }
                Outcome.DRAW -> { bump(engineId, opponentId, 2); bump(opponentId, engineId, 2) }
            }
            prefs(appCtx).edit().putString(KEY_RESULTS, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "记录对局成绩失败: ${e.message}")
        }
    }

    /** 某引擎与各家交手的成绩明细，每对手一项（如「胜张三 2」「负李四 1」）；无成绩返回空表。 */
    fun resultLines(ctx: Context, id: String): List<String> {
        return try {
            val root = JSONObject(prefs(ctx).getString(KEY_RESULTS, "{}") ?: "{}")
            val mine = root.optJSONObject(id) ?: return emptyList()
            val out = ArrayList<String>()
            val keys = mine.keys()
            while (keys.hasNext()) {
                val opp = keys.next()
                val arr = mine.optJSONArray(opp) ?: continue
                val w = arr.optInt(0); val l = arr.optInt(1); val d = arr.optInt(2)
                val oppName = entry(ctx, opp)?.displayName ?: opp
                val parts = ArrayList<String>(3)
                if (w > 0) parts.add("胜$oppName $w")
                if (l > 0) parts.add("负$oppName $l")
                if (d > 0) parts.add("和$oppName $d")
                if (parts.isNotEmpty()) out.add(parts.joinToString("、"))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    /** 某引擎的成绩摘要（多行文本，供列表「成绩」列展示）；无成绩返回空串。 */
    fun resultSummary(ctx: Context, id: String): String = resultLines(ctx, id).joinToString("\n")

    /** 清空全部成绩（长按「引擎列表」标题触发） */
    fun clearResults(ctx: Context) {
        prefs(ctx).edit().remove(KEY_RESULTS).apply()
    }

    // ══════════════ 内部工具 ══════════════

    private fun sanitize(name: String): String {
        val sb = StringBuilder()
        for (c in name) {
            sb.append(if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_')
        }
        val s = sb.toString().trim('.', '_', '-')
        return if (s.isEmpty()) "engine" else s
    }

    private fun isElf(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
            b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()

    /** EFI_CLASS=1（32 位）且 e_machine=40（ARM）→ 32 位 ARM 引擎（armeabi-v7a）。 */
    private fun is32BitArm(b: ByteArray): Boolean =
        b.size >= 20 && b[4].toInt() == 1 &&
            (b[18].toInt() and 0xFF) == 40 && (b[19].toInt() and 0xFF) == 0

    /** PC 的 Windows 可执行文件（PE）：MZ 开头。安卓永远跑不了，只用于给用户一句明白话。 */
    private fun isWindowsPe(b: ByteArray): Boolean =
        b.size >= 2 && b[0] == 'M'.code.toByte() && b[1] == 'Z'.code.toByte()

    /**
     * ★★★ 认 ELF 的 CPU 架构：安卓只跑 ARM。★★★
     *
     * <p>e_machine（偏移 18，小端）：40=ARM(arm32)、183=AArch64(arm64)、
     * 3=x86、62=x86-64。返回 "arm64"/"arm32"/"x86"/"x64"/"other"。</p>
     *
     * <p>为什么要认：以往"是 ELF 就收"，会把电脑版的 x86 引擎也装进来。装完 exec 必挂
     * （exec format error），引擎其实没起来，界面却显示已装载——随后只能靠云库/兜底出招，
     * 走出"炮打马"这类怪棋，还查不出原因。</p>
     */
    private fun elfAbi(b: ByteArray): String {
        if (!isElf(b) || b.size < 20) return "other"
        val machine = (b[18].toInt() and 0xFF) or ((b[19].toInt() and 0xFF) shl 8)
        return when (machine) {
            183 -> "arm64"
            40 -> "arm32"
            3 -> "x86"
            62 -> "x64"
            else -> "other"
        }
    }

    /**
     * 把一台引擎登记进清单（含去重、落盘、写清单），返回 (显示名, id, idName, author)；失败返回 null。
     * ★ 供"一包多引擎"逐个登记用，逻辑与原来的单台导入一致。
     */
    private fun registerEngine(
        appCtx: Context,
        root: File,
        eng: File,
        nnueFile: File?,
        pkg: String
    ): Triple<String, String, String>? {
        try {
            val probe = probeIdentity(eng)
            val idName = probe?.first ?: ""
            val author = probe?.second ?: ""
            val seed = idName.ifBlank { pkg.ifBlank { eng.name } }
            var slug = sanitize(seed).lowercase().ifBlank { "engine" }
            val existing = readEntries(appCtx)
            val clash = existing.firstOrNull {
                it.dirName.equals(slug, ignoreCase = true) ||
                    (idName.isNotBlank() && it.idName.equals(idName, ignoreCase = true))
            }
            if (clash != null) {
                try { File(root, clash.dirName).deleteRecursively() } catch (_: Exception) {}
            }
            var finalDir = File(root, slug)
            if (finalDir.exists()) {
                var i = 2
                while (File(root, "$slug-$i").exists()) i++
                slug = "$slug-$i"
                finalDir = File(root, slug)
            }
            if (!finalDir.mkdirs()) return null
            val outEngine = File(finalDir, eng.name)
            eng.copyTo(outEngine, overwrite = true)
            outEngine.setReadable(true, false)
            outEngine.setExecutable(true, false)
            val outNnue = nnueFile?.let { n -> File(finalDir, n.name).also { n.copyTo(it, overwrite = true) } }

            val entryId = if (idName.isNotBlank()) slug else "engine_${System.currentTimeMillis()}"
            val entry = Entry(
                id = entryId,
                idName = idName,
                author = author,
                dirName = slug,
                engineFileName = outEngine.name,
                nnueFileName = outNnue?.name ?: "",
                engineSize = outEngine.length(),
                weightSize = outNnue?.length() ?: 0L,
                packageName = pkg,
                addedAt = System.currentTimeMillis()
            )
            val updated = readEntries(appCtx).filter { it.id != clash?.id && it.id != entryId }.toMutableList()
            updated.add(entry)
            writeEntries(appCtx, updated)
            // ★ 返回 (显示名, id, idName, author)——用四元组包装成 Triple + 显示名放 idName 前缀不合适，
            //   这里改为返回自定义三元组：显示名 / id / "idName\u0000author"
            return Triple(entry.displayName, entryId, "$idName\u0000$author")
        } catch (t: Throwable) {
            Log.w(TAG, "登记引擎失败: ${eng.name}", t)
            return null
        }
    }


    private fun readEntryCapped(zis: ZipInputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = zis.read(buf)
            if (n <= 0) break
            total += n
            if (total > cap) return ByteArray(0)   // 超上限：不是我们要的引擎
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 从系统返回的 URI 问出文件名（外置包名，用于展示） */
    private fun queryDisplayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    /**
     * ★ 启动一次子进程跑 UCI 握手，读回它自报的 id name / id author。
     * 返回 null 表示探测失败（不致命，正式握手还会再取一次）。
     */
    fun probeIdentity(engine: File): Pair<String, String>? {
        var proc: Process? = null
        try {
            val pb = ProcessBuilder(engine.absolutePath)
            pb.directory(engine.parentFile)
            pb.redirectErrorStream(true)
            proc = pb.start()
            val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream, Charsets.UTF_8))
            writer.write("uci\n"); writer.flush()
            var id = ""
            var author = ""
            val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val line = try { reader.readLine() } catch (_: Exception) { null } ?: break
                val t = line.trim()
                if (t.startsWith("id name", ignoreCase = true)) id = t.substring(7).trim()
                else if (t.startsWith("id author", ignoreCase = true)) author = t.substring(9).trim()
                else if (t.startsWith("uciok")) break
            }
            try { writer.write("quit\n"); writer.flush() } catch (_: Exception) {}
            return if (id.isBlank() && author.isBlank()) null else Pair(id, author)
        } catch (e: Exception) {
            Log.w(TAG, "引擎身份探测失败: ${e.message}")
            return null
        } finally {
            try { proc?.destroy() } catch (_: Exception) {}
        }
    }
}