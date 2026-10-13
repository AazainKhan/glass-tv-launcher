package dev.glasslauncher.addons

import dev.glasslauncher.addon.AddonApi
import dev.glasslauncher.addon.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonRulesTest {
    private fun cand(
        pkg: String = "a.b", permission: String? = AddonApi.PERMISSION, exported: Boolean = true, cert: String = "c1",
        meta: Map<String, String?> = mapOf(AddonApi.META_API to "1", AddonApi.META_MIN_GLASS_API to "1", AddonApi.META_TITLE to "Title"),
    ) = AddonCandidate(pkg, "$pkg.Svc", exported, permission, meta, cert)

    @Test fun discoveryKeepsGuardedExportedServicesWithAnApiLevel() {
        val out = AddonDiscovery.filter(listOf(
            cand("ok"),
            cand("noperm", permission = null),
            cand("wrongperm", permission = "android.permission.BIND_JOB_SERVICE"),
            cand("private", exported = false),
            cand("noapi", meta = mapOf(AddonApi.META_TITLE to "x")),
            cand("badapi", meta = mapOf(AddonApi.META_API to "x")),
        ))
        assertEquals(listOf("ok"), out.map { it.pkg })
        assertEquals("Title", out.single().title)
    }

    @Test fun discoveryReadsMetadataAndFlagsIncompatible() {
        val out = AddonDiscovery.filter(listOf(
            cand("root", meta = mapOf(AddonApi.META_API to "1", AddonApi.META_NEEDS_ROOT to "true")),
            cand("future", meta = mapOf(AddonApi.META_API to "1", AddonApi.META_MIN_GLASS_API to "9")),
        ), glassLevel = 1)
        val root = out.first { it.pkg == "root" }
        assertTrue(root.needsRoot); assertTrue(root.compatible); assertEquals("root", root.title)
        assertFalse(out.first { it.pkg == "future" }.compatible)
    }

    @Test fun addonsStartDisabledAndEnableIsPerCert() {
        var m = emptyMap<String, String>()
        assertFalse(AddonTrust.isEnabled(m, "a.b", "c1"))
        m = AddonTrust.enable(m, "a.b", "c1")
        assertTrue(AddonTrust.isEnabled(m, "a.b", "c1"))
        assertFalse(AddonTrust.isEnabled(m, "a.b", "c2")) // signing cert changed: off again
        assertFalse(AddonTrust.isEnabled(m, "other", "c1"))
        assertFalse(AddonTrust.isEnabled(AddonTrust.disable(m, "a.b"), "a.b", "c1"))
        assertTrue(AddonTrust.isEnabled(AddonTrust.enable(m, "a.b", "c2"), "a.b", "c2")) // the user re-enables
    }

    @Test fun oneCallPerTilePerSecond() {
        var t = 0L
        val p = AddonPolicy({ t })
        assertTrue(p.tryAcquire("a", "t1"))
        t = 999; assertFalse(p.tryAcquire("a", "t1"))
        assertTrue(p.tryAcquire("a", "t2")) // another tile has its own budget
        t = 1_000; assertTrue(p.tryAcquire("a", "t1"))
    }

    @Test fun threeFailuresInARowMuteUntilResume() {
        var t = 0L
        val p = AddonPolicy({ t })
        assertFalse(p.report("a", false)); assertFalse(p.report("a", false))
        assertFalse(p.report("a", true)) // a success resets the count
        assertFalse(p.report("a", false)); assertFalse(p.report("a", false))
        assertTrue(p.report("a", false))
        assertTrue(p.isMuted("a"))
        t = 60_000; assertFalse(p.tryAcquire("a", "t")) // muted add-ons are never called
        assertTrue(p.tryAcquire("b", "t"))
        p.resume("a")
        assertFalse(p.isMuted("a")); assertTrue(p.tryAcquire("a", "t"))
    }

    @Test fun replyIsBoundedBeforeItIsDrawn() {
        val many = (1..20).map { TileState("t$it", "L".repeat(50), "i".repeat(40), value = "v".repeat(40)) }
        val tiles = AddonLimits.sanitize(many + TileState("bad id/with#chars", "x") + TileState("t1", "dup"))
        assertEquals(AddonLimits.MAX_TILES, tiles.size)
        assertEquals(24, tiles.first().label.length); assertEquals(18, tiles.first().value!!.length)
        assertTrue(AddonLimits.sanitize(listOf(TileState("a/b", "x"), TileState("", "x"))).isEmpty())
    }

    @Test fun textKeepsOnlyPrintableCharactersAndNeverSplitsPairs() {
        assertEquals("Pay Now", AddonText.clean("Pay\u202E Now\u200B\u0000", 20)) // bidi override, zero-width, control
        assertEquals("a b", AddonText.clean("a\u00A0\u00A0 b", 20)) // runs of spaces collapse
        assertEquals("", AddonText.clean("\uD800", 5)) // lone surrogate
        assertEquals("\uD83D\uDE00\uD83D\uDE00", AddonText.clean("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", 2)) // cap by code point, pairs whole
        assertEquals("", AddonText.clean(null, 5))
        assertEquals("abc", AddonText.clean("abcdef", 3))
    }

    @Test fun titleAndLabelsAreCleanedAndNeverEmpty() {
        val info = AddonDiscovery.filter(listOf(cand("t", meta = mapOf(AddonApi.META_API to "1", AddonApi.META_TITLE to "\u202EEvil\u200B"))))
        assertEquals("Evil", info.single().title)
        assertEquals("t", AddonDiscovery.filter(listOf(cand("t", meta = mapOf(AddonApi.META_API to "1", AddonApi.META_TITLE to "\u200B")))).single().title)
        assertEquals("id1", AddonLimits.sanitize(TileState("id1", "\u200B")).label)
    }

    @Test fun oneServicePerPackageFirstByNameWins() {
        val out = AddonDiscovery.filter(listOf(cand("p").copy(service = "p.Zed"), cand("p").copy(service = "p.Alpha")))
        assertEquals("p.Alpha", out.single().service)
    }

    @Test fun certChangeIsDetectedAndFingerprintIsShort() {
        val m = mapOf("p" to "abcdef0123,ff")
        assertTrue(AddonTrust.certChanged(m, "p", "other"))
        assertFalse(AddonTrust.certChanged(m, "p", "abcdef0123,ff"))
        assertFalse(AddonTrust.certChanged(m, "never", "x")) // never enabled: nothing to compare
        assertEquals("AB:CD:EF:01", AddonTrust.fingerprint("abcdef0123,ff"))
    }

    @Test fun refreshIsSkippedWithinTtlUnlessTheKeyChanges() {
        var t = 0L
        val g = RefreshGate({ t })
        assertTrue(g.due("k")); g.mark("k")
        t = 29_999; assertFalse(g.due("k"))
        assertTrue(g.due("other")) // enabled set changed
        t = 30_000; assertTrue(g.due("k"))
        g.mark("k"); g.reset(); assertTrue(g.due("k"))
    }

    @Test fun failedReadClearsStaleTilesUnlessMuted() {
        val m = HashMap<String, ShownTile>()
        m.setTiles("a", "A", listOf(TileState("t", "T")))
        m.setTiles("b", "B", listOf(TileState("t", "T")))
        m.failPkg("a", muted = false)
        assertFalse(m.containsKey("addon:a/t")) // stale tile gone, so it shows as inactive
        m.failPkg("b", muted = true)
        assertTrue(m.getValue("addon:b/t").muted) // a muted add-on stays, shown paused
        m.setTiles("b", "B", listOf(TileState("u", "U")))
        assertEquals(setOf("addon:b/u"), m.keys); assertFalse(m.getValue("addon:b/u").muted)
    }
}
