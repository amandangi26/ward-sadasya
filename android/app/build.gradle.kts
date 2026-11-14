plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.coceptual.wardsadasya"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.coceptual.wardsadasya"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "2.0"
        buildConfigField("String", "API_URL", "\"${project.findProperty("WS_API_URL")}\"")
        buildConfigField("String", "ANON_KEY", "\"${project.findProperty("WS_ANON_KEY")}\"")
        // Firebase (instant notifications). Leave empty to run without push; values come from gradle.properties or CI secrets.
        buildConfigField("String", "FCM_APP_ID", "\"${project.findProperty("FCM_APP_ID") ?: ""}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${project.findProperty("FCM_API_KEY") ?: ""}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${project.findProperty("FCM_PROJECT_ID") ?: ""}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${project.findProperty("FCM_SENDER_ID") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "wardsadasya123"
            keyAlias = "wardsadasya"
            keyPassword = "wardsadasya123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:24.0.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
