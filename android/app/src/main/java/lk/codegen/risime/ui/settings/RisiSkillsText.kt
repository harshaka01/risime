package lk.codegen.risime.ui.settings

import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.RisiSkillsErrors
import lk.codegen.risime.net.RisiUndo
import lk.codegen.risime.net.RisiUndoReply

/** §26.4 the notice after an [Undo] tap. */
fun undoResultText(r: ApiResult<RisiUndoReply>): String = when (r) {
    is ApiResult.Ok -> when (r.value.entry.undo.state) {
        RisiUndo.DONE -> "Undone"
        RisiUndo.PENDING -> "Undoing on your phone…"
        else -> "Undo requested"
    }
    is ApiResult.Error -> when (r.code) {
        RisiSkillsErrors.UNDO_UNAVAILABLE -> "This can't be undone any more."
        "not_found" -> "That's no longer in your activity."
        else -> "Couldn't undo. Try again."
    }
    is ApiResult.NetworkError -> "No connection. Try again."
}
