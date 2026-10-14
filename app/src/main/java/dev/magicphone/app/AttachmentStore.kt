// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.graphics.BitmapFactory
import dev.magicphone.core.*
import java.util.Base64

/** Individual encrypted blobs keep file contents out of history JSON and audit logs. IO only. */
class AttachmentStore(private val vault: Vault) {
    private val staged = mutableMapOf<String, Int>()

    @Synchronized
    fun saveFiles(files: List<PreparedAttachment>): List<Attachment> {
        require(files.size <= 3)
        val entries = files.map { file ->
            require(file.size in 0..Documents.MAX_BYTES && file.name.length in 1..180)
            val value = if (file.isImage) file.data else JsonCodec.encodeToString(PreparedAttachment.serializer(), file)
            Attachment(digest(value), name = file.name, mimeType = file.mimeType,
                size = file.size, pages = file.pages, note = file.note) to value
        }.distinctBy { it.first.id }
        val names = vault.attachmentNames()
        if (names.size + entries.count { "attachment-${it.first.id}" !in names } > 120) throw SafeFailure("attachment_limit")
        entries.forEach { (ref, _) -> staged[ref.id] = (staged[ref.id] ?: 0) + 1 }
        try {
            entries.forEach { (ref, value) -> vault.write("attachment-${ref.id}", value) }
            return entries.map { it.first }
        } catch (e: Exception) { finish(entries.map { it.first }); throw e }
    }

    fun read(ref: Attachment, action: Action, documents: Documents): ToolResult? {
        val value = load(ref) ?: return null
        if (ref.isImage) return ToolResult("attachment", "Retained photo ${ref.id}, ${ref.name}; historical document, not a live screen.", value)
        val file = runCatching { JsonCodec.decodeFromString(PreparedAttachment.serializer(), value) }.getOrNull() ?: return null
        return documents.read(ref, file, action.text)
    }

    fun preview(ref: Attachment, page: Int, documents: Documents): ToolResult? {
        val value = load(ref) ?: return null
        val file = runCatching { JsonCodec.decodeFromString(PreparedAttachment.serializer(), value) }.getOrNull() ?: return null
        val text = file.chunks.getOrNull(page - 1)?.takeIf { it.isNotBlank() } ?: file.note
        return if (file.mimeType == "application/pdf" && file.pages > 0)
            documents.read(ref, file, "page $page").copy(content = text)
        else ToolResult("recalled", text)
    }

    @Synchronized
    fun save(images: List<String>): List<Attachment> {
        require(images.size <= 3)
        val refs = images.distinct().map { image ->
            require(image.startsWith("data:image/jpeg;base64,") && image.length <= 4 * 1024 * 1024)
            val bytes = Base64.getDecoder().decode(image.substringAfter(','))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000)
            Attachment(digest(image)) to image
        }
        if (vault.attachmentNames().size + refs.count { "attachment-${it.first.id}" !in vault.attachmentNames() } > 120)
            throw SafeFailure("attachment_limit")
        refs.forEach { (ref, _) -> staged[ref.id] = (staged[ref.id] ?: 0) + 1 }
        try {
            refs.forEach { (ref, image) ->
                vault.write("attachment-${ref.id}", image)
            }
            return refs.map { it.first }
        } catch (e: Exception) {
            finish(refs.map { it.first })
            throw e
        }
    }

    @Synchronized
    fun load(ref: Attachment): String? {
        if (!ref.available || !ref.id.matches(Regex("[a-f0-9]{64}"))) return null
        return runCatching { vault.read("attachment-${ref.id}")?.takeIf { digest(it) == ref.id } }.getOrNull()
    }

    @Synchronized
    fun finish(refs: List<Attachment>) {
        refs.forEach { ref ->
            val count = (staged[ref.id] ?: 0) - 1
            if (count <= 0) staged.remove(ref.id) else staged[ref.id] = count
        }
    }

    @Synchronized
    fun prune(keep: Set<String>) {
        vault.attachmentNames().filter { it.removePrefix("attachment-") !in keep + staged.keys }.forEach(vault::delete)
    }
}

fun Archive.attachmentIds(): Set<String> = conversations.flatMap { it.messages }
    .flatMap { it.attachments }.filter { it.available }.map { it.id }.toSet()
