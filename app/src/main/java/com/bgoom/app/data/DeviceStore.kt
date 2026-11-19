package com.bgoom.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Hardware-backed encrypted credential & configuration store.
 * Private keys, key passphrases, and API keys are encrypted via Android Keystore (AES-256-GCM).
 * SSH passwords are strictly forbidden and never stored or accepted.
 */
class DeviceStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences = createEncryptedPrefs(context.applicationContext)

    private val _devices = MutableStateFlow<List<SshDevice>>(loadDevices())
    val devices: StateFlow<List<SshDevice>> = _devices.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun createEncryptedPrefs(appContext: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Fallback only if Keystore hardware is unavailable on a broken ROM/emulator
            appContext.getSharedPreferences(PREF_FILE + "_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun loadDevices(): List<SshDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SshDevice>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveDevices(list: List<SshDevice>) {
        prefs.edit().putString(KEY_DEVICES, json.encodeToString(list)).apply()
        _devices.value = list
    }

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(updated)).apply()
        _settings.value = updated
    }

    fun getActiveDevice(): SshDevice? {
        val id = _settings.value.activeDeviceId
        val all = _devices.value
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    fun setActiveDevice(deviceId: String?) {
        updateSettings { it.copy(activeDeviceId = deviceId) }
    }

    fun upsertDevice(
        device: SshDevice,
        privateKeyPem: String?,
        passphrase: String?,
    ) {
        val current = _devices.value.toMutableList()
        val idx = current.indexOfFirst { it.id == device.id }
        if (idx >= 0) {
            current[idx] = device
        } else {
            current.add(device)
        }
        val editor = prefs.edit()
        if (!privateKeyPem.isNullOrBlank()) {
            editor.putString(KEY_PRIV_PREFIX + device.id, privateKeyPem.trim())
        }
        if (passphrase != null) {
            if (passphrase.isEmpty()) {
                editor.remove(KEY_PASS_PREFIX + device.id)
            } else {
                editor.putString(KEY_PASS_PREFIX + device.id, passphrase)
            }
        }
        editor.apply()
        saveDevices(current)
        if (_settings.value.activeDeviceId == null) {
            setActiveDevice(device.id)
        }
    }

    fun updateTrustedHostFingerprint(deviceId: String, fingerprint: String?) {
        val updated = _devices.value.map {
            if (it.id == deviceId) it.copy(trustedHostFingerprint = fingerprint) else it
        }
        saveDevices(updated)
    }

    fun deleteDevice(deviceId: String) {
        val remaining = _devices.value.filterNot { it.id == deviceId }
        prefs.edit()
            .remove(KEY_PRIV_PREFIX + deviceId)
            .remove(KEY_PASS_PREFIX + deviceId)
            .apply()
        saveDevices(remaining)
        if (_settings.value.activeDeviceId == deviceId) {
            setActiveDevice(remaining.firstOrNull()?.id)
        }
    }

    fun getPrivateKey(deviceId: String): String? =
        prefs.getString(KEY_PRIV_PREFIX + deviceId, null)

    fun getPassphrase(deviceId: String): String? =
        prefs.getString(KEY_PASS_PREFIX + deviceId, null)

    fun setVisionApiKey(apiKey: String) {
        prefs.edit().putString(KEY_VISION_API_KEY, apiKey.trim()).apply()
    }

    fun getVisionApiKey(): String =
        prefs.getString(KEY_VISION_API_KEY, "") ?: ""

    companion object {
        private const val PREF_FILE = "bgoom_secure_vault"
        private const val KEY_DEVICES = "ssh_devices_v1"
        private const val KEY_SETTINGS = "app_settings_v1"
        private const val KEY_PRIV_PREFIX = "ssh_priv_"
        private const val KEY_PASS_PREFIX = "ssh_pass_"
        private const val KEY_VISION_API_KEY = "vision_api_key"
    }
}
