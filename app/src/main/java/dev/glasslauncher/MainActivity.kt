package dev.glasslauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.glasslauncher.home.HomeModel
import dev.glasslauncher.home.HomeRequest
import dev.glasslauncher.home.HomeScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val model: HomeModel by viewModels()
    // Conflated channel: keeps a request that arrives before Home starts collecting (cold start).
    private val requests = Channel<HomeRequest>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeScreen(model, requests.receiveAsFlow()) }
        // The wallpaper covers the whole window; skipping the window background saves a full-screen fill per frame.
        window.setBackgroundDrawable(null)
        request(intent)
    }

    override fun onRestart() {
        super.onRestart()
        // No system animation on the way back: Glass draws the app closing into its tile itself.
        // Fire OS ignores the theme's window animations for the home task (it fades Home in from black
        // ~300 ms after the Home press), but honours a pending override.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed on Home goes back to the top; Home pressed in an app just returns, leaving focus on
        // the app's tile (that's where the close animation lands, as on tvOS).
        val onHome = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        if (intent.action == Intent.ACTION_MAIN) { if (onHome) requests.trySend(HomeRequest.Home) } else request(intent)
    }

    /** Remote buttons (RemoteKeysService) ask for Control Center or the app switcher this way. */
    private fun request(intent: Intent?) {
        when (intent?.action) {
            ACTION_CONTROL_CENTER -> requests.trySend(HomeRequest.ControlCenter)
            ACTION_APP_SWITCHER -> requests.trySend(HomeRequest.AppSwitcher)
            ACTION_TV_SETTINGS -> requests.trySend(HomeRequest.TvSettings)
        }
    }

    companion object {
        const val ACTION_CONTROL_CENTER = "dev.glasslauncher.action.CONTROL_CENTER"
        const val ACTION_APP_SWITCHER = "dev.glasslauncher.action.APP_SWITCHER"
        const val ACTION_TV_SETTINGS = "dev.glasslauncher.action.TV_SETTINGS"
    }
}
