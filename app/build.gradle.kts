plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.xapkinstaller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.xapkinstaller"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // One permanent key committed to the repo, so every build is signed the same way
    // and new versions install over old ones. No GitHub secrets needed.
    signingConfigs {
        create("permanent") {
            storeFile = file("signing.keystore")
            storePassword = "feynosxapk"
            keyAlias = "xapkinstaller"
            keyPassword = "feynosxapk"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("permanent")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("permanent")
        }
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
}
