plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Braininger Lite für das Mudita Kompakt (E-Ink, Android 12, ohne Google-Dienste).
// Bewusst ohne Compose und ohne Bibliotheken: klein, schnell auf dem Helio A22, keine Animationen.
val schluesselDatei: String? = System.getenv("BRAININGER_KEYSTORE")

android {
    namespace = "de.braininger.lite"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.braininger.lite"
        minSdk = 31 // Kompakt: Android 12 (API 31)
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        if (schluesselDatei != null) {
            create("release") {
                storeFile = file(schluesselDatei)
                storePassword = System.getenv("BRAININGER_KEYSTORE_PASSWORT")
                keyAlias = System.getenv("BRAININGER_KEY_ALIAS")
                keyPassword = System.getenv("BRAININGER_KEY_PASSWORT")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (schluesselDatei != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
