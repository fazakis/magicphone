// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.magicphone.core.Attachment
import dev.magicphone.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

@Composable
fun AttachmentCard(runtime: AppRuntime, file: Attachment) {
    if (file.isImage) { AttachmentPhoto(runtime, file); return }
    var expanded by remember(file.id) { mutableStateOf(false) }
    var page by remember(file.id) { mutableIntStateOf(1) }
    OutlinedCard(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall)
            Text("${(file.size / 1024).coerceAtLeast(1)} KB" + if (file.pages > 0) " · ${file.pages} ${stringResource(R.string.document_pages)}" else "",
                style = MaterialTheme.typography.labelSmall)
            Text(stringResource(if (file.available) R.string.retained_file else R.string.attachment_unavailable),
                style = MaterialTheme.typography.labelSmall)
        }
    }
    if (expanded) {
        val content by produceState<Pair<ToolResult?, Bitmap?>?>(null, file, page) {
            value = null
            value = withContext(Dispatchers.IO) {
                val result = runtime.attachments.preview(file, page, runtime.documents)
                result to result?.image?.let { decodePhoto(it, 1600) }
            }
        }
        AlertDialog(onDismissRequest = { expanded = false }, title = { Text(file.name) },
            confirmButton = { TextButton({ expanded = false }) { Text(stringResource(android.R.string.ok)) } },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    val image = content?.second
                    if (image != null) Image(image.asImageBitmap(), file.name, Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    else Text(if (content == null) stringResource(R.string.attachment_loading)
                        else content?.first?.content?.take(5000) ?: stringResource(R.string.attachment_unavailable),
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()))
                    if (file.pages > 1) Row {
                        TextButton({ page-- }, enabled = page > 1) { Text("‹") }
                        Text("$page / ${file.pages}", Modifier.padding(12.dp))
                        TextButton({ page++ }, enabled = page < file.pages) { Text("›") }
                    }
                }
            })
    }
}

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
