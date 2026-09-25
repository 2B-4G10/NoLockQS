package com.example.nolockqs

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * NoLockQS Xposed module (libxposed API 101+).
 *
 * While the device is locked it keeps these out of reach:
 *  - Quick Settings: in SystemUI, any gesture that starts in the status-bar strip of the shade
 *    window is consumed, so the shade can never start to expand.
 *  - The power menu: in system_server, every trigger (power key, key chords, accessibility) is
 *    refused before SystemUI or the legacy dialog is asked to show it. SystemUI refuses it too,
 *    as a fallback for when the System Framework scope is not enabled.
 *
 * Nothing is tied to a device model or an Android release. Hook targets are resolved from ordered
 * lists of known class and method names, a missing target only disables its own layer (and is
 * logged), and the dead-zone is measured from live [WindowInsets], so it follows rotation,
 * cutouts, density and screen size.
 */
class NoLockXposedModule : XposedModule() {

    private companion object {
        const val TAG = "NoLockQS"

        /** Master kill-switch: `setprop persist.sys.nolockqs.enabled false` (as root) pauses every layer. */
        const val TOGGLE_PROPERTY = "persist.sys.nolockqs.enabled"

        /** Last-resort dead-zone height when neither insets nor the framework dimen are available. */
        const val FALLBACK_DEAD_ZONE_DP = 28f

        val SYSTEM_UI_PACKAGES = setOf("com.android.systemui", "com.google.android.systemui")

        /** Root views of SystemUI's notification-shade window: legacy shade, scene container, their base. */
        val SHADE_ROOT_CLASSES = listOf(
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.scene.ui.view.SceneWindowRootView",
            "com.android.systemui.scene.ui.view.WindowRootView",
            "com.android.systemui.statusbar.phone.NotificationShadeWindowView",
        )

        /** Methods that open the power menu in system_server, keyed by declaring class. */
        val SYSTEM_SERVER_POWER_MENU = mapOf(
            "com.android.server.policy.PhoneWindowManager" to setOf("showGlobalActionsInternal"),
            "com.android.server.policy.GlobalActions" to setOf("showDialog"),
            "com.android.server.policy.LegacyGlobalActions" to setOf("showDialog"),
        )

        /** Methods that open the power menu in SystemUI, keyed by declaring class. */
        val SYSTEM_UI_POWER_MENU = mapOf(
            "com.android.systemui.globalactions.GlobalActionsComponent" to setOf("handleShowGlobalActionsMenu"),
            "com.android.systemui.globalactions.GlobalActionsImpl" to setOf("showGlobalActions"),
            "com.android.systemui.globalactions.GlobalActionsDialogLite" to setOf("showOrHideDialog", "showDialog", "handleShow"),
            "com.android.systemui.globalactions.GlobalActionsDialog" to setOf("showOrHideDialog", "showDialog", "handleShow"),
        )

        /** SystemUI's callback interface back to system_server for the power menu. */
        const val GLOBAL_ACTIONS_MANAGER = "GlobalActionsManager"
    }

    /** Shade roots whose current gesture began in the dead-zone; the rest of it is consumed too. */
    private val blockedGestures: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    private val systemPropertiesGetBoolean: Method? by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            in SYSTEM_UI_PACKAGES -> {
                installShadeTouchHooks(param.classLoader)
                installHooks(param.classLoader, SYSTEM_UI_POWER_MENU, systemUiPowerMenuHooker, "Power menu (SystemUI)")
            }
            moduleApplicationInfo.packageName -> installModuleStatusHook(param.classLoader)
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installHooks(param.classLoader, SYSTEM_SERVER_POWER_MENU, systemServerPowerMenuHooker, "Power menu (system_server)")
    }

    /** Nothing to retire: the module owns no threads, receivers or native state. */
    override fun onHotReloading(param: HotReloadingParam): Boolean = true

    /**
     * Package callbacks are not replayed after a hot reload, so each live hook is atomically
     * re-pointed at this generation's code (protection never lapses) and unknown ones are dropped.
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        for (old in param.oldHookHandles) {
            val hooker = hookerFor(old.executable)
            runCatching { if (hooker != null) old.replaceHook(hooker) else old.unhook() }
                .onFailure { log(Log.WARN, TAG, "Hot reload: could not update the hook on ${old.executable}", it) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Quick Settings dead-zone
    // ---------------------------------------------------------------------------------------

    /**
     * Hooks `dispatchTouchEvent` where the shade roots declare it. On a build where they only
     * inherit it, [ViewGroup.dispatchTouchEvent] is hooked instead and filtered to the roots.
     */
    private fun installShadeTouchHooks(classLoader: ClassLoader) {
        val roots = SHADE_ROOT_CLASSES.mapNotNull { loadClassOrNull(classLoader, it) }
        val targets = roots.mapNotNull(::declaredDispatchTouchEvent).distinct().ifEmpty {
            if (roots.isEmpty()) emptyList() else listOf(ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java))
        }
        report("Quick Settings dead-zone", targets.count { hookSafely(it, shadeTouchHooker) })
    }

    private val shadeTouchHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            val view = chain.thisObject as? View ?: return chain.proceed()
            val event = chain.args.firstOrNull() as? MotionEvent ?: return chain.proceed()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    blockedGestures.remove(view)
                    if (isShadeRoot(chain, view) && shouldBlockShadeTouch(view, event)) {
                        blockedGestures.add(view)
                        log(Log.DEBUG, TAG, "Blocked a Quick Settings pull-down (y=${event.rawY}).")
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (blockedGestures.remove(view)) return true
                else -> if (view in blockedGestures) return true
            }
            return chain.proceed()
        }
    }

    private fun shouldBlockShadeTouch(view: View, event: MotionEvent): Boolean =
        event.rawY <= statusBarDeadZone(view) && isProtectionEnabled() && isKeyguardLocked(view.context)

    /** True unless the hook sits on [ViewGroup] and [view] is not one of the shade roots. */
    private fun isShadeRoot(chain: Chain, view: View): Boolean {
        if (chain.executable.declaringClass != ViewGroup::class.java) return true
        return generateSequence<Class<*>>(view.javaClass) { it.superclass }.any { it.name in SHADE_ROOT_CLASSES }
    }

    /**
     * Height (px) of the strip that starts a shade pull-down, measured for the current
     * configuration. Insets are recomputed by the framework on every rotation, fold or display
     * change; the framework dimen and a density-scaled default are fallbacks.
     */
    private fun statusBarDeadZone(view: View): Float {
        try {
            view.rootWindowInsets?.let { insets ->
                val top = maxOf(
                    insets.getInsets(WindowInsets.Type.statusBars()).top,
                    insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top,
                    insets.getInsets(WindowInsets.Type.displayCutout()).top,
                )
                if (top > 0) return top.toFloat()
            }
        } catch (ignored: RuntimeException) {
            // Fall through to the resource and density fallbacks.
        }
        val resources = view.resources
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id != 0) resources.getDimensionPixelSize(id).takeIf { it > 0 }?.let { return it.toFloat() }
        return FALLBACK_DEAD_ZONE_DP * resources.displayMetrics.density
    }

    private fun declaredDispatchTouchEvent(cls: Class<*>): Method? = cls.declaredMethods.firstOrNull {
        it.name == "dispatchTouchEvent" && it.parameterCount == 1 && it.parameterTypes[0] == MotionEvent::class.java
    }

    // ---------------------------------------------------------------------------------------
    // Power menu
    // ---------------------------------------------------------------------------------------

    private val systemServerPowerMenuHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            // GlobalActions#showDialog receives "keyguard showing" as its first argument.
            val keyguardShowing = chain.args.firstOrNull() == true
            if (isProtectionEnabled() && (keyguardShowing || isSystemServerLocked(chain.thisObject))) {
                log(Log.INFO, TAG, "Blocked the power menu (${chain.executable.name}).")
                return blockedResult(chain.executable)
            }
            return chain.proceed()
        }
    }

    private val systemUiPowerMenuHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (isProtectionEnabled() && isKeyguardLocked(contextOf(chain.thisObject))) {
                acknowledgeGlobalActions(chain)
                log(Log.INFO, TAG, "Blocked the power menu in SystemUI (${chain.executable.name}).")
                return blockedResult(chain.executable)
            }
            return chain.proceed()
        }
    }

    /** PhoneWindowManager answers directly; other policy classes go through [KeyguardManager]. */
    private fun isSystemServerLocked(target: Any?): Boolean {
        val direct = runCatching { target?.javaClass?.getMethod("isKeyguardLocked")?.invoke(target) as? Boolean }.getOrNull()
        return direct ?: isKeyguardLocked(contextOf(target))
    }

    /**
     * Reports the refused menu as shown and then hidden. Without this, system_server waits a few
     * seconds for SystemUI and then opens its own legacy power menu.
     */
    private fun acknowledgeGlobalActions(chain: Chain) {
        val candidates = chain.args.asSequence() + sequenceOf(chain.thisObject) + fieldValues(chain.thisObject)
        for (candidate in candidates) {
            val manager = candidate?.let { globalActionsManagerType(it.javaClass) } ?: continue
            for (name in arrayOf("onGlobalActionsShown", "onGlobalActionsHidden")) {
                runCatching { manager.getMethod(name).invoke(candidate) }
            }
            return
        }
    }

    private fun globalActionsManagerType(cls: Class<*>): Class<*>? {
        val pending = ArrayDeque<Class<*>>()
        generateSequence<Class<*>>(cls) { it.superclass }.forEach { pending += it.interfaces }
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (type.simpleName == GLOBAL_ACTIONS_MANAGER) return type
            pending += type.interfaces
        }
        return null
    }

    /** A harmless value for a skipped call; today every entry point is `void`. */
    private fun blockedResult(executable: Executable): Any? = when ((executable as? Method)?.returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }

    // ---------------------------------------------------------------------------------------
    // Module status (this app's own process)
    // ---------------------------------------------------------------------------------------

    private val moduleActiveHooker = object : Hooker {
        override fun intercept(chain: Chain): Any = true
    }

    /** Makes [MainActivity.isModuleActive] return true; its callers are deoptimized so it is not inlined. */
    private fun installModuleStatusHook(classLoader: ClassLoader) {
        val activity = loadClassOrNull(classLoader, MainActivity::class.java.name) ?: return
        val method = activity.declaredMethods.firstOrNull { it.name == "isModuleActive" } ?: return
        if (hookSafely(method, moduleActiveHooker)) {
            activity.declaredMethods.filter { it != method }.forEach { deoptimize(it) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------

    private fun installHooks(classLoader: ClassLoader, targets: Map<String, Set<String>>, hooker: Hooker, layer: String) {
        val methods = targets.flatMap { (className, names) ->
            loadClassOrNull(classLoader, className)?.declaredMethods
                ?.filter { it.name in names && !Modifier.isAbstract(it.modifiers) }
                .orEmpty()
        }
        report(layer, methods.count { hookSafely(it, hooker) })
    }

    private fun hookSafely(method: Method, hooker: Hooker): Boolean = try {
        hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker)
        true
    } catch (t: Throwable) {
        log(Log.ERROR, TAG, "Could not hook ${method.declaringClass.name}#${method.name}", t)
        false
    }

    /** Maps a hooked method back to its hooker; used to carry hooks across a hot reload. */
    private fun hookerFor(executable: Executable): Hooker? {
        val owner = executable.declaringClass.name
        val name = executable.name
        return when {
            name == "dispatchTouchEvent" -> shadeTouchHooker
            name == "isModuleActive" -> moduleActiveHooker
            SYSTEM_SERVER_POWER_MENU[owner]?.contains(name) == true -> systemServerPowerMenuHooker
            SYSTEM_UI_POWER_MENU[owner]?.contains(name) == true -> systemUiPowerMenuHooker
            else -> null
        }
    }

    private fun report(layer: String, count: Int) {
        if (count > 0) log(Log.INFO, TAG, "$layer: $count hook(s) installed.")
        else log(Log.WARN, TAG, "$layer: no known target on this build, layer inactive.")
    }

    private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? = try {
        classLoader.loadClass(name)
    } catch (ignored: ClassNotFoundException) {
        null
    } catch (ignored: LinkageError) {
        null
    }

    /** Defaults to enabled when the property is unset or unreadable. */
    private fun isProtectionEnabled(): Boolean =
        runCatching { systemPropertiesGetBoolean?.invoke(null, TOGGLE_PROPERTY, true) as? Boolean }.getOrNull() ?: true

    /** True whenever the keyguard is showing, including while an app is shown over it. */
    private fun isKeyguardLocked(context: Context?): Boolean = runCatching {
        context?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    private fun contextOf(target: Any?): Context? = when (target) {
        is Context -> target
        is View -> target.context
        else -> fieldValues(target).filterIsInstance<Context>().firstOrNull() ?: currentApplication()
    }

    /** Non-null instance field values of [target], walking up its class hierarchy lazily. */
    private fun fieldValues(target: Any?): Sequence<Any> = sequence {
        var cls: Class<*>? = target?.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                runCatching { field.isAccessible = true; field.get(target) }.getOrNull()?.let { yield(it) }
            }
            cls = cls.superclass
        }
    }

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()
}
