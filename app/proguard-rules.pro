# OkHttp and Media3 ship their own consumer rules.
# Keep this file small: every -keep rule costs APK size.

# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
