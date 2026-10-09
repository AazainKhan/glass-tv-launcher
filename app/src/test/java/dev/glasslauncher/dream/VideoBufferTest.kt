package dev.glasslauncher.dream

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoBufferTest {
    @Test fun buffersAFewSecondsAndKeepsNothingBehind() {
        assertTrue(VideoBuffer.MAX_MS <= 15_000)
        assertTrue(VideoBuffer.MIN_MS <= VideoBuffer.MAX_MS && VideoBuffer.START_MS <= VideoBuffer.MIN_MS)
        assertTrue(VideoBuffer.BACK_MS == 0)
    }

    /** Every player Glass builds uses it (Aerials and the motion background). */
    @Test fun everyPlayerUsesIt() {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        src.walkTopDown().filter { it.extension == "kt" && "ExoPlayer.Builder(" in it.readText() }.forEach { f ->
            assertTrue("${f.name} builds a player without VideoBuffer.loadControl()", "setLoadControl(" in f.readText() && "VideoBuffer.loadControl()" in f.readText())
        }
    }
}
