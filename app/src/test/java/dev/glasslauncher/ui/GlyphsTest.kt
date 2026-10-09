package dev.glasslauncher.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Symbols come from one icon set (vector drawables), never from text glyphs that each font draws its own way. */
class GlyphsTest {
    @Test fun noTextGlyphsStandInForIcons() {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val glyph = Regex("\"[^\"\\n]*[▶◀▲▼][^\"\\n]*\"|Text\\(\"i\"")
        val hits = src.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (glyph.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null }
        }.toList()
        assertTrue("text glyphs used as icons:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
