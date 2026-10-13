package dev.glasslauncher.downloads

import dev.glasslauncher.data.MyCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sha = "a".repeat(64)
    private val index = Codes.parseIndex(
        """[{"code":"Tv","url":"https://example.com/tv.apk","name":"TV app","sha256":"${sha.uppercase()}"},
            {"code":"42","url":"https://example.org/x.zip","sha256":"not a hash","extra":1},
            {"code":"","url":"https://example.org/none"}]""",
    )

    @Test fun theIndexParsesAndDropsBadEntries() {
        assertEquals(listOf("Tv", "42"), index.map { it.code })
        assertEquals(sha, index[0].sha256)
        assertNull(index[1].sha256)
        assertTrue(Codes.parseIndex("not json").isEmpty())
        assertTrue(Codes.parseIndex("[]").isEmpty())
    }

    @Test fun theUsersOwnListWinsAndCodesIgnoreCase() {
        val mine = listOf(MyCode("tv", "https://mine.example/tv.apk", "Mine"))
        assertEquals("https://mine.example/tv.apk", Codes.resolve("TV", mine, index)?.url)
        assertEquals("Mine", Codes.resolve("tv", mine, index)?.name)
        assertEquals("https://example.org/x.zip", Codes.resolve("42", mine, index)?.url)
    }

    @Test fun anUnknownCodeResolvesToNothing() {
        assertNull(Codes.resolve("nope", emptyList(), index))
    }

    @Test fun plannerStartsACodeAfterConfirmingItsHostOnce() = runBlocking {
        val start = DownloadPlanner.plan("tv", emptyList(), emptySet()) { index } as Plan.Start
        assertEquals("https://example.com/tv.apk", start.url)
        assertEquals("example.com", start.confirmHost)
        assertEquals(sha, start.sha256)
        val again = DownloadPlanner.plan("TV", emptyList(), setOf("example.com")) { index } as Plan.Start
        assertNull(again.confirmHost)
    }

    @Test fun plannerDoesNotFetchTheIndexForAPersonalCode() = runBlocking {
        val mine = listOf(MyCode("box", "https://mine.example/f.bin", ""))
        val plan = DownloadPlanner.plan("box", mine, emptySet()) { error("index fetched") } as Plan.Start
        assertEquals("https://mine.example/f.bin", plan.url)
        assertNull(plan.name)
    }

    @Test fun plannerRefusesUnknownCodesAndHttp() = runBlocking {
        assertTrue(DownloadPlanner.plan("nope", emptyList(), emptySet()) { index } is Plan.Refuse)
        val http = DownloadPlanner.plan("http://example.com/a.apk", emptyList(), emptySet()) { index } as Plan.Refuse
        assertEquals(DownloadInput.NEEDS_HTTPS, http.message)
        val insecureCode = listOf(MyCode("old", "http://example.com/a", ""))
        assertEquals(DownloadInput.NEEDS_HTTPS, (DownloadPlanner.plan("old", insecureCode, emptySet()) { index } as Plan.Refuse).message)
        assertEquals(Plan.Start("https://example.com/x", null, null, null, null), DownloadPlanner.plan("example.com/x", emptyList(), emptySet()) { index })
    }

    @Test fun theIndexIsFetchedOnceADay() = runBlocking {
        var now = 1_000_000_000L
        var fetches = 0
        var body = """[{"code":"a","url":"https://example.com/a"}]"""
        val cache = tmp.newFolder().resolve("codes.json")
        val idx = CodeIndex(cache, { fetches++; body }, { now })
        assertEquals(1, idx.load().size)
        assertEquals(1, idx.load().size)
        assertEquals(1, fetches)
        now += CodeIndex.DAY_MS + 1
        body = """[{"code":"a","url":"https://example.com/a"},{"code":"b","url":"https://example.com/b"}]"""
        assertEquals(2, idx.load().size)
        assertEquals(2, fetches)
    }

    @Test fun aFailedFetchFallsBackToTheOldCopy() = runBlocking {
        var now = 1_000_000_000L
        var online = true
        val cache = tmp.newFolder().resolve("codes.json")
        val idx = CodeIndex(cache, { if (online) """[{"code":"a","url":"https://example.com/a"}]""" else error("offline") }, { now })
        assertEquals(1, idx.load().size)
        online = false
        now += CodeIndex.DAY_MS * 3
        assertEquals(1, idx.load().size)
        assertTrue(CodeIndex(tmp.newFolder().resolve("none.json"), { error("offline") }, { now }).load().isEmpty())
    }

    @Test fun anApkNeedsAChecksumToBeListed() {
        val list = Codes.parseIndex(
            """[{"code":"a","url":"https://example.com/a.apk"},
                {"code":"b","url":"https://example.com/get?f=b.apk","name":"b.apk","sha256":"${sha}"},
                {"code":"c","url":"https://example.com/c.APK?x=1","sha256":"bad"},
                {"code":"d","url":"https://example.com/d.zip"}]""",
        )
        assertEquals(listOf("b", "d"), list.map { it.code })
        assertNull(Codes.parseIndexOrNull("<html>sign in to the wifi</html>"))
    }

    @Test fun aBadAnswerNeverReplacesTheCachedIndex() = runBlocking {
        var now = 1_000_000_000L
        var body = """[{"code":"a","url":"https://example.com/a"}]"""
        val cache = tmp.newFolder().resolve("codes.json")
        val idx = CodeIndex(cache, { body }, { now })
        assertEquals(1, idx.load().size)
        for (bad in listOf("<html>captive portal</html>", "[{\"code\":", "x".repeat(CodeIndex.MAX_BYTES + 1))) {
            now += CodeIndex.DAY_MS + 1
            body = bad
            assertEquals("a", idx.load().single().code)
        }
        assertTrue(cache.readText().contains("example.com/a"))
    }

    @Test fun cancellationIsNotSwallowedByTheFetch() = runBlocking {
        val idx = CodeIndex(tmp.newFolder().resolve("codes.json"), { throw CancellationException("stop") })
        try { idx.load(); fail("cancellation was swallowed") } catch (e: CancellationException) { /* expected */ }
    }

    @Test fun theIndexComesFromTheMasterBranch() {
        assertEquals("https://raw.githubusercontent.com/o/r/master/downloads/codes.json", CodeIndex.url("o/r"))
    }
}
