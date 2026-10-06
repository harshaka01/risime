package lk.codegen.risime.data.db

import android.app.Application
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §15.6: the database runs with `PRAGMA secure_delete = ON` (the app's open callback). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecureDeleteTest {
    @Test fun secureDeleteIsOnAfterOpen() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .addCallback(SecureDelete)
            .build()
        try {
            db.messages().countAll() // opens the database (runs the callback)
            val on = db.useWriterConnection { t -> t.usePrepared("PRAGMA secure_delete") { st -> st.step(); st.getLong(0) } }
            assertEquals(1L, on)
        } finally {
            db.close()
        }
        // The bundled SQLite defaults to on: check the callback itself on a connection switched off first.
        val conn = BundledSQLiteDriver().open(":memory:")
        try {
            conn.prepare("PRAGMA secure_delete = OFF").use { it.step() }
            assertEquals(0L, conn.prepare("PRAGMA secure_delete").use { it.step(); it.getLong(0) })
            SecureDelete.onOpen(conn)
            assertEquals(1L, conn.prepare("PRAGMA secure_delete").use { it.step(); it.getLong(0) })
        } finally {
            conn.close()
        }
    }
}
