import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Release signing.
 *
 * `keystore.properties` is gitignored and lives outside the repo in `ZAPP-KEYS/`. When it is
 * present the release is signed with ZAPP's own 4096-bit RSA key; when it is missing (a fresh
 * clone, CI) the build falls back to the local debug key so `assembleRelease` still works.
 *
 * Why it matters: with a private key nobody can take this APK, modify it, sign it again and
 * install it as an update over ZAPP's - Android rejects the signature mismatch. The shared
 * Android debug key, which every install of the SDK has, allowed exactly that.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.zapp.net"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zapp.net"
        minSdk = 26
        targetSdk = 36
        versionCode = 97
        versionName = "1.0.97"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("zappRelease") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Debuggable=false is the whole point: a debug APK runs with ART's optimisations
            // off and Compose's debug checks on, which is the "lag yung app" he reported.
            // Minify stays OFF until the R8 rules are proven - a stripped reflection path
            // (kotlinx.serialization models, the Shizuku binder) would break the app in a way
            // that cannot be debugged from a release build.
            isMinifyEnabled = false
            isDebuggable = false
            // ZAPP's own key when it is available, debug otherwise. See the block above.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("zappRelease")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // AGP 8 turns AIDL off by default. The Shizuku user service needs it: the binder
        // interface is how a class of ours runs inside the server process as uid shell.
        aidl = true
    }

    // app/src/main/jniLibs/arm64-v8a/libwg-go.so is the AAR blob with its baked-in UAPI
    // socket path rewritten to our own package (tools/patch-wg-go.ps1). Ours must win.
    androidResources {
        // assets/termux/termux.apk is itself a zip, so deflating it again buys nothing and
        // costs a full decompress on every unpack. Stored, the copy out of assets is I/O only.
        noCompress += "apk"
    }
    packaging {
        jniLibs {
            pickFirsts += "lib/arm64-v8a/libwg-go.so"
            // The embedded-server experiment (see `EmbeddedServer`): these are real ELF
            // files, not shared libraries, and AGP's strip step would corrupt a 237 KB
            // loader. Seed them unchanged.
            keepDebugSymbols += listOf("**/libocld.so", "**/liboclibc.so", "**/liboc*.so")
            // `extractNativeLibs` in the manifest is what puts them on disk where exec is
            // allowed; without legacy packaging AGP keeps them inside the APK and there is
            // no path to execute.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 	ests/test_reliability.py parity: the ported decision core is checked on the JVM
    testImplementation("junit:junit:4.13.2")

    // OpenCode API client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WARP tunneling (WireGuard protocol) + VPN
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("androidx.core:core-ktx:1.15.0")
    // Shizuku: a shell-uid binder server started once over ADB. This is the only way an
    // unrooted app gets `pm`, `am`, `input`, `dumpsys`, `screencap`. `provider` ships the
    // MultiprocessPermissionRequester/Provider that the server uses to read our uid.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
