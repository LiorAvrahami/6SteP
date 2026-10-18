plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber: String = System.getenv("BUILD_NUMBER") ?: "1"

android {
    namespace = "org.lior.sixstep"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.lior.sixstep"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber.toInt()
        versionName = "1.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // committed on purpose: every build signs with the same key so each new APK installs over the last one
            storeFile = file("keystore/sixstep.p12")
            storePassword = "sixstep"
            keyAlias = "sixstep"
            keyPassword = "sixstep"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    androidResources {
        noCompress.add("mp3")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
