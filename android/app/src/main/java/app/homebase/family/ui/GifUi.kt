package app.homebase.family.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.homebase.family.hb
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/** One picture loader for the whole app, with the decoder that makes GIFs move. */
object Pictures {
    private var loader: ImageLoader? = null
    fun get(ctx: Context): ImageLoader = loader ?: ImageLoader.Builder(ctx.applicationContext).components {
        if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build().also { loader = it }
}

/** A sticker is stored as a path on our own server ("/static/stickers/cat.gif"); a pasted GIF is an https link and is used as it is. */
fun pictureUrl(ctx: Context, body: String): String = if (body.startsWith("/")) ctx.hb.store.server + body else body

@Composable
fun GifImage(body: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(pictureUrl(ctx, body)).crossfade(true).build(),
        contentDescription = "GIF",
        imageLoader = Pictures.get(ctx),
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** The grid of stickers, plus a box to paste a GIF link (Giphy, Tenor, Imgur). [onPick] gets what to send: a sticker path or the link. */
@Composable
fun StickerTray(onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val stickers by ctx.hb.state.stickers.collectAsState()
    var link by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { ctx.hb.state.loadStickers() }
    Column(Modifier.fillMaxWidth().background(Surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val list = stickers
        if (list == null) Text("Loading stickers…", Modifier.padding(8.dp), color = Muted)
        else if (list.isEmpty()) Text("No stickers yet. You can still paste a GIF link below.", Modifier.padding(8.dp), color = Muted)
        else LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.name }) { s ->
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Surface2).clickable { onPick(s.url) }, contentAlignment = Alignment.Center) {
                    GifImage(s.url, Modifier.fillMaxSize().padding(4.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(link, { link = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Or paste a GIF link") })
            TextButton({ if (link.isNotBlank()) { onPick(link.trim()); link = "" } }) { Text("Send") }
        }
    }
}

fun openLink(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(ctx, "No app can open that link") }
}

/** A map app if there is one (geo: link), otherwise Google Maps in the browser. */
fun openDirections(ctx: Context, lat: Double, lng: Double, label: String) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(geo) } catch (e: Exception) { openLink(ctx, "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng") }
}

// ---- family games (Alibi): a message of kind "game" carries a join link; the button only appears for Alibi's own address
const val ALIBI_ORIGIN = "https://alibi-d9tn.onrender.com"
private val ALIBI_JOIN = Regex("^" + Regex.escape("$ALIBI_ORIGIN/#join/") + "[A-Za-z0-9]{4}$")

/** The join link at the end of a game message, but only if it is Alibi's address with a 4 letter or digit code. Anything else gets no button. */
fun gameJoinUrl(body: String): String? {
    val last = body.trim().split(Regex("\\s+")).lastOrNull() ?: return null
    return if (ALIBI_JOIN.matches(last)) last else null
}

/** The sentence of a game message without its link: "Aws started a game of Alibi. Join with code ABCD". */
fun gameText(body: String): String {
    val url = gameJoinUrl(body) ?: return body
    return body.trim().removeSuffix(url).trim().trimEnd(':').trim()
}

/** Opens the game with the person's own name added, so they do not have to type it. */
fun joinGame(ctx: Context, url: String, name: String) {
    val clean = gameJoinUrl(url) ?: return
    openLink(ctx, if (name.isBlank()) clean else clean + "/" + Uri.encode(name))
}

/** The game button in the chat: the server makes a one-time link, we open it, and the game posts the code and a Join button to the chat. */
fun startFamilyGame(ctx: Context, game: String = "alibi") {
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.startGame(game) }
            .onSuccess { r -> if (r.url.startsWith("https://")) { toast(ctx, "Opening ${r.game}…"); openLink(ctx, r.url) } else toast(ctx, "That game link did not look right") }
            .onFailure { toast(ctx, it.message ?: "Could not start the game") }
    }
}
