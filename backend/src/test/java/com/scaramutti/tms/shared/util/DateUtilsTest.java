package com.scaramutti.tms.shared.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
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

    @Test
    void toLocalDate_withLocalDate_returnsSameInstance() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        assertSame(date, DateUtils.toLocalDate(date));
    }

    /**
     * La rama que existe SOLO por si el driver cambia de tipo: sin este caso, el día que
     * empiece a devolver {@code java.sql.Date} nadie habría ejecutado nunca la conversión.
     */
    @Test
    void toLocalDate_withSqlDate_convertsWithoutShiftingTheDay() {
        java.sql.Date sqlDate = java.sql.Date.valueOf(LocalDate.of(2026, 5, 10));
        assertEquals(LocalDate.of(2026, 5, 10), DateUtils.toLocalDate(sqlDate));
    }

    /**
     * Un {@code Timestamp} NO es una fecha: hereda de {@code java.util.Date} por otra rama, así
     * que colarlo acá sería perder la hora en silencio. Tiene que fallar.
     */
    @Test
    void toLocalDate_withTimestamp_throws() {
        java.sql.Timestamp ts = java.sql.Timestamp.from(Instant.parse("2026-05-10T18:56:17Z"));
        assertThrows(IllegalStateException.class, () -> DateUtils.toLocalDate(ts));
    }

    @Test
    void toLocalDate_withUnexpectedType_throws() {
        assertThrows(IllegalStateException.class, () -> DateUtils.toLocalDate("no soy fecha"));
        assertThrows(IllegalStateException.class, () -> DateUtils.toLocalDate(null));
    }
}
