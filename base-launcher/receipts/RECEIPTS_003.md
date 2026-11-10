# Receipts Ledger (RECEIPTS_003.md)

## Entry 036
- **Timestamp**: 2026-11-09T00:43:00-08:00
- **Summary**: Implemented lock screen and app drawer wallpaper customization (default pure black), fluid parallax swipe-up transition, and curated Material symbols and glyphs custom icon picker.
- **Exact files touched**:
  - `/app/src/main/AndroidManifest.xml`
  - `/app/src/main/res/values/strings.xml`
  - `/app/src/main/kotlin/shura/home/helpers/Constants.kt`
  - `/app/src/main/kotlin/shura/home/helpers/Config.kt`
  - `/app/src/main/kotlin/shura/home/helpers/WallpaperHelper.kt`
  - `/app/src/main/kotlin/shura/home/extensions/View.kt`
  - `/app/src/main/res/layout/activity_settings.xml`
  - `/app/src/main/kotlin/shura/home/activities/SettingsActivity.kt`
  - `/app/src/main/kotlin/shura/home/activities/MainActivity.kt`
  - `/app/src/main/res/layout/dialog_pick_symbol.xml`
  - `/app/src/main/res/layout/item_symbol_glyph.xml`
  - `/app/src/main/kotlin/shura/home/dialogs/PickSymbolDialog.kt`
  - `/app/src/main/kotlin/shura/home/dialogs/EditItemDialog.kt`
  - `/BLUEPRINT.md`
  - `/receipts/RECEIPTS_002.md`
  - `/receipts/RECEIPTS_003.md`
- **What was actually done**:
  - `AndroidManifest.xml`: Declared `android.permission.SET_WALLPAPER`.
  - `WallpaperHelper.kt`: Created helper to apply system wallpaper via `WallpaperManager` targeting Home Screen, Lock Screen, Both, or App Drawer Background. Zero background resource consumption after setting.
  - `View.kt`: Updated `setupDrawerBackground()` to check `config.drawerUseCustomWallpaper` and load the downsampled drawer wallpaper with a subtle 30% legibility scrim (`#4D000000`). Default remains pure black (`Color.BLACK`).
  - `activity_settings.xml` & `SettingsActivity.kt`: Added *Set wallpaper* action in Appearance section and *App drawer background* setting in App Drawer section.
  - `MainActivity.kt`: Upgraded `showFragment` and `hideFragment` with Fast-Out-Slow-In `PathInterpolator(0.2f, 0f, 0f, 1f)`. Added live gesture drag parallax scaling to `homeScreenGrid` in `ACTION_MOVE` (`scale = 1f - progress * 0.06f`, `alpha = 1f - progress * 1.05f`), cleanly resetting to 1.0f on close. Wired home screen long-press *Wallpapers* action to the target wallpaper dialog.
  - `PickSymbolDialog.kt` & `EditItemDialog.kt`: Implemented in-app symbol & glyph picker offering 36 curated Material vector glyphs. Renders chosen glyphs onto a crisp squircle icon plate tinted in the user's dynamic primary color. Added "Choose from symbols & glyphs" in `EditItemDialog`.
  - Updated `BLUEPRINT.md` and initialized `receipts/RECEIPTS_003.md`.
- **How it was verified**: Local compilation via `compile_applet` (build succeeded). Purged ephemeral keystores per Credential Immunity Rule.
- **Any deviation from what was requested, and why**: None. Built all requested features.
- **Any known issue or follow-up needed**: Run on-device QA.

## Entry 037
- **Timestamp**: 2026-11-10T00:10:00-08:00
- **Summary**: Implemented custom symbol style selector (bubble vs. only symbol), interactive wallpaper framing & pinch-crop tool, theme palette preview capsule, and reset lock screen wallpaper action.
- **Exact files touched**:
  - `/app/src/main/res/values/strings.xml`
  - `/app/src/main/kotlin/shura/home/dialogs/PickSymbolDialog.kt`
  - `/app/src/main/res/drawable/shape_search_bar.xml`
  - `/app/src/main/res/layout/dialog_wallpaper_crop.xml`
  - `/app/src/main/kotlin/shura/home/views/WallpaperCropView.kt`
  - `/app/src/main/kotlin/shura/home/dialogs/WallpaperCropDialog.kt`
  - `/app/src/main/kotlin/shura/home/helpers/WallpaperHelper.kt`
  - `/app/src/main/res/layout/activity_settings.xml`
  - `/app/src/main/kotlin/shura/home/activities/SettingsActivity.kt`
  - `/BLUEPRINT.md`
  - `/receipts/RECEIPTS_003.md`
- **What was actually done**:
  - `PickSymbolDialog.kt`: Added style prompt upon selecting a symbol: "With background bubble" renders on squircle plate; "Only symbol (transparent background)" renders prominent vector glyph directly on transparent bitmap.
  - `WallpaperCropView.kt` & `WallpaperCropDialog.kt`: Created interactive framing & crop dialog with touch drag, pinch-to-zoom, screen-aspect framing border, and real-time palette preview dots extracted from the image via `WallpaperColors.fromBitmap()`. Eliminates wallpaper portions getting pushed off-screen.
  - `WallpaperHelper.kt`: Connected cropped bitmap processing via `setBitmap` and added `resetLockScreenWallpaper` to clear `FLAG_LOCK`.
  - `SettingsActivity.kt` & `activity_settings.xml`: Added Theme Palette Preview capsule beside *Customize colors* showing primary accent and wallpaper-extracted colors; added *Reset lock screen wallpaper* action.
  - Updated `BLUEPRINT.md` and appended Entry 037 to `receipts/RECEIPTS_003.md`.
- **How it was verified**: Local compilation via `compile_applet` (build succeeded). Purged ephemeral keystores per Credential Immunity Rule.
- **Any deviation from what was requested, and why**: None. Built all requested features.
- **Any known issue or follow-up needed**: Run on-device QA.

