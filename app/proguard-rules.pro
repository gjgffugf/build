# Add project specific ProGuard rules here.
-dontobfuscate
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
