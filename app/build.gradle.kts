import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
}

val buildTime: String = ZonedDateTime.now(ZoneId.of("Europe/Moscow")).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))

android {
    namespace = "com.darkcompany.messenger"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "com.darkcompany.messenger.nativeapp"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "3.0-native"
        // Метка сборки (время МСК) — видна в подзаголовке экрана «Чаты»
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "UPDATE_REPO", "\"DarkAlmas/messenger-android\"")
        ndk {
            // libsignal: только 64-битные ARM-телефоны (библиотека ~70 МБ на ABI)
            abiFilters += listOf("arm64-v8a")
        }
    }
    // Один и тот же debug-ключ (хранится вне git: ../../android-native-keys), чтобы обновления ставились поверх
    val sharedKey = rootProject.file("../android-native-keys/debug.keystore")
    if (sharedKey.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = sharedKey
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources {
            // Десктопные нативные библиотеки libsignal-client (Windows/macOS/Linux) в APK не нужны
            excludes += listOf("libsignal_jni*.so", "libsignal_jni*.dylib", "signal_jni*.dll")
        }
        jniLibs {
            excludes += listOf("**/libsignal_jni_testing.so")
            useLegacyPackaging = true // сжимать нативные библиотеки в APK
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Официальный libsignal (X3DH/PQXDH + Double Ratchet)
    implementation("org.signal:libsignal-android:0.86.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.json:json:20240303")
    testImplementation("org.signal:libsignal-client:0.86.5")
}
