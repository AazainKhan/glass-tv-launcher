package dev.glasslauncher.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** A disk that cannot take a write (full storage) must leave the saved config alone and never throw into the launcher (a crash found by the monkey on a full /data). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConfigStoreFullDiskTest {
    @Test fun aFailedWriteDoesNotThrowAndKeepsTheSavedConfig() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = ConfigStore(context, scope)
            assertTrue(runBlocking { store.update { it.copy(boldText = true) } })
            val dir = File(context.filesDir, "datastore")
            assertTrue("the store writes into ${dir.path}", dir.isDirectory)
            // A directory nothing can be created in: the write's temp file fails with an IOException, as ENOSPC does.
            check(dir.setWritable(false)) { "cannot make the directory read-only here" }
            try {
                val writes = store.writes
                assertFalse(runBlocking { store.update { it.copy(boldText = false) } })
                assertEquals("no write was counted", writes, store.writes)
                assertTrue("the saved config is unchanged", runBlocking { kotlinx.coroutines.flow.flowOf(store.config.value).let { store.config.value.boldText } })
            } finally { dir.setWritable(true) }
        } finally { scope.cancel() }
    }
}
