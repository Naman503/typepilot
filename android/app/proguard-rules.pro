# LiteRT-LM (on-device models) uses JNI callbacks, Gson and Kotlin reflection: keep its API intact.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-dontwarn kotlin.reflect.jvm.internal.**
# Services and the IME are referenced from the manifest (R8 keeps those automatically).
# PdfBox-Android (reading PDFs into memory): fonts/resources are loaded by name.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter
