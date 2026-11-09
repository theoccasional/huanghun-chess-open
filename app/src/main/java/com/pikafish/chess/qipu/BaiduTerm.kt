package com.pikafish.chess.qipu

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import android.text.method.LinkMovementMethod

/**
 * 百度热链接引擎 —— 把棋谱里的人物/布局/残局定式/中局术语/布局疑形 等"专名"识别出来，
 * 统一画下划线并变成可点击的链接：点一下即跳到百度搜索这个词，省得别人再去百度敲一遍。
 *
 * 用法：
 *   val sp = BaiduTerm.linkify("2018年碧桂园杯 王天一 胜 郑惟桐", baseColor, linkColor)
 *   tv.text = sp; tv.movementMethod = LinkMovementMethod.getInstance()
 *
 * 说明：
 *   - 识别采用"最长匹配"：在文本每个位置，若多个词典词都能命中，取最长者打链接（避免“炮”
 *     拆进“中炮”/“马前炮”这类误连）；已命中的区域跳过，保证互不重叠。
 *   - 词典分 人名 / 布局·开局 / 残局定式·排局 / 中局术语 / 布局疑形 几类，均含当代与经典。
 *   - 只做本地文本加链接，不含任何网络抓取；点链接由系统浏览器打开百度搜索。
 */
object BaiduTerm {

    // ════════════════ 词典（按用途分组，最终合并成一个按长度降序的列表）════════════════

    /** 当代名手 + 老一辈·特级大师 人名棋手 */
    private val PERSONS = arrayOf(
        // —— 当代活跃/当打（在现代数据源中确有对局记录）——
        "王天一", "郑惟桐", "蒋川", "赵鑫鑫", "汪洋", "洪智", "谢靖", "孟辰", "唐丹",
        "王廓", "孙勇征", "徐超", "徐崇峰", "黄竹风", "李少庚", "赵金成", "曹岩磊",
        "许银川", "吕钦", "赵国荣", "胡荣华", "杨官璘", "李来群", "柳大华", "徐天红",
        "于幼华", "陶汉明", "王嘉良", "李义庭", "谢业枧", "庄玉庭", "郑一泓", "金波",
        "申鹏", "程鸣", "陈泓盛", "郝继超", "陆伟韬", "王斌", "万春林", "张强",
        "阎文清", "吴贵临", "苗永鹏", "卜凤波", "欧阳琦琳", "党国蕾", "陈幸琳",
        "王琳娜", "陈丽淳", "赵冠芳", "左文静", "尤颖钦", "郭莉萍", "金海英", "谢思明",
        // —— 近代·民国/古谱 名手 ——
        "谢侠逊", "周德裕", "黄松轩", "冯敬如", "彭述圣", "钟珍", "曾展鸿", "李庆全",
        "林弈仙", "王浩然", "杨全忠", "邵次明", "张德魁", "那健庭", "谢小然", "徐健秒",
        // —— 名词性棋手称谓不做热链（避免误伤），但保留这些常用表身份词 ——
        "特级大师"
    )

    /** 布局 / 开局 体系与定式名 */
    private val OPENINGS = arrayOf(
        "五七炮", "五八炮", "五六炮", "五九炮", "中炮", "当头炮", "顺手炮", "顺炮",
        "列手炮", "列炮", "屏风马", "屈头屏风马", "反宫马", "单提马", "当头马", "拐角马",
        "过宫炮", "士角炮", "仙人指路", "进兵局", "飞相局", "起马局", "飞相", "过宫炮直车",
        "左中炮", "中炮过河车", "中炮进三兵", "中炮进七兵", "中炮七路马", "五七炮对屏风马",
        "五八炮互进三兵", "中炮巡河炮", "巡河炮", "左马盘河", "盘河马", "平炮兑车",
        "左直车", "右直车", "右横车", "后补列炮", "列手横车", "弃马局", "缓开车", "顺炮直车",
        "顺炮横车", "中炮横车", "窝心马", "窝心炮", "兑卒", "当头炮横车" , "金钩炮", "龟背炮",
        "敛炮", "兵底炮", "贴身车", "急性子开局"
    )

    /** 残局定式 / 排局 / 杀法名 残局 */
    private val ENDGAMES = arrayOf(
        "七星聚会", "蚯蚓降龙", "野马操田", "千里独行", "带子入朝", "五虎下川", "大征西",
        "小征西", "丹凤朝阳", "火烧连营", "九连环", "三仙炼丹", "海底捞月", "双车错",
        "铁门拴", "铁门栓", "天地炮", "夹车炮", "单马擒单士", "炮高兵", "车马冷着",
        "车炮冷着", "马蹩士角", "柳穿鱼", "燕双飞", "海底针", "火风凰", "鸿门宴",
        "封侯挂印", "二车斗士", "单兵困王", "车兵必胜", "马到成功", "麒麟送子", "晴空霹雳",
        "鹧鸪天", "轩辕出阵", "匹马难还", "高钓马", "挂角马", "卧槽马", "八角马", "钓鱼马",
        "双马饮泉", "马后炮", "白脸将", "闷宫", "闷杀", "自闷", "二鬼拍门", "送佛归殿",
        "独马踩双", "三车闹士", "车攻双士", "困毙", "排局", "江湖排局", "残局杀法"
    )

    /** 中局 战术·技法名 */
    private val MIDDLE = arrayOf(
        "弃子攻杀", "弃马陷车", "弃车保帅", "弃子抢攻", "兑子争先", "运子取势", "先弃后取",
        "顿挫", "腾挪", "阻塞", "拴链", "抽将", "闷宫", "闪击", "牵制", "围困", "穿透",
        "中路突破", "侧翼偷袭", "底线进攻", "正面直入", "车马冷着", "抽将得子", "稳步进取",
        "强子兑尽", "残局战术", "紧逼", "压迫", "闪将", "双重攻击", "拦截", "露帅", "冷着",
        "妙手", "好棋", "疑问手", "缓手", "恶手"
    )

    /** 布局疑形 / 布局陷阱 / 骗着（对应“布局疑形与攻击”一类书里的专名） */
    private val SUSPECT = arrayOf(
        "布局疑形", "布局疑形与攻击", "布局陷阱", "布局骗着", "布局骗着与对策", "疑形",
        "骗着", "冷着", "陷阱", "布局飞刀", "飞刀", "布局新变", "布局陷阱（尚威）", "布局骗着"
    )

    /** 合并后的全词典，按长度降序（长词优先，避免短词把长词拆散）。 */
    private val ALL_TERMS: List<String> = buildList {
        addAll(PERSONS); addAll(OPENINGS); addAll(ENDGAMES); addAll(MIDDLE); addAll(SUSPECT)
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    /**
     * 把文本中识别的专名加上下划线并变成可点击链接。
     *
     * @param text      原始文本（纯文本，例如一则棋标题：事件 + 对局者）。
     * @param baseColor 非链接文字的颜色；null 则用系统默认。
     * @param linkColor 链接(下划线词)的颜色；纯下划线且颜色同正文时观感最正统。
     */
    fun linkify(text: CharSequence, baseColor: Int = Color.WHITE, linkColor: Int = Color.rgb(96, 196, 255)): SpannableString {
        val sp = SpannableString(text)
        val n = text.length
        var i = 0
        while (i < n) {
            // 从当前位置尝试所有词典词，取最长命中
            var best: String? = null
            for (t in ALL_TERMS) {
                val tl = t.length
                if (i + tl > n) continue
                if (text.substring(i, i + tl) == t) {
                    // 取最长匹配
                    best = t
                    break
                }
            }
            if (best != null) {
                val start = i
                val end = i + best.length
                val term = best
                sp.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        openBaidu(widget.context, term)
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = end
            } else {
                i++
            }
        }
        return sp
    }

    /** 数一个文本里命中的专名数量（供界面做“已高亮 N 处”提示）。 */
    fun countTerms(text: CharSequence): Int {
        var c = 0
        var i = 0
        val n = text.length
        while (i < n) {
            var hit = false
            for (t in ALL_TERMS) {
                val tl = t.length
                if (i + tl <= n && text.substring(i, i + tl) == t) {
                    hit = true; c++
                    i += tl
                    break
                }
            }
            if (!hit) i++
        }
        return c
    }

    /** 用系统浏览器打开百度搜索词条。 */
    fun openBaidu(context: Context, term: String) {
        val url = "https://www.baidu.com/s?wd=" + Uri.encode(term)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = intent.resolveActivity(context.packageManager)
            if (resolved != null) context.startActivity(intent)
            else android.widget.Toast.makeText(context, "未找到浏览器打开「$term」", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "打开百度失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 便捷：把 linkify 结果填进一个 TextView，并接好点击与高亮。 */
    fun bind(tv: TextView, text: CharSequence, baseColor: Int, linkColor: Int) {
        tv.text = linkify(text, baseColor, linkColor)
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.highlightColor = Color.TRANSPARENT
    }
}