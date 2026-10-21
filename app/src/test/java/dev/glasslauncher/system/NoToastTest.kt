package dev.glasslauncher.system

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P234: Glass never puts up Android's own unstyled pop-ups: no Toast, no AlertDialog or other system dialog. Notices are the top-left glass
 * card (NoticeHost); true confirmations are Glass's own centred confirm card. A source scan, so a new raw dialog fails the build.
 */
class NoToastTest {
    private val forbidden = listOf(
        "\\bToast\\b", "\\bAlertDialog\\b", "android\\.app\\.Dialog\\b", "\\bDialogFragment\\b", "\\bProgressDialog\\b", "MaterialAlertDialog",
    ).map(::Regex)

    @Test fun noToastOrSystemDialogRemainsInTheApp() {
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val hits = src.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                // KDoc and comments may mention them; code may not.
                if (code.startsWith("*") || code.startsWith("/*")) null
                else forbidden.firstOrNull { it.containsMatchIn(code) }?.let { "${f.path}:${i + 1}: ${it.pattern}" }
            }
        }.toList()
        assertTrue("A raw Toast or system dialog is back:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
