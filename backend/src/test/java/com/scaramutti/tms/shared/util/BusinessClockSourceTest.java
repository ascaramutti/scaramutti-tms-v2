package com.scaramutti.tms.shared.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Todo reloj de negocio del backend sale de {@code DateUtils}: el instante de
 * {@code nowUtcMicros()} y el dia calendario de {@code LIMA}. Este caso recorre el codigo de
 * produccion y falla si vuelve cualquiera de las formas prohibidas.
 *
 * <p>Existe porque esas desviaciones son INVISIBLES para un test comun. El anio de Lima y el de
 * UTC coinciden todo el anio salvo las cinco horas que van de las 19:00 del 31 de diciembre a la
 * medianoche, asi que devolver el codigo de cotizacion al reloj UTC no rompe nada el resto del
 * tiempo. Un {@code OffsetDateTime.now(...)} sin truncar solo se nota en una JVM con reloj de
 * nanosegundos y al comparar el ETag. Sin este barrido, revertir cualquiera de los dos deja la
 * suite entera en verde.
 *
 * <p>{@code Instant.now()} queda permitido a proposito: es un instante absoluto y no depende de
 * ninguna zona. Lo usan el servicio de tokens y el ciclo semanal de operaciones, que resuelve la
 * zona adentro de {@code at(Instant)}.
 *
 * <p>Mide el texto del fuente y no el comportamiento, a proposito: lo que se protege es de donde
 * se lee la hora, no que numero devolvio hoy. De ahi salen sus dos limites, medidos y conocidos:
 * una llamada partida en dos lineas ({@code now(} y el argumento abajo) se le escapa, y las
 * lineas de comentario se saltean, porque si no un comentario que MENCIONE una forma prohibida
 * tumba el caso sin que haya nada mal en el codigo.
 */
class BusinessClockSourceTest {

    /** Relativo al directorio del modulo, que es desde donde corre la suite. */
    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java");

    /** El unico archivo que puede leer el reloj del sistema: es la fuente que los demas usan. */
    private static final String CLOCK_SOURCE_FILE = "DateUtils.java";

    /**
     * Se buscan por expresion y no por texto plano porque la misma llamada se escribe de varias
     * formas. Medido: con la lista en texto plano, revertir el reloj del codigo de cotizacion
     * escribiendolo como {@code LocalDate.now(java.time.ZoneOffset.UTC)} pasaba el barrido sin
     * que nada avisara. El patron ata la FORMA de la llamada, no su ortografia.
     */
    private static final List<Pattern> FORBIDDEN_FORMS = Stream.of(
        // now(ZoneOffset.UTC), con o sin el paquete adelante
        "\\bnow\\(\\s*(?:[\\w.]*\\.)?ZoneOffset\\.UTC\\s*\\)",
        // la zona del proceso, que es justo lo que la suite no debe poder ver
        "\\bZoneId\\.systemDefault\\(\\s*\\)",
        // un reloj con fecha SIN decir en que zona; Instant.now() no entra, es absoluto
        "\\b(?:OffsetDateTime|ZonedDateTime|LocalDateTime|LocalDate|LocalTime)\\.now\\(\\s*\\)",
        // una zona nombrada a mano en vez de la compartida
        "\\bZone(?:Id|Offset)\\.of\\("
    ).map(Pattern::compile).toList();

    private static boolean isComment(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
    }

    @Test
    void everyBusinessClockIsReadThroughDateUtils() throws IOException {
        assertTrue(Files.isDirectory(PRODUCTION_SOURCES),
            "No se encontro " + PRODUCTION_SOURCES.toAbsolutePath()
                + ": el barrido no midio nada, que no es lo mismo que estar limpio");

        List<String> findings = new ArrayList<>();
        int fileCount = 0;

        try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCES)) {
            List<Path> sources = tree
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals(CLOCK_SOURCE_FILE))
                .toList();
            for (Path source : sources) {
                fileCount++;
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (isComment(lines.get(i))) {
                        continue;
                    }
                    for (Pattern forbidden : FORBIDDEN_FORMS) {
                        if (forbidden.matcher(lines.get(i)).find()) {
                            findings.add(source + ":" + (i + 1) + " → " + forbidden.pattern());
                        }
                    }
                }
            }
        }

        assertTrue(fileCount > 50,
            "El barrido leyo solo " + fileCount + " archivos: el alcance quedo mal derivado");
        assertEquals(List.of(), findings,
            "Hay relojes leidos fuera de " + CLOCK_SOURCE_FILE + ". Usar DateUtils.nowUtcMicros()"
                + " para el instante y DateUtils.LIMA para el dia calendario");
    }
}
