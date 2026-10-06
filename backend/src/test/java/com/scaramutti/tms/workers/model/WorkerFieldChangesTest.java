package com.scaramutti.tms.workers.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El armador del diff de una edicion.
 *
 * <p>La regla que encierra es una sola y es facil de romper sin que se note: un campo reenviado
 * igual NO deja rastro. Medida aca cuesta microsegundos; adentro del servicio, un pedido con su
 * base por cada borde.
 */
class WorkerFieldChangesTest {

    @Test
    void compare_whenTheValueIsTheSame_recordsNothing() {
        WorkerFieldChanges changes = new WorkerFieldChanges();
        changes.compare(WorkerAuditField.FIRST_NAME, "Juan", "Juan");

        assertTrue(changes.isEmpty(), "reenviar lo mismo no es un cambio");
        assertEquals(List.of(), changes.asList());
    }

    @Test
    void compare_whenTheValueChanges_recordsBothSides() {
        WorkerFieldChanges changes = new WorkerFieldChanges();
        changes.compare(WorkerAuditField.FIRST_NAME, "Juan", "Ana");

        assertEquals(1, changes.asList().size());
        var change = changes.asList().get(0);
        assertEquals(WorkerAuditField.FIRST_NAME, change.field());
        assertEquals("Juan", change.oldValue());
        assertEquals("Ana", change.newValue());
    }

    /** Borrar un dato ES un cambio: el telefono que se vacia tiene que dejar rastro. */
    @Test
    void compare_whenTheValueIsCleared_recordsIt() {
        WorkerFieldChanges changes = new WorkerFieldChanges();
        changes.compare(WorkerAuditField.PHONE, "987654321", null);

        assertEquals(1, changes.asList().size());
        assertEquals("987654321", changes.asList().get(0).oldValue());
        assertEquals(null, changes.asList().get(0).newValue());
    }

    /** Y ponerle un valor a quien no tenia, tambien. */
    @Test
    void compare_whenTheValueIsSetFromNothing_recordsIt() {
        WorkerFieldChanges changes = new WorkerFieldChanges();
        changes.compare(WorkerAuditField.PHONE, null, "987654321");

        assertEquals(1, changes.asList().size());
        assertEquals(null, changes.asList().get(0).oldValue());
    }

    @Test
    void compare_twoNulls_recordNothing() {
        WorkerFieldChanges changes = new WorkerFieldChanges();
        changes.compare(WorkerAuditField.PHONE, null, null);

        assertTrue(changes.isEmpty());
    }

    /** Cambiar solo la caja ES un cambio: alguien lo pidio y el historial tiene que mostrarlo. */
    @Test
    void compare_aCaseOnlyChange_isAChange() {
        WorkerFieldChanges changes = new WorkerFieldChanges();
        changes.compare(WorkerAuditField.FIRST_NAME, "Juan", "juan");

        assertEquals(1, changes.asList().size());
    }

    /** Los espacios NO se ignoran: el recorte ya ocurrio en el borde. */
    @Test
    void compare_doesNotTrim() {
        WorkerFieldChanges changes = new WorkerFieldChanges();
        changes.compare(WorkerAuditField.FIRST_NAME, "Juan", " Juan ");

        assertEquals(1, changes.asList().size());
    }

    @Test
    void asList_keepsTheOrderInWhichTheyWereCompared() {
        WorkerFieldChanges changes = new WorkerFieldChanges();
        changes.compare(WorkerAuditField.LAST_NAME, "a", "b");
        changes.compare(WorkerAuditField.FIRST_NAME, "c", "d");
        changes.compare(WorkerAuditField.PHONE, "e", "f");

        assertEquals(List.of(WorkerAuditField.LAST_NAME, WorkerAuditField.FIRST_NAME,
            WorkerAuditField.PHONE), changes.asList().stream().map(WorkerFieldChange::field).toList());
    }

    /** Cada etiqueta entra en su columna: nadie las mira hasta que la pantalla exista. */
    @Test
    void everyField_fitsItsColumn() {
        for (WorkerAuditField field : WorkerAuditField.values()) {
            assertTrue(field.fieldName().length() <= 50,
                field + " no entra en la columna del nombre: " + field.fieldName());
            assertTrue(field.label().length() <= 100,
                field + " no entra en la columna de la etiqueta: " + field.label());
        }
    }

    /** Ningun par de campos comparte nombre ni etiqueta: dos iguales harian ilegible el historial. */
    @Test
    void noTwoFields_shareTheirNameOrLabel() {
        var names = java.util.Arrays.stream(WorkerAuditField.values())
            .map(WorkerAuditField::fieldName).distinct().count();
        var labels = java.util.Arrays.stream(WorkerAuditField.values())
            .map(WorkerAuditField::label).distinct().count();

        assertEquals(WorkerAuditField.values().length, names);
        assertEquals(WorkerAuditField.values().length, labels);
    }

    /**
     * El mapa COMPLETO de campo a etiqueta, afirmado literal.
     *
     * <p>Los otros dos casos miden largo y unicidad, que son propiedades: una etiqueta cambiada
     * las sigue cumpliendo. Y nadie mas puede notarlo, porque el repositorio de la bitacora no
     * tiene metodos de lectura a proposito y ninguna pantalla muestra estas filas todavia. Este
     * caso es el unico lugar donde el texto que va a ver una persona queda escrito dos veces.
     */
    @Test
    void everyField_keepsItsNameAndItsLabel() {
        var esperado = new java.util.LinkedHashMap<String, String>();
        esperado.put("firstName", "Nombre");
        esperado.put("lastName", "Apellido");
        esperado.put("documentType", "Tipo de documento");
        esperado.put("documentNumber", "Número de documento");
        esperado.put("phone", "Teléfono");
        esperado.put("role", "Cargo");
        esperado.put("hireDate", "Fecha de ingreso");
        esperado.put("driver.licenseNumber", "Licencia");
        esperado.put("driver.licenseCategory", "Categoría de licencia");
        esperado.put("driver.status", "Disponibilidad");
        esperado.put("driver.isActive", "Ficha de conductor");
        esperado.put("user.role", "Rol del usuario");
        esperado.put("isActive", "Trabajador");
        esperado.put("user.isActive", "Usuario del sistema");

        var real = new java.util.LinkedHashMap<String, String>();
        for (WorkerAuditField field : WorkerAuditField.values()) {
            real.put(field.fieldName(), field.label());
        }

        assertEquals(esperado, real, "el catalogo de etiquetas del historial");
    }

}
