import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.zhinibgdu.xianyu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhinibgdu.xianyu"
        minSdk = 26
        targetSdk = 35

        versionCode = 89
        versionName = "4.47.1"
    }

    // Stable signing support.
    // Configure signing.properties locally or GitHub Actions secrets before release builds.
    val signingPropsFile = rootProject.file("signing.properties")
    val signingProps = Properties()
    if (signingPropsFile.exists()) {
        signingProps.load(signingPropsFile.inputStream())
    }

    signingConfigs {
        create("stable") {
            if (signingPropsFile.exists()) {
                storeFile = rootProject.file(signingProps["storeFile"] as String)
                storePassword = signingProps["storePassword"] as String
                keyAlias = signingProps["keyAlias"] as String
                keyPassword = signingProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }

        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
}
