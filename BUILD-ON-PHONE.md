# ZAPP-Net — editing and building on the phone

Everything in this zip is the real source. `app/build/`, `.gradle/` and `.kotlin/`
are excluded on purpose: they are caches, they are 200+ MB, and Gradle regenerates
them on the first build.

## What is inside

```
ZAPP-Net/
  app/
    src/main/java/com/zapp/net/     74 Kotlin files - the whole app
    src/main/res/                    layouts, icons, strings
    src/main/assets/                 serve.sh, zapp_proxy.py (run inside Termux)
    src/test/                        unit tests (IpIdentityTest and friends)
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  gradle/wrapper/                    Gradle 8.14.5 wrapper
  BUILD-ON-PHONE.md                  this file
```

## Editing on the phone

Any editor that saves plain text works. Good ones on Android:

- **Acode** (free) - opens a folder as a project, syntax highlighting for Kotlin.
- **Squircle IDE** (free) - real project tree, Kotlin aware.
- **Termux + nano/vim** if you want to stay in one shell.

Open the unzipped `ZAPP-Net` folder, not the zip. Keep the folder structure exactly
as it is - Gradle finds sources by path, so moving a file breaks the build.

## Building on the phone

The app is a normal Gradle project. Inside Termux:

```bash
pkg update
pkg install openjdk-21 gradle unzip
cd ~/ZAPP-Net
gradle assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Two things that will bite on a phone:

1. **Memory.** Gradle's default heap is too small for this project. Put this in
   `gradle.properties` if the build dies with `OutOfMemoryError`:
   ```
   org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m
   ```
2. **Time.** A cold build on a phone is 5-15 minutes. Warm rebuilds are under a
   minute. Do not assume it hung.

If Gradle on the phone is too painful, the alternative is to edit here and send
the changed file back to the PC - a single `.kt` file is small, and the build
there takes 10 seconds.

## Where the interesting code is

| What | File |
|---|---|
| Root composable, gate, tab bar | `MainActivity.kt` |
| The card that greets every launch | `ui/StandardUserDialog.kt` |
| Setup flow (consent, address, Termux) | `onboarding/OnboardingScreen.kt` |
| Persisted setup state, device token | `onboarding/Onboarding.kt` |
| Discord invite card and launch card | `ui/DiscordInviteDialog.kt` |
| Chat screen | `ui/ChatScreen.kt` |
| HTTP client, auth header | `api/OpenCodeClient.kt` |
| Settings (DataStore) | `settings/SettingsStore.kt` |
| Server launcher, hands `serve.sh` to Termux | `termux/BootRelay.kt` |
| Rotation / guard | `guard/Guard.kt`, `guard/RotationEngine.kt` |

## The one thing not to touch

`WallGate.rotateOnce` in `guard/Guard.kt`, the rotation block in `assets/serve.sh`,
and the header of `assets/zapp_proxy.py` all carry the same warning:

> DO NOT TOUCH THE ROTATION

It is risky by design - address-scoped allowance, per-source registration rate
limit, shared `/64` pool. Leave the logic alone unless you are deliberately
redesigning it.
