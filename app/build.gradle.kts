import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signing material lives OUTSIDE git: keystore/hearth-tv.jks + passwords in local.properties (both gitignored).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystoreFile = rootProject.file("keystore/hearth-tv.jks")
val hasReleaseKey = keystoreFile.exists() && localProps.getProperty("HEARTHTV_STORE_PASSWORD") != null

android {
    namespace = "com.alec.hearthtv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alec.hearthtv"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
        // Where the app looks for a newer build (GitHub Pages) and where it sends the browser to get it.
        buildConfigField("String", "UPDATE_URL", "\"https://willits-alec.github.io/hearth-tv/version.json\"")
        buildConfigField("String", "APK_URL", "\"https://github.com/Willits-Alec/hearth-tv/releases/latest/download/hearth-tv.apk\"")
        buildConfigField("String", "PAGE_URL", "\"https://willits-alec.github.io/hearth-tv/\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = localProps.getProperty("HEARTHTV_STORE_PASSWORD")
                keyAlias = localProps.getProperty("HEARTHTV_KEY_ALIAS") ?: "hearthtv"
                keyPassword = localProps.getProperty("HEARTHTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// TDD gate (SCOPE.md §5 rule 6): a release APK cannot be produced while the JVM suite is red.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("testDebugUnitTest")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
