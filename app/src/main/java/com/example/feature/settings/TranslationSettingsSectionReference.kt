package com.example.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.feature.language.translation.TranslationService
import com.example.feature.language.translation.TranslationConfig

/**
 * Preserved Translation settings controls. Engine execution and model operations are unavailable
 * in this build; language preferences remain stored and all controls are visibly disabled.
 */
@Composable
fun TranslationSettingsDisabledSection(
    enabled: Boolean,
    switchEnabled: Boolean = true,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_section_translation"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Translation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Controls the Translate text-selection popup and floating Translation window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = switchEnabled,
                    modifier = Modifier.testTag("translation_master_switch")
                )
            }

            Text(
                text = if (enabled) {
                    "Translation entry points are enabled, but the engine is not included in this build. Requests will report that the engine is unavailable."
                } else {
                    "Translation is off by default. The settings below remain visible but disabled until an engine is restored."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("translation_engine_unavailable_notice")
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(0.42f)
                    .testTag("translation_options_disabled")
            ) {
                TranslationSettingsSectionReference()
            }
        }
    }
}

@Composable
fun TranslationSettingsSectionReference() {
    val context = LocalContext.current
    val translationService = remember(context) { TranslationService.getInstance(context) }
    val translationConfig = remember(translationService) { translationService.getConfig() }
    val downloadedLanguages by translationService.downloadedLanguages.collectAsState()
    val downloadedSorted = remember(downloadedLanguages, translationService) {
        downloadedLanguages.sortedBy { translationService.getLanguageDisplayName(it).lowercase() }
    }
    val translationStatusLabel = when {
        downloadedSorted.isEmpty() -> "Engine unavailable"
        downloadedSorted.size == 1 -> "1 model ready"
        else -> "${downloadedSorted.size} models ready"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_section_translation"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Translation preferences (temporarily disabled)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.testTag("translation_status_badge")
                        ) {
                            Text(
                                text = translationStatusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag("translation_refresh_models_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh downloaded models"
                    )
                }
            }

            Text(
                text = "Source/target preferences are retained. Engine execution, language identification and offline model management are unavailable in this build.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            LanguagePreferenceRow(
                title = "Default Source Language",
                subtitle = "Used by the Translation window and Translate text-selection popup.",
                value = translationService.getLanguageDisplayName(translationConfig.sourceLanguageCode),
                tag = "translation_source_language_row",
                enabled = false,
                onClick = {}
            )

            HorizontalDivider()

            LanguagePreferenceRow(
                title = "Default Target Language",
                subtitle = "Default output language for on-device translations.",
                value = translationService.getLanguageDisplayName(translationConfig.targetLanguageCode),
                tag = "translation_target_language_row",
                enabled = false,
                onClick = {}
            )

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("translation_wifi_only_row"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Wi-Fi-only model downloads",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (translationConfig.wifiOnlyDownloads) {
                            "On (recommended). Language models download only over Wi-Fi."
                        } else {
                            "Off. Mobile data downloads are allowed when downloading models."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = translationConfig.wifiOnlyDownloads,
                    onCheckedChange = {},
                    enabled = false,
                    modifier = Modifier.testTag("translation_wifi_only_switch")
                )
            }

            HorizontalDivider()

            Text(
                text = "Offline language models",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            if (downloadedSorted.isEmpty()) {
                Text(
                    text = "Model status and downloads are disabled because the Translation engine is not included in this build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("translation_models_empty_state")
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("translation_downloaded_models_list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    downloadedSorted.forEachIndexed { idx, lang ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = translationService.getLanguageDisplayName(lang),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${lang.uppercase()} • On-device model installed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.testTag("delete_downloaded_model_${lang}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete ${translationService.getLanguageDisplayName(lang)} model",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (idx < downloadedSorted.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("translation_browse_models_button")
            ) {
                Text("Browse & Download Language Models")
            }
        }
    }
}

@Composable
private fun LanguagePreferenceRow(
    title: String,
    subtitle: String,
    value: String,
    tag: String,
    enabled: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
