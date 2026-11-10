# Plume + Search 1.7.2 verification

Built November 10, 2026, from upstream Plume main commit `86616b7cb7ae4242769fc561a78169fe64dd505f`, with the Better TV search integration. Package `dev.patrickschaller.plume`, version code 11, label **Plume + Search**. Source namespace remains `dev.plume`.

## Artifact

- Optimized release APK: `app/build/outputs/apk/release/app-release.apk`, 507,211 bytes.
- Test USB filename: `Plume-Search-1.7.2.apk`.
- SHA-256: `7b683c6787f38453f92b561fe6dca88cb59028cb26d325cec273d6a28864bbfe`.
- APK v2 signature verification passed. This personal test build uses a local development signing key, not upstream Plume's release key.
- Minimum Android 8 (API 26), target Android 16 (API 36); tested on Android TV 12 (API 31).

## Passed

- `testDebugUnitTest`: 138 tests, zero failures/errors. Includes service identity, Search top-bar backup validation, provider filtering, token/ZIP rules, and terrestrial-guide rules.
- `lintDebug`, `assembleRelease`, `assembleDebug`, `assembleDebugAndroidTest`: successful. Lint retains upstream's baseline (10 existing errors and 23 existing warnings filtered); 119 warnings remain visible. New lint errors were resolved without expanding the baseline.
- Android instrumentation: 37 checks passed with raw `INSTRUMENTATION_CODE: -1`. Real Android JSON/URI fixtures cover title parsing, US offers, My-services filtering, unsafe links, guide filtering, and the masked token field. Home-to-Search-to-Back navigation preserves the same launcher and its existing controls/preferences. Back from selected-title details returns to results before leaving Search.
- The test waits for the target application's password dialog, including when the keyboard is the active window. No real TMDB token is included in test fixtures, source, or screenshots.
- Official Plume (`dev.plume`), Better TV, and the fork install side by side in the emulator.
- The final optimized APK was installed and opened separately after debug instrumentation; its Search button opens the search screen. The crash buffer stayed empty during that smoke test.
- Search activities are private; the token remains in separate app-private search preferences and is excluded from cloud backup, device transfer, and launcher settings export. Search requests retain HTTPS, authorization-header authentication, redirect refusal, timeouts, and response limits.
- The optional install helper passes shell syntax checks, with isolated fake-ADB device-state and recovery-file checks. Its complete shell suite was not completed because of Windows/Git Bash subprocess overhead; no complete-suite pass is claimed.

## Still requires the TCL

No credential-backed live TMDB request was performed. Import the token once into this separate app, select services, and verify live results and provider-app handoff. Synthetic fixtures are not evidence of current availability.

Home/accessibility persistence after restart, physical remote response, streaming playback, HDMI handoff, wallpaper/video behavior, casting, and TCL antenna-guide sharing remain hardware checks. Plume's Home/setup behavior is retained; the fork does not claim to fix the TCL switch resetting. Only one launcher's Home service should be enabled during comparison.

No stock TV launcher or system component was disabled. No production release-signing or GitHub release automation was added.
