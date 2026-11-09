package com.pikafish.chess.qipu

import android.app.Dialog
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.InputType
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Locale
import android.os.Handler
import android.os.Looper

/**
 * 棋谱库浏览器「圈二」— 全屏半透明手风琴式。
 *
 * 交互方式：
 *   - 第 1 屏：列出所有分类（专著、专辑、古谱...），点分类展开书
 *   - 第 2 屏（倒数第二个流程）：点书名 → 该书的【棋谱列表页】，每则后面标出
 *     最后一次打开的“年月日 时分秒 已来过”；没来过的则留空，弄过与没弄过的
 *     交界一目了然，方便一张张收山式往下练
 *   - 点某一则 → 打开并落“已来过”记录，回调给调用方（背谱沙盘 / 主界面）
 *
 * 外观：深檀色半透明底，棋盘在背后隐约可见。
 */
class QipuBrowser(
    private val context: Context,
    private val onOpenGame: (String, QipuBook, Int, List<QipuGame>) -> Unit,
    private val onResumeJournal: ((String) -> Unit)? = null,
    private val onUserUpload: ((QipuBook) -> Unit)? = null,
    private val onLocalDoc: ((QipuBook) -> Unit)? = null,
    private val onOpenReviewHistory: (() -> Unit)? = null
) {
    private var cats: List<QipuCategory> = emptyList()
    private var expandedCatIndex = -1  // 分类手风琴展开索引

    // 当前正在浏览的【书棋谱列表页】
    private var detailCatIndex = -1
    private var detailBook: QipuBook? = null
    private var detailGames: List<QipuGame> = emptyList()
    // ★ 分段渲染：一个压缩包（如华山和棋棋谱.zip）可含数千局，若一次性往 ScrollView 里
    //   塞几千行（每行 4~5 个 View）必然要等好几秒、还会把内存顶爆 —— 故按页渲染。
    private var detailPage = 0

    // ★ CBL 合成区：勾选状态（跨重渲染保留，改名/移动后不丢勾选）
    private val synthChecked = HashSet<String>()

    private var dialog: Dialog? = null

    // 每本书的加载缓存（避免重复解码CBL）
    private val bookGamesCache = HashMap<String, List<QipuGame>>()
    private var loadingBookFile: String? = null

    private val gold = 0xFFFFB300.toInt()      // 强调金色（恒亮，按钮/选框等"色块"通用）
    private val dark = 0xFF2B2015.toInt()      // 深字/按钮文字（所有主题通用）
    private val onDark = 0xFFF0E6D2.toInt()    // 固定「深底白字」：任何主题里写在深色按钮上的文字都用它，避免“深字配深底”

    // ★ 深底主题判断：目前仅「炭黑」是深底（深底配亮金字、浅字最清晰）；其余浅纸/透明一律黑字净金。
    private val isDark: Boolean
        get() = theme == VaultTheme.CHARCOAL

    // ★ 文字用金色：深底(炭黑)用明亮金；纸白/天蓝/青绿/米黄/透明等浅底用"沉淀的净金"，反差足、不费眼、不浑浊。
    private val goldText: Int
        get() = if (isDark) 0xFFFFC94D.toInt() else 0xFF935300.toInt()

    // ★ 带「序号 + 左侧标识/标钮」的棋则列表行：正文一概黑字（百度蓝链接仍蓝、序号/标钮金色照旧）；
    //   仅深底「炭黑」里保持浅字——否则黑字压在黑底上反而看不见。
    private val rowText: Int
        get() = if (isDark) 0xFFF5EEDC.toInt() else 0xFF1F1A13.toInt()

    // ★ 纸张/背景主题：可换「纸底底色」，衬托字迹更清晰。
    //   默认「纸白」：用户也能理解；怕"透明"字面看不懂。透明选项仍在、次序不变，仅默认选中纸白。
    //   用户改过后会持久化到 SharedPreferences，重启后宝库各屏（含我的对局等）依他自己的选择。
    private enum class VaultTheme(val label: String) { TRANSPARENT("透明"), CHARCOAL("炭黑"), PAPER("纸白"), BLUE("天蓝"), CYAN("青绿"), SAND("米黄") }
    private data class ThemeSpec(val rootBg: Int, val panel: Int, val textMain: Int, val textSub: Int)
    private val THEMES = mapOf(
        VaultTheme.TRANSPARENT to ThemeSpec(0x8FF0E8D4.toInt(), 0xFFE8DEC6.toInt(), 0xFF241E16.toInt(), 0xFF6A5D4C.toInt()),
        VaultTheme.CHARCOAL  to ThemeSpec(0xFF282018.toInt(), 0xFF201910.toInt(), 0xFFF5EEDC.toInt(), 0xFFC9BDA8.toInt()),
        VaultTheme.PAPER     to ThemeSpec(0xFFF7F3E8.toInt(), 0xFFEDE5D0.toInt(), 0xFF2C251A.toInt(), 0xFF6A5D4C.toInt()),
        VaultTheme.BLUE      to ThemeSpec(0xFFDDEBFF.toInt(), 0xFFC8DCF3.toInt(), 0xFF0C2A46.toInt(), 0xFF35587B.toInt()),
        VaultTheme.CYAN      to ThemeSpec(0xFFDBF4EE.toInt(), 0xFFBFE7DB.toInt(), 0xFF0B3930.toInt(), 0xFF26574B.toInt()),
        VaultTheme.SAND      to ThemeSpec(0xFFF3E7CC.toInt(), 0xFFE7D6B0.toInt(), 0xFF43341E.toInt(), 0xFF5E4A2B.toInt())
    )
    private var theme: VaultTheme = VaultTheme.PAPER           // 兜底默认纸白（未 saved 时走 loadTheme 的默认）
    private var panel = 0xFFEDE5D0.toInt()
    private var textMain = 0xFF2C251A.toInt()
    private var textSub = 0xFF6A5D4C.toInt()
    private var rootBg = 0xFFF7F3E8.toInt()
    private val themePrefs by lazy {
        context.getSharedPreferences("qipu_vault_theme", Context.MODE_PRIVATE)
    }
    private fun loadTheme() {
        runCatching {
            // 默认「纸白」：没选过的新用户直接落到纸白；用户改过就尊重他持久化的选择。
            val name = themePrefs.getString("paper", VaultTheme.PAPER.name) ?: VaultTheme.PAPER.name
            applyTheme(VaultTheme.valueOf(name))
        }
    }
    private fun applyTheme(t: VaultTheme) {
        theme = t
        val s = THEMES[t]!!
        rootBg = s.rootBg; panel = s.panel; textMain = s.textMain; textSub = s.textSub
    }
    private fun saveTheme(t: VaultTheme) {
        themePrefs.edit().putString("paper", t.name).apply()
    }
    // ★ 热链接颜色：随纸张底色取“看得清”的颜色 —— 炭黑(深底)用明亮的金，浅色纸/透明用深蓝。
    private val linkColor: Int
        get() = if (isDark) 0xFFFFC94D.toInt() else 0xFF1758B8.toInt()

    // 已来过时间格式：年月日 时分秒
    private val dfFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINESE)

    // ★ 全局搜索：运行时轻量索引（按书缓存检索文本，重复搜索秒出；点结果时再解码那一本书）
    //   检索维度：人名只对比红/黑，书名只对比来源书，布局只对比开局文字——"是谁就是谁，什么条件只给什么条件"
    private enum class SearchDim { ALL, PERSON, BOOK, LAYOUT }
    private var searchDim = SearchDim.ALL
    private var searchKwEdit: EditText? = null
    // 当代名局命中的哨兵书（点命中进档案/跳百度，不真正解码棋谱）
    private val sigilBook = QipuBook("当代名局", "__modern__", 0)

    private data class GameIdx(
        val index: Int,
        val title: String,
        val text: String,     // 全部字段（"全部"维度用）
        val persons: String,  // 归一：红名+黑名（人名维度专用）
        val bookName: String, // 归一：来源书名+分类（书名维度专用）
        val layout: String,   // 归一：布局/开局文字（布局维度专用）
        val years: Set<Int>
    )
    private data class BookIdx(val catName: String, val book: QipuBook, val entries: List<GameIdx>)
    private data class SearchHit(
        val catName: String, val book: QipuBook, val gameIndex: Int,
        val title: String, val source: String, val dimName: String,
        val modern: ModernPlayer? = null
    )
    private val searchIndex = LinkedHashMap<String, BookIdx>()
    private var searchGen = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show() {
        try {
            loadTheme()   // ★ 先读入持久化的纸张底色，再渲染
            cats = QipuLibrary.loadCategories(context)
            expandedCatIndex = -1
            detailCatIndex = -1; detailBook = null; detailGames = emptyList()
            renderCategoryList()
        } catch (e: Exception) {
            // ★ 异常消息可能为 null（如 NullPointerException），如实把类型也带上，别再只弹一个 "null"
            toast("棋谱库打开失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ════════════════════════ 共享对话框骨架 ════════════════════════

    private fun newShell(): Pair<Dialog, LinearLayout> {
        dismissSilently()
        val d = Dialog(context)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCancelable(true)
        d.setCanceledOnTouchOutside(true)

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(rootBg)   // 纸张底色（随主题变色）
        root.setPadding(14, 14, 14, 10)

        d.setContentView(root)
        val wm = d.window
        wm?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wm?.setBackgroundDrawableResource(android.R.color.transparent)
        wm?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        wm?.setDimAmount(0.05f)
        return d to root
    }

    private fun titleView(t: String): TextView = TextView(context).apply {
        text = t; setTextColor(goldText); textSize = 18f
        setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, 2, 0, 6)
    }

    /** 副标题；boldTail 非空时，尾部以粗体接上（用于「下划线可点清空」这类提示）。 */
    private fun subTitle(s: String, boldTail: String = ""): TextView = TextView(context).apply {
        text = if (boldTail.isEmpty()) s else SpannableStringBuilder(s).apply {
            val st = length
            append(boldTail)
            setSpan(StyleSpan(Typeface.BOLD), st, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setTextColor(textSub); textSize = 12f; setPadding(2, 0, 2, 8)
    }

    private fun bottomClose(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER; setPadding(0, 8, 0, 2)
        }
        val b = Button(context).apply {
            text = "关闭"; textSize = 14f; setTextColor(textMain)
            setBackgroundColor(panel); setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dismissSilently() }
        }
        bar.addView(b); return bar
    }

    /** 小退栏：返回棋谱宝库首页 + 关闭（用于我的对局/本地等次级屏，不必“大退”） */
    private fun backHomeBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER; setPadding(0, 8, 0, 2)
        }
        val bak = Button(context).apply {
            text = "← 返回棋谱宝库首页"; textSize = 14f; setTextColor(dark); setBackgroundColor(gold)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { renderCategoryList() }
        }
        val cls = Button(context).apply {
            text = "关闭"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dismissSilently() }
        }
        bar.addView(bak); bar.addView(cls); return bar
    }

    // ════════════════════════ 第 1 屏：分类手风琴 ════════════════════════

    private fun renderCategoryList() {
        val (d, root) = newShell()
        root.addView(titleView("棋谱宝库"))
        root.addView(subTitle(
            "共${cats.size}个分类，${cats.sumOf { it.books.size }}本书 · 点分类展开书单 · ",
            boldTail = "本数带下划线标识的，可点击清空。"
        ))

        val listRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listRow.addView(decorStrip(leftOrRight = true))
        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            addView(listContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listRow.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        listRow.addView(decorStrip(leftOrRight = false))
        root.addView(listRow)

        cats.forEachIndexed { catIndex, cat ->
            listContainer.addView(categoryRow(catIndex, cat))
            if (catIndex == expandedCatIndex) {
                val bookList = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24.dp(), 0, 12.dp(), 4.dp())
                }
                cat.books.forEachIndexed { bookIndex, book ->
                    bookList.addView(bookRow(catIndex, bookIndex, book))
                }
                listContainer.addView(bookList)
            }
        }

        // ★ 我的对局（本地存档：新开局时"保存到宝库"的续盘对局，可续盘/重命名/删除）
        //   实时预制位(DRAFT_ID)只是可靠性兜底，不在对外列表里展示
        val saved = GameJournal.listLog(context).count { it.stateJson != null && it.id != GameJournal.DRAFT_ID }
        listContainer.addView(duelRow(duelFiles().size))
        listContainer.addView(myGamesRow(saved))

        // ★ CBL 合成区：把各处棋谱条目汇集一处，重命名/排序后合成为一个 CBL 导出
        listContainer.addView(synthAreaRow(CblSynth.count(context)))

        // ★ 复盘文件（本地复盘存档：每次复盘即成一份复盘文件，点开即完整展开跳转试走）
        val reviewCount = com.pikafish.chess.ui.ReviewArchive.list(context).size
        listContainer.addView(reviewFilesRow(reviewCount))

        // ★ 当代名局：名手档案书柜（真实战绩/简介/常用开局，专名全部热链百科）
        val modernCount = ModernData.load(context).size
        if (modernCount > 0) {
            listContainer.addView(modernShelfRow(modernCount))
        }

        // ★ 底部全局搜索栏（在同一透明框内）：关键字 + 可选年份，按「搜索」确认
        root.addView(buildSearchBar())

        root.addView(bottomClose())
        show(d)
    }

    // ════════════════════════ 底部搜索 ════════════════════════

    /** 重建四个检索维度按钮（选中高亮），点击只更新维度与提示词，不重建整个对话框。 */
    private fun rebuildDimChips(cls: LinearLayout) {
        cls.removeAllViews()
        listOf(
            SearchDim.ALL to "全部", SearchDim.PERSON to "人名",
            SearchDim.BOOK to "书名", SearchDim.LAYOUT to "布局"
        ).forEach { (dim, label) -> cls.addView(dimChip(dim, label, cls)) }
    }

    private fun dimChip(dim: SearchDim, label: String, cls: LinearLayout): Button = Button(context).apply {
        text = label; textSize = 13f
        setTextColor(if (searchDim == dim) dark else textMain)
        setBackgroundColor(if (searchDim == dim) gold else panel)
        layoutParams = LinearLayout.LayoutParams(0, 40.dp(), 1f)
        setOnClickListener {
            searchDim = dim
            searchKwEdit?.hint = when (dim) {
                SearchDim.PERSON -> "填棋手姓名，如：胡荣华 / 许银川"
                SearchDim.BOOK -> "填书名关键词，如：弈林新编"
                SearchDim.LAYOUT -> "填布局名，如：屏风马 / 当头炮"
                SearchDim.ALL -> "任意关键词（姓名/书名/布局皆可）"
            }
            rebuildDimChips(cls)
        }
    }

    private fun buildSearchBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 6.dp(), 10.dp(), 4.dp())
            setBackgroundColor(0x3014100C.toInt())
        }
        bar.addView(TextView(context).apply {
            text = "棋谱搜索 · 选维度填关键词，可再限定 起始年–结束年"
            setTextColor(goldText); textSize = 12f; setPadding(2, 0, 2, 4.dp())
        })

        // ① 检索维度：全部 / 人名 / 书名 / 布局（互斥，选中高亮）
        val cls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 2.dp())
        }
        rebuildDimChips(cls)
        bar.addView(cls)

        // ② 关键词（按所选维度填写）
        val kw = EditText(context).apply {
            hint = when (searchDim) {
                SearchDim.PERSON -> "填棋手姓名，如：胡荣华 / 许银川"
                SearchDim.BOOK -> "填书名关键词，如：弈林新编"
                SearchDim.LAYOUT -> "填布局名，如：屏风马 / 当头炮"
                SearchDim.ALL -> "任意关键词（姓名/书名/布局皆可）"
            }
            textSize = 14f; setSingleLine(true); setBackgroundColor(0x22F0E6D2.toInt())
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
        }
        searchKwEdit = kw
        bar.addView(kw)

        // ③ 起始年 : [ ]  结束年 : [ ]（只允许填 4 位阿拉伯数字年；乱填视为未填）
        val yr = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6.dp(), 0, 0)
        }
        yr.addView(TextView(context).apply {
            text = "起始年:"; setTextColor(textSub); textSize = 13f; setPadding(0, 0, 4.dp(), 0)
        })
        val fromY = EditText(context).apply {
            hint = "如1990"; textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setBackgroundColor(0x22F0E6D2.toInt()); setPadding(6.dp(), 4.dp(), 6.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
        }
        yr.addView(fromY)
        yr.addView(TextView(context).apply {
            text = "　结束年:"; setTextColor(textSub); textSize = 13f; setPadding(6.dp(), 0, 4.dp(), 0)
        })
        val toY = EditText(context).apply {
            hint = "如2020"; textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setBackgroundColor(0x22F0E6D2.toInt()); setPadding(6.dp(), 4.dp(), 6.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
        }
        yr.addView(toY)
        bar.addView(yr)

        // ⑤ 纸张底色：透明(透视棋盘) / 纸白 / 天蓝 / 青绿 / 米黄；点击即时换色并持久化(重启仍生效)
        val themeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6.dp(), 0, 0)
        }
        themeRow.addView(TextView(context).apply {
            text = "纸张:"; setTextColor(textSub); textSize = 13f; setPadding(0, 0, 4.dp(), 0)
        })
        VaultTheme.values().forEach { t ->
            themeRow.addView(Button(context).apply {
                text = t.label; textSize = 12f
                val sel = (theme == t)
                setTextColor(if (sel) dark else textMain)
                setBackgroundColor(if (sel) gold else panel)
                setPadding(6.dp(), 2.dp(), 6.dp(), 2.dp())
                layoutParams = LinearLayout.LayoutParams(0, 40.dp(), 1f)
                setOnClickListener {
                    applyTheme(t); saveTheme(t); renderCategoryList()
                }
            })
        }
        bar.addView(themeRow)

        // ④ 确认搜索
        val sub = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6.dp(), 0, 0)
        }
        sub.addView(Button(context).apply {
            text = "确认搜索 →"; textSize = 14f; setTextColor(dark); setBackgroundColor(gold)
            gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setPadding(12.dp(), 0, 12.dp(), 0)
            setOnClickListener {
                val k = kw.text.toString().trim()
                val y1 = parseStrictYear(fromY.text.toString())
                val y2 = parseStrictYear(toY.text.toString())
                // 起始>结束 时自动互换，避免白搜一场
                val minY: Int?; val maxY: Int?
                if (y1 != null && y2 != null && y1 > y2) { minY = y2; maxY = y1 } else { minY = y1; maxY = y2 }
                openSearchResults(k, searchDim, minY, maxY)
            }
        })
        sub.addView(Button(context).apply {
            text = "全部"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            setPadding(12.dp(), 0, 12.dp(), 0)
            setOnClickListener { openSearchResults("", SearchDim.ALL, null, null) }
        })
        // ★★★ 「属性」搜索：只在自己的、真正写过属性的对局圈子里找 ★★★
        //   不会去翻整个宝库（几万局老棋谱本来就没有属性，白翻一场）。
        sub.addView(Button(context).apply {
            text = "属性"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            setPadding(12.dp(), 0, 12.dp(), 0)
            setOnClickListener { openAttrSearch(kw.text.toString().trim()) }
        })
        bar.addView(sub)
        return bar
    }

    /**
     * ★★★ 属性搜索：只在「带属性」的对局里查关键词。★★★
     *
     * <p>圈子怎么定：保存时若写过任意一个属性字节，条目里就会带 {@code hasAttrs=true}
     * 与 {@code attrs} 对象；这里只扫这些条目，所以是"不多不少"的一圈，
     * 不会把整座宝库翻个底朝天。属性全删光再保存的，就不带 hasAttrs，自然不进这一圈。</p>
     */
    private fun openAttrSearch(kwText: String) {
        val kw = kwText.trim()
        val all = try { GameJournal.listLog(context) } catch (_: Exception) { emptyList() }
        val hitAttrs = ArrayList<Triple<GameJournal.LogEntry, Map<String, String>, String>>()
        for (e in all) {
            val json = e.stateJson ?: continue
            if (!json.contains("\"hasAttrs\"")) continue
            val attrs = LinkedHashMap<String, String>()
            try {
                val obj = org.json.JSONObject(json).optJSONObject("attrs") ?: continue
                obj.keys().forEach { k -> obj.optString(k).takeIf { it.isNotBlank() }?.let { v -> attrs[k] = v } }
            } catch (_: Exception) {
                continue
            }
            if (attrs.isEmpty()) continue
            val haystack = attrs.entries.joinToString(" ") { "${it.key}=${it.value}" } + " " + e.title
            if (kw.isNotEmpty() && !haystack.contains(kw, ignoreCase = true)) continue
            val path = try { org.json.JSONObject(json).optString("txtPath") } catch (_: Exception) { "" }
            hitAttrs.add(Triple(e, attrs, path))
        }

        val (d, root) = newShell()
        root.addView(titleView("属性搜索 · " + (if (kw.isEmpty()) "全部带属性对局" else "关键词「$kw」")))
        root.addView(subTitle("只在带属性的对局里找（共 ${hitAttrs.size} 局），不翻整座宝库"))

        // ★ 这一屏里再套一层搜索：填关键词 → 确认，就在同一圈子里重新筛（不用退出去）
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2.dp(), 0, 6.dp())
        }
        val input = EditText(context).apply {
            hint = "搜属性关键词，如：张三 / 网络快棋"
            setText(kw)
            textSize = 14f
            setSingleLine(true)
            setBackgroundColor(0x22F0E6D2.toInt())
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
        }
        box.addView(input, LinearLayout.LayoutParams(0, 44.dp(), 1f))
        box.addView(Button(context).apply {
            text = "确认"
            textSize = 14f
            setTextColor(dark)
            setBackgroundColor(gold)
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val k = input.text.toString().trim()
                d.dismiss()
                openAttrSearch(k)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44.dp()))
        root.addView(box)
        if (hitAttrs.isEmpty()) {
            root.addView(subTitle("没有命中。给对局做一次「新对局 → 详细保存」，写上属性后就能搜到。"))
        }
        for ((entry, attrs, path) in hitAttrs.take(200)) {
            root.addView(TextView(context).apply {
                val attrText = attrs.entries.joinToString("　") { "${it.key}：${it.value}" }
                text = entry.title + "\n" + attrText + (if (path.isNotBlank()) "\n$path" else "")
                setTextColor(textMain)
                textSize = 14f
                setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
                setBackgroundColor(panel)
                // ★ 整行是热链接：点一下直接打开这一局（不再只弹个提示）
                setOnClickListener {
                    if (onResumeJournal != null) {
                        d.dismiss()
                        onResumeJournal.invoke(entry.id)
                    } else {
                        android.widget.Toast.makeText(context, attrText, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
        d.show()
    }

    /** 严格解析 4 位阿拉伯数字年份（1900-2099）；空/乱填一律视为未限定，返回 null。 */
    private fun parseStrictYear(s: String): Int? {
        val t = s.trim()
        val m = Regex("^(19\\d{2}|20\\d{2})$").find(t) ?: return null
        return m.value.toInt()
    }

    // ════════════════════════ 全局搜索执行 ════════════════════════

    /** 打开搜索结果屏：竖排展示，点一条即在主界面打开（可走可快进），不停机地后台扫描全部书。 */
    private fun openSearchResults(kwText: String, dim: SearchDim, minY: Int?, maxY: Int?) {
        val (d, root) = newShell()
        val dimName = when (dim) {
            SearchDim.PERSON -> "人名"; SearchDim.BOOK -> "书名"; SearchDim.LAYOUT -> "布局"; SearchDim.ALL -> "全部"
        }
        val yearPart = when {
            minY != null && maxY != null -> "  $minY–$maxY"
            minY != null -> "  $minY 年起"
            maxY != null -> "  $maxY 年以前"
            else -> ""
        }
        val label = (if (kwText.isBlank()) "全部${dimName}棋谱" else "$dimName·$kwText") + yearPart
        root.addView(titleView("检索 · $label"))
        val progress = TextView(context).apply {
            setTextColor(goldText); textSize = 13f; setPadding(4, 2, 4, 2)
        }
        root.addView(progress)

        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            addView(listContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val backBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 2.dp())
        }
        backBar.addView(Button(context).apply {
            text = "← 返回书单"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dismissSilently(); searchIndex.clear(); renderCategoryList() }
        })
        backBar.addView(Button(context).apply {
            text = "关闭"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dismissSilently() }
        })
        root.addView(backBar)

        show(d)
        runSearchScan(kwText, dim, minY, maxY, progress, listContainer)
    }

    /** 后台全量扫描（逐本解码），结果分批上屏；每次新搜索递增代号使旧扫描作废，绝不卡界面。 */
    private fun runSearchScan(
        kwText: String, dim: SearchDim, minY: Int?, maxY: Int?,
        progress: TextView, listContainer: LinearLayout
    ) {
        val gen = ++searchGen
        val kw = norm(kwText)
        val cap = 400
        val snapshot = cats
        val totalBooks = snapshot.sumOf { it.books.size }.coerceAtLeast(snapshot.size)
        var shown = 0
        Thread {
            val batch = ArrayList<SearchHit>(32)
            var scanned = 0
            var totalMatches = 0

            // ★ 第 0 批：当代名局·名手档案先出（数据极小、命中即秒回）。
            //   内置档案含王天一/许银川等名人，故“王天”→“名手档案·王天一”会立刻出现，
            //   不必等 159 本教科书慢扫 —— 直接解决“搜索半天空白”的观感。
            val moderns = ModernData.load(context)
            for (mp in moderns) {
                if (gen != searchGen) return@Thread
                if (matchModern(mp, kw, dim)) {
                    totalMatches++
                    if (shown < cap) {
                        shown++
                        batch.add(SearchHit(
                            "当代名局", sigilBook, -1,
                            "名手档案 · ${mp.name}（无实谱）", "当代名局",
                            dimNameOf(dim), modern = mp))
                    }
                }
            }
            if (gen != searchGen) return@Thread
            flush(progress, listContainer, gen, 0, totalBooks, totalMatches, batch)
            batch.clear()

            // ★ 名/文件名预扫：仅凭 书名·分类·文件名 快速命中，不等 201 本深扫。
            //   专治“搜王天一半天不出”——名手_王天一 这种书一按即中、点开即读首局。
            for (cat in snapshot) {
                for (book in cat.books) {
                    if (gen != searchGen) return@Thread
                    if (nameMatches(kw, dim, norm(book.name), norm(cat.name),
                            norm(book.file.substringBeforeLast('.')))) {
                        totalMatches++
                        if (shown < cap) {
                            shown++
                            batch.add(SearchHit(
                                cat.name, book, 0, book.name, cat.name, dimNameOf(dim)))
                        }
                    }
                }
            }
            if (gen != searchGen) return@Thread
            flush(progress, listContainer, gen, 0, totalBooks, totalMatches, batch)
            batch.clear()

            for (cat in snapshot) {
                for (book in cat.books) {
                    if (gen != searchGen) return@Thread
                    val idx = ensureBookIndex(cat.name, book)
                    scanned++
                    if (idx != null) for (e in idx.entries) {
                        if (gen != searchGen) return@Thread
                        if (matchGame(e, kw, dim, minY, maxY)) {
                            totalMatches++
                            if (shown < cap) {
                                shown++
                                batch.add(SearchHit(idx.catName, idx.book, e.index, e.title, idx.book.name, dimNameOf(dim)))
                            }
                        }
                    }
                    if (gen != searchGen) return@Thread
                    if (batch.size >= 32 || scanned >= totalBooks) {
                        flush(progress, listContainer, gen, scanned, totalBooks, totalMatches, batch)
                        batch.clear()
                    }
                }
            }
            flush(progress, listContainer, gen, scanned, totalBooks, totalMatches, batch, done = true)
        }.apply { isDaemon = true }.start()
    }

    /** 把一批结果一次性投递到主线程渲染（在后台线程调用）。 */
    private fun flush(
        progress: TextView, listContainer: LinearLayout, gen: Int,
        scanned: Int, total: Int, totalMatches: Int, batch: ArrayList<SearchHit>, done: Boolean = false
    ) {
        if (gen != searchGen) return
        val copy = ArrayList(batch)
        val s = scanned; val t = total; val m = totalMatches
        mainHandler.post {
            if (gen != searchGen) return@post
            progress.text = "已扫描 $s/$t 本 · 命中 $m 条" + (if (done) "（完成）" else "…")
            for (hit in copy) listContainer.addView(searchHitRow(hit))
            if (done) {
                if (m == 0) {
                    listContainer.addView(subTitle("没有搜到匹配的棋谱：试试搜「王天一」「许银川」「屏风马」，或用「全部」浏览"))
                } else if (m >= 400) {
                    listContainer.addView(subTitle("命中较多，已显示前 400 条；请加关键字/年份精确检索"))
                }
            }
        }
    }

    /** 渲染一条搜索结果卡片 */
    private fun searchHitRow(hit: SearchHit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            setBackgroundColor(0x1E1A140E.toInt())
            setOnClickListener { openSearchHit(hit) }
        }
        row.addView(TextView(context).apply {
            // ★ 命中的人名/布局/残局等专名加下划线，点一下跳百度搜索；正文先钉黑字，避免落到默认白字上。
            setTextColor(textMain)
            BaiduTerm.bind(this, "🎞 " + hit.title, textMain, linkColor)
            textSize = 14f; setTypeface(null, Typeface.BOLD)
        })
        row.addView(TextView(context).apply {
            text = "📚 ${hit.source}${if (hit.catName.isNotBlank()) " · ${hit.catName}" else ""}"
            setTextColor(textSub); textSize = 12f; setPadding(0, 2.dp(), 0, 0)
        })
        row.addView(TextView(context).apply {
            val tip = if (hit.modern != null) "点此查看名手档案（人名可点 → 百度）"
                      else "命中 ${hit.dimName} · 点此打开 → 主界面棋谱拆解"
            text = tip
            setTextColor(goldText); textSize = 11f; setPadding(0, 2.dp(), 0, 0)
        })
        return row
    }

    /** 点搜索结果：后台解码该书这一局，再到主界面打开（与宝库正常开棋谱同一回调，保证真能走）。 */
    private fun openSearchHit(hit: SearchHit) {
        // ★ 当代名局命中：直接进名手档案（人名已热链，也可一键跳百度），无需解码棋谱
        if (hit.modern != null) {
            showModernPlayer(hit.modern)
            dismissSilently()
            return
        }
        Thread {
            val games = try {
                com.pikafish.chess.qipu.QipuLibrary.decodeGames(
                    com.pikafish.chess.qipu.QipuLibrary.loadGamesBytes(context, hit.book), hit.book.name)
            } catch (e: Exception) { emptyList() }
            mainHandler.post {
                // ① 二进制规整库/CBL上传：能逐局解码 → 精确打开命中的那一则（真能走、能快进）
                if (hit.gameIndex in games.indices) {
                    onOpenGame(hit.catName, hit.book, hit.gameIndex, games)
                    dismissSilently()
                    return@post
                }
                // ② 文本格式的用户上传（解码不出局）：整体打开文件，走同一套自动识别导入，保证可用
                if (hit.book.isUserUpload) {
                    if (onUserUpload != null) { onUserUpload.invoke(hit.book); dismissSilently() }
                    else toast("找不到这条棋谱")
                    return@post
                }
                // ③ HHXQ 本地文档（解码不出局）：整体打开本地文件，走主界面同款自动识别导入
                if (hit.book.isLocalDoc) {
                    if (onLocalDoc != null) { onLocalDoc.invoke(hit.book); dismissSilently() }
                    else toast("找不到这条棋谱")
                    return@post
                }
                // ④ 嵌入 CBL 按“书名/文件名”预扫命中的书：打开首局即可（首局已尽量解出）
                if (games.isNotEmpty()) {
                    onOpenGame(hit.catName, hit.book, 0, games)
                    dismissSilently()
                    return@post
                }
                toast("找不到这条棋谱")
            }
        }.apply { isDaemon = true }.start()
    }

    /** 为某本书建立轻量检索索引（分维度存文本与年份，不存整局），成功后缓存。 */
    private fun ensureBookIndex(catName: String, book: QipuBook): BookIdx? {
        searchIndex[book.file]?.let { return it }
        return try {
            val raw = com.pikafish.chess.qipu.QipuLibrary.loadGamesBytes(context, book)
            // ★ 文件名（含子目录基名，本地文档常放在 vault_games/ 下）——所有维度都并入，
            //   专治“按文件名搜不到本地文档/新上传的 CBL”。文件短名 += 路径基名，双保险。
            val nameBase = book.name.trim()
            val fileBase = book.file.substringBeforeLast('.').replace('\\', '/')
            val short = fileBase.substringAfterLast('/')
            val fileTag = norm(listOf(nameBase, short, fileBase).joinToString(" "))
            if (fileTag.length < 2 && book.name.isNotEmpty()) { /* 空保护 */ }

            // ★ 成批 FEN 习题集（如「5-10步缓杀 50000题」）：一册可能几十万题，绝不能逐题建索引
            //   （那会把几十万条索引进内存，搜索时必卡死/OOM）。只按“分类+书名+文件名”建一条，
            //   保证按书名可搜；题目本体按序号在目录里直接翻页浏览。
            if (com.pikafish.chess.qipu.QipuLibrary.isFenBatch(raw)) {
                val bookTag = norm("$catName ${book.name}") + " $fileTag"
                val idx = BookIdx(catName, book, listOf(
                    GameIdx(-1, book.name, bookTag, bookTag, bookTag, bookTag, emptySet())))
                searchIndex[book.file] = idx
                return idx
            }

            // ① 二进制规整库（内置 CBL，以及用户上传的 CBL/XQF 等）→ 逐局解码建索引
            val games = com.pikafish.chess.qipu.QipuLibrary.decodeGames(raw, book.name)
            if (games.isNotEmpty()) {
                val entries = games.mapIndexed { i, g ->
                    // ★ 只取头部元信息：归档懒局在此绝不解着法，否则一个几千局的压缩包
                    //   会让全局搜索把上百万着法全解出来（卡死）——列表/索引都只需要 meta。
                    val cb = g.meta()
                    val bookNameC = "$catName ${book.name}"
                    // 人名维度：红/黑名为主；本地文档/用户上传再并入文件名（含全路径基名），
                    // 这样“我自己下的一盘棋存成 王乐乐.cbl”，搜「王乐乐」也能命中。
                    val persons = norm("${cb.red} ${cb.black}") + if (book.isUserUpload || book.isLocalDoc) " $fileTag" else ""
                    val layout = norm(listOf(cb.event, cb.introComment, cb.endComment).joinToString(" "))
                    val text = norm(listOf(
                        cb.event, cb.red, cb.black, cb.date, cb.introComment, cb.endComment,
                        book.name, catName, fileTag).joinToString(" "))
                    val years = Regex("(?:19|20)\\d{2}").findAll(cb.date + " " + cb.event)
                        .map { it.value.toInt() }.toSet()
                    com.pikafish.chess.qipu.QipuBrowser.GameIdx(
                        i, g.title(), text, persons, norm(bookNameC) + " $fileTag", layout, years)
                }
                val idx = BookIdx(catName, book, entries)
                searchIndex[book.file] = idx
                return idx
            }
            // ② 解码为空且是文本棋谱（用户上传 / HHXQ 本地文档：PGN/中文记谱/ICCS/FEN...）→ 按文本抽取元信息建索引。
            //    ★ 每条的检索文本都并入“分类+书名+文件名”，保证按文件名也能搜到本地文档。
            if (book.isUserUpload || book.isLocalDoc) {
                val metas = com.pikafish.chess.qipu.QipuLibrary.extractTextGameMetas(raw, book.name)
                if (metas.isNotEmpty()) {
                    val bookTag = norm("$catName ${book.name}") + " $fileTag"
                    val entries = metas.map { m ->
                        com.pikafish.chess.qipu.QipuBrowser.GameIdx(
                            m.index, m.title,
                            m.text + " " + bookTag,
                            m.persons + " " + bookTag,
                            bookTag,
                            m.layout, m.years)
                    }
                    val idx = BookIdx(catName, book, entries)
                    searchIndex[book.file] = idx
                    return idx
                }
            }
            // ③ 兜底：任意格式（含二进制嵌入CBL在①解码失败的书、纯文本注释等）——
            //    至少按“分类+书名+文件名”可搜；若原文可解出文本，再把全文归一并入，扩大命中范围。
            //    ★ 这样本地文档必可按文件名搜到，名手_王天一 这类嵌入书也必可按书名搜到。
            //    ★ 用容错解码（UTF-8→GB18030）：GBK 棋谱的正文也能被搜到，不再只认 UTF-8。
            val c = try { com.pikafish.chess.qipu.QipuLibrary.decodeText(raw) } catch (e: Exception) { null }
            val bookTag = norm("$catName ${book.name}") + " $fileTag"
            val rawNorm = c?.let { norm(it) }.orEmpty()
            val text = if (rawNorm.length >= 2) "$bookTag $rawNorm" else bookTag
            if (text.isNotEmpty()) {
                val ys = c?.let { cc ->
                    Regex("(?:19|20)\\d{2}").findAll(cc).map { it.value.toInt() }.toSet()
                } ?: emptySet()
                val idx = BookIdx(catName, book, listOf(
                    com.pikafish.chess.qipu.QipuBrowser.GameIdx(
                        -1, book.name, text, text, bookTag, text, ys)))
                searchIndex[book.file] = idx
                return idx
            }
            null
        } catch (e: Exception) { null }
    }

    /** 一条棋局是否命中：①按所选维度分别对比 ②年份范围。关键：人名只对红/黑、书名只对书、布局只对开局文字。 */
    private fun matchGame(e: GameIdx, kw: String, dim: SearchDim, minY: Int?, maxY: Int?): Boolean {
        if (kw.isNotEmpty()) {
            when (dim) {
                SearchDim.PERSON -> if (!e.persons.contains(kw)) return false
                SearchDim.BOOK -> if (!e.bookName.contains(kw)) return false
                SearchDim.LAYOUT -> if (!e.layout.contains(kw)) return false
                SearchDim.ALL -> if (!e.text.contains(kw) &&
                    !e.persons.contains(kw) && !e.bookName.contains(kw) && !e.layout.contains(kw)) return false
            }
        }
        if (minY != null || maxY != null) {
            if (e.years.isEmpty()) return false
            var ok = false
            for (y in e.years) if ((minY == null || y >= minY) && (maxY == null || y <= maxY)) { ok = true; break }
            if (!ok) return false
        }
        return true
    }

    private fun dimNameOf(dim: SearchDim): String = when (dim) {
        SearchDim.PERSON -> "人名"; SearchDim.BOOK -> "书名"; SearchDim.LAYOUT -> "布局"; SearchDim.ALL -> "全部"
    }

    /** 当代名手是否命中：人名维度比姓名；全部维度比 姓名+简介+常用开局。 */
    private fun matchModern(mp: ModernPlayer, kw: String, dim: SearchDim): Boolean {
        return when (dim) {
            SearchDim.PERSON -> kw.isBlank() || norm(mp.name).contains(kw)
            SearchDim.ALL -> kw.isBlank() ||
                norm(mp.name + mp.note + mp.bio + mp.openings.joinToString("")).contains(kw)
            else -> false
        }
    }

    /** 预扫阶段：按 书名/分类/文件名 快速命中（先于 201 本深扫，秒出结果）。 */
    private fun nameMatches(kw: String, dim: SearchDim, bn: String, catn: String, fileBase: String): Boolean {
        if (kw.isEmpty()) return false
        return when (dim) {
            SearchDim.BOOK -> fileBase.contains(kw) || bn.contains(kw)
            SearchDim.ALL -> bn.contains(kw) || catn.contains(kw) || fileBase.contains(kw)
            else -> false   // 人名/布局维度交给深扫，避免把“书名当人名”误报
        }
    }

    /** 归一化检索文本：去标点/空白，统一小写 */
    private fun norm(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^0-9a-z\\u4e00-\\u9fa5]"), "")


    /** ★ 第 14 项：双人对战（对局自动存档目录，TXT 棋谱） */
    private fun duelRow(count: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 14, 12, 14)
            setBackgroundColor(0xFFE65100.toInt())
            setOnClickListener { openDuelGames() }
        }
        row.addView(TextView(context).apply {
            text = "⚔"; textSize = 15f; setPadding(0, 0, 8.dp(), 0)
        })
        row.addView(TextView(context).apply {
            text = "14. 双人对战"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = "（${count} 局）"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
        })
        return row
    }

    /** 双人对战目录：filesDir/双人对战 下的自动存档 TXT */
    private fun duelDir(): java.io.File = java.io.File(context.filesDir, "双人对战")

    private fun duelFiles(): List<java.io.File> =
        duelDir().listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun openDuelGames() {
        val files = duelFiles()
        if (files.isEmpty()) {
            android.app.AlertDialog.Builder(context)
                .setTitle("双人对战")
                .setMessage("还没有存档。\n到「双人对战」下完棋（或走几步退出），这里会自动出现 TXT 棋谱。")
                .setPositiveButton("知道了", null).show()
            return
        }
        val names = files.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(context)
            .setTitle("双人对战 · ${files.size} 局")
            .setItems(names) { _, idx ->
                val f = files[idx]
                val text = runCatching { f.readText() }.getOrDefault("（读取失败）")
                android.app.AlertDialog.Builder(context)
                    .setTitle(f.name)
                    .setMessage(text)
                    .setPositiveButton("关闭", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun myGamesRow(savedCount: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 14, 12, 14)
            setBackgroundColor(0xFFFFB300.toInt())
            setOnClickListener { openMyGames() }
        }
        row.addView(TextView(context).apply {
            text = "🗂"; textSize = 15f; setPadding(0, 0, 8.dp(), 0)
        })
        row.addView(TextView(context).apply {
            text = "我的对局（续盘存档）"
            setTextColor(dark); textSize = 16f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = "（$savedCount）"
            setTextColor(dark); textSize = 13f
            // ★ 本数带下划线：点一下弹「清空我的对局（真删）」；为 0 时无需清空，不加下划线。
            if (savedCount > 0) {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { clearMyGamesPrompt() }
            }
        })
        return row
    }

    // ════════════════════════ CBL 合成区 ════════════════════════
    private val synthRowColor = 0xFF1F7A63.toInt()   // 墨绿，与金/紫/蓝青区分：合成区是「打包出口」

    /** CBL 合成区入口：点开进入合成区（重命名/移动/排序/勾选导出 CBL）。 */
    private fun synthAreaRow(count: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 14, 12, 14)
            setBackgroundColor(synthRowColor)
            setOnClickListener { openSynthArea() }
        }
        row.addView(TextView(context).apply {
            text = "🧬"; textSize = 15f; setPadding(0, 0, 8.dp(), 0)
        })
        row.addView(TextView(context).apply {
            text = "CBL 合成区（汇集 / 排序 / 打包导出）"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = "（$count 则）"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
        })
        return row
    }

    /** 把「某本书的某一则」加入合成区（返回是否新增）。 */
    private fun addToSynth(item: CblSynth.Item): Boolean {
        val ok = try { CblSynth.add(context, item) } catch (e: Exception) { false }
        if (ok) toast("已加入合成区（${CblSynth.count(context)} 则）") else toast("该则已在合成区")
        return ok
    }

    /**
     * ★ CBL 合成区管理屏：条目列表（勾选 / 改名 / 上移下移 / 删除）+ 排序 + 打包导出。
     *   只记引用，导出时才解析；导出后整区可清空以省空间。
     */
    private fun openSynthArea() {
        val (d, root) = newShell()
        val items = try { CblSynth.list(context) } catch (e: Exception) { ArrayList<CblSynth.Item>() }
        // 清掉已不存在的勾选（被删除后残留）
        synthChecked.retainAll(items.map { it.id }.toSet())

        root.addView(titleView("CBL 合成区"))
        root.addView(subTitle("汇集来源条目 · 勾选后合成一个 CBL 导出（位置可改）· 只记引用，原文件不动"))

        // ── 工具行①：排序 / 清空
        val tool1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 6)
        }
        fun toolBtn(label: String, onClick: () -> Unit) = Button(context).apply {
            text = label; textSize = 12f; setTextColor(onDark); setBackgroundColor(dark)
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(4.dp(), 0, 4.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(0, 40.dp(), 1f).apply { marginEnd = 4.dp() }
            setOnClickListener { onClick() }
        }
        tool1.addView(toolBtn("按名称排序") { CblSynth.sortByName(context); openSynthArea() })
        tool1.addView(toolBtn("按时间排序") { CblSynth.sortByTime(context); openSynthArea() })
        tool1.addView(toolBtn("🗑清空合成区") {
            if (items.isEmpty()) { toast("合成区已是空的"); return@toolBtn }
            confirmClear("清空合成区", "将移出合成区全部 ${items.size} 则（不影响任何原文件）。确认清空？") {
                CblSynth.clear(context); synthChecked.clear(); openSynthArea()
            }
        })
        root.addView(tool1)

        // ── 列表
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (items.isEmpty()) {
            list.addView(subTitle("（合成区暂无条目）\n在各书目录里点某则右侧「→合成」，或在「我的对局」点「→合成」，或在「挑战棋谱 / 本地文档」书卡上点「→合成」（整本汇入），即可把它汇集到这里。"))
        } else {
            items.forEachIndexed { i, item -> list.addView(synthRow(item, i)) }
        }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)

        // ── 工具行②：全选 / 导出 / 返回 / 关闭
        val tool2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 2)
        }
        val allChecked = items.isNotEmpty() && synthChecked.containsAll(items.map { it.id })
        tool2.addView(Button(context).apply {
            text = if (allChecked) "全不选" else "全选"
            textSize = 13f; setTextColor(textMain); setBackgroundColor(panel)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener {
                if (allChecked) synthChecked.clear() else items.forEach { synthChecked.add(it.id) }
                openSynthArea()
            }
        })
        tool2.addView(Button(context).apply {
            text = "导出为 CBL（${if (synthChecked.isEmpty()) items.size else synthChecked.size} 则）"
            textSize = 13f; setTextColor(dark); setBackgroundColor(gold)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1.6f)
            setOnClickListener { exportSynth(items) }
        })
        tool2.addView(Button(context).apply {
            text = "← 首页"; textSize = 13f; setTextColor(textMain); setBackgroundColor(panel)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { renderCategoryList() }
        })
        tool2.addView(Button(context).apply {
            text = "关闭"; textSize = 13f; setTextColor(textMain); setBackgroundColor(panel)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dismissSilently() }
        })
        root.addView(tool2)
        show(d)
    }

    /**
     * 合成区里的一行：勾选 + 序号/名称 + 来源小字 + 上移/下移/改名/删除。
     * ★ 名称、来源各占一整行（宽度 weight=1），按钮单独一行 —— 名称再长也是「横着排」，
     *   绝不会因为右边挤了一排按钮而被压成一字一行的竖排。
     */
    private fun synthRow(item: CblSynth.Item, pos: Int): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp(), 8, 6.dp(), 8)
            setBackgroundColor(if (item.id in synthChecked) 0x2E4A3820.toInt() else 0x1E1A140E.toInt())
        }
        // ① 勾选 + 名称（名称横向铺满整行，超长自然省略）
        val line1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(CheckBox(context).apply {
            isChecked = item.id in synthChecked
            setOnCheckedChangeListener { _, checked ->
                if (checked) synthChecked.add(item.id) else synthChecked.remove(item.id)
                card.setBackgroundColor(if (checked) 0x2E4A3820.toInt() else 0x1E1A140E.toInt())
            }
        })
        line1.addView(TextView(context).apply {
            text = "${pos + 1}. ${item.name}"
            setTextColor(rowText); textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(line1)

        // ② 来源小字（同样横向铺满一整行）
        val src = when (item.kind) {
            CblSynth.KIND_JOURNAL -> "来源：我的对局"
            else -> "来源：${item.cat.ifBlank { "棋谱库" }} · 《${item.bookName}》" +
                if (item.index == CblSynth.INDEX_WHOLE) " 全书" else " 第 ${item.index + 1} 则"
        }
        card.addView(TextView(context).apply {
            text = src
            setTextColor(textSub); textSize = 11f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(4.dp(), 2, 0, 4)
        })

        // ③ 操作按钮独占一行，四等分
        val ops = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun opBtn(label: String, color: Int, onClick: () -> Unit) = Button(context).apply {
            text = label; textSize = 12f; setTextColor(color); setBackgroundColor(panel)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, 36.dp(), 1f).apply {
                marginStart = 2.dp(); marginEnd = 2.dp()
            }
            setOnClickListener { onClick() }
        }
        ops.addView(opBtn("↑ 上移", textMain) { CblSynth.moveUp(context, item.id); openSynthArea() })
        ops.addView(opBtn("↓ 下移", textMain) { CblSynth.moveDown(context, item.id); openSynthArea() })
        ops.addView(opBtn("✎ 改名", textMain) { renameSynthPrompt(item) })
        ops.addView(opBtn("🗑 删除", 0xFFE0B0A0.toInt()) {
            confirmClear("移出合成区", "将「${item.name}」移出合成区（不影响原文件）。确认？", confirmLabel = "确认移出") {
                CblSynth.remove(context, item.id); synthChecked.remove(item.id); openSynthArea()
            }
        })
        card.addView(ops)
        return card
    }

    /** 重命名合成区条目（只改显示名，不动原文件）。 */
    private fun renameSynthPrompt(item: CblSynth.Item) {
        val input = EditText(context).apply { setText(item.name); textSize = 14f }
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "重命名（仅合成区内显示名）"; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(input)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 0)
        }
        row.addView(Button(context).apply {
            text = "取消"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dd.dismiss() }
        })
        row.addView(Button(context).apply {
            text = "确定"; textSize = 14f; setTextColor(dark); setBackgroundColor(gold)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener {
                CblSynth.rename(context, item.id, input.text.toString())
                dd.dismiss(); openSynthArea()
            }
        })
        v.addView(row)
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    /** 导出合成区条目为 CBL：先弹「文件名 + 目录」框（位置随便改），确认后后台流式编码。 */
    private fun exportSynth(allItems: List<CblSynth.Item>) {
        if (allItems.isEmpty()) { toast("合成区是空的，先加入条目再导出"); return }
        // 勾选的优先；一个都没勾则默认全部（符合“先全部，再额外剔除”的用法）
        val chosen = allItems.filter { it.id in synthChecked }.ifEmpty { allItems }
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "导出为 CBL（${chosen.size} 则）"; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 4.dp())
        })
        v.addView(TextView(context).apply {
            text = "按当前顺序合成一个纯 CBL 棋库（第三方打谱软件可读）。导出位置可自行修改。"
            setTextColor(textSub); textSize = 12f; setPadding(0, 0, 0, 8.dp())
        })
        v.addView(TextView(context).apply { text = "文件名（不含扩展名）"; setTextColor(textSub); textSize = 12f })
        val nameEdit = EditText(context).apply { setText("合成棋谱"); textSize = 14f }
        v.addView(nameEdit)
        v.addView(TextView(context).apply {
            text = "导出目录（相对/绝对均可，如 HHXQ/导出 或 /sdcard/Download）"
            setTextColor(textSub); textSize = 12f; setPadding(0, 8.dp(), 0, 0)
        })
        val dirEdit = EditText(context).apply { setText("HHXQ/导出"); textSize = 14f }
        v.addView(dirEdit)
        // 快捷目录
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 6.dp(), 0, 0)
        }
        fun chip(label: String, dir: () -> String) = Button(context).apply {
            text = label; textSize = 12f; setTextColor(textMain); setBackgroundColor(0x1E1A140E.toInt())
            layoutParams = LinearLayout.LayoutParams(0, 40.dp(), 1f)
            setOnClickListener { dirEdit.setText(dir()) }
        }
        chips.addView(chip("HHXQ/导出") { "HHXQ/导出" })
        chips.addView(chip("应用外部") { context.getExternalFilesDir(null)?.absolutePath ?: "HHXQ/导出" })
        chips.addView(chip("应用内部") { context.filesDir.absolutePath })
        v.addView(chips)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 12.dp(), 0, 0)
        }
        row.addView(Button(context).apply {
            text = "取消"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dd.dismiss() }
        })
        row.addView(Button(context).apply {
            text = "导出"; textSize = 14f; setTextColor(dark); setBackgroundColor(gold)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener {
                val nm = nameEdit.text.toString()
                val dr = dirEdit.text.toString()
                dd.dismiss()
                toast("正在合成 CBL（共 ${chosen.size} 则）…")
                Thread {
                    val r = try { CblSynth.exportCbl(context, chosen, nm, dr) }
                    catch (e: Exception) { CblSynth.ExportResult(0, "", e.message ?: "未知错误") }
                    Handler(Looper.getMainLooper()).post {
                        if (r.err.isEmpty()) showExportDone("${r.path}\n（已写入 ${r.written} 则）")
                        else toast("导出失败：${r.err}")
                    }
                }.start()
            }
        })
        v.addView(row)
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    // ════════════════════════ 复盘文件 ════════════════════════
    private val reviewRowColor = 0xFF7B4FA6.toInt()   // 紫，与金/蓝青/红区分：复盘是「纯本地分析档案」

    /** 复盘文件入口：点开即调主界面复盘历史，加载既往复盘为完整可跳转的成品 */
    private fun reviewFilesRow(count: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 14, 12, 14)
            setBackgroundColor(reviewRowColor)
            setOnClickListener {
                dismissSilently()
                onOpenReviewHistory?.invoke()
            }
        }
        row.addView(TextView(context).apply {
            text = "🧩"; textSize = 15f; setPadding(0, 0, 8.dp(), 0)
        })
        row.addView(TextView(context).apply {
            text = "复盘文件"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = "（$count）"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
            // ★ 本数带下划线：点一下弹「清空复盘文件（真删）」；为 0 时无需清空，不加下划线。
            if (count > 0) {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { clearReviewFilesPrompt() }
            }
        })
        return row
    }

    // ════════════════════════ 当代名局 · 名手档案 ════════════════════════
    private val modernRowColor = 0xFF2E6E8E.toInt()   // 蓝青，与金色宝库首页区分

    private fun modernShelfRow(count: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 14, 12, 14)
            setBackgroundColor(modernRowColor)
            setOnClickListener { openModernShelf() }
        }
        row.addView(TextView(context).apply {
            text = "🏆"; textSize = 15f; setPadding(0, 0, 8.dp(), 0)
        })
        row.addView(TextView(context).apply {
            text = "当代名局 · 名手档案（该处无实谱）"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = "（$count 位）"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
        })
        return row
    }

    /** 当代名局书柜：列出全部名手，点行看详情 */
    private fun openModernShelf() {
        val (d, root) = newShell()
        root.addView(titleView("当代名局 · 名手档案（该处无实谱）"))
        root.addView(subTitle("真实战绩/简介/常用开局（该处无实谱，不含棋谱文件）· 下划线专名可点 → 跳百度搜索 · 点行礼查详情"))

        val listRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listRow.addView(decorStrip(leftOrRight = true))
        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            addView(listContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listRow.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        listRow.addView(decorStrip(leftOrRight = false))
        root.addView(listRow)

        ModernData.load(context).forEach { listContainer.addView(modernPlayerRow(it)) }

        root.addView(backHomeBar())
        show(d)
    }

    /** 一位名手的列表卡片：姓名（热链）+ 称号籍贯 + 常用开局（热链） */
    private fun modernPlayerRow(p: ModernPlayer): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp(), 8, 4.dp(), 8)
            setBackgroundColor(0x1E1A140E.toInt())
            setOnClickListener { showModernPlayer(p) }
        }
        val line1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(TextView(context).apply {
            setTextColor(textMain)
            BaiduTerm.bind(this, p.name, textMain, linkColor)
            textSize = 16f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        line1.addView(TextView(context).apply {
            text = "${p.win}胜/${p.loss}负/${p.draw}和"
            setTextColor(goldText); textSize = 12f; setPadding(6.dp(), 0, 0, 0)
        })
        card.addView(line1)
        if (p.note.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = p.note
                setTextColor(textSub); textSize = 12f; setPadding(0, 3.dp(), 0, 0)
            })
        }
        if (p.openings.isNotEmpty()) {
            val joined = "常用开局：" + p.openings.joinToString("、")
            val openTv = TextView(context)
            openTv.setTextColor(textMain)
            BaiduTerm.bind(openTv, joined, textMain, linkColor)
            openTv.textSize = 12f
            openTv.setPadding(0, 3.dp(), 0, 0)
            openTv.setLineSpacing(1f, 1f)
            card.addView(openTv)
        }
        card.addView(TextView(context).apply {
            text = "点此查看档案 + 跳百度看对局"
            setTextColor(goldText); textSize = 11f; setPadding(0, 2, 0, 0)
        })
        return card
    }

    /** 名手详情：姓名大字号热链 + 简介(热链) + 战绩 + 常用开局(逐条热链) + 跳百度 */
    private fun showModernPlayer(p: ModernPlayer) {
        val (d, root) = newShell()
        root.addView(titleView("名手档案"))
        val nameTv = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 22f; setTypeface(null, Typeface.BOLD); setPadding(0, 2, 0, 4)
        }
        nameTv.setTextColor(textMain)
        BaiduTerm.bind(nameTv, p.name, textMain, linkColor)
        root.addView(nameTv)
        if (p.note.isNotBlank()) root.addView(subTitle(p.note))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
            setBackgroundColor(0x1E1A140E.toInt())
        }
        // 简介（热链）
        if (p.bio.isNotBlank()) {
            val bioTv = TextView(context)
            bioTv.setTextColor(textMain)
            BaiduTerm.bind(bioTv, "简介：" + p.bio, textMain, linkColor)
            bioTv.textSize = 14f
            bioTv.setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
            bioTv.setLineSpacing(2f, 1f)
            body.addView(bioTv)
        }
        // 战绩
        body.addView(TextView(context).apply {
            text = p.recordLine()
            setTextColor(goldText); textSize = 13f; setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        })
        // 常用开局（逐条热链，点一下跳百度搜该布局）
        if (p.openings.isNotEmpty()) {
            body.addView(TextView(context).apply {
                text = "常用开局（点下划线词语即可百度）："
                setTextColor(textSub); textSize = 13f; setPadding(4.dp(), 4.dp(), 4.dp(), 2.dp())
            })
            p.openings.forEach { op ->
                val opRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(0x2E2A2018.toInt())
                    setPadding(4.dp(), 6, 4.dp(), 6)
                    setOnClickListener { /* 留空：点词语才跳百度，整行不拦截 */ }
                }
                opRow.addView(TextView(context).apply {
                    text = "·"
                    setTextColor(goldText); textSize = 13f; setPadding(0, 0, 4.dp(), 0)
                })
                val opTv = TextView(context)
                opTv.setTextColor(textMain)
                BaiduTerm.bind(opTv, op, textMain, linkColor)
                opTv.textSize = 13f
                opTv.setPadding(0, 0, 0, 0)
                opRow.addView(opTv)
                opRow.addView(TextView(context).apply {
                    text = " 百度"; textSize = 11f; setTextColor(goldText); setPadding(6.dp(), 0, 0, 0)
                })
                body.addView(opRow)
            }
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部：去百度看其完整对局 + 返回
        val ops = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 2.dp())
        }
        ops.addView(Button(context).apply {
            text = "🔍 百度其完整对局"; textSize = 14f; setTextColor(dark); setBackgroundColor(gold)
            layoutParams = LinearLayout.LayoutParams(0, 46.dp(), 1f)
            setOnClickListener { BaiduTerm.openBaidu(context, p.name) }
        })
        ops.addView(Button(context).apply {
            text = "返回"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 46.dp(), 1f)
            setOnClickListener { openModernShelf() }
        })
        root.addView(ops)
        show(d)
    }

    // ════════════════════════ 我的储局管理 ════════════════════════
    private fun openMyGames() {
        val (d, root) = newShell()
        root.addView(titleView("我的对局"))
        root.addView(subTitle("新开局前保存到宝库的半局对局 · 点可续盘，可重命名/删除"))

        val listRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listRow.addView(decorStrip(leftOrRight = true))
        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            addView(listContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listRow.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        listRow.addView(decorStrip(leftOrRight = false))
        root.addView(listRow)

        val logs = GameJournal.listLog(context).filter { it.stateJson != null && it.id != GameJournal.DRAFT_ID }
        if (logs.isEmpty()) {
            listContainer.addView(subTitle("（暂无已保存的对局；下棋中途点「新开局」→ 选「保存到宝库」即可存到这里续盘）"))
        }
        logs.forEach { entry ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(4.dp(), 8, 4.dp(), 8)
                setBackgroundColor(0x1E1A140E.toInt())
                // 整卡点击 = 续盘
                setOnClickListener {
                    onResumeJournal?.invoke(entry.id)
                    dismissSilently()
                }
            }
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(context).apply {
                text = entry.title
                setTextColor(textMain); textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val btnRename = Button(context).apply {
                text = "✎ 改名"; textSize = 12f; setTextColor(textMain); setBackgroundColor(panel)
                setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
                setOnClickListener { renamePrompt(entry) }
            }
            val btnDel = Button(context).apply {
                text = "🗑 删除"; textSize = 12f; setTextColor(0xFFE0B0A0.toInt()); setBackgroundColor(panel)
                setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
                setOnClickListener { deletePrompt(entry) }
            }
            head.addView(btnDel)
            // ★ 加入合成区：只记引用（对局 id），导出时从快照取走法或另存。
            head.addView(Button(context).apply {
                text = "→合成"; textSize = 12f; setTextColor(gold); setBackgroundColor(panel)
                setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
                setOnClickListener {
                    try {
                        addToSynth(
                            CblSynth.Item(
                                name = entry.title.ifBlank { "我的对局" },
                                kind = CblSynth.KIND_JOURNAL,
                                bookName = "我的对局", logId = entry.id
                            )
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }
            })
            card.addView(head)
            card.addView(TextView(context).apply {
                text = buildString {
                    append(journalDate(entry.updatedAt))
                    append(" · ${entry.movesCount} 步")
                    entry.note.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                setTextColor(textSub); textSize = 12f; setPadding(0, 3.dp(), 0, 0)
            })
            card.addView(TextView(context).apply {
                text = "点卡片续盘"
                setTextColor(goldText); textSize = 11f; setPadding(0, 2.dp(), 0, 0)
            })
            listContainer.addView(card)
        }

        root.addView(backHomeBar())
        show(d)
    }

    /** 重命名我的对局 */
    private fun renamePrompt(entry: GameJournal.LogEntry) {
        val input = EditText(context).apply { setText(entry.title); textSize = 14f }
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "重命名对局"; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(input)
        val b = Button(context).apply {
            text = "确定"; textSize = 14f; setTextColor(dark); setBackgroundColor(gold)
            setOnClickListener {
                GameJournal.renameLog(context, entry.id, input.text.toString())
                dd.dismiss(); openMyGames()
            }
        }
        v.addView(b)
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    /** 删除我的对局（二次确认） */
    private fun deletePrompt(entry: GameJournal.LogEntry) {
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "删除对局"; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(TextView(context).apply {
            text = "确定删除「${entry.title}」？删除后不可恢复，也无法续盘。"
            setTextColor(textMain); textSize = 14f; setPadding(0, 0, 0, 12.dp())
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(Button(context).apply {
            text = "取消"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dd.dismiss() }
        })
        row.addView(Button(context).apply {
            text = "确认删除"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFFB0412E.toInt())
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener {
                GameJournal.deleteLog(context, entry.id)
                dd.dismiss(); openMyGames()
            }
        })
        v.addView(row)
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    private fun journalDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINESE).format(java.util.Date(ms))

    private fun categoryRow(index: Int, cat: QipuCategory): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 14, 12, 14)
            setBackgroundColor(0x2E2A2018.toInt())
            setOnClickListener { toggleCategory(index) }
        }
        row.addView(TextView(context).apply {
            text = if (index == expandedCatIndex) "▼" else "▶"
            setTextColor(goldText); textSize = 12f; setPadding(0, 0, 8.dp(), 0)
        })
        row.addView(TextView(context).apply {
            text = "${index + 1}. ${cat.name}"
            setTextColor(textMain); textSize = 16f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = "（${cat.books.size}本）"
            setTextColor(textSub); textSize = 12f
            // ★ 可清空分类（我的上传/挑战棋谱/本地文档）：本数带下划线，点一下即弹「清空后无法恢复」
            //   本数为 0 时无需清空，不加下划线（保持清爽，也不弹空清空框）。
            if (clearableCategory(cat.name) && cat.books.isNotEmpty()) {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { clearCategoryPrompt(cat) }
            }
        })
        return row
    }

    /** 可整册物理清空的分类名（用户上传 / 挑战棋谱 / 本地文档；内置 assets 书只读、不可清）。 */
    private fun clearableCategory(name: String): Boolean =
        name == "我的上传" || name == "挑战棋谱" || name == "本地文档"

    private fun toggleCategory(index: Int) {
        expandedCatIndex = if (expandedCatIndex == index) -1 else index
        renderCategoryList()
    }

    // ════════════════════════ 书行 → 打开本书棋谱列表 ════════════════════════

    private fun bookRow(catIndex: Int, bookIndex: Int, book: QipuBook): View {
        // ★ 用户上传棋谱：专属卡片——点整卡即打开（自动识别），底部配 时间/改名/删除
        if (book.isUserUpload) return userUploadRow(catIndex, bookIndex, book)
        // ★ HHXQ 本地文档（存档收集）：专属卡片——点整卡即从绝对路径读出并自动识别，无需手工导入
        if (book.isLocalDoc) return localDocRow(catIndex, bookIndex, book)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 10, 8.dp(), 10)
            setBackgroundColor(0x1E1A140E.toInt())
            setOnClickListener { openBook(catIndex, bookIndex, book) }
        }
        row.addView(TextView(context).apply {
            text = "📚"; textSize = 14f; setPadding(0, 0, 6.dp(), 0)
        })
        row.addView(TextView(context).apply {
            text = book.name
            setTextColor(textMain); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = formatSize(book.rawSize)
            setTextColor(textSub); textSize = 11f
        })
        return row
    }

    /** ★ 用户上传棋谱卡片：点空白区打开（先列出本书全部棋局，再点某则开谱），附 年月日、改名、删除 */
    private fun userUploadRow(catIndex: Int, bookIndex: Int, book: QipuBook): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 8, 8.dp(), 8)
            setBackgroundColor(0x1E1A140E.toInt())
            setOnClickListener {
                if (onUserUpload == null && onOpenGame == null) {
                    toast("请在【上传棋谱到宝库】后，点这里打开")
                } else {
                    openBook(catIndex, bookIndex, book)
                }
            }
        }
        // 第一行：书名 + 扩展名提示
        val line1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(TextView(context).apply {
            text = "🎞"; textSize = 13f; setPadding(0, 0, 6.dp(), 0)
        })
        line1.addView(TextView(context).apply {
            text = book.name
            setTextColor(textMain); textSize = 14f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        line1.addView(TextView(context).apply {
            val ext = book.file.substringAfterLast('.', "").uppercase()
            text = if (ext.isBlank()) "" else "·$ext"
            setTextColor(goldText); textSize = 11f; setPadding(4.dp(), 0, 0, 0)
        })
        card.addView(line1)
        // 第二行：年月日 + 修改/删除
        val line2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp(), 0, 0)
        }
        line2.addView(TextView(context).apply {
            text = uploadDate(book.mtime)
            setTextColor(textSub); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        line2.addView(Button(context).apply {
            text = "✎改名"; textSize = 12f; setTextColor(textMain); setBackgroundColor(panel)
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            setOnClickListener { renameUploadPrompt(book) }
        })
        line2.addView(Button(context).apply {
            text = "🗑删除"; textSize = 12f; setTextColor(0xFFE0B0A0.toInt()); setBackgroundColor(panel)
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            setOnClickListener { deleteUploadPrompt(book) }
        })
        card.addView(line2)
        return card
    }

    /** ★ HHXQ 本地文档卡片（存档收集）：点整卡先列出全部棋局再开谱，附 日期/格式/大小。 */
    private fun localDocRow(catIndex: Int, bookIndex: Int, book: QipuBook): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 8, 8.dp(), 8)
            setBackgroundColor(0x1E1A140E.toInt())
            setOnClickListener {
                if (onLocalDoc == null && onOpenGame == null) toast("本地文档暂不可打开")
                else openBook(catIndex, bookIndex, book)
            }
        }
        // 第一行：书名 + 扩展名提示
        val line1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(TextView(context).apply {
            text = "📄"; textSize = 13f; setPadding(0, 0, 6.dp(), 0)
        })
        line1.addView(TextView(context).apply {
            text = book.name
            setTextColor(textMain); textSize = 14f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        line1.addView(TextView(context).apply {
            val ext = book.file.substringAfterLast('.', "").uppercase()
            text = if (ext.isBlank()) "" else "·$ext"
            setTextColor(goldText); textSize = 11f; setPadding(4.dp(), 0, 0, 0)
        })
        card.addView(line1)
        // 第二行：日期 + 大小（HHXQ 本地文件，清缓存不丢）
        val line2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp(), 0, 0)
        }
        line2.addView(TextView(context).apply {
            text = uploadDate(book.mtime)
            setTextColor(textSub); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        line2.addView(TextView(context).apply {
            text = formatSize(book.rawSize)
            setTextColor(textSub); textSize = 11f; setPadding(4.dp(), 0, 0, 0)
        })
        // ★ 逐份删除（同「我的上传」）：物理真删，路径下确实消失。
        line2.addView(Button(context).apply {
            text = "🗑删除"; textSize = 12f; setTextColor(0xFFE0B0A0.toInt()); setBackgroundColor(panel)
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            setOnClickListener { deleteLocalDocPrompt(book) }
        })
        // ★ 删除后面加「→合成」：把整本书（全部棋则）直接汇进 CBL 合成区，
        //   不必先点进书里逐则挑。只记引用，原文件不动；导出时逐则展开编码。
        line2.addView(Button(context).apply {
            text = "→合成"; textSize = 12f; setTextColor(gold); setBackgroundColor(panel)
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            setOnClickListener {
                try {
                    addToSynth(
                        CblSynth.Item(
                            name = book.name.ifBlank { "本地文档" },
                            kind = CblSynth.KIND_BOOK,
                            cat = cats.getOrNull(catIndex)?.name ?: "本地文档",
                            bookName = book.name, file = book.file,
                            index = CblSynth.INDEX_WHOLE,
                            isLocalDoc = true
                        )
                    )
                } catch (e: Exception) { e.printStackTrace() }
            }
        })
        card.addView(line2)
        return card
    }

    /** 重命名用户上传棋谱 */
    private fun renameUploadPrompt(book: QipuBook) {
        val input = EditText(context).apply {
            setText(book.name); textSize = 14f
        }
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "重命名棋谱"; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(TextView(context).apply {
            text = "只改名称，保留 ${book.file.substringAfterLast('.', "").uppercase()} 格式。"
            setTextColor(textSub); textSize = 12f; setPadding(0, 0, 0, 8.dp())
        })
        v.addView(input)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 0)
        }
        row.addView(Button(context).apply {
            text = "取消"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dd.dismiss() }
        })
        row.addView(Button(context).apply {
            text = "确定"; textSize = 14f; setTextColor(dark); setBackgroundColor(gold)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener {
                val ok = com.pikafish.chess.qipu.QipuLibrary.renameUserUpload(context, book.file, input.text.toString())
                dd.dismiss()
                if (ok) { toast("已重命名"); reloadCats() } else toast("重命名失败（存在同名或非法字符）")
            }
        })
        v.addView(row)
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    /** 删除用户上传棋谱（二次确认） */
    private fun deleteUploadPrompt(book: QipuBook) {
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "删除棋谱"; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(TextView(context).apply {
            text = "确定删除「${book.name}」？删除后不可恢复。"
            setTextColor(textMain); textSize = 14f; setPadding(0, 0, 0, 12.dp())
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(Button(context).apply {
            text = "取消"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dd.dismiss() }
        })
        row.addView(Button(context).apply {
            text = "确认删除"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFFB0412E.toInt())
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener {
                val ok = com.pikafish.chess.qipu.QipuLibrary.deleteUserUpload(context, book.file)
                dd.dismiss()
                if (ok) { toast("已删除"); reloadCats() } else toast("删除失败")
            }
        })
        v.addView(row)
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    /** 重载分类（用户上传增删改名后调用），回到分类首页 */
    private fun reloadCats() {
        try {
            cats = QipuLibrary.loadCategories(context)
            expandedCatIndex = -1
            renderCategoryList()
        } catch (e: Exception) { toast("刷新失败: ${e.message}") }
    }

    // ════════════════ 清空（真删：清完路径下确实没有这些文件）════════════════

    /** 通用「删除/清空后无法恢复」确认框：取消 / 确认（确认按钮文字可定制）。 */
    private fun confirmClear(title: String, message: String, confirmLabel: String = "确认清空", onConfirm: () -> Unit) {
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = title; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(TextView(context).apply {
            text = message; setTextColor(textMain); textSize = 14f; setPadding(0, 0, 0, 12.dp())
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(Button(context).apply {
            text = "取消"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dd.dismiss() }
        })
        row.addView(Button(context).apply {
            text = confirmLabel; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFB0412E.toInt())
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dd.dismiss(); onConfirm() }
        })
        v.addView(row)
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    /** 清空一个分类：逐本物理删除，清完本数真实归零。 */
    private fun clearCategoryPrompt(cat: QipuCategory) {
        confirmClear(
            "清空「${cat.name}」",
            "将删除该分类下全部 ${cat.books.size} 份文件（物理真删，删除后无法恢复）。确认清空？"
        ) {
            val n = try { QipuLibrary.clearCategory(context, cat) } catch (e: Exception) { 0 }
            bookGamesCache.clear()
            searchIndex.clear()
            toast(if (n > 0) "已清空 $n 份" else "清空完成（0 份）")
            reloadCats()
        }
    }

    /** 删除一份本地文档 / 挑战棋谱（物理真删，路径下确实消失）。 */
    private fun deleteLocalDocPrompt(book: QipuBook) {
        confirmClear(
            "删除「${book.name}」",
            "将删除该文件（物理真删，删除后无法恢复）。确认删除？",
            confirmLabel = "确认删除"
        ) {
            val ok = try { QipuLibrary.deleteBook(context, book) } catch (e: Exception) { false }
            try { QipuLibrary.refreshLocalDocsCache() } catch (e: Exception) { /* 忽略 */ }
            if (ok) toast("已删除") else toast("删除失败")
            reloadCats()
        }
    }

    /** 清空「我的对局」：删除全部续盘存档（真删）。 */
    private fun clearMyGamesPrompt() {
        val saved = try {
            GameJournal.listLog(context).filter { it.id != GameJournal.DRAFT_ID }
        } catch (e: Exception) { emptyList() }
        confirmClear(
            "清空「我的对局」",
            "将删除全部 ${saved.size} 盘续盘存档（物理真删，删除后无法恢复）。确认清空？"
        ) {
            var n = 0
            for (e in saved) { try { GameJournal.deleteLog(context, e.id); n++ } catch (_: Exception) {} }
            toast("已清空 $n 盘")
            renderCategoryList()
        }
    }

    /** 清空「复盘文件」：删除目录下全部复盘存档（真删）。 */
    private fun clearReviewFilesPrompt() {
        val files = try { com.pikafish.chess.ui.ReviewArchive.list(context) } catch (e: Exception) { emptyList() }
        confirmClear(
            "清空「复盘文件」",
            "将删除全部 ${files.size} 份复盘文件（物理真删，删除后无法恢复）。确认清空？"
        ) {
            var n = 0
            for (f in files) { try { if (f.delete()) n++ } catch (_: Exception) {} }
            toast("已清空 $n 份")
            renderCategoryList()
        }
    }

    /** 导出成功提示：说明存到哪，便于用户去别的象棋软件打开。 */
    private fun showExportDone(path: String) {
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "CBL 导出成功"; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(TextView(context).apply {
            text = "已保存到：\n$path\n\n纯 CBL 棋库（无 ZIP/RAR 外壳），可直接在象棋桥等软件打开。"
            setTextColor(textMain); textSize = 14f; setPadding(0, 0, 0, 12.dp())
        })
        v.addView(Button(context).apply {
            text = "好的"; textSize = 14f; setTextColor(onDark); setBackgroundColor(dark)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp())
            setOnClickListener { dd.dismiss() }
        })
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    /** 导出失败提示：把原因摆出来，别让用户分不清是「没等够」还是「根本导不成」。 */
    private fun showExportFailed(reason: String) {
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "CBL 导出失败"; setTextColor(0xE57373); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(TextView(context).apply {
            text = "原因：$reason\n\n可回到导出框换一个目录（如「应用外部」）再试。"
            setTextColor(textMain); textSize = 14f; setPadding(0, 0, 0, 12.dp())
        })
        v.addView(Button(context).apply {
            text = "好的"; textSize = 14f; setTextColor(onDark); setBackgroundColor(dark)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp())
            setOnClickListener { dd.dismiss() }
        })
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    /**
     * ★ CBL 导出：把本册「当下整文件」的全部棋局（含变着/旁白）编成一个纯 CBL 棋库落盘，
     *   不带任何 ZIP/RAR 外壳，别的象棋软件可直接打开；解码较重，放后台线程，完成后回主线程提示。
     *
     * <p>导出期间弹一个带进度条的窗实时汇报：编码阶段报「第几则/共几则」，压缩阶段报
     * 「已写/总字节」，两者拼成一根 0–100% 的进度条；结束原地换成成功结果（含落盘位置）
     * 或失败原因——几十万则的大书也不再是“点一下干等到天亮”的开盲盒。</p>
     */
    private fun exportBookCbl(book: QipuBook, games: List<QipuGame>) {
        if (games.isEmpty()) { toast("本册没有可导出的棋局"); return }
        showExportChunkChooser(games.size) { chunk -> exportBookCbl(book, games, chunk) }
    }

    /**
     * ★ 导出方式选择：整册一个文件，或按「每卷 N 局」切成多个小册。
     *
     * 起因（实测结论）：第三方打谱软件（CNVCS 中国象棋对弈打谱 1.87 等）对单册棋库有
     * 容量上限——《适情雅趣》412 则（71KB）能打开，而「5-10步缓杀 50000题」近 50 万则
     * （未压缩 86MB）会被整册拒载（提示“打开失败”）。两册的 CBL 字节结构完全一致，
     * 差别只在体量，故对策是把巨册切成若干小卷，逐卷导出、逐卷可打开。
     */
    private fun showExportChunkChooser(count: Int, onPick: (Int) -> Unit) {
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "导出 CBL（共 $count 则）· 选择分卷"; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(TextView(context).apply {
            text = "有些打谱软件（如 CNVCS 中国象棋对弈打谱）对单册棋库有容量上限，" +
                "册子过大时会整册打不开；切成小卷即可逐卷正常打开。"
            setTextColor(textSub); textSize = 12f; setPadding(0, 0, 0, 10.dp())
        })
        val opts = intArrayOf(0, 5000, 10000, 20000)
        val labels = arrayOf("整册（导出单个 .cbl）", "分卷：每卷 5000 则", "分卷：每卷 10000 则", "分卷：每卷 20000 则")
        for (i in opts.indices) {
            val c = opts[i]
            if (c > 0 && c >= count) continue
            v.addView(Button(context).apply {
                text = labels[i]; textSize = 14f; setTextColor(onDark); setBackgroundColor(dark)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp())
                setOnClickListener { dd.dismiss(); onPick(c) }
            })
        }
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    private fun exportBookCbl(book: QipuBook, games: List<QipuGame>, chunk: Int) {
        val main = Handler(Looper.getMainLooper())
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = if (chunk > 0) "正在分卷导出 CBL（共 ${games.size} 则，每卷 ≤ $chunk 则）"
                   else "正在导出 CBL（共 ${games.size} 则）"
            setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12.dp())
        }
        v.addView(bar)
        val msg = TextView(context).apply {
            text = "正准备解码本册棋局…"; setTextColor(textMain); textSize = 13f
            setPadding(0, 8.dp(), 0, 8.dp())
        }
        v.addView(msg)
        v.addView(TextView(context).apply {
            text = "几十万则的大书耗时较长；此窗可先关掉，导出仍在后台继续，完成时会再弹窗告知位置。"
            setTextColor(textSub); textSize = 12f; setPadding(0, 0, 0, 10.dp())
        })
        v.addView(Button(context).apply {
            text = "后台运行（先关掉）"; textSize = 14f; setTextColor(onDark); setBackgroundColor(dark)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp())
            setOnClickListener { dd.dismiss() }
        })
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()

        // 进度回调极密（几万则、几万次分块），先按「百分比或 120ms」节流再回主线程刷 UI。
        var lastTick = 0L
        fun report(permille: Int, text: String) {
            val now = System.currentTimeMillis()
            if (now - lastTick < 120L && permille < 1000) return
            lastTick = now
            main.post {
                if (!dd.isShowing) return@post
                bar.progress = permille.coerceIn(0, 1000)
                msg.text = text
            }
        }
        fun sizeText(bytes: Long): String {
            val kb = bytes / 1024.0
            return if (kb >= 1024.0) String.format(Locale.CHINESE, "%.1f MB", kb / 1024.0)
            else String.format(Locale.CHINESE, "%.0f KB", kb)
        }

        Thread {
            var path = ""
            var err = ""
            try {
                val safe = book.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "棋谱" }
                val per = if (chunk > 0) chunk else games.size
                val parts = if (chunk > 0) (games.size + chunk - 1) / chunk else 1
                // 首选外部 HHXQ/导出/（便于用别的软件打开）；失败回退应用外部/内部目录
                val dirs = listOf(
                    java.io.File(android.os.Environment.getExternalStorageDirectory(), "HHXQ/导出"),
                    context.getExternalFilesDir(null),
                    context.filesDir
                )
                var wroteDir: java.io.File? = null
                var lastErr = ""
                for (d in dirs) {
                    if (d == null) continue
                    try {
                        if (!d.exists()) d.mkdirs()
                        var encodedTotal = 0
                        for (p in 0 until parts) {
                            val from = p * per
                            val to = minOf(games.size, from + per)
                            val fname = if (parts == 1) "$safe.cbl"
                                        else String.format(Locale.CHINESE, "%s_%02d.cbl", safe, p + 1)
                            val f = java.io.File(d, fname)
                            var encoded = 0
                            // ★ 流式写盘：逐局解码 + 逐条编码直写文件，不在内存里攒出整库字节。
                            //   格式用象棋桥「旧格式」(CCBridge 2.x) —— 第三方打谱软件只认这种。
                            java.io.BufferedOutputStream(java.io.FileOutputStream(f)).use { bos ->
                                CblDecoder.writeLegacyBook(
                                    bos, to - from, book.name, CblDecoder.LegacyBookMeta(), context.cacheDir,
                                    gameAt = { i -> try { games[from + i].cblGame } catch (e: Exception) { null } },
                                    onEncoded = { done, total ->
                                        encoded = done
                                        val g = encodedTotal + done
                                        val percent = g * 100 / maxOf(1, games.size)
                                        val tag = if (parts > 1) "第 ${p + 1}/$parts 卷 " else ""
                                        report(g * 900 / maxOf(1, games.size), "编码中 $tag$done / $total 则（$percent%）")
                                    },
                                    onCompress = { done, total ->
                                        val percent = (done * 100 / maxOf(1L, total)).toInt()
                                        val tag = if (parts > 1) "第 ${p + 1}/$parts 卷 " else ""
                                        report(900 + percent / 10,
                                            "$tag 压缩落盘 ${sizeText(done)} / ${sizeText(total)}（$percent%）")
                                    }
                                )
                            }
                            if (encoded == 0) { f.delete(); throw IllegalStateException("没有可导出的棋局") }
                            encodedTotal += encoded
                        }
                        wroteDir = d
                        break
                    } catch (e: Exception) { lastErr = e.message ?: "写入失败" /* 换下一个目录 */ }
                }
                if (wroteDir == null) throw java.io.IOException(lastErr.ifBlank { "写入失败" })
                path = if (parts == 1) {
                    if (wroteDir.parentFile?.name == "导出") "HHXQ/导出/$safe.cbl"
                    else java.io.File(wroteDir, "$safe.cbl").absolutePath
                } else {
                    val dirShown = if (wroteDir.parentFile?.name == "导出") "HHXQ/导出/"
                                   else wroteDir.absolutePath + "/"
                    "$dirShown（共 $parts 卷，每卷 ≤ $per 则）"
                }
            } catch (e: Exception) { err = e.message ?: "未知错误" }
            val p = path; val m = err
            main.post {
                dd.dismiss()
                if (p.isNotEmpty()) showExportDone(p) else showExportFailed(m)
            }
        }.start()
    }

    private fun uploadDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINESE).format(java.util.Date(ms))

    private fun openBook(catIndex: Int, bookIndex: Int, book: QipuBook) {
        detailPage = 0
        val cached = bookGamesCache[book.file]
        if (cached != null && cached.isNotEmpty()) { showBookDetail(catIndex, book, cached); return }
        if (loadingBookFile == book.file) return
        loadingBookFile = book.file
        toast("正在载入《${book.name}》…")
        QipuLibrary.Loader(context, book) { result ->
            loadingBookFile = null
            if (result == null || result.isEmpty()) {
                // ★ 解不出棋局：用户上传 / HHXQ 本地文档若是文本棋谱（PGN/中文记谱/ICCS/FEN…），
                //   整体打开交主界面自动识别导入，保证仍然可用——不再一律弹"无法识读"。
                when {
                    book.isUserUpload && onUserUpload != null -> { onUserUpload.invoke(book); dismissSilently() }
                    book.isLocalDoc && onLocalDoc != null -> { onLocalDoc.invoke(book); dismissSilently() }
                    else -> toast("无法识读《${book.name}》")
                }
                return@Loader
            }
            bookGamesCache[book.file] = result
            showBookDetail(catIndex, book, result)
        }.execute()
    }

    // ════════════════════════ 第 2 屏：本书棋谱列表（倒数第二个流程）═══

    /** 每页渲染多少则（一个压缩包几千局时，只渲染当前这一页，翻页再渲染下一页）。 */
    private val detailPageSize = 120

    // ★ 隐藏/取消隐藏后的重建节流：连点多次只重建一次，且延迟到手指停下再做。
    //   否则「5 万局」大书在快速连点时会被反复重建，主线程来不及喘气就卡死闪退。
    private var pendingDetailRefresh: Runnable? = null

    private fun scheduleDetailRefresh(catIndex: Int, book: QipuBook, games: List<QipuGame>) {
        pendingDetailRefresh?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingDetailRefresh = null
            try { showBookDetail(catIndex, book, games) } catch (e: Exception) { e.printStackTrace() }
        }
        pendingDetailRefresh = r
        mainHandler.postDelayed(r, 120L)
    }

    private fun showBookDetail(catIndex: Int, book: QipuBook, games: List<QipuGame>) {
        detailCatIndex = catIndex; detailBook = book; detailGames = games
        // ★ 隐藏只用于「内置宝库」（书里的一则则棋谱）：原 CBL 文件只读，隐藏只记本机；
        //   用户上传 / 本地文档 / 我的对局走真删，「自己的」东西不设隐藏。
        val hideable = !book.isUserUpload && !book.isLocalDoc
        val hidden = if (hideable) GameJournal.hiddenIndices(context, book.file) else emptySet<Int>()
        // 可见棋则（保留原始序号 index 供 GameRef 定位；隐藏的整条不进入渲染与分页）
        // ★ 用 IntArray 只存「可见序号」：避免为 5 万局每次重建 5 万个 Pair 对象，
        //   连点隐藏时不再产生大量临时对象（省内存、防 GC 卡顿）。
        val visibleIdx = IntArray(games.size)
        var visibleCount = 0
        for (i in games.indices) if (i !in hidden) visibleIdx[visibleCount++] = i
        val pageCount = maxOf(1, (visibleCount + detailPageSize - 1) / detailPageSize)
        val page = detailPage.coerceIn(0, pageCount - 1)
        detailPage = page
        val from = page * detailPageSize
        val to = minOf(visibleCount, from + detailPageSize)
        val (d, root) = newShell()

        val catName = cats.getOrNull(catIndex)?.name ?: ""
        root.addView(titleView("《${book.name}》"))
        root.addView(subTitle(
            "$catName · 本目录共 ${games.size} 则" +
                (if (hidden.isNotEmpty()) " · 已隐藏 ${hidden.size}" else "") +
                (if (pageCount > 1) " · 第 ${page + 1}/$pageCount 页（$from–$to）" else "") +
                " · 点一则开谱（下划线词可点 → 跳百度搜索）" +
                (if (pageCount > 1) " · 点下方页码格可直接跳页" else "")))

        // 顶部工具行：返回分类 + CBL 导出 + 关闭（三等分，各占 1/3）
        val tool = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 6)
        }
        fun toolBtn(label: String, weight: Float, onClick: () -> Unit) = Button(context).apply {
            text = label; textSize = 13f; setTextColor(onDark)
            setBackgroundColor(dark); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 40.dp(), weight).apply {
                marginEnd = 4.dp()
            }
            setOnClickListener { onClick() }
        }
        tool.addView(toolBtn("◀ 返回分类", 1f) { renderCategoryList() })
        // ★ 中间加塞「CBL 导出」：把本册（当下整个文件）全部棋局导出成纯 CBL 棋库，
        //   不带 ZIP/RAR 外壳，别的象棋软件可直接打开。
        tool.addView(toolBtn("CBL 导出", 1f) { exportBookCbl(book, games) })
        tool.addView(toolBtn("关闭", 1f) { dismissSilently() })
        root.addView(tool)

        // ★ 翻页条：内置宝库恒显（即便只有一页，也要有「隐藏」入口，才能翻出已隐藏的清单）；
        //   上千局时底部再来一条，看完一页就地翻，不必滚回顶部。
        val showPager = pageCount > 1 || hideable
        if (showPager) root.addView(pagerRow(catIndex, book, games, page, pageCount, atBottom = false, hideable = hideable, hiddenCount = hidden.size))

        // 棋则列表（只渲染当前这一页）
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (k in from until to) {
            val index = visibleIdx[k]
            list.addView(gameRow(catName, book, index, games[index], hideable))
        }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)

        if (showPager) root.addView(pagerRow(catIndex, book, games, page, pageCount, atBottom = true, hideable = hideable, hiddenCount = hidden.size))

        show(d)
    }

    /** 上一页 / 隐藏 / 页码 / 下一页。 */
    private fun pagerRow(
        catIndex: Int, book: QipuBook, games: List<QipuGame>,
        page: Int, pageCount: Int, atBottom: Boolean,
        hideable: Boolean, hiddenCount: Int
    ): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, if (atBottom) 6 else 0, 0, if (atBottom) 2 else 6)
        }
        fun mk(label: String, target: Int, enabled: Boolean) = Button(context).apply {
            text = label; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(if (enabled) onDark else textSub)
            setBackgroundColor(if (enabled) dark else panel)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 38.dp(), 1f)
            isEnabled = enabled
            if (enabled) setOnClickListener {
                detailPage = target
                showBookDetail(catIndex, book, games)
            }
        }
        bar.addView(mk("◀ 上一页", page - 1, page > 0))
        // ★ 「隐藏」入口：点开列出本书已被隐藏的棋则，可逐条取消隐藏。
        if (hideable) {
            bar.addView(Button(context).apply {
                text = if (hiddenCount > 0) "隐藏($hiddenCount)" else "隐藏"
                textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(onDark); setBackgroundColor(dark)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, 38.dp(), 1f)
                setOnClickListener { openHiddenList(catIndex, book, games) }
            })
        }
        bar.addView(TextView(context).apply {
            // ★ 页码格本身可点：直接手输目标页跳转（几千页的书一页页翻太熬人）。
            text = "第 ${page + 1} / $pageCount 页"
            setTextColor(goldText); textSize = 13f; gravity = Gravity.CENTER
            setBackgroundColor(0x1E1A140E.toInt())
            layoutParams = LinearLayout.LayoutParams(0, 38.dp(), 1f)
            isClickable = true
            setOnClickListener { promptJumpPage(catIndex, book, games, page, pageCount) }
        })
        bar.addView(mk("下一页 ▶", page + 1, page < pageCount - 1))
        return bar
    }

    /**
     * ★ 手输页码跳转：几千页的大书靠「下一页」翻到中间实在熬人，点页码格直接跳。
     *   输入超出范围就地夹到 1 与 pageCount 之间，不留“跳不动”的死角。
     */
    private fun promptJumpPage(
        catIndex: Int, book: QipuBook, games: List<QipuGame>, page: Int, pageCount: Int
    ) {
        val input = EditText(context).apply {
            setText("${page + 1}"); textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelectAllOnFocus(true)
        }
        val dd = Dialog(context)
        dd.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        v.addView(TextView(context).apply {
            text = "跳到第几页（1 - $pageCount）"; setTextColor(goldText); textSize = 16f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8.dp())
        })
        v.addView(input)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 0)
        }
        fun jump(target: Int) {
            detailPage = target.coerceIn(0, pageCount - 1)
            dd.dismiss()
            showBookDetail(catIndex, book, games)
        }
        row.addView(Button(context).apply {
            text = "取消"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dd.dismiss() }
        })
        row.addView(Button(context).apply {
            text = "跳转"; textSize = 14f; setTextColor(dark); setBackgroundColor(gold)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener {
                val n = input.text.toString().trim().toIntOrNull()
                if (n == null) toast("请输入 1 - $pageCount 之间的页码") else jump(n - 1)
            }
        })
        v.addView(row)
        dd.setContentView(v)
        dd.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dd.show()
    }

    /** 一则棋的列表行：序号 + 标题，已来过则下面标 yyyy-MM-dd HH:mm:ss；右侧「标」「隐」。 */
    private fun gameRow(catName: String, book: QipuBook, index: Int, game: QipuGame, hideable: Boolean): View {
        val refTag = GameJournal.GameRef(catName, book.name, book.file, index, game.title())
        val rec = GameJournal.getRecord(context, refTag)
        val visited = rec.lastOpen > 0

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 8, 10.dp(), 8)
            setBackgroundColor(if (visited) 0x2E4A3820.toInt() else 0x1E1A140E.toInt())
            setOnClickListener { openGame(catName, book, index, game) }
        }
        // 第一行：序号 + 标题
        val line1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(TextView(context).apply {
            text = "${index + 1}."
            setTextColor(goldText); textSize = 14f; setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 8.dp(), 0)
        })
        line1.addView(TextView(context).apply {
            // ★ 标题里的人物/布局/残局等专名自动加下划线，点一下跳百度搜索
            // ★ 正文先显式钉死 rowText（黑字，炭黑深底才浅字）——否则非链接部分会落到控件默认白字上；
            //   百度专名蓝链接由 span 覆盖，仍保持蓝色。
            setTextColor(rowText)
            BaiduTerm.bind(this, game.title().ifBlank { "第 ${index + 1} 则" }, rowText, linkColor)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 已来过 → 标时间（未去过不显示）
        if (visited) {
            line1.addView(TextView(context).apply {
                text = "已来过"
                setTextColor(goldText); textSize = 11f; setPadding(6.dp(), 0, 4.dp(), 0)
            })
        }
        // ★ 本则「印象」快捷按钮：点开编辑器选阅读程度/标签/备注并保存（点整行仍开谱）
        val hasImp = rec.reading.isNotBlank() || rec.tags.isNotEmpty()
        line1.addView(Button(context).apply {
            text = if (hasImp) "✎标" else "标"
            textSize = 11f; setTextColor(gold)
            setBackgroundColor(if (hasImp) 0xFF3A2C1E.toInt() else dark)
            setTypeface(null, Typeface.BOLD)
            setPadding(12.dp(), 4, 12.dp(), 4)
            setOnClickListener {
                openGameImpression(context, refTag, game.title())
            }
        })
        // ★ 本则「加入合成区」：只记引用（书文件+序号），原文件不动；导出时再解析。
        line1.addView(Button(context).apply {
            text = "→合成"
            textSize = 11f; setTextColor(gold); setBackgroundColor(dark)
            setTypeface(null, Typeface.BOLD)
            setPadding(10.dp(), 4, 10.dp(), 4)
            setOnClickListener {
                try {
                    addToSynth(
                        CblSynth.Item(
                            name = game.title().ifBlank { "第 ${index + 1} 则" },
                            kind = CblSynth.KIND_BOOK, cat = catName,
                            bookName = book.name, file = book.file, index = index,
                            isUserUpload = book.isUserUpload, isLocalDoc = book.isLocalDoc
                        )
                    )
                } catch (e: Exception) { e.printStackTrace() }
            }
        })
        // ★ 本则「隐藏」：点了就真隐藏（整行从目录消失），只记本机、绝不动原 CBL 文件；
        //   取消隐藏走翻页条上的「隐藏」清单。
        if (hideable) {
            line1.addView(Button(context).apply {
                text = "隐"
                textSize = 11f; setTextColor(gold); setBackgroundColor(dark)
                setTypeface(null, Typeface.BOLD)
                setPadding(12.dp(), 4, 12.dp(), 4)
                setOnClickListener {
                    try {
                        GameJournal.setHidden(context, refTag, true)
                        toast("已隐藏")
                        // ★ 节流重建：连点隐藏时只重建一次列表，避免大书被反复重建拖死。
                        scheduleDetailRefresh(detailCatIndex, book, detailGames)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            })
        }
        row.addView(line1)

        if (visited) {
            row.addView(TextView(context).apply {
                text = dfFull.format(java.util.Date(rec.lastOpen))
                setTextColor(textSub); textSize = 11f; setPadding(0, 2, 0, 0)
            })
        }
        return row
    }

    private fun openGame(catName: String, book: QipuBook, index: Int, game: QipuGame) {
        // 落一次打开记录（年月日 时分秒 已来过）
        GameJournal.touch(context,
            GameJournal.GameRef(catName, book.name, book.file, index, game.title()), "棋谱库")
        dismissSilently()
        onOpenGame(catName, book, index, detailGames.ifEmpty { listOf(game) })
    }

    /** ★ 打开本局「印象」编辑器：选阅读程度/印象标签/写备注/书签，保存后刷新当前目录列表。 */
    private fun openGameImpression(c: Context, ref: GameJournal.GameRef, title: String) {
        try {
            val rec = GameJournal.getRecord(c, ref)
            GameJournal.openEditImpressionDialog(
                c, title.ifBlank { "第 ${ref.index + 1} 则" },
                rec.reading, rec.tags, rec.note, rec.bookmark
            ) { reading, tags, note, bm ->
                val r = GameJournal.getRecord(c, ref)
                r.reading = reading
                r.tags.clear(); r.tags.addAll(tags)
                r.note = note
                r.bookmark = bm
                GameJournal.saveRecord(c, r)
                toast("已保存《$title》的阅读印象")
                // 刷新当前目录列表，让「已标」标记立即反映出来
                detailBook?.let { showBookDetail(detailCatIndex, it, detailGames) }
            }?.show()
        } catch (e: Exception) {
            toast("印象操作失败: ${e.message}")
        }
    }

    /**
     * ★ 隐藏清单：列出本书所有已隐藏的棋则，逐条「取消隐藏」。
     *   隐藏/取消隐藏只记本机（GameJournal），原 CBL 文件只读、不受任何影响。
     */
    private fun openHiddenList(catIndex: Int, book: QipuBook, games: List<QipuGame>) {
        val catName = cats.getOrNull(catIndex)?.name ?: ""
        val hidden = GameJournal.hiddenIndices(context, book.file).sorted()
        val (d, root) = newShell()
        val titleTv = titleView("《${book.name}》· 已隐藏 ${hidden.size} 则")
        root.addView(titleTv)
        root.addView(subTitle("点「取消隐藏」把该则放回目录；隐藏只记在本机，原棋谱文件不受任何影响。"))
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // ★ 剩余计数：取消隐藏后即时更新标题，不必整表重建
        var remain = hidden.size
        val emptyHint = TextView(context).apply {
            text = "（本书暂无隐藏的棋则）"
            setTextColor(textSub); textSize = 14f; setPadding(8.dp(), 12.dp(), 0, 0)
        }
        if (hidden.isEmpty()) {
            list.addView(emptyHint)
        } else {
            for (index in hidden) {
                val title = games.getOrNull(index)?.title()?.ifBlank { null } ?: "第 ${index + 1} 则"
                val ref = GameJournal.GameRef(catName, book.name, book.file, index, title)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8.dp(), 6, 0, 6)
                }
                row.addView(TextView(context).apply {
                    text = "${index + 1}. $title"
                    setTextColor(rowText); textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(context).apply {
                    text = "取消隐藏"
                    textSize = 12f; setTextColor(onDark); setBackgroundColor(dark)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(12.dp(), 4, 12.dp(), 4)
                    setOnClickListener {
                        try {
                            GameJournal.setHidden(context, ref, false)
                            toast("已取消隐藏")
                            // ★ 只把这一行从清单里摘掉、并更新计数：不再整表重建，
                            //   连点取消也不卡（清单很长时尤其明显）。
                            remain--
                            titleTv.text = "《${book.name}》· 已隐藏 $remain 则"
                            list.removeView(row)
                            if (remain <= 0) list.addView(emptyHint)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                })
                list.addView(row)
            }
        }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)
        // 底部：返回目录（重渲染，立刻看到取消隐藏后的结果）+ 关闭
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER; setPadding(0, 8, 0, 2)
        }
        bar.addView(Button(context).apply {
            text = "← 返回目录"; textSize = 14f; setTextColor(dark); setBackgroundColor(gold)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { showBookDetail(catIndex, book, games) }
        })
        bar.addView(Button(context).apply {
            text = "关闭"; textSize = 14f; setTextColor(textMain); setBackgroundColor(panel)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setOnClickListener { dismissSilently() }
        })
        root.addView(bar)
        show(d)
    }

    // ════════════════════════ 装饰与工具 ════════════════════════

    private fun decorStrip(leftOrRight: Boolean): View {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(14.dp(), ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(0, 4, 0, 4)
            setBackgroundColor((0x40 shl 24) or (gold and 0x00FFFFFF))
        }
        val faintGold = (gold and 0x00FFFFFF) or (0x40 shl 24)
        val glyphs = if (leftOrRight) arrayOf("龍", "鳳", "☰", "鳳", "龍", "鳳")
                     else arrayOf("鳳", "龍", "☰", "龍", "鳳", "龍")
        for (g in glyphs) {
            strip.addView(TextView(context).apply {
                text = g; setTextColor(faintGold); textSize = 10f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
        }
        return strip
    }

    private fun formatSize(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> "%.1fM".format(bytes / 1024f / 1024f)
        bytes >= 1024 -> "%dK".format(bytes / 1024)
        else -> "${bytes}B"
    }

    private fun show(d: Dialog) {
        try { d.show() } catch (e: Exception) { toast("显示失败: ${e.message}"); return }
        // ★ 宝库是全屏弹窗（独立的 Window，不继承主界面的沉浸式设置）。若不在此落一次，
        //   弹窗一开系统导航三键就"原形毕露"，关窗回主界面还赖着不走。此处与主界面保持一致的沉浸式：
        //   隐藏状态栏/导航栏，IMMERSIVE_STICKY 让临时唤出的键自动再收起。
        try {
            d.window?.decorView?.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        } catch (_: Exception) { }
        dialog = d
    }

    private fun dismissSilently() {
        // ★ 顺手取消尚未执行的重建任务：避免"点了隐藏又马上关闭"时弹窗被重新拉起。
        pendingDetailRefresh?.let { mainHandler.removeCallbacks(it) }
        pendingDetailRefresh = null
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun Int.dp(): Int {
        val density = context.resources.displayMetrics.density
        return (this * density + 0.5f).toInt()
    }
}