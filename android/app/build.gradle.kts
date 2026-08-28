import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing material never lives in the repository. Locally it is read from a properties file
 * outside the working tree (default `~/.athkar-signing/keystore.properties`); in CI the same four
 * values arrive as environment variables from repository secrets.
 */
val keystorePropertiesFile = file(
    providers.gradleProperty("athkar.keystoreProperties").orNull
        ?: "${System.getProperty("user.home")}/.athkar-signing/keystore.properties",
)
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}

fun signingValue(key: String, environmentVariable: String): String? =
    System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("storeFile", "ATHKAR_KEYSTORE_FILE")
    ?.let(::file)
    ?.takeIf { it.isFile }

android {
    namespace = "com.athkar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.athkar.app"
        minSdk = 26
        targetSdk = 35
        // Overridable so the release workflow can stamp the build from the git tag.
        versionCode = (providers.gradleProperty("athkar.versionCode").orNull ?: "1000").toInt()
        versionName = providers.gradleProperty("athkar.versionName").orNull ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("storePassword", "ATHKAR_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ATHKAR_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ATHKAR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falling back to the debug key keeps `assembleRelease` working for anyone who clones
            // the repo, but a build signed with it can never update an installed release — hence
            // the warning, and the signature check in the release workflow.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug").also {
                    logger.warn(
                        "athkar: no release keystore found at $keystorePropertiesFile — " +
                            "signing the release build with the DEBUG key. Do not distribute it.",
                    )
                }
        }
        debug { applicationIdSuffix = ".debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/versions/**",
        )
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
        disable += setOf(
            "GradleDependency",              // "newer version available" noise in this env
            "AndroidGradlePluginVersion",    // AGP newest-version advisory
            "NewerVersionAvailable",         // newest-version advisory
            "OldTargetApi",                  // targetSdk 35 pinned deliberately
        )
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":sync"))
    implementation(project(":feature-athkar"))
    implementation(project(":designsystem"))
    implementation(project(":feature-prayer-times"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.api)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
