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
        versionCode = 4
        versionName = "4.0-native"
        // Метка сборки (время МСК) — видна в подзаголовке экрана «Чаты»
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "UPDATE_REPO", "\"DarkAlmas/messenger-android\"")
        ndk {
            // libsignal: только 64-битные ARM-телефоны (библиотека ~70 МБ на ABI)
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    // Release-ключ: путь и пароль только из env или ~/.gradle/gradle.properties (MSGR_STORE_FILE, MSGR_STORE_PASSWORD, MSGR_KEY_ALIAS)
    fun secret(n: String): String? = System.getenv(n) ?: (project.findProperty(n) as String?)
    val relStore = secret("MSGR_STORE_FILE")
    if (relStore != null) {
        signingConfigs.create("release") {
            storeFile = file(relStore)
            storeType = "PKCS12"
            storePassword = secret("MSGR_STORE_PASSWORD")
            keyAlias = secret("MSGR_KEY_ALIAS") ?: "messenger"
            keyPassword = secret("MSGR_STORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            if (relStore != null) signingConfig = signingConfigs.getByName("release")
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
