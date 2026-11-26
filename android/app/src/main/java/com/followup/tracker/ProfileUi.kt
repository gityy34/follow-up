package com.followup.tracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

internal fun readProfilePhoto(context: Context, uri: Uri): String {
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
    val side = minOf(original.width, original.height)
    val square = Bitmap.createBitmap(original, (original.width - side) / 2, (original.height - side) / 2, side, side)
    val thumb = Bitmap.createScaledBitmap(square, 192, 192, true)
    return ByteArrayOutputStream().use {
        check(thumb.compress(Bitmap.CompressFormat.JPEG, 87, it))
        Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP).also { image -> check(isProfilePhoto(image)) }
    }
}

private fun decodeProfilePhoto(photo: String): Bitmap? {
    if (!isProfilePhoto(photo)) return null
    return runCatching {
        val bytes = Base64.decode(photo, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..256 || bounds.outHeight !in 1..256) null
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

@Composable
internal fun ProfileAvatar(settings: AppSettings, modifier: Modifier = Modifier.size(40.dp)) {
    val photo = remember(settings.photo) { decodeProfilePhoto(settings.photo)?.asImageBitmap() }
    if (photo != null) {
        Image(photo, "Profile photo", modifier.clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        val letters = initials(settings.operatorName.ifBlank { settings.displayName }).ifBlank { "UY" }
        Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Box(contentAlignment = Alignment.Center) { Text(letters, fontWeight = FontWeight.Bold) }
        }
    }
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
