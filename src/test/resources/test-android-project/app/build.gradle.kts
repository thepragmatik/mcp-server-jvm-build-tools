plugins {
    id("com.android.application") version "8.11.0"
    id("kotlin-android")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}

android {
    compileSdk = 36
    namespace = "com.example.app"

    defaultConfig {
        minSdk = 24
        targetSdk = 36
    }

    buildTypes {
        debug {
        }
        release {
        }
        staging {
        }
    }
}
