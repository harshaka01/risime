package lk.codegen.risime.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        SyncStateEntity::class,
        SeenEventEntity::class,
        BehaviourEventEntity::class,
        MlsKvEntity::class,
        MlsPendingEntity::class,
        ReactionEntity::class,
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
    abstract fun mlsPending(): MlsPendingDao
    abstract fun reactions(): ReactionDao

    companion object {
        /** Bump together with a new exported schema (app/schemas) and a Migration in [MIGRATIONS]. */
        const val VERSION = 4

        /**
         * One step per version (n-1 → n). Installed release builds must keep their data, so there is
         * no destructive fallback: a missing migration crashes on open instead of wiping chats.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(Migration1To2, Migration2To3, Migration3To4)

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "risime.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

/**
 * v1 → v2 (contract v1.6 friends): contacts gain `friend` and `vouched_by_name`. Existing rows
 * keep showing as friends until the first `/friends` refetch corrects them.
 */
object Migration1To2 : Migration(1, 2) {
    val SQL = listOf(
        "ALTER TABLE `contacts` ADD COLUMN `friend` INTEGER NOT NULL DEFAULT 0",
        "UPDATE `contacts` SET `friend` = `registered`",
        "ALTER TABLE `contacts` ADD COLUMN `vouched_by_name` TEXT",
    )

    override fun migrate(db: SupportSQLiteDatabase) = SQL.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = SQL.forEach { connection.execSQL(it) }
}

/** v2 → v3 (contract v1.7): the sealed MLS key-value table and the pending e2ee events. */
object Migration2To3 : Migration(2, 3) {
    val SQL = listOf(
        "CREATE TABLE IF NOT EXISTS `mls_kv` (`namespace` TEXT NOT NULL, `key` BLOB NOT NULL, `value` BLOB NOT NULL, PRIMARY KEY(`namespace`, `key`))",
        "CREATE TABLE IF NOT EXISTS `mls_pending` (`event_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `generation` INTEGER NOT NULL, `epoch` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `event_json` TEXT NOT NULL, PRIMARY KEY(`event_id`))",
        "CREATE INDEX IF NOT EXISTS `index_mls_pending_conversation_id` ON `mls_pending` (`conversation_id`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) = SQL.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = SQL.forEach { connection.execSQL(it) }
}

/** v3 → v4 (contract v1.8): reaction state. */
object Migration3To4 : Migration(3, 4) {
    val SQL = listOf(
        "CREATE TABLE IF NOT EXISTS `reactions` (`conversation_id` TEXT NOT NULL, `target_message_id` TEXT NOT NULL, `reactor_user_id` TEXT NOT NULL, `emoji` TEXT NOT NULL, `op` TEXT NOT NULL, `confirmed_op` TEXT, `confirmed_ts` TEXT, `confirmed_message_id` TEXT, `pending` INTEGER NOT NULL, `pending_client_msg_id` TEXT, `local_ts` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`, `target_message_id`, `reactor_user_id`, `emoji`))",
        "CREATE INDEX IF NOT EXISTS `index_reactions_conversation_id_target_message_id` ON `reactions` (`conversation_id`, `target_message_id`)",
        "CREATE INDEX IF NOT EXISTS `index_reactions_pending_client_msg_id` ON `reactions` (`pending_client_msg_id`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) = SQL.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = SQL.forEach { connection.execSQL(it) }
}
