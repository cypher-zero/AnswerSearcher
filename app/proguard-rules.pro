# Keep ML Kit models
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep POI
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**

# Keep model classes
-keep class com.answersearcher.app.model.** { *; }
