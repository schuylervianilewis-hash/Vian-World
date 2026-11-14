# Vian World launcher module

This module contains the launcher source integrated into the Vian World host application.

- Original source project: the launcher project imported on the `Launcher-merge` branch.
- Source namespace retained: `shura.home`.
- The launcher is built as an Android library and does not define an independent application ID or Application class.
- The host application owns the merged manifest, application identity, and Application subclass.
- Launcher data, preferences, Room database (`apps.db`), and widget-host identity remain separately owned by the launcher.

The upstream GPL-3.0 license is retained in this directory. Preserve the license and applicable source-distribution notices in any redistribution.
