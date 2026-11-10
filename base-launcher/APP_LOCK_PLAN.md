# Vian Launcher - App Lock Implementation Plan

This document outlines the end-to-end architectural plan and implementation roadmap for introducing **App Lock** (Biometric / Phone PIN / Custom Passcode) to Vian Launcher.

---

## 1. Feature Vision & Architecture Overview

The objective is to provide a seamless, secure, and privacy-first application locking mechanism directly integrated into the launcher experience.

### Architectural Model: Launcher-Level Interception
* **Core Mechanism**: Intercept application launch intents generated from the Home Screen grid, Dock, App Drawer, Search results, and App Shortcuts before calling `startActivity()`.
* **Zero Overhead**: Does not rely on heavy background battery-draining polling services.
* **Dual-Tier Authentication**:
  1. **Tier 1 (Device Credential & Biometrics)**: Uses AndroidX `BiometricPrompt` supporting Fingerprint, Face Unlock, and device Phone PIN / Pattern / Password.
  2. **Tier 2 (Custom Launcher Passcode)**: Uses Fossify Commons' built-in security architecture (`SecurityDialog`, custom PIN, or custom Pattern) for users who want a secret code distinct from their device lock screen.

---

## 2. Core Components & Touchpoints

```
                      +-----------------------------+
                      | User Taps App / Hidden Apps |
                      +--------------+--------------+
                                     |
                                     v
                 +---------------------------------------+
                 | Is App in Locked List or Protected?   |
                 +-------------------+-------------------+
                                     |
                         +-----------+-----------+
                         |                       |
                       [YES]                    [NO]
                         |                       |
                         v                       v
        +----------------------------------+  Normal Launch
        | Has active session / grace time? |  (launchApp)
        +----------------+-----------------+
                         |
                 +-------+-------+
                 |               |
               [YES]            [NO]
                 |               |
                 v               v
           Normal Launch  +--------------------------------+
                          | Present Auth Prompt            |
                          | (BiometricPrompt / Passcode)   |
                          +---------------+----------------+
                                          |
                                  +-------+-------+
                                  |               |
                              [Success]        [Cancel/Fail]
                                  |               |
                                  v               v
                           Store Session   Stay on Home/Drawer
                           & launchApp()   (Show feedback)
```

### Key Files in Codebase to Modify / Integrate:
1. **`app/src/main/AndroidManifest.xml`**:
   * Replace `<uses-permission android:name="android.permission.USE_FINGERPRINT" tools:node="remove" />` with `android.permission.USE_BIOMETRIC`.
2. **`app/src/main/kotlin/org/fossify/home/databases/AppsDatabase.kt`**:
   * Add `LockedApp` entity and `LockedAppsDao`.
   * Increment database version (Version 5 -> Version 6 with migration).
3. **`app/src/main/kotlin/org/fossify/home/helpers/AppLockManager.kt` (New)**:
   * Handles in-memory cache of locked package names (`HashSet<String>`).
   * Manages unlocked session states and timeout policies (e.g., "Immediately", "1 minute", "Until screen turns off").
   * Triggers AndroidX `BiometricPrompt` or Fossify Commons `SecurityDialog`.
4. **`app/src/main/kotlin/org/fossify/home/extensions/Activity.kt`**:
   * Enhance `Activity.launchApp(packageName: String, activityName: String)` to check `AppLockManager` before firing the intent.
5. **`app/src/main/kotlin/org/fossify/home/activities/MainActivity.kt` & `AllAppsFragment.kt`**:
   * Context Menu: Add "Lock app" / "Unlock app" toggle to the long-press item menu.
6. **`app/src/main/kotlin/org/fossify/home/activities/HiddenIconsActivity.kt`**:
   * Guard entry into "Hidden Apps" with authentication.
7. **`app/src/main/kotlin/org/fossify/home/activities/SettingsActivity.kt`**:
   * Add an "App Lock" settings screen/section (Toggle feature, Choose Lock Type, Timeout options).

---

## 3. Phased Implementation Roadmap

### Phase 1: Security Foundation & Dependencies
- [x] **Manifest Permissions**:
  - Remove `USE_FINGERPRINT` removal directive in `AndroidManifest.xml`.
  - Add `<uses-permission android:name="android.permission.USE_BIOMETRIC" />`.
- [x] **Dependencies**:
  - Verify `androidx.biometric:biometric` availability (or leverage Fossify Commons security modules).

### Phase 2: Persistence Layer (Room DB v6)
- [x] **Data Model**:
  ```kotlin
  @Entity(tableName = "locked_apps")
  data class LockedApp(
      @PrimaryKey val packageName: String,
      val title: String,
      val lockedAt: Long = System.currentTimeMillis()
  )
  ```
- [x] **DAO Interface**:
  - `getAllLockedApps()` (Flow/LiveData)
  - `isAppLocked(packageName: String): Boolean`
  - `insertLockedApp(app: LockedApp)`
  - `deleteLockedApp(packageName: String)`
- [x] **Database Migration**:
  - Migrate `AppsDatabase` from version 5 to 6:
    `CREATE TABLE IF NOT EXISTS locked_apps (packageName TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, lockedAt INTEGER NOT NULL)`

### Phase 3: AppLockManager & Authentication Controller
- [x] **In-Memory Cache**:
  - Maintain a fast, thread-safe `ConcurrentHashMap<String, Long>` / `HashSet<String>` of locked packages to ensure 0ms latency when opening apps.
- [x] **Session & Grace Period Handling**:
  - Store timestamp of last unlock per package (or global session).
  - Configurable relock timeouts:
    - *Immediately* (locks as soon as app loses focus / user returns to launcher)
    - *1 minute / 5 minutes*
    - *Until screen turns off* (listen for `Intent.ACTION_SCREEN_OFF` in a receiver)
- [x] **Biometric Prompt Flow**:
  - Build prompt with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`.
  - Fallback cleanly to device PIN / Pattern if device lacks biometric sensors or user has no enrolled fingerprints.

### Phase 4: Launch Interception & Context Menus
- [x] **Launcher Interception**:
  - In `Activity.launchApp(packageName, activityName)`:
    - If `AppLockManager.isLocked(packageName)`:
      - Launch auth prompt.
      - On success: mark app as temporarily unlocked, proceed to launch.
      - On failure/dismiss: stay on current screen.
- [x] **Long-Press Menu Integration**:
  - In `MainActivity.kt` (Home screen shortcuts/grid icons) and `AllAppsFragment.kt` (App drawer):
    - Add "Lock app" / "Unlock app" menu entry with lock icon.
    - When user taps "Unlock app", prompt for auth before removing protection.

### Phase 5: Hidden Apps & Settings Protection
- [x] **Protected Hidden Apps**:
  - Before rendering `HiddenIconsActivity`, invoke authentication check.
  - Ensures hidden applications cannot be viewed or unhidden by unauthorized users.
- [x] **Settings Section**:
  - Create dedicated "App Lock" section in `SettingsActivity`:
    - Enable/Disable App Lock.
    - Authentication method selector: *Device Lock (Biometric/PIN)* vs. *Custom Launcher Passcode*.
    - Relock timeout settings.
    - List of currently locked apps with quick-toggle checkboxes.

### Phase 6: Separate Custom PIN / Pattern (Later Tier)
- [x] **Fossify Commons Integration**:
  - Leverage `org.fossify.commons.dialogs.SecurityDialog`.
  - Support `PROTECTION_PIN` and `PROTECTION_PATTERN`.
  - Save hashed credentials via `baseConfig.appPasswordHash` and `baseConfig.appPasswordType`.

---

## 4. Edge Cases & Safety Precautions

1. **Uninstalled Apps Cleanup**:
   * When an app is uninstalled by the user or system, `PackageChangeReceiver` must automatically delete its record from `locked_apps` table to avoid orphaned database entries.
2. **Locking System / Critical Apps Warning**:
   * Display a warning dialog if the user attempts to lock critical system packages (e.g., Phone / Dialer, Settings, or the Launcher itself) to prevent lockouts.
3. **No Biometric Enrolled**:
   * Gracefully handle devices where fingerprint hardware exists but no biometrics are registered, falling back to `DEVICE_CREDENTIAL` (system PIN) or custom passcode.
4. **App Shortcuts & Widgets**:
   * Pinning a shortcut or opening via secondary activity names under the same package must still evaluate the root `packageName`.
5. **Recent Apps Bypass Awareness**:
   * Explicitly document in the settings UI that launcher-level locking protects all launcher entry points (Home screen, Drawer, Search). If full system-wide protection against Android's "Recent Apps" switcher is desired in future phases, a companion Accessibility Service toggle can be introduced.

---

## 5. Summary of Deliverables & Verification Checklist
- [x] Database migration test (5 -> 6) succeeds without data loss.
- [x] Biometric & PIN prompt triggers on Home Screen launch.
- [x] Biometric & PIN prompt triggers on App Drawer launch.
- [x] Session timeout works accurately according to user preference.
- [x] Long-press context menu reliably toggles lock status.
- [x] Hidden apps view is gated behind authentication.
- [x] Clean build and verified zero performance degradation.
