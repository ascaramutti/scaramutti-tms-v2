package com.scaramutti.tms.workers.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Junta los campos que efectivamente cambiaron en una edicion.
 *
 * <p>Existe aparte del servicio porque la regla que encierra es una sola y es facil de romper sin
 * que se note: un campo que se reenvia igual NO deja rastro. Escrita adentro del servicio, cada
 * borde costaria un pedido HTTP con su base; aca se mide directo.
 *
 * <p>La comparacion es por valor exacto, sin ignorar mayusculas y sin recortar: el recorte ya
 * ocurrio en el borde, y "Juan" a "juan" ES un cambio que alguien pidio y que el historial tiene
 * que poder mostrar.
 */
public final class WorkerFieldChanges {

    private final List<WorkerFieldChange> changes = new ArrayList<>();

    /**
     * Anota el campo si el valor nuevo difiere del anterior. Cualquiera de los dos puede ser
     * nulo: borrar un telefono es un cambio, y ponerle uno a quien no tenia tambien.
     */
    public void compare(WorkerAuditField field, String oldValue, String newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.add(new WorkerFieldChange(field, oldValue, newValue));
        }
    }

    /** Los cambios, en el orden en que se compararon. */
    public List<WorkerFieldChange> asList() {
        return Collections.unmodifiableList(changes);
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }
}
