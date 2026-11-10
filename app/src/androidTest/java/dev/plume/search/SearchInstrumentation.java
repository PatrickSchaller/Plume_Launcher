package dev.plume.search;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import dev.plume.R;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Offline parser checks using Android's actual JSON and URI implementations. */
public final class SearchInstrumentation extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            searchFixtures();
            availabilityFixtures();
            broadcastRules();
            tokenFieldIsMasked();
            launcherSearchNavigation();
            results.putString("stream", "\nPassed " + checks + " search and launcher-navigation checks.\n");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable failure) {
            results.putString("stream", "\nFAILED after " + checks + " checks:\n"
                    + Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private void searchFixtures() throws Exception {
        JSONObject fixture = new JSONObject("{\"results\":["
                + "{\"id\":11,\"media_type\":\"movie\",\"title\":\"Fixture Film\",\"release_date\":\"2024-05-01\",\"poster_path\":\"/film.jpg\"},"
                + "{\"id\":22,\"media_type\":\"tv\",\"name\":\"Fixture Series\",\"first_air_date\":\"2020-02-03\",\"poster_path\":null},"
                + "{\"id\":33,\"media_type\":\"person\",\"name\":\"Fixture Person\"},"
                + "{\"media_type\":\"movie\",\"title\":\"Missing ID\"},"
                + "{\"id\":0,\"media_type\":\"movie\",\"title\":\"Zero ID\"},"
                + "{\"id\":-4,\"media_type\":\"movie\",\"title\":\"Negative ID\"},"
                + "{\"id\":\"bad\",\"media_type\":\"tv\",\"name\":\"Bad ID\"},"
                + "{\"id\":12.5,\"media_type\":\"movie\",\"title\":\"Fractional ID\"},"
                + "{\"id\":4294967307,\"media_type\":\"movie\",\"title\":\"Overflow ID\"},"
                + "null,\"bad row\",{\"id\":44,\"media_type\":\"movie\",\"title\":null},"
                + "{\"id\":55,\"media_type\":\"movie\",\"title\":\"   \"},"
                + "{\"id\":66,\"media_type\":\"tv\",\"name\":\"Undated Fixture\",\"first_air_date\":\"unknown\"}]}");
        List<TmdbCatalog.Title> titles = TmdbCatalog.parseTitles(fixture);
        check(titles.size() == 3, "Search must ignore people and malformed/missing identifiers or names.");
        TmdbCatalog.Title film = titles.get(0), series = titles.get(1), undated = titles.get(2);
        check(film.id == 11 && "movie".equals(film.kind) && "2024".equals(film.year), "Movie identity/year changed.");
        check(series.id == 22 && "tv".equals(series.kind) && "2020".equals(series.year), "TV identity/year changed.");
        check(series.poster.isEmpty(), "JSON null poster must remain absent.");
        check(undated.year.isEmpty(), "An invalid release date must not become a displayed year.");
        check(TmdbCatalog.parseTitles(new JSONObject()).isEmpty(), "Missing search results must be empty.");
    }

    private void availabilityFixtures() throws Exception {
        JSONObject fixture = new JSONObject("{\"results\":{\"US\":{"
                + "\"link\":\"https://www.themoviedb.org/movie/11/watch?locale=US\","
                + "\"flatrate\":[{\"provider_id\":1,\"provider_name\":\"Fixture Subscription\"},null,\"bad row\","
                + "{\"provider_name\":\"Missing ID\"},{\"provider_id\":-1,\"provider_name\":\"Negative ID\"},"
                + "{\"provider_id\":8,\"provider_name\":null}],"
                + "\"free\":[{\"provider_id\":2,\"provider_name\":\"Fixture Free\"}],"
                + "\"ads\":[{\"provider_id\":3,\"provider_name\":\"Fixture Ads\"}],"
                + "\"rent\":[{\"provider_id\":4,\"provider_name\":\"Fixture Rent\"}],"
                + "\"buy\":[{\"provider_id\":5,\"provider_name\":\"Fixture Buy\"}]},"
                + "\"CA\":{\"flatrate\":[{\"provider_id\":9,\"provider_name\":\"Outside US Fixture\"}]}}}");
        TmdbCatalog.Availability availability = TmdbCatalog.parseAvailability(fixture);
        check(availability.offers.size() == 5, "Only valid US provider offers should be included.");
        Set<String> kinds = new HashSet<>(), labels = new HashSet<>();
        for (TmdbCatalog.Offer offer : availability.offers) {
            check(offer.provider.id >= 1 && offer.provider.id <= 5, "A malformed or non-US provider leaked in.");
            kinds.add(offer.kind);
            labels.add(SearchRules.offerLabel(offer.kind));
        }
        check(kinds.equals(new HashSet<>(Arrays.asList("flatrate", "free", "ads", "rent", "buy"))),
                "Subscription/free/ads/rent/buy categories must stay distinct.");
        check(labels.equals(new HashSet<>(Arrays.asList("Subscription", "Free", "Free with ads", "Rent", "Buy"))),
                "Offer descriptions must distinguish subscription access from purchases.");
        check(availability.link.startsWith("https://www.themoviedb.org/"), "Valid source link was dropped.");
        Set<String> mine = new HashSet<>(Arrays.asList("2", "5"));
        int selected = 0, none = 0, all = 0;
        for (TmdbCatalog.Offer offer : availability.offers) {
            if (SearchRules.showProvider(offer.provider.id, true, mine)) {
                selected++;
                check(mine.contains(Integer.toString(offer.provider.id)), "My services included an unselected provider.");
            }
            if (SearchRules.showProvider(offer.provider.id, true, Collections.emptySet())) none++;
            if (SearchRules.showProvider(offer.provider.id, false, Collections.emptySet())) all++;
        }
        check(selected == 2 && none == 0 && all == 5, "My services must honor selection, including an empty selection.");
        TmdbCatalog.Availability missingUs = TmdbCatalog.parseAvailability(new JSONObject(
                "{\"results\":{\"CA\":{\"link\":\"https://www.themoviedb.org/movie/11/watch\","
                        + "\"free\":[{\"provider_id\":9,\"provider_name\":\"Outside US Fixture\"}]}}}"));
        check(missingUs.offers.isEmpty() && missingUs.link.isEmpty(), "Missing US data must not use another region.");
        check(!missingUs.hasUs, "Missing US data must remain unknown rather than imply worldwide unavailability.");
        check(TmdbCatalog.parseAvailability(new JSONObject("{\"results\":{\"US\":{}}}")).hasUs,
                "Present but empty US data must remain distinct from missing US data.");
        for (String link : Arrays.asList("http://www.themoviedb.org/movie/11", "javascript:alert(1)",
                "https://www.themoviedb.org.evil.example/", "https://www.themoviedb.org@evil.example/")) {
            JSONObject unsafe = new JSONObject().put("results", new JSONObject().put("US", new JSONObject().put("link", link)));
            check(TmdbCatalog.parseAvailability(unsafe).link.isEmpty(), "Rejected source link accepted: " + link);
        }
    }

    private void broadcastRules() {
        check(BroadcastRules.isAntenna("TYPE_ATSC_T", "SERVICE_TYPE_AUDIO_VIDEO", true, false), "Browsable ATSC channel excluded.");
        check(!BroadcastRules.isAntenna("TYPE_ATSC_T", "SERVICE_TYPE_AUDIO_VIDEO", true, true), "Locked channel exposed.");
        check(!BroadcastRules.isAntenna("TYPE_DVB_C", "SERVICE_TYPE_AUDIO_VIDEO", true, false), "Cable channel treated as antenna.");
        check(BroadcastRules.matches(" local SPORTS ", "Local   Sports Tonight", null), "Guide text normalization failed.");
        check(!BroadcastRules.matches(" ", "Anything", "Anything"), "Empty guide searches should not return everything.");
    }

    private void tokenFieldIsMasked() {
        Activity settings = startActivitySync(new Intent(getTargetContext(), SearchActivity.class)
                .putExtra("open_settings", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
        int flags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        getUiAutomation().setServiceInfo(info);
        try {
            waitForIdleSync();
            long deadline = SystemClock.uptimeMillis() + 4000;
            boolean masked = false;
            do {
                for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null && android.text.TextUtils.equals(getTargetContext().getPackageName(), root.getPackageName())
                            && hasMaskedField(root)) masked = true;
                }
                if (masked) break;
                SystemClock.sleep(50);
            } while (SystemClock.uptimeMillis() < deadline);
            check(masked,
                    "The API token must remain a masked password field after single-line configuration.");
        } finally {
            info.flags = flags;
            getUiAutomation().setServiceInfo(info);
            runOnMainSync(settings::finish);
        }
    }

    private boolean hasMaskedField(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable() && node.isPassword()) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasMaskedField(node.getChild(i))) return true;
        return false;
    }

    private void launcherSearchNavigation() throws Exception {
        Context context = getTargetContext();
        SharedPreferences home = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        Map<String, ?> before = home.getAll();
        String[] temporaryKeys = {"setup_shown", "seen_notes", "reopen_settings", "top_bar", "icon_style"};
        String testBar = "search,wifi,apps,system,plume";
        home.edit().putBoolean("setup_shown", true)
                .putString("seen_notes", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName)
                .remove("reopen_settings").putString("top_bar", testBar).putString("icon_style", "mono").commit();
        Activity launcher = null;
        Activity search = null;
        ActivityMonitor monitor = addMonitor(SearchActivity.class.getName(), null, false);
        try {
            launcher = startActivitySync(new Intent().setClassName(context.getPackageName(), "dev.plume.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity homeActivity = launcher;
            awaitLauncher(homeActivity);
            runOnMainSync(() -> {
                View entry = homeActivity.findViewById(R.id.searchButton);
                entry.requestFocus();
                entry.performClick();
            });
            search = waitForMonitorWithTimeout(monitor, 4000);
            check(search instanceof SearchActivity, "The Plume Search button did not open title search.");
            Activity searchActivity = search;
            await(searchActivity::hasWindowFocus, "Search did not receive remote focus.");
            selectedTitleBackReturnsToResults((SearchActivity) searchActivity);
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            await(() -> searchActivity.isFinishing() && homeActivity.hasWindowFocus(),
                    "Back from search did not return to the same Plume home screen.");
            check(!homeActivity.isFinishing(), "Search closed the launcher behind it.");
            check(testBar.equals(home.getString("top_bar", null)) && "mono".equals(home.getString("icon_style", null)),
                    "Search changed Plume's top-bar order or monochrome preference.");
            runOnMainSync(() -> check(homeActivity.findViewById(R.id.wifi).isShown()
                            && homeActivity.findViewById(R.id.allAppsButton).isShown()
                            && homeActivity.findViewById(R.id.launcherSettings).isShown(),
                    "Returning from search hid existing Plume controls."));
        } finally {
            removeMonitor(monitor);
            Activity lastSearch = search, lastLauncher = launcher;
            runOnMainSync(() -> {
                if (lastSearch != null) lastSearch.finish();
                if (lastLauncher != null) lastLauncher.finish();
            });
            waitForIdleSync();
            SharedPreferences.Editor restore = home.edit();
            for (String key : temporaryKeys) {
                Object value = before.get(key);
                if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof String) restore.putString(key, (String) value);
                else restore.remove(key);
            }
            restore.commit();
        }
    }

    private void selectedTitleBackReturnsToResults(SearchActivity search) throws Exception {
        // Seed an offline title so this exercises actual Back navigation without a token or network call.
        java.lang.reflect.Field titles = SearchActivity.class.getDeclaredField("titles");
        java.lang.reflect.Field selected = SearchActivity.class.getDeclaredField("selected");
        java.lang.reflect.Field availability = SearchActivity.class.getDeclaredField("availability");
        java.lang.reflect.Method render = SearchActivity.class.getDeclaredMethod("renderAvailability");
        titles.setAccessible(true); selected.setAccessible(true); availability.setAccessible(true); render.setAccessible(true);
        TmdbCatalog.Title fixture = new TmdbCatalog.Title(11, "movie", "Offline Back fixture", "2024", "", "");
        runOnMainSync(() -> {
            try {
                titles.set(search, new java.util.ArrayList<>(Collections.singletonList(fixture)));
                selected.set(search, fixture);
                availability.set(search, new TmdbCatalog.Availability(Collections.emptyList(), "", true));
                render.invoke(search);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        waitForIdleSync();
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitForIdleSync();
        check(!search.isFinishing() && selected.get(search) == null,
                "Back from a selected title must show results rather than exit search.");
        check(!getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Offline Back fixture").isEmpty(),
                "Back lost the previous title results.");
    }

    private void awaitLauncher(Activity launcher) {
        AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
        int flags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        getUiAutomation().setServiceInfo(info);
        try {
            long deadline = SystemClock.uptimeMillis() + 4000;
            do {
                final boolean[] ready = {false};
                runOnMainSync(() -> ready[0] = launcher.hasWindowFocus() && launcher.findViewById(R.id.searchButton).isShown());
                if (ready[0]) return;
                AccessibilityNodeInfo deny = permissionDenyButton(getUiAutomation().getRootInActiveWindow());
                if (deny != null) deny.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                SystemClock.sleep(50);
            } while (SystemClock.uptimeMillis() < deadline);
        } finally {
            info.flags = flags;
            getUiAutomation().setServiceInfo(info);
        }
        throw new AssertionError("Plume did not expose the Search button.");
    }

    private AccessibilityNodeInfo permissionDenyButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String id = node.getViewIdResourceName();
        if (id != null && (id.endsWith(":id/permission_deny_button")
                || id.endsWith(":id/permission_deny_and_dont_ask_again_button"))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = permissionDenyButton(node.getChild(i));
            if (match != null) return match;
        }
        return null;
    }

    private void await(BooleanSupplier condition, String failure) {
        long deadline = SystemClock.uptimeMillis() + 4000;
        do {
            final boolean[] ready = {false};
            runOnMainSync(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(failure);
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
