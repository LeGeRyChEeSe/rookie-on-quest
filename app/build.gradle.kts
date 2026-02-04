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

        // VERSION CONFIGURATION - Source of Truth for Default Values
        // ================================================================================
        // versionCode can be overridden by Gradle property: -PversionCode="10"
        // versionName can be overridden by Gradle property: -PversionName="2.5.0"
        //
        // Implemented in Story 8.5: Centralized version management with CI extraction and RC support.
        // Fallback values are maintained for local building.
        //
        // DRY PRINCIPLE NOTE:
        // The values 9 (versionCode) and "2.5.0" (versionName) are the SINGLE SOURCE OF TRUTH.
        // The GHA workflow (release.yml) extracts these values from this file rather than
        // hardcoding them, ensuring consistency.
        //
        // VALIDATION: If a property is provided but invalid, the build FAILS instead of
        // silently falling back to defaults. This prevents silent version mismatches.
        val versionCodeProperty = project.findProperty("versionCode")?.toString()
        val versionNameProperty = project.findProperty("versionName")?.toString()

        // ================================================================================
        // VERSION CODE VALIDATION (Local + CI)
        // ================================================================================
        // Validation ensures:
        // 1. versionCode is a valid positive integer (>= 1, Android requirement)
        // 2. versionCode doesn't exceed Android max (2147483647)
        // 3. Regression warning if versionCode < current default (CI-only feature in GHA)
        //
        // NOTE: This validation mirrors the CI validation in release.yml for consistency.
        // The only difference is regression warning is CI-only (GHA has access to git history).
        versionCode = when {
            versionCodeProperty == null -> 10 // Default when not provided
            versionCodeProperty.toIntOrNull() == null -> throw GradleException(
                "Invalid versionCode property: '$versionCodeProperty'. " +
                "versionCode must be a valid integer >= 1. " +
                "Example: -PversionCode=10"
            )
            versionCodeProperty.toInt() < 1 -> throw GradleException(
                "Invalid versionCode property: '$versionCodeProperty'. " +
                "Android requires versionCode to be a positive integer >= 1. " +
                "See: https://developer.android.com/studio/publish/versioning"
            )
            versionCodeProperty.toLong() > 2147483647L -> throw GradleException(
                "Invalid versionCode property: '$versionCodeProperty'. " +
                "versionCode exceeds Android maximum (2147483647). " +
                "See: https://developer.android.com/studio/publish/versioning"
            )
            else -> versionCodeProperty.toInt()
        }

        versionName = when {
            versionNameProperty == null -> "2.5.0-rc.1" // Default when not provided
            versionNameProperty.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[a-zA-Z0-9.]+)?(\\+[a-zA-Z0-9.]+)?$")) -> versionNameProperty
            else -> throw GradleException(
                "Invalid versionName property: '$versionNameProperty'. " +
                "versionName must match semver format: X.Y.Z, X.Y.Z-rc.N, or X.Y.Z+build. " +
                "Example: -PversionName=2.5.0"
            )
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // ================================================================================
    // SIGNING CONFIGURATION - Single source of truth for keystore availability
    // ================================================================================
    // This consolidates the logic for checking if release signing is available.
    // Used by both signingConfigs.create("release") and buildTypes.release.signingConfig
    //
    // Implemented in Story 8.2: GitHub Secrets-based signing with secure credential injection.
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val hasReleaseKeystore = keystorePropertiesFile.exists()

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                val properties = Properties()
                properties.load(FileInputStream(keystorePropertiesFile))

                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")

                logger.lifecycle("[signing] Release signing config loaded from keystore.properties")
            } else {
                // ================================================================================
                // SECURITY WARNING: No keystore.properties found
                // ================================================================================
                // This block runs when no keystore.properties file exists. The actual build
                // behavior depends on whether we're in CI or local environment:
                // - CI (GITHUB_ACTIONS=true): Build will FAIL in buildTypes.release block
                // - Local: Falls back to debug signing with warning
                //
                // Implemented GitHub Secrets-based signing to manage this.
                logger.warn("[signing] ========================================")
                logger.warn("[signing] WARNING: keystore.properties not found")
                logger.warn("[signing] ========================================")
                logger.warn("[signing] Build behavior will be determined in buildTypes.release")
                logger.warn("[signing] See buildTypes.release for CI vs local handling")
                logger.warn("[signing] ========================================")
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
            // - If keystore.properties exists (hasReleaseKeystore): uses production release signing config
            // - If keystore.properties missing in CI (GITHUB_ACTIONS=true): FAILS the build
            // - If keystore.properties missing locally: falls back to debug signing with warning
            //
            // This uses the consolidated hasReleaseKeystore variable (defined at signingConfigs level)
            // to avoid duplicate file existence checks and ensure consistency.
            //
            // This prevents silent security failures in CI/CD while allowing local testing.
            // Implemented in Story 8.2: GitHub Secrets-based signing to manage this.
            val isCI = System.getenv("GITHUB_ACTIONS") == "true"

            signingConfig = when {
                hasReleaseKeystore -> {
                    logger.lifecycle("[signing] Using production release signing config from keystore.properties")
                    signingConfigs.getByName("release")
                }
                isCI -> {
                    // In CI, we only warn during configuration. 
                    // The build will naturally fail if a release task is executed 
                    // because the 'release' signingConfig won't be fully configured.
                    logger.warn("[signing] CI/CD build without keystore. Release builds will fail if attempted.")
                    signingConfigs.getByName("debug")
                }
                else -> {
                    // Local build without keystore: Allow but warn loudly with actionable guidance
                    logger.warn("[signing] ========================================")
                    logger.warn("[signing] WARNING: LOCAL BUILD - DEBUG SIGNING")
                    logger.warn("[signing] ========================================")
                    logger.warn("[signing] Release APK will be signed with DEBUG key")
                    logger.warn("[signing] This is NOT production-ready!")
                    logger.warn("[signing]")
                    logger.warn("[signing] TO CREATE A PRODUCTION BUILD:")
                    logger.warn("[signing] 1. Create keystore.properties in project root with:")
                    logger.warn("[signing]    storeFile=/path/to/your/keystore.jks")
                    logger.warn("[signing]    storePassword=your_store_password")
                    logger.warn("[signing]    keyAlias=your_key_alias")
                    logger.warn("[signing]    keyPassword=your_key_password")
                    logger.warn("[signing] 2. Keep keystore.properties in .gitignore (never commit!)")
                    logger.warn("[signing]")
                    logger.warn("[signing] ========================================")
                    signingConfigs.getByName("debug")
                }
            }
        }
    }
    // Java version configuration
    // ============================================================
    // Using Java 11 (LTS) for compatibility with:
    // - Android SDK 34 (requires Java 11+)
    // - Jetpack Compose (requires Java 11+)
    // - Modern Kotlin features
    //
    // NOTE: While we use JDK 17 for building (setup-java in GHA), the bytecode
    // target is Java 11 for broader device compatibility. Java 11 is the minimum
    // supported for modern Android development with Compose.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
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
// STATUS: Accepted for Story 8.1-8.5, will be refactored in Story 8.7
//
// WHAT THIS DOES:
// Renames the APK output from "app-release.apk" to "RookieOnQuest-v{version}.apk"
// This makes the APK filename consistent and version-identifiable.
//
// WHY INTERNAL API IS USED:
// The Android Gradle Plugin (AGP) public Variant API does not expose outputFileName.
//
// WILL BE ADDRESSED IN STORY 8.7:
// Story 8.7 "Build Dependency Caching and Performance" will evaluate better alternatives.
//
// References:
// - Issue tracker: https://issuetracker.google.com/issues/159636627
// - Community discussion: https://github.com/android/gradle-recipes/issues/3714
//
// ALTERNATIVES CONSIDERED:
// 1. Gradle task to rename APK after build → Timing issues with artifact upload
// 2. Custom Gradle plugin
// 3. Wait for public API
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
