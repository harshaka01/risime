package ai.risicloud.risime.data.tabs

import ai.risicloud.risime.net.RisiMeta

/**
 * Hotfix 2026-10-14 (P1, the missing morning brief): what a Risi message shows as text, so the chat
 * bubble and the chat-list preview (both built from `body`) never disagree, and a Risi message never
 * disappears.
 *
 * * The §35.4 brief is a `digest` with `scope: "brief"`; its greeting and sections live only in `body`
 *   (its `items` are just the promises). It used to fall into the group "Open items" card, which draws
 *   `items` only, so the chat showed "Open items · <date>" while the list preview showed the brief.
 * * A digest scope this version doesn't know, or one whose card would be empty, shows `body`.
 * * Rule: an unknown or unsupported kind shows its `body` (or `text`/`answer`); with none of them,
 *   [FALLBACK]. A `risi` object that doesn't even parse is drawn the same way (never a blank bubble).
 */
object RisiRender {
    const val FALLBACK = "Risi sent something this version can't show yet. Update RisiMe to see it."

    /** The `digest` scopes with a dedicated card: none (a group's open items) and `personal` (§27.6). */
    private val DIGEST_CARD_SCOPES = setOf(null, "personal")

    /** True when a `digest` is drawn as its `body` text (the brief, an unknown scope, or a card with no items). */
    fun digestShowsBody(r: RisiMeta): Boolean =
        r.kind == RisiKinds.DIGEST && (r.scope !in DIGEST_CARD_SCOPES || r.items.isEmpty())

    /** [body], or [FALLBACK] when it is blank. */
    fun bodyOrFallback(body: String?): String = body?.takeIf { it.isNotBlank() } ?: FALLBACK

    /** The card header of a `digest`. */
    fun digestHeader(r: RisiMeta): String = when (r.scope) {
        "brief" -> "Risi · Your day"
        "personal" -> "Risi · Your items"
        null -> "Risi · Open items"
        else -> "Risi"
    } + (r.date?.let { " · $it" } ?: "")

    /**
     * The main visible text of a Risi message drawn on its own (no other card to attach to): never blank.
     * [r] null = a `risi` object this version couldn't read.
     */
    fun visibleText(body: String?, r: RisiMeta?): String {
        r ?: return bodyOrFallback(body)
        val main = when {
            r.kind == RisiKinds.DIGEST && !digestShowsBody(r) -> r.items.joinToString("\n") { it.text }
            r.kind == RisiKinds.ANSWER -> r.answer
            r.kind == RisiKinds.SUMMARY -> r.summary
            r.kind == RisiKinds.COMMITMENT -> r.text
            else -> null
        }
        return main?.takeIf { it.isNotBlank() } ?: r.text?.takeIf { it.isNotBlank() && body.isNullOrBlank() } ?: bodyOrFallback(body)
    }
}
