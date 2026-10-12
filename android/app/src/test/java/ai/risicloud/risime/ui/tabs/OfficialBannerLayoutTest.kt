package ai.risicloud.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.zIndex
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ai.risicloud.risime.data.db.AppDatabase
import ai.risicloud.risime.data.tabs.ChatTabs
import ai.risicloud.risime.data.tabs.Tab
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.Group
import ai.risicloud.risime.net.GroupMeta
import ai.risicloud.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0 2026-10-12 (bug C, two phones): in a 1:1 Official the banner "Risi listens in Official … OK" was drawn ON
 * the "🔒 Private | ● Official" row, its OK over the search icon. The chat screens place the tab bar in a z-order
 * Box (`Box(Modifier.zIndex(1f)) { bar() }`, as they did then); the banner must still be its own row below the
 * tabs. Galaxy A15 class: 720 px wide at ~300 dpi = 384 dp, font scale 1.0 and 1.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h853dp-xhdpi")
class OfficialBannerLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val dm = "dm:0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val risi = "9e1f0000-0000-4000-8000-000000000001"
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver()).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tabs = ChatTabs(db.chatTabs(), scope, persistBannerSeen = {})
        .also { t -> runBlocking { withTimeout(5_000) { t.rows.first { it != null } } } }

    @After fun tearDown() {
        runBlocking { scope.coroutineContext[Job]!!.let { it.cancel(); it.join() } }
        db.close()
    }

    private fun checkAt(fontScale: Float) {
        runBlocking { tabs.recordMls(official, GroupMeta(name = "", tab = "official", chatId = dm, agents = listOf(risi))) }
        val c = ChatTabsController(dm, tabs, { ApiResult.Ok(Group(official)) }, scope, Tab.OFFICIAL)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                RisiMeTheme {
                    TabbedChatContent(
                        c, "Kamal", onBack = {},
                        privateScreen = { tabBar -> Column { tabBar?.invoke() } },
                        // Exactly how ChatScreen/GroupScreens placed it when the bug was reported.
                        officialScreen = { _, _, tabBar ->
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.zIndex(1f)) { tabBar() }
                                Text("official messages", Modifier.fillMaxWidth())
                            }
                        },
                        onSearch = { _, _ -> },
                    )
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(OFFICIAL_BANNER_TEXT)).fetchSemanticsNodes().isNotEmpty() }
        val banner = rule.onNodeWithTag("official_banner").getUnclippedBoundsInRoot()
        val ok = rule.onNodeWithTag("official_banner_ok", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val tabRow = rule.onNodeWithTag("tab_official").getUnclippedBoundsInRoot()
        val search = rule.onNodeWithTag("tab_search").getUnclippedBoundsInRoot()
        val messages = rule.onNodeWithText("official messages").getUnclippedBoundsInRoot()
        for ((name, other) in listOf("Official tab" to tabRow, "search icon" to search)) {
            assertTrue("@$fontScale banner $banner overlaps the $name $other", !banner.r().overlaps(other.r()))
            assertTrue("@$fontScale OK $ok overlaps the $name $other", !ok.r().overlaps(other.r()))
            assertTrue("@$fontScale the banner isn't below the $name", banner.top >= other.bottom)
        }
        assertTrue("@$fontScale the messages start under the banner", messages.top >= banner.bottom)
    }

    private fun DpRect.r() = Rect(left.value, top.value, right.value, bottom.value)

    @Test fun theBannerIsItsOwnRowBelowTheTabsAtFontScale1() = checkAt(1.0f)

    @Test fun theBannerIsItsOwnRowBelowTheTabsAtFontScale13() = checkAt(1.3f)
}
