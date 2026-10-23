-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.edens.myreader.** {
    *** Companion;
}
-keepclasseswithmembers class com.edens.myreader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
