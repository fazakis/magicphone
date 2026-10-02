// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.magicphone.core.*

fun Profile.modelLabel(): String = modelChoice?.takeIf { it.id == model }?.name ?: model

@Composable
fun effortLabel(effort: String?): String = s(when (effort) {
    "none" -> R.string.thinking_none
    "minimal" -> R.string.thinking_minimal
    "low" -> R.string.thinking_low
    "medium" -> R.string.thinking_medium
    "high" -> R.string.thinking_high
    "xhigh" -> R.string.thinking_xhigh
    "max" -> R.string.thinking_max
    "ultra" -> R.string.thinking_ultra
    else -> R.string.model_default
})

@Composable
fun speedLabel(tier: String?): String = s(when (tier) {
    "fast", "priority" -> R.string.speed_fast
    "ultrafast" -> R.string.speed_ultrafast
    "default" -> R.string.speed_standard
    else -> R.string.account_default
})

@Composable
fun ChoiceSelector(label: String, value: String, options: List<Pair<String?, String>>, change: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, enabled = options.size > 1,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
                if (options.size > 1) AppIcon(R.drawable.ic_chevron, modifier = Modifier.size(18.dp).rotate(90f))
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(
                text = { Text(name) },
                onClick = { change(id); expanded = false },
                trailingIcon = { if (name == value) AppIcon(R.drawable.ic_check) },
            ) }
        }
    }
}

@Composable
fun ModelControls(r: AppRuntime, profile: Profile) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val models by r.models.collectAsStateWithLifecycle()
    val choices = remember(models, profile.manualModelOptions, profile.kind) {
        if (profile.manualModelOptions && ModelOptions.canOverride(profile))
            (models + ModelOptions.manualModels).distinctBy { it.id } else models
    }
    val loading by r.modelsLoading.collectAsStateWithLifecycle()
    val error by r.modelsError.collectAsStateWithLifecycle()
    var picker by rememberSaveable(profile.id) { mutableStateOf(false) }
    var manual by rememberSaveable(profile.id) { mutableStateOf(false) }
    var model by rememberSaveable(profile.id, profile.model) { mutableStateOf(profile.model) }
    fun update(p: Profile) {
        r.stop()
        r.saveSettings(r.settings.value.copy(profiles = r.settings.value.profiles.map { if (it.id == p.id) p else it }))
    }
    Surface(onClick = { picker = true; r.discoverModels(refresh = false) },
        color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s(R.string.selected_model), style = MaterialTheme.typography.labelMedium)
                Text(profile.modelLabel().ifBlank { s(R.string.choose_model) }, style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(profile.name, style = MaterialTheme.typography.bodySmall)
            }
            AppIcon(R.drawable.ic_chevron)
        }
    }
    if (ModelOptions.canOverride(profile)) {
        Row(Modifier.fillMaxWidth().toggleable(value = profile.manualModelOptions, role = Role.Switch,
            onValueChange = { update(ModelOptions.withManualOptions(profile, it)) }), verticalAlignment = Alignment.CenterVertically) {
            Text(s(R.string.manual_model_options), Modifier.weight(1f))
            Switch(checked = profile.manualModelOptions, onCheckedChange = null)
        }
        Info(s(if (profile.manualModelOptions) R.string.manual_options_help else R.string.dynamic_options_help))
    }
    if (profile.model.isNotBlank()) {
        val efforts = ModelOptions.reasoningChoices(profile)
        ChoiceSelector(s(R.string.thinking_level), effortLabel(profile.reasoningEffort),
            listOf(null to s(R.string.model_default)) + efforts.map { it to effortLabel(it) }) {
            update(profile.copy(reasoningEffort = it))
        }
        if (efforts.isEmpty()) Info(s(R.string.thinking_unavailable))
        if (profile.kind in setOf(ProviderKind.OPENAI, ProviderKind.CHATGPT)) {
            ChoiceSelector(s(R.string.processing_speed), speedLabel(profile.serviceTier),
                listOf(null to s(R.string.account_default)) + ModelOptions.speedChoices(profile).map { it to speedLabel(it) }) {
                update(profile.copy(serviceTier = it))
            }
            Info(s(R.string.requested_speed_help))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton({ r.discoverModels() }, enabled = !loading) { Text(s(R.string.refresh_models)) }
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        if (profile.manualModelOptions || profile.kind == ProviderKind.COMPATIBLE)
            TextButton({ manual = !manual }) { Text(s(R.string.enter_model)) }
    }
    if (error.isNotEmpty()) Text(s(errorResource(error)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (manual && (profile.manualModelOptions || profile.kind == ProviderKind.COMPATIBLE)) {
        Field(model, R.string.model, { model = it })
        Button({
            val name = model.trim()
            if (name.isNotEmpty()) r.selectModel(profile, choices.find { it.id == name } ?: ModelChoice(name, name))
            manual = false
            focusManager.clearFocus()
            keyboard?.hide()
        }, enabled = model.isNotBlank() && model.trim().length <= 200) { Text(s(R.string.save)) }
    }
    if (picker) {
        var query by rememberSaveable { mutableStateOf("") }
        val filtered = remember(choices, query) { choices.filter { it.name.contains(query, true) || it.id.contains(query, true) } }
        AlertDialog(onDismissRequest = { picker = false }, title = { Text(s(R.string.choose_model)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(query, R.string.search_models, { query = it })
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!loading && filtered.isEmpty()) Info(s(R.string.no_models))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.id }) { choice ->
                            Surface(onClick = { r.selectModel(profile, choice); picker = false },
                                color = if (profile.model == choice.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(choice.name, fontWeight = FontWeight.SemiBold)
                                        Text(choice.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(s(if (models.any { it.id == choice.id }) R.string.from_connection else R.string.manual_model_choice),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (profile.model == choice.id) AppIcon(R.drawable.ic_check)
                                }
                            }
                        }
                    }
                    Info(s(if (profile.manualModelOptions) R.string.manual_catalog_help else R.string.models_account_help))
                }
            }, confirmButton = { TextButton({ picker = false }) { Text(s(R.string.dismiss)) } },
            dismissButton = { TextButton({ r.discoverModels() }, enabled = !loading) { Text(s(R.string.refresh_models)) } },
        )
    }
}

@Composable
fun ModelResponseInfo(info: ModelRunInfo) {
    BoxCard {
        Text(s(R.string.last_model_response), style = MaterialTheme.typography.titleMedium)
        Info(s(R.string.requested_model_options, info.requestedModel,
            effortLabel(info.requestedEffort), speedLabel(info.requestedTier)))
        Text(s(R.string.reported_model, info.reportedModel ?: s(R.string.not_reported)), style = MaterialTheme.typography.bodySmall)
        Text(s(R.string.reported_thinking, info.reportedEffort?.let { effortLabel(it) } ?: s(R.string.not_reported)), style = MaterialTheme.typography.bodySmall)
        val tier = when (val reported = info.reportedTier) {
            null -> s(R.string.not_reported)
            "default", "fast", "priority", "ultrafast" -> speedLabel(info.reportedTier)
            else -> reported
        }
        Text(s(R.string.reported_speed, tier), style = MaterialTheme.typography.bodySmall)
        if (info.requestedTier != null && !info.speedConfirmed) Info(s(R.string.speed_not_confirmed))
    }
}
