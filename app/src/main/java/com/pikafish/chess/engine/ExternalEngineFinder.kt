package com.pikafish.chess.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ★★★ 懒人外置引擎/权重定位器 ★★★
 *
 * 仿"皮卡鱼懒人包 / 优步象棋"的做法：用户把桌面解压好的外置包/权重放进一个固定目录，
 * App 启动时自动扫描、优先级最高地去加载外置 NNUE 权重（以及可执行的外置引擎二进制）。
 * 找不到才回退到内置引擎与内置权重。
 *
 * 这解决了两种场景：
 *   1) 设备不支持 GLES 3.1 计算着色器 → GPU 神经引擎起不来，自动回退到经典皮卡鱼，
 *      再叠加上这里的外置权重/引擎，棋力不打折；
 *   2) 玩家想自己换更强的皮卡鱼新权重 / 新引擎 → 丢进懒人目录即生效，无需重装 App。
 *
 * 约定路径（先到先得，谁存在用哪个）：
 *   /storage/emulated/0/象棋引擎/
 *   /storage/emulated/0/XiangqiEngine/
 *   /storage/emulated/0/皮卡鱼/
 *   以及 App 私有外部目录 Android/data/<包名>/files/engine/
 *
 * 目录结构二选一均可：
 *   ├─ pikafish.nnue            (权重，*.nnue 取最大的那份)
 *   └─ pikafish-base            (可执行引擎二进制，可选)
 *  或
 *   ├─ engine/
 *   │   ├─ pikafish.nnue
 *   │   └─ pikafish-base
 *   └─ ...
 */
object ExternalEngineFinder {

    private const val TAG = "ExternalEngine"
    private const val NNUE_EXT = "nnue"
    const val MIN_NNUE_BYTES = 5_000_000L   // 至少 5MB，过滤残缺/伪文件

    // 皮卡鱼懒人包内常见的引擎二进制命名
    private val ENGINE_NAMES = listOf(
        "pikafish", "pikafish-base", "pikafish-armv8", "pikafish-arm64",
        "pikafish-x86-64", "pikafish-native", "px"
    )

    /** 候选懒人目录（按用户友好程度排序） */
    fun candidateDirs(ctx: Context): List<File> {
        val list = ArrayList<File>(4)
        list.add(File("/storage/emulated/0/象棋引擎"))
        list.add(File("/storage/emulated/0/XiangqiEngine"))
        list.add(File("/storage/emulated/0/皮卡鱼"))
        ctx.getExternalFilesDir(null)?.let { list.add(File(it, "engine")) }
        return list
    }

    /** 在所有懒人目录中查找 NNUE 权重文件（返回最大者，通常是完整模型） */
    fun findNnue(ctx: Context): File? {
        for (dir in candidateDirs(ctx)) {
            val d = engineSubdir(dir)
            val f = scanNnue(d) ?: if (d != dir) scanNnue(dir) else null
            if (f != null) {
                Log.i(TAG, "命中外置权重: ${f.absolutePath} (${f.length()}B)")
                return f
            }
        }
        return null
    }

    /** 在所有懒人目录中查找可执行引擎二进制（仅返回 file.canExecute() 为真的） */
    fun findEngine(ctx: Context): File? {
        for (dir in candidateDirs(ctx)) {
            val d = engineSubdir(dir)
            val f = scanEngine(d) ?: if (d != dir) scanEngine(dir) else null
            if (f != null) {
                Log.i(TAG, "命中外置引擎: ${f.absolutePath}")
                return f
            }
        }
        return null
    }

    /** 目录树里更深的 engine/ 子目录（兼容"懒人包含 engine 子文件夹"的布局） */
    private fun engineSubdir(dir: File): File {
        val sub = File(dir, "engine")
        return if (sub.isDirectory) sub else dir
    }

    private fun listFiles(dir: File): Array<File>? {
        if (!dir.isDirectory) return null
        return dir.listFiles()
    }

    private fun scanNnue(dir: File): File? {
        val files = listFiles(dir) ?: return null
        return files
            .filter { it.isFile && it.name.lowercase().endsWith(".$NNUE_EXT") && it.length() >= MIN_NNUE_BYTES }
            .maxByOrNull { it.length() }
    }

    private fun scanEngine(dir: File): File? {
        val files = listFiles(dir) ?: return null
        return files.firstOrNull {
            it.isFile && it.canExecute() &&
                (ENGINE_NAMES.contains(it.name.lowercase()) || it.name.lowercase().startsWith("pikafish"))
        }
    }

    /** 供 UI 展示当前外置资源的命中情况 */
    fun statusText(ctx: Context): String {
        val n = findNnue(ctx)
        val e = findEngine(ctx)
        return when {
            n != null && e != null -> "外置引擎＋外置权重"
            n != null -> "外置权重"
            e != null -> "外置引擎"
            else -> "内置引擎/权重"
        }
    }
}