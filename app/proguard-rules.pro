# Keep manifest-referenced components readable in stack traces; R8 keeps them alive anyway.
-keepnames class com.neurone.myblocker.vpn.BlockerVpnService
-keepnames class com.neurone.myblocker.system.** { *; }
-keepnames class com.neurone.myblocker.update.** { *; }
# Line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
