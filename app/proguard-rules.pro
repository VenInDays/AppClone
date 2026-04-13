# AppClone ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Kotlin classes
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Keep Room entities
-keep class com.appclone.data.** { *; }

# Keep Material components
-keep class com.google.android.material.** { *; }

# Keep clone engine classes (important for reflection/serialization)
-keep class com.appclone.core.** { *; }
-keepclassmembers class com.appclone.core.** { *; }

# Keep BouncyCastle classes used for PKCS#7 signing
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep serialization
-keepclassmembers class * {
    ** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.** {
    *** Companion;
}
