package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Test

/** P162: a failed launch says so in tvOS's plain voice. */
class LaunchNoticeTest {
    @Test fun itNamesTheApp() = assertEquals("Couldn’t open Netflix.", LaunchNotice.text("Netflix"))
    @Test fun aBlankLabelStillReads() = assertEquals("Couldn’t open the app.", LaunchNotice.text("  "))
}
