package lk.codegen.risime.data

/** Tests stand in for the confirm dialog (production code may only use ConfirmLogout.kt). */
fun testConfirmation(deleteChats: Boolean = false): UserConfirmation = UserConfirmation.fromConfirmDialog(deleteChats)
