# Proguard rules for HomeOS-Android
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles settings in build.gradle.kts.

# Keep JavaScript Interface methods safe from obfuscation
-keepattributes *Annotation*
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the JS bridge class & its interface methods (called by name from WebView JS)
-keep class com.homeos.app.NativeBridge { *; }

# Keep Firebase and HMS classes
-keep class com.google.firebase.** { *; }
-keep class com.huawei.hms.** { *; }
-keep class com.huawei.agconnect.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.huawei.hms.**
-dontwarn com.huawei.agconnect.**

# OkHttp / Okio (ship their own consumer rules; keep dontwarn as a safety net)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# androidx.security (Tink) used by EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**

# Maintain JSON library model properties
-keepclassmembers class * {
    *** get*();
    *** set*(***);
}
