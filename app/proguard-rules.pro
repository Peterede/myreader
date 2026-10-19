-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.myreader.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.myreader.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
