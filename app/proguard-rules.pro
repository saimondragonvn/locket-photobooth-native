# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.elingo.locket.**$$serializer { *; }
-keepclassmembers class com.elingo.locket.** {
    *** Companion;
}
-keepclasseswithmembers class com.elingo.locket.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp & Conscrypt
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# CameraX & Media3
-keep class androidx.camera.** { *; }
-keep class androidx.media3.** { *; }

