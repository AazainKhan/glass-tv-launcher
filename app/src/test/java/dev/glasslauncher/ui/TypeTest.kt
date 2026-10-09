package dev.glasslauncher.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** The smallest text is still readable from a sofa (audit 9.3: captions and overlines were 11.5 sp). */
class TypeTest {
    @Test fun nothingIsSmallerThanThirteenSp() {
        val styles = mapOf("caption" to Type.caption, "overline" to Type.overline, "secondary" to Type.secondary, "label" to Type.label, "body" to Type.body)
        styles.forEach { (name, s) -> assertTrue("$name is ${s.fontSize}", s.fontSize.value >= 13f) }
    }
}
