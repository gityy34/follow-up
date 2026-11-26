package com.followup.tracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun readProfileSource(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    val original = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val ratio = minOf(1.0, 512.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1),
                (info.size.height * ratio).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(requireNotNull(input), null, opts) }
        require(opts.outWidth > 0 && opts.outHeight > 0)
        opts.inSampleSize = Integer.highestOneBit((maxOf(opts.outWidth, opts.outHeight) / 512).coerceAtLeast(1))
        opts.inJustDecodeBounds = false
        resolver.openInputStream(uri).use { input -> requireNotNull(BitmapFactory.decodeStream(requireNotNull(input), null, opts)) }
    }
    val ratio = minOf(1.0, 512.0 / maxOf(original.width, original.height))
    val source = Bitmap.createScaledBitmap(original, (original.width * ratio).toInt().coerceAtLeast(1),
        (original.height * ratio).toInt().coerceAtLeast(1), true)
    return encodePhoto(source, 350_000)
}

private fun encodePhoto(bitmap: Bitmap, limit: Int): String = ByteArrayOutputStream().use {
    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 87, it))
    Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP).also { image -> check(isProfilePhoto(image, limit)) }
}

private fun decodeProfilePhoto(photo: String, maxSize: Int = 256): Bitmap? {
    if (!isProfilePhoto(photo, if (maxSize > 256) 350_000 else 90_000)) return null
    return runCatching {
        val bytes = Base64.decode(photo, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..maxSize || bounds.outHeight !in 1..maxSize) null
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

@Composable
internal fun ProfileAvatar(settings: AppSettings, modifier: Modifier = Modifier.size(40.dp)) {
    val photo = remember(settings.photo) { decodeProfilePhoto(settings.photo)?.asImageBitmap() }
    if (photo != null) {
        Image(photo, "Profile photo", modifier.clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Image(painterResource(R.drawable.ic_launcher), "Default profile icon", modifier.clip(CircleShape),
            contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun ProfilePhotoEditor(settings: AppSettings, close: () -> Unit, apply: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(settings.photoSource.ifBlank { settings.photo }) }
    var editing by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    val sourceBitmap = remember(source) { decodeProfilePhoto(source, 512) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                source = withContext(Dispatchers.IO) { readProfileSource(context, uri) }
                editing = true
                removed = false
                zoom = 1f; x = 0f; y = 0f
                error = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "That image could not be opened. Choose another photo." }
            finally { busy = false }
        }
    }
    val bitmap = sourceBitmap
    val crop = if (editing && bitmap != null) profileCrop(bitmap.width, bitmap.height, zoom, x, y) else null
    val preview = remember(bitmap) { bitmap?.asImageBitmap() }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("Profile photo") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!removed && preview != null && crop != null) {
                    Canvas(Modifier.size(152.dp).clip(CircleShape)) {
                        drawImage(preview, srcOffset = IntOffset(crop.left, crop.top), srcSize = IntSize(crop.side, crop.side),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Text("Zoom"); Slider(zoom, { zoom = it }, valueRange = 1f..3f)
                        Text("Horizontal position"); Slider(x, { x = it }, valueRange = -1f..1f)
                        Text("Vertical position"); Slider(y, { y = it }, valueRange = -1f..1f)
                    }
                } else ProfileAvatar(if (removed) settings.copy(photo = "") else settings, Modifier.size(152.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
                        Text(if (settings.photo.isBlank() && source.isBlank()) "Upload" else "Change")
                    }
                    if (preview != null && !removed) {
                        TextButton(onClick = { editing = true }, enabled = !editing && !busy) { Text("Crop") }
                        TextButton(onClick = { removed = true; editing = false }, enabled = !busy) { Text("Remove") }
                    }
                }
                if (removed) Text("Apply to remove the photo.", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (removed || crop != null), onClick = {
                if (removed) apply("", "")
                else if (bitmap != null && crop != null) scope.launch {
                    busy = true
                    try {
                        val thumbnail = withContext(Dispatchers.Default) {
                            val square = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.side, crop.side)
                            encodePhoto(Bitmap.createScaledBitmap(square, 192, 192, true), 90_000)
                        }
                        apply(thumbnail, source)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "The crop could not be saved." }
                    finally { busy = false }
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Close") } }
    )
}

@Composable
internal fun ProfileDialog(settings: AppSettings, initial: Boolean, onDismiss: () -> Unit,
                           save: (String, String, Boolean) -> Unit) {
    var choice by rememberSaveable { mutableStateOf(if (initial) "" else "existing") }
    var name by rememberSaveable { mutableStateOf(settings.operatorName) }
    var display by rememberSaveable { mutableStateOf(settings.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial) "Set up your profile" else "Edit profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (choice.isEmpty()) {
                    Primary("New profile") { choice = "new"; name = ""; display = "" }
                    OutlinedButton(onClick = {
                        choice = "existing"
                        name = settings.operatorName
                        display = settings.displayName
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use existing profile") }
                } else {
                    Field("Your name", name, { name = it.take(60) })
                    Field("Dashboard display title (optional)", display, { display = it.take(60) })
                }
            }
        },
        confirmButton = { if (choice.isNotEmpty()) TextButton(onClick = {
            save(name.trim(), display.trim(), choice == "new")
        }, enabled = !initial || name.isNotBlank()) { Text(if (initial && choice == "existing") "Confirm profile" else "Save") } },
        dismissButton = {
            if (initial && choice.isNotEmpty()) TextButton(onClick = { choice = "" }) { Text("Back") }
            else TextButton(onClick = onDismiss) { Text(if (initial) "Not now" else "Cancel") }
        }
    )
}
