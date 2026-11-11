package dev.plume.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** shortcut: genre/date phrases only; use a semantic service if arbitrary plot or mood requests are needed. */
final class DiscoveryQuery {
    private static final Pattern REQUEST = Pattern.compile(
            "(?:(?:show me|find|recommend|i want to watch) )?"
            + "(?:(funny|comedy|comedies|sci-fi|science fiction|action|adventure|animated|animation|family|drama|dramatic|crime|mystery|documentary|documentaries|horror|scary|thriller|romantic|romance|fantasy|western) )?"
            + "(movies|films|tv shows|shows|series)"
            + "(?: (?:from|in|released in)(?: the)? '?((?:19|20)?[0-9]{2})(s)?)?");
    final String kind, genre, label;
    final int firstYear, lastYear;

    private DiscoveryQuery(String kind, String genre, String label, int firstYear, int lastYear) {
        this.kind = kind; this.genre = genre; this.label = label; this.firstYear = firstYear; this.lastYear = lastYear;
    }

    static DiscoveryQuery parse(String text) {
        if (text == null || text.length() > 200) return null;
        Matcher match = REQUEST.matcher(text.toLowerCase(Locale.ROOT).replace('’', '\'').trim().replaceAll("\\s+", " "));
        if (!match.matches() || match.group(1) == null && match.group(3) == null) return null;
        String kind = match.group(2).equals("movies") || match.group(2).equals("films") ? "movie" : "tv";
        String word = match.group(1), genre = "", label = "";
        if (word != null) switch (word) {
            case "funny": case "comedy": case "comedies": genre = "35"; label = "Comedy"; break;
            case "sci-fi": case "science fiction": genre = kind.equals("movie") ? "878" : "10765"; label = kind.equals("movie") ? "Science fiction" : "Sci-fi & fantasy"; break;
            case "action": case "adventure": genre = kind.equals("tv") ? "10759" : word.equals("action") ? "28" : "12"; label = kind.equals("tv") ? "Action & adventure" : word.equals("action") ? "Action" : "Adventure"; break;
            case "animated": case "animation": genre = "16"; label = "Animation"; break;
            case "family": genre = "10751"; label = "Family"; break;
            case "drama": case "dramatic": genre = "18"; label = "Drama"; break;
            case "crime": genre = "80"; label = "Crime"; break;
            case "mystery": genre = "9648"; label = "Mystery"; break;
            case "documentary": case "documentaries": genre = "99"; label = "Documentary"; break;
            case "horror": case "scary": if (kind.equals("tv")) return null; genre = "27"; label = "Horror"; break;
            case "thriller": if (kind.equals("tv")) return null; genre = "53"; label = "Thriller"; break;
            case "romantic": case "romance": if (kind.equals("tv")) return null; genre = "10749"; label = "Romance"; break;
            case "fantasy": genre = kind.equals("movie") ? "14" : "10765"; label = kind.equals("movie") ? "Fantasy" : "Sci-fi & fantasy"; break;
            case "western": genre = "37"; label = "Western"; break;
            default: return null;
        }
        int first = 0, last = 0;
        if (match.group(3) != null) {
            String year = match.group(3); boolean decade = match.group(4) != null;
            if (year.length() == 2 && !decade) return null;
            first = Integer.parseInt(year);
            if (year.length() == 2) first += first <= 20 ? 2000 : 1900;
            if (decade && first % 10 != 0) return null;
            last = first + (decade ? 9 : 0);
        }
        label += (label.isEmpty() ? "" : " ") + (kind.equals("movie") ? "movies" : "series");
        if (first > 0) label += " · " + first + (last == first ? "" : "–" + last);
        return new DiscoveryQuery(kind, genre, label, first, last);
    }

    String[] parameters() {
        List<String> parameters = new ArrayList<>(Arrays.asList("include_adult", "false", "sort_by", "popularity.desc"));
        if (!genre.isEmpty()) parameters.addAll(Arrays.asList("with_genres", genre));
        if (firstYear > 0) {
            String date = kind.equals("movie") ? "primary_release_date" : "first_air_date";
            parameters.addAll(Arrays.asList(date + ".gte", firstYear + "-01-01", date + ".lte", lastYear + "-12-31"));
        }
        return parameters.toArray(new String[0]);
    }
}
