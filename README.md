# The NoLockQS Project. 🔐🚫

## Prevents Access to QuickSettings/Control Center tiles and the Power Menu on a Locked Screen for Rooted Pixel Phones!

## Built on the modern Xposed API (libxposed 101+), so it is not tied to a specific Pixel model or Android release.

## Requirements:
- Rooted Pixel running Android 15 or newer (tested on Android 15-17) 🤖
- Vector 2.2 (Minimum) 🚀

## Instructions:
- Install the APK on your rooted device.
- Open the LSPosed Manager, enable the module, and check **System UI** and **System Framework** in the scope ("What The Module Asks for").
- Grant Root Privileges (Preferred)
- Reboot and the Protection will be Active!

System UI blocks the Quick Settings pull-down; System Framework blocks the power menu.
To pause the protection without uninstalling, run `su -c setprop persist.sys.nolockqs.enabled false` (set it back to `true` to resume).

## Releases:
- The version lives in `app/src/main/resources/META-INF/xposed/module.prop` (`version` and `versionCode`).
- Every push to `main` is built by GitHub Actions. When `version` is new, the release `v<version>` is published with the APK attached.
- Releases are signed with the key stored in these repository secrets: `NOLOCKQS_KEYSTORE_BASE64` (the keystore file, base64-encoded), `NOLOCKQS_KEYSTORE_PASSWORD`, `NOLOCKQS_KEY_ALIAS` and `NOLOCKQS_KEY_PASSWORD`. Without them, the release is saved as a draft signed with a temporary key.

Collaborators: Just Me!

# You Can show your Love here!❤️ https://patreon.com/Dossary

## Made in Saudi 🇸🇦
