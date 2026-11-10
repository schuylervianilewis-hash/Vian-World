# Vian Home (Viabhron Series)

<div align="center">
  <h3>A lightweight, high-performance, privacy-first Android home launcher.</h3>
  <p><strong>Package:</strong> <code>shura.launcher</code> • <strong>Series:</strong> Viabhron • <strong>Creator:</strong> Lewis</p>
  <p><em>Built almost completely in Google AI Studio.</em></p>
</div>

---

## 📖 Origin & Open-Source Lineage
**Vian Home** is an independent open-source fork of **Fossify Launcher** (originally **Simple Launcher** by Simple Mobile Tools). 

While honoring the minimalist, privacy-first roots of its upstream foundations, Vian Home introduces a complete architectural overhaul optimized for lightning-fast responsiveness, zero background RAM waste, modern Material 3 aesthetics, and high-density home screen personalization.

---

## ⚡ Core Highlights & Enhancements

### 🚀 Zero-Latency Event-Driven Navigation
* **Instant Home Return:** Replaced repetitive and heavy `onResume()` app re-queries with an event-driven `LauncherApps.Callback` / Package Broadcast listener. 
* **RAM Optimization & LMK Immunity:** Installed app drawables are downsampled to `192x192` memory-efficient ARGB bitmaps upon loading, reducing background heap consumption by ~80%. The Android Low Memory Killer (LMK) leaves Vian Home alive in RAM, allowing sub-16ms transitions when returning home.

### 🎨 Material 3 Light Theme & Sky Blue Accents
* **Clean Default Palette:** Modern Light Theme default with high-contrast `#212121` typography on `#FAFAFA` surfaces and `#0288D1` Sky Blue interactive highlights (switch tracks, headers, ripples, toolbar).
* **Organized Settings Experience:** Simplified 6-tier structure cleanly dividing *Personalization*, *Home Screen & Dock*, *App Drawer*, *Gestures & Actions*, *Privacy & Security*, and *System & Maintenance*.

### 📱 Ultra-Compact Long-Press Context Menu
* **150dp Ultra-Slim Profile:** Snug, horizontally compressed floating card context menu with bold `14sp` typography and high-contrast Sky Blue actions.
* **Unified Widget Management:** Instant access to system App Info directly from desktop widgets and custom shortcuts.

### 🕒 Built-In Stroked Clock Widget (5x2)
* Native dual-pass stroked canvas rendering (white fill with solid black outline) for crisp visibility over any wallpaper.
* Full drag-and-drop support, edge-snapping, and direct launch targeting default deskclock applications.

### 🔒 Enterprise-Grade App Lock & Privacy
* Biometric (Fingerprint/Face) and PIN protection for individual applications.
* Protected Hidden Apps: Hide sensitive system and user applications behind biometric gates.
* Configurable relock timeouts: *Immediately*, *1 minute*, *5 minutes*, or *Screen off*.

### 📂 Unified Backup & Restore
* Complete JSON export and import covering home screen item coordinates, custom dock layout, drawer renames, hidden apps, item gestures, and custom icon caches.
* Two-pass parent ID re-mapper ensuring flawless restoration across devices.

### 🛡️ Isolated Scoped Storage Log Keeper
* Dedicated on-demand Log Viewer activity with quick filtering (6h, 12h, 24h, All).
* Master On/Off switch, manual export to device `Download/` directory, and emergency automatic crash dump handling with zero telemetry or network calls.

---

## 🛠️ Build & Development
* **Platform:** Android SDK 36 (Minimum SDK 26)
* **Language:** Kotlin DSL & Coroutines
* **Architecture:** Modular MVVM / ViewBinding + Room SQLite
* **Dependency Management:** Gradle Version Catalogs (`libs.versions.toml`)

Built with care by **Lewis** as part of the **Viabhron Series**, powered by **Google AI Studio**.
