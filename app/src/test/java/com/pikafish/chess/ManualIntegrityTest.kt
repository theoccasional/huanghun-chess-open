package com.pikafish.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 《清新人生手册》完整性校验。
 *
 * <p>这不是普通的“文件存在性”测试。手册的正片（`app/src/main/assets/manual/qingxin.html`）
 * 被 **SHA-256 逐字节锁定**：删一个字、换一段内容、塞一堆垃圾再把标题补回去，
 * 哈希都会变，本测试立刻变红。</p>
 *
 * <p>为什么用哈希而不是“字数 + 关键词”：关键词可以用一行字糊过去，
 * 字数也可以拿垃圾文本凑满。只有整版哈希能证明「这一版一个字没动」。</p>
 *
 * <p>若作者本人发布了新版本，请同步更新本文件里的 [MANUAL_SHA256]，
 * 并在提交说明中注明“手册换版”。除此之外的任何改动都应视为破坏。</p>
 */
class ManualIntegrityTest {

    private companion object {
        /** 2.9.308 版《清新人生手册》正片的 SHA-256。 */
        const val MANUAL_SHA256 = "80b754a069f66669e123a8d8b2b1450beb78b9d2925c50833271b3fc84e7a6f6"

        /** 正文字数下限（当前约 24.8 万字）。作为哈希之外的第二道保险。 */
        const val MIN_CHARS = 200_000

        /** 作者与书名，防止把整份文件换成别的东西。 */
        const val TITLE = "清新人生手册"
        const val AUTHOR = "汉军威武"
    }

    /** 兼容从模块目录（app/）或仓库根目录运行。 */
    private fun resolve(relative: String): File {
        val candidates = listOf(
            File("src/main/assets/manual/$relative"),
            File("app/src/main/assets/manual/$relative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: File("app/src/main/assets/manual/$relative")
    }

    @Test
    fun manualIsByteForByteIntact() {
        val html = resolve("qingxin.html")
        assertTrue(
            "《清新人生手册》正片丢失：app/src/main/assets/manual/qingxin.html 不存在。\n" +
                "这是本项目不可分割的精神内核，任何 fork / 重构 / 版本迭代都必须原样保留。",
            html.exists()
        )

        val digest = MessageDigest.getInstance("SHA-256").digest(html.readBytes())
        val actual = digest.joinToString("") { "%02x".format(it) }

        assertEquals(
            "《清新人生手册》正片被改动过。\n" +
                "本文件逐字节锁定，不接受任何删改（包括“保留标题、替换正文”这类做法）。\n" +
                "确属作者发布新版本时，才更新 ManualIntegrityTest 里的 MANUAL_SHA256，并在提交说明中注明。",
            MANUAL_SHA256,
            actual
        )
    }

    @Test
    fun manualStillReadableAndComplete() {
        val html = resolve("qingxin.html")
        assertTrue("《清新人生手册》正片丢失", html.exists())

        val text = html.readText()
        assertTrue("正文不含书名《$TITLE》", text.contains(TITLE))
        assertTrue("正文不含作者名「$AUTHOR」", text.contains(AUTHOR))

        val plain = text
            .substringAfter("<body", text)
            .replace(Regex("<script[\\s\\S]*?</script>"), "")
            .replace(Regex("<style[\\s\\S]*?</style>"), "")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s+"), "")
        assertTrue(
            "《清新人生手册》正文只剩 ${plain.length} 字，低于 $MIN_CHARS 字的下限——内容被大幅删减。",
            plain.length >= MIN_CHARS
        )
    }

    /** 手册入口不能被摘掉：主界面要有入口，阅读器要能加载这份正文。 */
    @Test
    fun manualEntryPointStillWired() {
        val reader = File("src/main/java/com/pikafish/chess/ui/ReaderActivity.kt").let {
            if (it.exists()) it else File("app/src/main/java/com/pikafish/chess/ui/ReaderActivity.kt")
        }
        assertTrue("阅读器 ReaderActivity.kt 丢失", reader.exists())
        assertTrue(
            "阅读器不再加载 qingxin.html，手册入口已断",
            reader.readText().contains("qingxin.html")
        )

        val main = File("src/main/java/com/pikafish/chess/MainActivity.kt").let {
            if (it.exists()) it else File("app/src/main/java/com/pikafish/chess/MainActivity.kt")
        }
        assertTrue("主界面 MainActivity.kt 丢失", main.exists())
        val mainText = main.readText()
        assertTrue(
            "主界面不再提供「清新人生」入口（未找到 ReaderActivity 的调用）",
            mainText.contains("ReaderActivity")
        )
        assertTrue(
            "主界面「清新人生」按钮不见了",
            mainText.contains("FreshLife") || mainText.contains("清新人生")
        )
    }

    /** 根目录说明文件也在，防止只留正片、把话抹掉。 */
    @Test
    fun readmeStatementStillPresent() {
        // 从当前目录逐级向上找：无论从 app/ 还是仓库根目录跑测试都能找到。
        fun findUp(name: String): File? {
            var dir: File? = File(".").absoluteFile
            repeat(4) {
                val candidate = File(dir, name)
                if (candidate.exists()) return candidate
                dir = dir?.parentFile
            }
            return null
        }

        val readme = findUp("README.md")
        assertTrue("README.md 丢失", readme != null)
        val text = readme!!.readText()
        assertTrue("README 顶部的维护者须知被删除", text.contains("维护者须知"))
        assertTrue("README 不再指向《清新人生手册》", text.contains(TITLE))
        assertTrue("README 不再写明「不得删改」", text.contains("不得删改"))

        val notice = findUp("清新人生手册.md")
        assertTrue("根目录的 清新人生手册.md 丢失", notice != null)
        val noticeText = notice!!.readText()
        assertTrue("说明文件里的维护者须知被删除", noticeText.contains("维护者须知"))
        assertTrue("说明文件不再声明「不得删改」", noticeText.contains("不得删改"))
    }
}
