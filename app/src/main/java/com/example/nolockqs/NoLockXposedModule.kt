package com.example.nolockqs

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import java.util.concurrent.Executor

/**
 * NoLockQS Xposed module (libxposed API 101+).
 *
 * While the device is locked it keeps these out of reach:
 *  - Quick Settings: SystemUI asks the status bar service to disable Quick Settings
 *    (`DISABLE2_QUICK_SETTINGS`) whenever the keyguard shows, and lifts it
 *    once it is gone. SystemUI then hides the tiles and refuses every way into them, pull-downs,
 *    two-finger pulls and requests from apps alike, while the notifications stay usable: they can
 *    still be tapped, expanded and acted on. On the scene-container SystemUI (Android 16+) the
 *    Quick Settings scene and overlay are also vetoed directly, in case the flag arrives late.
 *  - The power menu: in system_server, every trigger (power key, key chords, accessibility) is
 *    refused before SystemUI or the legacy dialog is asked to show it. SystemUI refuses it too,
 *    as a fallback for when the System Framework scope is not enabled.
 *
 * Each protection has a switch in the app (see [FeatureSettings]), which the framework shares with
 * the hooked processes and keeps current, so a change applies right away.
 */
class NoLockXposedModule : XposedModule() {

    private companion object {
        const val TAG = "NoLockQS"

        /** Master kill-switch: `setprop persist.sys.nolockqs.enabled false` (as root) pauses every layer. */
        const val TOGGLE_PROPERTY = "persist.sys.nolockqs.enabled"

        val SYSTEM_UI_PACKAGES = setOf("com.android.systemui", "com.google.android.systemui")

        /** StatusBarManager.DISABLE2_QUICK_SETTINGS. */
        const val DISABLE2_QUICK_SETTINGS = 1

        /** StatusBarManager.DISABLE2_NONE. */
        const val DISABLE2_NONE = 0

        /** How often, and for how long, to wait for SystemUI's application before giving up. */
        const val STARTUP_RETRY_MS = 500L
        const val STARTUP_RETRIES = 120

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
            "com.android.systemui.globalactions.GlobalActionsDialogLite" to setOf("showOrHideDialog", "handleShow"),
        )

        /** SystemUI's callback interface back to system_server for the power menu. */
        const val GLOBAL_ACTIONS_MANAGER = "GlobalActionsManager"

        /**
         * Scene-container entry points (Android 16+ "Flexiglass" SystemUI): opening Quick Settings
         * settles here as a change to the Quick Settings scene or overlay, which is vetoed while
         * locked. Up to Android 16 `SceneInteractor` is the class itself; on Android 17 it is an
         * interface and the work happens in `LegacySceneInteractorImpl`. The notification shade is
         * left alone, as tapping a notification to expand it opens the shade scene.
         */
        private val SCENE_CHANGE_METHODS = setOf("changeScene", "snapToScene", "showOverlay", "instantlyShowOverlay")
        val SCENE_CONTAINER_ENTRY = mapOf(
            "com.android.systemui.scene.domain.interactor.SceneInteractor" to SCENE_CHANGE_METHODS,
            "com.android.systemui.scene.domain.interactor.LegacySceneInteractorImpl" to SCENE_CHANGE_METHODS,
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Methods already hooked in this process, so a repeated package callback never hooks one twice. */
    private val hookedMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())

    /** SystemUI class loaders already hooked in this process. */
    private val hookedLoaders: MutableSet<ClassLoader> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>()))

    /** The app's feature switches as the framework shares them; read on first use, see [isFeatureEnabled]. */
    @Volatile
    private var featureSettings: SharedPreferences? = null

    @Volatile
    private var featureSettingsFailureLogged = false

    @delegate:SuppressLint("PrivateApi") // Runs inside SystemUI and system_server, which may use hidden APIs.
    private val systemPropertiesGetBoolean: Method? by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            in SYSTEM_UI_PACKAGES -> {
                // The framework can report SystemUI more than once per process, also with a class
                // loader that holds none of its code; hook each real SystemUI loader once.
                if (!isSystemUiLoader(param.classLoader) || !hookedLoaders.add(param.classLoader)) return
                log(Log.INFO, TAG, "Hooking ${param.packageName} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}).")
                startQuickSettingsGuard()
                installHooks(param.classLoader, SCENE_CONTAINER_ENTRY, sceneContainerEntryHooker, "Quick Settings scene")
                installHooks(param.classLoader, SYSTEM_UI_POWER_MENU, systemUiPowerMenuHooker, "Power menu (SystemUI)", everyRelease = true)
            }
            moduleApplicationInfo.packageName -> installModuleStatusHook(param.classLoader)
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installHooks(param.classLoader, SYSTEM_SERVER_POWER_MENU, systemServerPowerMenuHooker, "Power menu (system_server)", everyRelease = true)
    }

    /** The lock-state watchers belong to this generation's code: drop them, the next one starts its own. */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        mainHandler.post { stopQuickSettingsGuard() }
        return true
    }

    /**
     * Package callbacks are not replayed after a hot reload, so each live hook is atomically
     * re-pointed at this generation's code (protection never lapses) and unknown ones are dropped.
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        var inSystemUi = false
        for (old in param.oldHookHandles) {
            val hooker = hookerFor(old.executable)
            if (old.executable.declaringClass.name.startsWith("com.android.systemui.")) inSystemUi = true
            runCatching { if (hooker != null) old.replaceHook(hooker) else old.unhook() }
                .onFailure { log(Log.WARN, TAG, "Hot reload: could not update the hook on ${old.executable}", it) }
        }
        if (inSystemUi) startQuickSettingsGuard()
    }

    // ---------------------------------------------------------------------------------------
    // Quick Settings: disabled through the status bar service while locked
    // ---------------------------------------------------------------------------------------

    /** SystemUI's application, once [startQuickSettingsGuard] has it. Main thread only. */
    private var guardContext: Context? = null

    /**
     * This module's own StatusBarManager. The service keeps one disable record per manager, so a
     * manager of its own keeps the flag apart from the ones SystemUI sets through its shared one.
     */
    private var statusBar: Any? = null
    private var lockReceiver: BroadcastReceiver? = null
    private var lockListener: Any? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** What was last asked of the status bar service: true while Quick Settings is disabled. Main thread only. */
    private var quickSettingsDisabled: Boolean? = null

    /**
     * Starts following the lock state in SystemUI. It needs SystemUI's own application, which is
     * not set up yet when the package is reported, so it waits for it on the main thread.
     */
    private fun startQuickSettingsGuard(attempt: Int = 0) {
        mainHandler.post {
            if (guardContext != null) return@post
            val context = currentApplication()
            if (context == null) {
                if (attempt < STARTUP_RETRIES) mainHandler.postDelayed({ startQuickSettingsGuard(attempt + 1) }, STARTUP_RETRY_MS)
                else log(Log.ERROR, TAG, "Quick Settings: SystemUI's application never came up, layer inactive.")
                return@post
            }
            guardContext = context
            // A context of its own comes with its own service cache, hence its own manager.
            statusBar = runCatching { context.createConfigurationContext(context.resources.configuration).getSystemService("statusbar") }.getOrNull()
            watchLockState(context)
            log(Log.INFO, TAG, "Quick Settings: following the lock state (lock listener=${lockListener != null}).")
            refreshQuickSettings()
        }
    }

    /**
     * Follows the keyguard: the locked-state listener reports every change, also those without a
     * screen change (boot, Smart Lock, face unlock). The screen and user-present broadcasts back it
     * up, and the feature switch applies as soon as it is flipped.
     */
    @SuppressLint("MissingPermission") // SystemUI may hold SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE; if not, the broadcasts do.
    private fun watchLockState(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshQuickSettings()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) }
            .onSuccess { lockReceiver = receiver }
            .onFailure { log(Log.WARN, TAG, "Quick Settings: could not watch the screen state", it) }

        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val listener = KeyguardManager.KeyguardLockedStateListener { refreshQuickSettings() }
        runCatching { keyguard.addKeyguardLockedStateListener(Executor { mainHandler.post(it) }, listener) }
            .onSuccess { lockListener = listener }
            .onFailure { log(Log.INFO, TAG, "Quick Settings: no lock-state listener here (${it.javaClass.simpleName}), using the screen events.") }

        val settings = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mainHandler.post { refreshQuickSettings() } }
        runCatching { featureSettings()?.registerOnSharedPreferenceChangeListener(settings) }
            .onSuccess { settingsListener = settings }
    }

    /** Stops following the lock state and lifts this generation's flag; the next one sets its own. */
    @SuppressLint("MissingPermission") // Only removes the listener that watchLockState managed to add.
    private fun stopQuickSettingsGuard() {
        val context = guardContext ?: return
        if (quickSettingsDisabled == true) setQuickSettingsDisabled(false)
        lockReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        (lockListener as? KeyguardManager.KeyguardLockedStateListener)?.let { listener ->
            runCatching { context.getSystemService(KeyguardManager::class.java).removeKeyguardLockedStateListener(listener) }
        }
        settingsListener?.let { runCatching { featureSettings()?.unregisterOnSharedPreferenceChangeListener(it) } }
        lockReceiver = null
        lockListener = null
        settingsListener = null
        statusBar = null
        quickSettingsDisabled = null
        guardContext = null
    }

    /** Disables Quick Settings while the keyguard is locked and the switch is on, else enables it. */
    private fun refreshQuickSettings() {
        val context = guardContext ?: return
        val disable = isFeatureEnabled(FeatureSettings.BLOCK_QUICK_SETTINGS) && isKeyguardLocked(context)
        if (disable != quickSettingsDisabled) setQuickSettingsDisabled(disable)
    }

    /** Sets or clears DISABLE2_QUICK_SETTINGS on this module's disable record. */
    private fun setQuickSettingsDisabled(disable: Boolean) {
        runCatching {
            val statusBar = statusBar ?: error("no status bar service")
            statusBar.javaClass.getMethod("disable2", Int::class.javaPrimitiveType)
                .invoke(statusBar, if (disable) DISABLE2_QUICK_SETTINGS else DISABLE2_NONE)
        }.onSuccess {
            quickSettingsDisabled = disable
            log(Log.INFO, TAG, if (disable) "Quick Settings disabled: the device is locked." else "Quick Settings enabled again.")
        }.onFailure { log(Log.ERROR, TAG, "Quick Settings: the status bar service refused the change", it) }
    }

    /**
     * Refuses the Quick Settings scene or overlay over the lock screen (Android 16+). Every hooked
     * method takes the destination first; the shade itself, Lockscreen, Bouncer, Gone and the rest
     * go through, so notifications keep working.
     */
    private val sceneContainerEntryHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            val destination = chain.args.firstOrNull()
            if (destination != null && isQuickSettingsKey(destination) &&
                isFeatureEnabled(FeatureSettings.BLOCK_QUICK_SETTINGS) && isKeyguardLocked(systemContext())
            ) {
                log(Log.INFO, TAG, "Blocked the '${sceneKeyName(destination)}' scene over the lock screen (${chain.executable.name}).")
                return blockedResult(chain.executable)
            }
            return chain.proceed()
        }
    }

    /** A scene or overlay key's debug name (SceneTransitionLayout `Key.debugName`), else its toString. */
    private fun sceneKeyName(key: Any): String =
        runCatching { key.javaClass.getMethod("getDebugName").invoke(key) as? String }.getOrNull()
            ?: key.toString()

    /** Scenes.QuickSettings and Overlays.QuickSettingsShade, matched on the debug name. */
    private fun isQuickSettingsKey(key: Any): Boolean =
        "quicksettings" in sceneKeyName(key).lowercase().filter(Char::isLetterOrDigit)

    // ---------------------------------------------------------------------------------------
    // Power menu
    // ---------------------------------------------------------------------------------------

    private val systemServerPowerMenuHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            // GlobalActions#showDialog receives "keyguard showing" as its first argument.
            val keyguardShowing = chain.args.firstOrNull() == true
            if (isFeatureEnabled(FeatureSettings.BLOCK_POWER_MENU) && (keyguardShowing || isSystemServerLocked(chain.thisObject))) {
                log(Log.INFO, TAG, "Blocked the power menu (${chain.executable.name}).")
                return blockedResult(chain.executable)
            }
            return chain.proceed()
        }
    }

    private val systemUiPowerMenuHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (isFeatureEnabled(FeatureSettings.BLOCK_POWER_MENU) && isKeyguardLocked(systemContext())) {
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
        return direct ?: isKeyguardLocked(fieldValues(target).filterIsInstance<Context>().firstOrNull() ?: systemContext())
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

    /** A harmless value for a skipped call: false for a check, nothing for an action. */
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

    /** Whether [classLoader] holds SystemUI's own code: any class this module knows how to hook. */
    private fun isSystemUiLoader(classLoader: ClassLoader): Boolean =
        (SCENE_CONTAINER_ENTRY.keys + SYSTEM_UI_POWER_MENU.keys).any { loadClassOrNull(classLoader, it) != null }

    private fun installHooks(
        classLoader: ClassLoader,
        targets: Map<String, Set<String>>,
        hooker: Hooker,
        layer: String,
        everyRelease: Boolean = false,
    ) {
        val methods = targets.flatMap { (className, names) ->
            loadClassOrNull(classLoader, className)?.declaredMethods
                ?.filter { it.name in names && !Modifier.isAbstract(it.modifiers) }
                .orEmpty()
        }
        val count = methods.count { hookSafely(it, hooker) }
        when {
            count > 0 -> log(Log.INFO, TAG, "$layer: $count hook(s) installed.")
            everyRelease -> log(Log.WARN, TAG, "$layer: no known target on this build, layer inactive.")
            else -> log(Log.INFO, TAG, "$layer: not part of this build.")
        }
    }

    private fun hookSafely(method: Method, hooker: Hooker): Boolean {
        if (!hookedMethods.add(method)) return true
        return try {
            hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker)
            true
        } catch (t: Throwable) {
            hookedMethods.remove(method)
            log(Log.ERROR, TAG, "Could not hook ${method.declaringClass.name}#${method.name}", t)
            false
        }
    }

    /** Maps a hooked method back to its hooker; used to carry hooks across a hot reload. */
    private fun hookerFor(executable: Executable): Hooker? {
        val owner = executable.declaringClass.name
        val name = executable.name
        return when {
            SCENE_CONTAINER_ENTRY[owner]?.contains(name) == true -> sceneContainerEntryHooker
            name == "isModuleActive" -> moduleActiveHooker
            SYSTEM_SERVER_POWER_MENU[owner]?.contains(name) == true -> systemServerPowerMenuHooker
            SYSTEM_UI_POWER_MENU[owner]?.contains(name) == true -> systemUiPowerMenuHooker
            else -> null
        }
    }

    private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? = try {
        classLoader.loadClass(name)
    } catch (ignored: ClassNotFoundException) {
        null
    } catch (ignored: LinkageError) {
        null
    }

    /**
     * Whether a protection is on: the master property must allow it, and so must its switch in
     * the app. Anything that can't be read counts as on.
     */
    private fun isFeatureEnabled(key: String): Boolean =
        isProtectionEnabled() &&
            (runCatching { featureSettings()?.getBoolean(key, FeatureSettings.DEFAULT_ON) }.getOrNull() ?: FeatureSettings.DEFAULT_ON)

    /** The feature switches, fetched from the framework once it can provide them. */
    private fun featureSettings(): SharedPreferences? = featureSettings ?: try {
        getRemotePreferences(FeatureSettings.GROUP).also { featureSettings = it }
    } catch (t: Throwable) {
        if (!featureSettingsFailureLogged) {
            featureSettingsFailureLogged = true
            log(Log.WARN, TAG, "The feature switches can't be read, so every protection stays on.", t)
        }
        null
    }

    /** Defaults to enabled when the property is unset or unreadable. */
    private fun isProtectionEnabled(): Boolean =
        runCatching { systemPropertiesGetBoolean?.invoke(null, TOGGLE_PROPERTY, true) as? Boolean }.getOrNull() ?: true

    /** True whenever the keyguard is showing, including while an app is shown over it. */
    private fun isKeyguardLocked(context: Context?): Boolean = runCatching {
        context?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

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

    @delegate:SuppressLint("PrivateApi") // Runs inside SystemUI and system_server, which may use hidden APIs.
    private val activityThread: Class<*>? by lazy { runCatching { Class.forName("android.app.ActivityThread") }.getOrNull() }

    /** The process's application, or null while it is not set up yet. */
    private fun currentApplication(): Context? = runCatching {
        activityThread?.getMethod("currentApplication")?.invoke(null) as? Context
    }.getOrNull()

    /** The process's application, else its system context: either answers [KeyguardManager]. */
    private fun systemContext(): Context? = currentApplication() ?: runCatching {
        activityThread?.getMethod("currentActivityThread")?.invoke(null)
            ?.let { activityThread?.getMethod("getSystemContext")?.invoke(it) as? Context }
    }.getOrNull()
}
