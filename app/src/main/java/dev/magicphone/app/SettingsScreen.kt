// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.magicphone.core.*
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPage(r: AppRuntime) {
    val settings by r.settings.collectAsStateWithLifecycle()
    val connected by r.connected.collectAsStateWithLifecycle()
    val busy by r.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var section by rememberSaveable { mutableIntStateOf(0) }
    var addConnection by rememberSaveable { mutableStateOf(false) }
    var accounts by rememberSaveable { mutableStateOf(false) }
    var appQuery by rememberSaveable { mutableStateOf("") }
    val apps by produceState<List<Pair<String, String>>>(emptyList(), section) {
        if (section == 2) value = withContext(Dispatchers.IO) { r.launchable() }
    }
    val filteredApps = remember(apps, appQuery) { apps.filter { it.first != context.packageName &&
        (it.second.contains(appQuery, true) || it.first.contains(appQuery, true)) } }
    val active = settings.profiles.find { it.id == settings.selected }
    fun change(value: Settings) { r.stop(); r.saveSettings(value) }
    LaunchedEffect(settings.selected, settings.activeAccount, section) {
        if (section == 0 && active?.kind == ProviderKind.CHATGPT) r.discoverModels(refresh = false)
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item(key = "settings-heading") {
            Label(s(R.string.settings_title))
            Info(s(R.string.settings_subtitle))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                listOf(R.string.models_section, R.string.access_section, R.string.apps_section, R.string.more_section).forEachIndexed { i, title ->
                    FilterChip(section == i, { section = i }, label = { Text(s(title)) })
                }
            }
        }
        when (section) {
            0 -> {
                item(key = "active-model") {
                    BoxCard {
                        if (active != null && active.kind != ProviderKind.MOCK) ModelControls(r, active)
                        else {
                            Text(s(R.string.providers), style = MaterialTheme.typography.titleMedium)
                            Info(s(R.string.signin_note))
                        }
                        if (active == null || active.kind != ProviderKind.CHATGPT || settings.activeAccount.isEmpty()) {
                            Button({ r.signIn({ url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) }, enabled = !busy) {
                                Text(s(if (busy) R.string.busy else R.string.continue_chatgpt))
                            }
                        }
                    }
                }
                if (settings.profiles.isNotEmpty()) item(key = "connections") {
                    BoxCard {
                        Text(s(R.string.connections), style = MaterialTheme.typography.titleMedium)
                        settings.profiles.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(settings.selected == p.id, { change(settings.copy(selected = p.id)) })
                                Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.Medium); Info(p.modelLabel().ifBlank { s(R.string.choose_model) }) }
                            }
                        }
                        TextButton({ accounts = !accounts }) { Text(s(R.string.manage_connections)) }
                        if (accounts) {
                            settings.accounts.forEach { a ->
                                Info(a.email)
                                FlowRow {
                                    TextButton({ change(settings.copy(activeAccount = a.client, selected = "chatgpt")) }) { Text(s(R.string.select)) }
                                    TextButton({ r.signIn({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }, a.client) }) { Text(s(R.string.continue_chatgpt)) }
                                    TextButton({ r.logout(a.client) }) { Text(s(R.string.signout)) }
                                }
                            }
                            settings.profiles.forEach { p ->
                                Text(p.name, fontWeight = FontWeight.SemiBold)
                                FlowRow {
                                    TextButton({ change(settings.copy(secondary = p.id)) }) { Text(s(R.string.secondary)) }
                                    TextButton({
                                        r.stop(); r.vault.delete("secret-${p.id}")
                                        change(settings.copy(profiles = settings.profiles.filterNot { it.id == p.id },
                                            selected = settings.selected.takeUnless { it == p.id }.orEmpty(),
                                            secondary = settings.secondary.takeUnless { it == p.id }.orEmpty()))
                                    }) { Text(s(R.string.delete)) }
                                }
                            }
                        }
                    }
                }
                item(key = "add-connection") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton({ addConnection = !addConnection }, Modifier.fillMaxWidth()) {
                            AppIcon(R.drawable.ic_plus); Spacer(Modifier.width(8.dp)); Text(s(R.string.add_connection))
                        }
                        if (addConnection) ProviderEditor(r)
                        BoxCard {
                            Text(s(R.string.practice), style = MaterialTheme.typography.titleMedium)
                            Info(s(R.string.practice_help))
                            TextButton({ r.configurePractice() }) { Text(s(R.string.practice)) }
                        }
                    }
                }
            }
            1 -> item(key = "phone-access") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BoxCard {
                        Row(Modifier.fillMaxWidth().toggleable(value = settings.showTaskResultBubbles, role = Role.Switch,
                            onValueChange = { r.saveSettings(settings.copy(showTaskResultBubbles = it)) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(s(R.string.task_result_bubbles), Modifier.weight(1f))
                            Switch(settings.showTaskResultBubbles, onCheckedChange = null)
                        }
                        Info(s(R.string.task_result_bubbles_help))
                    }
                    BoxCard {
                        Row(Modifier.fillMaxWidth().toggleable(value = settings.policy.checkSensitiveContent, role = Role.Switch,
                            onValueChange = r::setSensitiveContentChecks), verticalAlignment = Alignment.CenterVertically) {
                            Text(s(R.string.sensitive_checks), Modifier.weight(1f))
                            Switch(settings.policy.checkSensitiveContent, onCheckedChange = null)
                        }
                        Info(s(R.string.sensitive_checks_help))
                    }
                    BoxCard {
                        Text(s(R.string.all_apps_title), style = MaterialTheme.typography.titleMedium)
                        Info(s(R.string.all_apps_help))
                        AutomationDisclaimer()
                        Button({ r.setAllowAllApps(!settings.policy.allowAllApps) }) {
                            Text(s(if (settings.policy.allowAllApps) R.string.all_apps_disable else R.string.all_apps_enable))
                        }
                    }
                    BoxCard {
                        Text(s(if (connected) R.string.connected else R.string.disconnected), style = MaterialTheme.typography.titleMedium)
                        Info(s(R.string.permission_help))
                        OutlinedButton({ context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text(s(R.string.accessibility)) }
                        NotificationAccess(r, showHelp = true)
                    }
                    BoxCard { Text(s(R.string.voice_input), style = MaterialTheme.typography.titleMedium); Info(s(R.string.voice_help)) }
                }
            }
            2 -> {
                item(key = "app-search") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Info(s(R.string.app_access_help))
                        Field(appQuery, R.string.search_apps, { appQuery = it })
                        TextButton({ change(settings.copy(policy = settings.policy.copy(grants = emptyList(), allowAllApps = false))) }) { Text(s(R.string.revoke)) }
                    }
                }
                items(filteredApps, key = { it.first }) { (pkg, name) ->
                    val rule = settings.policy.apps[pkg] ?: AppRule()
                    fun setRule(value: AppRule) = change(settings.copy(policy = settings.policy.copy(apps = settings.policy.apps + (pkg to value))))
                    BoxCard {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        if (settings.policy.allowAllApps) Info(s(R.string.all_apps_rule_help)) else {
                            Toggle(s(R.string.observe), rule.observe) { setRule(rule.copy(observe = it)) }
                            Toggle(s(R.string.mutate), rule.mutate) { setRule(rule.copy(mutate = it)) }
                        }
                        Toggle(s(R.string.deny), rule.deny) { setRule(rule.copy(deny = it)) }
                        if (!settings.policy.allowAllApps && rule.observe && rule.mutate && !rule.deny) {
                            Info(s(R.string.grant_help))
                            TextButton({ change(settings.copy(policy = settings.policy.copy(grants = settings.policy.grants +
                                Grant(pkg, setOf(Op.TAP, Op.SCROLL, Op.OPEN), System.currentTimeMillis() + 300000)))) }) { Text(s(R.string.grant)) }
                        }
                    }
                }
            }
            3 -> item(key = "extensions-data") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    McpPage(r)
                    DataPage(r)
                    AutomationDisclaimer()
                    Info("MagicPhone ${BuildConfig.VERSION_NAME} · MIT / Apache-2.0")
                }
            }
        }
    }
}
