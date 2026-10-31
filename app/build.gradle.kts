plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.readaloud"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.readaloud"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // PDF text-layer extraction. No AndroidX, no Compose -> small APK.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device OCR for scanned PDFs and images (bundled model, works offline).
    // Devanagari model reads Hindi and Latin (English) text.
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}
