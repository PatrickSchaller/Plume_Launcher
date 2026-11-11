package dev.plume.search;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import dev.plume.R;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline fixtures exercise the real picker; none ship in the application APK. */
public final class ServicesInstrumentationCheck {
    private ServicesInstrumentationCheck() { }

    public static int run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE);
        boolean hadServices = prefs.contains("services");
        Set<String> before = new HashSet<>(prefs.getStringSet("services", Collections.emptySet()));
        Set<String> saved = new HashSet<>(Arrays.asList("42", "legacy"));
        prefs.edit().putStringSet("services", saved).commit();
        List<TmdbCatalog.Provider> fixture = Arrays.asList(
                new TmdbCatalog.Provider(8, "Netflix"), new TmdbCatalog.Provider(9, "Netflix Standard with Ads"),
                new TmdbCatalog.Provider(15, "Hulu"), new TmdbCatalog.Provider(20, "Paramount+ Amazon Channel"),
                new TmdbCatalog.Provider(42, "An existing catalog selection"));
        ServicesActivity activity = null;
        int[] checks = {0};
        try {
            activity = open(test, fixture);
            ServicesActivity focusActivity = activity;
            test.runOnMainSync(() -> focusActivity.findViewById(android.R.id.content).findViewWithTag("netflix").requestFocus());
            SearchVisualCheck.shot(test, "plume-services-preview.png");
            test.runOnMainSync(() -> SearchVisualCheck.assertVisible(focusActivity.getCurrentFocus()));
            ServicesActivity cancelActivity = activity;
            test.runOnMainSync(() -> {
                View netflix = cancelActivity.findViewById(android.R.id.content).findViewWithTag("netflix");
                check(checks, netflix != null && netflix.isShown(), "Installed services were not the initial short list.");
                check(checks, !netflix.isSelected(), "Installed must not automatically select a subscription.");
                check(checks, cancelActivity.findViewById(android.R.id.content).findViewWithTag("hulu") == null,
                        "Uninstalled services cluttered the Installed tab.");
                netflix.requestFocus();
                netflix.performClick();
                check(checks, netflix.isSelected() && cancelActivity.getCurrentFocus() == netflix,
                        "Toggling a service lost its remote focus.");
                check(checks, saved.equals(prefs.getStringSet("services", Collections.emptySet())),
                        "A draft selection was persisted before Save.");
                TextView save = cancelActivity.findViewById(R.id.services_save);
                check(checks, save.getText().toString().contains("3 selected"), "Grouped service tiers were counted as separate subscriptions.");
                EditText filter = cancelActivity.findViewById(R.id.services_filter);
                filter.setText("Paramount");
                View channel = cancelActivity.findViewById(android.R.id.content).findViewWithTag("provider:20");
                check(checks, channel != null && channel.getContentDescription().toString().contains("add-on channel")
                                && !channel.getContentDescription().toString().contains("installed"),
                        "Catalog search did not distinguish an add-on channel from its direct app.");
                cancelActivity.findViewById(R.id.services_cancel).performClick();
            });
            test.waitForIdleSync();
            check(checks, saved.equals(prefs.getStringSet("services", Collections.emptySet())), "Cancel changed saved services.");

            activity = open(test, fixture);
            ServicesActivity saveActivity = activity;
            test.runOnMainSync(() -> {
                View netflix = saveActivity.findViewById(android.R.id.content).findViewWithTag("netflix");
                netflix.performClick();
                saveActivity.findViewById(R.id.services_save).performClick();
            });
            test.waitForIdleSync();
            check(checks, new HashSet<>(Arrays.asList("42", "legacy", "8", "9"))
                            .equals(prefs.getStringSet("services", Collections.emptySet())),
                    "Save dropped existing IDs or failed to include the explicitly selected service's direct tiers.");
            return checks[0];
        } finally {
            ServicesActivity last = activity;
            if (last != null) test.runOnMainSync(last::finish);
            SharedPreferences.Editor restore = prefs.edit();
            if (hadServices) restore.putStringSet("services", before); else restore.remove("services");
            restore.commit();
        }
    }

    private static ServicesActivity open(Instrumentation test, List<TmdbCatalog.Provider> fixture) throws Exception {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), ServicesActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ServicesActivity services = (ServicesActivity) activity;
        Method catalog = ServicesActivity.class.getDeclaredMethod("showCatalog", List.class, Set.class);
        catalog.setAccessible(true);
        test.runOnMainSync(() -> {
            try { catalog.invoke(services, fixture, Collections.singleton("com.netflix.ninja")); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        test.waitForIdleSync();
        return services;
    }

    private static void check(int[] count, boolean condition, String message) {
        count[0]++;
        if (!condition) throw new AssertionError(message);
    }
}
