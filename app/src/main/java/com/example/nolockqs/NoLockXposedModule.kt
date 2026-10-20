package com.example.nolockqs

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import kotlin.math.abs
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
 *  - Quick Settings: in SystemUI, while the keyguard is locked any downward pull on the shade /
 *    keyguard root view is consumed at `dispatchTouchEvent`, so the shade can never start to
 *    expand, wherever the pull starts and with one finger or two. This layer needs no internal
 *    class or method names beyond the root view, so it holds whatever SystemUI does above it. On
 *    top of it the known transitions are also refused directly: requests to open the shade from
 *    outside SystemUI (apps, accessibility, `cmd statusbar`), the legacy locked-shade entry points
 *    (Android 15, 16), and on the scene-container SystemUI (Android 17 "Flexiglass") the change to
 *    the Shade / Quick Settings scene or overlay; and should the legacy shade open anyway, it is
 *    collapsed at once.
 *  - The power menu: in system_server, every trigger (power key, key chords, accessibility) is
 *    refused before SystemUI or the legacy dialog is asked to show it. SystemUI refuses it too,
 *    as a fallback for when the System Framework scope is not enabled.
 *
 * Each protection has a switch in the app (see [FeatureSettings]), which the framework shares with
 * the hooked processes and keeps current, so a change applies right away.
 *
 * Nothing is tied to a device model or an Android release. Hook targets are resolved from ordered
 * lists of known class and method names, a missing target only disables its own layer (and is
 * logged), and the top strip is measured from live [WindowInsets], so it follows rotation,
 * cutouts, density and screen size.
 */
class NoLockXposedModule : XposedModule() {

    private companion object {
        const val TAG = "NoLockQS"

        /** Master kill-switch: `setprop persist.sys.nolockqs.enabled false` (as root) pauses every layer. */
        const val TOGGLE_PROPERTY = "persist.sys.nolockqs.enabled"

        /** Last-resort top-strip height when neither insets nor the framework dimen are available. */
        const val FALLBACK_TOP_STRIP_DP = 28f

        val SYSTEM_UI_PACKAGES = setOf("com.android.systemui", "com.google.android.systemui")

        /** Root views of SystemUI's notification-shade window: legacy shade, scene container, their base. */
        val SHADE_ROOT_CLASSES = listOf(
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.scene.ui.view.SceneWindowRootView",
            "com.android.systemui.scene.ui.view.WindowRootView",
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
            "com.android.systemui.globalactions.GlobalActionsDialogLite" to setOf("showOrHideDialog", "handleShow"),
        )

        /** SystemUI's callback interface back to system_server for the power menu. */
        const val GLOBAL_ACTIONS_MANAGER = "GlobalActionsManager"

        /**
         * What starts the locked shade from the lock screen in the legacy shade: the drag-down
         * gesture anywhere on it, going to the locked shade (also from a tapped notification), and
         * the two-finger pull that opens Quick Settings directly.
         */
        val LOCKED_SHADE_ENTRY = mapOf(
            "com.android.systemui.statusbar.DragDownHelper" to setOf("onInterceptTouchEvent"),
            "com.android.systemui.statusbar.LockscreenShadeTransitionController" to setOf("goToLockedShade"),
            "com.android.systemui.shade.QuickSettingsControllerImpl" to setOf("isOpenQsEvent"),
        )

        /**
         * Scene-container entry points (Android 16+ "Flexiglass" SystemUI). Opening the shade or
         * Quick Settings over the lock screen settles through the scene interactor as a change to
         * the Shade / Quick Settings scene, or as a Notifications-/Quick-Settings-shade overlay. The
         * legacy [LOCKED_SHADE_ENTRY] classes are not on this path, so this is the layer that holds
         * on current Pixels. Up to Android 16 `SceneInteractor` is the class itself; on Android 17
         * it is an interface and the work happens in `LegacySceneInteractorImpl`.
         */
        private val SCENE_CHANGE_METHODS = setOf("changeScene", "snapToScene", "showOverlay", "instantlyShowOverlay")
        val SCENE_CONTAINER_ENTRY = mapOf(
            "com.android.systemui.scene.domain.interactor.SceneInteractor" to SCENE_CHANGE_METHODS,
            "com.android.systemui.scene.domain.interactor.LegacySceneInteractorImpl" to SCENE_CHANGE_METHODS,
        )

        /**
         * Requests to open the shade or Quick Settings that reach SystemUI through the status bar
         * service: apps holding EXPAND_STATUS_BAR, accessibility services, `cmd statusbar`. They
         * open the panel directly, without a gesture or (in the legacy shade) a state change.
         */
        val PANEL_EXPAND_REQUESTS = mapOf(
            "com.android.systemui.statusbar.CommandQueue" to setOf(
                "animateExpandNotificationsPanel", "animateExpandSettingsPanel",
                "toggleNotificationsPanel", "toggleQuickSettingsPanel",
            ),
        )

        /**
         * Where the status bar state changes in the legacy shade (Android 15, 16): whatever opens the
         * shade over the lock screen, the state becomes SHADE_LOCKED. The scene container (17) has
         * no such state; its scenes are vetoed through [SCENE_CONTAINER_ENTRY] instead.
         */
        val STATUS_BAR_STATE = mapOf(
            "com.android.systemui.statusbar.StatusBarStateControllerImpl" to setOf("setState"),
        )

        /** StatusBarState.SHADE_LOCKED: the shade, with Quick Settings, open over the lock screen. */
        const val SHADE_LOCKED = 2
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Methods already hooked in this process, so a repeated package callback never hooks one twice. */
    private val hookedMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())

    /** SystemUI class loaders already hooked in this process. */
    private val hookedLoaders: MutableSet<ClassLoader> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>()))

    @Volatile
    private var collapsePending = false

    /** Shade roots whose current gesture is being consumed; the rest of it is consumed too. */
    private val blockedGestures: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    /** Down position [rawX, rawY] of a gesture on a shade root that is being watched for a pull. */
    private val gestureAnchor: MutableMap<View, FloatArray> =
        Collections.synchronizedMap(WeakHashMap<View, FloatArray>())

    @Volatile
    private var lastTouchDiagLog = 0L

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
                logEnvironment(param.packageName)
                installShadeTouchHooks(param.classLoader)
                installHooks(param.classLoader, SCENE_CONTAINER_ENTRY, sceneContainerEntryHooker, "Lock screen shade scene")
                installHooks(param.classLoader, PANEL_EXPAND_REQUESTS, panelExpandRequestHooker, "Shade open requests", everyRelease = true)
                installHooks(param.classLoader, LOCKED_SHADE_ENTRY, lockedShadeEntryHooker, "Lock screen pull-down")
                installHooks(param.classLoader, STATUS_BAR_STATE, statusBarStateHooker, "Locked shade")
                installHooks(param.classLoader, SYSTEM_UI_POWER_MENU, systemUiPowerMenuHooker, "Power menu (SystemUI)", everyRelease = true)
            }
            moduleApplicationInfo.packageName -> installModuleStatusHook(param.classLoader)
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installHooks(param.classLoader, SYSTEM_SERVER_POWER_MENU, systemServerPowerMenuHooker, "Power menu (system_server)", everyRelease = true)
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
    // Lock screen touches
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
        report("Lock screen touches", targets.count { hookSafely(it, shadeTouchHooker) }, everyRelease = true)
    }

    /**
     * The blunt, class-name-independent layer and the one that should hold whatever SystemUI does
     * internally. The shade/keyguard root view sees every touch on the lock screen, so while the
     * keyguard is locked any downward pull on it, the gesture that expands the shade or Quick
     * Settings from the top strip, mid-screen or with two fingers, is consumed before the scene
     * framework can act on it. Taps, the swipe-up to unlock and sideways swipes are left alone, and
     * nothing is touched once unlocked. The lock state is read from the view's own context, which
     * is always available here, rather than the process-wide application that can come back null.
     */
    private val shadeTouchHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            val view = chain.thisObject as? View ?: return chain.proceed()
            val event = chain.args.firstOrNull() as? MotionEvent ?: return chain.proceed()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    blockedGestures.remove(view)
                    gestureAnchor.remove(view)
                    if (isShadeRoot(chain, view) && isFeatureEnabled(FeatureSettings.BLOCK_QUICK_SETTINGS)) {
                        val locked = isKeyguardLocked(view.context)
                        logTouchDiag(view, event, locked)
                        if (locked) {
                            if (event.rawY <= topStripHeight(view)) {
                                blockedGestures.add(view)
                                log(Log.DEBUG, TAG, "Blocked a lock-screen pull-down in the top strip (y=${event.rawY}).")
                                return true
                            }
                            gestureAnchor[view] = floatArrayOf(event.rawX, event.rawY)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (view in blockedGestures) return true
                    val anchor = gestureAnchor[view]
                    if (anchor != null) {
                        val dx = event.rawX - anchor[0]
                        val dy = event.rawY - anchor[1]
                        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                        if (dy > slop && dy >= abs(dx)) {
                            gestureAnchor.remove(view)
                            blockedGestures.add(view)
                            log(Log.DEBUG, TAG, "Blocked a lock-screen shade pull (downward drag, dy=$dy).")
                            // SystemUI already saw this gesture go down: end it there with a cancel,
                            // or it waits for an up that never comes and ignores the next swipe up.
                            return cancelGesture(chain, event)
                        }
                        if (dy < -slop || abs(dx) > slop) {
                            // Swipe up to unlock, or a sideways gesture: not a shade pull, let it run.
                            gestureAnchor.remove(view)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gestureAnchor.remove(view)
                    if (blockedGestures.remove(view)) return true
                }
                else -> if (view in blockedGestures) return true
            }
            return chain.proceed()
        }
    }

    /** Passes [event] on as ACTION_CANCEL, so the views below drop the gesture they were tracking. */
    private fun cancelGesture(chain: Chain, event: MotionEvent): Any? {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        return try {
            chain.proceed(arrayOf<Any?>(cancel))
        } finally {
            cancel.recycle()
        }
    }

    /** At most once a second, records whether a lock-screen shade-root touch is seen as locked. */
    private fun logTouchDiag(view: View, event: MotionEvent, locked: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTouchDiagLog < 1000L) return
        lastTouchDiagLog = now
        log(Log.DEBUG, TAG, "Shade-root touch on ${view.javaClass.simpleName}: keyguardLocked=$locked, y=${event.rawY}.")
    }

    /** One line at install so a single log shows the device and build; each layer then reports itself. */
    private fun logEnvironment(packageName: String) {
        log(Log.INFO, TAG, "Hooking $packageName on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}).")
    }

    /** Whether [classLoader] holds SystemUI's own code: any class this module knows how to hook. */
    private fun isSystemUiLoader(classLoader: ClassLoader): Boolean =
        (SHADE_ROOT_CLASSES + SCENE_CONTAINER_ENTRY.keys + PANEL_EXPAND_REQUESTS.keys + LOCKED_SHADE_ENTRY.keys + STATUS_BAR_STATE.keys + SYSTEM_UI_POWER_MENU.keys)
            .any { loadClassOrNull(classLoader, it) != null }

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
    @SuppressLint("DiscouragedApi", "InternalInsetResource") // The dimen is only a fallback for missing insets.
    private fun topStripHeight(view: View): Float {
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
        return FALLBACK_TOP_STRIP_DP * resources.displayMetrics.density
    }

    private fun declaredDispatchTouchEvent(cls: Class<*>): Method? = cls.declaredMethods.firstOrNull {
        it.name == "dispatchTouchEvent" && it.parameterCount == 1 && it.parameterTypes[0] == MotionEvent::class.java
    }

    // ---------------------------------------------------------------------------------------
    // Locked shade: pulling down anywhere on the lock screen
    // ---------------------------------------------------------------------------------------

    /**
     * Refuses to start the locked shade: no drag-down, no going to it, no two-finger pull. The
     * drag-down helper sees every touch on the lock screen, so it is decided once per gesture, when
     * the finger goes down.
     */
    private val lockedShadeEntryHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            val event = chain.args.firstOrNull() as? MotionEvent
            val blocked = if (event != null && chain.executable.name == "onInterceptTouchEvent") {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) dragDownBlocked = shouldBlockLockedShade(chain.thisObject)
                dragDownBlocked
            } else {
                shouldBlockLockedShade(chain.thisObject)
            }
            return if (blocked) blockedResult(chain.executable) else chain.proceed()
        }
    }

    @Volatile
    private var dragDownBlocked = false

    /**
     * Refuses to open the shade or Quick Settings over the lock screen in the scene container
     * (Android 16+). Every such gesture, the two-finger pull and a tapped notification included,
     * settles through the scene interactor as a change to the Shade / Quick Settings scene or as a
     * Notifications-/Quick-Settings-shade overlay; while the keyguard is locked those destinations
     * are vetoed and the lock screen scene stays put. Leaving the shade (going to Lockscreen, Gone,
     * Bouncer and the rest) is untouched.
     */
    private val sceneContainerEntryHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (isFeatureEnabled(FeatureSettings.BLOCK_QUICK_SETTINGS) && isKeyguardLocked(contextOf(chain.thisObject))) {
                // Every hooked method takes the destination scene or overlay first.
                val destination = chain.args.firstOrNull()
                if (destination != null && isShadeOrQuickSettingsKey(destination)) {
                    log(Log.INFO, TAG, "Blocked the '${sceneKeyName(destination)}' scene over the lock screen (${chain.executable.name}).")
                    return blockedResult(chain.executable)
                }
            }
            return chain.proceed()
        }
    }

    /** Refuses requests from outside SystemUI to open the shade or Quick Settings while locked. */
    private val panelExpandRequestHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (isFeatureEnabled(FeatureSettings.BLOCK_QUICK_SETTINGS) && isKeyguardLocked(contextOf(chain.thisObject))) {
                log(Log.INFO, TAG, "Blocked a request to open the shade over the lock screen (${chain.executable.name}).")
                return blockedResult(chain.executable)
            }
            return chain.proceed()
        }
    }

    /** A scene or overlay key's debug name (SceneTransitionLayout `Key.debugName`), else its toString. */
    private fun sceneKeyName(key: Any): String =
        runCatching { key.javaClass.getMethod("getDebugName").invoke(key) as? String }.getOrNull()
            ?: runCatching { key.javaClass.getField("debugName").get(key) as? String }.getOrNull()
            ?: key.toString()

    /**
     * Whether a scene or overlay key is the shade or Quick Settings, matched on its debug name so it
     * holds across builds and the single/dual-shade split: Scenes.Shade, Scenes.QuickSettings,
     * Overlays.NotificationsShade and Overlays.QuickSettingsShade all match, while Lockscreen,
     * Bouncer, Gone, Communal and Dream are left alone.
     */
    private fun isShadeOrQuickSettingsKey(key: Any): Boolean {
        val name = sceneKeyName(key).lowercase().filter(Char::isLetterOrDigit)
        return "shade" in name || "quicksettings" in name
    }

    private fun shouldBlockLockedShade(target: Any?): Boolean =
        isFeatureEnabled(FeatureSettings.BLOCK_QUICK_SETTINGS) && isKeyguardLocked(contextOf(target))

    /** Collapses the shade as soon as it opens over the locked lock screen, however it got there. */
    private val statusBarStateHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val state = runCatching { chain.thisObject?.javaClass?.getMethod("getState")?.invoke(chain.thisObject) as? Int }.getOrNull()
            if (state == SHADE_LOCKED && isFeatureEnabled(FeatureSettings.BLOCK_QUICK_SETTINGS)) {
                collapseLockedShade()
            }
            return result
        }
    }

    /**
     * Asks the status bar service to collapse the shade, which SystemUI then animates back to the
     * lock screen. Posted, as the state change that triggers it is still being dispatched.
     */
    @SuppressLint("PrivateApi") // Runs inside SystemUI, which may use hidden APIs.
    private fun collapseLockedShade() {
        if (collapsePending) return
        collapsePending = true
        mainHandler.post {
            collapsePending = false
            val context = processContext() ?: return@post
            if (!isKeyguardLocked(context)) return@post
            runCatching {
                val statusBar = context.getSystemService("statusbar") ?: return@runCatching
                statusBar.javaClass.getMethod("collapsePanels").invoke(statusBar)
                log(Log.INFO, TAG, "Collapsed the shade opened over the lock screen.")
            }.onFailure { log(Log.WARN, TAG, "Could not collapse the locked shade", it) }
        }
    }

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
            if (isFeatureEnabled(FeatureSettings.BLOCK_POWER_MENU) && isKeyguardLocked(contextOf(chain.thisObject))) {
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
        report(layer, methods.count { hookSafely(it, hooker) }, everyRelease)
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
            name == "dispatchTouchEvent" -> shadeTouchHooker
            SCENE_CONTAINER_ENTRY[owner]?.contains(name) == true -> sceneContainerEntryHooker
            PANEL_EXPAND_REQUESTS[owner]?.contains(name) == true -> panelExpandRequestHooker
            LOCKED_SHADE_ENTRY[owner]?.contains(name) == true -> lockedShadeEntryHooker
            STATUS_BAR_STATE[owner]?.contains(name) == true -> statusBarStateHooker
            name == "isModuleActive" -> moduleActiveHooker
            SYSTEM_SERVER_POWER_MENU[owner]?.contains(name) == true -> systemServerPowerMenuHooker
            SYSTEM_UI_POWER_MENU[owner]?.contains(name) == true -> systemUiPowerMenuHooker
            else -> null
        }
    }

    /** Logs a layer's hook count; a missing layer is only a warning when every release has it. */
    private fun report(layer: String, count: Int, everyRelease: Boolean = false) {
        when {
            count > 0 -> log(Log.INFO, TAG, "$layer: $count hook(s) installed.")
            everyRelease -> log(Log.WARN, TAG, "$layer: no known target on this build, layer inactive.")
            else -> log(Log.INFO, TAG, "$layer: not part of this build.")
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

    private fun contextOf(target: Any?): Context? = when (target) {
        is Context -> target
        is View -> target.context
        else -> fieldValues(target).filterIsInstance<Context>().firstOrNull() ?: processContext()
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

    /**
     * The process's application, or its system context while the application is not set up yet
     * (as early in SystemUI): either answers [KeyguardManager] for the device.
     */
    @SuppressLint("PrivateApi") // Runs inside SystemUI and system_server, which may use hidden APIs.
    private fun processContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getMethod("currentApplication").invoke(null) as? Context
            ?: activityThread.getMethod("currentActivityThread").invoke(null)
                ?.let { activityThread.getMethod("getSystemContext").invoke(it) as? Context }
    }.getOrNull()
}
