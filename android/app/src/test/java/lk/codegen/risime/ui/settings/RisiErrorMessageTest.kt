package lk.codegen.risime.ui.settings

import lk.codegen.risime.net.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RisiErrorMessageTest {
    private fun err(status: Int) = ApiResult.Error(status, "http_$status", "")

    @Test
    fun notFoundAndForbiddenSayRisiIsUnavailable() {
        assertEquals(RISI_UNAVAILABLE, risiErrorMessage(err(404)))
        assertEquals(RISI_UNAVAILABLE, risiErrorMessage(err(403)))
    }

    @Test
    fun serverErrorsSayTheServerHadAProblem() {
        assertEquals(RISI_SERVER_PROBLEM, risiErrorMessage(err(500)))
        assertEquals(RISI_SERVER_PROBLEM, risiErrorMessage(err(503)))
    }

    @Test
    fun onlyNetworkTroubleSaysCouldntReachTheServer() {
        assertEquals(RISI_OFFLINE, risiErrorMessage(ApiResult.NetworkError(java.io.IOException("x"))))
        assertEquals(RISI_OFFLINE, risiErrorMessage(null))
    }
}
