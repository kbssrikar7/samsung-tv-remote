import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kbs.tvremote"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    // Play release signing comes from keystore.properties (not in git). Without it, the Play build
    // falls back to the debug key so anyone can still build and sideload the app.
    val keystoreProps = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("upload") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 strips the unused parts of the Material icon set, keeping the APK small.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // "play" is the Google Play build: YouTube search hands off to the official YouTube app.
    // "full" adds in-app YouTube search, playing on the TV and now playing through YouTube's
    // unofficial web and TV-link APIs, which Play doesn't allow; it is distributed on GitHub.
    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            applicationId = "com.kbs.remoteforsamsungtv"
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "YOUTUBE_IN_APP", "false")
            resValue("string", "app_name", "Remote for Samsung TV")
        }
        create("full") {
            dimension = "store"
            applicationId = "com.kbs.tvremote"
            // Sideloaded builds keep the debug key so updates install over earlier GitHub releases.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "YOUTUBE_IN_APP", "true")
            resValue("string", "app_name", "TV Remote")
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
        buildConfig = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
