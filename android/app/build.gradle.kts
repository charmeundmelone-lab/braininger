plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Der Signierschlüssel liegt nie im Projekt. Bei GitHub kommt er aus verschlüsselten Secrets,
// die der Arbeitsablauf als Umgebungsvariablen übergibt.
val schluesselDatei: String? = System.getenv("BRAININGER_KEYSTORE")

android {
    namespace = "de.braininger.app"
    compileSdk = 35
    // Spracherkennung (whisper.cpp) wird mit dem NDK übersetzt; auf GitHub liegt es unter ANDROID_NDK_LATEST_HOME
    System.getenv("ANDROID_NDK_LATEST_HOME")?.let { pfad ->
        ndkPath = pfad
        // ndkVersion muss zur Version unter ndkPath passen (steht in source.properties)
        file("$pfad/source.properties").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("Pkg.Revision") }?.substringAfter("=")?.trim()?.let { ndkVersion = it }
    }

    defaultConfig {
        applicationId = "de.braininger.app"
        minSdk = 33 // Glyph Developer Kit verlangt 33; Braininger läuft nur auf Jans Phone (3a) mit Android 16
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk { abiFilters += "arm64-v8a" } // nur Jans Phone (3a); whisper.cpp ist auf ARMv8.2 mit i8mm abgestimmt
        externalNativeBuild {
            cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static") }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        // Die Claude-Bibliothek bringt doppelte Lizenz-/Metadateien mit
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin")
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Glyph Developer Kit von Nothing: lädt der Bau auf GitHub nach libs/ (keine Lizenz zum Weitergeben im Repo)
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Claude direkt vom Handy (offizielles SDK von Anthropic, Version vom 30.09.2026)
    implementation("com.anthropic:anthropic-java:2.68.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
