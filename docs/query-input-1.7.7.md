# Query entry verification: 1.7.7

Verified November 12, 2026 on the Android TV 12/API 31 emulator at 1920×1080, density 320. Package `dev.patrickschaller.plume`, version code 16.

## Resulting flow

- Search starts with the query focused. With a saved TMDB token, it requests the TV keyboard after the window gains focus.
- **Up** from any poster in the first row focuses **New search**. OK clears the previous text, focuses the field and opens the keyboard. Typing begins a fresh query without deleting the old text character by character.
- The field also highlights its contents on focus/click, using Android's native selection behavior. The explicit New search action is the reliable replacement flow: the emulator TV keyboard did not consistently replace selected text when typing directly over an existing query.
- After dismissing the keyboard, **Down** from the field returns to the first poster. Back from title details and Back to Plume retain their existing behavior.
- A no-match response returns focus to query entry and requests the keyboard.

The implementation follows Android's [selection API](https://developer.android.com/reference/android/widget/TextView#setSelectAllOnFocus(boolean)) and [keyboard visibility guidance](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility), including avoiding a startup Always hidden flag and posting keyboard requests after focus. The prior composing state is cleared and the input session restarted when editing resumes.

## Validation

- 148 unit tests pass with no failures.
- 119 emulator search/launcher/input checks pass in light mode and again in dark mode (`INSTRUMENTATION_CODE: -1`).
- New checks cover initial query focus, actual input-method window visibility, clearing an old query through New search, fresh text entry while the input method is active, Back hiding the keyboard without closing Search, no-match query entry, Up from posters, and Down back to results with the keyboard closed.
- Existing search/service filtering, My Services Save/Cancel, Home recency and contrast checks remain in the suite.
- The optimized release was installed over the earlier fork. Its actual Home Search action opens with the query focused, and New search clears an injected old-query fixture and retains query focus. The release smoke test's crash buffer stays empty.
- Debug/release builds and lint pass against the existing upstream baseline. Lint reports 119 visible warnings, with 10 existing errors and 23 warnings filtered by that baseline.

The TV keyboard can append text despite a visible selection. New search explicitly empties the field before opening the keyboard; validation uses that flow and injects text keys while the real input-method window is active. Tests initialize D-pad mode and use only offline fixtures and a temporary placeholder token, restored afterward. No catalog request or personal-token import is part of this test.

## Release artifact

`Plume-Search-1.7.7.apk`, 518,647 bytes. SHA-256: `54339b7a3df1448eedf6765a9ffd458883babd3a688bf718136353da864b87c1`. APK v2 signature verification passes, with the same signing identity as earlier fork builds. This APK includes the 1.7.6 readability and recency changes.

Physical TCL keyboard behavior, other keyboard implementations, Android TV versions before API 31, and live TMDB responses remain unverified. Android may suppress the software keyboard with an attached hardware keyboard.
