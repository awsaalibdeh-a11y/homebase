package app.homebase.family.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.Meal
import app.homebase.family.hb
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val SLOT_ICON = mapOf("breakfast" to "🥣", "lunch" to "🥪", "dinner" to "🍽️", "snack" to "🍎")
val MEAL_SLOTS = listOf("breakfast", "lunch", "dinner", "snack")

fun slotIcon(slot: String) = SLOT_ICON[slot] ?: "🍽️"
fun slotName(slot: String) = slot.replaceFirstChar { it.uppercase() }

/** "Today", "Tomorrow", or "Saturday 24 Oct" for a day written 2026-10-24. */
fun dayLabel(day: String): String {
    val d = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault()))
    }
}

fun mealCookLine(m: Meal, me: Int, nameOf: (Int) -> String): String =
    if (m.cook == 0) "" else if (m.cook == me) "You are cooking" else nameOf(m.cook) + " is cooking"

/** "I'll cook it": put your own name on a meal, or take it off. */
fun toggleCook(ctx: Context, m: Meal, then: () -> Unit = {}) {
    val me = ctx.hb.store.myId
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.setCook(m.id, if (m.cook == me) 0 else me) }
            .onSuccess { ctx.hb.state.refreshMeals(); then() }
            .onFailure { toast(ctx, it.message ?: "Could not do that") }
    }
}

/** Puts a meal's ingredients on the family list, skipping what is already waiting there. */
fun mealToList(ctx: Context, m: Meal) {
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.mealToList(m.id) }
            .onSuccess { n -> toast(ctx, if (n > 0) "Added $n to the family list" else "Everything is already on the list"); ctx.hb.state.refreshItems() }
            .onFailure { toast(ctx, it.message ?: "Could not add them") }
    }
}

/** A parent's answer to a child's meal suggestion. */
fun answerMeal(ctx: Context, m: Meal, ok: Boolean) {
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.approveMeal(m.id, ok) }
            .onSuccess { ctx.hb.state.refreshMeals(); toast(ctx, if (ok) "Planned" else "Removed") }
            .onFailure { toast(ctx, it.message ?: "Could not save that") }
    }
}

@Composable
fun MealCard(m: Meal, nav: Nav, nameOf: (Int) -> String) {
    val ctx = LocalContext.current
    val me = ctx.hb.store.myId
    val cook = mealCookLine(m, me, nameOf)
    SoftCard(onClick = { nav.go(Screen.Meal(m.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (m.photo.isNotBlank()) AsyncImage(m.photo, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
            else Avatar(slotIcon(m.slot), 56, AccentSoft)
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium)
                Text(slotName(m.slot) + (if (m.servings > 0) " · serves ${m.servings}" else "") + (if (m.via.isNotBlank() && m.via != "homebase") " · from ${slotName(m.via)}" else ""), color = Muted, style = MaterialTheme.typography.bodyMedium)
                if (cook.isNotBlank()) Text("🍳 $cook", color = Ink, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (m.suggested) {
            Chip("💡 Suggested by ${nameOf(m.by)}", SunSoft, Ink)
            if (ctx.hb.store.isParent) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Yes, plan it", true) { answerMeal(ctx, m, true) }
                Pill("Not now", false) { answerMeal(ctx, m, false) }
            } else Text("A parent will say yes or not now.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (m.cook == me) "✓ I'm cooking" else "I'll cook", m.cook == me) { toggleCook(ctx, m) }
            if (m.ingredients.isNotEmpty()) Pill("＋ To the list", false) { mealToList(ctx, m) }
        }
    }
}

/** The meal plan: the next days, grouped by day, with who is cooking. */
@Composable
fun MealsSection(nav: Nav, nameOf: (Int) -> String, onAdd: () -> Unit) {
    val meals by LocalContext.current.hb.state.meals.collectAsState()
    val today = LocalDate.now().toString()
    val byDay = meals.filter { it.day >= today }.groupBy { it.day }.toSortedMap().entries.take(7)
    SectionLabel("🍽️ Meal plan")
    byDay.forEach { (day, list) ->
        Text(dayLabel(day), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium)
        list.sortedBy { MEAL_SLOTS.indexOf(it.slot) }.forEach { MealCard(it, nav, nameOf) }
    }
    if (byDay.isEmpty()) CenterNote("Nothing planned yet. Add a meal, or plan one from Platter and it shows up here.")
    TwoUp({ SoftButton("＋ Add a meal", onClick = onAdd) }, { SoftButton("🔎 Search recipes") { nav.go(Screen.Recipes()) } })
}

@Composable
fun AddMealDialog(day: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()) }
    var slot by remember { mutableStateOf("dinner") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Add a meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Everyone in the family sees what we are having.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), placeholder = { Text("What are we eating?") }, singleLine = true)
                SoftButton(dayLabel(date.toString())) {
                    DatePickerDialog(ctx, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MEAL_SLOTS.forEach { s -> Pill(slotIcon(s) + " " + slotName(s), slot == s) { slot = s } }
                }
            }
        },
        confirmButton = {
            TextButton({
                if (title.isBlank()) toast(ctx, "What are we eating?")
                else ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.addMeal(title.trim(), date.toString(), slot) }
                        .onSuccess { ctx.hb.state.refreshMeals(); toast(ctx, "Meal added"); onDismiss() }
                        .onFailure { toast(ctx, it.message ?: "Could not add it") }
                }
            }) { Text("Add it") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** One meal in full: photo, who is cooking, ingredients, method and a link to the recipe. */
@Composable
fun MealScreen(nav: Nav, id: Int) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val me = app.store.myId
    val fam by app.state.family.collectAsState()
    val list by app.state.meals.collectAsState()
    var shown by remember { mutableStateOf(list.firstOrNull { it.id == id } ?: app.state.mealsSeen[id]) }
    var gone by remember { mutableStateOf(false) }
    val nameOf: (Int) -> String = { who -> fam?.members?.firstOrNull { it.id == who }?.name ?: "Someone" }
    // pull this one meal again, so the cook line is right even for a day outside the next two weeks
    suspend fun reload() {
        val day = shown?.day ?: return
        runCatching { app.api.meals(day, day) }.onSuccess { found -> found.firstOrNull { it.id == id }?.let { shown = it; app.state.mealsSeen[id] = it } }
    }
    LaunchedEffect(id, list) { list.firstOrNull { it.id == id }?.let { shown = it } }
    val m = shown
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        if (m == null || gone) { CenterNote("This meal is not on the plan any more."); return@Column }
        if (m.photo.isNotBlank()) AsyncImage(m.photo, null, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
        Heading("${slotIcon(m.slot)} ${m.title}", dayLabel(m.day) + " · " + slotName(m.slot) + (if (m.servings > 0) " · serves ${m.servings}" else "") + (if (m.via.isNotBlank() && m.via != "homebase") " · from ${slotName(m.via)}" else ""))
        val cook = mealCookLine(m, me, nameOf)
        Text(if (cook.isBlank()) "Nobody has said they will cook it yet." else "🍳 $cook", color = Muted)
        BigButton(if (m.cook == me) "✓ I'm cooking this" else "I'll cook this") { toggleCook(ctx, m) { app.scope.launch { reload() } } }
        if (m.ingredients.isNotEmpty()) SoftButton("＋ Put the ingredients on the list") { mealToList(ctx, m) }
        if (m.ingredients.isNotEmpty()) {
            SectionLabel("Ingredients")
            SoftCard { m.ingredients.forEach { Text("•  $it", Modifier.padding(vertical = 2.dp)) } }
        }
        if (m.steps.isNotEmpty()) {
            SectionLabel("Method")
            SoftCard { m.steps.forEachIndexed { i, step -> Text("${i + 1}.  $step", Modifier.padding(vertical = 4.dp)) } }
        }
        if (m.source.startsWith("https://")) SoftButton("Open the full recipe" + (if (m.via == "platter") " in Platter" else "") + " ↗") { openLink(ctx, m.source) }
        if (app.store.isParent || m.by == me) {
            TextButton({
                app.scope.launch {
                    runCatching { app.api.dropMeal(m.id) }
                        .onSuccess { app.state.refreshMeals(); gone = true; nav.back() }
                        .onFailure { toast(ctx, it.message ?: "Could not remove it") }
                }
            }) { Text("Remove this meal", color = Rose) }
        }
        Box(Modifier.height(8.dp))
    }
}
