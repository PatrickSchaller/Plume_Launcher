package dev.plume.search;

import dev.plume.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class SearchActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(16, 17, 20), WHITE = Color.rgb(246, 246, 246);
    private static final int MUTED = Color.rgb(192, 194, 198), FOCUS = Color.WHITE;
    private static final int IMPORT_TOKEN = 41;
    private final ExecutorService requests = Executors.newFixedThreadPool(2);
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private SharedPreferences preferences;
    private EditText query;
    private CheckBox onlyMine;
    private LinearLayout content;
    private TextView status;
    private List<TmdbCatalog.Title> titles = new ArrayList<>();
    private TmdbCatalog.Title selected;
    private TmdbCatalog.Availability availability;
    private long availabilityCheckedAt;
    private int season = -1;
    private volatile int generation;
    private Future<?> pending;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        preferences = getSharedPreferences("search", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout page = column();
        page.setPadding(dp(36), dp(20), dp(36), dp(28));
        scroll.addView(page);
        TextView title = text("Find something good.", 30, WHITE);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        page.addView(title);
        page.addView(text("Movies and TV shows · United States", 14, MUTED));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Movie or TV show");
        query.setTextColor(WHITE);
        query.setHintTextColor(MUTED);
        query.setTextSize(18);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        query.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || event != null && event.getAction() == android.view.KeyEvent.ACTION_UP
                    && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) { search(); return true; }
            return false;
        });
        controls.addView(query, new LinearLayout.LayoutParams(0, dp(52), 1));
        controls.addView(button("Search", this::search));
        controls.addView(button("My services", this::chooseServices));
        controls.addView(button("Settings", this::showSettings));
        page.addView(controls);
        onlyMine = new CheckBox(this);
        onlyMine.setText("Filter viewing options to my services");
        onlyMine.setTextColor(WHITE);
        onlyMine.setChecked(preferences.getBoolean("only_my_services", false));
        onlyMine.setOnCheckedChangeListener((view, checked) -> {
            preferences.edit().putBoolean("only_my_services", checked).apply();
            if (selected != null && availability != null) renderAvailability();
        });
        page.addView(onlyMine);
        page.addView(button("Search antenna guide", () -> startActivity(new Intent(this, LocalTvActivity.class)
                .putExtra("query", selected == null ? query.getText().toString().trim() : selected.name))));
        status = text("", 14, MUTED);
        status.setPadding(0, dp(14), 0, dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        content = column();
        page.addView(content);
        setContentView(scroll);
        if (state != null) query.setText(state.getString("query", ""));
        intro();
        if (getIntent().getBooleanExtra("open_settings", false)) showSettings();
    }

    private String token() { return preferences.getString("tmdb_token", ""); }
    private TmdbCatalog catalog() { return new TmdbCatalog(token()); }
    private Set<String> services() { return new HashSet<>(preferences.getStringSet("services", new HashSet<>())); }

    private void intro() {
        selected = null;
        availability = null;
        titles.clear();
        content.removeAllViews();
        status.setText(token().isEmpty() ? "Set up a free TMDB read token in Settings to search streaming titles."
                : "Enter a title, then select Search.");
        content.addView(text("Find where a title is listed, with subscription, free, rental and purchase options shown separately. "
                + "Installed apps do not tell us which subscriptions you have. Antenna search works independently.", 16, MUTED));
    }

    private void search() {
        String term = query.getText().toString().trim();
        if (term.isEmpty()) { query.setError("Enter a movie or TV show."); query.requestFocus(); return; }
        if (token().isEmpty()) { showSettings(); return; }
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
        selected = null;
        availability = null;
        titles.clear();
        content.removeAllViews();
        status.setText("Searching…");
        TmdbCatalog api = catalog();
        request(() -> api.search(term), result -> { titles = result; showResults(); }, this::showError);
    }

    private void showResults() {
        selected = null;
        content.removeAllViews();
        status.setText(titles.isEmpty() ? "No matching movies or TV shows. Try another title."
                : "Choose a title to see where it is listed in the US.");
        int ticket = generation;
        View first = null;
        for (TmdbCatalog.Title title : titles) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackground(background());
            row.setContentDescription(title.name + ", " + title.subtitle() + ". Show viewing options.");
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
            poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(poster, new LinearLayout.LayoutParams(dp(58), dp(87)));
            LinearLayout words = column();
            words.setPadding(dp(16), 0, 0, 0);
            words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            TextView name = text(title.name, 20, WHITE);
            words.addView(name);
            TextView subtitle = text(title.subtitle(), 14, MUTED);
            words.addView(subtitle);
            TextView overview = text(title.overview, 13, MUTED);
            overview.setMaxLines(2);
            overview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(overview);
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnFocusChangeListener((view, focused) -> {
                name.setTextColor(focused ? BACKGROUND : WHITE);
                subtitle.setTextColor(focused ? BACKGROUND : MUTED);
                overview.setTextColor(focused ? BACKGROUND : MUTED);
            });
            row.setOnClickListener(view -> showAvailability(title, -1));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
            space.bottomMargin = dp(8);
            content.addView(row, space);
            if (first == null) first = row;
            Bitmap cached = posters.get(title.poster);
            if (cached != null) poster.setImageBitmap(cached);
            else if (!title.poster.isEmpty()) images.execute(() -> {
                if (!current(ticket)) return;
                try {
                    Bitmap bitmap = TmdbCatalog.poster(title.poster);
                    if (bitmap != null) {
                        posters.put(title.poster, bitmap);
                        runOnUiThread(() -> { if (current(ticket)) poster.setImageBitmap(bitmap); });
                    }
                } catch (Exception ignored) { /* Titles remain usable when a poster cannot load. */ }
            });
        }
        if (first != null) first.requestFocus();
    }

    private void showAvailability(TmdbCatalog.Title title, int seasonNumber) {
        selected = title;
        season = seasonNumber;
        availability = null;
        content.removeAllViews();
        status.setText("Checking US viewing options for " + title.name + "…");
        TmdbCatalog api = catalog();
        request(() -> api.availability(title, seasonNumber), result -> {
            availability = result;
            availabilityCheckedAt = System.currentTimeMillis();
            renderAvailability();
        }, this::showError);
    }

    private void renderAvailability() {
        content.removeAllViews();
        status.setText("Where to watch · US · Fetched " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(availabilityCheckedAt)));
        content.addView(text(selected.name, 27, WHITE));
        content.addView(text(selected.subtitle() + (season < 0 ? "" : " · Season " + season), 15, MUTED));
        if ("tv".equals(selected.kind)) {
            content.addView(text(season < 0 ? "Series-level listing. Seasons and episodes may differ."
                    : "Season-level listing. Check the service for individual episodes.", 14, MUTED));
            content.addView(button("Choose season", this::chooseSeason));
        }
        boolean filtered = onlyMine.isChecked();
        if (filtered) content.addView(button("Show all services", () -> onlyMine.setChecked(false)));
        Set<String> mine = services();
        int shown = 0;
        for (String kind : new String[]{"flatrate", "free", "ads", "rent", "buy"}) {
            boolean heading = false;
            for (TmdbCatalog.Offer offer : availability.offers) {
                if (!kind.equals(offer.kind) || !SearchRules.showProvider(offer.provider.id, filtered, mine)) continue;
                if (!heading) { content.addView(text(SearchRules.offerLabel(kind), 19, FOCUS)); heading = true; }
                Intent launch = providerIntent(offer.provider.name);
                if (launch == null) content.addView(text(offer.provider.name, 16, WHITE));
                else content.addView(button("Open " + offer.provider.name + " app", () -> openApp(launch)));
                shown++;
            }
        }
        if (shown == 0) content.addView(text(!availability.hasUs ? "No US availability data was returned. Check the service directly."
                : filtered ? "No listed offers match your selected services. Choose services or show all."
                : "No US viewing offers were returned. This does not prove the title is unavailable everywhere.", 16, MUTED));
        content.addView(button("Choose my services", this::chooseServices));
        content.addView(text("An app shortcut opens the service, not this exact title. Subscription access, add-ons and current availability "
                + "must be confirmed in that service. Rental and purchase prices are not supplied here.", 13, MUTED));
        if (!availability.link.isEmpty()) content.addView(button("View availability details on TMDB", () -> openWeb(availability.link)));
        content.addView(text("Availability data: JustWatch · Movie and TV metadata: TMDB", 12, MUTED));
        content.addView(button("Back to results", this::showResults));
        onlyMine.requestFocus();
    }

    private Intent providerIntent(String name) {
        String packageName = SearchRules.packageForProvider(name);
        return packageName == null ? null : getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
    }

    private void openApp(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException unavailable) { toast("This app is unavailable. Open it from your TV's Apps screen."); }
    }

    private void chooseSeason() {
        TmdbCatalog.Title title = selected;
        TmdbCatalog api = catalog();
        status.setText("Loading seasons…");
        request(() -> api.seasons(title), seasons -> {
            status.setText("Choose a season for its own availability listing.");
            String[] names = new String[seasons.size() + 1];
            names[0] = "Whole series";
            for (int i = 0; i < seasons.size(); i++) names[i + 1] = seasons.get(i).name;
            new AlertDialog.Builder(this).setTitle("Season")
                    .setItems(names, (dialog, index) -> showAvailability(title, index == 0 ? -1 : seasons.get(index - 1).number))
                    .setNegativeButton("Cancel", null).show();
        }, this::showError);
    }

    private void chooseServices() {
        if (token().isEmpty()) { showSettings(); return; }
        TmdbCatalog api = catalog();
        status.setText("Loading services available in the US…");
        request(api::providers, providers -> {
            status.setText("Select services you use. Installed apps are not treated as subscriptions.");
            if (providers.isEmpty()) { restoreContent(); toast("TMDB returned no US providers. Try again later."); return; }
            Set<String> chosen = services();
            String[] names = new String[providers.size()];
            boolean[] checked = new boolean[providers.size()];
            for (int i = 0; i < providers.size(); i++) {
                names[i] = providers.get(i).name;
                checked[i] = chosen.contains(Integer.toString(providers.get(i).id));
            }
            AlertDialog choices = new AlertDialog.Builder(this).setTitle("My services · United States")
                    .setMultiChoiceItems(names, checked, (dialog, index, value) -> {
                        String id = Integer.toString(providers.get(index).id);
                        if (value) chosen.add(id); else chosen.remove(id);
                    }).setPositiveButton("Save", (dialog, which) -> {
                        preferences.edit().putStringSet("services", new HashSet<>(chosen)).apply();
                    }).setNegativeButton("Cancel", null).create();
            choices.setOnDismissListener(dialog -> restoreContent());
            choices.show();
        }, failure -> { restoreContent(); showError(failure); });
    }

    private void restoreContent() {
        if (selected != null && availability != null) renderAvailability();
        else if (selected != null) showAvailability(selected, season);
        else if (!titles.isEmpty()) showResults();
        else intro();
    }

    private void showSettings() {
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(10), dp(24), dp(10));
        fields.addView(text("TMDB read access token", 16, WHITE));
        EditText secret = new EditText(this);
        secret.setSingleLine(true);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        secret.setSaveEnabled(false);
        secret.setHint(token().isEmpty() ? "Enter your TMDB API Read Access Token" : "Token saved — leave blank to keep it");
        secret.setTextColor(WHITE);
        secret.setHintTextColor(MUTED);
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        secret.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        fields.addView(secret);
        fields.addView(text("Free for personal, noncommercial use. Get a read access token from your TMDB account's API settings. "
                + "It stays in this app's private settings and is sent only to TMDB. You can also import a plain text token file from USB.", 13, MUTED));
        fields.addView(text("US ZIP code (optional)", 16, WHITE));
        EditText zip = new EditText(this);
        zip.setInputType(InputType.TYPE_CLASS_NUMBER);
        zip.setSingleLine(true);
        zip.setTextColor(WHITE);
        zip.setText(preferences.getString("zip", getString(R.string.search_default_zip_code)));
        zip.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        fields.addView(zip);
        fields.addView(text("Streaming listings use the US country catalog. ZIP does not change streaming offers or configure antenna channels.", 13, MUTED));
        addCredits(fields);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Search settings").setView(scroll)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        fields.addView(button("Get a token on TMDB", () -> openWeb("https://www.themoviedb.org/settings/api")));
        fields.addView(button("Import token text file", () -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain"), IMPORT_TOKEN);
                dialog.dismiss();
            } catch (ActivityNotFoundException unavailable) { toast("No document picker is available. Enter your token above instead."); }
        }));
        if (!token().isEmpty()) fields.addView(button("Remove saved token", () -> {
            cancelRequest(); preferences.edit().remove("tmdb_token").apply(); dialog.dismiss(); intro();
        }));
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String entered = secret.getText().toString().trim();
            String postal = zip.getText().toString().trim();
            if (!postal.isEmpty() && !SearchRules.validZip(postal)) { zip.setError("Use a five-digit US ZIP code."); return; }
            if (!entered.isEmpty() && !SearchRules.validToken(entered)) { secret.setError("Paste only the token, without spaces or extra text."); return; }
            cancelRequest();
            SharedPreferences.Editor edit = preferences.edit().putString("zip", postal);
            if (!entered.isEmpty()) edit.putString("tmdb_token", entered);
            edit.apply();
            secret.getText().clear();
            dialog.dismiss(); intro(); query.requestFocus();
        }));
        dialog.setOnDismissListener(ignored -> secret.getText().clear());
        dialog.show();
    }

    private void addCredits(LinearLayout fields) {
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setContentDescription("The Movie Database");
        try (InputStream input = getAssets().open("tmdb_logo.png")) { logo.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(input, null)); }
        catch (Exception ignored) { }
        fields.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(36)));
        fields.addView(text("This product uses the TMDB API but is not endorsed or certified by TMDB. "
                + "Streaming availability data is provided by JustWatch. Listings may change; confirm access in the streaming service.", 12, MUTED));
        fields.addView(button("About JustWatch", () -> openWeb("https://www.justwatch.com/us")));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_TOKEN || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { toast("Choose a document from the system file picker."); return; }
        request(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 8192) throw new IllegalArgumentException();
                    output.write(buffer, 0, count);
                }
                String imported = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
                if (!SearchRules.validToken(imported)) throw new IllegalArgumentException();
                return imported;
            }
        }, imported -> {
            preferences.edit().putString("tmdb_token", imported).apply(); intro(); toast("TMDB token saved privately.");
        }, failure -> toast("Could not import a token. Choose a text file under 8 KB containing only your TMDB read token."));
    }

    private void openWeb(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            new AlertDialog.Builder(this).setTitle("Open on a phone").setMessage(url).setPositiveButton("Close", null).show();
        }
    }

    private <T> void request(Callable<T> work, Consumer<T> success, Consumer<Exception> failure) {
        cancelRequest();
        int ticket = generation;
        pending = requests.submit(() -> {
            try {
                T value = work.call();
                runOnUiThread(() -> { if (current(ticket)) success.accept(value); });
            } catch (Exception error) { runOnUiThread(() -> { if (current(ticket)) failure.accept(error); }); }
        });
    }
    private void cancelRequest() { generation++; if (pending != null) pending.cancel(true); }
    private boolean current(int ticket) { return !destroyed && !isFinishing() && generation == ticket; }
    private void showError(Exception error) {
        status.setText(error instanceof TmdbCatalog.ApiFailure ? error.getMessage()
                : "Couldn't load search data. Check the TV's internet connection and try again.");
    }
    // Android 12 and older TV remotes use this; Android 13+ uses the dispatcher registered above.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { navigateBack(); }

    private void navigateBack() {
        cancelRequest();
        if (selected != null) showResults(); else finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", query.getText().toString()); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed = true; cancelRequest(); requests.shutdownNow(); images.shutdownNow(); posters.evictAll(); super.onDestroy();
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(13);
        button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}}, new int[]{BACKGROUND, WHITE}));
        button.setBackground(background()); button.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2); space.setMargins(dp(4), dp(5), dp(4), dp(5));
        button.setLayoutParams(space); button.setOnClickListener(view -> action.run()); return button;
    }
    private StateListDrawable background() {
        StateListDrawable state = new StateListDrawable();
        for (boolean focus : new boolean[]{true, false}) {
            GradientDrawable shape = new GradientDrawable(); shape.setColor(focus ? FOCUS : 0xFF25262A); shape.setCornerRadius(dp(6));
            if (focus) shape.setStroke(dp(2), WHITE);
            state.addState(focus ? new int[]{android.R.attr.state_focused} : new int[]{}, shape);
        }
        return state;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
