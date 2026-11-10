package dev.plume.search;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import dev.plume.R;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

/** Design fixtures exist only in the test APK; screenshots are explicitly marked as previews. */
final class SearchVisualCheck {
    static int run(Instrumentation test, SearchActivity activity) throws Exception {
        Field titles = field("titles"), cache = field("posters"), selected = field("selected"), offers = field("availability");
        Method results = method("showResults"), details = method("renderAvailability");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 2;
        Bitmap art = BitmapFactory.decodeResource(activity.getResources(), R.drawable.wallpaper_aurora, options);
        ArrayList<TmdbCatalog.Title> fixture = new ArrayList<>();
        for (int i = 0; i < 6; i++) fixture.add(new TmdbCatalog.Title(100 + i, "movie", "Design preview " + (i + 1), "2026",
                "Offline design fixture. Actual searches show the title's own poster, backdrop and description.", "/preview" + i + ".jpg", "/preview-backdrop.jpg"));
        test.runOnMainSync(() -> {
            try {
                @SuppressWarnings("unchecked") LruCache<String, Bitmap> images = (LruCache<String, Bitmap>) cache.get(activity);
                for (TmdbCatalog.Title title : fixture) images.put("poster:" + title.poster, art);
                images.put("backdrop:/preview-backdrop.jpg", art);
                titles.set(activity, fixture); results.invoke(activity);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        test.waitForIdleSync();
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
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); test.waitForIdleSync();
        if (selected.get(activity) != null || activity.isFinishing()) throw new AssertionError("Details Back lost the poster results.");
        if (activity.getCurrentFocus() == null || !activity.getCurrentFocus().getContentDescription().toString().contains("Design preview 2"))
            throw new AssertionError("Details Back did not restore the selected poster.");
        return 3;
    }
    private static Field field(String name) throws Exception { Field f = SearchActivity.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static Method method(String name) throws Exception { Method m = SearchActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m; }
    static void shot(Instrumentation test, String name) throws Exception {
        test.getUiAutomation().waitForIdle(250, 3000);
        try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                test.getUiAutomation().executeShellCommand("screencap -p /sdcard/" + name))) { byte[] b = new byte[1024]; while (in.read(b) != -1) {} }
    }
}
