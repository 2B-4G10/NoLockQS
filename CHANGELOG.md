# Changelog

## v2.0

- Vector's module list now shows what NoLockQS does under its name
- Built with Kotlin 2.4.20 and against the newest Android 17 SDK (API 37.2)
- Cleaner code: the legacy Xposed entries are gone from the manifest, as Vector reads the module from its libxposed files, and lint reports no warnings
- Installs over v1.9 as an update

## v1.9

- Every release is now also published to the Xposed Modules Repository ([modules.lsposed.org](https://modules.lsposed.org/module/io.github.i2B4G10.NoLockQS))
- Its package name is now `io.github.i2B4G10.NoLockQS`, the name the repository lists it under. This is a one-time reinstall: uninstall the previous NoLockQS, install this one, then enable it again in Vector for System UI and System Framework

## v1.8

- Turn Quick Settings and power menu blocking on or off separately: tap a box in the app, green means on and grey means off
- The status card now says what is blocked, and turns red when every protection is off
- The heart in the support section is now red

## v1.7

- Releases are now signed with a permanent key: after this one-time reinstall, future updates install over the previous version

## v1.6

- Redesigned module screen: a status card that turns green or red, a list of what is blocked while the phone is locked, and a cleaner header
- Support NoLockQS on Patreon straight from the app

## v1.5

- Refreshed module screen: Material You colors, a status card, and a layout that stays clear of the status bar and scrolls in any orientation
- Added the Apache License 2.0

## v1.4

- New app icon
- Power menu blocked on the lock screen for every trigger (enable System Framework in the scope)
- Hooks resolve from fallback lists, so new Pixel and Android releases keep working, including the new scene-container shade
- A gesture that starts in the dead-zone is blocked until the finger lifts, not just its first touch
- Hot reload now moves live hooks to the updated code
- Module status screen shows ACTIVE again, plus the installed version
- Updated Android Gradle Plugin 9.4.1, Gradle 9.8.0 and core-ktx 1.19.1

## v1.3

- Dead-zone measured live from the window insets (status bar and display cutout)
- Adapts automatically to rotation, screen size, density and punch-hole cutouts
- Density-safe fallback instead of a hardcoded pixel value
- Hardened hooks: precise `dispatchTouchEvent` resolution and safe failure logging

## v1.2

- Dynamic dead-zone set for versatility
- Fixed hidden status bar icons
- Power menu disabling on the way!

## v1

- Fixed compatibility with older Android versions
