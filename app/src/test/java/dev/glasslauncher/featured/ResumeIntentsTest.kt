package dev.glasslauncher.featured

import dev.glasslauncher.featured.ResumeIntents.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P140: a Continue Watching row's published intent is launched as published, but only ever into its own app. */
class ResumeIntentsTest {
    private val glass = "dev.glasslauncher"
    private val app = "com.example.tv"
    private fun parse(uri: String, pkg: String = app) = ResumeIntents.parsePublished(uri, pkg, glass)
    private fun item(link: String?, id: String = "tv:$app:1", pkgs: List<String> = listOf(app), owner: String? = null) = FeaturedItem(id = id, title = "T", link = link, packages = pkgs, owner = owner)

    @Test fun httpsAndOwnSchemeAreAccepted() {
        assertNotNull(parse("intent://watch/123#Intent;scheme=https;package=$app;end"))
        assertNotNull(parse("intent://play?id=9&t=120#Intent;scheme=myapp;action=android.intent.action.VIEW;end"))
        assertNotNull(parse("intent:https://example.com/title/1#Intent;end"))
        assertNotNull(parse("intent://x#Intent;scheme=market;end"))
    }

    @Test fun anotherPackageOrComponentIsRefused() {
        assertNull(parse("intent://x#Intent;scheme=https;package=com.other;end"))
        assertNull(parse("intent://x#Intent;scheme=https;component=com.other/.Main;end"))
        assertNull(parse("intent://x#Intent;scheme=https;component=$glass/.MainActivity;end"))
        assertNotNull(parse("intent://x#Intent;scheme=https;component=$app/.PlayerActivity;end"))
    }

    @Test fun glassItselfIsNeverATarget() {
        assertNull(parse("intent://x#Intent;scheme=https;end", pkg = glass))
    }

    @Test fun selectorsBadActionsAndBadSchemesAreRefused() {
        assertNull(parse("intent://x#Intent;scheme=https;SEL;component=com.other/.A;end"))
        assertNull(parse("intent://x#Intent;scheme=https;action=android.intent.action.CALL;end"))
        assertNull(parse("intent://x#Intent;scheme=https;action=android.intent.action.DELETE;end"))
        assertNull(parse("intent://x#Intent;scheme=file;end"))
        assertNull(parse("intent://x#Intent;scheme=content;end"))
        assertNull(parse("intent://x#Intent;scheme=javascript;end"))
        assertNull(parse("intent:file:///sdcard/a.mp4#Intent;end"))
        assertNull(parse("intent://x#Intent;scheme=HTTPS ;end"))
        assertNull(parse("not an intent"))
        assertNull(parse("intent:no marker"))
        assertNull(parse("intent:#Intent;end"))
    }

    @Test fun mainNeedsItsOwnComponent() {
        assertNull(parse("intent:#Intent;action=android.intent.action.MAIN;end"))
        assertNotNull(parse("intent:#Intent;action=android.intent.action.MAIN;component=$app/.Home;end"))
        assertNotNull(parse("intent://x#Intent;scheme=a;action=$app.PLAY;end"))
        assertNull(parse("intent://x#Intent;scheme=a;action=com.other.PLAY;end"))
    }

    @Test fun publishedIntentComesFirstThenAppLaunch() {
        val link = "intent://watch/1#Intent;scheme=https;end"
        assertEquals(listOf<Step>(Step.Published(link, app), Step.Launch(app)), ResumeIntents.steps(item(link, owner = app), listOf(app), glass))
    }

    @Test fun plainLinksAreViewedInEachCapableApp() {
        val steps = ResumeIntents.steps(item("stremio:///detail/movie/tt1", id = "stremio:1", pkgs = listOf("a.b", "c.d")), listOf("a.b", "c.d"), glass)
        assertEquals(listOf<Step>(Step.View("stremio:///detail/movie/tt1", "a.b"), Step.View("stremio:///detail/movie/tt1", "c.d"), Step.Launch("a.b"), Step.Launch("c.d")), steps)
    }

    @Test fun unsafeLinkFallsBackToLaunchOnly() {
        assertEquals(listOf<Step>(Step.Launch(app)), ResumeIntents.steps(item("intent://x#Intent;scheme=https;package=com.other;end"), listOf(app), glass))
        assertEquals(listOf<Step>(Step.Launch(app)), ResumeIntents.steps(item("file:///sdcard/x.mp4"), listOf(app), glass))
        assertEquals(listOf<Step>(Step.Launch(app)), ResumeIntents.steps(item(null), listOf(app), glass))
        assertEquals(emptyList<Step>(), ResumeIntents.steps(item(null, pkgs = listOf(glass)), listOf(glass), glass))
    }

    @Test fun onlyRowsFromOtherAppsAreUntrusted() {
        assertTrue(ResumeIntents.isAppPublished(item("https://a")))
        assertTrue(!ResumeIntents.isAppPublished(item("https://a", id = "tt123")))
    }

    private val unpackaged = "intent://watch/1#Intent;scheme=https;end"
    private val crowd = listOf("com.a", "com.b", app, "com.c")   // several installed apps that would all handle the intent

    @Test fun anUnpackagedIntentOnlyGoesToTheRowsOwner() {
        val steps = ResumeIntents.steps(item(unpackaged, pkgs = crowd, owner = app), crowd, glass)
        assertEquals(listOf<Step>(Step.Published(unpackaged, app), Step.Launch(app)), steps)
        // The owner not installed: nobody else is tried.
        assertEquals(emptyList<Step>(), ResumeIntents.steps(item(unpackaged, pkgs = crowd, owner = "com.gone"), crowd, glass))
        // A plain link is also the owner's alone.
        assertEquals(listOf<Step>(Step.View("https://x/y", app), Step.Launch(app)), ResumeIntents.steps(item("https://x/y", pkgs = crowd, owner = app), crowd, glass))
    }

    @Test fun withoutAnOwnerAnUnpackagedIntentIsRefused() {
        val steps = ResumeIntents.steps(item(unpackaged, id = "x", pkgs = crowd), crowd, glass)
        assertTrue(steps.none { it is Step.Published })
        assertEquals(crowd.map { Step.Launch(it) }, steps)
    }

    @Test fun withoutAnOwnerAPackagedIntentStillOnlyReachesItsApp() {
        val link = "intent://watch/1#Intent;scheme=https;package=$app;end"
        val steps = ResumeIntents.steps(item(link, id = "x", pkgs = crowd), crowd, glass)
        assertEquals(listOf<Step>(Step.Published(link, app)), steps.filterIsInstance<Step.Published>())
    }

    @Test fun stremioDeepLinkGetsAutoPlay() {
        val want = "stremio:///detail/series/tt0944947/tt0944947:1:1?autoPlay=true"
        assertEquals(want, ResumeIntents.stremioAutoPlay("stremio:///detail/series/tt0944947/tt0944947:1:1"))
        assertEquals("already set: kept once", want, ResumeIntents.stremioAutoPlay("stremio:///detail/series/tt0944947/tt0944947:1:1?autoPlay=true"))
        assertEquals("stremio:///detail/movie/tt1/tt1?x=1&autoPlay=true", ResumeIntents.stremioAutoPlay("stremio://detail/movie/tt1/tt1?x=1&autoPlay=false"))
        assertEquals(want, ResumeIntents.stremioAutoPlay("intent:stremio:///detail/series/tt0944947/tt0944947:1:1#Intent;scheme=stremio;package=com.stremio.one;end"))
    }

    @Test fun stremioLinkWithoutAVideoIdOrFromAnotherSchemeIsNotAutoPlayed() {
        assertEquals(null, ResumeIntents.stremioAutoPlay("stremio:///detail/movie/tt1"))
        assertEquals(null, ResumeIntents.stremioAutoPlay("stremio:///board"))
        assertEquals(null, ResumeIntents.stremioAutoPlay("https://example.com/detail/movie/tt1/tt1"))
        assertEquals(null, ResumeIntents.stremioAutoPlay(null))
    }
}
