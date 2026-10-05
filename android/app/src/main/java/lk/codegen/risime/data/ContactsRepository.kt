package lk.codegen.risime.data

import lk.codegen.risime.data.db.ContactDao
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult

class ContactsRepository(private val api: ApiClient, private val dao: ContactDao) {
    val contacts = dao.all()

    fun contact(userId: String) = dao.observeByUserId(userId)

    /** Replaces the local contact list with GET /contacts. Returns the API result for error handling. */
    suspend fun refresh(): ApiResult<Unit> = when (val r = api.contacts()) {
        is ApiResult.Ok -> {
            dao.clear()
            dao.upsertAll(
                r.value.contacts.map { ContactEntity(it.phone, it.displayName, it.company, it.userId, it.registered) },
            )
            ApiResult.Ok(Unit)
        }
        is ApiResult.Error -> r
        is ApiResult.NetworkError -> r
    }
}
