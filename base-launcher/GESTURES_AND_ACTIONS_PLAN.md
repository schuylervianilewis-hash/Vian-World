# Vian Launcher - Gestures, Actions & Popup Widgets Plan

This document outlines the complete architectural design and phased roadmap for introducing **5 Item Gestures, Curated System Actions, On-Demand Popup Widgets (Option C), Add to Home Bottom Sheet, Unified Backup & Restore, and Universal LogCatcher Integration** in Vian Launcher.

---

## 1. Architectural Vision & Scope Constraints

The goal is to deliver responsive, power-user customization with zero background battery/RAM bloat, adhering strictly to:
* **Preserve Core Taps**: Instant 0ms single-tap launch and standard long-press (shortcuts popup / drag initiation) remain untouched.
* **5 Target Gestures**: Double Tap, Swipe Up, Swipe Down, Swipe Left, Swipe Right.
* **On-Demand Widgets (Option C)**: Both linked to icons (Shutters) and standalone popup widget icons. Widgets load strictly on demand and unload when dismissed, never running permanently in the background.
* **Add to Home Bottom Sheet**: Modern M3 bottom sheet replacing the nested popup menu with Folder, App, Shortcut, and Popup Widget (explicitly **NO Add Page**).
* **Full Dedicated Settings Page**: A comprehensive "Gestures & Actions" screen for global defaults, plus per-item overrides in `EditItemDialog`.
* **Connected Systems**: Universal error/event capture in `LogCatcher` and full layout/gesture export in `BackupHelper`.

---

## 2. Core Architecture & System Flows

### A. Touch Discrimination Pipeline
```
[User Touch Down on Home Screen]
        |
        v
[Is Finger on a Grid Item?]
  |                      |
 [NO]                   [YES]
  |                      |
  |                      +--> Tap (ACTION_UP < 250ms, no move) -> Instant Launch (0ms delay)
  |                      |
  |                      +--> Second Tap within 250ms -> Item Double Tap
  |                      |
  |                      +--> Long Press (500ms stationary) -> Context Menu & Drag
  |                      |
  |                      +--> Fling Vertical (> min fling velocity):
  |                      |      - Upward   -> Item Swipe Up
  |                      |      - Downward -> Item Swipe Down
  |                      |
  |                      +--> Fling Horizontal:
  |                             - Left/Right action configured? -> Trigger Item Action
  |                             - No action configured?         -> Pass to Home Screen Pager
  v
Global Wallpaper Gestures
(Drawer Fling Up, Notification Shade, Empty Double-Tap Lock)
```

### B. Curated System Actions ("Not Too Many, Easy to Implement")
1. `ACTION_LOCK_SCREEN`: Locks device immediately via `DevicePolicyManager.lockNow()`.
2. `ACTION_MUTE_VOLUME`: Toggles media volume mute/unmute via `AudioManager.adjustStreamVolume()`.
3. `ACTION_MUTE_RINGTONE`: Toggles ringtone between Normal and Vibrate (or Silent with DND check).
4. `ACTION_NOTIFICATIONS`: Expands notification shade via `StatusBarManager` reflection (safe fallback).
5. `ACTION_QUICK_SETTINGS`: Expands quick settings panel via `StatusBarManager` reflection.
6. `ACTION_APP_DRAWER`: Opens all apps drawer fragment.
7. `ACTION_FOLDER_POPUP`: Opens animated floating folder popup overlay (`HomeScreenGrid.openFolder()`).
8. `ACTION_POPUP_WIDGET`: Summons linked widget in floating modal window.
9. `ACTION_APP_SHORTCUTS`: Opens dynamic app shortcuts popup.
10. `ACTION_LAUNCH_APP`: Launches user-assigned secondary application.

---

## 3. Phased Implementation Roadmap

### Phase 1: Action Dispatcher & Item Gesture Engine
* **Objective**: Enable gesture detection and action execution on home screen items.
* **Components**:
  * `LauncherActionHandler.kt`: Centralized executor for all 10 curated actions with safe fallbacks and `LogCatcher` logging.
  * `ItemGestureManager.kt`: Fast, in-memory cached mapping of item gesture assignments, persisted to isolated JSON preferences (preventing Room schema migration risks).
  * `HomeScreenGrid.kt` & `MainActivity.kt`: Pointer event interception, velocity tracking, 0ms tap execution, and horizontal page pass-through.
* **Verification**: Unit tests on touch disambiguation; verify single tap remains instant (0ms delay) when double tap is unassigned.

### Phase 2: On-Demand Popup Widgets (Option C: Dual-Mode)
* **Objective**: On-demand widget loading without permanent background overhead.
* **Components**:
  * `PopupWidgetManager.kt`: Manages `AppWidgetHostView` inflation inside a floating Material card overlay.
  * **Mode 1 (Linked Shutter)**: Swiping Up or Double-Tapping an app icon pops open its linked widget.
  * **Mode 2 (Standalone)**: Dedicated popup widget icon on home screen grid that opens the widget on tap.
  * **Lifecycle Safeguard**: Immediate host view detach and receiver cleanup on dismiss (`onDismiss()`).
* **Verification**: Memory profiling to confirm 0MB residual widget RAM after dismissal.

### Phase 3: "Add to Home" Bottom Sheet
* **Objective**: Replace legacy nested popup menus with an accessible M3 bottom sheet.
* **Components**:
  * `AddToHomeBottomSheet.kt` & `dialog_add_to_home_bottom_sheet.xml`.
  * **Options**:
    1. 📁 **Folder**: `CreateFolderDialog`
    2. 📱 **App**: `AddAppDialog`
    3. ⚡ **Shortcut**: `AddShortcutDialog`
    4. 🧩 **Popup Widget**: Opens on-demand widget selector
  * *(Explicitly NO Add Page)*.
* **Verification**: Visual inspection on light/dark themes; verifies target cell placement.

### Phase 4: Full "Gestures & Actions" Settings Page & Per-Item UI
* **Objective**: Unified user control over gestures and actions.
* **Components**:
  * `GesturesActivity.kt` & `activity_gestures.xml`: Full settings screen for global/default gestures, folder cover mode toggle, and widget trigger preferences.
  * `EditItemDialog.kt`: Expandable "Gestures & Actions" section for per-item overrides and "Link Popup Widget" picker.
* **Verification**: Verify setting persistence across app restarts; verify folder cover mode triggers.

### Phase 5: Backup & Restore + Universal LogCatcher Integration
* **Objective**: Reliability, recovery, and deep diagnostic traceability.
* **Components**:
  * `BackupHelper.kt`: JSON export/import of home screen items, folder hierarchies, custom icons, gesture configs, and launcher preferences directly to `Download/VianLauncher/backup_<timestamp>.json`.
  * Backup & Restore UI triggers in `SettingsActivity.kt`.
  * `LogCatcher` hooks across all action dispatches, popup widget lifecycles, and backup operations.
* **Verification**: Full export and import cycle test; inspect `Download/vian logs.txt` to verify clean logging.

---

## 4. Key Files to Modify / Create

| Path | Purpose |
| :--- | :--- |
| `app/src/main/kotlin/org/fossify/home/helpers/LauncherActionHandler.kt` | New: System & launcher action execution |
| `app/src/main/kotlin/org/fossify/home/helpers/ItemGestureManager.kt` | New: Gesture config storage & memory cache |
| `app/src/main/kotlin/org/fossify/home/dialogs/PopupWidgetDialog.kt` | New: Floating on-demand widget window |
| `app/src/main/kotlin/org/fossify/home/dialogs/AddToHomeBottomSheet.kt` | New: M3 Add to Home bottom sheet |
| `app/src/main/kotlin/org/fossify/home/activities/GesturesActivity.kt` | New: Full gestures settings page |
| `app/src/main/kotlin/org/fossify/home/helpers/BackupHelper.kt` | New: Complete JSON backup & restore engine |
| `app/src/main/kotlin/org/fossify/home/activities/MainActivity.kt` | Surgical edit: Touch interception & action routing |
| `app/src/main/kotlin/org/fossify/home/views/HomeScreenGrid.kt` | Surgical edit: Fling detection & folder popup open |
| `app/src/main/kotlin/org/fossify/home/dialogs/EditItemDialog.kt` | Surgical edit: Per-item gesture & widget config |
| `app/src/main/kotlin/org/fossify/home/activities/SettingsActivity.kt` | Surgical edit: Gestures & Backup/Restore entries |
| `app/src/main/res/values/strings.xml` | String resources for gestures, actions, and sheet |
