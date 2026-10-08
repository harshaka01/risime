package lk.codegen.risime.data.tabs

import lk.codegen.risime.net.ApiResult

/**
 * §24.11 `PATCH /me {"tz"}`: the phone's IANA zone goes to the server at sign-in and whenever it changes
 * (so Risi's reminders and 09:00 digest run in the owner's zone). [lastSent] is remembered per user; a
 * failed send is retried on the next call (every reconnect), an unknown-zone refusal (422) is not.
 */
class TimezoneSync(
    private val zone: () -> String,
    private val lastSent: (user: String) -> String?,
    private val remember: (user: String, tz: String) -> Unit,
    private val patch: suspend (tz: String) -> ApiResult<*>,
) {
    /** @return true when a PATCH went out and was accepted. */
    suspend fun sync(user: String?): Boolean {
        user ?: return false
        val tz = zone()
        if (tz.isBlank() || lastSent(user) == tz) return false
        return when (val r = runCatching { patch(tz) }.getOrNull()) {
            is ApiResult.Ok -> { remember(user, tz); true }
            is ApiResult.Error -> { if (r.httpStatus == 422) remember(user, tz); false }
            else -> false
        }
    }
}
