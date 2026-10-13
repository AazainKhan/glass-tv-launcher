package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootProcessTest {
    private val empty = File.createTempFile("input",".bin")

    @Test fun aStuckProcessTimesOutInsteadOfBlockingOnItsOutput() {
        val t0 = System.nanoTime()
        val r = Root.runProcess(listOf("sh", "-c", "echo hi; sleep 20"), empty, 400)
        assertEquals(124, r.code)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 8_000)
    }

    @Test fun anOrdinaryRunReturnsItsExitCodeAndOutput() {
        val r = Root.runProcess(listOf("sh", "-c", "cat; echo done; exit 3"), empty.apply { writeText("x") }, 5_000)
        assertEquals(3, r.code)
        assertTrue(r.out.endsWith("done"))
    }

    @Test fun anInterruptIsATimeoutNotANoSuAndKeepsTheFlag() {
        var result: Root.Result? = null
        var flag = false
        val th = Thread { result = Root.runProcess(listOf("sh", "-c", "sleep 20"), empty, 30_000); flag = Thread.currentThread().isInterrupted }
        th.start(); Thread.sleep(300); th.interrupt(); th.join(10_000)
        assertEquals(124, result?.code)
        assertTrue(flag)
    }

    @Test fun aMissingBinaryIsNotStarted() {
        assertEquals(127, Root.runProcess(listOf("/nonexistent/su"), empty, 1_000).code)
    }
}
