package io.novafoundation.nova.common.data.network.signed

import android.content.Context
import android.util.Base64

/**
 * Root 1 of pezkuwi-wallet-utils signed/root.json, byte for byte, carried by the APK. Root keys (2 of 3, wallet
 * accounts): 5HWSvCZBVQEyLoEgZWSWksM1hqCE4UsAhwpzJpJPvS5og9gT, 5EcFdcEPd4qo3KTmTqMw64kXyW1titsde5BRboeub7FevbvS,
 * 5FKfqRce9YAB5dEdr1YP6x16sVT1nYcJjfepF78EaD2wemkn. Targets key: ed25519 6066e6a8…ab5a. Expires 2031-10-31.
 */
const val BUNDLED_CONFIG_ROOT_ASSET = "signed_config/root.json"

fun Context.bundledConfigRoot(): ByteArray = assets.open(BUNDLED_CONFIG_ROOT_ASSET).use { it.readBytes() }

class PreferencesSignedConfigStore(context: Context) : SignedConfigStore {

    private val preferences = context.getSharedPreferences("signed_config", Context.MODE_PRIVATE)

    override var trustedRoot: ByteArray?
        get() = preferences.getString(ROOT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) = preferences.edit().putString(ROOT, value?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()

    override var targetsVersion: Long?
        get() = if (preferences.contains(TARGETS_VERSION)) preferences.getLong(TARGETS_VERSION, 0) else null
        set(value) = preferences.edit().apply { if (value == null) remove(TARGETS_VERSION) else putLong(TARGETS_VERSION, value) }.apply()

    override var clockFloor: Long
        get() = preferences.getLong(CLOCK_FLOOR, 0)
        set(value) = preferences.edit().putLong(CLOCK_FLOOR, value).apply()

    private companion object {
        const val ROOT = "trusted_root"
        const val TARGETS_VERSION = "targets_version"
        const val CLOCK_FLOOR = "clock_floor"
    }
}
