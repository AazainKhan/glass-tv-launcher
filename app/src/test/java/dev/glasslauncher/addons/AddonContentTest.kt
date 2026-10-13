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
import dev.glasslauncher.addon.OptionsReply
import dev.glasslauncher.addon.ShowIf
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

class DynamicFormTest {
    private fun field(id: String, type: String = "toggle", showIf: ShowIf? = null, optionsFrom: String? = null, choices: List<Choice> = emptyList(), default: String? = null) =
        FormField(id, type, id.uppercase(), choices = choices, showIf = showIf, optionsFrom = optionsFrom, default = default)
    private fun form(vararg f: FormField) = AddonContent.form(AddonCodec.encodeForm(FormReply(fields = f.toList())), "S")
    private val opts = listOf(Choice("a", "A"), Choice("b", "B"))

    @Test fun aRuleShowsAFieldOnlyWhileTheOtherFieldHasTheValue() {
        val f = form(field("bright"), field("nick", "text", showIf = ShowIf("bright", "true")))!!.fields
        assertEquals(listOf("bright"), FormRules.visible(f, emptyMap()).map { it.id }) // toggle defaults to "false"
        assertEquals(listOf("bright", "nick"), FormRules.visible(f, mapOf("bright" to "true")).map { it.id })
        assertEquals(listOf("bright"), FormRules.visible(f, mapOf("bright" to "false")).map { it.id })
    }

    @Test fun aRuleCanReadAChoiceOrAnUnsetDefault() {
        val f = form(field("flavour", "choice", choices = opts, default = "a"), field("x", "text", showIf = ShowIf("flavour", "a")), field("y", "text", showIf = ShowIf("flavour", "b")))!!.fields
        assertEquals(listOf("flavour", "x"), FormRules.visible(f, emptyMap()).map { it.id })
        assertEquals(listOf("flavour", "y"), FormRules.visible(f, mapOf("flavour" to "b")).map { it.id })
    }

    @Test fun brokenRulesDropTheFieldAndNestingIsOneDeep() {
        val f = form(
            field("t"), field("ok", "text", showIf = ShowIf("t", "true")),
            field("self", "text", showIf = ShowIf("self", "x")), field("ghost", "text", showIf = ShowIf("missing", "x")),
            field("chain", "text", showIf = ShowIf("ok", "x")), // points at a field that has a rule: depth 2
            field("empty", "text", showIf = ShowIf("t", "")), field("act", "action"), field("onact", "text", showIf = ShowIf("act", "x")),
        )!!.fields.map { it.id }
        assertEquals(listOf("t", "ok", "act"), f)
    }

    @Test fun optionsFromNeedsAChoiceAndARealOtherField() {
        val f = form(
            field("flavour", "choice", choices = opts), field("dyn", "choice", optionsFrom = "flavour"), field("dyn2", "choice", optionsFrom = "dyn2"),
            field("dyn3", "choice", optionsFrom = "nope"), field("notchoice", "text", optionsFrom = "flavour"),
        )!!.fields
        assertEquals(listOf("flavour", "dyn", "notchoice"), f.map { it.id }) // a text field's stray optionsFrom is ignored, the field stays
        assertNull(f.first { it.id == "notchoice" }.optionsFrom)
        assertEquals("flavour", f.first { it.id == "dyn" }.optionsFrom)
    }

    @Test fun aDynamicChoiceNeedsNoStaticOptionsButTheDefaultMustBeOneOfThem() {
        val f = form(field("flavour", "choice", choices = opts), field("dyn", "choice", optionsFrom = "flavour", default = "zzz"), field("dyn2", "choice", optionsFrom = "flavour", choices = opts, default = "a"))!!.fields.associateBy { it.id }
        assertNull(f["dyn"]!!.default); assertEquals("a", f["dyn2"]!!.default)
    }

    @Test fun optionRepliesAreCappedCleanedAndJunkKeepsTheStaticOnes() {
        val many = (1..20).map { Choice("v$it", "L‮$it") } + Choice("v1", "dup") + Choice("​", "x")
        val got = AddonContent.options(AddonCodec.encodeOptions(OptionsReply(choices = many)))!!
        assertEquals(AddonContent.MAX_CHOICES, got.size); assertEquals("L1", got.first().label)
        assertNull(AddonContent.options("junk")); assertNull(AddonContent.options(null))
        assertNull(AddonContent.options(AddonCodec.encodeOptions(OptionsReply(error = "x"))))
        assertNull(AddonContent.options(AddonCodec.encodeOptions(OptionsReply(choices = emptyList()))))
    }

    @Test fun optionsAreAskedAgainOnlyWhenTheDependencyChanges() {
        val c = OptionsCache()
        assertNull(c.get("topping", "vanilla")); c.put("topping", "vanilla", opts)
        assertEquals(opts, c.get("topping", "vanilla")) // same dependency value: no second call
        assertNull(c.get("topping", "mocha")) // changed: asked
        assertEquals(listOf("topping"), FormRules.dependents(listOf(field("flavour", "choice", choices = opts), field("topping", "choice", optionsFrom = "flavour")), "flavour"))
        assertTrue(FormRules.dependents(listOf(field("a")), "a").isEmpty())
        assertEquals("b", FormRules.next(opts, "a")); assertEquals("a", FormRules.next(opts, "b")); assertEquals("a", FormRules.next(opts, "gone"))
    }

    @Test fun capsStillApplyAndHiddenFieldsKeepTheirValuesButRemovedOnesArePurged() {
        val many = (1..20).map { field("f$it", "text") }
        assertEquals(AddonContent.MAX_FIELDS, form(*many.toTypedArray())!!.fields.size)
        val stored = mapOf("p/nick" to "Al", "p/gone" to "x", "p/bright" to "false")
        val f = form(field("bright"), field("nick", "text", showIf = ShowIf("bright", "true")))!!.fields
        assertFalse(FormRules.visible(f, mapOf("bright" to "false")).any { it.id == "nick" }) // hidden...
        assertEquals(setOf("p/gone"), AddonPurge.undeclared(stored, "p", f.map { it.id }.toSet())) // ...but declared, so its value is kept
    }

    @Test fun providerServerAnswersFieldOptionsAndFailsSafely() {
        val h = object : dev.glasslauncher.addon.AddonPointsHandler {
            override fun fieldOptions(req: dev.glasslauncher.addon.ProviderRequest) = if (req.id == "boom") throw IllegalStateException() else OptionsReply(choices = opts)
        }
        val ask = { id: String -> AddonProviderServer.handle(AddonApi.METHOD_FIELD_OPTIONS, AddonCodec.encodeProviderRequest(dev.glasslauncher.addon.ProviderRequest(id = id)), h, true) }
        assertEquals(2, AddonCodec.decodeOptions(ask("topping"))!!.choices.size)
        assertEquals("IllegalStateException", AddonCodec.decodeOptions(ask("boom"))!!.error)
        assertNull(AddonProviderServer.handle(AddonApi.METHOD_FIELD_OPTIONS, "{}", object : dev.glasslauncher.addon.AddonPointsHandler {}, true))
        assertNull(AddonProviderServer.handle(AddonApi.METHOD_FIELD_OPTIONS, "{}", h, permitted = false))
    }

    @Test fun oldFormsWithoutRulesDecodeUnchanged() {
        val f = AddonCodec.decodeForm("""{"fields":[{"id":"a","type":"toggle","label":"A","future":1}]}""")!!.fields.single()
        assertNull(f.showIf); assertNull(f.optionsFrom)
    }
}

class FormReviewTest {
    private fun field(id: String, type: String = "toggle", showIf: ShowIf? = null, optionsFrom: String? = null, choices: List<Choice> = emptyList()) =
        FormField(id, type, id.uppercase(), choices = choices, showIf = showIf, optionsFrom = optionsFrom)
    private fun form(vararg f: FormField) = AddonContent.form(AddonCodec.encodeForm(FormReply(fields = f.toList())), "S")!!
    private val opts = listOf(Choice("a", "A"), Choice("b", "B"))

    @Test fun aDependencyChangeStartsFromTheStaticOptionsAndAFailureIsUnavailableAndNotSelectable() {
        val f = field("dyn", "choice", optionsFrom = "x", choices = opts)
        val start = FormRules.initialOptions(f)
        assertEquals(opts, start.choices); assertEquals(FormRules.Status.Loading, start.status) // not the old dependency's options
        val failed = FormRules.Options(emptyList(), FormRules.Status.Failed)
        assertEquals("Unavailable", FormRules.choiceText(failed, "a")); assertFalse(FormRules.choiceSelectable(failed))
        assertTrue(FormRules.choiceSelectable(FormRules.Options(opts, FormRules.Status.Ready)))
    }

    @Test fun aStoredValueThatIsNotAnOptionShowsNotSetNeverAnotherValue() {
        val ready = FormRules.Options(listOf(Choice("cocoa", "Cocoa"), Choice("caramel", "Caramel")), FormRules.Status.Ready)
        assertEquals("Not set", FormRules.choiceText(ready, "sprinkles")); assertEquals("Not set", FormRules.choiceText(ready, ""))
        assertEquals("Caramel", FormRules.choiceText(ready, "caramel"))
        assertEquals("cocoa", FormRules.next(ready.choices, "sprinkles")) // Select from "Not set" picks the first, and stores it
    }

    @Test fun nothingStaysOnLoadingForever() {
        assertEquals("Loading…", FormRules.choiceText(FormRules.Options(emptyList(), FormRules.Status.Loading), ""))
        assertEquals("Unavailable", FormRules.choiceText(FormRules.Options(emptyList(), FormRules.Status.Failed), ""))
        assertEquals("A", FormRules.choiceText(FormRules.Options(opts, FormRules.Status.Loading), "a")) // static options meanwhile
    }

    @Test fun dependenciesAreOneLevelDeepWhateverTheirKind() {
        val f = form(
            field("root"), field("c1", "choice", choices = opts),
            field("a", "choice", optionsFrom = "c1"), // ok
            field("b", "choice", optionsFrom = "a"), // optionsFrom chain
            field("cyc1", "choice", optionsFrom = "cyc2"), field("cyc2", "choice", optionsFrom = "cyc1"), // cycle
            field("s1", "text", showIf = ShowIf("a", "x")), // showIf on a field that has an optionsFrom
            field("s2", "text", showIf = ShowIf("root", "true")), field("o2", "choice", optionsFrom = "s2"), // optionsFrom on a field with a showIf
        ).fields.map { it.id }
        assertEquals(listOf("root", "c1", "a", "s2"), f)
    }

    @Test fun rulesAreResolvedAgainstTheFinalList() {
        // "dead" is dropped (bad rule); "child" points at it, so it must go too instead of being hidden forever.
        val f = form(field("t"), field("dead", "text", showIf = ShowIf("ghost", "x")), field("child", "text", showIf = ShowIf("dead", "x")), field("ok", "text", showIf = ShowIf("t", "true")))
        assertEquals(listOf("t", "ok"), f.fields.map { it.id })
    }

    @Test fun sameValueForgetsNothingAndARealChangeForgetsDependents() {
        val fields = listOf(field("flavour", "choice", choices = opts), field("topping", "choice", optionsFrom = "flavour"))
        assertTrue(FormRules.dependentsOnChange(fields, "flavour", "a", "a").isEmpty())
        assertEquals(listOf("topping"), FormRules.dependentsOnChange(fields, "flavour", "a", "b"))
    }

    @Test fun aReadThatWasNeverMadeDoesNotTouchTheMarks() {
        val ok = ProviderResult.Ok("row")
        assertEquals(AddonSurfaces.RowDecision.Replace, AddonSurfaces.rowDecision(ok, null, 0))
        assertEquals(AddonSurfaces.RowDecision.Nothing, AddonSurfaces.rowDecision(ProviderResult.Skipped, 1_000, 2_000)) // rate limited: the fresh 15 min mark stays
        assertEquals(AddonSurfaces.RowDecision.KeepAndRetry, AddonSurfaces.rowDecision(ProviderResult.Failed, 1_000, 2_000))
        assertEquals(AddonSurfaces.RowDecision.ClearAndRetry, AddonSurfaces.rowDecision(ProviderResult.Failed, 1_000, 2_000 + AddonSurfaces.STALE_MS))
        assertEquals(AddonSurfaces.RowDecision.ClearAndRetry, AddonSurfaces.rowDecision(ProviderResult.Failed, null, 5))
    }

    @Test fun nothingEnabledMeansNoScan() {
        assertTrue(AddonSurfaces.nothingToRefresh(AddonSnapshot.NONE, haveRows = false))
        assertFalse(AddonSurfaces.nothingToRefresh(AddonSnapshot(mapOf("p" to "c"), emptyMap()), false))
        assertFalse(AddonSurfaces.nothingToRefresh(AddonSnapshot.NONE, haveRows = true)) // a row to clear
        assertFalse(AddonSurfaces.nothingToRefresh(AddonSnapshot(emptyMap(), mapOf("p/x" to "1")), false)) // settings to purge
    }

    @Test fun optionReadsAreCappedPerAddonPerMinute() {
        var t = 0L
        val p = AddonPolicy({ t })
        repeat(20) { assertTrue(p.windowAcquire("a", "options", 20, 60_000)); t += 100 }
        assertFalse(p.windowAcquire("a", "options", 20, 60_000)) // the 21st inside the minute
        assertTrue(p.windowAcquire("b", "options", 20, 60_000)) // another add-on has its own
        t = 60_000; assertTrue(p.windowAcquire("a", "options", 20, 60_000)) // the oldest ones have aged out
        repeat(3) { p.report("a", false) }
        assertFalse(p.windowAcquire("a", "options", 20, 60_000)) // muted
    }

    @Test fun onlyIdsTheReplyDoesNotNameLoseTheirValuesNotFieldsWithABrokenRule() {
        val f = form(field("t"), field("broken", "text", showIf = ShowIf("ghost", "x")))
        assertEquals(listOf("t"), f.fields.map { it.id }); assertEquals(setOf("t", "broken"), f.declared) // rejected, but still declared
        val stored = mapOf("p/t" to "true", "p/broken" to "keep me", "p/gone" to "x")
        assertEquals(setOf("p/gone"), AddonPurge.undeclared(stored, "p", f.declared))
        assertNull(AddonContent.form("""{"fields":"junk"}""", "S")) // a malformed reply is no form at all: nothing is purged from it
    }
}
