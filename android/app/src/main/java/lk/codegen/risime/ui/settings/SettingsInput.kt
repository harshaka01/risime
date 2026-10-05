package lk.codegen.risime.ui.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Result of checking user input: the normalised value, or a message to show. */
sealed interface Checked {
    data class Valid(val value: String) : Checked
    data class Invalid(val message: String) : Checked
}

/**
 * Server base URL: http or https, a host, no query or fragment. Trailing slashes are dropped
 * (the API client appends `/api/v1/...`). Cleartext http only works for 10.0.2.2 and 127.0.0.1
 * (network security config); the tailnet is reached over https.
 */
fun checkServerUrl(input: String): Checked {
    val raw = input.trim()
    if (raw.isEmpty()) return Checked.Invalid("Enter the server URL")
    val scheme = raw.substringBefore("://", "").lowercase()
    if (scheme != "http" && scheme != "https") {
        return Checked.Invalid("The URL must start with http:// or https://")
    }
    val url = raw.toHttpUrlOrNull() ?: return Checked.Invalid("That isn't a valid URL")
    if (url.host.isBlank()) return Checked.Invalid("The URL needs a host")
    if (url.query != null || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()) {
        return Checked.Invalid("Use just the server address, e.g. https://risime.example.ts.net")
    }
    return Checked.Valid(raw.trimEnd('/'))
}

/** Same comparison the app uses for "did the server change" (case-insensitive scheme/host, no trailing slash). */
fun sameServer(a: String, b: String): Boolean {
    val ua = a.trim().trimEnd('/').toHttpUrlOrNull()
    val ub = b.trim().trimEnd('/').toHttpUrlOrNull()
    return if (ua != null && ub != null) ua == ub else a.trim().trimEnd('/') == b.trim().trimEnd('/')
}

/** PROTOCOL.md §1.3 / server: display name is 1–64 characters after trimming. */
const val DISPLAY_NAME_MAX = 64

fun checkDisplayName(input: String): Checked {
    val name = input.trim()
    return when {
        name.isEmpty() -> Checked.Invalid("Enter a display name")
        name.length > DISPLAY_NAME_MAX -> Checked.Invalid("At most $DISPLAY_NAME_MAX characters")
        else -> Checked.Valid(name)
    }
}
