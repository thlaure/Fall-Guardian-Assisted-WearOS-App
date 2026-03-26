package com.fallguardian

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*

class MainActivity : ComponentActivity() {

    // Receives /cancel_alert messages while the Activity is in the foreground.
    // WearableListenerService is unreliable in the emulator for phone→watch direction;
    // MessageClient.addListener works as long as the Activity is alive.
    private val cancelAlertListener = MessageClient.OnMessageReceivedListener { messageEvent ->
        if (messageEvent.path == "/cancel_alert") {
            Log.d("MainActivity", "cancelAlertListener: received /cancel_alert")
            WearDataSender.cancelAlertFromPhone()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            WearDataSender.permissionDenied = false
            startForegroundService(Intent(this, FallDetectionService::class.java))
        } else {
            WearDataSender.permissionDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Wearable.getMessageClient(this).addListener(cancelAlertListener)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, FallDetectionService::class.java))
        } else {
            requestPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
        }
        setContent { WearApp() }
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(cancelAlertListener)
        super.onDestroy()
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val alertActive = WearDataSender.alertActive
    val permissionDenied = WearDataSender.permissionDenied
    MaterialTheme {
        when {
            permissionDenied -> PermissionDeniedScreen(context)
            alertActive -> AlertScreen(context)
            else -> IdleScreen(context)
        }
    }
}

@Composable
private fun PermissionDeniedScreen(context: Context) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Sensor access\nrequired",
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Chip(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                },
                label = { Text("Open Settings", fontSize = 11.sp) },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF23254A))
            )
        }
    }
}

@Composable
private fun AlertScreen(context: Context) {
    val remaining = WearDataSender.remainingSeconds
    val vibrator = context.getSystemService(Vibrator::class.java)

    // Haptic every second — stronger under 10 s
    LaunchedEffect(remaining) {
        if (remaining in 1..29) {
            val effect = if (remaining <= 10)
                VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
            else
                VibrationEffect.createOneShot(40, 80)
            vibrator?.vibrate(effect)
        }
    }

    // Flash overlay pulses between 0 and 0.4 opacity under 10 s
    val flashAlpha by if (remaining <= 10) {
        rememberInfiniteTransition(label = "flash").animateFloat(
            initialValue = 0f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(400), repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
    } else {
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableFloatStateOf(0f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A0000))
            .background(Color.Red.copy(alpha = flashAlpha))
            .clickable { WearDataSender.sendCancelAlert(context) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$remaining",
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap anywhere to cancel",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun IdleScreen(context: Context) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(Color(0xFF23254A), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = Color(0xFF5DEBB8),
                    modifier = Modifier.size(30.dp)
                )
            }
            Text(
                text = "Fall Guardian",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Monitoring active",
                color = Color(0xFF4CAF50),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(4.dp))
                Chip(
                    onClick = { simulateFall(context) },
                    label = {
                        Text(
                            text = "Simulate Fall (debug)",
                            fontSize = 11.sp,
                            color = Color(0xFFFFAB40)
                        )
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1A1A2E)),
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = Color(0xFFFFAB40),
                        shape = RoundedCornerShape(50)
                    )
                )
            }
        }
    }
}

private fun simulateFall(context: Context) {
    WearDataSender.sendFallEvent(context, System.currentTimeMillis())
}
