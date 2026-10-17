package dev.glasslauncher

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.system.HomeSetup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Home role request only shows its dialog when started for a result; the plain Settings home picker does not need one. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeRoleRequestTest {
    @Test fun theRoleRequestIsStartedForAResult() {
        assertTrue(HomeSetup.needsResult(Intent(HomeSetup.ACTION_REQUEST_ROLE)))
    }

    @Test fun theSettingsHomePickerIsAPlainStart() {
        assertFalse(HomeSetup.needsResult(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)))
    }
}
