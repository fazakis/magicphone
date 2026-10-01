// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault(context: Context) {
    private val root = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val alias = "${context.packageName}.vault.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    private fun file(name: String): AtomicFile {
        require(name.matches(Regex("[a-z0-9_-]{1,80}")))
        return AtomicFile(File(root, "$name.enc"))
    }

    @Synchronized
    fun read(name: String): String? {
        val f = file(name)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return null
        val b = f.readFully()
        require(b.size in 29..(5 * 1024 * 1024) && b[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(1, 13)))
        cipher.updateAAD(name.toByteArray())
        return cipher.doFinal(b.copyOfRange(13, b.size)).decodeToString()
    }

    @Synchronized
    fun write(name: String, value: String) {
        require(value.toByteArray().size <= 4 * 1024 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val b = byteArrayOf(1) + cipher.iv + cipher.doFinal(value.toByteArray())
        val f = file(name)
        val out = f.startWrite()
        try {
            out.write(b)
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    @Synchronized
    fun delete(name: String) {
        file(name).delete()
    }
}
