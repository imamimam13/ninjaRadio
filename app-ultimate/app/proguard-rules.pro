# =====================================================================
# Ninja Ultimate - Hardened R8 / ProGuard Obfuscation Configuration
# =====================================================================

# 1. Aggressive Class Repackaging & Name Obfuscation
# Flattens all packages into a single root package 'a' with single-letter classes
-repackageclasses 'a'
-allowaccessmodification
-overloadaggressively

# 2. Strip Decompiler Source & Line Number Metadata
# Decompilers (JADX, CFR, Fernflower) won't have original .java/.kt filenames or line numbers
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# Keep minimal attributes required by runtime
-keepattributes Exceptions,InnerClasses,Signature,EnclosingMethod

# 3. Preserve Logging for Diagnostics
# -assumenosideeffects class android.util.Log {
#     public static boolean isLoggable(java.lang.String, int);
#     public static int v(...);
#     public static int d(...);
#     public static int i(...);
# }

# 3.1 TS3 Native Protocol & Model Keep Rules
-keep class com.ninjaradio.ultimate.protocol.ts3.** { *; }
-keep class com.ninjaradio.ultimate.model.** { *; }

# 4. Concentus Pure-Java Opus Codec & MD380 AMBE Vocoder
-keep class io.github.jaredmdobson.concentus.** { *; }
-dontwarn io.github.jaredmdobson.concentus.**
-keep class com.z3.dmr.net.vocoder.** { *; }
-keep class com.example.hblink.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 5. OkHttp WebSocket & Network Stack
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# 6. Android Framework Entry Points
-keep public class com.ninjaradio.ultimate.MainActivity extends android.app.Activity {
    public void *(android.view.View);
}

# Keep system callbacks for hardware / USB
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# Keep Parcelable / Serializable creators if used
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
