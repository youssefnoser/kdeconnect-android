/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mirrorphone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

/**
 * Shows this phone's screen, or one of its apps, in a window on the desktop.
 *
 * The desktop uses scrcpy over adb, by USB or Wireless debugging. We report our developer
 * settings to the desktop as they change, so that its settings page can guide the user, and we
 * turn Wireless debugging on when asked, which works once the desktop granted us
 * WRITE_SECURE_SETTINGS over adb. Activities cannot be opened from the background, so the
 * desktop's "open on device" requests show notifications that lead to the settings.
 *
 * The settings page shows the desktop's side too (its adb connection, pairing and tools), which
 * the desktop sends while the page has asked for it.
 */
@LoadablePlugin
class MirrorPhonePlugin : Plugin() {
    override val displayName: String
        get() = context.getString(R.string.pref_plugin_mirrorphone)

    override val description: String
        get() = context.getString(R.string.pref_plugin_mirrorphone_desc)

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_RESPONSE, PACKET_TYPE_ADB, PACKET_TYPE_STATUS)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_REQUEST, PACKET_TYPE_ADB_STATUS)

    override val hasSettings: Boolean = true

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment =
        MirrorPhoneSettingsFragment.newInstance(pluginKey, device.deviceId)

    /** What the desktop reports for the settings page; null until it answered [requestDesktopStatus] */
    data class DesktopStatus(
        val transport: String,
        val busy: Boolean,
        val message: String,
        val pairingState: String,
        val pairingMessage: String,
        val adb: String,
        val scrcpy: String,
    )

    @Volatile
    var desktopStatus: DesktopStatus? = null
        private set

    /** Called on the main thread when [desktopStatus] or this device's developer settings change */
    var statusListener: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Several settings change together, e.g. turning Developer options off; report them once
    private val sendChangedStatus = Runnable {
        sendStatus()
        statusListener?.invoke()
    }
    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(sendChangedStatus)
            mainHandler.postDelayed(sendChangedStatus, 500)
        }
    }

    override fun onCreate() {
        val resolver = context.contentResolver
        for (name in listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Settings.Global.ADB_ENABLED, ADB_WIFI_ENABLED)) {
            resolver.registerContentObserver(Settings.Global.getUriFor(name), false, settingsObserver)
        }
        sendStatus()
    }

    override fun onDestroy() {
        context.contentResolver.unregisterContentObserver(settingsObserver)
        mainHandler.removeCallbacks(sendChangedStatus)
    }

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(context.getString(R.string.mirrorphone_mirror_button), R.drawable.ic_mirror_screen_24dp) { mirrorScreen() },
        PluginUiButton(context.getString(R.string.openapp_button), R.drawable.ic_open_app_24dp) { parentActivity ->
            parentActivity.startActivity(
                Intent(parentActivity, OpenAppActivity::class.java)
                    .putExtra(OpenAppActivity.EXTRA_DEVICE_ID, device.deviceId)
            )
        },
    )

    fun mirrorScreen() {
        val np = NetworkPacket(PACKET_TYPE_REQUEST)
        np["action"] = "mirror"
        device.sendPacket(np)
        toast(context.getString(R.string.mirrorphone_mirroring, device.name), Toast.LENGTH_SHORT)
    }

    fun requestDesktopStatus() {
        val np = NetworkPacket(PACKET_TYPE_REQUEST)
        np["action"] = "status"
        device.sendPacket(np)
    }

    /** [turnScreenOff]: this screen goes dark while the app is open on the desktop, which needs Android 15 */
    fun openApp(packageName: String, label: String, turnScreenOff: Boolean) {
        val np = NetworkPacket(PACKET_TYPE_REQUEST)
        np["action"] = "openApp"
        np["package"] = packageName
        np["label"] = label
        np["turnScreenOff"] = turnScreenOff
        device.sendPacket(np)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean = when (np.type) {
        PACKET_TYPE_RESPONSE -> {
            val success = np.getBoolean("success", false)
            if (success) {
                if (np.getString("action", "") == "openApp") {
                    toast(context.getString(R.string.openapp_opened, device.name), Toast.LENGTH_SHORT)
                }
            } else {
                toast(context.getString(R.string.mirrorphone_failed, np.getString("error", "")), Toast.LENGTH_LONG)
            }
            true
        }
        PACKET_TYPE_ADB -> {
            when (val action = np.getString("action", "")) {
                "status" -> sendStatus()
                "open" -> showSettingsNotification(np.getString("target", "developer"))
                "enable" -> enableWirelessDebugging()
                else -> Log.w(TAG, "Unknown adb action: $action")
            }
            true
        }
        PACKET_TYPE_STATUS -> {
            desktopStatus = DesktopStatus(
                transport = np.getString("transport", ""),
                busy = np.getBoolean("busy", false),
                message = np.getString("message", ""),
                pairingState = np.getString("pairingState", "idle"),
                pairingMessage = np.getString("pairingMessage", ""),
                adb = np.getString("adb", ""),
                scrcpy = np.getString("scrcpy", ""),
            )
            mainHandler.post { statusListener?.invoke() }
            true
        }
        else -> false
    }

    private fun globalSetting(name: String): Boolean = Settings.Global.getInt(context.contentResolver, name, 0) == 1

    val developerOptions: Boolean
        get() = globalSetting(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)

    val usbDebugging: Boolean
        get() = globalSetting(Settings.Global.ADB_ENABLED)

    val wirelessDebugging: Boolean
        get() = globalSetting(ADB_WIFI_ENABLED)

    val canEnable: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun statusPacket(): NetworkPacket {
        val np = NetworkPacket(PACKET_TYPE_ADB_STATUS)
        np["developerOptions"] = developerOptions
        np["usbDebugging"] = usbDebugging
        np["wirelessDebugging"] = wirelessDebugging
        np["canEnable"] = canEnable
        return np
    }

    private fun sendStatus() {
        if (isDeviceInitialized) {
            device.sendPacket(statusPacket())
        }
    }

    private fun enableWirelessDebugging() {
        val developerOptions = developerOptions
        // Turning Developer options on behind the user's back would be surprising; that stays theirs
        val enabled = developerOptions && canEnable && try {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not turn on Wireless debugging", e)
            false
        }
        Log.i(TAG, "Wireless debugging requested by ${device.name}; turned on: $enabled")
        // "enabled" marks this as the answer to the desktop's request
        val status = statusPacket()
        status["enabled"] = enabled
        if (!enabled) {
            if (developerOptions) {
                showSettingsNotification("developer")
                status["error"] = context.getString(R.string.mirrorphone_adb_off)
            } else {
                showSettingsNotification("about")
                status["error"] = context.getString(R.string.mirrorphone_developer_options_off)
            }
        }
        device.sendPacket(status)
    }

    @SuppressLint("MissingPermission") // Checked below
    private fun showSettingsNotification(target: String) {
        val (action, title, text) = if (target == "about") {
            Triple(
                Settings.ACTION_DEVICE_INFO_SETTINGS,
                context.getString(R.string.mirrorphone_developer_notification_title),
                context.getString(R.string.mirrorphone_developer_notification_text),
            )
        } else {
            Triple(
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                context.getString(R.string.mirrorphone_adb_notification_title),
                context.getString(R.string.mirrorphone_adb_notification_text, device.name),
            )
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            toast(text, Toast.LENGTH_LONG)
            return
        }
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun toast(message: String, length: Int) {
        mainHandler.post { Toast.makeText(context, message, length).show() }
    }

    companion object {
        const val PACKET_TYPE_REQUEST = "kdeconnect.mirrorphone.request"
        const val PACKET_TYPE_RESPONSE = "kdeconnect.mirrorphone.response"
        const val PACKET_TYPE_ADB = "kdeconnect.mirrorphone.adb"
        const val PACKET_TYPE_ADB_STATUS = "kdeconnect.mirrorphone.adb.status"
        const val PACKET_TYPE_STATUS = "kdeconnect.mirrorphone.status"

        // Settings.Global.ADB_WIFI_ENABLED is hidden from the SDK
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val NOTIFICATION_ID = 0x5c4ee7
        private const val TAG = "MirrorPhonePlugin"
    }
}
