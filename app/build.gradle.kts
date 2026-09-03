import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Upload keystore lives outside the repo (~/.riddleboox/keystore) — this repo has no
// .gitignore history to trust yet, so signing secrets never touch the working tree.
// CI can point RIDDLEBOOX_KEYSTORE_PROPERTIES at an equivalent file instead.
val keystorePropertiesFile = System.getenv("RIDDLEBOOX_KEYSTORE_PROPERTIES")
    ?.let { File(it) }
    ?: File(System.getProperty("user.home"), ".riddleboox/keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

// google-services.json identifies a real Firebase project and isn't something
// to fake or generate here — see docs/crash-reporting.md for how to get one.
// Applying the plugin unconditionally would break every build that hasn't set
// one up yet, the same reason signingConfigs.release above is conditional.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.riddleboox.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.riddleboox.app"
        minSdk = 29
        // Google Play yêu cầu targetSdk 36 cho bản phát hành mới từ 31/8/2026.
        targetSdk = 36
        versionCode = 8
        versionName = "0.4.4"

        buildConfigField("String", "LLM_API_KEY", "\"${localProperties.getProperty("llm.apiKey", "")}\"")
        buildConfigField("String", "LLM_BASE_URL", "\"${localProperties.getProperty("llm.baseUrl", "https://openrouter.ai/api")}\"")
        buildConfigField("String", "LLM_MODEL", "\"${localProperties.getProperty("llm.model", "openai/gpt-5.6-luna")}\"")
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Public builds must never embed the developer's key from local.properties;
            // users bring their own key via Settings (SettingsStore falls back to this default).
            buildConfigField("String", "LLM_API_KEY", "\"\"")
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                abiFilters += setOf("armeabi-v7a", "arm64-v8a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
            )
        }
        jniLibs {
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Oracle turns route through koog's OpenAILLMClient (see AskDiary.kt).
    // http-client-ktor is declared explicitly and passed to OpenAILLMClient as a
    // KoogHttpClient.Factory: koog-agents alone does not resolve a default HTTP
    // client factory from the classpath (IllegalStateException: "No
    // KoogHttpClient.Factory provider found").
    implementation("ai.koog:koog-agents:1.1.1")
    implementation("ai.koog:http-client-ktor:1.1.1")
    if (hasFirebaseConfig) {
        implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
        implementation("com.google.firebase:firebase-crashlytics")
    }
    testImplementation("junit:junit:4.13.2")
    // RiddleStateMachine wires a real android.view.View (RegionView) and calls
    // android.graphics.Bitmap/Canvas (PageRasterizer) as part of its normal
    // request path; those throw "not mocked" under the plain unit-test stub
    // jar. Robolectric is the one place in this module that needs it, so it
    // stays test-only rather than pulling every JVM test into requiring it.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
