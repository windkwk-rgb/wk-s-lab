plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 버전은 저장소 루트의 version.txt 하나로 관리 (노트북 앱과 공유)
// 1.2.3 → versionCode 10203
val appVersion = rootProject.file("../version.txt").readText().trim()
val (vMajor, vMinor, vPatch) = appVersion.split(".").map { it.toInt() }

android {
    namespace = "com.penbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.penbridge"
        minSdk = 26          // Android 8.0 이상
        targetSdk = 35
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = appVersion
    }

    // 서명 키: GitHub 자동 빌드는 PENBRIDGE_KEYSTORE(노트북의 debug.keystore와 같은 키)를 쓰고,
    // 노트북에서 빌드하면 기본 debug 키를 씀 → 어디서 빌드해도 같은 서명이라 태블릿 앱이 그대로 업데이트됨
    val ciKeystore = System.getenv("PENBRIDGE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ciKeystore != null) {
            create("penbridge") {
                storeFile = ciKeystore
                storePassword = System.getenv("PENBRIDGE_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("PENBRIDGE_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("PENBRIDGE_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (ciKeystore != null) "penbridge" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // 첫 화면 (Jetpack Compose + Material 3)
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
