package com.scaramutti.tms.shared.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateUtilsTest {

    @Test
    void limaZoneId_isPeruBusinessZone() {
        assertEquals("America/Lima", DateUtils.LIMA_ZONE_ID);
        assertEquals(DateUtils.LIMA_ZONE_ID, DateUtils.LIMA.getId());
    }

    @Test
    void toOffsetDateTime_withOffsetDateTime_returnsSameInstance() {
        OffsetDateTime odt = OffsetDateTime.parse("2026-07-11T18:56:17.712392Z");
        assertSame(odt, DateUtils.toOffsetDateTime(odt));
    }

    @Test
    void toOffsetDateTime_withInstant_normalizesToUtc() {
        Instant instant = Instant.parse("2026-07-11T18:56:17.712392Z");
        assertEquals(instant.atOffset(ZoneOffset.UTC), DateUtils.toOffsetDateTime(instant));
    }

    @Test
    void toOffsetDateTime_withTimestamp_normalizesToUtc() {
        Instant instant = Instant.parse("2026-07-11T18:56:17.712392Z");
        java.sql.Timestamp ts = java.sql.Timestamp.from(instant);
        assertEquals(instant.atOffset(ZoneOffset.UTC), DateUtils.toOffsetDateTime(ts));
    }

    @Test
    void toOffsetDateTime_withUnexpectedType_throws() {
        assertThrows(IllegalStateException.class, () -> DateUtils.toOffsetDateTime("no soy fecha"));
        assertThrows(IllegalStateException.class, () -> DateUtils.toOffsetDateTime(null));
    }
}
