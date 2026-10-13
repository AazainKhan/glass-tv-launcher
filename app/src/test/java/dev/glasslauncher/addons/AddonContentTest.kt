package dev.glasslauncher.addons

import dev.glasslauncher.addon.AddonApi
import dev.glasslauncher.addon.AddonCodec
import dev.glasslauncher.addon.AddonPoint
import dev.glasslauncher.addon.AddonPointsHandler
import dev.glasslauncher.addon.AddonProviderServer
import dev.glasslauncher.addon.Choice
import dev.glasslauncher.addon.FeedItem
import dev.glasslauncher.addon.FeedReply
import dev.glasslauncher.addon.FormField
import dev.glasslauncher.addon.FormReply
import dev.glasslauncher.addon.HomeRowReply
import dev.glasslauncher.addon.ProviderRequest
import dev.glasslauncher.addon.RowItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonContentTest {
    private fun feedJson(vararg i: FeedItem, heading: String = "H", error: String? = null) = AddonCodec.encodeFeed(FeedReply(heading = heading, items = i.toList(), error = error))
    private fun item(id: String = "a", title: String = "T", image: String? = null, link: String? = null) = FeedItem(id, title, image = image, link = link)

    @Test fun feedIsCleanedBoundedAndMarkedAsFromTheAddon() {
        val many = (1..45).map { item("i$it", "T$it") }
        val (feed, _) = AddonContent.feed(feedJson(*many.toTypedArray(), heading = "Top‮"), "p.q", "Sample")!!
        assertEquals(AddonContent.MAX_ITEMS, feed.items.size)
        assertEquals("Top · via Sample", feed.heading)
        assertEquals("addon:p.q/i1", feed.items.first().id)
        assertEquals(listOf("p.q"), feed.items.first().packages) // Select can only fall back to the add-on's own app
    }

    @Test fun feedArtMustBeHttpsAndLinksHttpsOrMarket() {
        val (feed, _) = AddonContent.feed(feedJson(
            item("a", "A", image = "http://x.com/a.jpg", link = "javascript:alert(1)"),
            item("b", "B", image = "https://x.com/b.jpg", link = "https://x.com/b"),
            item("c", "C", image = "https://u:p@x.com/c.jpg", link = "intent://scan#Intent;end"),
            item("d", "D", image = "file:///data/x.png", link = "market://details?id=a.b"),
            item("e", "E", image = "https://x.com/a b.jpg", link = "https://x.com/\u0000"),
        ), "p", "S")!!
        val by = feed.items.associateBy { it.title }
        assertNull(by["A"]!!.image); assertNull(by["A"]!!.link)
        assertEquals("https://x.com/b.jpg", by["B"]!!.image); assertEquals("https://x.com/b", by["B"]!!.link)
        assertNull(by["C"]!!.image); assertNull(by["C"]!!.link)
        assertNull(by["D"]!!.image); assertEquals("market://details?id=a.b", by["D"]!!.link)
        assertNull(by["E"]!!.image); assertNull(by["E"]!!.link)
    }

    @Test fun junkErrorAndEmptyFeedsAreFailures() {
        assertNull(AddonContent.feed("not json", "p", "S"))
        assertNull(AddonContent.feed(null, "p", "S"))
        assertNull(AddonContent.feed(feedJson(item(), error = "boom"), "p", "S"))
        assertNull(AddonContent.feed(feedJson(), "p", "S"))
        assertNull(AddonContent.feed(feedJson(item("bad id!", "T"), item("a", "​")), "p", "S")) // nothing usable left
    }

    @Test fun rowDropsCardsWithUnsafeActions() {
        val json = AddonCodec.encodeHomeRow(HomeRowReply(title = "R", items = listOf(
            RowItem("a", "A", link = "https://x.com/a"),
            RowItem("b", "B", component = "dev.glasslauncher/dev.glasslauncher.MainActivity"),
            RowItem("c", "C", component = "other.app/.Main"),
            RowItem("d", "D", link = "intent:#Intent;end"),
            RowItem("e", "E"),
            RowItem("f", "F", component = ".Detail"),
        )))
        val row = AddonContent.row(json, "p.q", "dev.glasslauncher", "S")!!
        assertEquals(listOf("A", "E", "F"), row.items.map { it.title })
        assertEquals(AddonAction.Component("p.q", "p.q.Detail"), row.items.last().action)
        assertEquals(AddonAction.LaunchApp, row.items[1].action)
        assertNull(AddonContent.row(AddonCodec.encodeHomeRow(HomeRowReply(error = "x")), "p", "g", "S"))
    }

    @Test fun intentsNeverTargetGlassOrOtherPackages() {
        assertNull(AddonIntents.resolve("dev.glasslauncher", "dev.glasslauncher", null, null)) // an add-on cannot be Glass
        assertNull(AddonIntents.resolve("p", "g", null, "g/g.Main"))
        assertNull(AddonIntents.resolve("p", "g", null, "p/bad class"))
        assertNull(AddonIntents.resolve("p", "g", "https://x.com", "p/.A")?.takeIf { it !is AddonAction.View }) // a link wins
        assertNull(AddonIntents.resolve("p", "g", "content://x/y", null))
        assertNull(AddonIntents.resolve("p", "g", "http://x.com", null)) // cleartext is not a safe link
        assertEquals(AddonAction.View("market://details?id=a.b"), AddonIntents.resolve("p", "g", "market://details?id=a.b", null))
        assertNull(AddonIntents.resolve("p", "g", "market://search?q=a", null)) // only a package's page
    }

    @Test fun formDropsUnknownTypesAndBoundsEverything() {
        val fields = listOf(
            FormField("t", "toggle", "T"),
            FormField("c", "choice", "C", choices = (1..12).map { Choice("v$it", "V$it") }),
            FormField("e", "choice", "Empty choice"),
            FormField("x", "slider", "Unknown"),
            FormField("n", "text", "N‮", default = "d".repeat(200)),
            FormField("a", "action", "A"),
        ) + (1..20).map { FormField("f$it", "toggle", "F$it") }
        val form = AddonContent.form(AddonCodec.encodeForm(FormReply(title = "Ti", fields = fields)), "S")!!
        assertEquals(AddonContent.MAX_FIELDS, form.fields.size)
        assertEquals(listOf("t", "c", "n", "a"), form.fields.take(4).map { it.id })
        assertEquals(AddonContent.MAX_CHOICES, form.fields[1].choices.size)
        assertEquals("N", form.fields[2].label); assertEquals(AddonContent.MAX_VALUE, form.fields[2].default!!.length)
        assertNull(AddonContent.form("junk", "S")); assertNull(AddonContent.form(AddonCodec.encodeForm(FormReply(error = "x")), "S"))
    }

    @Test fun refreshIntervalIsNeverUnderFifteenMinutes() {
        assertEquals(30, AddonRefresh.minutes(0)); assertEquals(15, AddonRefresh.minutes(1)); assertEquals(60, AddonRefresh.minutes(60))
        assertEquals(24 * 60, AddonRefresh.minutes(1_000_000))
        var t = 0L
        val g = IntervalGate { t }
        assertTrue(g.due("k", 1)); g.mark("k", 1, 0)
        t = 29 * 60_000L; assertFalse(g.due("k", 1))
        assertTrue(g.due("k", 2)) // the add-on's settings changed
        t = 30 * 60_000L; assertTrue(g.due("k", 1))
        g.mark("k", 1, 5); t += 14 * 60_000L; assertFalse(g.due("k", 1)) // a hint under 15 min is raised to 15
        g.forget("k"); assertTrue(g.due("k", 1))
    }

    private val h = object : AddonPointsHandler {
        override fun feed(req: ProviderRequest) = FeedReply(heading = req.values["x"].orEmpty(), items = listOf(item()))
        override fun homeRow(req: ProviderRequest): HomeRowReply = throw IllegalStateException()
    }

    @Test fun providerServerEnforcesPermissionAndTurnsExceptionsIntoErrors() {
        val req = AddonCodec.encodeProviderRequest(ProviderRequest(values = mapOf("x" to "v")))
        assertNull(AddonProviderServer.handle(AddonApi.METHOD_FEED, req, h, permitted = false))
        assertEquals("v", AddonCodec.decodeFeed(AddonProviderServer.handle(AddonApi.METHOD_FEED, req, h, true))!!.heading) // settings come back in the request
        assertEquals("IllegalStateException", AddonCodec.decodeHomeRow(AddonProviderServer.handle(AddonApi.METHOD_HOME_ROW, req, h, true))!!.error)
        assertNull(AddonProviderServer.handle(AddonApi.METHOD_SETTINGS_FORM, req, h, true)) // a point it does not supply
        assertNull(AddonProviderServer.handle("nope", req, h, true))
        assertNotNull(AddonProviderServer.handle(AddonApi.METHOD_FEED, "junk", h, true)) // a junk request is an empty one
    }

    @Test fun unknownFieldsInProviderRepliesAreIgnored() {
        val r = AddonCodec.decodeFeed("""{"api":9,"heading":"H","extra":[1],"items":[{"id":"a","title":"T","future":true}]}""")!!
        assertEquals("T", r.items.single().title)
    }

    private fun cand(provider: ProviderCandidate?, meta: Map<String, String?> = mapOf(AddonApi.META_API to "1", AddonApi.META_PROVIDER to "auth", AddonApi.META_POINTS to "tiles, topshelf ,bogus,settings")) =
        AddonCandidate("p", "p.S", true, AddonApi.PERMISSION, meta, "c", provider)

    @Test fun providerPointsNeedAnOwnGuardedExportedProvider() {
        val good = ProviderCandidate("p", true, AddonApi.PERMISSION, AddonApi.PERMISSION)
        assertEquals("auth", AddonDiscovery.filter(listOf(cand(good))).single().authority)
        assertEquals(setOf("tiles", "topshelf", "settings"), AddonDiscovery.filter(listOf(cand(good))).single().points)
        for (bad in listOf(
            good.copy(pkg = "other"), good.copy(exported = false), good.copy(readPermission = null),
            good.copy(writePermission = "android.permission.INTERNET"), null,
        )) {
            val i = AddonDiscovery.filter(listOf(cand(bad))).single()
            assertNull(i.authority); assertEquals(setOf(AddonPoint.TILES), i.points) // only tiles survive
        }
    }

    @Test fun settingsComeBackKeyedByField() {
        val s = AddonSnapshot(mapOf("p" to "c"), mapOf("p/a" to "1", "p/b" to "2", "q/a" to "9", "pp/a" to "8"))
        assertEquals(mapOf("a" to "1", "b" to "2"), s.valuesOf("p"))
        assertTrue(AddonSnapshot.NONE.valuesOf("p").isEmpty())
    }
}

class FocusRefreshTest {
    private var t = 0L
    private val gate = IntervalGate { t }
    private val f = FocusRefresh(gate)
    private fun min(n: Int) = n * 60_000L

    @Test fun firstFocusRefreshesAndRepeatedFocusInsideTheFloorDoesNot() {
        assertTrue(f.begin("row:p", 1, homeVisible = true)); f.end("row:p"); gate.mark("row:p", 1, 30)
        t = min(1); assertFalse(f.begin("row:p", 1, true)) // focus flapping never polls
        t = min(29); assertFalse(f.begin("row:p", 1, true))
        t = min(30); assertTrue(f.begin("row:p", 1, true))
    }

    @Test fun nothingRefreshesWhileHomeIsNotVisible() {
        assertFalse(f.begin("row:p", 1, homeVisible = false))
        assertTrue(f.begin("row:p", 1, homeVisible = true))
    }

    @Test fun oneRefreshAtATimePerKeyAndFocusDuringItIsIgnored() {
        assertTrue(f.begin("row:p", 1, true))
        assertFalse(f.begin("row:p", 1, true)) // already running
        assertTrue(f.begin("row:q", 1, true)) // another add-on is independent
        f.end("row:p"); assertTrue(f.begin("row:p", 1, true))
    }

    @Test fun anExplicitUserChangeReopensTheFloorButAReSignDoesNot() {
        gate.mark("row:p", AddonSurfaces.version(AddonSnapshot(mapOf("p" to "c1"), mapOf("p/x" to "1")), "p"), 30)
        t = min(2)
        assertFalse(f.begin("row:p", AddonSurfaces.version(AddonSnapshot(mapOf("p" to "c2"), mapOf("p/x" to "1")), "p"), true))
        assertTrue(f.begin("row:p", AddonSurfaces.version(AddonSnapshot(mapOf("p" to "c1"), mapOf("p/x" to "2")), "p"), true))
    }

    @Test fun aFailedReadRetriesSoonButStillNotOnEveryFocus() {
        gate.retryIn("row:p", 1, AddonSurfaces.RETRY_MS)
        t = AddonSurfaces.RETRY_MS - 1; assertFalse(f.begin("row:p", 1, true))
        t = AddonSurfaces.RETRY_MS; assertTrue(f.begin("row:p", 1, true))
    }
}
