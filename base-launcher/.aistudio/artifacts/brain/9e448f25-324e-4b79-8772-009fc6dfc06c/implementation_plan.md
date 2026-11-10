# Implementation Plan: App Drawer Overhaul, Squarish Bubble Folders, Folder Popup (+) Action, and Unified Widget Drag-and-Drop

## Overview & Objectives
This plan outlines the architecture and execution strategy for four coordinated features and fixes across Vian Launcher (`org.fossify.home`):
1. **App Drawer Modernization (Matching Screenshot):** Transform the drawer layout to feature the bold "All apps" header, search action on top right, semi-transparent background scrim, and refined grid alignment matching `Screenshot_20261009-215950.png`.
2. **Squarish Bubble Folder on Home Screen:** Update the folder icon drawing on `HomeScreenGrid` to render a translucent squircle with a subtle bubble border outline and rounded icon preview clipping.
3. **Folder Popup Overhaul (Label-free + Top Bar (+) Button):** Remove label rendering inside the open folder popup (displaying crisp app icons only), and introduce a top bar featuring the folder title alongside a `(+)` button dedicated to picking and appending app icons to the folder.
4. **Unified Widget Long-Press & Drag:** Resolve the widget drag limitation on `HomeScreenGrid` so that long-pressing a widget activates its resize frame and immediately enables multi-cell dragging across grid coordinates, maintaining sanity with app icon drag physics.

---

## 1. App Drawer Redesign (`AllAppsFragment`, Layouts, & Styles)

### Current Architecture
- `AllAppsFragment` hosts a `FastScroller`, search bar (`EditText`), and `RecyclerView` using `LaunchersAdapter`.
- Search bar is currently a standard text field embedded above or floating within the list.

### Proposed Changes
- **Header & Search Bar (`all_apps_fragment.xml`):**
  - Implement a clean top header row containing:
    - Title: `"All apps"` in bold typography (`headlineMedium` or `titleLarge`, matching theme primary text).
    - Action icon: A search icon button on the top right that toggles or focuses the expanded search bar.
  - Apply semi-transparent scrim (`#CC000000` dark or dynamic palette with ~80% alpha) to drawer background instead of solid opaque surface.
- **Icon Grid Refinement (`LaunchersAdapter.kt` & `item_launcher_label.xml`):**
  - Ensure uniform icon sizing and label typography aligned with the modern Android app drawer style depicted in the screenshot.
  - Maintain smooth fast-scrolling and alphabet indicator sync.

---

## 2. Squarish Bubble Folder on Home Screen (`HomeScreenGrid.kt`)

### Current Architecture
- Folder cells in `HomeScreenGrid.kt` calculate thumbnail preview locations in `drawFolderIcons()` using circular or generic rectangular backgrounds via `folderBackgroundPaint`.

### Proposed Changes
- **Squircle Geometry & Translucent Bubble:**
  - Create a dedicated squircle path or `drawRoundRect` with refined corner radii (e.g., 22% to 28% of cell size) for squarish bubble aesthetics.
  - Implement a dual-paint technique:
    1. **Fill Paint:** Translucent surface background (`ColorUtils.setAlphaComponent(folderBgColor, 120)` to `160`).
    2. **Bubble Border Paint:** Subtle stroke paint (`strokeWidth = 1.5.dp`, translucent white or accent highlight) outlining the squircle to provide the "bubble" tactile depth.
  - Position preview app icons (up to 4 or 9 icons) centered neatly inside the squircle boundary with proportional padding.

---

## 3. Folder Popup Overhaul: Label-Free Grid & Top Bar (+) Button

### Current Architecture
- When a folder is open (`currentlyOpenFolder != null`), `HomeScreenGrid.drawItemInCell()` renders icons and calls `drawText()` for each child item if titles are enabled.
- The open folder banner draws the title text with simple editing on tap, but lacks a direct action button for adding items.

### Proposed Changes
- **Omit Labels inside Folder Popup:**
  - In `HomeScreenGrid.kt`: In `drawItemInCell()`, check if `item.parentId != null` (meaning the item is rendered inside an open folder).
  - When inside an open folder, suppress label text measurement and `textPaint` drawing entirely, allowing icons to be spaced generously and centered vertically.
- **Top Bar Header & (+) Button:**
  - Define folder top bar bounds `folderHeaderRect` containing:
    - Folder title on the left.
    - Clickable `(+)` icon button on the right (vector drawable or canvas icon drawn with touch hit-rect `folderAddButtonRect`).
  - Handle touch down/up inside `folderAddButtonRect`:
    - Trigger `MainActivity.showAddAppToFolderDialog(folderId)`.
- **App-Only Picker Implementation:**
  - Open a dedicated application-only selection dialog (`SelectFolderAppsDialog` or an optimized mode of `AddAppDialog`), querying `getAllAppLaunchers()` strictly on a background coroutine thread (`ensureBackgroundThread`).
  - Upon selecting an app, persist the new `HomeScreenItem` with `parentId = folderId` and `type = TYPE_APP` to Room DB off the main thread, then invalidate and reload the folder items smoothly.

---

## 4. Unified Widget Long-Press, Drag & Multi-Cell Movement

### Current Architecture
- Long-pressing a widget invokes `widgetLongPressed(item)` in `MyAppWidgetHostView` which triggers `binding.resizeFrame` in `MainActivity`.
- However, `draggedItem` is NOT set for widgets in `HomeScreenGrid`, and moving a widget fails because:
  - `moveItem()` currently sets `right = left` and `bottom = top` for dragging items, collapsing multi-cell widgets into 1x1.
  - `MyAppWidgetHostView.onTouchEvent` consumes drag events when touched, preventing `HomeScreenGrid` from receiving pointer movement for drag gestures once the resize frame appears.

### Proposed Changes
- **Touch & Drag Coordination (`HomeScreenGrid.kt` & `MyAppWidgetHostView.kt`):**
  - When a widget is long-pressed:
    - Display the resize frame with its boundary handles.
    - Simultaneously initialize `draggedItem = item` in `HomeScreenGrid` with `mLongPressedIcon = item`.
    - Allow touch events (`ACTION_MOVE`) to route to `HomeScreenGrid.handleDrag()` so the user can immediately drag the widget around the grid while the resize outline stays attached or follows the drag pointer.
- **Multi-Cell Grid Geometry Preservation:**
  - In `HomeScreenGrid.moveItem(item, x, y)`:
    - Ensure `right = left + (item.widthCells - 1)` and `bottom = top + (item.heightCells - 1)`.
    - Check grid collision detection accounting for full `widthCells x heightCells` footprint.
  - On `ACTION_UP`, commit the widget's new `(left, top, right, bottom)` coordinates to Room DB in background thread and re-layout the `MyAppWidgetHostView`.

---

## 5. Safety, Threading & Policy Checklist
- **Database Threading:** All Room operations (retrieving launchers, adding item to folder, updating widget position) MUST execute via coroutines on `Dispatchers.IO` / `ensureBackgroundThread` to prevent `IllegalStateException` crashes.
- **Credential Immunity:** No modification or restoration of any `.keystore` or credentials.
- **Surgical Changes:** Edit strictly the touch handlers in `HomeScreenGrid.kt`, layout in `all_apps_fragment.xml`, adapter in `LaunchersAdapter.kt`, and folder drawing logic. Leave all unrelated components untouched.
- **Audit Logging:** Maintain receipts in `/receipts/RECEIPTS_002.md` upon any code execution once approved.

---

## Verification & QA Strategy
1. **Compilation Check:** Proactively compile with `compile_applet` and run existing Robolectric tests (`gradle :app:testDebugUnitTest`).
2. **On-Device Manual Flow:**
   - **App Drawer:** Open drawer -> verify semi-transparent background, bold "All apps" title, and search icon alignment.
   - **Squarish Folder:** Place folder on home screen -> verify translucent squircle with bubble outline.
   - **Folder Popup:** Tap folder -> confirm child app icons have no labels; tap `(+)` button -> confirm app picker opens, selects an app, and app is added to folder.
   - **Widget Drag:** Long press any widget -> confirm resize frame shows AND dragging finger immediately moves the widget across grid cells without collapse or crash.
