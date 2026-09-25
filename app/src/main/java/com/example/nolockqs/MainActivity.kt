package com.example.nolockqs

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
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
        updateModuleStatus()
    }

    private fun updateModuleStatus() {
        val active = isModuleActive()
        val color = getColorStateList(if (active) R.color.status_active else R.color.status_inactive)
        binding.statusIcon.setImageResource(if (active) R.drawable.ic_status_active else R.drawable.ic_status_inactive)
        binding.statusIcon.imageTintList = color
        binding.statusValue.setText(if (active) R.string.status_active else R.string.status_inactive)
        binding.statusValue.setTextColor(color)
        binding.setupHint.setText(if (active) R.string.setup_hint_active else R.string.setup_hint_inactive)
    }

    /** Returns false here; [NoLockXposedModule] hooks it to return true inside this app's process. */
    fun isModuleActive(): Boolean = false
}
