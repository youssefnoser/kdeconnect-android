/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mirrorphone

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

/**
 * Connection, developer settings, pairing and tools, as on the desktop's settings page.
 * The desktop's side arrives with [MirrorPhonePlugin.desktopStatus] while this page is open.
 */
class MirrorPhoneSettingsFragment : PluginSettingsFragment() {
    private val mirrorPhone: MirrorPhonePlugin
        get() = plugin as MirrorPhonePlugin

    private lateinit var connection: Preference
    private lateinit var developerOptions: Preference
    private lateinit var usbDebugging: Preference
    private lateinit var wirelessDebugging: Preference
    private lateinit var canEnable: Preference
    private lateinit var pairing: Preference
    private lateinit var adb: Preference
    private lateinit var scrcpy: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        val screen = preferenceManager.createPreferenceScreen(preferenceManager.context)
        preferenceScreen = screen

        val connectionCategory = screen.category(R.string.mirrorphone_settings_connection)
        connection = connectionCategory.info(R.string.mirrorphone_settings_status)

        val deviceCategory = screen.category(R.string.mirrorphone_settings_this_device)
        developerOptions = deviceCategory.info(R.string.mirrorphone_settings_developer_options)
        usbDebugging = deviceCategory.info(R.string.mirrorphone_settings_usb_debugging)
        wirelessDebugging = deviceCategory.info(R.string.mirrorphone_settings_wireless_debugging)
        canEnable = deviceCategory.info(R.string.mirrorphone_settings_can_enable)

        pairing = screen.category(R.string.mirrorphone_settings_pairing).info(R.string.mirrorphone_settings_pairing_state)

        val toolsCategory = screen.category(R.string.mirrorphone_settings_tools)
        adb = toolsCategory.info(R.string.mirrorphone_settings_adb)
        scrcpy = toolsCategory.info(R.string.mirrorphone_settings_scrcpy)
    }

    override fun onResume() {
        super.onResume()
        mirrorPhone.statusListener = ::update
        mirrorPhone.requestDesktopStatus()
        update()
    }

    override fun onPause() {
        mirrorPhone.statusListener = null
        super.onPause()
    }

    private fun update() {
        developerOptions.summary = onOff(mirrorPhone.developerOptions)
        usbDebugging.summary = onOff(mirrorPhone.usbDebugging)
        wirelessDebugging.summary = onOff(mirrorPhone.wirelessDebugging)
        canEnable.summary = getString(
            if (mirrorPhone.canEnable) R.string.mirrorphone_settings_can_enable_yes else R.string.mirrorphone_settings_can_enable_no
        )

        val desktop = mirrorPhone.desktopStatus
        if (desktop == null) {
            val waiting = getString(R.string.mirrorphone_settings_waiting, device.name)
            listOf(connection, pairing, adb, scrcpy).forEach { it.summary = waiting }
            return
        }
        connection.summary = when {
            desktop.busy -> desktop.message.ifEmpty { getString(R.string.mirrorphone_settings_connecting) }
            desktop.transport == "usb" -> getString(R.string.mirrorphone_settings_connected_usb)
            desktop.transport == "wifi" -> getString(R.string.mirrorphone_settings_connected_wifi)
            else -> desktop.message.ifEmpty { getString(R.string.mirrorphone_settings_not_connected) }
        }
        pairing.summary = desktop.pairingMessage.ifEmpty { getString(R.string.mirrorphone_settings_not_pairing) }
        adb.summary = desktop.adb.ifEmpty { getString(R.string.mirrorphone_settings_not_installed) }
        scrcpy.summary = desktop.scrcpy.ifEmpty { getString(R.string.mirrorphone_settings_not_installed) }
    }

    private fun onOff(on: Boolean): String = getString(if (on) R.string.mirrorphone_settings_on else R.string.mirrorphone_settings_off)

    private fun PreferenceScreen.category(@StringRes title: Int): PreferenceCategory {
        val category = PreferenceCategory(context)
        category.setTitle(title)
        category.isIconSpaceReserved = false
        addPreference(category)
        return category
    }

    private fun PreferenceCategory.info(@StringRes title: Int): Preference {
        val preference = Preference(context)
        preference.setTitle(title)
        preference.isSelectable = false
        preference.isIconSpaceReserved = false
        addPreference(preference)
        return preference
    }

    companion object {
        fun newInstance(pluginKey: String, deviceId: String): MirrorPhoneSettingsFragment =
            MirrorPhoneSettingsFragment().apply { setArguments(pluginKey, deviceId) }
    }
}
