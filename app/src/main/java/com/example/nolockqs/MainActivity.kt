package com.example.nolockqs

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Switch
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.example.nolockqs.databinding.ActivityMainBinding
import com.example.nolockqs.databinding.ItemFeatureBinding
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {

    /** A protection's box on this screen and the switch in [FeatureSettings] it controls. */
    private class Feature(
        val key: String,
        val views: ItemFeatureBinding,
        @param:DrawableRes val icon: Int,
        @param:StringRes val title: Int,
        @param:StringRes val body: Int,
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var features: List<Feature>

    private val app get() = application as NoLockApplication

    /** The last known state of the switches, shown until the framework connects. */
    private val savedSwitches by lazy { getSharedPreferences("feature_switches", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The window is edge-to-edge: keep the content clear of the system bars and camera cutout.
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val versionName = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        binding.version.text = getString(R.string.version_format, versionName.orEmpty())
        binding.patreonButton.setOnClickListener { openLink(getString(R.string.patreon_url)) }

        features = listOf(
            Feature(
                FeatureSettings.BLOCK_QUICK_SETTINGS, binding.quickSettings,
                R.drawable.ic_quick_settings, R.string.feature_qs_title, R.string.feature_qs_body,
            ),
            Feature(
                FeatureSettings.BLOCK_POWER_MENU, binding.powerMenu,
                R.drawable.ic_power_menu, R.string.feature_power_title, R.string.feature_power_body,
            ),
        )
        features.forEach(::setUpFeature)
    }

    override fun onStart() {
        super.onStart()
        app.onFeatureSettingsChanged = ::onFeatureSettingsChanged
        onFeatureSettingsChanged()
    }

    override fun onStop() {
        app.onFeatureSettingsChanged = null
        super.onStop()
    }

    private fun setUpFeature(feature: Feature) = with(feature.views) {
        featureIcon.setImageResource(feature.icon)
        featureTitle.setText(feature.title)
        featureBody.setText(feature.body)
        root.setOnClickListener { toggle(feature.key) }
        // Screen readers announce the whole box as a switch.
        ViewCompat.setAccessibilityDelegate(root, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.checked = if (isOn(feature.key)) {
                    AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                } else {
                    AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
                }
            }
        })
    }

    /** Updates the saved copy of the switches from the framework, then the screen. */
    private fun onFeatureSettingsChanged() {
        app.featureSettings?.let { settings ->
            savedSwitches.edit {
                features.forEach { putBoolean(it.key, settings.getBoolean(it.key, FeatureSettings.DEFAULT_ON)) }
            }
        }
        render()
    }

    private fun toggle(key: String) {
        val settings = app.featureSettings
        if (settings == null) {
            Toast.makeText(this, R.string.switches_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val on = !isOn(key)
        settings.edit { putBoolean(key, on) }
        savedSwitches.edit { putBoolean(key, on) }
        render()
    }

    private fun isOn(key: String): Boolean =
        app.featureSettings?.getBoolean(key, FeatureSettings.DEFAULT_ON)
            ?: savedSwitches.getBoolean(key, FeatureSettings.DEFAULT_ON)

    private fun render() {
        features.forEach(::renderFeature)
        updateModuleStatus()
    }

    /** Green when on, grey when off; dimmed while the framework isn't connected to save changes. */
    private fun renderFeature(feature: Feature) = with(feature.views) {
        val on = isOn(feature.key)
        root.setCardBackgroundColor(getColor(if (on) R.color.feature_on_container else R.color.feature_off_container))
        root.alpha = if (app.featureSettings != null) 1f else 0.6f
        featureTitle.setTextColor(getColor(if (on) R.color.feature_on_content else R.color.feature_off_content))
        featureBody.setTextColor(getColor(if (on) R.color.feature_on_content else R.color.feature_off_content_variant))
        featureIcon.backgroundTintList = getColorStateList(if (on) R.color.feature_on_accent else R.color.feature_off_accent)
        featureIcon.imageTintList = getColorStateList(if (on) R.color.feature_on_on_accent else R.color.feature_off_on_accent)
        featureSwitch.isChecked = on
        ViewCompat.setStateDescription(root, getString(if (on) R.string.feature_state_on else R.string.feature_state_off))
    }

    private fun updateModuleStatus() {
        val active = isModuleActive()
        val quickSettings = isOn(FeatureSettings.BLOCK_QUICK_SETTINGS)
        val powerMenu = isOn(FeatureSettings.BLOCK_POWER_MENU)
        val protecting = active && (quickSettings || powerMenu)
        val container = getColorStateList(if (protecting) R.color.status_active_container else R.color.status_inactive_container)
        val content = getColorStateList(if (protecting) R.color.status_on_active_container else R.color.status_on_inactive_container)
        binding.statusCard.setCardBackgroundColor(container)
        binding.statusIcon.setImageResource(if (protecting) R.drawable.ic_shield_active else R.drawable.ic_shield_inactive)
        binding.statusIcon.imageTintList = content
        binding.statusTitle.setText(
            when {
                !active -> R.string.status_inactive_title
                protecting -> R.string.status_active_title
                else -> R.string.status_off_title
            },
        )
        binding.statusTitle.setTextColor(content)
        binding.statusBody.setText(
            when {
                !active -> R.string.status_inactive_body
                quickSettings && powerMenu -> R.string.status_active_body
                quickSettings -> R.string.status_active_body_qs
                powerMenu -> R.string.status_active_body_power
                else -> R.string.status_off_body
            },
        )
        binding.statusBody.setTextColor(content)
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (ignored: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    /** Returns false here; [NoLockXposedModule] hooks it to return true inside this app's process. */
    fun isModuleActive(): Boolean = false
}
