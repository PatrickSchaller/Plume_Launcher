# Search redesign 1.7.3

The earlier emulator blocker was resolved in the [1.7.4 verification follow-up](search-ui-1.7.4.md), which includes successful light/dark UI runs and screenshots.

Search uses Plume's existing wallpaper, low-resolution frost, glass surfaces, light/dark theme, and remote-focus treatment. Results are poster cards. Selecting a title opens a large artwork panel and viewing-option tiles grouped as Subscription, Free to watch, and Rent or buy. Back returns to the previously selected poster. Artwork failures leave labeled, usable cards.

My services starts with Installed, or Popular when no recognized services are installed. Selected and All services tabs and a name filter replace the long default checkbox list. Direct plan variants share one choice; Amazon, Apple, and Roku add-on channels remain separate. Unknown saved IDs are retained unless the user removes them explicitly. Save commits the draft; Cancel and Back leave it unchanged. At most 48 catalog cards render at once, with full-catalog name filtering available.

Installation does not establish login, paid membership, or plan entitlement. Google Play Billing exposes purchases made within the calling app, not other apps' subscriptions. The picker therefore never silently selects a subscription. Free offers may require an account or contain ads; those requirements cannot be established from app presence.

The same application ID and private search preferences are retained, so installing this update over 1.7.2 keeps the TMDB token and saved selections. No streaming-account credentials or new runtime dependencies are introduced.

## Build artifact

- Version 1.7.3, version code 12, `dev.patrickschaller.plume`.
- Optimized APK: 514,687 bytes; local development signing identity retained.
- SHA-256: `11bc50d5c3cb7fed3893167ad15a4f4a23aace719fc95cc16db6e3d610807cfc`.
- 144 unit tests pass. Debug/release builds and lint pass with the existing upstream baseline; lint warnings remain visible.
- Android instrumentation uses offline fixtures for catalog, offers, poster focus, Back navigation, selection Save/Cancel, artwork validation, token masking, and shared-glass behavior. The updated suite compiled, but execution was blocked by local emulator ADB authorization; no runtime pass is claimed for 1.7.3.

Credential-backed catalog/offer/artwork requests and physical TCL navigation/performance still need user validation. Existing OEM Home/restart and antenna-guide limitations remain. No stock launcher or TV software was changed.

Primary references: [BillingClient queryPurchasesAsync](https://developer.android.com/reference/com/android/billingclient/api/BillingClient#queryPurchasesAsync(com.android.billingclient.api.QueryPurchasesParams,%20com.android.billingclient.api.PurchasesResponseListener)), [launchable TV app detection](https://developer.android.com/reference/android/content/pm/PackageManager#getLeanbackLaunchIntentForPackage(java.lang.String)).
