# Add project specific ProGuard rules here.
# Keep Forge build engine classes
-keep class com.forge.app.build_engine.** { *; }
-keep class com.forge.app.agent.** { *; }
-keep class com.forge.app.data.** { *; }

# Keep ECJ classes if bundled
-keep class org.eclipse.jdt.** { *; }

# Keep D8/R8 if bundled
-keep class com.android.tools.r8.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *