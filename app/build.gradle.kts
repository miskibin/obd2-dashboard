import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.miskibin.obd2dashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.miskibin.obd2dashboard"
        minSdk = 26
        targetSdk = 35
        // Bumped per tagged release. The name is what the mechanic report prints at the
        // top of itself, so it has to be the version somebody could be asked to reinstall;
        // the code is what lets a newer APK replace an older one on the phone.
        versionCode = 4
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // Only ship the locales we actually translate.
        localeFilters += setOf("en", "pl")
    }

    signingConfigs {
        // Shared debug keystore checked into the repo (standard debug credentials,
        // nothing secret) so every CI build carries the same signature and the APK
        // can be updated in place on the phone.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The Play upload key, read from an untracked keystore.properties in the repo
        // root (storeFile, storePassword, keyAlias, keyPassword). The config only exists
        // when the file does, so a machine without the key still builds everything else.
        if (rootProject.file("keystore.properties").exists()) {
            create("release") {
                val props = Properties()
                rootProject.file("keystore.properties").inputStream().use(props::load)
                // Named at the point of failure: a typo in one key would otherwise
                // surface as "file(null)" with no mention of which property was wrong.
                fun prop(name: String): String = props.getProperty(name)
                    ?: error(
                        "keystore.properties is missing '$name' " +
                            "(expected storeFile, storePassword, keyAlias, keyPassword)",
                    )
                storeFile = rootProject.file(prop("storeFile"))
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // The Play upload key when keystore.properties is present; otherwise the
            // shared debug key, so a release build stays installable and upgradeable on
            // a machine that does not hold the upload key.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Uploading a bundle to Play, without a browser and without a file picker.
//
// The plugin only exists when `play-service-account.json` does, mirroring how the
// upload key is wired above: a clone without the key still builds and tests
// everything, it just has no `publish*` tasks. The key is a Google Cloud service
// account that was granted release access in Play Console; it is gitignored, and
// it is not the upload key - it proves *who is uploading*, not *what is signed*.
val playCredentials = rootProject.file("play-service-account.json")
if (playCredentials.exists()) {
    apply(plugin = "com.github.triplet.play")
    configure<com.github.triplet.gradle.play.PlayPublisherExtension> {
        serviceAccountCredentials.set(playCredentials)
        // Bundles, never APKs: Play rejects APKs for new apps anyway.
        defaultToAppBundles.set(true)
        // Internal testing by default. Promoting further is a deliberate act:
        // `./gradlew promoteArtifact --from-track internal --promote-track production`.
        track.set("internal")
        releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
        // Fail loudly when versionCode was not bumped, rather than silently
        // uploading a build Play will refuse or, worse, quietly ignore.
        resolutionStrategy.set(com.github.triplet.gradle.androidpublisher.ResolutionStrategy.FAIL)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.car.app)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
