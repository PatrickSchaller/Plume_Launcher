# Local Android TV test emulator

The original `BetterTV_API31` userdata stopped authorizing ADB. The emulator and ADB public keys were compared and matched, but the old instance still rejected the connection. A fresh, isolated data folder restored authorization. The original AVD userdata was preserved; no TV was connected or modified.

The working test instance uses the existing Android TV 12 x86 image and AVD configuration, with userdata at `.tools/plume-ui-test-data` in the BetterTVOS workspace. It uses the standard user `.android/adbkey` for both ADB and the emulator. Workspace-local Gradle/SDK user caches are still used for builds; they are distinct from this emulator's authorization home.

After the UI tests, the same isolated profile was restarted without `-wipe-data`. ADB again reported `device`, confirming that authorization survived the next launch.

For subsequent launches, align `ANDROID_USER_HOME` and `ANDROID_EMULATOR_HOME` to the standard user `.android` directory and `ADB_VENDOR_KEYS` to its existing `adbkey`. Set `ANDROID_AVD_HOME` to the workspace `.tools/avd`. Start the existing SDK's `platform-tools/adb.exe` server, then launch the emulator with:

```text
-avd BetterTV_API31 -datadir <workspace>/.tools/plume-ui-test-data -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -no-boot-anim
```

The data folder was initialized empty with `-wipe-data` once. Do not pass that option on subsequent launches if you want to retain installed test apps and test settings. Do not erase or move the original AVD to repair this instance.

After `adb devices` shows `device` and `sys.boot_completed` is `1`, install the debug application and debug Android-test APK. Run the framework-only suite with:

```text
adb -s emulator-5554 shell am instrument -w -r -e theme light dev.patrickschaller.plume.test/dev.plume.search.SearchInstrumentation
adb -s emulator-5554 shell am instrument -w -r -e theme dark dev.patrickschaller.plume.test/dev.plume.search.SearchInstrumentation
```

Success is `INSTRUMENTATION_CODE: -1`. Each run restores the previous theme and service selections. Fixtures are only in the test APK. The suite captures `plume-results-preview.png`, `plume-detail-preview.png`, `plume-offers-preview.png`, and `plume-services-preview.png` on the emulator's shared storage. These are layout previews, not current streaming availability.

This procedure does not verify TCL firmware, Home-service restart persistence, private streaming subscriptions, or live TMDB data.
