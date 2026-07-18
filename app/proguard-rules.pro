# The JS bridge is invoked reflectively from WebView JavaScript, so its
# @JavascriptInterface methods must survive shrinking/obfuscation.
-keepclassmembers class com.lenovodock.app.MediaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
