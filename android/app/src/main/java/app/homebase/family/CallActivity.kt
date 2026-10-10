package app.homebase.family

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.homebase.family.ui.CallOverlay
import app.homebase.family.ui.HomebaseTheme

/** Opens over the lock screen when a call comes in, shows the call, and goes away when the call is over. */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        val accept = intent?.getBooleanExtra("accept", false) == true
        setContent {
            HomebaseTheme {
                val call by hb.state.call.collectAsState()
                LaunchedEffect(call == null) { if (call == null) finish() }
                LaunchedEffect(accept) { if (accept && hb.state.call.value?.status == "ringing") hb.calls.accept() }
                call?.let { CallOverlay(it) }
            }
        }
    }
}
