package lk.codegen.risime.data.tabs

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import lk.codegen.risime.net.ClientPermission
import lk.codegen.risime.net.RisiSkill

/** The Android side of [SkillPermissions]: what the phone says, and the prompt through [asker]. */
class AndroidSkillPermissions(
    private val context: Context,
    private val runtimeAsker: () -> (suspend (Array<String>) -> Unit)?,
    private val exactAlarmAsker: () -> (suspend () -> Unit)?,
    /** v1.31 §31.2: a skill whose OAuth grant is held on this phone (the Google grant IS the Calendar skill's permission). */
    private val externallyGranted: (RisiSkill) -> Boolean = { false },
) : SkillPermissions {
    private val prefs = context.getSharedPreferences("risime_skills", Context.MODE_PRIVATE)

    private fun asked(p: String) = prefs.getBoolean("asked_$p", false)

    private fun markAsked(p: String) = prefs.edit().putBoolean("asked_$p", true).apply()

    /** A Clock app handles `ACTION_SET_ALARM` (§26.1 `unsupported` otherwise). */
    fun clockAvailable(): Boolean =
        runCatching { Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(context.packageManager) != null }.getOrDefault(false)

    fun exactAlarmsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)

    private fun granted(p: String): Boolean = when (p) {
        Manifest.permission.POST_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT < 33) NotificationManagerCompat.from(context).areNotificationsEnabled()
            else ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        Manifest.permission.SCHEDULE_EXACT_ALARM -> exactAlarmsAllowed()
        else -> ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun runtimeOf(skill: RisiSkill): List<String> = skill.permissions.filter { it.scope == "android" && it.runtime }.map { it.name }

    override fun current(skill: RisiSkill): String {
        if (skill.permissions.any { it.name == "com.android.alarm.permission.SET_ALARM" } && !clockAvailable()) return ClientPermission.UNSUPPORTED
        if (externallyGranted(skill)) return ClientPermission.GRANTED
        val runtime = runtimeOf(skill)
        if (runtime.isEmpty()) return ClientPermission.NOT_NEEDED
        val states = runtime.map { p -> if (granted(p)) ClientPermission.GRANTED else if (asked(p)) ClientPermission.DENIED else ClientPermission.NOT_ASKED }
        return when {
            states.all { it == ClientPermission.GRANTED } -> ClientPermission.GRANTED
            ClientPermission.DENIED in states -> ClientPermission.DENIED
            else -> ClientPermission.NOT_ASKED
        }
    }

    override suspend fun request(skill: RisiSkill): String {
        val now = current(skill)
        if (now == ClientPermission.GRANTED || now == ClientPermission.NOT_NEEDED || now == ClientPermission.UNSUPPORTED) return now
        val missing = runtimeOf(skill).filter { !granted(it) }
        val exact = Manifest.permission.SCHEDULE_EXACT_ALARM in missing
        val normal = missing.filter { it != Manifest.permission.SCHEDULE_EXACT_ALARM && !(it == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) }
        if (normal.isNotEmpty()) {
            val ask = runtimeAsker() ?: return now
            ask(normal.toTypedArray())
            normal.forEach(::markAsked)
        }
        if (exact) {
            exactAlarmAsker()?.invoke()
            markAsked(Manifest.permission.SCHEDULE_EXACT_ALARM)
        }
        if (Manifest.permission.POST_NOTIFICATIONS in missing && Build.VERSION.SDK_INT < 33) markAsked(Manifest.permission.POST_NOTIFICATIONS)
        return current(skill)
    }
}
