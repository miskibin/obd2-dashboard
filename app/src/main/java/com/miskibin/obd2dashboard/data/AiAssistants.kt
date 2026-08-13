package com.miskibin.obd2dashboard.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The AI assistant apps a recorded trip can be handed to directly.
 *
 * The point of naming them, rather than leaving the driver to the share sheet, is the
 * distance between "share" and "ask": the share sheet is forty targets in the order the
 * phone likes, and the assistant the driver actually wants is somewhere in the fold. A
 * short list of the apps that can take a CSV and answer questions about it turns the
 * errand into one tap.
 *
 * Only apps actually installed — and actually declaring they accept a file like ours —
 * are offered, which is what keeps the tap from throwing [android.content.ActivityNotFoundException]
 * at the driver.
 */
object AiAssistants {

    /**
     * One assistant found on this phone.
     *
     * [sendType] is the MIME type its share filter matched. Assistants differ: one
     * declares `text/csv`, the next only `text/plain` or the full wildcard, and an intent
     * typed with something a filter does not match simply fails to resolve. The probe below
     * records which type worked so the real send uses the same one; the attachment itself
     * is the CSV file either way.
     */
    data class Assistant(val packageName: String, val label: String, val sendType: String)

    /**
     * Packages probed, in the order they are offered.
     *
     * Must stay in step with the `<queries>` declarations in the manifest: on Android 11+
     * an app can only see the packages it has declared, and a package missing there would
     * silently never be offered however many drivers have it installed.
     */
    private val KNOWN_PACKAGES = listOf(
        "com.openai.chatgpt", // ChatGPT
        "com.google.android.apps.bard", // Google Gemini
        "com.anthropic.claude", // Claude
        "com.microsoft.copilot", // Microsoft Copilot
        "ai.perplexity.app.android", // Perplexity
    )

    /** From the most specific to the type every share target takes. */
    private val CANDIDATE_TYPES = listOf(
        TripRepository.MIME_TYPE,
        "text/comma-separated-values",
        "text/plain",
        "*/*",
    )

    /** The assistants installed right now, each with the MIME type it will accept. */
    fun installed(context: Context): List<Assistant> {
        val pm = context.packageManager
        return KNOWN_PACKAGES.mapNotNull { pkg -> assistantOf(pm, pkg) }
    }

    private fun assistantOf(pm: PackageManager, pkg: String): Assistant? {
        val type = CANDIDATE_TYPES.firstOrNull { candidate ->
            pm.resolveActivity(sendProbe(pkg, candidate), PackageManager.MATCH_DEFAULT_ONLY) != null
        } ?: return null
        val label = runCatching {
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Assistant(packageName = pkg, label = label, sendType = type)
    }

    private fun sendProbe(pkg: String, mimeType: String) = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        setPackage(pkg)
    }
}
