package lk.codegen.risime

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
        // The installer's confirmation (when Android doesn't allow a silent self-update).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.updater.confirm.filterNotNull().collect { intent ->
                    container.updater.confirmShown()
                    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
    }

    override fun onDestroy() {
        authUi.dispose()
        super.onDestroy()
    }
}
