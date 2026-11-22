# RECEIPTS_019.md

- **Date:** 2026-11-22
- **Scope:** Stage 1 — non-widget launcher item placement.
- **Base:** `recovery/pre-logkeeper-with-current-icon-20261121` at `7a119c598841043f0935b98ada01e0e8539613c4`.
- **Implementation branch:** `feature/launcher-nonwidget-placement-20261122`.

## Files changed

- `launcher/src/main/kotlin/shura/home/activities/MainActivity.kt`
- `launcher/src/main/kotlin/shura/home/views/HomeScreenGrid.kt`

## Changes made

- Corrected the long-press coordinate handoff to pass screen coordinates into `intoViewSpaceCoords()`.
- Added a coordinate-to-cell resolver that considers the rendered desktop and dock cell rectangles rather than silently falling back to the first empty desktop cell.
- Added nearest-free-cell allocation within the intended area: desktop additions stay on the current desktop page when possible; dock additions stay in the dock.
- Extended Sidebar element and Sidebar-created folder placement to re-check occupancy against Room before insertion and persist dock state correctly.
- If the requested dock is full, the add flow reports that condition instead of silently placing the item on the desktop.
- Kept the existing desktop-only placement path for popup-widget items; normal Android widget placement was not changed.

## Verification status

- GitHub source read-back and branch diff inspected after the changes.
- GitHub Actions [run #310](https://github.com/schuylervianilewis-hash/Vian-World/actions/runs/44994097987) passed the launcher, Firewall and host unit tests, Vian World debug APK assembly, and artifact upload.
- Uploaded artifact: `vian-world-debug` (51,705,609 bytes; SHA-256 `983efec454ccafe7bf5b447b9bfbb1843abda364a0cf4aaf75fd81c09d5d81b4`).
- No physical-device placement QA has been performed; this remains required before Stage 1 is marked complete.

## Required QA

- Add an app, shortcut, folder, and Sidebar element from multiple desktop rows and confirm each is placed at or near the intended cell.
- Repeat from each dock position and confirm the item is stored as docked.
- Repeat with an occupied target and confirm the nearest free cell is selected in the same area.
- Fill the dock and confirm the app explains that it is full without adding the item to the desktop.
- Verify popup widgets and normal Android widgets retain their existing placement behaviour.
- Verify page changes and persistence after closing/reopening the launcher.
