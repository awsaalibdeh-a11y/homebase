package app.homebase.family.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.Member
import app.homebase.family.hb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loaded profile photos, kept in memory and keyed by the avatar url (which carries a version, so a new photo reloads). */
object Avatars {
    val cache = mutableStateMapOf<String, ImageBitmap?>()   // url -> bitmap, or null while loading / on failure
}

/** A member's photo if they have one, otherwise their emoji. Same round shape either way. */
@Composable
fun MemberAvatar(m: Member, size: Int = 52, tint: Color = AccentSoft) {
    val ctx = LocalContext.current
    val url = m.avatar
    val bmp = if (url != null) Avatars.cache[url] else null
    LaunchedEffect(url) {
        if (url != null && !Avatars.cache.containsKey(url)) {
            Avatars.cache[url] = null
            runCatching {
                val bytes = ctx.hb.api.avatarBytes(url)
                if (bytes != null) withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } else null
            }.onSuccess { if (it != null) Avatars.cache[url] = it }
        }
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        else Text(m.emoji, fontSize = (size * 0.5).sp)
    }
}
