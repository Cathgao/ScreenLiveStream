# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line numbers for readable release crash stack traces while hiding original filenames.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep enum values/valueOf used dynamically in intent/discovery parsing
-keepclassmembers enum com.cath.screencast.model.* {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
