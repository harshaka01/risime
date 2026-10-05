package lk.codegen.risime.data

import io.michaelrocks.libphonenumber.android.MetadataLoader
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Properties

class PhoneNormalizerTest {
    /** Loads the real libphonenumber-android metadata from the merged assets (no Android runtime needed). */
    private val normalizer: PhoneNormalizer by lazy {
        val props = Properties().apply {
            PhoneNormalizerTest::class.java.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use { load(it) }
        }
        val assets = File(props.getProperty("android_merged_assets"))
        PhoneNormalizer(PhoneNumberUtil.createInstance(MetadataLoader { name -> File(assets, name.removePrefix("/")).inputStream() }))
    }

    @Test
    fun e164Unchanged() = assertEquals("+94771234567", normalizer.normalize("+94771234567"))

    @Test
    fun spacesAndDashes() = assertEquals("+94771234567", normalizer.normalize(" +94 77 123-4567 "))

    @Test
    fun localSriLankanNumber() = assertEquals("+94771234567", normalizer.normalize("0771234567"))

    @Test
    fun otherCountryWithPlus() = assertEquals("+447911123456", normalizer.normalize("+44 7911 123456"))

    @Test
    fun invalid() {
        assertNull(normalizer.normalize(""))
        assertNull(normalizer.normalize("+94"))
        assertNull(normalizer.normalize("12"))
        assertNull(normalizer.normalize("not a number"))
    }
}
