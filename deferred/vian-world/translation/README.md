# Vian World Translation integration

Status: integrated into the Vian World app source in the Sidebar mini-app synchronization branch.

## Process boundary

- `TranslationMiniAppInstance`, `TranslationService`, and `TranslateSelectionActivity` run in the app's main process.
- `TranslationSettingsActivity` is intentionally declared without `android:process`, so it also runs in the main process.
- The existing `SettingsActivity` remains in `:heavy`. Legacy Translation settings routes are trampolined to `TranslationSettingsActivity`; do not initialize ML Kit or call `TranslationService` directly from `:heavy`.
- This keeps a single ML Kit native runtime and avoids the `MlKitContext has not been initialized` failure in the secondary process.

## Runtime and storage

- Translation uses Google ML Kit Translate and Language Identification.
- Language models are downloaded on demand; the APK contains the SDKs, not every language model.
- The floating Translation window is a Main-only mini-app. It is included in `FloatingMiniAppBridge.isMainOnlyMiniApp` and `FloatingTriggerStore.MAIN_ONLY_MINI_APP_TYPES`.
- Translation state is saved through `DurableMiniAppStore` and restored by the normal floating mini-app recovery path.

## User entry points

- Add Element exposes `system:translation_floating`.
- Android text selection exposes `TranslateSelectionActivity` through `ACTION_PROCESS_TEXT`.
- The floating Translation top-bar Edit button opens the Main-process Translation Settings activity.

## Verification

The integration tests are `TranslationServiceTest` and `TranslationUiAndSelectionTest`. The existing Dictionary and Reader tests remain in place. The source AI Studio workflow only runs `assembleDebug`; unit-test execution must be verified separately in Vian World CI.
