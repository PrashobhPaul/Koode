# Keep event payload/JSON reflection surfaces used by Firebase RTDB mapping.
-keepattributes Signature,*Annotation*
-keep class com.trippulse.app.domain.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
