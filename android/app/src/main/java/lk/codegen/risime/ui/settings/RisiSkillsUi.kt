package lk.codegen.risime.ui.settings

import androidx.compose.runtime.Composable
import lk.codegen.risime.AppContainer

/** Settings → Risi skills (§26.2); `?skill=<id>` opens it at that skill. */
const val RISI_SKILLS_ROUTE = "risi_skills"

@Composable
fun RisiSkillsRoute(c: AppContainer, skillId: String?, onBack: () -> Unit) {
    androidx.compose.material3.Text("Risi skills")
}
