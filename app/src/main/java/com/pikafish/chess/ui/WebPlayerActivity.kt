package com.pikafish.chess.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pikafish.chess.engine.SandboxEngine
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor
import java.nio.charset.Charset

/**
 * ★★★ 微思象棋·打谱对弈台 ★★★
 *
 * 0）离线 WebView 承载微思完整播放器（变招 / XQF·CBR·CCM / PGN·WXF·ICCS·东萍·世家 多格式）。
 * 1）【接入自家引擎】在竖屏下方铺一排我们自己的按钮，真正“驾驶”这块棋盘：
 *    - 红方/黑方 「电脑·人脑」 切换：谁是电脑，轮到它就请自家引擎应一手。
 *    - 新局 / 悔一手 / 上步 / 下步 / 末步 走子定位。
 *    - 「应手」：无论谁在走，都让引擎指点或续下一手。
 * 2）【延伸被遮挡的菜单】微思是横版工具，竖屏里很多菜单看不见——我们把“导入/导出各格式”
 *    提到明面：导入走系统文件选择器；导出支持 中文/WXF/ICCS/PGN/东萍/鹏飞PFC/QQ(CHE)。
 *
 * 坐标说明：自家引擎 UCI 与微思 ICCS 同坐标系（a0-i9，红方底线为第 0 排），走法串可直接互通。
 */
class WebPlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var bridge: JsBridge? = null
    private var readyForFile = false

    // ── 对弈状态（原生侧执掌）──
    private val board = Board()
    private val moveList = ArrayList<String>()
    private var redComputer = false
    private var blackComputer = false
    private var aiThinking = false
    private var searchGen = 0
    private var lastEngineReplyPly = 0           // 引擎已走到的步数，悔棋退回
    private val STEP_MS = 0L                       // 预留：单步间隔
    private val THINK_MS = 1200L

    private var engine: SandboxEngine? = null
    @Volatile private var engineReady = false
    private var engineFailNotified = false

    private lateinit var statusText: TextView
    private lateinit var btnRed: Button
    private lateinit var btnBlack: Button

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) handlePickedFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bridge = JsBridge(this)
        val bridgeObj = bridge!!

        webView = WebView(this)
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.allowFileAccessFromFileURLs = true
        settings.setSupportZoom(false)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.setWebViewClient(object : WebViewClient() {})
        webView.setWebChromeClient(WebChromeClient())
        webView.addJavascriptInterface(bridgeObj, "Android")
        webView.loadUrl("file:///android_asset/vschess/index.html")

        setContentView(buildUi())
    }

    // ═════════════ 界面 ═════════════
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF27313B.toInt())
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            setBackgroundColor(0xFF1D242C.toInt())
        }

        // 行1：走子定位与开局
        panel.addView(rowOf(listOf(
            makeBtn("新局") { newGame() },
            makeBtn("悔一手") { backStep() },
            makeBtn("上步") { runJs("Player.goStep(${moveList.size})", null) },
            makeBtn("下步") { runJs("Player.goStep(${moveList.size + 1})", null) },
            makeBtn("末步") { runJs("Player.goLast()", null) }
        )))

        // 行2：人机角色 & 应手
        btnRed = makeBtn(roleLabel(true), 11f) { toggleComputer(true) }
        btnBlack = makeBtn(roleLabel(false), 11f) { toggleComputer(false) }
        panel.addView(rowOf(listOf(
            makeBtn("导入棋谱") { launchFilePicker() },
            makeBtn("导出") { showExportDialog() },
            btnRed, btnBlack,
            makeBtn("应手") { engineStep() }
        )))

        statusText = TextView(this).apply {
            text = "就绪 · 红=人脑 黑=人脑 · 点右下上方的「导出」可转储各格式"
            setTextColor(0xFFB9C4D2.toInt())
            textSize = 11f
            setPadding(dp(2), dp(3), dp(2), 0)
            gravity = Gravity.CENTER
        }
        panel.addView(statusText)

        val wrap = ScrollView(this).apply { addView(panel) }
        root.addView(wrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // ★ 让控制面板始终悬在系统导航键上方且留有舒适间距：
        //   即使系统 insets 在非透明导航栏下返回 0，也强制预留一份底距，避免按钮被遮挡/误触退出。
        val MIN_BOTTOM = dp(10)
        ViewCompat.setOnApplyWindowInsetsListener(wrap) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val bottomGap = if (nav >= MIN_BOTTOM) nav else MIN_BOTTOM
            v.setPadding(dp(6), dp(4), dp(6), bottomGap + dp(4))
            insets
        }
        return root
    }

    private fun rowOf(btns: List<Button>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btns.forEach {
            row.addView(it, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        return row
    }

    private fun makeBtn(text: String, textSize: Float = 12f, onClick: (() -> Unit)? = null): Button =
        Button(this).apply {
            this.text = text
            this.textSize = textSize
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF3A4654.toInt())
            isAllCaps = false
            if (onClick != null) setOnClickListener { onClick() }
        }

    private fun roleLabel(isRed: Boolean): String {
        val comp = if (isRed) redComputer else blackComputer
        val side = if (isRed) "红" else "黑"
        return "$side：${if (comp) "●电脑" else "○人脑"}"
    }

    private fun toggleComputer(isRed: Boolean) {
        if (aiThinking) { toast("引擎正在思考…"); return }
        if (isRed) redComputer = !redComputer else blackComputer = !blackComputer
        btnRed.text = roleLabel(true)
        btnBlack.text = roleLabel(false)
        updateStatus()
        // 若当前轮到的一方变聪明（电脑），续走
        val cur = board.currentPlayer
        val need = if (cur == PieceColor.RED) redComputer else blackComputer
        if (need) engineStep()
    }

    // ═════════════ 对弈驱动 ═════════════
    private fun newGame() {
        board.loadFromFen(Board().toFen())          // 标准开局
        moveList.clear()
        lastEngineReplyPly = 0
        runJs("Player.resetStandard()", null)
        runJs("Player.resetBaseline()", null)
        updateStatus()
        toast("新开一局")
        engineStepIfComputer()
    }

    private fun backStep() {
        // 原生镜像悔掉最后一着（算子不分红黑，仅作定位退回 + 同步）
        if (moveList.isNotEmpty()) {
            moveList.removeAt(moveList.size - 1)
            board.loadFromFen(moveListToFen())
            runJs("Player.goStep(${moveList.size})", null)
            lastEngineReplyPly = moveList.size
            pushBoard()
            updateStatus()
        } else {
            runJs("Player.goStep(0)", null)
        }
    }

    /** 引擎应一手（无论轮谁，帮当前轮走方续走；一般用于电脑方自动应手）。 */
    private fun engineStep() {
        if (aiThinking) return
        if (board.currentSideIsComputer()) {
            startAiSearch()
        } else {
            toast("轮到红方/人脑，请先在棋盘上走棋")
        }
    }

    private fun engineStepIfComputer() {
        if (board.currentSideIsComputer()) startAiSearch()
    }

    /** 走子列表回放成局面（原生镜像与微思保持一致）。 */
    private fun moveListToFen(): String {
        val b = Board()
        for (u in moveList) {
            val m = Move.fromUci(u, b) ?: continue
            b.makeMoveUnchecked(m)
        }
        return b.toFen()
    }

    /** 将当前整局推送到微思棋盘（显示为主变 + 定位末步）。
     *  viewer.js 的契约是「起始FEN + 主变着法列表」；moveList 的语义就是从标准开局重放到末步，
     *  故树根必须传标准开局 FEN，而不是 board.toFen()（当前终局）。二者错位会导致
     *  stepListToNode 建树从错误起点开始、loadData 后棋盘无法定位到末步——即“后台在走、画面不动”。 */
    private fun pushBoard() {
        val json = "[" + moveList.joinToString(",") { "\"$it\"" } + "]"
        runJs("Player.showGame(${jsString(Board().toFen())},${jsString(json)})", null)
        runJs("Player.resetBaseline()", null)
    }

    private fun startAiSearch() {
        if (aiThinking) return
        val color = board.currentPlayer
        if ((color == PieceColor.RED && !redComputer) || (color == PieceColor.BLACK && !blackComputer)) {
            updateStatus()
            return
        }
        aiThinking = true
        val gen = ++searchGen
        updateStatus()
        ensureEngineAsync { ok ->
            if (!ok) { aiThinking = false; updateStatus(); return@ensureEngineAsync }
            val eng = engine ?: return@ensureEngineAsync
            val b = board.clone()
            try {
                eng.searchBestMove(b, timeMs = THINK_MS, onResult = { mv ->
                    runOnUiThread {
                        if (gen != searchGen) { aiThinking = false; return@runOnUiThread }
                        aiThinking = false
                        if (mv == null) {
                            updateStatus(); toast("电脑暂未算出走法"); return@runOnUiThread
                        }
                        applyEngineMove(mv)
                    }
                })
            } catch (e: Throwable) {
                aiThinking = false
                updateStatus()
                toast("引擎出错：${e.message}")
            }
        }
    }

    private fun applyEngineMove(mv: Move) {
        val legal = board.getLegalMoves(board.currentPlayer).any { it.toUci() == mv.toUci() }
        if (!legal) {
            // 引擎给非法着 → 取一个合法兜底着（等效“另走次优”）
            val fb = board.getLegalMoves(board.currentPlayer).firstOrNull()
            if (fb != null) applyEngineMove(fb) else { toast("电脑走法异常"); updateStatus(); return }
            return
        }
        board.makeMoveUnchecked(mv)
        moveList.add(mv.toUci())
        lastEngineReplyPly = moveList.size
        pushBoard()
        updateStatus()
        // 若另一侧也是电脑，继续
        if (board.currentSideIsComputer()) {
            // 两电脑自战：生成完一手后再让引擎续走（由回调链维持）
            engineStepIfComputer()
        }
    }

    /** JS 轮询检测到微思棋盘步数变化，回传完整着法列表（原生做精确 diff 同步 + 反请引擎应手）。
     *  采用“从开局按完整列表回放、取最长合法前缀”的方式，稳健处理走子/悔棋/跳步/换线。 */
    private fun onBoardMove(jsonList: String) {
        runOnUiThread {
            try {
                val n = org.json.JSONArray(jsonList)
                // 完全回放一遍，重算合法前缀
                val cur = Board()
                var prefix = 0
                for (i in 0 until n.length()) {
                    val m = Move.fromUci(n.getString(i), cur) ?: break
                    if (cur.getLegalMoves(cur.currentPlayer).none { it.toUci() == m.toUci() }) break
                    cur.makeMoveUnchecked(m)
                    prefix = i + 1
                }
                // 若前缀比原生当前进度更长 → 同步（用户走了新着法）；否则不动（悔棋/跳步无需改原生棋盘）
                if (prefix > moveList.size) {
                    val cp = cur.clone()
                    board.loadFromFen(cp.toFen())
                    for (i in moveList.size until prefix) moveList.add(n.getString(i))
                }
                if (moveList.size > lastEngineReplyPly) lastEngineReplyPly = moveList.size
                updateStatus()
                if (board.currentSideIsComputer()) startAiSearch()
            } catch (e: Exception) {
                // 忽略解析异常
            }
        }
    }

    // ═════════════ 引擎装配 ═════════════
    private fun ensureEngineAsync(cb: (Boolean) -> Unit) {
        if (engineReady) { cb(true); return }
        Thread {
            fun fail(reason: String) {
                runOnUiThread {
                    if (!engineFailNotified) {
                        engineFailNotified = true
                        toast("电脑引擎启动失败：$reason")
                    }
                    cb(false)
                }
            }
            try {
                val e = SandboxEngine(this, SandboxEngine.ELEEYE, SandboxEngine.isUcci(SandboxEngine.ELEEYE))
                val loc = e.locateEngine()
                if (loc != null) { fail(loc); return@Thread }
                val startErr = e.startEngine()
                if (startErr != null) { fail(startErr); return@Thread }
                val initErr = e.liteInit()
                if (initErr != null) { fail(initErr); return@Thread }
                engine = e
                engineReady = true
                engineFailNotified = false
                Log.i(TAG, "微思台引擎就绪")
                runOnUiThread { cb(true) }
            } catch (ex: Throwable) {
                Log.e(TAG, "引擎启动异常", ex)
                fail(ex.message ?: "未知异常")
            }
        }.apply { name = "vschess-engine"; start() }
    }

    private fun Board.currentSideIsComputer(): Boolean {
        return if (currentPlayer == PieceColor.RED) redComputer else blackComputer
    }

    private fun updateStatus() {
        val who = if (board.currentPlayer == PieceColor.RED) "红" else "黑"
        val stance = when {
            aiThinking -> "$who 方·引擎思考中…"
            board.currentSideIsComputer() -> "$who 方·电脑走（${if (board.currentPlayer==PieceColor.RED) "红" else "黑"}）"
            else -> "$who 方·请走棋"
        }
        statusText.text = "第 ${moveList.size / 2 + 1} 回合 · $stance · 已 ${moveList.size} 手"
    }

    // ═════════════ 导入 ═════════════
    fun launchFilePicker() {
        pickFile.launch(arrayOf("*/*"))
    }

    private fun handlePickedFile(uri: Uri) {
        try {
            val name = queryDisplayName(uri) ?: "棋谱"
            val bytes = readAll(uri)
            val ext = name.substringAfterLast('.', "").lowercase()
            val hex = bytesToHex(bytes)
            val isByteMagic = bytes.size >= 2 &&
                ((bytes[0].toInt() == 'X'.code && bytes[1].toInt() == 'Q'.code) ||
                 (bytes[0].toInt() == 'C'.code && bytes[1].toInt() == 'B'.code))

            when {
                ext == "xqf" || (isByteMagic && ext != "cbr" && ext != "ccm") ->
                    runJs("Player.loadHex('$hex','xqf')", "XQF 谱载入完成：$name")
                ext == "cbr" ->
                    runJs("Player.loadHex('$hex','cbr')", "CBR 谱载入完成：$name")
                ext == "ccm" -> {
                    // CCM 是二进制，转成文本喂 dataToNode_CCM
                    val txt = decodeText(bytes)
                    val esc = jsString(txt)
                    runJs("Player.loadText(\"$esc\",\"ccm\")", "CCM 谱载入完成：$name")
                }
                ext == "cbl" ->
                    toast("CBL 为象棋桥格式，请在主界面用「导入 CBL」打开")
                else -> {
                    val text = decodeText(bytes)
                    val escaped = jsString(text)
                    runJs("Player.loadText(\"$escaped\",\"auto\")", "棋谱载入完成：$name")
                }
            }
        } catch (e: Exception) {
            toast("打开棋谱失败：${e.message}")
        }
    }

    // ═════════════ 导出（延伸格式菜单）════════════
    private fun showExportDialog() {
        val formats = arrayOf(
            "中文着法", "WXF 纵线", "ICCS", "标准 PGN", "东萍 DhtmlXQ",
            "鹏飞 PFC", "QQ新中国 CHE", "文本(自动)"
        )
        val keys = arrayOf("chinese", "wxf", "iccs", "pgn", "dhxq", "pfc", "qq", "text")
        AlertDialog.Builder(this)
            .setTitle("导出棋谱 · 选择格式")
            .setItems(formats) { _, idx -> fetchExport(keys[idx], formats[idx]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fetchExport(key: String, label: String) {
        if (moveList.isEmpty()) {
            toast("当前没有着法可导出，请先走棋或导入棋谱")
            return
        }
        webView.evaluateJavascript("(function(){return Player.exportData('$key')})()") { value ->
            val text = value?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")?.replace("\\n", "\n") ?: ""
            if (text.isBlank()) { toast("导出失败（该格式暂不可用）"); return@evaluateJavascript }
            showExportResult(label, text)
        }
    }

    private fun showExportResult(label: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF2A3440.toInt())
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("导出：$label")
            .setView(sv)
            .setPositiveButton("复制并分享") { _, _ -> shareText("$label 棋谱", text) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun shareText(subject: String, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, "导出 $subject"))
    }

    // ═════════════ 工具 ═════════════
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun readAll(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw Exception("无法读取文件")
        return input.use { it.readBytes() }
    }

    private fun decodeText(bytes: ByteArray): String {
        var text = try { String(bytes, Charset.forName("UTF-8")) } catch (e: Exception) { "" }
        var bad = 0
        for (c in text) if (c == '\uFFFD') bad++
        if (bad > 0 && text.isNotEmpty()) {
            try {
                val gb = String(bytes, Charset.forName("GB18030"))
                var badGb = 0
                for (c in gb) if (c == '\uFFFD') badGb++
                if (badGb < bad) text = gb
            } catch (e: Exception) { }
        }
        return text
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                '<' -> sb.append("\\u003C")
                else -> if (c.code < 0x20) sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun runJs(code: String, successToast: String?) {
        webView.evaluateJavascript(code, null)
        if (successToast != null) toast(successToast)
    }

    private fun log(s: String) = android.util.Log.i(TAG, s)

    /** JS 桥接对端对象。 */
    inner class JsBridge(private val activity: WebPlayerActivity) {
        @JavascriptInterface
        fun openFile() = activity.launchFilePicker()

        @JavascriptInterface
        fun onPlayerReady() {
            activity.readyForFile = true
            // 就绪后让 JS 探测走子列表可读性并开启轮询基线与自动应手
            activity.runJs("Player.setEngineAuto(1);Player.resetBaseline()", null)
        }

        @JavascriptInterface
        fun onToast(msg: String) = activity.toast(msg)

        /** 人在微思棋盘上落子的回传（JSON 数组，新增走法）。 */
        @JavascriptInterface
        fun onBoardMove(json: String) = activity.onBoardMove(json)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        try { runJs("Player.setEngineAuto(0)", null) } catch (e: Exception) { }
        engine?.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VschessPlayer"
    }
}