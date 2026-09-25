package com.example.nolockqs

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.nolockqs.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val versionName = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        binding.version.text = getString(R.string.version_format, versionName.orEmpty())
        updateModuleStatus()
    }

    private fun updateModuleStatus() {
        val active = isModuleActive()
        binding.statusValue.setText(if (active) R.string.status_active else R.string.status_inactive)
        binding.statusValue.setTextColor(
            ContextCompat.getColor(this, if (active) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
    }

    /** Returns false here; [NoLockXposedModule] hooks it to return true inside this app's process. */
    fun isModuleActive(): Boolean = false
}
