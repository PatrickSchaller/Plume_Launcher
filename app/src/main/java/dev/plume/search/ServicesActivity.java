package dev.plume.search;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.plume.R;
import dev.plume.SearchStyle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A short list to start with, with the provider catalog available only when needed. */
public final class ServicesActivity extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Future<?> loading;
    private SharedPreferences preferences;
    private Set<String> draft;
    private List<ServiceChoices.Choice> choices = new ArrayList<>();
    private final Set<String> catalogIds = new HashSet<>();
    private LinearLayout tabs, body;
    private TextView status, save;
    private EditText filter;
    private View firstCard;
    private String tab = "popular";
    private boolean loaded, destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        preferences = getSharedPreferences("search", MODE_PRIVATE);
        draft = new HashSet<>(preferences.getStringSet("services", new HashSet<>()));
        FrameLayout root = SearchStyle.root(this);
        LinearLayout page = column();
        page.setPadding(dp(48), dp(27), dp(48), dp(27));
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout heading = row();
        heading.addView(label("My services", 30, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView cancel = action("Cancel", () -> finish());
        cancel.setId(R.id.services_cancel);
        heading.addView(cancel);
        save = action("Save", this::save);
        save.setId(R.id.services_save);
        save.setEnabled(false);
        heading.addView(save);
        page.addView(heading);
        page.addView(label("Choose what you use. An installed app does not confirm a subscription.", 14, true));

        tabs = row();
        tabs.setPadding(0, dp(18), 0, dp(12));
        page.addView(tabs);
        renderTabs();
        filter = new EditText(this);
        filter.setId(R.id.services_filter);
        filter.setSingleLine(true);
        filter.setTextSize(15);
        filter.setTextColor(SearchStyle.ink(this));
        filter.setHintTextColor(SearchStyle.muted(this));
        filter.setHint("Search all services");
        filter.setImeOptions(EditorInfo.IME_ACTION_DONE);
        filter.setPadding(dp(16), dp(8), dp(16), dp(8));
        filter.setBackground(SearchStyle.panel(this, 16, false));
        filter.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(filter.getWindowToken(), 0);
            if (firstCard != null) firstCard.requestFocus();
            return true;
        });
        page.addView(filter, new LinearLayout.LayoutParams(-1, dp(48)));
        status = label("Loading US services…", 13, true);
        status.setPadding(0, dp(12), 0, dp(10));
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false); scroll.setClipToPadding(false);
        body = column();
        body.setPadding(dp(8), dp(8), dp(8), dp(8)); body.setClipChildren(false);
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (value.length() > 0 && !"all".equals(tab)) { tab = "all"; renderTabs(); }
                renderChoices();
            }
            public void afterTextChanged(Editable value) { }
        });
        setContentView(root);
        load();
    }

    private void load() {
        String token = preferences.getString("tmdb_token", "");
        if (token.isEmpty()) {
            status.setText("Add your TMDB read token in Search settings to choose services.");
            return;
        }
        loading = network.submit(() -> {
            try {
                List<TmdbCatalog.Provider> providers = new TmdbCatalog(token).providers();
                Set<String> installed = installedPackages();
                runOnUiThread(() -> { if (!destroyed && !isFinishing()) showCatalog(providers, installed); });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!destroyed && !isFinishing()) status.setText("Couldn't load services. Check your token and connection, then reopen My services. Your saved choices are unchanged.");
                });
            }
        });
    }

    private Set<String> installedPackages() {
        Set<String> packages = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent apps = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo app : getPackageManager().queryIntentActivities(apps, 0))
                if (app.activityInfo != null) packages.add(app.activityInfo.packageName);
        }
        return packages;
    }

    private void showCatalog(List<TmdbCatalog.Provider> providers, Set<String> installed) {
        choices = ServiceChoices.build(providers, installed, draft);
        catalogIds.clear();
        for (ServiceChoices.Choice choice : choices) catalogIds.addAll(choice.ids);
        loaded = true;
        tab = choices.stream().anyMatch(choice -> choice.installed) ? "installed" : "popular";
        save.setEnabled(!choices.isEmpty());
        renderTabs();
        renderChoices();
    }

    private void renderTabs() {
        boolean keepFocus = tabs.hasFocus();
        tabs.removeAllViews();
        for (String key : new String[]{"installed", "popular", "selected", "all"}) {
            String title = "installed".equals(key) ? "Installed" : "popular".equals(key) ? "Popular"
                    : "selected".equals(key) ? "Selected" : "All services";
            TextView button = action(title, () -> { tab = key; renderTabs(); renderChoices(); });
            button.setTag("tab:" + key);
            button.setBackground(SearchStyle.panel(this, 18, key.equals(tab)));
            tabs.addView(button);
            if (keepFocus && key.equals(tab)) button.requestFocus();
        }
    }

    private void renderChoices() {
        if (!loaded) return;
        body.removeAllViews();
        firstCard = null;
        String term = filter.getText().toString().trim();
        List<ServiceChoices.Choice> shown = new ArrayList<>();
        for (ServiceChoices.Choice choice : choices) {
            boolean inTab = "all".equals(tab) || "installed".equals(tab) && choice.installed
                    || "popular".equals(tab) && choice.popular || "selected".equals(tab) && selected(choice);
            if (inTab && choice.matches(term)) shown.add(choice);
        }
        updateCount();
        status.setText(shown.size() + (shown.size() == 1 ? " service" : " services")
                + ("installed".equals(tab) ? " found on this TV. Select the ones you use." : " · Select to include in My services."));
        if (shown.isEmpty()) body.addView(label(term.isEmpty() ? "No services here yet. Try Popular or All services."
                : "No matching services. Try another name.", 16, true));
        GridLayout grid = new GridLayout(this);
        grid.setClipChildren(false); grid.setClipToPadding(false);
        grid.setColumnCount(3);
        int width = (getResources().getDisplayMetrics().widthPixels - dp(112)) / 3;
        // Keep a broad catalog from rebuilding hundreds of TV views on every keypress; search still covers all choices.
        for (ServiceChoices.Choice choice : shown.subList(0, Math.min(48, shown.size()))) {
            View card = card(choice);
            GridLayout.LayoutParams space = new GridLayout.LayoutParams();
            space.width = width - dp(10);
            space.height = dp(90);
            space.setMargins(dp(3), dp(4), dp(7), dp(6));
            grid.addView(card, space);
            if (firstCard == null) firstCard = card;
        }
        body.addView(grid);
        if (shown.size() > 48) body.addView(label("Showing the first 48 services. Search by name to narrow the list.", 14, true));
        Set<String> unavailable = unavailable();
        if ("selected".equals(tab) && !unavailable.isEmpty()) {
            body.addView(label(unavailable.size() + " saved selections are no longer in the US catalog. They will be kept unless you remove them.", 14, true));
            body.addView(action("Remove unavailable selections", () -> {
                draft.removeAll(unavailable);
                renderChoices();
            }));
        }
    }

    private View card(ServiceChoices.Choice choice) {
        LinearLayout card = row();
        card.setTag(choice.key);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        boolean hasIcon = false;
        if (choice.installed && choice.packageName != null) try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(choice.packageName));
            ColorMatrix mono = new ColorMatrix();
            mono.setSaturation(0);
            icon.setColorFilter(new ColorMatrixColorFilter(mono));
            hasIcon = true;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        if (hasIcon) card.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout words = column();
        words.setPadding(hasIcon ? dp(12) : 0, 0, dp(5), 0);
        TextView title = label(choice.name, 16, false);
        title.setMaxLines(2);
        words.addView(title);
        String detail = choice.addOn ? "Add-on channel" : choice.installed ? "Installed" : "Streaming service";
        words.addView(label(detail, 12, true));
        card.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mark = label("", 19, false);
        mark.setGravity(Gravity.CENTER);
        card.addView(mark, new LinearLayout.LayoutParams(dp(24), -2));
        updateCard(card, mark, choice);
        card.setFocusable(true);
        card.setClickable(true);
        SearchStyle.focus(card);
        card.setOnClickListener(view -> {
            if (selected(choice)) draft.removeAll(choice.ids); else draft.addAll(choice.ids);
            updateCard(card, mark, choice);
            updateCount();
        });
        return card;
    }

    private void updateCard(View card, TextView mark, ServiceChoices.Choice choice) {
        boolean selected = selected(choice);
        card.setSelected(selected);
        card.setBackground(SearchStyle.panel(this, 20, selected));
        mark.setText(selected ? "✓" : "+");
        card.setContentDescription(choice.name + (choice.installed ? ", installed" : "")
                + (choice.addOn ? ", add-on channel" : "") + (selected ? ", selected" : ", not selected"));
    }

    private boolean selected(ServiceChoices.Choice choice) {
        for (String id : choice.ids) if (draft.contains(id)) return true;
        return false;
    }

    private Set<String> unavailable() {
        Set<String> unavailable = new HashSet<>(draft);
        unavailable.removeAll(catalogIds);
        return unavailable;
    }

    private void updateCount() {
        int count = unavailable().size();
        for (ServiceChoices.Choice choice : choices) if (selected(choice)) count++;
        save.setText("Save · " + count + " selected");
    }

    private void save() {
        preferences.edit().putStringSet("services", new HashSet<>(draft)).apply();
        setResult(RESULT_OK);
        finish();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (loading != null) loading.cancel(true);
        network.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout column() { LinearLayout view = row(); view.setOrientation(LinearLayout.VERTICAL); view.setGravity(Gravity.TOP); return view; }
    private int dp(float value) { return SearchStyle.dp(this, value); }
    private TextView label(String value, int size, boolean muted) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size);
        text.setTextColor(muted ? SearchStyle.muted(this) : SearchStyle.ink(this));
        text.setPadding(0, dp(3), 0, dp(3));
        return text;
    }
    private TextView action(String title, Runnable click) {
        TextView action = label(title, 14, false);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(18), dp(9), dp(18), dp(9));
        action.setBackground(SearchStyle.panel(this, 18, false));
        action.setFocusable(true); action.setClickable(true);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, dp(42));
        space.setMargins(dp(7), 0, 0, 0);
        action.setLayoutParams(space);
        SearchStyle.focus(action);
        action.setOnClickListener(view -> click.run());
        return action;
    }
}
