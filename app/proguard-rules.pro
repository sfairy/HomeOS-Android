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

# Keep Firebase and HMS classes
-keep class com.google.firebase.** { *; }
-keep class com.huawei.hms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.huawei.hms.**

# Maintain JSON library model properties
-keepclassmembers class * {
    *** get*();
    *** set*(***);
}
