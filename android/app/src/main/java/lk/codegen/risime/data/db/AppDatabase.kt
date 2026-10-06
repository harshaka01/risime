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
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupOpEntity::class,
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
    abstract fun groups(): GroupDao
    abstract fun groupOps(): GroupOpDao

    companion object {
        /** Bump together with a new exported schema (app/schemas) and a Migration in [MIGRATIONS]. */
        const val VERSION = 5

        /**
         * One step per version (n-1 → n). Installed release builds must keep their data, so there is
         * no destructive fallback: a missing migration crashes on open instead of wiping chats.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5)

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

/**
 * v4 → v5 (contract v1.9 groups): groups, members and the group-op outbox; messages gain the
 * system-line kind and aggregated receipts; contacts gain group_ready. Additive only: `to_id`
 * stays NOT NULL and holds the conversation id for groups (no table rebuild).
 */
object Migration4To5 : Migration(4, 5) {
    val SQL = listOf(
        "ALTER TABLE `messages` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'text'",
        "ALTER TABLE `messages` ADD COLUMN `system_json` TEXT",
        "ALTER TABLE `messages` ADD COLUMN `receipt_delivered` INTEGER",
        "ALTER TABLE `messages` ADD COLUMN `receipt_read` INTEGER",
        "ALTER TABLE `messages` ADD COLUMN `receipt_of` INTEGER",
        "ALTER TABLE `contacts` ADD COLUMN `group_ready` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `groups` (`conversation_id` TEXT NOT NULL, `name` TEXT, `my_role` TEXT NOT NULL, `state` TEXT NOT NULL, `created_by` TEXT, `created_at` TEXT, `generation` INTEGER NOT NULL, `epoch_seen` INTEGER, `meta_updated_at` INTEGER, `last_refreshed_at` INTEGER, `local_ts` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`))",
        "CREATE TABLE IF NOT EXISTS `group_members` (`conversation_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `display_name` TEXT NOT NULL, `phone` TEXT, `role` TEXT NOT NULL, `kind` TEXT NOT NULL, `state` TEXT NOT NULL, `joined_at` TEXT, PRIMARY KEY(`conversation_id`, `user_id`))",
        "CREATE INDEX IF NOT EXISTS `index_group_members_user_id` ON `group_members` (`user_id`)",
        "CREATE TABLE IF NOT EXISTS `group_ops` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversation_id` TEXT, `type` TEXT NOT NULL, `payload_json` TEXT NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `client_group_id` TEXT, `op_id` TEXT, `next_at` INTEGER NOT NULL, `last_error` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_group_ops_conversation_id` ON `group_ops` (`conversation_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_group_ops_op_id` ON `group_ops` (`op_id`)",
        "CREATE INDEX IF NOT EXISTS `index_group_ops_state_next_at` ON `group_ops` (`state`, `next_at`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) = SQL.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = SQL.forEach { connection.execSQL(it) }
}
