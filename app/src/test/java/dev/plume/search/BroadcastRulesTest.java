package dev.plume.search;

public final class BroadcastRulesTest {
    @org.junit.Test public void validatesRules() {
        org.junit.Assert.assertTrue(BroadcastRules.isAntenna("TYPE_ATSC_T", "SERVICE_TYPE_AUDIO_VIDEO", true, false));
        org.junit.Assert.assertTrue(BroadcastRules.isAntenna("TYPE_ATSC3_T", "SERVICE_TYPE_AUDIO_VIDEO", true, false));
        org.junit.Assert.assertTrue(!BroadcastRules.isAntenna("TYPE_ATSC_C", "SERVICE_TYPE_AUDIO_VIDEO", true, false));
        org.junit.Assert.assertTrue(!BroadcastRules.isAntenna("TYPE_PREVIEW", "SERVICE_TYPE_AUDIO_VIDEO", true, false));
        org.junit.Assert.assertTrue(!BroadcastRules.isAntenna("TYPE_OTHER", "SERVICE_TYPE_AUDIO_VIDEO", true, false));
        org.junit.Assert.assertTrue(!BroadcastRules.isAntenna("TYPE_ATSC_T", "SERVICE_TYPE_AUDIO", true, false));
        org.junit.Assert.assertTrue(!BroadcastRules.isAntenna("TYPE_ATSC_T", "SERVICE_TYPE_AUDIO_VIDEO", false, false));
        org.junit.Assert.assertTrue(!BroadcastRules.isAntenna("TYPE_ATSC_T", "SERVICE_TYPE_AUDIO_VIDEO", true, true));
        org.junit.Assert.assertTrue(BroadcastRules.matches("  BEARS  ", "Chicago Bears at Minnesota Vikings", null));
        org.junit.Assert.assertTrue(BroadcastRules.matches("a new  hope", "Star Wars", "A New Hope"));
        org.junit.Assert.assertTrue(!BroadcastRules.matches("", "Anything", "Anything"));
        org.junit.Assert.assertTrue(!BroadcastRules.matches("prime", null, null));
        org.junit.Assert.assertTrue(!BroadcastRules.matches("Dune", "Foundation", "The Emperor's Peace"));
        System.out.println("Broadcast rules checks passed: terrestrial TV only, visibility/lock limits, title matching.");
    }
}
