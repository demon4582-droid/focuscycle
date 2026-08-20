package com.focuscycle.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

// Persistence logic
val Context.dataStore by preferencesDataStore(name = "focus_prefs")

object FocusSettings {
    const val INSTAGRAM = "com.instagram.android"
    val STATE = stringPreferencesKey("state")
    val REMAINING = longPreferencesKey("remaining")
    val COOLDOWN_END = longPreferencesKey("cooldown_end")
    const val ALLOWED_MS = 5 * 60 * 1000L
    const val COOLDOWN_MS = 40 * 60 * 1000L
}

// 1. ACCESSIBILITY SERVICE (Detection & Blocking)
class FocusService : AccessibilityService() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var trackingJob: Job? = null
    private var lastPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        
        scope.launch {
            val prefs = dataStore.data.first()
            val state = prefs[FocusSettings.STATE] ?: "AVAILABLE"
            val cooldownEnd = prefs[FocusSettings.COOLDOWN_END] ?: 0L
            val now = System.currentTimeMillis()

            // If cooldown ended while app was closed, reset state
            if (state == "COOLDOWN" && now >= cooldownEnd) {
                resetToAvailable()
            }

            // Blocking logic
            if (state == "COOLDOWN" && now < cooldownEnd && pkg == FocusSettings.INSTAGRAM) {
                val intent = Intent(this@FocusService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                return@launch
            }

            // Usage tracking logic
            if (pkg == FocusSettings.INSTAGRAM) {
                startTracking()
            } else {
                stopTracking()
            }
            lastPackage = pkg
        }
    }

    private fun startTracking() {
        if (trackingJob?.isActive == true) return
        trackingJob = scope.launch {
            while (isActive) {
                val prefs = dataStore.data.first()
                val currentRemaining = prefs[FocusSettings.REMAINING] ?: FocusSettings.ALLOWED_MS
                val next = currentRemaining - 1000L
                
                dataStore.edit { it[FocusSettings.REMAINING] = next }

                if (next <= 0) {
                    dataStore.edit {
                        it[FocusSettings.STATE] = "COOLDOWN"
                        it[FocusSettings.COOLDOWN_END] = System.currentTimeMillis() + FocusSettings.COOLDOWN_MS
                    }
                    stopTracking()
                    // Force redirect
                    val intent = Intent(this@FocusService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(intent)
                    break
                }
                delay(1000)
            }
        }
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
    }

    private suspend fun resetToAvailable() {
        dataStore.edit {
            it[FocusSettings.STATE] = "AVAILABLE"
            it[FocusSettings.REMAINING] = FocusSettings.ALLOWED_MS
        }
    }

    override fun onInterrupt() {}
}

// 2. NOTIFICATION LISTENER (Silence alerts during cooldown)
class FocusNotificationService : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == FocusSettings.INSTAGRAM) {
            scope.launch {
                val state = dataStore.data.map { it[FocusSettings.STATE] }.first()
                if (state == "COOLDOWN") {
                    cancelNotification(sbn.key)
                }
            }
        }
    }
}

// 3. MAIN DASHBOARD UI
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs by dataStore.data.collectAsState(initial = null)
            val state = prefs?.get(FocusSettings.STATE) ?: "AVAILABLE"
            val remaining = prefs?.get(FocusSettings.REMAINING) ?: FocusSettings.ALLOWED_MS
            val cooldownEnd = prefs?.get(FocusSettings.COOLDOWN_END) ?: 0L
            
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while(true) {
                    now = System.currentTimeMillis()
                    delay(1000)
                }
            }

            val isCooldown = state == "COOLDOWN" && now < cooldownEnd
            val timeLeft = if (isCooldown) (cooldownEnd - now) else remaining

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("FocusCycle", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Target: Instagram", color = Color.Gray)
                    
                    Spacer(modifier = Modifier.height(40.dp))

                    val cardColor = if (isCooldown) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = cardColor)
                    ) {
                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (isCooldown) "STATUS: LOCKED" else "STATUS: AVAILABLE")
                            Text(
                                text = formatTime(timeLeft),
                                fontSize = 56.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("1. Enable Focus Guard")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("2. Silence Notifications")
                    }
                    Text("Both required to work", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val m = TimeUnit.MILLISECONDS.toMinutes(ms.coerceAtLeast(0))
        val s = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0)) % 60
        return String.format("%02d:%02d", m, s)
    }
}
