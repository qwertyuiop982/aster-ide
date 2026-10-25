-keepclasseswithmembernames class * { native <methods>; }
-keep class com.termux.terminal.JNI { *; }
# These JNI exports use fixed owner and method names in the native payloads.
-keep class dev.aster.ide.editor.NativeSearch { native <methods>; }
-keep class dev.aster.termux.PathRedirect { native <methods>; }
# WebView discovers these entry points by annotation and Java method name.
-keep class dev.aster.agent.AgentJavascriptBridge { *; }
-keepattributes RuntimeVisibleAnnotations
-keep class org.eclipse.tm4e.** { *; }
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
-dontwarn org.eclipse.jdt.annotation.**
