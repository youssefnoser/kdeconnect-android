/*
 * SPDX-FileCopyrightText: 2026 Youssef Noser <youssefnoser1i@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mirrorphone

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect_tp.R

/**
 * Asks the user to unlock this device, over an app the desktop opened on a virtual display while the device was
 * locked: the app shows but takes no input until then, and the lock screen cannot be used from the desktop, as
 * Android hides it from screen capture. The desktop starts this over adb on that display; once the device is
 * unlocked, the lock opens and this closes.
 */
class UnlockPromptActivity : AppCompatActivity() {
    private var unlocked by mutableStateOf(false)
    private var receiverRegistered = false

    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            unlocked = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Unlocked before this could show: nothing to ask
        if (!isLocked()) {
            finish()
            return
        }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val label = packageName?.let {
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)
        }
        // The app's display sleeps with the phone, and shows nothing then
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            KdeTheme(this) {
                UnlockPromptScreen(label, unlocked)
                if (unlocked) {
                    LaunchedEffect(Unit) {
                        delay(CLOSE_DELAY_MS)
                        finish()
                    }
                }
            }
        }
        // Exported: SystemUI sends USER_PRESENT under its own uid, not the system's, so a receiver that is not exported
        // never gets it. It is a protected broadcast, which other apps cannot send.
        ContextCompat.registerReceiver(this, userPresent, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (!isLocked()) {
            unlocked = true
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(userPresent)
        }
        super.onDestroy()
    }

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    companion object {
        const val EXTRA_PACKAGE = "package"
        private const val CLOSE_DELAY_MS = 1400L
    }
}

@Composable
private fun UnlockPromptScreen(appLabel: String?, unlocked: Boolean) {
    // The shackle pops up, overshooting a little as if sprung free, then flips open to the left
    val pop = remember { Animatable(0f) }
    val swing = remember { Animatable(0f) }
    LaunchedEffect(unlocked) {
        if (unlocked) {
            launch { pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow)) }
            delay(SWING_DELAY_MS)
            swing.animateTo(1f, tween(durationMillis = 550, easing = FastOutSlowInEasing))
        }
    }
    val progress = pop.value.coerceIn(0f, 1f)
    val scale by animateFloatAsState(
        targetValue = if (unlocked) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "bounce",
    )
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Lock(
                pop = pop.value,
                swing = swing.value,
                color = lerp(colors.primary, colors.tertiary, progress),
                keyholeColor = colors.surface,
                modifier = Modifier.size(128.dp).scale(scale),
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(if (unlocked) R.string.mirrorphone_unlock_done else R.string.mirrorphone_unlock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            // Kept in the layout once unlocked, so that the lock and the title do not move
            Text(
                text = if (appLabel != null) {
                    stringResource(R.string.mirrorphone_unlock_text_app, appLabel)
                } else {
                    stringResource(R.string.mirrorphone_unlock_text)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(1f - progress),
            )
        }
    }
}

private const val SWING_DELAY_MS = 220L

/** A padlock whose shackle pops up as [pop] goes from 0 to 1, and flips open to the left as [swing] does */
@Composable
private fun Lock(pop: Float, swing: Float, color: Color, keyholeColor: Color, modifier: Modifier = Modifier) {
    Box(modifier) {
        // In a layer of its own, to turn in 3D about its left leg as a real shackle does; under the body, which hides
        // the bottom of its legs
        Canvas(
            Modifier.matchParentSize().graphicsLayer {
                translationY = -size.minDimension * 0.14f * pop
                rotationY = 180f * swing
                transformOrigin = TransformOrigin(0.3f, 0.5f)
                cameraDistance = 12f * density
            }
        ) {
            val s = size.minDimension
            val shackle = Path().apply {
                moveTo(s * 0.3f, s * 0.52f)
                lineTo(s * 0.3f, s * 0.32f)
                arcTo(Rect(s * 0.3f, s * 0.12f, s * 0.7f, s * 0.52f), 180f, 180f, false)
                lineTo(s * 0.7f, s * 0.52f)
            }
            drawPath(shackle, color, style = Stroke(width = s * 0.09f, cap = StrokeCap.Round))
        }
        Canvas(Modifier.matchParentSize()) {
            val s = size.minDimension
            drawRoundRect(color, topLeft = Offset(s * 0.15f, s * 0.45f), size = Size(s * 0.7f, s * 0.5f), cornerRadius = CornerRadius(s * 0.1f))
            drawCircle(keyholeColor, radius = s * 0.06f, center = Offset(s * 0.5f, s * 0.66f))
            drawRoundRect(keyholeColor, topLeft = Offset(s * 0.475f, s * 0.68f), size = Size(s * 0.05f, s * 0.12f), cornerRadius = CornerRadius(s * 0.025f))
        }
    }
}
