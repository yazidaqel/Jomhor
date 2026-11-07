import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "ma.jomhor.scanner"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "ma.jomhor.scanner"
        // Android 10+ (plan lot-2 Q28). The device key is ECDSA P-256 (Keystore has Ed25519 only from API 33).
        minSdk = 29
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // Release signing from android/key.properties (git-ignored, never committed):
    // storeFile=<path to .jks>, storePassword=, keyAlias=, keyPassword=
    val keyProperties = rootProject.file("key.properties")
    signingConfigs {
        if (keyProperties.exists()) {
            val props = Properties().apply { keyProperties.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

// Fail a release build without a key instead of signing with the debug key. Debug builds are unaffected.
gradle.taskGraph.whenReady {
    val release = allTasks.any { it.project == project && it.name.contains("Release") }
    if (release && !rootProject.file("key.properties").exists()) {
        throw GradleException(
            "Release signing: android/key.properties not found. Create it (git-ignored) with " +
                "storeFile, storePassword, keyAlias and keyPassword; see android/app/build.gradle.kts.",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
