// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object Sanitizer {
    private val patterns =
        listOf(
            Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*"),
            Regex("\\bsk-[A-Za-z0-9_-]{8,}"),
            Regex(
                "(?i)(password|passwd|otp|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|κωδικός)\\s*[:=]\\s*[^\\s,;]+"
            ),
            Regex("\\b[0-9]{13,19}\\b"),
            Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
        )

    fun text(input: String): String =
        patterns.fold(input.take(32_768)) { s, r -> r.replace(s, "[redacted]") }

    // Conversation documents may contain long receipt/customer references. Keep those exact;
    // explicit credentials and labelled card numbers still do not belong in retained prose.
    fun conversation(input: String): String {
        val safe = patterns.filterIndexed { index, _ -> index != 3 }
            .fold(input.take(32_768)) { s, r -> r.replace(s, "[redacted]") }
        return Regex("(?i)(card(?: number)?|credit card|debit card|αριθμός κάρτας)\\s*[:=]\\s*[0-9 -]{13,25}")
            .replace(safe, "$1: [redacted]")
    }

    fun event(action: Action, status: String) =
        Audit(
            id(),
            action.op.name,
            if (action.isDevice) action.app.take(200) else "",
            status.take(40),
            System.currentTimeMillis(),
        )
}

@Serializable
data class Message(
    val id: String = id(),
    val role: String,
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class Attachment(val id: String, val available: Boolean = true)

@Serializable
data class Audit(
    val id: String,
    val operation: String,
    val app: String,
    val status: String,
    val time: Long,
)

@Serializable
data class Conversation(
    val id: String = id(),
    val title: String,
    val messages: List<Message> = emptyList(),
    val parent: String? = null,
    val state: RunState = RunState.IDLE,
    val updated: Long = System.currentTimeMillis(),
)

@Serializable
data class Knowledge(
    val id: String = id(),
    val app: String,
    val title: String,
    val content: String,
    val kind: String = "memory",
    val reviewed: Boolean = false,
)

@Serializable
data class Archive(
    val schema: Int = 3,
    val conversations: List<Conversation> = emptyList(),
    val audits: List<Audit> = emptyList(),
    val knowledge: List<Knowledge> = emptyList(),
    val scripts: List<Script> = emptyList(),
)

object Archives {
    const val MAX = 4 * 1024 * 1024

    private fun identifier(value: String) = value.matches(Regex("[a-zA-Z0-9-]{1,80}"))

    fun read(bytes: ByteArray): Archive {
        require(bytes.size <= MAX)
        val raw = JsonCodec.parseToJsonElement(bytes.decodeToString()).jsonObject
        val version = raw.int("schema")
        require(version in 1..3)
        val archive =
            JsonCodec.decodeFromJsonElement(
                Archive.serializer(),
                kotlinx.serialization.json.JsonObject(
                    raw.toMutableMap().apply {
                        put("schema", kotlinx.serialization.json.JsonPrimitive(3))
                    }
                ),
            )
        require(
            archive.conversations.size <= 500 &&
                archive.audits.size <= 5000 &&
                archive.knowledge.size <= 500 &&
                archive.scripts.size <= 100
        )
        require(archive.conversations.map { it.id }.distinct().size == archive.conversations.size)
        archive.conversations.forEach { c ->
            require(identifier(c.id) && c.messages.size <= 1000 && c.title.length <= 300)
            c.parent?.let { require(identifier(it)) }
            c.messages.forEach {
                require(
                    identifier(it.id) &&
                        it.role in setOf("user", "assistant", "system") &&
                        it.text.length <= 32768
                )
                require(it.attachments.size <= 3 && it.attachments.map { a -> a.id }.distinct().size == it.attachments.size)
                require(it.attachments.all { a -> a.id.matches(Regex("[a-f0-9]{64}")) })
            }
            require(c.messages.flatMap { it.attachments }.distinctBy { it.id }.size <= 60)
        }
        archive.audits.forEach {
            require(
                identifier(it.id) &&
                    it.operation in Op.entries.map { op -> op.name } &&
                    it.app.matches(Regex("[a-zA-Z0-9_.]{0,200}")) &&
                    it.status in
                        setOf(
                            "dispatching",
                            "dispatched",
                            "observed",
                            "captured",
                            "condition_observed",
                            "failed",
                            "uncertain",
                            "waited",
                            "updated",
                            "waiting_user",
                            "reported",
                            "pending_local_review",
                            "apps",
                            "catalog",
                            "remote_result",
                            "recalled",
                            "attachment",
                        )
            )
        }
        archive.knowledge.forEach {
            require(identifier(it.id) && it.content.length <= 16000 && it.app.length <= 200)
        }
        archive.scripts.forEach {
            require(identifier(it.id))
            ScriptRunner.validate(it)
        }
        return clean(archive)
    }

    fun clean(a: Archive): Archive =
        a.copy(
            scripts =
                a.scripts.map { script ->
                    script.copy(
                        name = Sanitizer.text(script.name),
                        steps =
                            script.steps.map { step ->
                                step.copy(
                                    action =
                                        step.action.copy(
                                            text = Sanitizer.text(step.action.text),
                                            arguments =
                                                kotlinx.serialization.json.JsonObject(emptyMap()),
                                        )
                                )
                            },
                    )
                },
            conversations =
                a.conversations.map { c ->
                    c.copy(
                        title = Sanitizer.text(c.title),
                        messages = c.messages.map { it.copy(text = Sanitizer.conversation(it.text)) },
                    )
                },
            knowledge = a.knowledge.map { it.copy(content = Sanitizer.text(it.content)) },
            audits =
                a.audits.map {
                    it.copy(
                        operation = it.operation.take(40),
                        app = it.app.take(200),
                        status = it.status.take(40),
                    )
                },
        )

    fun previewImport(bytes: ByteArray) =
        read(bytes).let { a ->
            a.copy(
                audits = emptyList(),
                // Backups contain references, not photo bytes. Imported references may not open
                // an unrelated local photo even if an attacker guesses its content hash.
                conversations = a.conversations.map { c -> c.copy(state = RunState.INTERRUPTED,
                    messages = c.messages.map { m -> m.copy(attachments = m.attachments.map { it.copy(available = false) }) }) },
                knowledge = a.knowledge.map { it.copy(reviewed = false) },
                scripts = a.scripts.map { it.copy(enabled = false) },
            )
        }

    fun interrupted(a: Archive) =
        a.copy(
            conversations =
                a.conversations.map {
                    if (
                        it.state in
                            setOf(
                                RunState.ACTING,
                                RunState.PLANNING,
                                RunState.WAITING_APPROVAL,
                                RunState.WAITING_USER,
                                RunState.PAUSED,
                            )
                    )
                        it.copy(state = RunState.INTERRUPTED)
                    else it
                }
        )
}

/** Standard provider cryptography, versioned authenticated envelope. No ZIP or filesystem paths. */
object BackupCrypto {
    private val magic = "MAGICPHONE-BACKUP-1\n".toByteArray()

    private fun key(pass: CharArray, salt: ByteArray): ByteArray {
        require(pass.size in 12..1024)
        val spec = PBEKeySpec(pass, salt, 600_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(archive: Archive, pass: CharArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val material = key(pass, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(material, "AES"),
                GCMParameterSpec(128, iv),
            )
            cipher.updateAAD(magic)
            magic +
                salt +
                iv +
                cipher.doFinal(
                    JsonCodec.encodeToString(Archive.serializer(), Archives.clean(archive))
                        .toByteArray()
                )
        } finally {
            material.fill(0)
            pass.fill('\u0000')
        }
    }

    fun decrypt(bytes: ByteArray, pass: CharArray): Archive {
        require(bytes.size in (magic.size + 44)..(Archives.MAX + 100))
        require(bytes.take(magic.size).toByteArray().contentEquals(magic))
        val n = magic.size
        val material = key(pass, bytes.copyOfRange(n, n + 16))
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(material, "AES"),
                GCMParameterSpec(128, bytes.copyOfRange(n + 16, n + 28)),
            )
            cipher.updateAAD(magic)
            Archives.previewImport(cipher.doFinal(bytes.copyOfRange(n + 28, bytes.size)))
        } finally {
            material.fill(0)
            pass.fill('\u0000')
        }
    }
}
