# --- R8 rules for Elefin release builds ---

# Keep line numbers in crash logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Strip verbose/debug/info logging from release builds.
#
# The app makes ~1050 Log calls, 650 of them Log.d. The cost is not the I/O but
# the argument evaluation, which happens before the call: several sites build
# throwaway lists and concatenate long strings on every API response, e.g.
#   Log.d(TAG, "order: ${response.Items.mapIndexed { ... }}")
# -assumenosideeffects lets R8 delete the call AND the dead argument expressions.
#
# Log.w / Log.e are deliberately kept so field crashes stay diagnosable.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static boolean isLoggable(...);
}

# ---------------------------------------------------------------------------
# MPV JNI bridge: native methods are registered by name from libplayer.so /
# libmpv.so, and native code calls back into MPVLib event/log helpers.
# ---------------------------------------------------------------------------
-keep class is.xyz.mpv.** { *; }

# ---------------------------------------------------------------------------
# kotlinx.serialization: serializer lookup happens through generated
# Companion/serializer methods on @Serializable classes across the app.
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.flex.elefin.**$$serializer { *; }
-keepclassmembers class com.flex.elefin.** {
    *** Companion;
}
-keepclasseswithmembers class com.flex.elefin.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Gson (updater): reflective field-based deserialization of GitHub API models.
# ---------------------------------------------------------------------------
-keep class com.flex.elefin.updater.GitHubRelease { <fields>; }
-keep class com.flex.elefin.updater.GitHubAsset { <fields>; }
-dontwarn com.google.gson.**

# ---------------------------------------------------------------------------
# NewPipe extractor (trailer streaming): parses JS/JSON payloads, keep
# members referenced from dynamically-built class names quiet.
# ---------------------------------------------------------------------------
-dontwarn org.schabi.newpipe.**

# Rhino (extractor's JS engine) and slf4j reference JDK classes that do not
# exist on Android; the paths using them are never reached at runtime.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.ScriptEngineFactory
-dontwarn org.slf4j.impl.StaticLoggerBinder
