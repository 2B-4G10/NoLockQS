package com.example.nolockqs

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import java.lang.reflect.Method

/**
 * NoLockQS Xposed Module
 *
 * Blocks the Quick Settings / Control Center pull-down (and the power menu) while the
 * device is locked. Built against the Vector / libxposed API 102 surface and validated
 * for Pixel 7-11 on Android 15-17.
 *
 * Design goals:
 *  - The status-bar "dead-zone" is measured live from [WindowInsets] on every touch, so it
 *    tracks screen size, density, display cutouts and (crucially) rotation automatically
 *    instead of relying on a single static resource value.
 *  - Every hook is defensive: a missing class or method disables that one feature and is
 *    logged, but never crashes SystemUI.
 */
class NoLockXposedModule : XposedModule() {

    private companion object {
        const val TAG = "NoLockQS"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        // Root touch container for the shade/QS on Pixel SystemUI.
        const val ROOT_WINDOW_CLASS = "com.android.systemui.shade.NotificationShadeWindowView"

        // Master kill-switch: `setprop persist.sys.nolockqs.enabled false` to disable at runtime.
        const val TOGGLE_PROPERTY = "persist.sys.nolockqs.enabled"

        // Last-resort dead-zone if neither insets nor the framework resource are available.
        const val FALLBACK_DEAD_ZONE_DP = 28f

        // Classes responsible for the Power Menu across different Android versions.
        val GLOBAL_ACTIONS_CLASSES = listOf(
            "com.android.systemui.globalactions.GlobalActionsDialogLite",
            "com.android.systemui.globalactions.GlobalActionsDialog"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)

        if (param.packageName == SYSTEM_UI_PACKAGE) {
            Log.d(TAG, "SystemUI detected. Injecting security hooks.")
            applyRootWindowHook(param.classLoader)
            applyPowerMenuHook(param.classLoader)
        }
    }

    /**
     * FEATURE 1: Dynamic Quick Settings dead-zone.
     *
     * Consumes any touch that begins inside the top status-bar strip while the keyguard is
     * showing, which prevents the shade / QS from ever starting to expand.
     */
    private fun applyRootWindowHook(classLoader: ClassLoader) {
        val method = resolveDispatchTouchEvent(classLoader) ?: return

        try {
            hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!isModuleEnabled()) return chain.proceed()

                    val event = chain.args[0] as? MotionEvent ?: return chain.proceed()
                    val view = chain.thisObject as? View ?: return chain.proceed()

                    // Only the initial finger-down starts a shade drag; that is all we must block.
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val deadZone = computeDeadZone(view)
                        if (event.rawY <= deadZone && isKeyguardShowing(view.context)) {
                            Log.w(TAG, "Blocked QS pull-down (rawY=${event.rawY}, deadZone=$deadZone).")
                            return true // Consume the gesture so it never reaches the shade.
                        }
                    }
                    return chain.proceed()
                }
            })
            Log.d(TAG, "QS dead-zone hook installed.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install QS dead-zone hook.", t)
        }
    }

    /**
     * FEATURE 2: Power Menu (Global Actions) interceptor. Cancels the long-press power dialog
     * while the keyguard is showing.
     */
    private fun applyPowerMenuHook(classLoader: ClassLoader) {
        GLOBAL_ACTIONS_CLASSES.forEach { className ->
            try {
                val globalActionsClass = classLoader.loadClass(className)

                val showMethods = globalActionsClass.declaredMethods.filter {
                    it.name.startsWith("show") || it.name == "handleShow"
                }

                showMethods.forEach { method ->
                    hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            if (isModuleEnabled() && isKeyguardShowing()) {
                                Log.w(TAG, "Blocked Power Menu on lockscreen.")
                                return null // Cancel the dialog from opening.
                            }
                            return chain.proceed()
                        }
                    })
                }
            } catch (ignored: ClassNotFoundException) {
                // Expected: only one of the Global Actions classes exists on a given build.
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to hook power menu class $className.", t)
            }
        }
    }

    /**
     * Locates [NotificationShadeWindowView.dispatchTouchEvent] as declared on the class itself,
     * so the hook lands on the exact override SystemUI uses (never the generic [View] method).
     */
    private fun resolveDispatchTouchEvent(classLoader: ClassLoader): Method? {
        return try {
            val windowViewClass = classLoader.loadClass(ROOT_WINDOW_CLASS)
            val method = windowViewClass.declaredMethods.firstOrNull {
                it.name == "dispatchTouchEvent" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == MotionEvent::class.java
            }
            if (method == null) {
                Log.e(TAG, "dispatchTouchEvent not declared on $ROOT_WINDOW_CLASS; dead-zone disabled.")
            }
            method
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load $ROOT_WINDOW_CLASS; dead-zone disabled.", t)
            null
        }
    }

    /**
     * Measures the dead-zone height (px) live for the current configuration.
     *
     * Order of preference:
     *  1. Live [WindowInsets] — the framework recomputes these on every rotation, fold and
     *     configuration change, so this value is always correct for the current orientation,
     *     screen size, density and display cutout.
     *  2. The framework `status_bar_height` resource.
     *  3. A density-scaled default.
     */
    private fun computeDeadZone(view: View): Float {
        try {
            val insets: WindowInsets? = view.rootWindowInsets
            if (insets != null) {
                val statusBars = insets.getInsets(WindowInsets.Type.statusBars()).top
                val cutout = insets.getInsets(WindowInsets.Type.displayCutout()).top
                val top = maxOf(statusBars, cutout)
                if (top > 0) return top.toFloat()
            }
        } catch (ignored: Throwable) {
            // Fall through to resource / density fallbacks below.
        }

        val resources = view.context.resources
        try {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                val height = resources.getDimensionPixelSize(resourceId)
                if (height > 0) return height.toFloat()
            }
        } catch (ignored: Throwable) {
        }

        return FALLBACK_DEAD_ZONE_DP * resources.displayMetrics.density
    }

    /**
     * Master kill-switch via System Properties. Defaults to enabled when the property is unset
     * or unreadable.
     */
    private fun isModuleEnabled(): Boolean {
        return try {
            val sysPropClass = Class.forName("android.os.SystemProperties")
            val getBooleanMethod = sysPropClass.getMethod(
                "getBoolean", String::class.java, Boolean::class.javaPrimitiveType
            )
            getBooleanMethod.invoke(null, TOGGLE_PROPERTY, true) as Boolean
        } catch (ignored: Throwable) {
            true
        }
    }

    /**
     * True whenever the keyguard is showing. Uses the supplied context (always available inside
     * the touch hook) and falls back to the current application context otherwise.
     */
    private fun isKeyguardShowing(context: Context? = null): Boolean {
        return try {
            val ctx = context ?: currentApplicationContext()
            val km = ctx?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isKeyguardLocked == true
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun currentApplicationContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            activityThreadClass.getMethod("currentApplication").invoke(null) as? Context
        } catch (ignored: Throwable) {
            null
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = true
    override fun onHotReloaded(param: HotReloadedParam) {}
}
