plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10" apply false
    // Only ever applied in app/build.gradle.kts when app/google-services.json
    // exists — see docs/crash-reporting.md. Registered here regardless so
    // that conditional apply(plugin = "...") call can resolve a version.
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
}