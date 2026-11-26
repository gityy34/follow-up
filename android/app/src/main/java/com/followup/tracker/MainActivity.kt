package com.followup.tracker

import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainActivity : ComponentActivity() {
    private val model: JourneyModel by viewModels()
    private var locked by mutableStateOf(true)
    private var stoppedAt = 0L
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var authentication = false
    private var incoming by mutableStateOf<String?>(null)
    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    private var lockNotice by mutableStateOf("")
    private val credentials = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        authentication = false
        if (it.resultCode == RESULT_OK) { locked = false; lastInteraction = SystemClock.elapsedRealtime() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onNewLink(intent)
        lifecycleScope.launch {
            try { importLegacy(model.repo); model.reminders.request() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retry reading the previous local store next launch. */ }
        }
        setContent {
            val workspace by model.state.collectAsState()
            val settings = workspace?.settings
            val dark = when (settings?.theme ?: ThemeMode.NEON) {
                ThemeMode.NEON, ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            JourneyTheme(dark, settings?.palette ?: "Violet", settings?.font ?: AppFont.DEFAULT) {
                val loading = workspace == null
                val hide = settings?.appLock == true && locked
                val privacy = loading || hide || settings?.privateRecents == true
                LaunchedEffect(hide, settings?.appLock, settings?.lockAfter) {
                    val minutes = settings?.lockAfter ?: 0
                    if (settings?.appLock != true || hide || minutes == 0) return@LaunchedEffect
                    while (isActive) {
                        delay(1000)
                        if (SystemClock.elapsedRealtime() - lastInteraction >= minutes * 60_000L) { locked = true; break }
                    }
                }
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
                    if (privacy) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    hide -> LockedPanel(lockNotice, ::unlock)
                    else -> JourneyScreen(model, requireNotNull(workspace), incoming, { incoming = null }) { keyguard.isDeviceSecure }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as JourneyApp).reminders.request()
        val preferences = model.state.value?.settings
        if (!authentication && preferences?.appLock == true && stoppedAt != 0L &&
            SystemClock.elapsedRealtime() - stoppedAt >= preferences.lockAfter * 60_000L) locked = true
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteraction = SystemClock.elapsedRealtime()
    }

    override fun onStop() {
        if (!authentication) stoppedAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onNewLink(intent)
    }

    private fun onNewLink(intent: Intent) {
        val uri = intent.data
        if (uri?.scheme == "youfollowups" && uri.host == "open") incoming = uri.lastPathSegment
    }

    private fun unlock() {
        if (!keyguard.isDeviceSecure) {
            lockNotice = "Set up a device screen lock in Android settings to unlock this app."
            return
        }
        if (authentication) return
        authentication = true
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricPrompt.Builder(this).setTitle("Unlock YoU Follow ups")
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                .build().authenticate(android.os.CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        authentication = false
                        locked = false
                        lastInteraction = SystemClock.elapsedRealtime()
                        lockNotice = ""
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        authentication = false
                        lockNotice = errString.toString()
                    }
                })
        } else {
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock YoU Follow ups", "Use your device screen lock")
            if (intent != null) credentials.launch(intent) else authentication = false
        }
    }
}

internal class JourneyModel(app: android.app.Application) : AndroidViewModel(app) {
    private val journey = app as JourneyApp
    val repo = journey.repository
    val reminders = journey.reminders
    val state = flow {
        repo.applyRestoreSettings()
        emitAll(repo.workspace)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
private fun LockedPanel(message: String, unlock: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("YoU Follow ups", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(if (message.isBlank()) "Use your device screen lock to continue." else message)
            Spacer(Modifier.height(20.dp))
            Button(onClick = unlock) { Text("Unlock") }
        }
    }
}
