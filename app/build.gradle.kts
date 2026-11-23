plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.jzzy.picoamp"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.jzzy.picoamp"
        minSdk = 24
        targetSdk = 35
        versionCode = (System.getenv("PICOAMP_BUILD") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("PICOAMP_BUILD") ?: "0")
    }

    signingConfigs {
        // a key for sideloading only (in the repository, so every build can update the last one);
        // a Play Store release gets its own upload key
        create("sideload") {
            storeFile = file("../keystore/sideload.jks")
            storePassword = "picoamp"
            keyAlias = "picoamp"
            keyPassword = "picoamp"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // (the program's WebAssembly as it is)
    androidResources { noCompress += listOf("wasm") }

    // (smbj's Bouncy Castle: its Java 9 manifests are not the app's)
    packaging {
        resources.excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    // Windows and NAS shares (SMB 2/3), for the pictures
    implementation("com.hierynomus:smbj:0.13.0")
}
