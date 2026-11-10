-keep class com.wireguard.** { *; }
-keep class org.torproject.** { *; }
-keep class info.guardianproject.** { *; }
-keepclassmembers class com.nova.browser.engine.VideoBridge {
    @android.webkit.JavascriptInterface <methods>;
}
