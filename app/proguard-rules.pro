# TFLite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# JS interface methods are called by name from JavaScript.
-keepclassmembers class app.halum.bridge.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class app.halum.MainActivity$* {
    @android.webkit.JavascriptInterface <methods>;
}
