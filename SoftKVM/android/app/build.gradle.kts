plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// KVM 폴더의 VERSION.txt(예: 1.6.0)를 앱 버전으로 사용 → 10600
val kvmVersion: String = rootProject.file("../VERSION.txt").let { if (it.exists()) it.readText().trim() else "1.0.0" }
val kvmCode: Int = kvmVersion.split(".").map { it.toIntOrNull() ?: 0 }.let {
    it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }
}

android {
    namespace = "com.example.kvm"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.kvm"
        minSdk = 26
        targetSdk = 35
        versionCode = kvmCode
        versionName = kvmVersion
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
