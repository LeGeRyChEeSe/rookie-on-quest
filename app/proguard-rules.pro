# ProGuard rules for Rookie On Quest
# Story 8.1: Added R8/ProGuard minification support for release builds
#
# ================================================================================
# PROGUARD RULES STRATEGY - SPECIFIC OVER AGGRESSIVE
# ================================================================================
# These rules are SPECIFIC to each library's public API and reflection usage.
# We DO NOT use generic "keep everything" rules like `-keep class library.** { *; }`
# because that would disable R8/ProGuard optimization entirely.
#
# Instead, each rule block:
# 1. Targets only the specific classes/methods that need preservation
# 2. Explains WHY the rule is necessary (reflection, serialization, etc.)
# 3. References the library's official ProGuard documentation
# 4. Notes what would break if the rule is removed
#
# VALIDATION APPROACH:
# - These are standard rules from each library's official documentation
# - Project-specific testing should be done when adding new libraries
# - When in doubt, test release builds thoroughly before distribution
#
# For more details, see:
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ================================================================================
# Kotlin coroutine rules
# ================================================================================
# NECESSARY: Kotlin coroutines use reflection and need these rules to work correctly
# with R8/ProGuard obfuscation. Removing these will cause coroutine-related crashes.
# Source: Kotlin documentation and standard coroutine ProGuard rules.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ================================================================================
# OkHttp rules
# ================================================================================
# NECESSARY: OkHttp is used by Retrofit for HTTP networking. These rules prevent
# obfuscation of OkHttp's internal classes which rely on specific naming.
# Removing these will cause HTTP networking failures.
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ================================================================================
# Retrofit rules
# ================================================================================
# NECESSARY: Retrofit is used for API communication with VRP servers. These rules
# preserve Retrofit's service interfaces and JSON converters. Removing these will
# cause API deserialization failures.
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# ================================================================================
# Gson rules
# ================================================================================
# NECESSARY: Gson is used for JSON serialization/deserialization. These rules
# preserve Gson's type adapters and annotations. Removing these will cause JSON
# parsing failures for game catalog and metadata.
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ================================================================================
# Room database rules
# ================================================================================
# NECESSARY: Room is used for local database (installation queue). These rules
# preserve Room's generated code and database classes. Removing these will cause
# database access failures and crashes.
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class androidx.room.paging.** { *; }

# ================================================================================
# WorkManager rules
# ================================================================================
# NECESSARY: WorkManager is used for background download tasks. These rules
# preserve WorkManager's worker classes and scheduling logic. Removing these
# will cause background task failures.
-dontwarn androidx.work.**
-keep class androidx.work.** { *; }

# ================================================================================
# Jetpack Compose rules
# ================================================================================
# NECESSARY: Compose is the UI framework. These rules preserve Compose's
# runtime and composables. Removing these will cause UI rendering failures.
-keep class androidx.compose.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.animation.** { *; }

# ================================================================================
# Coil image loading rules
# ================================================================================
# NECESSARY: Coil is used for loading game thumbnails and icons. These rules
# preserve Coil's image loading components. Removing these will cause image
# loading failures.
-dontwarn coil.**
-keep class coil.** { *; }

# ================================================================================
# Apache Commons Compress (7z support)
# ================================================================================
# NECESSARY: Apache Commons Compress is used for extracting 7z game archives.
# These rules preserve the compression library's classes. Removing these will
# cause archive extraction failures.
-dontwarn org.apache.commons.**
-keep class org.apache.commons.compress.** { *; }

# ================================================================================
# Keep native methods
# ================================================================================
# NECESSARY: JNI native methods must be preserved to allow Java/Kotlin code to
# call into native libraries.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ================================================================================
# Keep serializable classes
# ================================================================================
# NECESSARY: Serializable classes may be used for data transfer or caching.
# These rules preserve serialization metadata.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
