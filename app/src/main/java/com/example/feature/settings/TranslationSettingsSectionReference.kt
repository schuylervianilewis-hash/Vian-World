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

/**
 * Preserved Compose layout for language preferences and offline-model management. Its interactive
 * controls are disabled by design; this function never initiates model refresh/download/delete.
 */
@Composable
fun TranslationSettingsSectionReference() {
@Composable
fun TranslationSettingsSectionReference() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val translationService = remember(context) { TranslationService.getInstance(context) }

    var translationConfig by remember { mutableStateOf(TranslationConfig()) }
    val downloadedLanguages by translationService.downloadedLanguages.collectAsState()
    val modelStates by translationService.modelStates.collectAsState()
    var translationFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isRefreshingModels by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var showModelsBrowser by remember { mutableStateOf(false) }
    var modelPendingDelete by remember { mutableStateOf<LanguageOption?>(null) }

    val sourceLanguageOptions = remember(translationService) {
        translationService.getSupportedSourceLanguages()
    }
    val targetLanguageOptions = remember(translationService) {
        translationService.getSupportedTargetLanguages()
    }

    fun refreshTranslationModels() {
        if (isRefreshingModels) return
        coroutineScope.launch {
            isRefreshingModels = true
            val res = withContext(Dispatchers.IO) {
                translationService.refreshDownloadedModels()
            }
            if (res.isFailure) {
                translationFeedback = false to (
                    res.exceptionOrNull()?.message
                        ?: "Could not query downloaded ML Kit language models."
                    )
            }
            isRefreshingModels = false
        }
    }

    LaunchedEffect(Unit) {
        translationConfig = withContext(Dispatchers.IO) {
            translationService.getConfig()
        }
    }

    val downloadedSorted = remember(downloadedLanguages, translationService) {
        downloadedLanguages
            .map { code -> LanguageOption(code, translationService.getLanguageDisplayName(code)) }
            .sortedBy { it.displayName.lowercase() }
    }
    val translationStatusLabel = when {
        downloadedSorted.isEmpty() -> "Engine unavailable"
        downloadedSorted.size == 1 -> "1 model ready"
        else -> "${downloadedSorted.size} models ready"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("translation_settings_disabled_controls"),
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

            translationFeedback?.let { (success, message) ->
                Surface(
                    color = if (success) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("translation_action_feedback")
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (success) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            val activeDownloadingCodes = modelStates.filterValues { it is ModelDownloadState.Downloading }.keys
            if (activeDownloadingCodes.isNotEmpty()) {
                val names = activeDownloadingCodes.joinToString(", ") {
                    translationService.getLanguageDisplayName(it)
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Downloading model(s): $names...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
                                    text = lang.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${lang.code.uppercase()} • On-device model installed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.testTag("delete_downloaded_model_${lang.code}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete ${lang.displayName} model",
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
                Text("Browse & Download Language Models (${targetLanguageOptions.size})")
            }
        }
    }

    if (showSourcePicker) {
        LanguageSelectionDialog(
            title = "Default Source Language",
            options = sourceLanguageOptions,
            selectedCode = translationConfig.sourceLanguageCode,
            downloadedCodes = downloadedLanguages,
            onSelect = { code ->
                showSourcePicker = false
                coroutineScope.launch {
                    translationConfig = withContext(Dispatchers.IO) {
                        translationService.updateConfig(sourceLanguageCode = code)
                    }
                }
            },
            onDismiss = { showSourcePicker = false }
        )
    }

    if (showTargetPicker) {
        LanguageSelectionDialog(
            title = "Default Target Language",
            options = targetLanguageOptions,
            selectedCode = translationConfig.targetLanguageCode,
            downloadedCodes = downloadedLanguages,
            onSelect = { code ->
                showTargetPicker = false
                coroutineScope.launch {
                    translationConfig = withContext(Dispatchers.IO) {
                        translationService.updateConfig(targetLanguageCode = code)
                    }
                }
            },
            onDismiss = { showTargetPicker = false }
        )
    }

    if (showModelsBrowser) {
        TranslationModelsBrowserDialog(
            languages = targetLanguageOptions,
            downloadedCodes = downloadedLanguages,
            modelStates = modelStates,
            wifiOnlyDownloads = translationConfig.wifiOnlyDownloads,
            onDownload = { code ->
                translationFeedback = null
                coroutineScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        translationService.downloadModel(code)
                    }
                    val langName = translationService.getLanguageDisplayName(code)
                    translationFeedback = if (res.isSuccess) {
                        true to "Downloaded $langName offline model."
                    } else {
                        false to (res.exceptionOrNull()?.message ?: "Failed to download $langName model.")
                    }
                }
            },
            onRequestDelete = { lang -> modelPendingDelete = lang },
            onDismiss = { showModelsBrowser = false }
        )
    }

    modelPendingDelete?.let { lang ->
        ConfirmDeleteDialog(
            title = "Delete Language Model?",
            message = "Remove the offline ${lang.displayName} (${lang.code.uppercase()}) ML Kit model from this device? You can re-download it later.",
            onConfirm = {
                modelPendingDelete = null
                coroutineScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        translationService.deleteModel(lang.code)
                    }
                    translationFeedback = if (res.isSuccess) {
                        true to "Deleted ${lang.displayName} offline model."
                    } else {
                        false to (res.exceptionOrNull()?.message ?: "Failed to delete ${lang.displayName} model.")
                    }
                }
            },
            onDismiss = { modelPendingDelete = null }
        )
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
