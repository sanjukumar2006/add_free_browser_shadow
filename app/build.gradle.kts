plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nova.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nova.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Your VPN control-plane API (see backend/README.md). No keys or credentials live in the app.
        buildConfigField("String", "VPN_API_BASE", "\"https://vpn-api.example.com\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { warningsAsErrors = false; abortOnError = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // 1.12+ is required for Profile APIs (WebViewFeature.MULTI_PROFILE) used by private tabs.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WireGuard userspace tunnel (Apache-2.0)
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    // Tor (Guardian Project tor-android, see README for licensing/version notes)
    implementation("info.guardianproject:tor-android:0.4.7.14")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    testImplementation("junit:junit:4.13.2")
}
