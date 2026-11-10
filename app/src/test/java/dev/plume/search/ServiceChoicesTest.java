package dev.plume.search;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public final class ServiceChoicesTest {
    @Test public void appleStoreIsSeparateFromAppleSubscription() {
        List<ServiceChoices.Choice> choices = ServiceChoices.build(Arrays.asList(provider(1, "Apple TV"), provider(2, "Apple TV Plus")),
                Collections.emptySet(), Collections.singleton("2"));
        assertEquals(2, choices.size());
        assertEquals(Collections.singleton("2"), choices.get(0).ids);
        assertTrue(choices.get(0).selected);
        assertFalse(choices.get(1).selected);
        assertFalse(choices.get(1).popular);
        assertEquals("com.apple.atve.androidtv.appletv", SearchRules.packageForProvider("Apple TV"));
    }
    @Test public void groupsDirectTiersWithoutAssumingAnInstalledSubscription() {
        List<ServiceChoices.Choice> choices = ServiceChoices.build(Arrays.asList(
                provider(8, "Netflix"), provider(9, "Netflix Standard with Ads"),
                provider(10, "Hulu")), Collections.singleton("com.netflix.ninja"), Collections.emptySet());
        assertEquals(2, choices.size());
        ServiceChoices.Choice netflix = choices.get(0);
        assertEquals("Netflix", netflix.name);
        assertEquals(new HashSet<>(Arrays.asList("8", "9")), netflix.ids);
        assertTrue(netflix.installed);
        assertFalse(netflix.selected);
        assertEquals("com.netflix.ninja", netflix.packageName);
    }

    @Test public void directSubscriptionsNeverIncludeThirdPartyChannels() {
        List<ServiceChoices.Choice> choices = ServiceChoices.build(Arrays.asList(
                provider(1, "Paramount Plus"), provider(2, "Paramount+ Amazon Channel"),
                provider(3, "Paramount Plus Apple TV Channel"), provider(4, "Paramount+ Roku Premium Channel")),
                Collections.singleton("com.cbs.ott"), Collections.singleton("2"));
        assertEquals(4, choices.size());
        assertEquals("Paramount+", choices.get(0).name);
        assertFalse(choices.get(0).selected);
        for (ServiceChoices.Choice choice : choices) if (choice.addOn) {
            assertFalse(choice.installed);
            assertFalse(choice.popular);
            assertNull(choice.packageName);
        }
        assertNull(SearchRules.packageForProvider("HBO Max Amazon Channel"));
        assertNull(SearchRules.packageForProvider("Crunchyroll Amazon Channel"));
        assertFalse(ServiceChoices.isAddOn("The Roku Channel"));
    }

    @Test public void preservesSavedSelectionsAndOrdersInstalledBeforePopularBeforeOther() {
        Set<String> saved = new HashSet<>(Arrays.asList("6", "absent"));
        List<ServiceChoices.Choice> choices = ServiceChoices.build(Arrays.asList(
                provider(6, "Unknown actual catalog service"), provider(7, "Hulu"),
                provider(8, "Peacock Premium")), Collections.singleton("com.peacocktv.peacockandroid"), saved);
        assertEquals("Peacock", choices.get(0).name);
        assertEquals("Hulu", choices.get(1).name);
        assertTrue(choices.get(2).selected);
        assertEquals(new HashSet<>(Arrays.asList("6", "absent")), saved);
        assertFalse(choices.get(2).installed);
        assertFalse(choices.get(2).popular);
    }

    @Test public void recognizesVerifiedAppAliasesWithoutGuessingUnknownProviderNames() {
        assertEquals("com.wbd.stream", SearchRules.packageForProvider("HBO Max"));
        assertEquals("com.wbd.hbomax", ServiceChoices.installedPackage("Max", Collections.singleton("com.wbd.hbomax")));
        assertEquals("com.crunchyroll.crunchyroid", SearchRules.packageForProvider("Crunchyroll"));
        assertEquals("com.espn.score_center", SearchRules.packageForProvider("ESPN Plus"));
        assertNull(SearchRules.packageForProvider("Max Amazon"));
        assertNull(SearchRules.packageForProvider("Netflix and other services"));
        assertNull(SearchRules.packageForProvider(null));
        assertNull(ServiceChoices.installedPackage("Hulu", Collections.singleton("unrelated.app")));
    }

    @Test public void usesOnlyActualCatalogIdsAndSearchesOriginalNamesAndCanonicalNames() {
        List<ServiceChoices.Choice> choices = ServiceChoices.build(Arrays.asList(
                provider(7, "Amazon Prime Video"), provider(8, "Amazon Prime Video with Ads"),
                provider(8, "Duplicate id"), provider(0, "Invalid"), provider(9, " ")),
                Collections.emptySet(), Collections.singleton("8"));
        assertEquals(1, choices.size());
        ServiceChoices.Choice prime = choices.get(0);
        assertEquals(new HashSet<>(Arrays.asList("7", "8")), prime.ids);
        assertTrue(prime.selected);
        assertTrue(prime.matches("amazon"));
        assertTrue(prime.matches("prime video"));
        assertTrue(prime.matches("ADS"));
        assertFalse(prime.matches("netflix"));
    }

    private static TmdbCatalog.Provider provider(int id, String name) {
        return new TmdbCatalog.Provider(id, name);
    }
}
