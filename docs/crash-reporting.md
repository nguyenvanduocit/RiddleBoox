# Crash reporting (Firebase Crashlytics)

Source of truth for how field crashes get reported, and the one manual step needed before
they actually do — nothing here should have to be re-derived from `app/build.gradle.kts` cold.

## Current state

`app/build.gradle.kts` wires the Crashlytics Gradle plugin and dependency **conditionally**, on
whether `app/google-services.json` exists (`hasFirebaseConfig`) — the exact same pattern
`signingConfigs.release` already uses for `keystore.properties`. Without that file:

- The build compiles and runs exactly as before.
- No Firebase code, dependency, or network call is present in the APK at all.

`app/google-services.json` is gitignored (`.gitignore`): it identifies a specific Firebase
project and isn't something to check into a repo before that project exists.

## One-time setup

1. Go to the [Firebase console](https://console.firebase.google.com/), create a project (or
   reuse an existing one for RiddleBoox).
2. Add an Android app to it with package name `com.riddleboox.app` (must match
   `applicationId` in `app/build.gradle.kts` exactly).
3. Download the generated `google-services.json` and place it at `app/google-services.json`
   (repo root's `app/` directory, next to `build.gradle.kts`).
4. In the Firebase console, enable **Crashlytics** for the app (Release & Monitor →
   Crashlytics → "Enable Crashlytics").
5. Build and run the app once — Crashlytics registers itself automatically (no code changes
   needed; the SDK installs its own uncaught-exception handler and reports on the next launch
   after a crash). Nothing in `RiddleBooxApp.kt` needs to call an init method.

## Verifying it actually works

Crashlytics batches and uploads on the *next* app start after a crash, not the crash itself —
force one to confirm the pipeline end-to-end:

```kotlin
// Temporary, in any reachable place (e.g. a button's onClick) — remove after verifying.
throw RuntimeException("Test crash for Crashlytics verification")
```

Run the app, trigger the crash, relaunch the app once (this is when the report actually
uploads), then check the Firebase console's Crashlytics dashboard — the event usually appears
within a few minutes.

## Privacy note

RiddleBoox is a diary app; crash reports can incidentally carry stack traces or breadcrumbs
touching app state. Default Crashlytics collection does not include user-generated diary text
(no code here calls `log()`/`setCustomKey()` with page or reply content) — keep it that way:
do not add custom Crashlytics logging that includes conversation transcripts, replies, or raw
ink data.
