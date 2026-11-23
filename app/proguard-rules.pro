# 保留 WebView JavaScript 接口（如后续添加 addJavascriptInterface）
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
