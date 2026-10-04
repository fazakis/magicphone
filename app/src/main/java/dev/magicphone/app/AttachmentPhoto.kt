// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.magicphone.core.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

@Composable
fun AttachmentPhoto(runtime: AppRuntime, photo: Attachment) {
    var expanded by remember(photo) { mutableStateOf(false) }
    val preview by produceState<Pair<Boolean, Bitmap?>>(false to null, photo) {
        value = true to withContext(Dispatchers.IO) {
            runtime.attachments.load(photo)?.let { decodePhoto(it, 320) }
        }
    }
    val bitmap = preview.second
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), stringResource(R.string.retained_photo),
            Modifier.sizeIn(maxWidth = 240.dp, maxHeight = 160.dp).clickable { expanded = true })
        Text(stringResource(R.string.retained_photo), style = MaterialTheme.typography.labelSmall)
    } else Text(stringResource(if (preview.first) R.string.attachment_unavailable else R.string.attachment_loading),
        style = MaterialTheme.typography.labelSmall)
    if (expanded) {
        val full by produceState<Bitmap?>(bitmap, photo) {
            value = withContext(Dispatchers.IO) { runtime.attachments.load(photo)?.let { decodePhoto(it, 1600) } }
        }
        AlertDialog(onDismissRequest = { expanded = false },
            confirmButton = { TextButton({ expanded = false }) { Text(stringResource(android.R.string.ok)) } },
            text = { full?.let { Image(it.asImageBitmap(), stringResource(R.string.retained_photo), Modifier.fillMaxWidth()) } })
    }
}

private fun decodePhoto(data: String, edge: Int): Bitmap? = runCatching {
    val bytes = Base64.getDecoder().decode(data.substringAfter(','))
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / edge).coerceAtLeast(1)
    })
}.getOrNull()
