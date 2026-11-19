# Keep the app's own classes as they are (data models, JSON parsing); the libraries are shrunk and optimized.
-dontobfuscate
-keep class com.nova.tv.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
