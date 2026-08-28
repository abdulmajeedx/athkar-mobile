# Room requires these for databases/entities reflection-free access
-dontwarn androidx.room.paging.**
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.athkar.data.db.** { *; }
