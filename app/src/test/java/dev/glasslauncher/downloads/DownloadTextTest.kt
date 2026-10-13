package dev.glasslauncher.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadTextTest {
    private fun item(state: DownloadState, size: Long = 100_000_000, done: Long = 0, bps: Long = 0, name: String? = null, file: String? = null) =
        DownloadItem("i", "https://host.example/path/app.apk?x=1", name, state = state, size = size, downloaded = done, bytesPerSecond = bps, file = file)

    @Test fun sizesAndSpeeds() {
        assertEquals("820 KB", DownloadText.bytes(820_000))
        assertEquals("12.4 MB", DownloadText.bytes(12_400_000))
        assertEquals("1.50 GB", DownloadText.bytes(1_500_000_000))
        assertEquals("3.4 MB/s", DownloadText.speed(3_400_000))
    }

    @Test fun timeLeft() {
        assertEquals("30 s left", DownloadText.left(item(DownloadState.Active, 100, 40, 2).copy(size = 100_000_000, downloaded = 40_000_000, bytesPerSecond = 2_000_000)))
        assertEquals("2 min left", DownloadText.left(item(DownloadState.Active, 100_000_000, 40_000_000, 500_000)))
        assertNull(DownloadText.left(item(DownloadState.Active, -1, 40, 5)))
        assertNull(DownloadText.left(item(DownloadState.Paused, 100, 40, 5)))
    }

    @Test fun namesComeFromTheFileThenTheGivenNameThenTheUrl() {
        assertEquals("app.apk", DownloadText.name(item(DownloadState.Queued)))
        assertEquals("Mine", DownloadText.name(item(DownloadState.Queued, name = "Mine")))
        assertEquals("real.apk", DownloadText.name(item(DownloadState.Done, name = "Mine", file = "real.apk")))
    }

    @Test fun controlCenterValue() {
        assertNull(DownloadText.summary(listOf(item(DownloadState.Paused))))
        assertEquals("3.0 MB/s", DownloadText.summary(listOf(item(DownloadState.Active, bps = 3_000_000), item(DownloadState.Done))))
        assertEquals("2 active", DownloadText.summary(listOf(item(DownloadState.Active, bps = 1), item(DownloadState.Active, bps = 1))))
    }

    @Test fun apksAreRecognisedByName() {
        assertEquals(true, DownloadText.isApk(item(DownloadState.Done, file = "App.APK")))
        assertEquals(false, DownloadText.isApk(item(DownloadState.Done, file = "movie.mp4")))
    }
}
