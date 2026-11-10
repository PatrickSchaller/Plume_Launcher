package dev.plume.search;

import dev.plume.R;
import dev.plume.SearchStyle;
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
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
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
    private int BACKGROUND, WHITE, MUTED, FOCUS;
    private static final int IMPORT_TOKEN = 41, EDIT_SERVICES = 42;
    private final ExecutorService requests = Executors.newFixedThreadPool(2);
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private SharedPreferences preferences;
    private EditText query;
    private CheckBox onlyMine;
    private Button everywhere, myServices;
    private LinearLayout content;
    private TextView status;
    private List<TmdbCatalog.Title> titles = new ArrayList<>();
    private TmdbCatalog.Title selected;
    private TmdbCatalog.Availability availability;
    private long availabilityCheckedAt;
    private int season = -1, lastTitleId;
    private volatile int generation;
    private volatile boolean destroyed;
    private Future<?> pending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        preferences = getSharedPreferences("search", MODE_PRIVATE);
        WHITE = SearchStyle.ink(this); MUTED = SearchStyle.muted(this);
        BACKGROUND = SearchStyle.dark(this) ? Color.rgb(13, 15, 19) : Color.rgb(240, 242, 245);
        FOCUS = WHITE;
        FrameLayout root = SearchStyle.root(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout page = column(); page.setPadding(dp(32), dp(16), dp(32), dp(20));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("Search", 28, WHITE); heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("My services", this::chooseServices));
        header.addView(button("Settings", this::showSettings));
        page.addView(header);
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(10), 0, dp(6));
        query = new EditText(this); query.setSingleLine(true);
        query.setHint("Movie or TV show"); query.setTextColor(WHITE); query.setHintTextColor(MUTED); query.setTextSize(19);
        query.setPadding(dp(18), 0, dp(18), 0); query.setBackground(SearchStyle.panel(this, 16f, false));
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        query.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || event != null && event.getAction() == android.view.KeyEvent.ACTION_UP
                    && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) { search(); return true; }
            return false;
        });
        controls.addView(query, new LinearLayout.LayoutParams(0, dp(44), 1));
        controls.addView(button("Search", this::search)); page.addView(controls);
        LinearLayout filters = new LinearLayout(this); filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.addView(text("Viewing options", 13, MUTED));
        everywhere = button("Everywhere", () -> onlyMine.setChecked(false));
        myServices = button("My services", () -> onlyMine.setChecked(true));
        filters.addView(everywhere); filters.addView(myServices);
        View spacer = new View(this); filters.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        filters.addView(button("Antenna guide", this::openAntenna)); page.addView(filters);
        onlyMine = new CheckBox(this);
        onlyMine.setChecked(preferences.getBoolean("only_my_services", false));
        onlyMine.setOnCheckedChangeListener((view, checked) -> {
            preferences.edit().putBoolean("only_my_services", checked).apply(); updateFilters();
            if (selected != null && availability != null) renderAvailability();
        });
        updateFilters();
        status = text("", 13, MUTED); status.setPadding(0, dp(8), 0, dp(6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); page.addView(status);
        content = column(); scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1)); setContentView(root);
        if (state != null) query.setText(state.getString("query", ""));
        intro();
        if (getIntent().getBooleanExtra("open_settings", false)) showSettings();
    }

    private String token() { return preferences.getString("tmdb_token", ""); }
    private TmdbCatalog catalog() { return new TmdbCatalog(token()); }
    private Set<String> services() { return new HashSet<>(preferences.getStringSet("services", new HashSet<>())); }
    private void updateFilters() {
        everywhere.setSelected(!onlyMine.isChecked()); myServices.setSelected(onlyMine.isChecked());
        everywhere.setBackground(SearchStyle.panel(this, 18f, !onlyMine.isChecked()));
        myServices.setBackground(SearchStyle.panel(this, 18f, onlyMine.isChecked()));
    }
    private void openAntenna() {
        startActivity(new Intent(this, LocalTvActivity.class).putExtra("query", selected == null ? query.getText().toString().trim() : selected.name));
    }
    private void intro() {
        selected = null; availability = null; titles.clear(); content.removeAllViews();
        status.setText(token().isEmpty() ? "Set up TMDB in Settings to start searching." : "Movies and shows · United States");
        LinearLayout empty = column(); empty.setPadding(dp(26), dp(25), dp(26), dp(25));
        empty.setBackground(SearchStyle.panel(this, 24f, false));
        empty.addView(text("Find a movie or show", 26, WHITE));
        empty.addView(text("See your subscription options, free services and rentals together.", 16, MUTED));
        empty.addView(button(token().isEmpty() ? "Set up search" : "Choose your services", token().isEmpty() ? this::showSettings : this::chooseServices));
        content.addView(empty);
    }
    private void search() {
        String term = query.getText().toString().trim();
        if (term.isEmpty()) { query.setError("Enter a movie or TV show."); query.requestFocus(); return; }
        if (token().isEmpty()) { showSettings(); return; }
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
        selected = null; availability = null; lastTitleId = 0; titles.clear(); content.removeAllViews(); status.setText("Searching…");
        TmdbCatalog api = catalog(); request(() -> api.search(term), result -> { titles = result; showResults(); }, this::showError);
    }
    private void showResults() {
        selected = null; content.removeAllViews();
        status.setText(titles.isEmpty() ? "No matches. Try another title." : titles.size() + " matches · Choose a title");
        GridLayout grid = new GridLayout(this); int columns = columns(160, 5); grid.setColumnCount(columns);
        grid.setClipChildren(false); grid.setClipToPadding(false);
        int width = tileWidth(columns); View first = null, previous = null;
        List<View> cards = new ArrayList<>();
        int ticket = generation;
        for (TmdbCatalog.Title title : titles) {
            LinearLayout card = column(); card.setId(View.generateViewId()); card.setFocusable(true); card.setClickable(true);
            card.setPadding(dp(5), dp(5), dp(5), dp(10)); card.setBackground(background()); card.setClipToOutline(true);
            card.setContentDescription(title.name + ", " + title.subtitle() + ". Show viewing options.");
            FrameLayout art = new FrameLayout(this); art.setBackgroundColor(0xFF25282D);
            TextView missing = text("tv".equals(title.kind) ? "Series" : "Film", 18, 0xFFE2E5EA);
            missing.setGravity(Gravity.CENTER); art.addView(missing, new FrameLayout.LayoutParams(-1, -1));
            ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            card.addView(art, new LinearLayout.LayoutParams(-1, dp((width - 10) * 3 / 2)));
            TextView name = text(title.name, 16, WHITE); name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setMaxLines(2); name.setMinLines(2); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(dp(8), dp(10), dp(8), 0); card.addView(name);
            TextView meta = text(title.subtitle(), 12, MUTED); meta.setPadding(dp(8), dp(3), dp(8), 0); card.addView(meta);
            card.setOnClickListener(view -> showAvailability(title, -1)); SearchStyle.focus(card);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = dp(width); lp.height = -2;
            lp.setMargins(0, 0, dp(14), dp(16)); grid.addView(card, lp); cards.add(card);
            if (first == null) first = card; if (title.id == lastTitleId) previous = card;
            loadImage(poster, "poster:" + title.poster, () -> TmdbCatalog.poster(title.poster), ticket);
        }
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            if (i % columns > 0) card.setNextFocusLeftId(cards.get(i - 1).getId());
            if (i % columns < columns - 1 && i + 1 < cards.size()) card.setNextFocusRightId(cards.get(i + 1).getId());
            if (i >= columns) card.setNextFocusUpId(cards.get(i - columns).getId());
            if (i + columns < cards.size()) card.setNextFocusDownId(cards.get(i + columns).getId());
        }
        content.addView(grid);
        if (previous != null) previous.requestFocus(); else if (first != null) first.requestFocus();
    }
    private void showAvailability(TmdbCatalog.Title title, int seasonNumber) {
        selected = title; lastTitleId = title.id; season = seasonNumber; availability = null;
        content.removeAllViews(); status.setText("Checking viewing options…");
        TmdbCatalog api = catalog(); request(() -> api.availability(title, seasonNumber), result -> {
            availability = result; availabilityCheckedAt = System.currentTimeMillis(); renderAvailability();
        }, this::showError);
        addHero(title);
    }
    private void addHero(TmdbCatalog.Title title) {
        FrameLayout hero = new FrameLayout(this); hero.setBackground(SearchStyle.panel(this, 24f, false)); hero.setClipToOutline(true);
        hero.setFocusable(true); hero.setContentDescription(title.name + ", " + title.subtitle());
        ImageView backdrop = new ImageView(this); backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF20A0C11, 0xB00A0C11, 0x800A0C11})); hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams posterSpace = new FrameLayout.LayoutParams(dp(138), dp(207));
        posterSpace.leftMargin = dp(20); posterSpace.topMargin = dp(20); hero.addView(poster, posterSpace);
        LinearLayout words = column(); TextView name = text(title.name, 30, Color.WHITE);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name); words.addView(text(title.subtitle() + (season < 0 ? "" : " · Season " + season), 14, 0xFFD3D8E0));
        TextView overview = text(title.overview.isEmpty() ? "Viewing options below." : title.overview, 15, 0xFFE3E6EB);
        overview.setMaxLines(4); overview.setEllipsize(TextUtils.TruncateAt.END); words.addView(overview);
        FrameLayout.LayoutParams wordSpace = new FrameLayout.LayoutParams(-1, -2); wordSpace.leftMargin = dp(182); wordSpace.rightMargin = dp(28); wordSpace.topMargin = dp(20);
        hero.addView(words, wordSpace); content.addView(hero, new LinearLayout.LayoutParams(-1, dp(248)));
        hero.requestFocus();
        int ticket = generation;
        loadImage(poster, "poster:" + title.poster, () -> TmdbCatalog.poster(title.poster), ticket);
        loadImage(backdrop, "backdrop:" + title.backdrop, () -> TmdbCatalog.backdrop(title.backdrop), ticket);
    }
    private void renderAvailability() {
        content.removeAllViews(); status.setText("Where to watch · United States"); addHero(selected);
        LinearLayout actions = new LinearLayout(this); actions.setPadding(0, dp(12), 0, dp(5));
        if ("tv".equals(selected.kind)) actions.addView(button(season < 0 ? "Choose season" : "Season " + season, this::chooseSeason));
        actions.addView(button("Antenna airings", this::openAntenna));
        actions.addView(button("Back to results", this::showResults)); content.addView(actions);
        Set<String> mine = services(); boolean filtered = onlyMine.isChecked(); int shown = 0; View first = null;
        String[][] groups = {{"Subscription", "flatrate"}, {"Free to watch", "free", "ads"}, {"Rent or buy", "rent", "buy"}};
        for (String[] group : groups) {
            List<TmdbCatalog.Offer> offers = new ArrayList<>();
            for (TmdbCatalog.Offer offer : availability.offers) {
                boolean kind = false; for (int i = 1; i < group.length; i++) kind |= group[i].equals(offer.kind);
                if (kind && SearchRules.showProvider(offer.provider.id, filtered, mine)) offers.add(offer);
            }
            if (offers.isEmpty()) continue;
            TextView label = text(group[0], 19, WHITE); label.setPadding(0, dp(16), 0, dp(9)); content.addView(label);
            if ("Free to watch".equals(group[0])) content.addView(text("May need a free account. Ads may apply.", 13, MUTED));
            GridLayout grid = new GridLayout(this); int columns = columns(205, 4); grid.setColumnCount(columns);
            int width = tileWidth(columns);
            for (TmdbCatalog.Offer offer : offers) {
                LinearLayout card = new LinearLayout(this); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(14), dp(12), dp(12), dp(12));
                card.setBackground(background()); card.setFocusable(true); card.setClickable(true);
                Intent launch = providerIntent(offer.provider.name);
                ImageView logo = new ImageView(this); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); card.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
                LinearLayout words = column(); words.setPadding(dp(12), 0, 0, 0);
                TextView provider = text(offer.provider.name, 15, WHITE); provider.setMaxLines(2); provider.setEllipsize(TextUtils.TruncateAt.END); words.addView(provider);
                words.addView(text(SearchRules.offerLabel(offer.kind) + (launch == null ? " · Details" : " · Open app"), 11, MUTED));
                card.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                card.setContentDescription(offer.provider.name + ", " + SearchRules.offerLabel(offer.kind) + ". " + (launch == null ? "View availability details" : "Open app"));
                card.setOnClickListener(view -> { if (launch != null) openApp(launch); else if (!availability.link.isEmpty()) openWeb(availability.link);
                    else toast("Open this service to confirm access."); });
                SearchStyle.focus(card); GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = dp(width); lp.height = dp(88);
                lp.setMargins(0, 0, dp(14), dp(12)); grid.addView(card, lp);
                loadImage(logo, "logo:" + offer.provider.logo, () -> TmdbCatalog.providerLogo(offer.provider.logo), generation);
                if (first == null) first = card; shown++;
            }
            content.addView(grid);
        }
        if (shown == 0) {
            content.addView(text(!availability.hasUs ? "No US listing data. Check the service directly."
                    : filtered ? "No matches on your selected services." : "No viewing options listed yet.", 19, WHITE));
            if (filtered) content.addView(button("Show everywhere", () -> onlyMine.setChecked(false)));
            content.addView(button("Edit my services", this::chooseServices));
        }
        content.addView(text("App buttons open the service, not this exact title. Confirm your plan and access there.", 12, MUTED));
        if (!availability.link.isEmpty()) content.addView(button("More viewing details", () -> openWeb(availability.link)));
        content.addView(text("JustWatch / TMDB · Updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(availabilityCheckedAt)), 11, MUTED));
    }
    private int availableWidth() { return Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density) - 64; }
    private int columns(int minimumWidth, int maximum) { return Math.max(2, Math.min(maximum, availableWidth() / minimumWidth)); }
    private int tileWidth(int columns) { return (availableWidth() - 14 * columns) / columns; }
    private void loadImage(ImageView view, String key, Callable<Bitmap> work, int ticket) {
        if (key.endsWith(":")) return;
        Bitmap cached = posters.get(key); if (cached != null) { view.setImageBitmap(cached); return; }
        images.execute(() -> {
            if (!current(ticket)) return;
            try { Bitmap bitmap = work.call(); if (bitmap == null) return; posters.put(key, bitmap);
                runOnUiThread(() -> { if (current(ticket)) view.setImageBitmap(bitmap); });
            } catch (Exception ignored) { /* A title stays usable if its artwork is unavailable. */ }
        });
    }
    private Intent providerIntent(String name) {
        String packageName = SearchRules.packageForProvider(name);
        return packageName == null ? null : getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
    }
    private void openApp(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException unavailable) { toast("This app is unavailable. Open it from Apps."); }
    }
    private void chooseSeason() {
        TmdbCatalog.Title title = selected; TmdbCatalog api = catalog(); status.setText("Loading seasons…");
        request(() -> api.seasons(title), seasons -> {
            status.setText("Choose a season"); String[] names = new String[seasons.size() + 1]; names[0] = "Whole series";
            for (int i = 0; i < seasons.size(); i++) names[i + 1] = seasons.get(i).name;
            new AlertDialog.Builder(this, SearchStyle.dark(this) ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Season").setItems(names, (dialog, index) -> showAvailability(title, index == 0 ? -1 : seasons.get(index - 1).number))
                    .setNegativeButton("Cancel", null).show();
        }, this::showError);
    }
    private void chooseServices() {
        if (token().isEmpty()) { showSettings(); return; }
        startActivityForResult(new Intent(this, ServicesActivity.class), EDIT_SERVICES);
    }
    private void restoreContent() {
        if (selected != null && availability != null) renderAvailability();
        else if (selected != null) showAvailability(selected, season);
        else if (!titles.isEmpty()) showResults(); else intro();
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
        AlertDialog dialog = new AlertDialog.Builder(this, SearchStyle.dark(this) ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Search settings").setView(scroll)
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
        if (requestCode == EDIT_SERVICES && resultCode == RESULT_OK) { restoreContent(); return; }
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
            new AlertDialog.Builder(this, SearchStyle.dark(this) ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Open on a phone").setMessage(url).setPositiveButton("Close", null).show();
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
        button.setTextColor(WHITE); button.setMinimumHeight(0); button.setMinHeight(dp(32));
        button.setBackground(background()); button.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2); space.setMargins(dp(4), dp(3), dp(4), dp(3));
        button.setLayoutParams(space); button.setOnClickListener(view -> action.run()); SearchStyle.focus(button); return button;
    }
    private StateListDrawable background() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_focused}, SearchStyle.panel(this, 16f, true));
        state.addState(new int[]{}, SearchStyle.panel(this, 16f, false));
        return state;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
