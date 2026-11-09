package com.pikafish.chess.batch

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 棋谱批转工具（独立小 App）
 *   ① 一次可多选任意数量棋谱文件（xqf / cbl / mxq / pgn / qyl / zip …）
 *   ② 解析阶段：实时显示「第几个文件 / 包内第几项 / 已解出多少局 / 已用时」
 *   ③ 勾选条目 → 批导出：显示「已导多少局 / 共多少局 / 百分比 / 速度 / 预计剩余」
 */
class MainActivity : Activity() {

    private data class Item(
        val uri: Uri,
        val name: String,
        var games: Int = -1,        // -1 = 未解析；0 = 解析失败/无棋局
        var bytes: Long = 0,
        val check: CheckBox
    )

    private val items = ArrayList<Item>()
    private lateinit var listBox: LinearLayout
    private lateinit var tvPhase: TextView
    private lateinit var tvDetail: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnExport: Button
    private val ui = Handler(Looper.getMainLooper())

    private var probing = false
    private var exporting = false
    private var lastPaint = 0L

    private val REQ_PICK = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "棋谱批转工具"
            textSize = 20f
            setTextColor(Color.parseColor("#8B5A2B"))
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "① 选文件（可多选）  ② 勾选条目  ③ 批导出 PGN"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 12)
        })

        root.addView(Button(this).apply {
            text = "添加棋谱文件（可多选）"
            setOnClickListener { if (!probing && !exporting) pickFiles() }
        })

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = ProgressBar.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 18).apply { topMargin = 14 }
        }
        root.addView(bar)

        tvPhase = TextView(this).apply {
            text = "尚未添加文件"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#5A3A1A"))
            setPadding(0, 10, 0, 2)
        }
        root.addView(tvPhase)

        tvDetail = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setLineSpacing(4f, 1f)
            setPadding(0, 0, 0, 8)
        }
        root.addView(tvDetail)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(listBox, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        row.addView(Button(this).apply {
            text = "全选"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { items.forEach { it.check.isChecked = true }; repaint() }
        })
        row.addView(Button(this).apply {
            text = "全不选"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { items.forEach { it.check.isChecked = false }; repaint() }
        })
        row.addView(Button(this).apply {
            text = "清空"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (probing || exporting) {
                    Toast.makeText(this@MainActivity, "任务进行中，无法清空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                items.clear(); refreshList(); repaint()
                tvPhase.text = "已清空"; tvDetail.text = ""; bar.visibility = ProgressBar.GONE
            }
        })
        root.addView(row)

        btnExport = Button(this).apply {
            text = "批导出所选 → PGN"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D9922E"))
            setOnClickListener { exportSelected() }
        }
        root.addView(btnExport)

        setContentView(root)
    }

    // ── 选择文件 ─────────────────────────────────────────
    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return

        val picked = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) picked.add(clip.getItemAt(k).uri)
        } else {
            data.data?.let { picked.add(it) }
        }
        if (picked.isEmpty()) return

        var added = 0
        for (u in picked) {
            val nm = displayName(u)
            if (items.any { it.name == nm && it.uri == u }) continue
            val cb = CheckBox(this).apply {
                isChecked = true
                text = nm
                textSize = 13f
            }
            items.add(Item(u, nm, -1, 0L, cb))
            added++
        }
        refreshList()
        repaint()
        if (added > 0) probeAll() else Toast.makeText(this, "文件已在列表中", Toast.LENGTH_SHORT).show()
    }

    private fun refreshList() {
        listBox.removeAllViews()
        items.forEach { it.check.text = label(it) }
        items.forEach { listBox.addView(it.check) }
    }

    private fun label(it: Item): String = when {
        it.games < 0 -> "${it.name}  ·  未解析"
        it.games == 0 -> "${it.name}  ·  未识别到棋局"
        else -> "${it.name}  ·  ${it.games} 局"
    }

    private fun repaint() {
        refreshList()
        val ok = items.count { it.games > 0 }
        val total = items.filter { it.games > 0 }.sumOf { it.games }
        if (!probing && !exporting) {
            tvPhase.text = if (items.isEmpty()) "尚未添加文件"
            else "已添加 ${items.size} 个文件，可导出 $total 局（$ok 个含棋局）"
        }
    }

    // ── 解析阶段（带进度）─────────────────────────────────
    private fun probeAll() {
        if (probing || exporting) return
        probing = true
        btnExport.isEnabled = false
        btnExport.text = "解析中，请稍候…"
        bar.visibility = ProgressBar.VISIBLE
        bar.isIndeterminate = true

        val pending = items.filter { it.games < 0 }
        thread {
            val t0 = System.currentTimeMillis()
            var cumulative = 0
            var idx = 0
            for (it in pending) {
                idx++
                val no = idx
                ui.post {
                    tvPhase.text = "解析中  $no/${pending.size}  ·  ${it.name}"
                    tvDetail.text = "正在读取文件…"
                }
                val raw = readAll(it.uri)
                if (raw == null) {
                    it.games = 0
                    ui.post { refreshList() }
                    continue
                }
                it.bytes = raw.size.toLong()
                var innerDone = 0
                var innerTotal = -1
                var innerName = ""
                var fileGames = 0
                try {
                    BatchDecode.walkAll(raw, it.name, 0,
                        { _, gs -> fileGames += gs.count { it.movesUci.isNotEmpty() } },
                        { d, t, en ->
                            innerDone = d; innerTotal = t; innerName = en
                            val now = System.currentTimeMillis()
                            if (now - lastPaint >= 180) {
                                lastPaint = now
                                val el = (now - t0) / 1000
                                val got = cumulative + fileGames
                                ui.post {
                                    bar.isIndeterminate = false
                                    if (innerTotal > 0) {
                                        bar.max = innerTotal
                                        bar.progress = innerDone
                                    }
                                    tvPhase.text = "解析中  $no/${pending.size}  ·  ${it.name}"
                                    tvDetail.text = buildString {
                                        if (innerTotal > 0)
                                            append("包内进度  $innerDone / $innerTotal 项")
                                                .append("（${innerDone * 100 / innerTotal}%）\n")
                                        else append("包内已处理  $innerDone 项\n")
                                        if (innerName.isNotEmpty()) append("当前：$innerName\n")
                                        append("累计解出 $got 局  ·  已用时 ${mmss(el)}")
                                    }
                                }
                            }
                        })
                } catch (e: Exception) { }

                it.games = fileGames
                cumulative += fileGames
                val now = System.currentTimeMillis()
                val el = (now - t0) / 1000
                val cum = cumulative
                ui.post {
                    refreshList()
                    tvPhase.text = "解析中  $no/${pending.size}  ·  ${it.name}"
                    tvDetail.text = "$no 个文件已完成，累计解出 $cum 局  ·  已用时 ${mmss(el)}\n" +
                        (if (no < pending.size) "下一个：${pending[no].name}" else "解析完成，可以勾选并批导出")
                }
            }

            probing = false
            val total = items.filter { it.games > 0 }.sumOf { it.games }
            val ok = items.count { it.games > 0 }
            ui.post {
                bar.visibility = ProgressBar.GONE
                btnExport.isEnabled = true
                btnExport.text = "批导出所选 → PGN"
                tvPhase.text = "解析完成：${items.size} 个文件，$ok 个含棋局，共 $total 局"
                tvDetail.text = "已用时 ${mmss((System.currentTimeMillis() - t0) / 1000)}  ·  " +
                    "勾选需要的条目后点下方按钮"
            }
        }
    }

    // ── 批导出（带进度 / 速度 / 预计剩余）──────────────────
    private fun exportSelected() {
        if (probing) { Toast.makeText(this, "还在解析，请稍候", Toast.LENGTH_SHORT).show(); return }
        if (exporting) return
        val sel = items.filter { it.check.isChecked && it.games > 0 }
        if (sel.isEmpty()) {
            Toast.makeText(this, "请先勾选含棋局的条目", Toast.LENGTH_SHORT).show()
            return
        }

        val totalGames = sel.sumOf { it.games }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "棋谱批量导出"
        )
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "棋谱_$stamp.pgn")

        exporting = true
        btnExport.isEnabled = false
        btnExport.text = "导出中…"
        bar.visibility = ProgressBar.VISIBLE
        bar.isIndeterminate = false
        bar.max = 100
        bar.progress = 0

        thread {
            val t0 = System.currentTimeMillis()
            var games = 0
            var files = 0
            var failed = 0
            try {
                out.bufferedWriter(Charsets.UTF_8).use { w ->
                    for (it in sel) {
                        files++
                        val fno = files
                        val raw = readAll(it.uri)
                        if (raw == null) { failed++; continue }

                        var localGames = 0
                        BatchDecode.walkAll(raw, it.name, 0, { _, gs ->
                            for (g in gs) {
                                if (g.movesUci.isEmpty()) continue   // 空局（无着法）不写入
                                try {
                                    w.write(g.toPgn())
                                    w.write("\n\n")
                                    games++
                                    localGames++
                                } catch (_: Exception) { }
                            }
                            w.flush()
                            val now = System.currentTimeMillis()
                            if (now - lastPaint >= 180) {
                                lastPaint = now
                                postExport(t0, games, totalGames, fno, sel.size, it.name)
                            }
                        }, { d, t, en ->
                            val now = System.currentTimeMillis()
                            if (now - lastPaint >= 180) {
                                lastPaint = now
                                val el = ((now - t0) / 1000).coerceAtLeast(1)
                                val rate = games / el
                                ui.post {
                                    val pct = if (totalGames > 0) games * 100 / totalGames else 0
                                    bar.progress = pct
                                    tvPhase.text = "导出中  $games / $totalGames 局（$pct%）"
                                    tvDetail.text = buildString {
                                        append("文件 $fno/${sel.size}：${it.name}\n")
                                        if (t > 0) append("包内 $d / $t 项 · 当前：$en\n")
                                        else append("包内已处理 $d 项\n")
                                        append("速度 ${rate} 局/秒 · 已用 ${mmss(el)}")
                                        if (rate > 0 && totalGames > games)
                                            append(" · 预计剩余 ${mmss((totalGames - games) / rate)}")
                                    }
                                }
                            }
                        })
                        if (localGames == 0) failed++
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    exporting = false
                    btnExport.isEnabled = true
                    btnExport.text = "批导出所选 → PGN"
                    tvPhase.text = "导出失败：${e.message}"
                    tvDetail.text = ""
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
                return@thread
            }

            val el = ((System.currentTimeMillis() - t0) / 1000).coerceAtLeast(1)
            val chk = verifyPgn(out)
            ui.post {
                exporting = false
                bar.progress = 100
                btnExport.isEnabled = true
                btnExport.text = "批导出所选 → PGN"
                tvPhase.text = "导出完成：$games 局（$files 个文件）"
                tvDetail.text = buildString {
                    append("用时 ${mmss(el)}  ·  平均 ${games / el} 局/秒")
                    if (failed > 0) append("  ·  $failed 个文件未解出")
                    append("\n★ 自校验：文件内含 ${chk.count} 局")
                    append(if (chk.count == games) "  ✔ 与导出计数一致" else "  ✘ 与导出计数不一致")
                    append("  ·  ${chk.sizeMb} MB")
                    if (chk.firstTitle.isNotBlank()) append("\n首局：${chk.firstTitle}")
                    if (chk.lastTitle.isNotBlank()) append("\n末局：${chk.lastTitle}")
                    append("\n${out.absolutePath}")
                }
                Toast.makeText(
                    this,
                    "已导出 $games 局 · 文件内校验 ${chk.count} 局\n${out.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private data class PgnCheck(val count: Int, val sizeMb: String, val firstTitle: String, val lastTitle: String)

    /** 读回刚写出的 PGN，统计其中真正存在多少局（以 [Event 头计数），用于自证导出结果 */
    private fun verifyPgn(f: File): PgnCheck {
        var count = 0
        var first = ""
        var last = ""
        try {
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { ln ->
                    if (ln.startsWith("[Event")) {
                        count++
                        val t = Regex("""\[Event\s+"([^"]*)"]""").find(ln)?.groupValues?.get(1).orEmpty()
                        if (first.isEmpty()) first = t
                        last = t
                    }
                }
            }
        } catch (_: Exception) {
        }
        val mb = String.format(Locale.US, "%.1f", f.length() / 1048576.0)
        return PgnCheck(count, mb, first, last)
    }

    private fun postExport(t0: Long, games: Int, total: Int, fno: Int, fcount: Int, name: String) {
        val el = ((System.currentTimeMillis() - t0) / 1000).coerceAtLeast(1)
        val rate = games / el
        val pct = if (total > 0) games * 100 / total else 0
        ui.post {
            bar.progress = pct
            tvPhase.text = "导出中  $games / $total 局（$pct%）"
            tvDetail.text = buildString {
                append("文件 $fno/$fcount：$name\n")
                append("速度 ${rate} 局/秒 · 已用 ${mmss(el)}")
                if (rate > 0 && total > games) append(" · 预计剩余 ${mmss((total - games) / rate)}")
            }
        }
    }

    private fun mmss(sec: Long): String {
        val s = if (sec < 0) 0 else sec
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // ── 工具 ─────────────────────────────────────────────
    private fun displayName(u: Uri): String {
        var nm = u.lastPathSegment ?: "未知文件"
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) nm = c.getString(idx) ?: nm
            }
        } catch (_: Exception) {
        }
        return nm.substringAfterLast('/')
    }

    private fun readAll(u: Uri): ByteArray? = try {
        contentResolver.openInputStream(u)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }
}
