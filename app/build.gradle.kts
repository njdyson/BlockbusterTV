plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.njdyson.blockbustertv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.njdyson.blockbustertv"
        // API 22 = Fire OS 5, the oldest Fire TV Stick firmware still in use.
        minSdk = 22
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-linklab"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded personal build: sign release with the debug key so it installs anywhere.
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
