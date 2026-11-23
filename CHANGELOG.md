# Changelog

Each release on GitHub and in the Xposed Modules Repository takes its notes from its section here.
A section starts with `## v<version>`, then an optional one-line summary as a quote, then any of
these groups: `### ✨ Highlights`, `### 🐞 Fixes`, `### 🔄 Changes`, `### 🧹 Under the hood`.
A release without a section gets notes made from its commits.

## v2.8 · 2026-11-08

> Quick Settings no longer gets stuck hidden on an unlocked phone.

### ✨ Highlights

- **Quick Settings is always back after unlocking.** The real cause of the rare "only notifications" shade on an unlocked phone is found and fixed. System UI runs a few services, such as the screenshot service, in processes of their own, and the module's lock guard ran in each of them. Android freezes such a process soon after its job is done, and a frozen process never hears the unlock, so its Quick Settings switch stayed on. The module now works only in System UI's main process.

### 🧹 Under the hood

- The Battery Saver resync and the disable-flags watch from v2.7 are gone, since they chased the wrong cause
- Gradle 9.9.0 and Android Gradle Plugin 9.5.1

> [!NOTE]
> Reboot (or restart System UI) once after updating, to clear any switch an older version left behind.

## v2.7 · 2026-11-02

> A safety net for Quick Settings after unlocking, and a log line that names the culprit.

### 🐞 Fixes

- Quick Settings could now and then stay hidden (only notifications showing) on an unlocked phone after Battery Saver turned on. The module now sends its Quick Settings switch again whenever Battery Saver or the system configuration changes, and if System UI is still told to hide Quick Settings a second after an unlock, the module lifts its own switch instead of waiting for a lock event

### 🧹 Under the hood

- If Quick Settings is ever hidden on an unlocked phone by something other than the module, the module log now says so, with the flags and the Battery Saver state

> [!NOTE]
> v2.8 found the real cause of this bug and replaced these workarounds with a proper fix.

## v2.6 · 2026-10-31

> A clean, see-through lock screen on the classic shade, and Quick Settings always back after unlocking.

### ✨ Highlights

- **See-through shade on the lock screen.** On the classic shade (Android 16 and older, and ROMs built on it), pulling down or expanding a notification on the lock screen no longer shows an empty Quick Settings panel above the notifications ([#12](https://github.com/2B-4G10/NoLockQS/issues/12)). Only the notifications show.

### 🐞 Fixes

- Quick Settings could stay blocked after unlocking while Extreme Battery Saver was on. While Quick Settings is off, the module now checks the lock state again every second with the screen on, and whenever Quick Settings is opened, so it is back as soon as the phone is unlocked

## v2.5 · 2026-10-28

> Lock-screen notifications work again, while Quick Settings stays locked away.

### ✨ Highlights

- **Expand and act on notifications from the lock screen.** Notifications can be tapped and expanded again, with their action buttons ([#12](https://github.com/2B-4G10/NoLockQS/issues/12)).
- **A sturdier block.** Quick Settings is now switched off with Android's own Quick Settings switch while the phone is locked: the tiles are hidden and can't be reached by any pull-down or app, and the rest of the lock screen works as normal.

### 🧹 Under the hood

- Leaner module: the touch blocking and the internal System UI hooks it relied on are gone, which leaves far fewer places for an Android update to break
- Android Gradle Plugin 9.5.0

## v2.4 · 2026-10-20

> Quick Settings stays closed on the lock screen whatever opens it, on Android 15, 16 and 17.

### ✨ Highlights

- **Apps can't open Quick Settings on the lock screen.** Requests from apps, accessibility services and `cmd statusbar` to expand the shade or Quick Settings are now refused while the phone is locked. Before, only finger pulls were blocked.
- **The app shows the real state, and fixes the scope for you.** If System UI or System Framework is missing from Vector's scope, the status card says which one, and a tap asks Vector to add it. It no longer shows "Module inactive" while the protection is running.

### 🐞 Fixes

- Android 17: swipe up to unlock works again after a blocked pull from the middle of the screen

### 🧹 Under the hood

- Android 17's new scene layer is covered as well
- Hook targets that exist on none of Android 15, 16 or 17 are removed, and each System UI class and method is hooked only once
- README: Android 17 needs Magisk 31.0+ and a Vector canary, and the scope the module needs is spelled out
- Gradle 9.8.2

## v2.3 · 2026-10-15

> The lock-screen pull-down is now stopped at the source.

### ✨ Highlights

- **Pull-down blocked at the touch layer.** On current Pixels, Quick Settings / Control Center could still be pulled down on the lock screen. While the phone is locked, any downward pull on the lock screen is now stopped at the source: from the top, the middle, or with two fingers. The block no longer depends on System UI's internal names.

### 🐞 Fixes

- The lock check is reliable inside System UI

### 🧹 Under the hood

- The module log now records what it hooks and what it sees, so a single log pinpoints anything left

## v2.2 · 2026-10-15

> Support for the scene-container System UI on current Pixels.

### 🐞 Fixes

- On the scene-container System UI (Android 16+ "Flexiglass", the current Pixel), the lock-screen pull-down and the two-finger Quick Settings pull opened the shade and Control Center again, because they now settle through a new path the previous build did not cover. That path is refused while locked, so the shade and Quick Settings stay out of reach on the lock screen

## v2.1 · 2026-10-15

> Blocked anywhere on the lock screen, not just at the top.

### 🐞 Fixes

- Pulling down from the middle of the lock screen opened the shade with Quick Settings. It is now blocked anywhere on the lock screen, and if the shade still opens over the lock screen some other way (a two-finger pull, a tapped notification), it closes at once

### 🧹 Under the hood

- Kotlin 2.4.21 and Gradle 9.8.1

## v2.0 · 2026-10-02

> A polish release: a proper description in Vector, and the newest Android 17 SDK.

### ✨ Highlights

- **NoLockQS describes itself in Vector.** Vector's module list now shows what NoLockQS does under its name.

### 🧹 Under the hood

- Built with Kotlin 2.4.20 and against the newest Android 17 SDK (API 37.2)
- Cleaner code: the legacy Xposed entries are gone from the manifest, as Vector reads the module from its libxposed files, and lint reports no warnings

> [!TIP]
> Installs over v1.9 as an update.

## v1.9 · 2026-09-29

> NoLockQS is now in the Xposed Modules Repository.

### ✨ Highlights

- **Install and update from Vector's Store.** Every release is now also published to the Xposed Modules Repository ([modules.lsposed.org](https://modules.lsposed.org/module/io.github.i2B4G10.NoLockQS)).

### 🔄 Changes

- The package name is now `io.github.i2B4G10.NoLockQS`, the name the repository lists it under

> [!IMPORTANT]
> One-time reinstall: uninstall the previous NoLockQS, install this one, then enable it again in Vector for System UI and System Framework.

## v1.8 · 2026-09-28

> Choose exactly what gets blocked.

### ✨ Highlights

- **Switch each protection on or off.** Turn Quick Settings and power menu blocking on or off separately: tap a box in the app, green means on and grey means off.
- **A smarter status card.** It now says what is blocked, and turns red when every protection is off.

### 🔄 Changes

- The heart in the support section is now red

### 🧹 Under the hood

- A weekly check keeps the Android SDK, Gradle, the Android Gradle Plugin and libxposed up to date

## v1.7 · 2026-09-28

> Seamless updates from here on.

### ✨ Highlights

- **Signed with a permanent key.** After this one-time reinstall, future updates install over the previous version.

## v1.6 · 2026-09-27

> A redesigned module screen.

### ✨ Highlights

- **New module screen:** a status card that turns green or red, a list of what is blocked while the phone is locked, and a cleaner header
- **Support NoLockQS on Patreon** straight from the app

## v1.5 · 2026-09-27

> A fresh Material You look.

### ✨ Highlights

- **Refreshed module screen:** Material You colors, a status card, and a layout that stays clear of the status bar and scrolls in any orientation

### 🔄 Changes

- NoLockQS is now licensed under the Apache License 2.0

## v1.4 · 2026-09-25

> The power menu joins the lock, and the hooks are ready for new Android releases.

### ✨ Highlights

- **Power menu blocked on the lock screen** for every trigger (enable System Framework in the scope)
- **New app icon**

### 🐞 Fixes

- A gesture that starts in the dead-zone is blocked until the finger lifts, not just its first touch
- The module status screen shows ACTIVE again, plus the installed version

### 🧹 Under the hood

- Hooks resolve from fallback lists, so new Pixel and Android releases keep working, including the new scene-container shade
- Hot reload now moves live hooks to the updated code
- Android Gradle Plugin 9.4.1, Gradle 9.8.0 and core-ktx 1.19.1

## v1.3

> A dead-zone that fits every screen.

### ✨ Highlights

- **Dead-zone measured live** from the window insets (status bar and display cutout), so it adapts to rotation, screen size, density and punch-hole cutouts

### 🧹 Under the hood

- Density-safe fallback instead of a hardcoded pixel value
- Hardened hooks: precise `dispatchTouchEvent` resolution and safe failure logging

## v1.2 · 2026-08-26

> A dynamic dead-zone, and the power menu is next.

### ✨ Highlights

- **Dynamic dead-zone** for more devices and layouts

### 🐞 Fixes

- Status bar icons are no longer hidden

### 🔄 Changes

- Power menu blocking is on the way

## v1 · 2026-08-19

> The first release.

### 🐞 Fixes

- Compatibility with older Android versions
