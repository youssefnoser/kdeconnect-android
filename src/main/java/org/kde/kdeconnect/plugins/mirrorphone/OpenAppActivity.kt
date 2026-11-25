/*
 * SPDX-FileCopyrightText: 2026 Youssef Noser <youssefnoser1i@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mirrorphone

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect.ui.compose.KdeTopAppBar
import org.kde.kdeconnect_tp.R

data class LaunchableApp(val packageName: String, val label: String, val icon: ImageBitmap)

class OpenAppActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val device = deviceId?.let { KdeConnect.getInstance().getDevice(it) }
        val plugin = device?.getPlugin<MirrorPhonePlugin>() ?: run {
            finish()
            return
        }

        setContent {
            KdeTheme(this) {
                var screenOff by remember { mutableStateOf(plugin.preferences.getBoolean(PREF_SCREEN_OFF, false)) }
                OpenAppScreen(
                    deviceName = device.name,
                    screenOff = screenOff,
                    onScreenOffChange = {
                        screenOff = it
                        plugin.preferences.edit { putBoolean(PREF_SCREEN_OFF, it) }
                    },
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onPick = { app ->
                        plugin.openApp(app.packageName, app.label, screenOff)
                        Toast.makeText(this, getString(R.string.openapp_opening, app.label, device.name), Toast.LENGTH_SHORT).show()
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        private const val PREF_SCREEN_OFF = "openapp_screen_off"
    }
}

/** The apps with a launcher entry, one per package, sorted by name, with their icons, so that the list shows complete */
private fun loadLaunchableApps(pm: PackageManager, ownPackage: String): List<LaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return activities
        .map { it.activityInfo }
        .filter { it.packageName != ownPackage }
        .distinctBy { it.packageName }
        .map { LaunchableApp(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm).toBitmap(96, 96).asImageBitmap()) }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun OpenAppScreen(
    deviceName: String,
    screenOff: Boolean,
    onScreenOffChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onPick: (LaunchableApp) -> Unit,
) {
    val context = LocalContext.current
    val apps by produceState<List<LaunchableApp>?>(null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context.packageManager, context.packageName) }
    }
    var query by remember { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            KdeTopAppBar(
                title = stringResource(R.string.openapp_title),
                subTitle = deviceName,
                navIconOnClick = onBack,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.openapp_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
            // The app runs on a display of its own on the desktop, so this screen is not needed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onScreenOffChange(!screenOff) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.openapp_screen_off), modifier = Modifier.weight(1f))
                Switch(checked = screenOff, onCheckedChange = onScreenOffChange)
            }
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val filtered = remember(list, query) {
                    if (query.isBlank()) list else list.filter {
                        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(app, onClick = { onPick(app) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: LaunchableApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Column {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
