# Changelog

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
