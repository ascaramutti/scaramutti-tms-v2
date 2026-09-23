package com.scaramutti.tms.workers.model;

/**
 * Que hacer con la ficha de conductor al editar un trabajador.
 *
 * <p>Es una funcion PURA y vive aparte del servicio por una razon medible: son dieciocho
 * combinaciones (hay fila o no, esta activa o no, la modalidad del cargo, vino ficha en el cuerpo
 * o no) y adentro del servicio cada una costaria un pedido HTTP con su base. Aca las dieciocho se
 * miden en microsegundos, y el servicio queda con una sola decision que leer.
 */
public enum DriverProfileTransition {

    /** El cargo no lleva ficha y el cuerpo la manda igual, o la exige y no vino. */
    REJECT,
    /** No hay fila y no hace falta: no se toca nada. */
    NOTHING,
    /** No hay fila y el cargo la lleva con ficha en el cuerpo: nace encendida. */
    CREATE,
    /**
     * No hay fila, el cuerpo trae ficha y el cargo la lleva, pero el trabajador esta dado de baja:
     * la fila nace APAGADA con los datos del cuerpo.
     *
     * <p>Es el mismo defecto que cerro {@link #UPDATE_OFF}, del lado de la fila que no existe.
     * Mientras este caso devolvio "no hacer nada", la licencia del cuerpo se descartaba en
     * silencio con 200, sin rastro ni chequeo de unicidad; y con un cargo que EXIGE ficha la API
     * pedia un campo que despues ignoraba, dejando un conductor sin ficha que la reactivacion
     * devolveria asi. Nacer apagada guarda el dato y no lo devuelve al catalogo de conductores.
     */
    CREATE_OFF,
    /** Hay fila activa y sigue activa, con los datos del cuerpo. */
    UPDATE,
    /** Hay fila apagada y vuelve a encenderse con los datos del cuerpo. */
    REACTIVATE,
    /** Hay fila activa y el cargo ya no la lleva, o no vino ficha: se apaga sin borrarse. */
    DEACTIVATE,
    /** Hay fila apagada y tiene que seguir apagada. */
    STAY_OFF,
    /**
     * Hay fila y el cargo la lleva, pero el trabajador esta dado de baja: los datos del cuerpo
     * se escriben igual y la fila queda apagada.
     *
     * <p>Existe porque los dos ejes son independientes y confundirlos deja un agujero: si "no
     * encender" tambien significara "no escribir", a alguien dado de baja con un cargo que EXIGE
     * ficha no habria ningun cuerpo que le corrigiera la licencia. Mandarla se descartaria en
     * silencio, y no mandarla seria un rechazo porque el cargo la exige.
     */
    UPDATE_OFF;

    /**
     * La decision, con todo lo que la determina como parametro y nada mas.
     *
     * <p>SON DOS EJES INDEPENDIENTES y la funcion los mantiene separados: uno decide si se
     * escriben los datos del cuerpo (hay fila y el cargo la lleva), y el otro si la fila queda
     * encendida (la vigencia del trabajador). Cruzarlos fue un defecto real: una ficha NUNCA se
     * enciende sobre un dado de baja, porque eso lo devolveria al catalogo de conductores sin
     * pasar por su reactivacion, pero corregirle la licencia si tiene que poder guardarse.
     *
     * <p>Cuando NO hay fila y el trabajador esta de baja, la fila nace solo si el cuerpo trae
     * datos que guardar, y nace APAGADA: la regla de los dos ejes vale igual sin fila previa.
     */
    public static DriverProfileTransition decide(boolean hasRow, boolean rowIsActive,
            DriverProfileMode mode, boolean bodyHasProfile, boolean workerIsActive) {

        if (mode == DriverProfileMode.NONE && bodyHasProfile) {
            return REJECT;
        }
        if (mode == DriverProfileMode.REQUIRED && !bodyHasProfile) {
            return REJECT;
        }
        if (!hasRow) {
            if (!bodyHasProfile) {
                return NOTHING;
            }
            return workerIsActive ? CREATE : CREATE_OFF;
        }
        boolean writesData = bodyHasProfile && mode != DriverProfileMode.NONE;
        if (!writesData) {
            return rowIsActive ? DEACTIVATE : STAY_OFF;
        }
        if (!workerIsActive) {
            return UPDATE_OFF;
        }
        return rowIsActive ? UPDATE : REACTIVATE;
    }

    /**
     * Si la decision escribe los datos del cuerpo sobre la fila.
     *
     * <p>Es lo que decide si la licencia del cuerpo tiene que compararse contra las demas. Hoy
     * coincide con "vino ficha y no se rechazo": desde {@link #CREATE_OFF} ningun cuerpo con ficha
     * se descarta. La condicion se declara por lo que se ESCRIBE porque es la que tiene que seguir
     * valiendo si una transicion futura vuelve a descartar datos.
     */
    public boolean writesBodyData() {
        return this == CREATE || this == CREATE_OFF || this == UPDATE || this == REACTIVATE
            || this == UPDATE_OFF;
    }
}
