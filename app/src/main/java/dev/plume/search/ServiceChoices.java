package dev.plume.search;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Recognizable service choices; installation is a suggestion, never subscription evidence. */
final class ServiceChoices {
    private ServiceChoices() { }

    static List<Choice> build(List<TmdbCatalog.Provider> providers, Set<String> installedPackages,
                              Set<String> selectedIds) {
        Map<String, Choice> grouped = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (TmdbCatalog.Provider provider : providers) {
            if (provider.id <= 0 || provider.name == null || provider.name.trim().isEmpty() || !seen.add(provider.id)) continue;
            String family = family(provider.name);
            String key = family == null ? "provider:" + provider.id : family;
            Choice choice = grouped.get(key);
            if (choice == null) {
                String pkg = installedPackage(provider.name, installedPackages);
                choice = new Choice(key, family == null ? provider.name : displayName(family),
                        pkg == null ? packageForProvider(provider.name) : pkg,
                        pkg != null, family != null, isAddOn(provider.name));
                grouped.put(key, choice);
            }
            String id = Integer.toString(provider.id);
            choice.ids.add(id);
            choice.selected |= selectedIds.contains(id);
            choice.searchNames.append(' ').append(normalize(provider.name));
        }
        List<Choice> choices = new ArrayList<>(grouped.values());
        Collator order = Collator.getInstance();
        choices.sort((a, b) -> {
            int category = Integer.compare(a.installed ? 0 : a.popular ? 1 : 2, b.installed ? 0 : b.popular ? 1 : 2);
            return category != 0 ? category : order.compare(a.name, b.name);
        });
        return choices;
    }

    static String packageForProvider(String name) {
        if (normalize(name).equals("appletv")) return "com.apple.atve.androidtv.appletv";
        String[] packages = packages(family(name));
        return packages.length == 0 ? null : packages[0];
    }

    static String installedPackage(String name, Set<String> installedPackages) {
        for (String pkg : packages(family(name))) if (installedPackages.contains(pkg)) return pkg;
        return null;
    }

    static boolean isAddOn(String name) {
        String normalized = normalize(name);
        return normalized.contains("amazonchannel") || normalized.contains("appletvchannel")
                || normalized.contains("rokupremiumchannel")
                || (normalized.endsWith("rokuchannel") && !normalized.equals("therokuchannel"));
    }

    private static String family(String name) {
        if (isAddOn(name)) return null;
        switch (normalize(name)) {
            case "netflix": case "netflixstandardwithads": return "netflix";
            case "amazonprimevideo": case "amazonprimevideowithads": case "primevideo": return "prime";
            case "disneyplus": return "disney";
            case "hulu": return "hulu";
            case "appletvplus": return "apple";
            case "paramountplus": case "paramountplusessential": case "paramountpluspremium":
            case "paramountpluswithshowtime": return "paramount";
            case "max": case "hbomax": return "max";
            case "peacock": case "peacockpremium": case "peacockpremiumplus": return "peacock";
            case "crunchyroll": return "crunchyroll";
            case "espn": case "espnplus": return "espn";
            default: return null;
        }
    }

    private static String displayName(String family) {
        switch (family) {
            case "netflix": return "Netflix";
            case "prime": return "Prime Video";
            case "disney": return "Disney+";
            case "hulu": return "Hulu";
            case "apple": return "Apple TV+";
            case "paramount": return "Paramount+";
            case "max": return "HBO Max";
            case "peacock": return "Peacock";
            case "crunchyroll": return "Crunchyroll";
            case "espn": return "ESPN";
            default: return family;
        }
    }

    private static String[] packages(String family) {
        if (family == null) return new String[0];
        switch (family) {
            case "netflix": return new String[]{"com.netflix.ninja", "com.netflix.mediaclient"};
            case "prime": return new String[]{"com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"};
            case "disney": return new String[]{"com.disney.disneyplus"};
            case "hulu": return new String[]{"com.hulu.livingroomplus", "com.hulu.plus"};
            case "apple": return new String[]{"com.apple.atve.androidtv.appletv"};
            case "paramount": return new String[]{"com.cbs.ott"};
            case "max": return new String[]{"com.wbd.stream", "com.wbd.hbomax"};
            case "peacock": return new String[]{"com.peacocktv.peacockandroid"};
            case "crunchyroll": return new String[]{"com.crunchyroll.crunchyroid"};
            case "espn": return new String[]{"com.espn.score_center"};
            default: return new String[0];
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("+", "plus").replaceAll("[^a-z0-9]", "");
    }

    static final class Choice {
        final String key, name, packageName;
        final Set<String> ids = new LinkedHashSet<>();
        final boolean installed, popular, addOn;
        boolean selected;
        private final StringBuilder searchNames = new StringBuilder();

        private Choice(String key, String name, String packageName, boolean installed, boolean popular, boolean addOn) {
            this.key = key; this.name = name; this.packageName = packageName;
            this.installed = installed; this.popular = popular; this.addOn = addOn;
        }

        boolean matches(String query) {
            String term = normalize(query);
            return normalize(name).contains(term) || searchNames.indexOf(term) >= 0;
        }
    }
}
