package com.example.nolockqs

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.example.nolockqs.databinding.ActivityMainBinding
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
        updateModuleStatus()
    }

    private fun updateModuleStatus() {
        val active = isModuleActive()
        val container = getColorStateList(if (active) R.color.status_active_container else R.color.status_inactive_container)
        val content = getColorStateList(if (active) R.color.status_on_active_container else R.color.status_on_inactive_container)
        binding.statusCard.setCardBackgroundColor(container)
        binding.statusIcon.setImageResource(if (active) R.drawable.ic_shield_active else R.drawable.ic_shield_inactive)
        binding.statusIcon.imageTintList = content
        binding.statusTitle.setText(if (active) R.string.status_active_title else R.string.status_inactive_title)
        binding.statusTitle.setTextColor(content)
        binding.statusBody.setText(if (active) R.string.status_active_body else R.string.status_inactive_body)
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
