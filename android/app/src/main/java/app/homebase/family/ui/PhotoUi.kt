package app.homebase.family.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.homebase.family.hb
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads a picture from the phone's gallery, turned the right way up and no bigger than [maxSide] pixels. */
suspend fun readBitmap(ctx: Context, uri: Uri, maxSide: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
        val turn = ctx.contentResolver.openInputStream(uri)!!.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        fit(rotated(raw, turn), maxSide)
    }.getOrNull()
}

fun rotated(b: Bitmap, degrees: Float): Bitmap =
    if (degrees == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees) }, true)

fun fit(b: Bitmap, maxSide: Int): Bitmap {
    val big = maxOf(b.width, b.height)
    if (big <= maxSide) return b
    val k = maxSide.toFloat() / big
    return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
}

/** A small JPEG as a data: link, ready for the server. */
fun jpegDataUrl(b: Bitmap, quality: Int = 82): String {
    val out = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/** Shrinks a photo, uploads it, and sends it as a photo message to [to] (0 is the whole family). */
fun sendPhoto(ctx: Context, photo: Bitmap, to: Int) {
    ctx.hb.scope.launch {
        runCatching {
            val url = withContext(Dispatchers.Default) { jpegDataUrl(fit(photo, 1280), 80) }.let { ctx.hb.api.uploadImage(it) }
            ctx.hb.api.send("image", url, to)
        }.onSuccess { ctx.hb.state.add(it); toast(ctx, "Photo sent") }
            .onFailure { toast(ctx, it.message ?: "Could not send the photo") }
    }
}

/** Profile pictures have to show a face: exactly one, big enough to see. The picture is then cropped around it. Nothing leaves the phone for this check. */
object FaceCheck {
    class Result(val photo: Bitmap?, val problem: String?)

    suspend fun check(src: Bitmap): Result {
        val small = fit(src, 1024)
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(0.15f).build())
        val faces = try {
            suspendCoroutine<List<com.google.mlkit.vision.face.Face>?> { k ->
                detector.process(InputImage.fromBitmap(small, 0)).addOnSuccessListener { k.resume(it) }.addOnFailureListener { k.resume(null) }
            }
        } finally { runCatching { detector.close() } }
        if (faces == null) return Result(null, "Could not check the photo. Try again.")
        if (faces.isEmpty()) return Result(null, "We cannot see a face. Look at the camera in good light, with your whole face in the circle.")
        if (faces.size > 1) return Result(null, "There is more than one face. A profile picture has to be just you.")
        val box = faces[0].boundingBox
        val side = maxOf(box.width(), box.height())
        if (side < 0.22f * minOf(small.width, small.height)) return Result(null, "Come closer, so your face fills more of the picture.")
        val crop = (side * 2.3f).toInt().coerceAtMost(minOf(small.width, small.height)).coerceAtLeast(1)
        val left = (box.centerX() - crop / 2).coerceIn(0, small.width - crop)
        val top = (box.centerY() - crop / 2).coerceIn(0, small.height - crop)
        return Result(Bitmap.createBitmap(small, left, top, crop, crop), null)
    }
}

private suspend fun cameraProvider(ctx: Context): ProcessCameraProvider = suspendCoroutine { k ->
    val f = ProcessCameraProvider.getInstance(ctx)
    f.addListener({ k.resume(f.get()) }, ContextCompat.getMainExecutor(ctx))
}

/**
 * The in-app camera. [purpose] is "pfp" (a profile picture: front camera, a face is required) or "chat:<id>" (a photo for that conversation).
 * After the shot you see it and choose Use or Retake.
 */
@Composable
fun CameraScreen(nav: Nav, purpose: String) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val pfp = purpose == "pfp"
    var front by remember { mutableStateOf(pfp) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(front, granted, shot == null) {
        if (!granted || shot != null) return@LaunchedEffect
        runCatching {
            val provider = cameraProvider(ctx)
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }.onFailure { problem = "The camera would not start." }
    }
    fun shoot() {
        busy = true
        capture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = runCatching { rotated(image.toBitmap(), image.imageInfo.rotationDegrees.toFloat()) }.getOrNull()
                image.close()
                busy = false
                if (bmp != null) { shot = bmp; problem = null } else problem = "Could not take the photo."
            }
            override fun onError(exception: ImageCaptureException) { busy = false; problem = "Could not take the photo." }
        })
    }
    fun use(b: Bitmap) {
        busy = true
        ctx.hb.scope.launch {
            if (pfp) {
                val r = FaceCheck.check(b)
                if (r.photo == null) { problem = r.problem; shot = null; busy = false; return@launch }
                runCatching { ctx.hb.api.setAvatar(jpegDataUrl(Bitmap.createScaledBitmap(r.photo, 256, 256, true), 85)) }
                    .onSuccess { ctx.hb.state.refreshFamily(); Avatars.cache.clear(); toast(ctx, "Profile picture saved"); nav.back() }
                    .onFailure { problem = it.message ?: "Could not save it."; shot = null }
            } else if (purpose == "album") {
                runCatching { ctx.hb.api.addToAlbum(ctx.hb.api.uploadImage(jpegDataUrl(fit(b, 1600), 82)), "") }
                    .onSuccess { toast(ctx, "Added to the album"); nav.back() }.onFailure { problem = it.message ?: "Could not add it."; shot = null }
            } else {
                sendPhoto(ctx, b, purpose.removePrefix("chat:").toIntOrNull() ?: 0)
                nav.back()
            }
            busy = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val picture = shot
        if (picture != null) Image(picture.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (granted) AndroidView({ view }, Modifier.fillMaxSize())
        if (pfp && picture == null && granted) Box(Modifier.fillMaxSize().drawWithContent {
            drawContent()
            val r = size.minDimension * 0.36f
            drawCircle(Color.White.copy(alpha = 0.9f), r, Offset(size.width / 2, size.height * 0.42f), style = Stroke(width = 4f))
        })
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ nav.back() }) { Text("Cancel", color = Color.White) }
                Text(if (pfp) "Take your profile picture" else "Take a photo", Modifier.weight(1f), color = Color.White, textAlign = TextAlign.Center, fontSize = 16.sp)
                if (picture == null && !pfp) TextButton({ front = !front }) { Text("Flip", color = Color.White) }
                else Box(Modifier.size(48.dp))
            }
            problem?.let { Text(it, Modifier.padding(top = 8.dp).background(Color(0xCC000000)).padding(10.dp), color = Color.White, textAlign = TextAlign.Center) }
            if (!granted) Text("Allow the camera to take a photo.", Modifier.padding(top = 80.dp), color = Color.White, textAlign = TextAlign.Center)
            Box(Modifier.weight(1f))
            if (pfp && picture == null) Text("Only your face, looking at the camera. The picture is taken here, not picked from the gallery.", Modifier.padding(bottom = 12.dp), color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp)
            if (picture != null) Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton({ shot = null; problem = null }, enabled = !busy) { Text("Retake", color = Color.White, fontSize = 18.sp) }
                TextButton({ use(picture) }, enabled = !busy) { Text(if (busy) "Checking…" else if (pfp) "Use this" else "Send", color = Color.White, fontSize = 18.sp) }
            } else Box(
                Modifier.padding(bottom = 16.dp).size(78.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).background(Color.White, CircleShape)
                    .clickable(enabled = granted && !busy) { shoot() },
            )
        }
    }
}
