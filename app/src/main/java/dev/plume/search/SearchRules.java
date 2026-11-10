package dev.plume.search;

import java.util.Set;

/** Small rules shared by the search UI and its offline checks. */
public final class SearchRules {
    private SearchRules() { }

    public static boolean validZip(String zip) { return zip != null && zip.matches("[0-9]{5}"); }

    public static boolean validToken(String token) {
        if (token == null || token.isEmpty() || token.length() > 4096) return false;
        return token.matches("[A-Za-z0-9._~+/-]+=*");
    }

    public static boolean showProvider(int id, boolean onlyMine, Set<String> services) {
        return !onlyMine || services.contains(Integer.toString(id));
    }

    public static String offerLabel(String kind) {
        switch (kind) {
            case "flatrate": return "Subscription";
            case "free": return "Free";
            case "ads": return "Free with ads";
            case "rent": return "Rent";
            case "buy": return "Buy";
            default: return "Other offer";
        }
    }

    public static String packageForProvider(String name) {
        return ServiceChoices.packageForProvider(name);
    }
}
