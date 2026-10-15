plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "belot.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "md.belot.troe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A key kept in the repository so that every build (local or CI) can be installed over the previous one.
        create("shared") {
            storeFile = file("belot-shared.jks")
            storePassword = "belot-shared"
            keyAlias = "belot"
            keyPassword = "belot-shared"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
}
