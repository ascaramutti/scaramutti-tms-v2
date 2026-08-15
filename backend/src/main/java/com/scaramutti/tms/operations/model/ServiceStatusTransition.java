package com.scaramutti.tms.operations.model;

import java.util.Set;

/**
 * Los cinco destinos que un usuario puede PEDIR (cuatro estados y una accion), cada uno con todo
 * lo que su pedido implica.
 *
 * <p>Es una tabla de politica, no una lista de estados: cada constante declara si hace falta la
 * version del recurso, si el motivo es obligatorio, que columna de fecha escribe, como se titula
 * el texto libre en la bitacora y que roles NO pueden pedirla. La alternativa —cuatro
 * {@code if (target == X || target == Y)} repartidos por el servicio— dice lo mismo hoy y se
 * separa manana: agregar un sexto target obligaria a acordarse de cuatro lugares.
 *
 * <p>Escrito asi, una constante nueva que no conteste las cinco preguntas <b>no compila</b>. Ese es
 * todo el punto, y es la misma leccion del {@code default -> throw} de la edicion: dos listas
 * paralelas se separan en silencio.
 *
 * <p>Ojo con lo que este enum NO es: no es la maquina de estados. Aca esta que puede pedirse y con
 * que requisitos; desde donde se puede llegar a cada uno lo dice {@code ServiceStatusMachine}. Son
 * dos preguntas distintas y por eso son dos tablas: {@code PENDING_START} es un estado real del
 * viaje que la maquina conoce, pero nadie lo pide por este endpoint (se llega asignando recursos).
 */
public enum ServiceStatusTransition {

    /** Iniciar: el viaje sale a ruta y se le fija el inicio real. */
    IN_PROGRESS(ServiceStatus.IN_PROGRESS, false, false, DateColumn.START, "Nota", Set.of()),

    /** Finalizar: el viaje se cierra y se le fija el fin real. */
    COMPLETED(ServiceStatus.COMPLETED, false, false, DateColumn.END, "Nota", Set.of()),

    /** Cancelar: un viaje REAL que se aborto. Destructivo, con motivo y con version. */
    CANCELLED(ServiceStatus.CANCELLED, true, true, DateColumn.NONE, "Motivo", Set.of("dispatcher")),

    /** Eliminar: el registro que nunca debio existir. Destructivo, con motivo y con version. */
    DELETED(ServiceStatus.DELETED, true, true, DateColumn.NONE, "Motivo", Set.of("dispatcher")),

    /**
     * Reabrir: deshace una cancelacion o una eliminacion y devuelve el viaje al estado que tenia
     * ANTES. Es la unica transicion cuyo destino no esta escrito aca: sale de la auditoria, que ya
     * guarda de donde venia. Un viaje cancelado en ruta vuelve a en ruta, no a foja cero.
     *
     * <p>Es una herramienta de REPARACION, no una operacion del dia a dia: existe porque una
     * cancelacion por error, sin esto, seria permanente. Por eso la lista de vetados es la mas
     * larga de la tabla — solo la gerencia general y la administracion pueden — y por eso pide
     * motivo y version igual que las dos que deshace.
     */
    REOPENED(null, true, true, DateColumn.NONE, "Motivo",
        Set.of("operations_manager", "dispatcher"));

    /** Que marca de tiempo real fija la transicion, si es que fija alguna. */
    public enum DateColumn { START, END, NONE }

    private final ServiceStatus target;
    private final boolean requiresIfMatch;
    private final boolean requiresNote;
    private final DateColumn dateColumn;
    private final String noteLabel;
    private final Set<String> vetoedRoles;

    ServiceStatusTransition(ServiceStatus target, boolean requiresIfMatch, boolean requiresNote,
            DateColumn dateColumn, String noteLabel, Set<String> vetoedRoles) {
        this.target = target;
        this.requiresIfMatch = requiresIfMatch;
        this.requiresNote = requiresNote;
        this.dateColumn = dateColumn;
        this.noteLabel = noteLabel;
        this.vetoedRoles = vetoedRoles;
    }

    /**
     * El estado al que lleva, o {@code null} en la unica transicion que lo resuelve en tiempo de
     * ejecucion ({@link #REOPENED}, que lo saca de la auditoria). Quien la use tiene que preguntar
     * antes por {@link #restoresPreviousStatus()}.
     */
    public ServiceStatus target() {
        return target;
    }

    /** Si el destino lo dicta el historial del viaje en vez de esta tabla. */
    public boolean restoresPreviousStatus() {
        return this == REOPENED;
    }

    /**
     * Cancelar, eliminar y reabrir exigen la version del recurso; iniciar y finalizar no.
     *
     * <p>La razon es el dano: las dos primeras sacan el viaje del circuito y la tercera lo trae de
     * vuelta, asi que el estado que el usuario vio en pantalla tiene que seguir siendo el actual
     * —deshacer sobre una pantalla vieja deshace otra cosa que la que se miro—. Iniciar y
     * finalizar avanzan por un camino de una sola direccion y su propia transicion ya las protege
     * del doble click: el segundo intento cae en una auto-transicion, que no existe.
     */
    public boolean requiresIfMatch() {
        return requiresIfMatch;
    }

    /** El motivo es obligatorio (con minimo) en las dos que matan el viaje y en la que las deshace: RN-OP7. */
    public boolean requiresNote() {
        return requiresNote;
    }

    public DateColumn dateColumn() {
        return dateColumn;
    }

    /**
     * Como se titula el texto libre en la bitacora. No es cosmetico: en una transicion destructiva
     * el texto ES el motivo por el que el viaje murio, y llamarlo "Nota" en el unico rastro que
     * queda le baja el peso a lo que despues hay que rendir.
     */
    public String noteLabel() {
        return noteLabel;
    }

    /**
     * Roles que NO pueden pedir esta transicion, aunque el endpoint los deje entrar.
     *
     * <p>Es una lista NEGATIVA a proposito. El despacho opera el viaje pero no decide matarlo, y
     * escrito como "estos roles si pueden cancelar" un token que sumara despacho y gerencia
     * entraria por la lista positiva y la regla diria lo contrario de lo que significa. Mismo
     * molde que la visibilidad de precios.
     */
    public Set<String> vetoedRoles() {
        return vetoedRoles;
    }
}
