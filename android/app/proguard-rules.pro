# Shared Contact CRM - ProGuard/R8 rules

# Keep annotations and signatures
-keepattributes Signature
-keepattributes *Annotation*

# --- Room ---
# Keep Room database/DAO/entities and annotations
-keep class androidx.room.** { *; }
-keep @androidx.room.* class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# --- Kotlin Serialization ---
-keep class kotlinx.serialization.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.Transient <fields>;
}

# --- WorkManager ---
-keep class androidx.work.** { *; }

# --- Supabase (Jan Tennert client) ---
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }

# --- libphonenumber ---
-keep class com.google.i18n.phonenumbers.** { *; }

# --- Coil (optional) ---
-keep class coil.** { *; }

# --- App package ---
-keep class com.sharedcrm.** { *; }