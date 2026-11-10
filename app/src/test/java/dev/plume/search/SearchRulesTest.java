package dev.plume.search;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class SearchRulesTest {
    @org.junit.Test public void validatesRules() {
        check(SearchRules.validZip("01234"), "ZIP must preserve leading zeroes");
        for (String invalid : new String[]{"", "1234", "123456", "1234a", " 12345", "１２３４５"})
            check(!SearchRules.validZip(invalid), "Reject malformed ZIP");
        check(!SearchRules.validZip(null), "Reject absent ZIP");
        check(SearchRules.validToken("sample.read-token.signature"), "Allow token characters");
        for (String invalid : new String[]{"", "Bearer sample", "secret\nheader", "token\rheader", "é", "{\"token\":\"abc\"}", "\"abc\""})
            check(!SearchRules.validToken(invalid), "Reject token control characters and spaces");
        check(!SearchRules.validToken("x".repeat(4097)), "Bound token length");
        check(SearchRules.showProvider(8, false, Collections.emptySet()), "All-services view includes providers with no selection");
        check(!SearchRules.showProvider(8, true, Collections.emptySet()), "Empty my-services selection must not imply entitlement");
        Set<String> selected = new HashSet<>(Collections.singleton("8"));
        check(SearchRules.showProvider(8, true, selected), "Show explicitly selected service");
        check(!SearchRules.showProvider(9, true, selected), "Hide unselected service");
        check("Subscription".equals(SearchRules.offerLabel("flatrate")), "Subscription category");
        check("Rent".equals(SearchRules.offerLabel("rent")), "Rental stays separate from subscription");
        check("Buy".equals(SearchRules.offerLabel("buy")), "Purchase stays separate from subscription");
        check(SearchRules.packageForProvider("Paramount+ Amazon Channel") == null, "Do not map an add-on channel to a direct subscription app");
        check(SearchRules.packageForProvider("Unknown service") == null, "No invented package mapping");
        System.out.println("Search rules checks passed.");
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
