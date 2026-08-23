package io.github.prince_dulb.ohmynotification.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NotificationItemEntity::class,
        HealthEvidenceEntity::class,
        ExcludedSourceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OmnDatabase : RoomDatabase() {
    abstract fun omnDao(): OmnDao

    companion object {
        private const val DATABASE_NAME = "oh-my-notification.db"

        @Volatile
        private var instance: OmnDatabase? = null

        fun get(context: Context): OmnDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OmnDatabase::class.java,
                DATABASE_NAME,
            ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { database -> instance = database }
        }
    }
}
