package dev.plume.search;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import dev.plume.R;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Design fixtures exist only in the test APK; screenshots are explicitly marked as previews. */
final class SearchVisualCheck {
    static int run(Instrumentation test, SearchActivity activity) throws Exception {
        SharedPreferences prefs = activity.getSharedPreferences("search", Context.MODE_PRIVATE);
        Set<String> saved = new HashSet<>(prefs.getStringSet("services", Collections.emptySet()));
        boolean hadServices = prefs.contains("services");
        boolean filtered = prefs.getBoolean("only_my_services", false);
        prefs.edit().putStringSet("services", Collections.singleton("8")).putBoolean("only_my_services", false).commit();
        try { return preview(test, activity); }
        finally {
            SharedPreferences.Editor edit = prefs.edit().putBoolean("only_my_services", filtered);
            if (hadServices) edit.putStringSet("services", saved); else edit.remove("services");
            edit.commit();
        }
    }
    private static int preview(Instrumentation test, SearchActivity activity) throws Exception {
        Field titles = field("titles"), cache = field("posters"), selected = field("selected"), offers = field("availability");
        Method results = method("showResults"), details = method("renderAvailability");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 4;
        Bitmap art = BitmapFactory.decodeResource(activity.getResources(), R.drawable.wallpaper_aurora, options);
        ArrayList<TmdbCatalog.Title> fixture = new ArrayList<>();
        for (int i = 0; i < 6; i++) fixture.add(new TmdbCatalog.Title(100 + i, "movie", "Design preview " + (i + 1), "2026",
                "Offline design fixture. Actual searches show the title's own poster, backdrop and description.", "/preview" + i + ".jpg", "/preview-backdrop.jpg"));
        test.runOnMainSync(() -> {
            try {
                ((android.widget.CheckBox) field("onlyMine").get(activity)).setChecked(false);
                @SuppressWarnings("unchecked") LruCache<String, Bitmap> images = (LruCache<String, Bitmap>) cache.get(activity);
                for (TmdbCatalog.Title title : fixture) images.put("poster:" + title.poster, art);
                images.put("backdrop:/preview-backdrop.jpg", art);
                titles.set(activity, fixture); results.invoke(activity);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        test.waitForIdleSync();
        test.runOnMainSync(() -> assertVisible(activity.getCurrentFocus()));
        View before = activity.getCurrentFocus(); test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); test.waitForIdleSync();
        if (before == activity.getCurrentFocus()) throw new AssertionError("Poster cards do not support horizontal D-pad navigation.");
        shot(test, "plume-results-preview.png");
        test.runOnMainSync(() -> {
            try {
                selected.set(activity, fixture.get(1));
                field("lastTitleId").setInt(activity, 101);
                offers.set(activity, new TmdbCatalog.Availability(Arrays.asList(
                        new TmdbCatalog.Offer(new TmdbCatalog.Provider(8, "Netflix"), "flatrate"),
                        new TmdbCatalog.Offer(new TmdbCatalog.Provider(73, "Tubi"), "free"),
                        new TmdbCatalog.Offer(new TmdbCatalog.Provider(9, "Amazon Prime Video"), "rent")), "", true));
                details.invoke(activity);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        test.waitForIdleSync(); shot(test, "plume-detail-preview.png");
        test.runOnMainSync(() -> {
            assertVisible(provider(activity.findViewById(android.R.id.content), "Netflix,"));
            android.widget.TextView status;
            try { status = (android.widget.TextView) field("status").get(activity); }
            catch (Exception error) { throw new AssertionError(error); }
            if (!status.getText().toString().contains("On your services: Netflix")) throw new AssertionError("Matching services are not visible in the fixed header.");
            assertVisible(status);
        });
        test.runOnMainSync(() -> {
            View provider = provider(activity.findViewById(android.R.id.content), "Netflix,");
            if (provider == null || !provider.requestFocus()) throw new AssertionError("Subscription tile cannot receive remote focus.");
        });
        test.waitForIdleSync(); shot(test, "plume-offers-preview.png");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); test.waitForIdleSync();
        if (activity.getCurrentFocus() == null || !String.valueOf(activity.getCurrentFocus().getContentDescription()).startsWith("Tubi,"))
            throw new AssertionError("Down did not reach the free viewing option.");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); test.waitForIdleSync();
        if (activity.getCurrentFocus() == null || !String.valueOf(activity.getCurrentFocus().getContentDescription()).startsWith("Amazon Prime Video,"))
            throw new AssertionError("Down did not reach the rental viewing option.");
        test.runOnMainSync(() -> {
            try { ((android.widget.CheckBox) field("onlyMine").get(activity)).setChecked(true); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        test.waitForIdleSync();
        test.runOnMainSync(() -> {
            View root = activity.findViewById(android.R.id.content);
            assertVisible(provider(root, "Netflix,"));
            if (provider(root, "Tubi,") != null || provider(root, "Amazon Prime Video,") != null)
                throw new AssertionError("My services leaked unselected providers.");
        });
        test.runOnMainSync(() -> {
            try {
                offers.set(activity, new TmdbCatalog.Availability(Collections.emptyList(), "", true));
                details.invoke(activity);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        test.waitForIdleSync();
        test.runOnMainSync(() -> {
            View empty = text(activity.findViewById(android.R.id.content), "No matches on your selected services.");
            assertVisible(empty);
            try { ((android.widget.CheckBox) field("onlyMine").get(activity)).setChecked(false); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); test.waitForIdleSync();
        if (selected.get(activity) != null || activity.isFinishing()) throw new AssertionError("Details Back lost the poster results.");
        if (activity.getCurrentFocus() == null || !activity.getCurrentFocus().getContentDescription().toString().contains("Design preview 2"))
            throw new AssertionError("Details Back did not restore the selected poster.");
        return 11;
    }
    static void assertVisible(View view) {
        Rect visible = new Rect();
        if (view == null || !view.getGlobalVisibleRect(visible)
                || visible.width() < Math.round(view.getWidth() * view.getScaleX())
                || visible.height() < Math.round(view.getHeight() * view.getScaleY()))
            throw new AssertionError("A first-screen control is clipped: " + (view == null ? "missing" : view.getContentDescription()));
        int safe = Math.round(view.getResources().getDisplayMetrics().widthPixels * .05f);
        if (visible.left < safe || visible.right > view.getResources().getDisplayMetrics().widthPixels - safe)
            throw new AssertionError("A focused control escaped the TV safe margins: " + visible);
    }
    private static View text(View view, String value) {
        if (view instanceof android.widget.TextView && value.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = text(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static View provider(View view, String prefix) {
        if (String.valueOf(view.getContentDescription()).startsWith(prefix)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = provider(((ViewGroup) view).getChildAt(i), prefix); if (found != null) return found;
        }
        return null;
    }
    private static Field field(String name) throws Exception { Field f = SearchActivity.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static Method method(String name) throws Exception { Method m = SearchActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m; }
    static void shot(Instrumentation test, String name) throws Exception {
        test.getUiAutomation().waitForIdle(250, 3000);
        try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                test.getUiAutomation().executeShellCommand("screencap -p /sdcard/" + name))) { byte[] b = new byte[1024]; while (in.read(b) != -1) {} }
    }
}
