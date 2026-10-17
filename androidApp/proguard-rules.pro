# kotlinx.serialization: keep generated serializers of our @Serializable classes
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class biz.fesh.syncmusic.**$$serializer { *; }
-keepclassmembers class biz.fesh.syncmusic.** {
    *** Companion;
}
-keepclasseswithmembers class biz.fesh.syncmusic.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers enum biz.fesh.syncmusic.** { *; }

# WorkManager instantiates the worker by class name
-keep class biz.fesh.syncmusic.sync.SyncWorker { <init>(...); }

# Readable stack traces in logcat
-keepattributes SourceFile, LineNumberTable
