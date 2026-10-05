package lk.codegen.risime.ui.search

/** `%query%` for SQL LIKE with `\` as the escape character, so `%`, `_` and `\` match literally. */
fun likePattern(query: String): String {
    val q = query.trim()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return "%$q%"
}
