# =========================================================================
# Zenith CashFlow - Production ProGuard & R8 Optimization Rules
# =========================================================================

# --- General Optimization & Stack Trace Preservation ---
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- AndroidX & Jetpack Compose ---
-dontwarn androidx.**
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.navigation.** { *; }

# --- Kotlin Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Kotlinx Serialization ---
-dontnote kotlinx.serialization.SerializationKt
-dontwarn kotlinx.atomicfu.**
-dontwarn org.slf4j.**
-dontwarn java.lang.invoke.**
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class **$$serializer {
    *;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}

# --- Supabase & Ktor Client ---
-keep class io.github.jan.supabase.** { *; }
-keep interface io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-dontwarn io.ktor.**

# --- Room Database ---
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# --- Domain, Sync DTOs, ViewModel & Components ---
-keep class com.zenith.app.data.models.** { *; }
-keep class com.zenith.app.data.network.** { *; }
-keep class com.zenith.app.data.ai.** { *; }
-keep class com.zenith.app.data.upi.** { *; }
-keep class com.zenith.app.ui.viewmodel.** { *; }
-keep class com.zenith.app.BuildConfig { *; }

# --- Google ML Kit ---
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**

# --- CameraX ---
-dontwarn androidx.camera.**
-keep class androidx.camera.** { *; }
