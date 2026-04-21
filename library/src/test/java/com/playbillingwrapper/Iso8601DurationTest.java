package com.playbillingwrapper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Iso8601DurationTest {

    private static final long DAY = 86_400_000L;

    @Test
    public void parses_days() {
        assertEquals(3 * DAY, PlayBillingWrapper.parseIso8601DurationMillis("P3D"));
        assertEquals(7 * DAY, PlayBillingWrapper.parseIso8601DurationMillis("P7D"));
        assertEquals(14 * DAY, PlayBillingWrapper.parseIso8601DurationMillis("P14D"));
    }

    @Test
    public void parses_weeks() {
        assertEquals(7 * DAY, PlayBillingWrapper.parseIso8601DurationMillis("P1W"));
        assertEquals(2 * 7 * DAY, PlayBillingWrapper.parseIso8601DurationMillis("P2W"));
    }

    @Test
    public void parses_months_approx_30d() {
        assertEquals(30 * DAY, PlayBillingWrapper.parseIso8601DurationMillis("P1M"));
    }

    @Test
    public void parses_years_approx_365d() {
        assertEquals(365 * DAY, PlayBillingWrapper.parseIso8601DurationMillis("P1Y"));
    }

    @Test
    public void rejects_malformed_input() {
        assertEquals(-1, PlayBillingWrapper.parseIso8601DurationMillis(null));
        assertEquals(-1, PlayBillingWrapper.parseIso8601DurationMillis(""));
        assertEquals(-1, PlayBillingWrapper.parseIso8601DurationMillis("abc"));
        assertEquals(-1, PlayBillingWrapper.parseIso8601DurationMillis("P3X"));
        assertEquals(-1, PlayBillingWrapper.parseIso8601DurationMillis("3D"));
    }
}
