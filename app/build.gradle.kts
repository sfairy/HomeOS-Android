plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

import java.util.Properties

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.homeos.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.homeos.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2026061507
        versionName = "2026.06.15.07"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
            ?: System.getenv("RELEASE_STORE_PASSWORD")
        val keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            ?: System.getenv("RELEASE_KEY_PASSWORD")
        if (storePassword != null && keyPassword != null) {
            create("release") {
                storeFile = file("homeos-release-key.jks")
                this.storePassword = storePassword
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS") ?: "homeos"
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    buildFeatures {
        viewBinding = true
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val fileName = "HomeOSAPP+${variant.versionName}.apk"
            output.outputFileName = fileName
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.10.0")

    // OkHttp & Gson removed — network uses HttpURLConnection; no JSON library needed

    // Google FCM
    implementation("com.google.firebase:firebase-messaging-ktx:23.4.1")

    // Huawei HMS Push fallbacks
    implementation("com.huawei.hms:push:6.12.0.300")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
