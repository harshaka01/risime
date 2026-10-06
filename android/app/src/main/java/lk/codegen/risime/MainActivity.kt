package lk.codegen.risime

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import lk.codegen.risime.ui.RisiMeRoot
import lk.codegen.risime.ui.auth.AuthUi
import lk.codegen.risime.ui.theme.RisiMeTheme

/** A FragmentActivity because BiometricPrompt needs one (decision 014). */
class MainActivity : FragmentActivity() {
    private lateinit var authUi: AuthUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RisiMeApp).container
        authUi = AuthUi(this, container)
        setContent { RisiMeTheme { RisiMeRoot(container, authUi) } }
    }

    override fun onDestroy() {
        authUi.dispose()
        super.onDestroy()
    }
}
