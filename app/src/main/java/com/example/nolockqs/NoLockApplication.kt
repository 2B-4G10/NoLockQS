package com.example.nolockqs

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Connects to the Xposed framework's service, which stores the feature switches where the hooks
 * in System UI and System Framework can read them. The framework only connects while NoLockQS is
 * enabled in it.
 */
class NoLockApplication : Application(), XposedServiceHelper.OnServiceListener {

    /** The framework's copy of the feature switches, or null while it isn't connected. */
    var featureSettings: SharedPreferences? = null
        private set

    /** Runs on the main thread whenever [featureSettings] connects or disconnects. */
    var onFeatureSettingsChanged: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        val settings = runCatching { service.getRemotePreferences(FeatureSettings.GROUP) }.getOrNull()
        mainHandler.post {
            featureSettings = settings
            onFeatureSettingsChanged?.invoke()
        }
    }

    override fun onServiceDied(service: XposedService) {
        mainHandler.post {
            featureSettings = null
            onFeatureSettingsChanged?.invoke()
        }
    }
}
