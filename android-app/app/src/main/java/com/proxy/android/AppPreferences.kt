package com.proxy.android

import android.content.Context

/** Persisted Android node settings shared by the activity and VPN service. */
object AppPreferences {
    private const val FILE = "simpleplane_settings"
    private const val HOST = "remote_host"
    private const val PORT = "remote_port"
    private const val KEY = "remote_key"
    private const val TLS = "remote_tls"

    const val DEFAULT_HOST = "54.172.101.190"
    const val DEFAULT_PORT = 9090

    data class Config(
        val host: String,
        val port: Int,
        val key: String,
        val tls: Boolean,
    )

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Config(
            host = prefs.getString(HOST, DEFAULT_HOST).orEmpty(),
            port = prefs.getInt(PORT, DEFAULT_PORT),
            key = prefs.getString(KEY, "").orEmpty(),
            tls = prefs.getBoolean(TLS, false),
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST, config.host)
            .putInt(PORT, config.port)
            .putString(KEY, config.key)
            .putBoolean(TLS, config.tls)
            .apply()
    }

    fun isUsable(config: Config): Boolean =
        config.host.isNotBlank() &&
            config.port in 1..65535 &&
            config.key.isNotBlank()
}
