package dev.plume.search;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Requests are made only on demand; the bearer token never enters a URL. */
final class TmdbCatalog {
    private final String token;
    TmdbCatalog(String token) { this.token = token; }

    List<Title> search(String query) throws IOException, JSONException {
        return parseTitles(get("search/multi", "query", query, "include_adult", "false"));
    }

    Availability availability(Title title, int season) throws IOException, JSONException {
        String path = title.kind + "/" + title.id;
        if ("tv".equals(title.kind) && season >= 0) path += "/season/" + season;
        return parseAvailability(get(path + "/watch/providers"));
    }

    List<Provider> providers() throws IOException, JSONException {
        Map<Integer, Provider> unique = new LinkedHashMap<>();
        for (String kind : new String[]{"movie", "tv"}) {
            JSONArray data = get("watch/providers/" + kind, "watch_region", "US").optJSONArray("results");
            if (data == null) continue;
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.optJSONObject(i);
                if (row == null) continue;
                Provider provider = parseProvider(row);
                if (provider.id > 0 && !provider.name.isEmpty()) unique.put(provider.id, provider);
            }
        }
        List<Provider> result = new ArrayList<>(unique.values());
        Collator order = Collator.getInstance();
        result.sort((a, b) -> order.compare(a.name, b.name));
        return result;
    }

    List<Season> seasons(Title title) throws IOException, JSONException {
        List<Season> result = new ArrayList<>();
        JSONArray data = get("tv/" + title.id).optJSONArray("seasons");
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject season = data.optJSONObject(i);
            if (season == null) continue;
            int number = season.optInt("season_number", -1);
            if (number >= 0) result.add(new Season(number, season.optString("name", "Season " + number)));
        }
        return result;
    }

    static List<Title> parseTitles(JSONObject response) throws JSONException {
        List<Title> titles = new ArrayList<>();
        JSONArray results = response.optJSONArray("results");
        if (results == null) return titles;
        for (int i = 0; i < results.length() && titles.size() < 10; i++) {
            JSONObject row = results.optJSONObject(i);
            if (row == null) continue;
            String kind = row.optString("media_type");
            if (!"movie".equals(kind) && !"tv".equals(kind)) continue;
            int id = positiveId(row, "id");
            String name = string(row, "movie".equals(kind) ? "title" : "name");
            if (id <= 0 || name.isEmpty()) continue;
            String date = string(row, "movie".equals(kind) ? "release_date" : "first_air_date");
            titles.add(new Title(id, kind, name, date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? date.substring(0, 4) : "",
                    string(row, "overview"), string(row, "poster_path")));
        }
        return titles;
    }

    static Availability parseAvailability(JSONObject response) throws JSONException {
        List<Offer> offers = new ArrayList<>();
        JSONObject regions = response.optJSONObject("results");
        JSONObject us = regions == null ? null : regions.optJSONObject("US");
        if (us == null) return new Availability(offers, "", false);
        for (String kind : new String[]{"flatrate", "free", "ads", "rent", "buy"}) {
            JSONArray rows = us.optJSONArray(kind);
            if (rows == null) continue;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Provider provider = parseProvider(row);
                if (provider.id > 0 && !provider.name.isEmpty()) offers.add(new Offer(provider, kind));
            }
        }
        String link = string(us, "link");
        Uri uri = Uri.parse(link);
        if (!"https".equals(uri.getScheme()) || !"www.themoviedb.org".equals(uri.getHost())) link = "";
        return new Availability(offers, link, true);
    }

    private static Provider parseProvider(JSONObject row) {
        return new Provider(positiveId(row, "provider_id"), string(row, "provider_name"));
    }

    private static String string(JSONObject row, String key) {
        Object value = row.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static int positiveId(JSONObject row, String key) {
        Object value = row.opt(key);
        if (!(value instanceof Number)) return 0;
        Number number = (Number) value;
        int id = number.intValue();
        return id > 0 && number.doubleValue() == id ? id : 0;
    }

    private JSONObject get(String path, String... parameters) throws IOException, JSONException {
        Uri.Builder uri = Uri.parse("https://api.themoviedb.org/3/" + path).buildUpon()
                .appendQueryParameter("language", "en-US");
        for (int i = 0; i < parameters.length; i += 2) uri.appendQueryParameter(parameters[i], parameters[i + 1]);
        HttpURLConnection connection = null;
        try {
            connection = open(uri.toString());
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new ApiFailure("TMDB rejected the token. Check Search settings.");
            if (status == 429) throw new ApiFailure("TMDB is busy with too many requests. Wait a moment and try again.");
            if (status != 200) throw new ApiFailure("TMDB could not complete this request. Try again later.");
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(read(input, 3 * 1024 * 1024), StandardCharsets.UTF_8));
            }
        } catch (SocketTimeoutException timeout) {
            throw new ApiFailure("The search service took too long. Please try again.");
        } finally { if (connection != null) connection.disconnect(); }
    }

    static Bitmap poster(String path) throws IOException {
        if (!path.matches("/[A-Za-z0-9._-]+")) return null;
        HttpURLConnection connection = open("https://image.tmdb.org/t/p/w185" + path);
        try {
            if (connection.getResponseCode() != 200) return null;
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) { bytes = read(input, 1024 * 1024); }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 10000 || options.outHeight > 10000) return null;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 400 || options.outHeight / options.inSampleSize > 600) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled.");
            if (output.size() + count > limit) throw new IOException("The search response was too large. Please try again.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static final class Title {
        final int id;
        final String kind, name, year, overview, poster;
        Title(int id, String kind, String name, String year, String overview, String poster) {
            this.id = id; this.kind = kind; this.name = name; this.year = year; this.overview = overview; this.poster = poster;
        }
        String subtitle() { return ("tv".equals(kind) ? "TV series" : "Movie") + (year.isEmpty() ? "" : " · " + year); }
    }
    static final class Provider {
        final int id; final String name;
        Provider(int id, String name) { this.id = id; this.name = name; }
    }
    static final class Offer {
        final Provider provider; final String kind;
        Offer(Provider provider, String kind) { this.provider = provider; this.kind = kind; }
    }
    static final class Availability {
        final List<Offer> offers; final String link; final boolean hasUs;
        Availability(List<Offer> offers, String link, boolean hasUs) { this.offers = offers; this.link = link; this.hasUs = hasUs; }
    }
    static final class Season {
        final int number; final String name;
        Season(int number, String name) { this.number = number; this.name = name; }
    }
    static final class ApiFailure extends IOException {
        ApiFailure(String message) { super(message); }
    }
}
