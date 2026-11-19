plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.altyazi.canli"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.altyazi.canli"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Sabit imza anahtarı: her yeni sürüm eskisinin üstüne kurulabilsin diye.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Google ML Kit: telefonun içinde çalışan İngilizce → Türkçe çeviri
    implementation("com.google.mlkit:translate:17.0.3")
}
