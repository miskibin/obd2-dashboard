package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue's descriptions, checked against the two files that actually have to carry
 * them.
 *
 * A missing translation is invisible in Kotlin — `R.string.metric_desc_pid_5c` compiles
 * whether or not `values-pl` has ever heard of it, and the app then shows an English
 * paragraph to a Polish driver. So the parity check reads the resource XML itself rather
 * than anything the compiler can see, which also means it covers every key added later
 * without anybody having to remember this test exists.
 */
class MetricDescriptionsTest {

    @Test
    fun `every metric in the catalogue has a description of its own`() {
        val fallback = R.string.metric_desc_unknown
        val missing = Metrics.catalog.filter { it.descriptionRes == fallback }
        assertTrue(
            "no metric should fall back to the unknown-parameter description: " +
                missing.joinToString { it.id.storageKey },
            missing.isEmpty(),
        )
        assertTrue(Metrics.catalog.all { it.descriptionRes != 0 })
    }

    @Test
    fun `the description of a family is shared by its channels and differs between them`() {
        // Both channels of a wide-range probe are the same sensor, so they share the
        // sensor's story; the lambda it measures and the signal voltage behind it are not
        // the same reading, so they do not share a paragraph.
        val lambda = description(sensorKeyOf(0x24, 0))
        val voltage = description(sensorKeyOf(0x24, 1))
        assertNotEquals(lambda, voltage)
        assertEquals(lambda, description(sensorKeyOf(0x2B, 0)))
    }

    @Test
    fun `both locales define exactly the same string keys`() {
        val english = keysOf(stringsFile("values"))
        val polish = keysOf(stringsFile("values-pl"))
        assertEquals(
            "keys missing from values-pl",
            emptySet<String>(),
            english - polish,
        )
        assertEquals(
            "keys in values-pl with nothing to translate",
            emptySet<String>(),
            polish - english,
        )
    }

    @Test
    fun `descriptions are written as a paragraph rather than a subtitle`() {
        listOf("values", "values-pl").forEach { locale ->
            val text = stringsFile(locale).readText()
            DESCRIPTION.findAll(text).forEach { match ->
                val (name, body) = match.destructured
                assertTrue(
                    "$locale/$name is too short to be a description",
                    body.length >= MIN_DESCRIPTION_LENGTH,
                )
                assertTrue(
                    "$locale/$name should be more than one sentence",
                    body.count { it == '.' } >= MIN_SENTENCES,
                )
            }
        }
    }

    private fun description(key: Int): Int =
        Metrics[MetricId.Sensor(key)]?.descriptionRes ?: error("no metric for key $key")

    private fun sensorKeyOf(pid: Int, channel: Int): Int =
        com.miskibin.obd2dashboard.obd.sensorKey(pid, channel)

    private fun keysOf(file: File): Set<String> =
        NAME.findAll(file.readText()).map { it.groupValues[1] }.toSet()

    /**
     * Unit tests run from the module directory under Gradle and from the project root in
     * some IDE run configurations, so the file is looked for from both.
     */
    private fun stringsFile(locale: String): File {
        val relative = "src/main/res/$locale/strings.xml"
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull(File::exists)
            ?: error("cannot find $relative from ${File(".").absolutePath}")
    }

    private companion object {
        val NAME = Regex("""<string name="([^"]+)"""")
        val DESCRIPTION = Regex("""<string name="(metric_desc_[^"]+)">(.*?)</string>""")
        const val MIN_DESCRIPTION_LENGTH = 80
        const val MIN_SENTENCES = 2
    }
}
