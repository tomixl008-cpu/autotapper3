# ProGuard / R8 rules for Auto Text Tapper.
#
# Note: the debug build never runs R8, and the release build has minify disabled,
# so these rules are only a safety net if minification is enabled later.

# Android components declared in AndroidManifest.xml (activities, services) are
# kept automatically. Keep the accessibility service explicitly as well, because
# it is bound by the system via an Intent action.
-keep class com.example.autotexttapper.TextAutomationAccessibilityService { *; }
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService {
    <methods>;
}
