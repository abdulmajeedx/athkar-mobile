# Room requires these for databases/entities reflection-free access
-dontwarn androidx.room.paging.**
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.athkar.data.db.** { *; }

# Keep stack traces readable. Without these a release crash arrives as `a.b.c(Unknown Source)` and
# tells you nothing; with them, plus the mapping.txt the release workflow publishes, `retrace`
# turns a report back into file-and-line. The attributes cost a little size and no speed.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep our own exception types nameable: the whole point of PolarDayException is that the message
# identifies the failure, which an obfuscated class name defeats.
-keep public class * extends java.lang.Exception { *; }

# Ktor ships no consumer rules. Its engines and plugins are looked up reflectively, so R8 cannot see
# the references and strips them.
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

# SQLCipher's native layer calls back into Java by name. R8's default config keeps classes that
# declare native methods, but not the types those methods hand back and forth.
-keep class net.sqlcipher.** { *; }
-keep interface net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# kotlinx.serialization generates a $serializer for every @Serializable type and reaches it through
# the companion. The library ships rules for the common cases; these cover our own types explicitly.
-keepclassmembers class com.athkar.** {
    *** Companion;
}
-keepclasseswithmembers class com.athkar.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.athkar.**$$serializer { *; }
