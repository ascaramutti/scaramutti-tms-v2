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
     * Hermano de {@link #toOffsetDateTime(Object)} para las columnas {@code DATE}: segun la
     * version de Hibernate y del driver, una fecha de una query nativa llega como
     * {@code java.sql.Date} o como {@code LocalDate}. Castear a una sola de las dos convierte un
     * cambio de version en un 500.
     */
    public static java.time.LocalDate toLocalDate(Object value) {
        if (value instanceof java.time.LocalDate localDate) return localDate;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        throw new IllegalStateException("Unexpected date type: "
            + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * Inicio del dia (00:00) de una fecha en zona Lima, como {@link OffsetDateTime} para
     * comparar contra columnas {@code timestamptz}. Es el borde INCLUSIVO inferior de un
     * filtro por dia/rango en hora de negocio. Fuente unica del patron: los filtros de
     * fecha (retiros, kardex, cotizaciones, stats, reportes) lo comparten en vez de
     * repetir {@code d.atStartOfDay(LIMA).toOffsetDateTime()}.
     */
    public static OffsetDateTime limaDayStart(java.time.LocalDate date) {
        return date.atStartOfDay(LIMA).toOffsetDateTime();
    }

    /**
     * Inicio del dia SIGUIENTE (00:00) en zona Lima. Es el borde EXCLUSIVO superior de un
     * rango cuyo {@code dateTo} es inclusivo del dia completo ({@code < limaNextDayStart}
     * cubre hasta las 23:59:59.999 de {@code date}). Complementa a {@link #limaDayStart}.
     */
    public static OffsetDateTime limaNextDayStart(java.time.LocalDate date) {
        return date.plusDays(1).atStartOfDay(LIMA).toOffsetDateTime();
    }

    /**
     * Como se GUARDA una marca de tiempo que llego de afuera: en UTC y truncada a microsegundos.
     *
     * <p>Las dos cosas importan y por motivos distintos. UTC porque es el marco de la columna, y
     * comparar despues una marca guardada contra otra con huso propio daria diferencias que no
     * existen. Microsegundos porque esa es la precision REAL de {@code timestamptz}: una marca con
     * nanos se guarda truncada, asi que el valor que relee un GET no es el que se comparo al
     * escribir, y de ahi salen ETags que no coinciden consigo mismos.
     *
     * <p>Vive aca, al lado de {@link #nowUtcMicros()}, porque es su gemela: la misma decision
     * aplicada al valor que llega en vez de al de ahora. Ya la aplican dos endpoints sobre las
     * MISMAS dos columnas (el inicio y el fin reales), y una copia por servicio es una divergencia
     * silenciosa esperando a que alguien retoque una sola.
     */
    public static OffsetDateTime toStorableUtc(OffsetDateTime value) {
        return value == null ? null
            : value.withOffsetSameInstant(java.time.ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /**
     * Ahora en UTC truncado a MICROSEGUNDOS. Postgres ({@code timestamptz}) guarda esa
     * precision, asi que los timestamps que sirven de version del ETag deben truncarse
     * a micros: sin esto, el valor devuelto por un POST/PUT no coincide con el releido
     * por un GET en JVMs con reloj de nanosegundos (Linux), rompiendo el If-Match (bug
     * D-12). Fuente unica del truncado: todos los {@code @PrePersist}/{@code @PreUpdate}
     * de las entities y los services lo usan.
     */
    public static OffsetDateTime nowUtcMicros() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
