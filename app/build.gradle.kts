plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.copokbl.orbitgolf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.copokbl.orbitgolf"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so the release APK installs straight onto a phone.
            // Swap in your own signingConfig if you ever publish it.
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("shots.dir", System.getProperty("shots.dir") ?: "")
            System.getProperty("robolectric.dependency.repo.url")?.let { url ->
                it.systemProperty("robolectric.dependency.repo.url", url)
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
