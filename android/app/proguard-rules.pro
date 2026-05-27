# ── Aegis ProGuard Rules ───────────────────────────────────────────────────────

# Keep all Aegis Kotlin classes — they're called via reflection by Android
-keep class com.aegis.app.** { *; }

# ── Flutter ────────────────────────────────────────────────────────────────────
-keep class io.flutter.** { *; }
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.plugin.** { *; }
-dontwarn io.flutter.**

# ── BouncyCastle ───────────────────────────────────────────────────────────────
# BC uses reflection heavily for crypto algorithm registration
-keep class org.bouncycastle.** { *; }
-keepnames class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { *; }

# Keep coroutines (used by Kotlin stdlib)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-dontwarn kotlinx.coroutines.**

# ── Android VPN / Network ──────────────────────────────────────────────────────
-keep class android.net.VpnService { *; }
-keep class android.net.VpnService$Builder { *; }

# Keep BroadcastReceiver (BootReceiver must survive shrinking)
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.app.Activity { *; }

# ── SSL / TLS ──────────────────────────────────────────────────────────────────
-keep class javax.net.ssl.** { *; }
-keep class java.security.** { *; }
-dontwarn javax.net.ssl.**
-dontwarn java.security.**

# ── JSON (org.json built into Android) ────────────────────────────────────────
-keep class org.json.** { *; }

# ── Remove logging in release ──────────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# ── General safety ─────────────────────────────────────────────────────────────
# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations (needed by Flutter and BouncyCastle)
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
