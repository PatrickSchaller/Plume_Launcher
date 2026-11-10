package dev.plume.search;

import dev.plume.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalTvActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(16, 17, 20), TEXT = Color.rgb(246, 246, 246);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText query;
    private TextView status;
    private LinearLayout results;
    private CancellationSignal cancellation;
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(36), dp(24), dp(36), dp(16));
        page.setBackgroundColor(BACKGROUND);
        setContentView(page);
        page.addView(text("Search antenna TV", 28));
        String zip = getSharedPreferences("search", MODE_PRIVATE).getString("zip", getString(R.string.search_default_zip_code));
        page.addView(text("Reads this TV's existing antenna guide for the next 48 hours."
                + (zip.isEmpty() ? "" : " Saved ZIP: " + zip + ".")
                + " ZIP alone does not identify received stations; this uses the TV's channel setup.", 15));
        LinearLayout controls = new LinearLayout(this);
        query = new EditText(this);
        query.setSingleLine(true); query.setTextColor(TEXT); query.setTextSize(18);
        query.setHintTextColor(Color.LTGRAY); query.setHint("Show, movie, or sports event");
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setText(getIntent().getStringExtra("query"));
        controls.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("Search guide", this::search));
        controls.addView(button("App permissions", this::openPermissions));
        page.addView(controls);
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search(); return true;
        });
        status = text("Search listings shared by your TV. Opening a result plays that channel now.", 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void search() {
        if (query.getText().toString().trim().isEmpty()) {
            status.setText("Enter a show, movie, or event title first."); query.requestFocus(); return;
        }
        if (checkSelfPermission(BroadcastGuide.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle("Read this TV's antenna guide?")
                    .setMessage("Plume Search needs TV-listings access to search channel names and schedules."
                            + " It will not change channels, scan stations, or upload this guide.")
                    .setPositiveButton("Allow access", (dialog, which) ->
                            requestPermissions(new String[]{BroadcastGuide.PERMISSION}, 42))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        readGuide();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != 42) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) readGuide();
        else status.setText("TV-listings access is unavailable or denied. You can review App permissions,"
                + " or use the TV's Live TV guide.");
    }

    private void readGuide() {
        if (cancellation != null) cancellation.cancel();
        CancellationSignal signal = new CancellationSignal(); cancellation = signal;
        int request = ++generation;
        String term = query.getText().toString().trim();
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
        status.setText("Reading antenna listings…"); results.removeAllViews();
        worker.execute(() -> {
            try {
                BroadcastGuide.Result found = BroadcastGuide.search(this, term, signal);
                runOnUiThread(() -> { if (request == generation && !isDestroyed()) show(found); });
            } catch (OperationCanceledException ignored) {
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (request == generation && !isDestroyed()) status.setText(
                            "Your TV did not share antenna listings. Open Live TV and check channel setup for your location.");
                });
            }
        });
    }

    private void openPermissions() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))); }
        catch (RuntimeException error) { status.setText("Open Settings, then Apps, Plume Search, Permissions to review TV-listings access."); }
    }

    private void show(BroadcastGuide.Result found) {
        if (found.channels == 0 || found.availablePrograms == 0) {
            status.setText("Your TV did not share antenna listings. Open Live TV and check channel setup for your location.");
            return;
        }
        status.setText(found.programs.isEmpty() ? "No matching titles in the antenna listings shared by your TV."
                : "Showing up to " + BroadcastGuide.LIMIT + " matches in the next 48 hours. Open channel plays what's on now.");
        DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (BroadcastGuide.Program program : found.programs) {
            String label = (program.title == null || program.title.isEmpty() ? "TV program" : program.title)
                    + (program.episode == null || program.episode.isEmpty()
                    ? "" : " — " + program.episode) + "\n" + program.station + "  ·  "
                    + dates.format(new Date(program.start)) + "\nOpen channel";
            results.addView(button(label, () -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(program.channelId))); }
                catch (RuntimeException error) { status.setText("The TV cannot open that channel here. Use the remote's Live TV button."); }
            }));
        }
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(TEXT); view.setPadding(0, 0, 0, dp(12)); return view;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(16);
        button.setAllCaps(false); button.setPadding(dp(16), dp(8), dp(16), dp(8));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, fill(Color.WHITE));
        background.addState(new int[]{}, fill(Color.rgb(37, 38, 42)));
        button.setBackground(background);
        button.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{BACKGROUND, TEXT}));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable fill(int color) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(6)); return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        generation++;
        if (cancellation != null) cancellation.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }
}
