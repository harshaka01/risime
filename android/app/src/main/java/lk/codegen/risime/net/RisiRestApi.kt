package lk.codegen.risime.net

/** [RisiRest] over the app's [ApiClient]. */
class RisiRestApi(private val api: ApiClient) : RisiRest {
    override suspend fun feedback(callRef: String, rating: String, reason: String?) = api.risiFeedback(RisiFeedback(callRef, rating, reason?.trim()?.take(500)?.takeIf { it.isNotEmpty() }))

    override suspend fun facts() = api.risiFacts()

    override suspend fun deleteFact(factId: String) = api.deleteRisiFact(factId)

    override suspend fun deleteAllFacts() = api.deleteRisiFacts()

    override suspend fun commitments(state: String) = api.risiCommitments(state)
}
