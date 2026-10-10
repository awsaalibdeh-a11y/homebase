package app.homebase.family.ui

import androidx.compose.runtime.mutableStateListOf
import app.homebase.family.InviteLook

sealed interface Screen {
    data object Welcome : Screen
    data object About : Screen
    data object Create : Screen
    data object JoinCode : Screen
    data class Consent(val code: String, val name: String, val emoji: String, val look: InviteLook) : Screen
    data object Permissions : Screen
    data object Main : Screen
    data class Thread(val id: Int) : Screen
    data object Places : Screen
    data object AddPlace : Screen
    data class History(val memberId: Int) : Screen
    data object Lists : Screen
    data object Settings : Screen
    data object Transparency : Screen
    data object Perms : Screen
    data object Tour : Screen
    data object Invite : Screen
}

class Nav(initial: Screen) {
    val stack = mutableStateListOf(initial)
    val current: Screen get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean { if (stack.size > 1) { stack.removeAt(stack.size - 1); return true }; return false }
    fun reset(s: Screen) { stack.clear(); stack.add(s) }
}
