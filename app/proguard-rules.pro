# Keep JNI native methods
-keep class com.bgoom.app.scanner.NativeDocScanner { native <methods>; }
-keep class com.bgoom.app.typst.NativeTypstEngine { native <methods>; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.bgoom.app.data.**$$serializer { *; }
-keepclassmembers class com.bgoom.app.data.** { *** Companion; }
-keepclasseswithmembers class com.bgoom.app.data.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep SSHJ / BouncyCastle / EdDSA cryptographic providers & factories
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-dontwarn javax.security.**
-dontwarn java.lang.management.**

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Compile-only annotations & desktop JDK fallback references in Tink / EdDSA
-dontwarn com.google.errorprone.annotations.**
-dontwarn sun.security.x509.X509Key

