package app.homebase.family.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import app.homebase.family.Recipe
import app.homebase.family.RecipeTag
import app.homebase.family.hb
import coil.compose.AsyncImage
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Search the recipe library inside Mellow. A parent adds a recipe to the plan; a child suggests it and a parent says yes. Anyone can open it in Platter. */
@Composable
fun RecipesScreen(nav: Nav, surprise: Boolean) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    var ideas by remember { mutableStateOf(surprise) }
    var results by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var tags by remember { mutableStateOf<List<RecipeTag>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Recipe?>(null) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(submitted, tag, ideas, round) {
        busy = true
        runCatching { ctx.hb.api.recipes(submitted, tag, random = ideas || (submitted.isBlank() && tag == null)) }
            .onSuccess { results = it.recipes; if (it.tags.isNotEmpty()) tags = it.tags; failed = false }
            .onFailure { failed = true }
        busy = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Search recipes", if (ctx.hb.store.isParent) "Pick one and add it to the meal plan." else "Pick one and suggest it. A parent says yes.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp), placeholder = { Text("Chicken, pasta, soup…") })
            TextButton({ ideas = false; submitted = query.trim() }) { Text("Search") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("All", tag == null) { tag = null; ideas = false }
            tags.forEach { t -> Pill(t.name, tag == t.id) { tag = if (tag == t.id) null else t.id; ideas = false } }
        }
        SoftButton("🎲 Surprise me") { query = ""; submitted = ""; tag = null; ideas = true; round++ }
        if (failed) CenterNote("Could not load recipes. Check your signal and try again.")
        else if (busy) CenterNote("Looking…")
        else if (results.isEmpty()) CenterNote("No recipes found. Try another word.")
        results.forEach { r ->
            SoftCard(onClick = { picked = r }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (r.img.startsWith("https://")) AsyncImage(r.img, null, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    else Avatar("🍽️", 64, AccentSoft)
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(listOfNotNull(if (r.min > 0) "${r.min} min" else null, if (r.serves > 0) "serves ${r.serves}" else null, r.area.ifBlank { null }).joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Gap(8)
    }
    picked?.let { RecipeDialog(it) { picked = null } }
}

@Composable
private fun RecipeDialog(r: Recipe, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val parent = ctx.hb.store.isParent
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var slot by remember { mutableStateOf("dinner") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(r.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.img.startsWith("https://")) AsyncImage(r.img, null, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                Text(listOfNotNull(if (r.min > 0) "${r.min} min" else null, if (r.serves > 0) "serves ${r.serves}" else null).joinToString(" · "), color = Muted)
                SoftButton(dayLabel(date.toString())) { DatePickerDialog(ctx, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show() }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MEAL_SLOTS.forEach { s -> Pill(slotIcon(s) + " " + slotName(s), slot == s) { slot = s } }
                }
                if (r.ing.isNotEmpty()) { Text("Ingredients", style = MaterialTheme.typography.titleMedium); r.ing.forEach { Text("•  $it", fontSize = 13.sp) } }
                if (r.steps.isNotEmpty()) { Text("Method", style = MaterialTheme.typography.titleMedium); r.steps.forEachIndexed { i, s -> Text("${i + 1}.  $s", fontSize = 13.sp) } }
                TextButton({ openLink(ctx, r.platter) }) { Text("Open in Platter ↗") }
            }
        },
        confirmButton = {
            TextButton({
                busy = true
                ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.addRecipeMeal(r.id, date.toString(), slot) }
                        .onSuccess { suggested ->
                            ctx.hb.state.refreshMeals()
                            toast(ctx, if (suggested) "Suggested. A parent will see it." else "Added to the meal plan")
                            onDismiss()
                        }
                        .onFailure { toast(ctx, it.message ?: "Could not add it"); busy = false }
                }
            }, enabled = !busy) { Text(if (parent) "Add to the plan" else "Suggest this") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Close") } },
    )
}
