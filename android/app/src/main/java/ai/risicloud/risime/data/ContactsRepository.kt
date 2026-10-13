package ai.risicloud.risime.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ai.risicloud.risime.data.db.ContactDao
import ai.risicloud.risime.data.db.ContactEntity
import ai.risicloud.risime.net.ApiClient
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.BlockedUser
import ai.risicloud.risime.net.Contact
import ai.risicloud.risime.net.FriendRequest
import ai.risicloud.risime.net.FriendsReply

/** Requests and blocks from `GET /friends` (memory only; refetched on every join and `friend` signal). */
data class FriendsState(
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
    val blocked: List<BlockedUser> = emptyList(),
    /** §21.4 (v1.20): lowercased user ids of friends whose phone isn't confirmed ("Phone not verified"). */
    val unconfirmed: Set<String> = emptySet(),
    /** v1.44 §39.1: lowercased user ids of friends with `group_calls: false` ("can't join calls yet"; absent = can). */
    val noGroupCalls: Set<String> = emptySet(),
)

/** §9.2: friends as local contact rows (friend = true). */
fun friendEntities(reply: FriendsReply): List<ContactEntity> = reply.friends.map {
    ContactEntity(it.phone, it.displayName, it.company, it.userId, registered = true, friend = true, vouchedByName = it.vouchedBy?.displayName, groupReady = it.groupReady)
}

/** Pre-v1.6 server (no /friends): the old allowlist contacts; everyone registered counts as a friend. */
fun contactEntities(contacts: List<Contact>): List<ContactEntity> = contacts.map {
    ContactEntity(it.phone, it.displayName, it.company, it.userId, it.registered, friend = it.registered)
}

/**
 * Friends replace contacts (contract v1.6). `/friends` is the source; non-friends keep their row
 * with friend = false so an old chat stays visible (read-only).
 */
class ContactsRepository(private val api: ApiClient, private val dao: ContactDao) {
    val contacts = dao.all()

    private val _friends = MutableStateFlow(FriendsState())
    val friendsState: StateFlow<FriendsState> = _friends.asStateFlow()

    private val lock = Mutex()

    fun contact(userId: String) = dao.observeByUserId(userId)

    /** GET /friends (or /contacts on a pre-v1.6 server) → local rows + requests. */
    suspend fun refresh(): ApiResult<Unit> = lock.withLock {
        when (val r = api.friends()) {
            is ApiResult.Ok -> {
                dao.replaceFriends(friendEntities(r.value))
                _friends.value = FriendsState(
                    r.value.incoming, r.value.outgoing, r.value.blocked,
                    unconfirmed = r.value.friends.filter { !it.phoneConfirmed }.map { it.userId.lowercase() }.toSet(),
                    noGroupCalls = r.value.friends.filter { it.groupCalls == false }.map { it.userId.lowercase() }.toSet(),
                )
                ApiResult.Ok(Unit)
            }
            is ApiResult.Error -> if (r.httpStatus == 404) legacyRefresh() else r
            is ApiResult.NetworkError -> r
        }
    }

    private suspend fun legacyRefresh(): ApiResult<Unit> = when (val r = api.contacts()) {
        is ApiResult.Ok -> {
            dao.replaceFriends(contactEntities(r.value.contacts))
            ApiResult.Ok(Unit)
        }
        is ApiResult.Error -> r
        is ApiResult.NetworkError -> r
    }

    fun clearMemory() {
        _friends.value = FriendsState()
    }
}
