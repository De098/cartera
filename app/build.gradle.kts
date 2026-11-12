plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.de098.cartera"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.de098.cartera"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // F-Droid firma con su propia clave: aquí no hay firma ni nada personal
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    // sin bloques de metadatos de dependencias en el APK (compilación reproducible)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // la única dependencia: generar y leer códigos de barras y QR (Apache 2.0, Java puro, sin red)
    implementation("com.google.zxing:core:3.5.3")
}
