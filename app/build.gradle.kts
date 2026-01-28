import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.vrpirates.rookieonquest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vrpirates.rookieonquest"
        minSdk = 29
        targetSdk = 34

        // Version configuration with GitHub Actions parameter override support
        // ========================================================================
        // versionCode can be overridden by Gradle property: -PversionCode="10"
        // versionName can be overridden by Gradle property: -PversionName="2.5.0"
        //
        // Story 8.1: These fallback values enable CI/CD workflow foundation testing
        // Story 8.3: Version will be centralized via Git tags, eliminating fallbacks
        //
        // Current fallbacks (from build.gradle.kts):
        versionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 9
        versionName = project.findProperty("versionName")?.toString() ?: "2.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val properties = Properties()
                properties.load(FileInputStream(keystorePropertiesFile))

                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            } else {
                // ================================================================================
                // CRITICAL SECURITY WARNING: No keystore.properties found - release APK will be signed with debug key!
                // ================================================================================
                //
                // ACCEPTABLE FOR STORY 8.1 ONLY:
                // This fallback is ONLY acceptable for Story 8.1 (CI/CD foundation testing workflow).
                // Story 8.2 "Secure APK Signing with Keystore Management" will add proper signing config
                // with GitHub Secrets integration to eliminate this security risk.
                //
                // NOT ACCEPTABLE FOR PRODUCTION:
                // PRODUCTION BUILDS MUST HAVE keystore.properties CONFIGURED!
                // Debug-signed release APKs are NOT suitable for production distribution.
                //
                // RISKS OF DEBUG-SIGNED RELEASE BUILDS:
                // - Cannot be upgraded from in production (signature mismatch)
                // - Cannot be uploaded to Google Play (signature verification fails)
                // - Users cannot install over existing production installs
                // - Security vulnerabilities (debug keys are publicly known)
                //
                logger.error("========================================")
                logger.error("CRITICAL: keystore.properties not found")
                logger.error("========================================")
                logger.error("Release APK will be signed with DEBUG key")
                logger.error("This is NOT production-ready!")
                logger.error("Story 8.2 will add GitHub Secrets-based signing")
                logger.error("========================================")
            }
        }
    }

    buildTypes {
        release {
            // Enable R8/ProGuard minification and obfuscation for release builds
            // This reduces APK size and improves security by obfuscating code
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // SECURITY NOTICE: Signing config selection
            // - If keystore.properties exists: uses production release signing config
            // - If keystore.properties missing: falls back to debug signing config (NOT production-ready)
            // This fallback is ONLY for Story 8.1 CI/CD workflow testing.
            // Story 8.2 will add GitHub Secrets-based signing to eliminate this fallback entirely.
            signingConfig = if (rootProject.file("keystore.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("Falling back to debug signing config for release build (NOT production-ready)")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// APK output filename configuration
// ================================================================================
// TECHNICAL DEBT: Using internal AGP API (BaseVariantOutputImpl)
// ================================================================================
// This workaround is necessary because the public Variant API does not yet
// support outputFileName configuration.
//
// ACCEPTABLE FOR STORY 8.1:
// This technical debt is accepted for Story 8.1 (CI/CD workflow foundation).
// The internal API is stable and widely used, and there is no public alternative yet.
//
// WILL BE ADDRESSED IN STORY 8.7:
// This will be refactored in Story 8.7 "Build Dependency Caching and Performance"
// when we can revisit the build setup and potentially use the new public API
// if available, or use a different approach (e.g., Gradle task rename).
//
// References:
// - Issue tracker: https://issuetracker.google.com/issues/159636627
// - Public API discussion: https://github.com/android/gradle-issues/issues/3714
//
// RISKS:
// - Internal API may break with AGP upgrades (mitigation: pin AGP version)
// - Lint warnings about using internal APIs (accepted for Story 8.1)
//
// ALTERNATIVES CONSIDERED:
// 1. Gradle task to rename APK after build (adds complexity, timing issues)
// 2. Build script with external rename (not idiomatic for Android builds)
// 3. Wait for public API (blocks Story 8.1 - not acceptable)
//
// DECISION: Accept technical debt for Story 8.1, address in Story 8.7
android.applicationVariants.all {
    outputs
        .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
        .forEach { output ->
            output.outputFileName = "RookieOnQuest-v${versionName}.apk"
        }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Networking & Utilities
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    // 7z Support
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // WorkManager for background download tasks
    val workVersion = "2.9.1"
    implementation("androidx.work:work-runtime-ktx:$workVersion")
    androidTestImplementation("androidx.work:work-testing:$workVersion")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // MockWebServer for HTTP testing
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
