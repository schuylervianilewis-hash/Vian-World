package com.example.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only Translation settings preserved at the bottom of the unified Dictionary & Translation
 * page. The master switch controls entry-point availability; engine-specific controls stay visibly
 * disabled while ML Kit is excluded from the APK.
 */
@Composable
fun TranslationSettingsDisabledSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val translationService = remember(context) { TranslationService.getInstance(context) }
    var config by remember { mutableStateOf(translationService.getConfig()) }

    LaunchedEffect(Unit) {
        config = withContext(Dispatchers.IO) { translationService.getConfig() }
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
                    modifier = Modifier.testTag("translation_master_switch")
                )
            }

            Text(
                text = if (enabled) {
                    "Translation entry points are enabled, but the engine is not included in this build. Requests will report that the engine is unavailable."
                } else {
                    "Translation is off by default. You can enable its entry points, but actual translation remains unavailable until an engine is restored."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("translation_engine_unavailable_notice")
            )

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(0.42f)
                    .testTag("translation_options_disabled"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Translation preferences — temporarily disabled",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                DisabledTranslationPreference(
                    title = "Default Source Language",
                    value = translationService.getLanguageDisplayName(config.sourceLanguageCode)
                )
                HorizontalDivider()
                DisabledTranslationPreference(
                    title = "Default Target Language",
                    value = translationService.getLanguageDisplayName(config.targetLanguageCode)
                )
                HorizontalDivider()
                DisabledTranslationPreference(
                    title = "Wi-Fi-only model downloads",
                    value = if (config.wifiOnlyDownloads) "On" else "Off"
                )
                HorizontalDivider()
                DisabledTranslationPreference(
                    title = "Offline language models",
                    value = "Model downloads and management are not included in this build"
                )
            }
        }
    }
}

@Composable
private fun DisabledTranslationPreference(
    title: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
