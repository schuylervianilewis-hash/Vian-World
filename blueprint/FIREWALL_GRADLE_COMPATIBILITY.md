# Firewall Gradle Compatibility Audit

**Repository:** `schuylervianilewis-hash/Vian-World`  
**Working branch:** `integration/firewall-sidebar-launcher`  
**Audited base commit:** `f8b9014b721a7f4e3d3c4c2c99771bb57a683547`  
**Scope:** Gradle compatibility and conversion of the imported TrackerControl source into the `:firewall` Android library module.

## Existing build configurations

| Setting | Vian-World host | Imported Firewall standalone |
|---|---:|---:|
| Android Gradle Plugin | 9.3.1 (selected after CI validation) | 9.3.3 |
| Gradle wrapper | 9.7.0 (selected after CI validation) | 9.8.1 |
| Kotlin / Compose compiler plugin | 2.4.21 (selected after CI validation) | 2.4.21 |
| compileSdk | 36 | 37 |
| minSdk | 26 | 23 |
| targetSdk | 34 | 37 |
| Compose BOM | 2024.06.00 | 2026.09.00 |
| NDK | 29.0.14206865 | 29.0.14206865 |
| Rust | — | 1.95.0 |
| cargo-ndk | 4.1.2 | 4.1.2 |

## Compatibility findings

- The first CI compile proved that AGP 9's default bundled KGP 2.2.10 cannot read Kotlin 2.4 metadata from Firewall's resolved stdlib (2.4.20). The failures appeared as incompatible metadata and cascading unresolved standard-library references in WireGuard Kotlin files. AGP 9's official migration guidance allows a higher KGP/KSP to be selected by adding both plugins to the top-level buildscript classpath; the root build now explicitly pins KGP 2.4.21 and KSP 2.3.12 this way, while keeping built-in Kotlin enabled.
- Kotlin 2.4.21's fully supported matrix is Gradle 7.6.3–9.7.0 and AGP 8.5.2–9.3.1. AGP 9.3 requires Gradle 9.5.0 or newer. The selected host combination is therefore AGP 9.3.1 + Gradle 9.7.0 + Kotlin 2.4.21.
- The imported standalone combination (AGP 9.3.3, Gradle 9.8.1, Kotlin 2.4.21) exceeds both the fully supported AGP and Gradle bounds. It is not copied unchanged.
- KSP 2.3.12 supports Kotlin 2.3.20–2.4.x, AGP 8.13.0 or later and Gradle 8.13 or later, so it is aligned with the selected stack.
- The root project centralizes Android application/library, Kotlin Compose compiler and KSP plugin versions. The top-level buildscript overrides AGP's default bundled KGP/KSP versions with the selected compatible versions; the Firewall subproject uses those root-resolved plugins rather than declaring its own AGP/Kotlin versions.
- Firewall Compose sources use `PullToRefreshBox` and `LocalLocale`; the old host Compose BOM 2024.06.00 does not provide the APIs used by this source. The host and Firewall BOM are aligned to 2026.09.00.
- Keeping only the `github` flavor preserves the source-level `BuildConfig.FLAVOR` and `GITHUB_LATEST_API` contracts without adding separate Play/F-Droid application identities.

## Integration decisions applied

1. Align the whole build to AGP 9.3.1, Gradle 9.7.0, KGP/Compose compiler plugin 2.4.21 and KSP 2.3.12; keep AGP built-in Kotlin enabled and override its default bundled KGP/KSP versions through the root buildscript classpath. Keep JDK 17.
2. Register `:firewall` from `external/tracker-control-android/app` and apply `com.android.library`.
3. Remove app-only Firewall configuration: applicationId, targetSdk, version code/name, signing, build-type suffixes, APK output naming and Play/F-Droid variants.
4. Retain the `github` flavor, BuildConfig constants, four ABI filters, CMake, Rust WireGuard bridge, dependencies, unit/instrumentation test configuration and consumer ProGuard rules.
5. Compile both host and Firewall against API 37. Keep host targetSdk 34; this stage does not silently change app-wide runtime behavior.
6. Enable core-library desugaring and legacy JNI packaging in the host app.
7. Resolve Rust source and toolchain paths relative to the Firewall module project directory.
8. Convert the Firewall manifest into a library manifest: remove its Application class and launcher filter, retain Firewall components/permissions/network security configuration, and use a distinct FileProvider authority.
9. Extend CI to build this integration branch and install the pinned NDK/Rust/cargo-ndk toolchain.

## CI validation history

- The first integration CI attempt stopped in Android SDK setup because the SDK action received a multiline package list as one package name. The package list was changed to the action's space-separated format.
- The next CI attempt installed Android SDK packages and Rust/cargo-ndk successfully, then reached Kotlin compilation. It failed specifically because Kotlin 2.2.10 cannot read the 2.4.0 metadata in the resolved Kotlin standard library. This finding drove the shared toolchain upgrade above.
- The updated stack must pass the next CI run before this module conversion is considered build-verified.

## Deferred integration risks

- Host targetSdk remains 34. Firewall's API-37 target-gated local-network permission behavior is not activated until the host targetSdk is deliberately raised and reviewed.
- Host Android backup remains enabled. Firewall's standalone `allowBackup=false` cannot be applied independently inside a library; Firewall secrets and data-extraction exclusions must be handled in the planned unified backup/restore stage before release.
- `ApplicationEx` is no longer registered as the Android Application class. Its startup work still needs the separate lifecycle integration stage.
- ACRA dependencies and original initialization source remain temporarily; shared diagnostic logging is a later stage.
- Default-preference namespace and legacy database migration are unchanged and remain part of data/preferences integration.

## References

- https://developer.android.com/build/releases/agp-9-1-0-release-notes
- https://developer.android.com/build/releases/about-agp
- https://kotlinlang.org/docs/gradle-configure-project.html
