package dev.glasslauncher.addons

import dev.glasslauncher.addon.ActionInfo
import dev.glasslauncher.addon.ActionResult
import dev.glasslauncher.addon.ActionsReply
import dev.glasslauncher.addon.AddonApi
import dev.glasslauncher.addon.AddonCodec
import dev.glasslauncher.addon.AddonHandler
import dev.glasslauncher.addon.AddonRequest
import dev.glasslauncher.addon.AddonServer
import dev.glasslauncher.system.RemoteAction
import dev.glasslauncher.system.RemoteButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonActionTest {
    private val ref = AddonActionRef("dev.x.sample", "dev.x.sample.Svc\$Inner", "increment")

    @Test fun idsRoundTripAndMalformedOnesAreRejected() {
        assertEquals("addon:dev.x.sample/dev.x.sample.Svc\$Inner/increment", ref.key)
        assertEquals(ref, AddonActionId.decode(ref.key))
        for (bad in listOf("app:com.x", "addon:", "addon:a.b/S", "addon:a.b/S/x/y", "addon:nodot/S/x", "addon:a.b//x", "addon:a.b/S/", "addon:a.b/S/bad id", "addon:a.b/S/" + "x".repeat(41), "addon:a.b/S S/x"))
            assertNull(bad, AddonActionId.decode(bad))
    }

    @Test fun theMappingStoresAndParsesTheStableKey() {
        val a = RemoteAction.parse(ref.key)
        assertEquals(RemoteAction.AddonAction(ref.pkg, ref.service, ref.id), a); assertEquals(ref.key, a.key)
        assertEquals(RemoteAction.Default, RemoteAction.parse("addon:broken")) // never crashes on a bad stored key
        assertEquals(a, RemoteButtons.action(RemoteButtons.all.first(), mapOf(RemoteButtons.all.first().id to ref.key)))
    }

    private val lists = mapOf(
        "z.pkg.b" to ActionListing("z.pkg.b.S", "Zed", listOf(ActionInfo("go", "Go"))),
        "a.pkg.a" to ActionListing("a.pkg.a.S", "Alpha", listOf(ActionInfo("one", "One"), ActionInfo("two", "Two"))),
    )

    @Test fun pickerListsActionsWithTheirAddonInOrder() {
        val e = AddonActionPicker.entries(lists, null)
        assertEquals(listOf("One · via Alpha", "Two · via Alpha", "Go · via Zed"), e.map { it.label })
        assertTrue(e.all { it.available }); assertEquals("addon:a.pkg.a/a.pkg.a.S/one", e.first().key)
        assertEquals(e, AddonActionPicker.entries(lists, "app:com.netflix.ninja")) // another kind of mapping adds nothing
    }

    @Test fun aMappedActionWhoseAddonIsGoneShowsAsUnavailableAndStaysSelectable() {
        val gone = AddonActionRef("gone.pkg.c", "gone.pkg.c.S", "act").key
        val e = AddonActionPicker.entries(lists, gone)
        assertEquals(PickerEntry(gone, "act · gone.pkg.c (unavailable)", false), e.last())
        assertEquals("act · gone.pkg.c (unavailable)", AddonActionPicker.name(AddonActionId.decode(gone)!!, emptyMap()))
        assertEquals("Go · via Zed", AddonActionPicker.name(AddonActionRef("z.pkg.b", "z.pkg.b.S", "go"), lists))
        assertEquals(1, AddonActionPicker.entries(emptyMap(), gone).size) // nothing installed at all: still shown, so it can be replaced
    }

    @Test fun attributionIsKeptWhenNamesAreLong() {
        val l = mapOf("a.b" to ActionListing("a.b.S", "Sample Add-on", listOf(ActionInfo("x", "N".repeat(30)))))
        val label = AddonActionPicker.entries(l, null).single().label
        assertTrue(label, label.endsWith(" · via Sample Add-on") && label.length <= 60)
    }

    @Test fun actionRepliesAreCleanedBoundedAndJunkIsAFailure() {
        val many = (1..20).map { ActionInfo("a$it", "L‮$it") } + ActionInfo("bad id", "x") + ActionInfo("a1", "dup")
        val got = AddonReplies.actions(AddonCodec.encodeActions(ActionsReply(actions = many)))!!
        assertEquals(12, got.size); assertEquals("L1", got.first().label)
        assertNull(AddonReplies.actions("junk")); assertNull(AddonReplies.actions(null))
        assertNull(AddonReplies.actions(AddonCodec.encodeActions(ActionsReply(error = "x"))))
        assertEquals("", AddonReplies.actionNotice(AddonCodec.encodeActionResult(ActionResult())))
        assertEquals("Counter: 3", AddonReplies.actionNotice(AddonCodec.encodeActionResult(ActionResult("Counter:​ 3"))))
        assertEquals(60, AddonReplies.actionNotice(AddonCodec.encodeActionResult(ActionResult("n".repeat(200))))!!.length)
        assertNull(AddonReplies.actionNotice(AddonCodec.encodeActionResult(ActionResult(error = "boom")))); assertNull(AddonReplies.actionNotice("junk"))
    }

    private val h = object : AddonHandler {
        var ran: String? = null
        override fun actions() = listOf(ActionInfo("go", "Go"))
        override fun onAction(id: String): String? { ran = id; if (id == "boom") throw IllegalStateException(); return "done" }
    }

    @Test fun serverRunsActionsRefusesStrangersAndReportsExceptions() {
        val get = AddonServer.handle(AddonApi.GET_ACTIONS, AddonCodec.encodeRequest(AddonRequest()), h, true)
        assertEquals("go", AddonCodec.decodeActions(get)!!.actions.single().id)
        val ok = AddonServer.handle(AddonApi.ON_ACTION, AddonCodec.encodeRequest(AddonRequest(action = "go")), h, true)
        assertEquals("done", AddonCodec.decodeActionResult(ok)!!.notice); assertEquals("go", h.ran)
        assertEquals("IllegalStateException", AddonCodec.decodeActionResult(AddonServer.handle(AddonApi.ON_ACTION, AddonCodec.encodeRequest(AddonRequest(action = "boom")), h, true))!!.error)
        assertNull(AddonServer.handle(AddonApi.ON_ACTION, AddonCodec.encodeRequest(AddonRequest(action = "go")), h, permitted = false))
        assertEquals(emptyList<ActionInfo>(), object : AddonHandler {}.actions()) // an add-on without actions answers with none
    }

    @Test fun actionsShareTheMuteAndRateBudgetOfTheAddon() {
        var t = 0L
        val p = AddonPolicy({ t })
        assertTrue(p.tryAcquire("a.b", "action:go"))
        t = 500; assertFalse(p.tryAcquire("a.b", "action:go")) // a button held down or tapped fast: one call per second
        assertTrue(p.tryAcquire("a.b", "action:other"))
        repeat(3) { p.report("a.b", false) } // timeouts and errors count like tile calls
        t = 10_000; assertFalse(p.tryAcquire("a.b", "action:go")) // muted: a pressed button does nothing, never stalls
        assertTrue(p.tryAcquire("c.d", "action:go"))
    }
}
