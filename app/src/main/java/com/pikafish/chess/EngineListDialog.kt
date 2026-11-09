package com.pikafish.chess

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pikafish.chess.engine.EngineIdentity
import com.pikafish.chess.engine.UserEngineStore

/**
 * ★★★ 引擎列表对话框 ★★★
 *
 * 一竖排是引擎名称，一竖排是「采用」，一竖排是「测试（红/黑）」，一竖排是「成绩」。
 *
 *   名称：内置默认排第一，名字后面缀「（默认）」；当前采用的那台缀「（当前采用）」。
 *   采用：勾上＝全局使用这台引擎（主界面·解惑·分析·复盘 一并生效）。采用是全局的，故其
 *         适用范围只在「抬头」一句话里说明，不再每台引擎都摊一排小框，省下大片空间。
 *   测试：勾「红」/「黑」，挑两台【不同】引擎做全自动 PK（红走红引擎、黑走黑引擎）。
 *   成绩：本引擎与各家交手记录（胜X n、负X n、和X n），没成绩的不写。
 *         行高统一预留【4 行】（以最啰嗦的详情为准）；对手超过 4 家时，最后一格变成
 *         「全部」热链接，点开看完整详情。
 *
 * 采用 与 测试 互斥：「后勾的那个才算数」——勾了采用会清掉测试勾选，勾了测试会清掉
 * 采用勾选。点「确定」时以最后一次勾选为准；测试若只勾了一方 / 红黑勾成同一台，
 * 会当场提示，不放过半个不规范的确认。
 *
 * 长按某台外置引擎的名字 → 删除该引擎；长按标题 → 清空全部成绩。
 */
class EngineListDialog(
    private val activity: AppCompatActivity,
    private val onAdopt: (UserEngineStore.Entry) -> Unit,
    private val onStartPk: (UserEngineStore.Entry, UserEngineStore.Entry) -> Unit,
    /** 确定钮文案：常规「确定」；PK 挡屏里重选时用「按已选好的继续」 */
    private val confirmLabel: String = "确定"
) {

    private lateinit var dialog: AlertDialog
    private lateinit var container: LinearLayout

    private var adoptId: String = ""
    private var testRedId: String = ""
    private var testBlackId: String = ""

    private val nameColor = Color.parseColor("#2B2118")
    private val subColor = Color.parseColor("#7A6A55")
    private val winColor = Color.parseColor("#2E6B4F")
    private val linkColor = Color.parseColor("#1565C0")   // 「全部」热链接

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun show() {
        adoptId = UserEngineStore.activeId(activity)
        testRedId = ""
        testBlackId = ""

        container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        val scroll = ScrollView(activity).apply {
            addView(container)
            // ★ 大字撑高了内容 → 做成滚屏，滚动条常显，方便上下翻
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("引擎列表")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmLabel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onConfirm() }
            // 长按标题 → 清空全部成绩
            dialog.findViewById<TextView>(android.R.id.title)?.setOnLongClickListener {
                UserEngineStore.clearResults(activity)
                Toast.makeText(activity, "已清空全部引擎成绩", Toast.LENGTH_SHORT).show()
                rebuild()
                true
            }
        }
        rebuild()
        dialog.show()
        // ★ 宽度弹开一点：用满约 96% 屏宽（左右只留一丝边），别窄窄的一条
        try {
            val dm = activity.resources.displayMetrics
            dialog.window?.setLayout((dm.widthPixels * 0.96).toInt(), (dm.heightPixels * 0.72).toInt())
        } catch (_: Exception) {}
    }

    // ══════════════ 视图重建 ══════════════

    private fun rebuild() {
        container.removeAllViews()
        container.addView(hintText(
            "「采用」＝全局启用这台引擎：主界面·解惑·分析·复盘 一并生效；" +
                "「测试」＝勾红/黑两台【不同】引擎做全自动 PK。两者互斥，后勾的才算数。\n" +
                "长按引擎名可删除外置引擎，长按标题可清空成绩。"
        ))
        container.addView(headerRow())
        for (e in UserEngineStore.list(activity)) {
            container.addView(engineRow(e))
        }
    }

    private fun hintText(s: String): TextView = TextView(activity).apply {
        text = s
        textSize = 15f
        setTextColor(subColor)
        setPadding(0, dp(8), 0, dp(6))
    }

    private fun headerRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(6))
        }
        // ★ 列宽按需分配：名称/成绩放宽，采用/测试收窄（采用只一个框、测试顶多「框+红/黑」三字）
        row.addView(headerCell("引擎名称", 2.6f))
        row.addView(headerCell("采用", 1.2f))
        row.addView(headerCell("测试", 1.0f))
        row.addView(headerCell("成绩", 2.6f))
        return row
    }

    private fun headerCell(s: String, weight: Float): TextView = TextView(activity).apply {
        text = s
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(nameColor)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
    }

    private fun engineRow(e: UserEngineStore.Entry): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, dp(7))
        }
        row.addView(nameCell(e))
        row.addView(adoptCell(e))
        row.addView(testCell(e))
        row.addView(resultCell(e))
        return row
    }

    private fun cell(weight: Float): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
    }

    private fun nameCell(e: UserEngineStore.Entry): View = cell(2.6f).apply {
        val adopted = adoptId == e.id
        addView(TextView(activity).apply {
            val suffix = buildString {
                if (e.isBuiltin) append("（默认）")
                if (adopted) append("（当前采用）")
            }
            text = e.displayName + suffix
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (adopted) winColor else nameColor)
        })
        val sub = if (e.isBuiltin) "内置官方" else {
            "外置 · " + EngineIdentity.humanSize(e.engineSize) +
                if (e.weightSize > 0) " + 权重" + EngineIdentity.humanSize(e.weightSize) else ""
        }
        addView(TextView(activity).apply {
            text = sub
            textSize = 15f
            setTextColor(subColor)
        })
        if (!e.isBuiltin) {
            // 长按名字删除这台外置引擎
            setOnLongClickListener {
                confirmRemove(e)
                true
            }
        }
    }

    private fun adoptCell(e: UserEngineStore.Entry): View = cell(1.2f).apply {
        // ★ 采用＝全局启用（适用范围已在抬头说明），这里只留一个框，列窄、不挤
        addView(CheckBox(activity).apply {
            text = "采用"
            textSize = 15f
            setTextColor(if (adoptId == e.id) winColor else nameColor)
            isChecked = adoptId == e.id
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    adoptId = e.id
                    // 采用一确认，测试作废（两个片区分明，最后勾的算数）
                    testRedId = ""
                    testBlackId = ""
                } else if (adoptId == e.id) {
                    adoptId = ""
                }
                rebuild()
            }
        })
    }

    private fun testCell(e: UserEngineStore.Entry): View = cell(1.0f).apply {
        val cbRed = CheckBox(activity).apply {
            text = "红"
            textSize = 15f
            setTextColor(nameColor)
            isChecked = testRedId == e.id
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    testRedId = e.id
                    if (testBlackId == e.id) testBlackId = ""   // 同一台不能同时当红又当黑
                    adoptId = ""                                 // 测一确认，采用作废
                } else if (testRedId == e.id) {
                    testRedId = ""
                }
                rebuild()
            }
        }
        val cbBlack = CheckBox(activity).apply {
            text = "黑"
            textSize = 15f
            setTextColor(nameColor)
            isChecked = testBlackId == e.id
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    testBlackId = e.id
                    if (testRedId == e.id) testRedId = ""
                    adoptId = ""
                } else if (testBlackId == e.id) {
                    testBlackId = ""
                }
                rebuild()
            }
        }
        addView(cbRed)
        addView(cbBlack)
    }

    /** 成绩列：统一预留 4 行高（以最啰嗦的详情为准）；对手超过 4 家时最后一格换「全部」热链接。 */
    private fun resultCell(e: UserEngineStore.Entry): View = cell(2.6f).apply {
        val lines = UserEngineStore.resultLines(activity, e.id)
        if (lines.isEmpty()) {
            addView(TextView(activity).apply {
                text = "—"
                textSize = 15f
                setTextColor(subColor)
                minLines = 4                     // ★ 预留 4 行：行高统一，不参差
            })
        } else {
            val more = lines.size > 4            // 超过 4 家 → 只列前 3，第 4 行给「全部」
            addView(TextView(activity).apply {
                text = (if (more) lines.take(3) else lines).joinToString("\n")
                textSize = 15f
                setTextColor(winColor)
                minLines = if (more) 3 else 4
            })
            if (more) {
                addView(TextView(activity).apply {
                    text = "全部 ▸"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(linkColor)
                    setPadding(0, dp(2), 0, 0)
                    setOnClickListener { showResultDetail(e, lines) }
                })
            }
        }
    }

    /** 「全部」热链接 → 跳出弹窗：该引擎与各家 PK 的完整成绩详情。 */
    private fun showResultDetail(e: UserEngineStore.Entry, lines: List<String>) {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        body.addView(TextView(activity).apply {
            text = "「${e.displayName}」共与 ${lines.size} 台引擎交手："
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(nameColor)
            setPadding(0, 0, 0, dp(6))
        })
        for (l in lines) {
            body.addView(TextView(activity).apply {
                text = "· $l"
                textSize = 15f
                setTextColor(winColor)
                setPadding(0, dp(3), 0, dp(3))
            })
        }
        val d = AlertDialog.Builder(activity)
            .setTitle("引擎成绩详情")
            .setView(ScrollView(activity).apply {
                addView(body)
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
            })
            .setPositiveButton("关闭", null)
            .create()
        d.show()
        try {
            val dm = activity.resources.displayMetrics
            d.window?.setLayout((dm.widthPixels * 0.96).toInt(), (dm.heightPixels * 0.72).toInt())
        } catch (_: Exception) {}
    }

    // ══════════════ 确认 / 删除 ══════════════

    private fun onConfirm() {
        val hasTest = testRedId.isNotBlank() || testBlackId.isNotBlank()
        if (hasTest) {
            val red = UserEngineStore.entry(activity, testRedId)
            val black = UserEngineStore.entry(activity, testBlackId)
            when {
                red == null && black == null ->
                    toast("请为红黑双方各选一台引擎：红方引擎 / 黑方引擎")
                red == null ->
                    toast("红方引擎还没选，请再选一台红方引擎")
                black == null ->
                    toast("黑方引擎还没选，请再选一台黑方引擎")
                red.id == black.id ->
                    toast("红黑必须是两台不同的引擎，请再挑一台")
                else -> {
                    dialog.dismiss()
                    onStartPk(red, black)
                }
            }
            return
        }
        if (adoptId.isNotBlank()) {
            val e = UserEngineStore.entry(activity, adoptId)
            dialog.dismiss()
            if (e != null) onAdopt(e)
            return
        }
        dialog.dismiss()
    }

    private fun confirmRemove(e: UserEngineStore.Entry) {
        AlertDialog.Builder(activity)
            .setTitle("删除引擎")
            .setMessage("删除外置引擎「${e.displayName}」？\n删除后其文件一并清除，已记的成績保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                UserEngineStore.remove(activity, e.id)
                if (adoptId == e.id) adoptId = ""
                if (testRedId == e.id) testRedId = ""
                if (testBlackId == e.id) testBlackId = ""
                Toast.makeText(activity, "已删除「${e.displayName}」", Toast.LENGTH_SHORT).show()
                rebuild()
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()
}