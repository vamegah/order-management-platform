package com.example.omp.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Controllable clock for deterministic time in tests.
 *
 * <p>Not used by every test; provided for tests that assert on scheduled times or signature
 * timestamps.
 */
public final class TestClock {

    private TestClock() {}

    public static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    public static Clock now() {
        return Clock.systemUTC();
    }
}
