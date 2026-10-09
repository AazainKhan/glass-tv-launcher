package dev.glasslauncher.featured

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.size.Dimension
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Title logos decode at the size they're drawn, never at the source's full resolution (some are 2000+ px). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LogoRequestTest {
    @Test fun logosAreDecodedAtDisplaySize() = runBlocking {
        val req = logoRequest(ApplicationProvider.getApplicationContext(), "https://example.com/logo.png")
        val size = req.sizeResolver.size()
        val w = (size.width as? Dimension.Pixels)?.px
        val h = (size.height as? Dimension.Pixels)?.px
        assertTrue("logo decode size is $size", w != null && h != null && w <= 880 && h <= 300)
    }
}
