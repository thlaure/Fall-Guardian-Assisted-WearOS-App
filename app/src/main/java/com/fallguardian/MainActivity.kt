package com.fallguardian

import android.content.Context
import android.content.Intent
import com.fallguardian.BuildConfig
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start detection service
        startForegroundService(
            Intent(this, FallDetectionService::class.java)
        )

        setContent {
            WearApp()
        }
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    MaterialTheme {
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
                    text = "🛡",
                    fontSize = 32.sp
                )
                Text(
                    text = "Fall Guardian",
                    color = Color.White,
                    fontSize = 14.sp,
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
                                text = "🐛 Simulate Fall (debug)",
                                fontSize = 11.sp,
                                color = Color(0xFFFFAB40)
                            )
                        },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = Color(0xFF1A1A2E)
                        ),
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
}

private fun simulateFall(context: Context) {
    WearDataSender.sendFallEvent(context, System.currentTimeMillis())
}
