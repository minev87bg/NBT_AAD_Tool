plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The GitHub build passes its run number, so every build is newer than the one before and the in-app updater can
// tell (versionCode 57 = release v1.0.57). A build on a developer machine is "dev" with code 1.
val appVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (findProperty("appVersionName") as String?) ?: "dev"

// Android installs an update over an installed app only when both are signed with the same key. The GitHub build
// hands its key in through these variables; without them (a local build) the usual debug key is used.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "com.nbtaad.editor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nbtaad.editor"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("app") {
            signingKeystore?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("app")
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/**"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // walks a user-picked folder tree (OpenDocumentTree) to find the owner's own PSdZData security/ files
    implementation("androidx.documentfile:documentfile:1.0.1")
    // maintained JSch fork: still supports the old ssh-dss / ssh-rsa / hmac-sha1 the head unit needs
    implementation("com.github.mwiede:jsch:0.2.21")

    // plain JVM tests of the settings parser (run by the GitHub build)
    testImplementation("junit:junit:4.13.2")
}
