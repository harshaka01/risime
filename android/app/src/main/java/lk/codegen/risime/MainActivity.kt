package lk.codegen.risime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import lk.codegen.risime.ui.RisiMeRoot
import lk.codegen.risime.ui.theme.RisiMeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RisiMeApp).container
        setContent { RisiMeTheme { RisiMeRoot(container) } }
    }
}
