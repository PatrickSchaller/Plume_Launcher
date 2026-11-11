package dev.plume.search;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiscoveryQueryTest {
    @Test public void interpretsGenreAndDecadeWithoutChangingTitleSearch() {
        DiscoveryQuery query = DiscoveryQuery.parse("Show me funny movies from the ’90s");
        assertEquals("movie", query.kind); assertEquals("35", query.genre);
        assertEquals(1990, query.firstYear); assertEquals(1999, query.lastYear);
        assertArrayEquals(new String[]{"include_adult", "false", "sort_by", "popularity.desc", "with_genres", "35",
                "primary_release_date.gte", "1990-01-01", "primary_release_date.lte", "1999-12-31"}, query.parameters());
        for (String title : new String[]{"Scary Movie", "Funny People", "Show Me Love", "The '90s", "Comedy movies with dogs", "movies from 95", "movies from 1995s", "horror shows", "movies", "", "x".repeat(201)})
            assertNull(title, DiscoveryQuery.parse(title));
        assertNull(DiscoveryQuery.parse(null));
    }
    @Test public void usesTvGenresAndFirstAirDate() {
        DiscoveryQuery query = DiscoveryQuery.parse("  sci-fi   shows from 2020 ");
        assertEquals("tv", query.kind); assertEquals("10765", query.genre);
        assertEquals(2020, query.firstYear); assertEquals(2020, query.lastYear);
        assertArrayEquals(new String[]{"include_adult", "false", "sort_by", "popularity.desc", "with_genres", "10765",
                "first_air_date.gte", "2020-01-01", "first_air_date.lte", "2020-12-31"}, query.parameters());
        assertEquals("10759", DiscoveryQuery.parse("action shows").genre);
        assertEquals("28", DiscoveryQuery.parse("action movies").genre);
        assertEquals(2000, DiscoveryQuery.parse("movies from the 00s").firstYear);
        assertEquals(1980, DiscoveryQuery.parse("movies from the 1980s").firstYear);
        assertEquals(0, DiscoveryQuery.parse("family movies").firstYear);
    }
}
