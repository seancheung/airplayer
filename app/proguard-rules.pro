# Keep JNI callback interface (called from native code)
-keep class io.github.seancheung.airplayer.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.seancheung.airplayer.bridge.RaopCallbackHandler { *; }

# Keep NativeBridge native methods
-keep class io.github.seancheung.airplayer.bridge.NativeBridge { *; }
