# Firewall Gradle Compatibility Audit

**Repository:** `schuylervianilewis-hash/Vian-World`  
**Working branch:** `integration/firewall-sidebar-launcher`  
**Audited base commit:** `f8b9014b721a7f4e3d3c4c2c99771bb57a683547`  
**Scope:** Gradle compatibility and conversion of the imported TrackerControl source into the `:firewall` Android library module.

## Existing build configurations

| Setting | Vian-World host | Imported Firewall standalone |
|---|---:|---:|
| Android Gradle Plugin | 9.1.1 | 9.3.3 |
| Gradle wrapper | 9.3.1 | 9.8.1 |
| Kotlin / Compose compiler plugin | 2.2.10 | 2.4.21 |
| compileSdk | 36 | 37 |
| minSdk | 26 | 23 |
| targetSdk | 34 | 37 |
| Compose BOM | 2024.06.00 | 2026.09.00 |
| NDK | host default | 29.0.14206865 |
| Rust | — | 1.95.0 |
| cargo-ndk | — | 4.1.2 |

## Compatibility findings

- AGP 9.1.1 requires Gradle 9.3.1; the host wrapper is at that minimum supported version.
- AGP 9.1.1 supports compileSdk/API 37, so the host can compile the Firewall library without changing AGP.
- The imported standalone combination (AGP 9.3.3, Gradle 9.8.1, Kotlin 2.4.21) is outside the Kotlin 2.4.21 fully supported matrix: that Kotlin release lists AGP through 9.3.1 and Gradle through 9.7.0. Its independent plugin classpath cannot be carried into a subproject.
- The root project centralizes Android application/library and Kotlin Compose plugin versions. The Firewall subproject uses those root-resolved plugins rather than declaring its own AGP/Kotlin versions.
- Firewall Compose sources use `PullToRefreshBox` and `LocalLocale`; the old host Compose BOM 2024.06.00 does not provide the APIs used by this source. The host and Firewall BOM are aligned to 2026.09.00.
- Keeping only the `github` flavor preserves the source-level `BuildConfig.FLAVOR` and `GITHUB_LATEST_API` contracts without adding separate Play/F-Droid application identities.

## Integration decisions applied

1. Keep the host's AGP 9.1.1, Gradle 9.3.1, Kotlin/Compose compiler plugin 2.2.10 and JDK 17.
2. Register `:firewall` from `external/tracker-control-android/app` and apply `com.android.library`.
3. Remove app-only Firewall configuration: applicationId, targetSdk, version code/name, signing, build-type suffixes, APK output naming and Play/F-Droid variants.
4. Retain the `github` flavor, BuildConfig constants, four ABI filters, CMake, Rust WireGuard bridge, dependencies, tests and consumer ProGuard rules.
5. Compile both host and Firewall against API 37. Keep host targetSdk 34; this stage does not silently change app-wide runtime behavior.
6. Enable core-library desugaring and legacy JNI packaging in the host app.
7. Resolve Rust source and toolchain paths relative to the Firewall module project directory.
8. Convert the Firewall manifest into a library manifest: remove its Application class and launcher filter, retain Firewall components/permissions/network security configuration, and use a distinct FileProvider authority.
9. Extend CI to build this integration branch and install the pinned NDK/Rust/cargo-ndk toolchain.

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
