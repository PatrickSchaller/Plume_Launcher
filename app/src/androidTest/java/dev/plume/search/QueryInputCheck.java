package dev.plume.search;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;

/** Exercises the real TV input method without making a catalog request. */
final class QueryInputCheck {
    static int run(Instrumentation test) throws Exception {
        if (Build.VERSION.SDK_INT < 30) return 0;
        android.content.SharedPreferences prefs = test.getTargetContext().getSharedPreferences("search", Context.MODE_PRIVATE);
        boolean hadToken = prefs.contains("tmdb_token");
        if (!hadToken) prefs.edit().putString("tmdb_token", "offline.fixture.token").commit();
        SearchActivity activity = null;
        try {
            activity = (SearchActivity) test.startActivitySync(new Intent(test.getTargetContext(), SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Field field = SearchActivity.class.getDeclaredField("query"); field.setAccessible(true);
            EditText query = (EditText) field.get(activity);
            test.waitForIdleSync();
            if (!query.hasFocus()) throw new AssertionError("Search did not start with the query focused.");
            awaitKeyboard(test, query, true);
            test.runOnMainSync(() -> {
                query.setText("Previous search");
                ((ViewGroup) query.getParent()).getChildAt(1).performClick();
                if (!query.hasFocus() || query.length() != 0)
                    throw new AssertionError("New search did not clear and focus query entry.");
            });
            test.sendStringSync("fresh");
            test.waitForIdleSync();
            if (!"fresh".equalsIgnoreCase(query.getText().toString())) throw new AssertionError("Fresh query input kept old fixture text: " + query.getText());
            SearchVisualCheck.shot(test, "plume-query-input-preview.png");
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitKeyboard(test, query, false);
            if (activity.isFinishing()) throw new AssertionError("Dismissing the keyboard closed search.");
            java.lang.reflect.Method empty = SearchActivity.class.getDeclaredMethod("showResults"); empty.setAccessible(true);
            SearchActivity search = activity;
            test.runOnMainSync(() -> {
                try { empty.invoke(search); } catch (Exception error) { throw new AssertionError(error); }
            });
            test.waitForIdleSync();
            if (!query.hasFocus() || query.getSelectionStart() != 0 || query.getSelectionEnd() != query.length())
                throw new AssertionError("No matches did not return to a selected query.");
            awaitKeyboard(test, query, true);
            return 7;
        } finally {
            SearchActivity last = activity;
            if (last != null) test.runOnMainSync(last::finish);
            test.waitForIdleSync();
            if (!hadToken) prefs.edit().remove("tmdb_token").commit();
        }
    }
    @android.annotation.TargetApi(30)
    static void awaitKeyboard(Instrumentation test, EditText query, boolean expected) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            boolean[] visible = {false};
            test.runOnMainSync(() -> {
                WindowInsets insets = query.getRootWindowInsets();
                visible[0] = insets != null && insets.isVisible(WindowInsets.Type.ime());
            });
            if (visible[0] == expected) return;
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("TV keyboard visibility did not become " + expected);
    }
}
