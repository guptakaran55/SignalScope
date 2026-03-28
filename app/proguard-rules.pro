# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.signalscope.app.data.** { *; }
-keep class com.google.gson.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
