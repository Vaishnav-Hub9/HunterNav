# Project-specific ProGuard/R8 rules.
# No keep rules are needed currently: release builds minify nothing (isMinifyEnabled=false
# in app/build.gradle.kts) and the app uses no reflection-based SDKs. The previous MapLibre
# JNI-reflective keep rules were removed with the MapLibre removal (Google-only migration).
