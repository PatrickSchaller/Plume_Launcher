# Plume + Search

Personal fork of [Plume Launcher](https://github.com/aradhyas8/Plume_Launcher), with the title-search feature from [Better TV](https://github.com/PatrickSchaller/BetterTV). The Plume home screen, first-run setup, monochrome app styling, wallpaper options, and controls are retained. A magnifying-glass button joins the top-right controls and uses their existing focus, move, and hide behavior.

Actual Android TV 12 emulator screenshots of the optimized APK:

![Plume home with the new Search control](docs/search-fork-home.png)

![Title search before token setup](docs/search-fork-search.png)

Build and test results are recorded in [verification](docs/search-fork-verification.md).

## Install and try

This build is version **1.7.2**, labeled **Plume + Search**, with application ID `dev.patrickschaller.plume`. It installs alongside official Plume and Better TV; each app has separate settings. Install the APK from USB and open **Plume + Search** from the TV's Apps screen.

First try it as a normal app. Before testing its Home-button setup, turn off Better TV's Home button fix and official Plume's Home service so only the selected launcher's service handles Home. The fork keeps Plume's setup process; actual TCL restart behavior still needs checking. No stock launcher is disabled by this change.

The source namespace stays `dev.plume` to keep upstream merges small. If you use ADB, the activity component is `dev.patrickschaller.plume/dev.plume.MainActivity`. The optional Home service is `dev.patrickschaller.plume/dev.plume.HomeOverride`.

## Search setup

1. Select the magnifying glass in the top-right controls.
2. Open **Settings** in Search, then **Import token text file**.
3. Select a UTF-8 text file under 8 KB containing only your TMDB **API Read Access Token**, without quotes or a `Bearer` prefix. Alternatively enter it in the masked field.
4. Remove that token file from the USB afterward. The fork cannot read the token stored in Better TV or official Plume; import it once into this app.
5. Select **My services** to choose your streaming providers, then use **Filter viewing options to my services** on or off.

Request your personal token through [TMDB API settings](https://www.themoviedb.org/settings/api). Do not commit it or include it in screenshots or issue reports. Search credentials are stored separately from launcher preferences and excluded from Android backup/device-transfer rules and Plume's settings export.

Search uses TMDB movie/show metadata and US watch-provider listings powered by JustWatch. It distinguishes subscription, free, ad-supported, rental, and purchase offers, and can query a TV season. The service filter applies to a selected title's offers. App buttons open the provider's app; they do not promise exact-title playback or confirm a subscription. Availability can change or be incomplete.

**Search antenna guide** reads terrestrial listings already shared by the TV, with permission, over the next 48 hours. It does not download schedules by ZIP, configure the tuner, or schedule recordings. ZIP defaults to 54650 and can be changed. Whether the TCL exposes its guide remains unverified.

## Build and updates

Use the upstream Gradle wrapper with JDK 17 and Android SDK 36:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`. Without a private `keystore.properties`, upstream's build uses the local development signing key. Keep that key privately for subsequent test updates; it is not included in Git. APKs and tool/build caches are also ignored.

The update check points to **PatrickSchaller/Plume_Launcher**, not upstream Plume. Automatic builds and releases of this fork are not configured. Official Plume APKs do not replace this app. Upstream weather, aerial, and wallpaper sources remain unchanged.

The search instrumentation runner is `dev.plume.search.SearchInstrumentation`. It checks parsing/filtering with synthetic fixtures, masked token entry, and the new Home-to-Search navigation. It does not establish successful live TMDB responses without a personal token. Upstream hardware-dependent instrumentation remains in the repository.

## License and credits

The fork retains Plume's GPLv3 license and upstream credits. Search is adapted from PatrickSchaller's Better TV. The TMDB logo and nonendorsement notice and JustWatch credit are included in Search settings. This product uses the TMDB API but is not endorsed or certified by TMDB. Provider artwork and trademarks retain their respective rights.
