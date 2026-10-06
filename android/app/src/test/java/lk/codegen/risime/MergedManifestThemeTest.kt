package lk.codegen.risime

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression for the v0.2.0-nightly.3 crash: AppAuth's RedirectUriReceiverActivity (a
 * FragmentActivity via AppCompat) inherited Theme.RisiMe (android:Theme.Material…) and crashed
 * with "You need to use a Theme.AppCompat theme" when Keycloak redirected back.
 *
 * Reads the real merged manifests (debug + release) and checks that every library activity that
 * needs AppCompat, and MainActivity (BiometricPrompt's fingerprint dialog on API 26–28 is an
 * AppCompat AlertDialog), resolve to a Theme.AppCompat descendant.
 */
class MergedManifestThemeTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** Our style → parent, from values/themes.xml. */
    private val ourStyles: Map<String, String?> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(System.getProperty("risime.themes")))
        val styles = doc.getElementsByTagName("style")
        (0 until styles.length).associate { i ->
            val e = styles.item(i) as Element
            val name = e.getAttribute("name")
            val parent = e.getAttribute("parent").ifBlank { null }
                ?: name.substringBeforeLast('.', "").ifBlank { null } // implicit parent by name
            name to parent
        }
    }

    /** Follows our styles' parents until a library/framework style; true if that is AppCompat. */
    private fun isAppCompat(themeRef: String): Boolean {
        var name: String? = themeRef.removePrefix("@style/").removePrefix("@android:style/")
        val seen = mutableSetOf<String>()
        while (name != null && seen.add(name)) {
            if (name.startsWith("Theme.AppCompat") || name.startsWith("@style/Theme.AppCompat")) return true
            if (name.startsWith("android:") || themeRef.startsWith("@android:")) return false
            if (name !in ourStyles) return false // unknown library style: not provably AppCompat
            name = ourStyles[name]?.removePrefix("@style/")
        }
        return false
    }

    private fun check(variant: String) {
        val path = System.getProperty("risime.mergedManifest.$variant")
        val file = File(path ?: error("system property for $variant manifest not set (see app/build.gradle.kts)"))
        assertTrue("merged $variant manifest missing at $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val app = doc.getElementsByTagName("application").item(0) as Element
        val appTheme = app.getAttributeNS(androidNs, "theme")
        val activities = doc.getElementsByTagName("activity")
        val mustBeAppCompat = listOf("net.openid.appauth.", "lk.codegen.risime.MainActivity")
        val checked = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (i in 0 until activities.length) {
            val a = activities.item(i) as Element
            val name = a.getAttributeNS(androidNs, "name")
            if (mustBeAppCompat.none { name.startsWith(it) }) continue
            val theme = a.getAttributeNS(androidNs, "theme").ifBlank { appTheme }
            checked += name
            if (!isAppCompat(theme)) failures += "$variant: $name resolves to $theme (not Theme.AppCompat)"
        }
        assertTrue("$variant: AppAuth activities not found in the merged manifest", checked.count { it.startsWith("net.openid.appauth.") } >= 2)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun debugManifestThemesAreAppCompat() = check("debug")

    @Test fun releaseManifestThemesAreAppCompat() = check("release")
}
