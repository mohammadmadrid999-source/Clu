# kotlinx.serialization: keep generated serializers for persisted profile models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.clu.motion.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.clu.motion.** {
    kotlinx.serialization.KSerializer serializer(...);
}
