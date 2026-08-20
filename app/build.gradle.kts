import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing comes from local.properties, which is gitignored — the keystore and its
 * passwords must never land in the repo. If release.storeFile isn't set, the release build
 * type is left unsigned (fine for CI/local verification builds); if it is set, the keystore
 * it points at must actually exist, or the build fails below with a clear message instead of
 * a confusing signing-tool error.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStoreFile = localProperties.getProperty("release.storeFile")

android {
    namespace = "dev.mike.couchtour"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mike.couchtour"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        // A workflow_dispatch release build passes -PversionName=$TAG (see
        // build-debug-apk.yml) so BuildConfig.VERSION_NAME reflects the actual release tag
        // (e.g. "v0.23") rather than this static placeholder; local and plain CI push builds
        // fall back to it.
        versionName = project.findProperty("versionName") as? String ?: "1.0"
        manifestPlaceholders["appLabel"] = "Couch Tour"
        manifestPlaceholders["appIcon"] = "ic_launcher"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                val file = rootProject.file(releaseStoreFile)
                if (!file.exists()) {
                    error(
                        "local.properties sets release.storeFile=$releaseStoreFile, but no " +
                            "keystore exists at that path. Generate one (see CLAUDE.md) or " +
                            "remove the release.* properties to produce an unsigned build."
                    )
                }
                storeFile = file
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Opt-in only (-PsideInstall=true): a distinct applicationId so this build
            // installs alongside the regular debug sideload rather than updating over it —
            // for trying a risky change without disturbing the working install. Every
            // ordinary debug build (local or CI) is unaffected and keeps sharing one
            // applicationId/signing key so it always updates in place, per
            // build-debug-apk.yml's own reasoning.
            if (project.findProperty("sideInstall") == "true") {
                applicationIdSuffix = ".beta"
                versionNameSuffix = "-beta"
                manifestPlaceholders["appLabel"] = "Couch Tour Beta"
                // A ribboned launcher icon, so the side-installed build is visually
                // distinguishable from the regular app in the launcher/app drawer, not
                // just by label text.
                manifestPlaceholders["appIcon"] = "ic_launcher_beta"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        unitTests {
            // Robolectric needs the merged manifest and resources.
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.cast)

    // Cast discovery and the session lifecycle. mediarouter arrives transitively with the
    // Cast framework, but the device picker is ours and uses it directly, so it's declared.
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
