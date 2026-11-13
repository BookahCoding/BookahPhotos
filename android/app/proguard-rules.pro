# kotlinx.serialization and OkHttp ship their own consumer rules. Add app-specific keep rules here only when a release build needs them.

# ONNX Runtime (search on the phone) is called from native code by class and method name: R8 must not rename or remove any of it.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
