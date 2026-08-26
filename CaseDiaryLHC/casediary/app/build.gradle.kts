plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pk.advocate.casediary"
    compileSdk = 34

    // CI passes the workflow run number so every build is a strictly higher
    // versionCode than the last, and the in-app update checker (see
    // util/UpdateChecker.kt) can tell a newer release apart from the one
    // that's installed. Local/manual builds fall back to a fixed dev version.
    val ciVersionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: 1
    val ciVersionName = System.getenv("APP_VERSION_NAME") ?: "1.0-dev"

    defaultConfig {
        applicationId = "pk.advocate.casediary"
        minSdk = 24
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName
        buildConfigField("String", "GITHUB_REPO", "\"zillrh35-Farzillaw/My-LHC-cases-check-app\"")
    }

    // A fresh CI runner has no ~/.android/debug.keystore, so Gradle's default
    // behaviour is to auto-generate a brand-new one — with a brand-new random
    // key — on every single build. Every APK would then carry a different
    // signature, and Android refuses to install one app "over" another
    // signed with a different key ("package conflicts with an existing
    // package"), which defeats the whole point of the in-app update checker.
    // Signing every build with this one committed keystore instead keeps the
    // signature identical release after release, so updates install in place.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jsoup:jsoup:1.17.2")

    testImplementation("junit:junit:4.13.2")
}
