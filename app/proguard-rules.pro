# ProGuard rules for Rookie On Quest
# Story 8.1: Added R8/ProGuard minification support for release builds
#
# ================================================================================
# PROGUARD RULES STRATEGY - SURGICAL LIBRARY-PROVIDED RULES
# ================================================================================
# This file uses the OFFICIAL ProGuard rules provided by each library's maintainers.
# These rules are the minimum necessary for each library to function correctly.
#
# WHY SURGICAL RULES (not blanket keep):
# - Smaller APK: R8 can optimize and shrink unused library code
# - Better performance: Dead code elimination reduces method count
# - Security: Obfuscation still applies to library internals where safe
#
# RULE SOURCES:
# Each section below cites the official source for the rules.
# When updating library versions, verify rules haven't changed.
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
# Kotlin Coroutines
# ================================================================================
# Source: https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/jvm/resources/META-INF/proguard/coroutines.pro
# These rules are bundled with kotlinx-coroutines but included here for clarity.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}

# ================================================================================
# OkHttp
# ================================================================================
# Source: https://square.github.io/okhttp/features/r8_proguard/
# OkHttp's rules are bundled via META-INF, but we include critical ones here.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ================================================================================
# Retrofit
# ================================================================================
# Source: https://github.com/square/retrofit/blob/master/retrofit/src/main/resources/META-INF/proguard/retrofit2.pro
# Retrofit uses reflection for service method invocation - these are REQUIRED.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>

-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ================================================================================
# Gson
# ================================================================================
# Source: https://github.com/google/gson/blob/main/examples/android-proguard-example/proguard.cfg
# Gson uses reflection - keep TypeAdapters and annotated classes.
-keepattributes Signature
-keepattributes *Annotation*

# Prevent R8 from removing annotations on Gson @SerializedName fields
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Keep type adapters
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep @SerializedName annotated fields in data classes used by this app
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ================================================================================
# Room Database
# ================================================================================
# Source: https://developer.android.com/jetpack/androidx/releases/room
# Room's rules are bundled, but we ensure DAOs and entities are kept.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * {
    <fields>;
}
-dontwarn androidx.room.paging.**

# ================================================================================
# WorkManager
# ================================================================================
# Source: https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration
# Workers are instantiated by class name - keep worker classes.
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ================================================================================
# Jetpack Compose
# ================================================================================
# Source: https://developer.android.com/jetpack/compose/tooling#r8-proguard
# Compose rules are bundled with AGP 8.0+. These are MINIMAL surgical rules.
# R8 full mode handles most Compose obfuscation automatically.
#
# NOTE: Unlike previous versions, we DO NOT use blanket "-keep class ** { *; }"
# rules for Compose. AGP 8.0+ bundles proper Compose rules via META-INF.
# Only specific stability and recomposition-related classes need explicit rules.

# Keep Composable functions metadata for proper recomposition
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ================================================================================
# Coil Image Loading
# ================================================================================
# Source: https://coil-kt.github.io/coil/getting_started/#proguard
# Coil's rules are bundled via META-INF. These are for extra safety.
-dontwarn coil.network.**

# ================================================================================
# Apache Commons Compress (7z support)
# ================================================================================
# Source: https://commons.apache.org/proper/commons-compress/
# ServiceLoader requires concrete implementations to be kept.
-keep class org.apache.commons.compress.compressors.** extends org.apache.commons.compress.compressors.CompressorStreamProvider
-keep class org.apache.commons.compress.archivers.** extends org.apache.commons.compress.archivers.ArchiveStreamProvider
-dontwarn org.apache.commons.compress.**

# ================================================================================
# XZ Utils (used by Commons Compress for LZMA/7z)
# ================================================================================
-dontwarn org.tukaani.xz.**

# ================================================================================
# Native Methods
# ================================================================================
# JNI methods must be preserved for Java/native interop.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ================================================================================
# Serialization Support
# ================================================================================
# Preserve Java serialization mechanics for classes that use it.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ================================================================================
# Application Data Classes
# ================================================================================
# Keep data classes that are serialized/deserialized by Gson.
# These are specific to Rookie On Quest app.
#
# WHY FULL PRESERVATION (not surgical keep):
# ================================================================================
# These packages contain DTOs (Data Transfer Objects) and domain models that are:
# 1. Serialized/deserialized by Gson using reflection
# 2. Used across network boundaries (Retrofit responses)
# 3. Stored in Room database (GameEntity, etc.)
#
# The Gson rules above (lines 103-106) only preserve @SerializedName fields.
# However, this app's data classes may have:
# - Fields without @SerializedName that rely on field name matching
# - Nested classes referenced only through reflection
# - Room entities with relationships
#
# PERFORMANCE IMPACT:
# These packages are small (~10-20 classes) so full preservation has minimal
# impact on APK size. The safety benefit outweighs the marginal size cost.
#
# FUTURE OPTIMIZATION (Story 8.7):
# If APK size becomes a concern, annotate each class explicitly:
# - Add @Keep to critical classes
# - Add @SerializedName to all Gson fields
# - Then replace these rules with annotation-based keeping
#
-keep class com.vrpirates.rookieonquest.data.** { *; }
-keep class com.vrpirates.rookieonquest.model.** { *; }
