package lk.codegen.risime.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        SyncStateEntity::class,
        SeenEventEntity::class,
        BehaviourEventEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun sync(): SyncDao
    abstract fun contacts(): ContactDao
    abstract fun behaviour(): BehaviourDao
    abstract fun wipe(): WipeDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "risime.db").build()
    }
}
