package lk.codegen.risime.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        SyncStateEntity::class,
        SeenEventEntity::class,
        BehaviourEventEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun sync(): SyncDao
    abstract fun contacts(): ContactDao
    abstract fun behaviour(): BehaviourDao
    abstract fun wipe(): WipeDao

    companion object {
        /** Bump together with a new exported schema (app/schemas) and a Migration in [MIGRATIONS]. */
        const val VERSION = 1

        /**
         * One step per version (n-1 → n). Installed release builds must keep their data, so there is
         * no destructive fallback: a missing migration crashes on open instead of wiping chats.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "risime.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
