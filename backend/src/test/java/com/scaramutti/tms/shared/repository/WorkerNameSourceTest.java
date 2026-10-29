package com.scaramutti.tms.shared.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El nombre de un trabajador se arma y se escribe en dos archivos: {@code Worker} (la regla Java y
 * {@code assignSurnames}) y {@code WorkerRepository} (la regla SQL y el orden). Fuera de ellos, y en
 * los mappers que genera MapStruct, el barrido falla si algo nombra las columnas o el metamodelo del
 * nombre, suma sus campos o escribe un apellido. Mide el texto, como {@code BusinessClockSourceTest}:
 * se le escapan un {@code String.join}, {@code String.format}, {@code concat}, un {@code StringBuilder},
 * un campo dentro de una cadena JPQL y lo partido en dos lineas, y saltea los comentarios.
 */
class WorkerNameSourceTest {

    /** Relativo al directorio del modulo, que es desde donde corre la suite. */
    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java");

    /** Los mappers generados: uno que escribiera la entidad lo haria aca, no en el fuente. */
    private static final Path GENERATED_SOURCES = Path.of("target/generated-sources/annotations");

    /** Los dos archivos que arman el nombre: los demas los usan. */
    private static final Set<Path> NAME_SOURCES = Set.of(
        PRODUCTION_SOURCES.resolve("com/scaramutti/tms/shared/entity/Worker.java"),
        PRODUCTION_SOURCES.resolve("com/scaramutti/tms/shared/repository/WorkerRepository.java"));

    private static final String NAME_FIELDS = "(?:firstName|lastName|paternalLastName|maternalLastName)";

    private static final List<Pattern> FORBIDDEN_FORMS = Stream.of(
        // las columnas del nombre en una consulta
        "\\b(?:first_name|last_name|paternal_last_name|maternal_last_name)\\b",
        // un campo del nombre sumado con + o +=, a la izquierda o a la derecha, o su accesor de record
        "\\b" + NAME_FIELDS + "\\b(?:\\(\\))?\\s*\\+(?!\\+)",
        "(?<!\\+)\\+=?\\s*(?:[\\w.]*\\.)?" + NAME_FIELDS + "\\b",
        // un apellido escrito sin assignSurnames
        "\\.(?:lastName|paternalLastName|maternalLastName)\\s*=(?!=)",
        // el metamodelo del nombre, que es como se lo nombra en una consulta de Panache o JPA
        "\\bWorker_\\.(?:FIRST_NAME|LAST_NAME|PATERNAL_LAST_NAME|MATERNAL_LAST_NAME|" + NAME_FIELDS + ")\\b"
    ).map(Pattern::compile).toList();

    private static boolean isComment(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
    }

    @Test
    void theWorkerName_isComposedAndWrittenOnlyInItsTwoFiles() throws IOException {
        for (Path root : List.of(PRODUCTION_SOURCES, GENERATED_SOURCES)) {
            assertTrue(Files.isDirectory(root),
                "No se encontro " + root.toAbsolutePath()
                    + ": el barrido no midio nada, que no es lo mismo que estar limpio");
        }
        for (Path nameSource : NAME_SOURCES) {
            assertTrue(Files.isRegularFile(nameSource),
                "No se encontro " + nameSource + ": se movio y el barrido lo trataria como ajeno");
        }

        List<String> findings = new ArrayList<>();
        int fileCount = 0;

        try (Stream<Path> tree = Stream.concat(Files.walk(PRODUCTION_SOURCES), Files.walk(GENERATED_SOURCES))) {
            List<Path> sources = tree
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !NAME_SOURCES.contains(p))
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
            "Hay nombres de trabajador armados o escritos fuera de Worker.java y WorkerRepository.java."
                + " Usar Worker.fullName(), WorkerRepository.fullNameExpression o fullNameOrder, y"
                + " Worker.assignSurnames para escribir los apellidos");
    }

    /**
     * Ninguna consulta de esta imagen nombra la columna de antes del apellido, ni con un comodin sobre
     * {@code workers} (sin alias, con {@code w} o con el que le da el archivo), cuyo plan preparado
     * cambiaria el dia que se borre. Mide cada linea sin mayusculas: marca de mas un {@code w.*} de otra
     * tabla; se le escapan lo partido en dos lineas y el {@code RETURNING *} que el driver suma al alta.
     */
    @Test
    void theOldSurnameColumn_isNotNamedByThisImage() throws IOException {
        Pattern aliasOfWorkers =
            Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+(?:public\\.)?workers\\s+(?:AS\\s+)?(\\w+)");
        List<String> findings = new ArrayList<>();
        int fileCount = 0;
        try (Stream<Path> tree = Stream.concat(Files.walk(PRODUCTION_SOURCES), Files.walk(GENERATED_SOURCES))) {
            for (Path source : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                fileCount++;
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8).stream()
                    .map(line -> isComment(line) ? "" : line)
                    .toList();
                List<String> forms = new ArrayList<>(List.of("\\blast_name\\b", "\\bw\\.\\*", "\\bworkers\\.\\*",
                    "SELECT\\s+\\*\\s+FROM\\s+(?:public\\.)?workers\\b",
                    "(?:INSERT\\s+INTO|UPDATE)\\s+(?:public\\.)?workers\\b.*RETURNING\\s+\\*"));
                for (String line : lines) {
                    Matcher alias = aliasOfWorkers.matcher(line);
                    while (alias.find()) {
                        forms.add("\\b" + alias.group(1) + "\\.\\*");
                    }
                }
                Pattern oldColumn = Pattern.compile("(?i)" + String.join("|", forms));
                for (int i = 0; i < lines.size(); i++) {
                    if (oldColumn.matcher(lines.get(i)).find()) {
                        findings.add(source + ":" + (i + 1));
                    }
                }
            }
        }

        assertTrue(fileCount > 50,
            "El barrido leyo solo " + fileCount + " archivos: el alcance quedo mal derivado");
        assertEquals(List.of(), findings, "Hay codigo que lee la columna de antes del apellido");
    }
}
