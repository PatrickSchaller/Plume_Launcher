package dev.plume.search;

import org.json.JSONObject;

import java.util.List;

/** Real Android JSON parsing and rejected image paths; no network or personal token needed. */
final class ArtworkChecks {
    static int run() throws Exception {
        int checks = 0;
        List<TmdbCatalog.Title> titles = TmdbCatalog.parseTitles(new JSONObject("{\"results\":["
                + "{\"id\":1,\"media_type\":\"movie\",\"title\":\"Artwork fixture\",\"poster_path\":\"/poster.jpg\",\"backdrop_path\":\"/scene.jpg\"},"
                + "{\"id\":2,\"media_type\":\"tv\",\"name\":\"No artwork\",\"poster_path\":null},"
                + "{\"id\":3,\"media_type\":\"movie\",\"title\":\"Rejected artwork\",\"poster_path\":\"https://example.invalid/poster.jpg\",\"backdrop_path\":\"/../scene.jpg\"}]}"));
        check(titles.size() == 3, "Optional artwork must not remove title matches."); checks++;
        check("/poster.jpg".equals(titles.get(0).poster) && "/scene.jpg".equals(titles.get(0).backdrop),
                "Title artwork paths were not parsed."); checks++;
        check(titles.get(1).poster.isEmpty() && titles.get(1).backdrop.isEmpty(),
                "Missing artwork must remain empty."); checks++;
        check(titles.get(2).poster.isEmpty() && titles.get(2).backdrop.isEmpty(),
                "External or nested artwork paths must be rejected."); checks++;
        check(new TmdbCatalog.Title(1, "movie", "Legacy fixture", "", "", "").backdrop.isEmpty(),
                "Existing title constructors must keep an empty backdrop."); checks++;

        TmdbCatalog.Availability availability = TmdbCatalog.parseAvailability(new JSONObject(
                "{\"results\":{\"US\":{\"flatrate\":["
                        + "{\"provider_id\":8,\"provider_name\":\"Provider fixture\",\"logo_path\":\"/logo.png\"},"
                        + "{\"provider_id\":9,\"provider_name\":\"Invalid logo\",\"logo_path\":\"//example.invalid/logo.png\"}]}}}"));
        check("/logo.png".equals(availability.offers.get(0).provider.logo), "Provider logo was not parsed."); checks++;
        check(availability.offers.get(1).provider.logo.isEmpty(), "External provider logo must be rejected."); checks++;
        check(new TmdbCatalog.Provider(8, "Legacy provider").logo.isEmpty(),
                "Existing provider constructors must keep an empty logo."); checks++;
        check(TmdbCatalog.validImagePath("/abc_09-.jpg"), "A valid TMDB image filename was rejected."); checks++;
        for (String path : new String[]{null, "", "/..", "/.", "//host/logo.png", "/a/b.jpg", "/a.jpg?x=1", "https://host/a.jpg"}) {
            check(!TmdbCatalog.validImagePath(path), "Invalid image path was accepted."); checks++;
        }
        check(TmdbCatalog.poster(null) == null && TmdbCatalog.backdrop("/..") == null
                        && TmdbCatalog.providerLogo("https://example.invalid/logo.png") == null,
                "Invalid artwork must be rejected before a network request."); checks++;
        return checks;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
