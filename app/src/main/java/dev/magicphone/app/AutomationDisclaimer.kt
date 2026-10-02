// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource as s

@Composable
fun AutomationDisclaimer() {
    val context = LocalContext.current
    Column {
        Text(s(R.string.automation_disclaimer_title), style = MaterialTheme.typography.titleMedium)
        Info(s(R.string.automation_disclaimer_body))
        TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://magicphone.org/disclaimer.html"))) }) {
            Text(s(R.string.read_disclaimer))
        }
    }
}
