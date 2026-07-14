package com.scaramutti.tms.shared.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Utilidades de fecha/hora compartidas por todo el backend.
 *
 * <p>{@link #LIMA} / {@link #LIMA_ZONE_ID}: la zona horaria del negocio (Peru).
 * Es un INVARIANTE DE DOMINIO (Scaramutti opera en hora de Peru en todos los
 * entornos), no configuracion — por eso vive como constante y no en properties
 * (configurarlo invitaria a un modo de falla por entorno con bugs sutiles de
 * borde de dia). {@code LIMA_ZONE_ID} se expone como {@code String} para los
 * lugares que necesitan un literal de compilacion, p.ej.
 * {@code @Scheduled(timeZone = DateUtils.LIMA_ZONE_ID)}.
 */
public final class DateUtils {

    /** Id de la zona del negocio como String (para anotaciones que exigen literal constante). */
    public static final String LIMA_ZONE_ID = "America/Lima";

    /** Zona horaria del negocio (Peru). Los filtros/formatos de fecha se interpretan aca. */
    public static final ZoneId LIMA = ZoneId.of(LIMA_ZONE_ID);

    private DateUtils() {
    }

    /**
     * Conversion defensiva de un valor temporal crudo de una native query a
     * {@link OffsetDateTime}. Hibernate/PG puede devolver {@code OffsetDateTime},
     * {@code Instant} o {@code Timestamp} segun version/driver; los dos ultimos se
     * normalizan a UTC.
     */
    public static OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof Instant inst) return inst.atOffset(ZoneOffset.UTC);
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected temporal type: "
            + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * Ahora en UTC truncado a MICROSEGUNDOS. Postgres ({@code timestamptz}) guarda esa
     * precision, asi que los timestamps que sirven de version del ETag deben truncarse
     * a micros: sin esto, el valor devuelto por un POST/PUT no coincide con el releido
     * por un GET en JVMs con reloj de nanosegundos (Linux), rompiendo el If-Match (bug
     * D-12). Fuente unica del truncado para el codigo nuevo (los {@code @PrePersist}
     * viejos aun lo hacen inline: migrarlos es una deuda de refactor dedicado).
     */
    public static OffsetDateTime nowUtcMicros() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
