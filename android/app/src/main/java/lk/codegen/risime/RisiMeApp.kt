package lk.codegen.risime

import android.app.Application

class RisiMeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Debug builds (and so every redroid gate): disk and database work on the main thread is
        // logged with a stack ("StrictMode policy violation"); the gates fail on violations that
        // come from Room or the MLS core (nightly.17 crashed opening a DM on a main-thread Room
        // transaction). Release builds are unaffected.
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build(),
            )
        }
        container = AppContainer(this)
    }
}
