plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.pingpong"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.pingpong"
        minSdk = 26
        targetSdk = 34
        versionCode = 10202
        versionName = "1.2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // ML Kit — on-device translation (models downloaded on first use per language)
    implementation("com.google.mlkit:translate:17.0.3")

    // Vosk — fully offline speech recognition
    implementation("com.alphacephei:vosk-android:0.3.47")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}