package com.scaramutti.tms.operations;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Operaciones con codigos trazables (OPS-XXX). Los codigos se
 * agregan a medida que cada endpoint los necesita, tomando el numero que el contrato le reservo,
 * asi que el orden en que aparecen aca no es el orden en que se implementaron. Un codigo ya usado
 * NO se recodifica: cada caso nuevo toma el siguiente libre.
 */
public enum OperationsError implements ApiError {

    /**
     * La transicion pedida no existe en la maquina de estados: el par origen→destino no figura en
     * la tabla.
     *
     * <p>Habla del ARCO, no de la fila. Los dos terminales inmutables (cancelado y eliminado) los
     * rechaza ANTES {@link #SERVICE_NOT_EDITABLE}, con lo cual a la maquina nunca se la pregunta
     * por ellos y los dos codigos no se solapan, aunque para esos doce pares los dos serian
     * ciertos. Sin esa precedencia declarada, el mismo caso contestaria un codigo u otro segun el
     * orden de dos {@code if}.
     *
     * <p>La REAPERTURA es la excepcion, y por eso no rompe la regla: es la unica que ENTRA a un
     * terminal en vez de salir de el, asi que no pasa por la guarda de inmutabilidad ni por la
     * maquina. Tiene su propio rechazo con este mismo codigo —"solo se reabre un viaje cancelado o
     * eliminado"— que si habla de un origen terminal.
     *
     * <p>Tampoco es {@link #ACTION_NOT_ALLOWED_FOR_STATUS}, que rechaza acciones que NO son
     * cambios de estado (asignar recursos, sumar refuerzos). Se deja escrito porque los tres son
     * 409 de estado y se confunden.
     *
     * <p>El detalle se sobrescribe por caso nombrando los dos estados en es-PE: el texto generico
     * no le dice al usuario a donde SI podia ir.
     */
    INVALID_STATUS_TRANSITION("OPS-001", 409, "Invalid status transition",
        "No se puede pasar al estado pedido desde el estado actual"),

    /**
     * Un recurso pedido ya participa de OTRO viaje activo, como recurso principal o como
     * refuerzo. Es FORZABLE: el conflicto avisa, no prohibe, porque asi trabajaba el sistema
     * anterior y porque la decision de mandar igual una unidad ocupada es operativa, no del
     * software. Reintentar con {@code force} la asigna y deja constancia.
     *
     * <p>Lleva codigo propio y no comparte el de los conflictos de estado justamente por eso:
     * es el UNICO 409 del modulo que el cliente puede resolver reintentando la MISMA operacion
     * con un campo mas, y la interfaz necesita distinguirlo para ofrecer "Forzar asignacion".
     * El cuerpo agrega {@code forcible} y {@code conflicts} como miembros de extension.
     */
    RESOURCE_CONFLICT("OPS-002", 409, "Resource conflict",
        "El recurso ya está asignado a otro servicio activo"),

    /**
     * El viaje esta cancelado o eliminado: los dos estados terminales son INMUTABLES. El
     * completado si se toca (corregir los datos de un viaje ya cerrado es legitimo), asi que este
     * error no habla del final del ciclo sino de sus dos salidas.
     *
     * <p>El mensaje NO nombra la edicion a proposito: el contrato le asigna este mismo codigo al
     * endpoint de transiciones de estado, y ahi un rechazo que hablara de "editar" mandaria al
     * usuario a buscar el problema en otro lado.
     */
    SERVICE_NOT_EDITABLE("OPS-004", 409, "Conflict",
        "El servicio está cancelado o eliminado: ya no admite cambios"),

    /** El viaje pedido no existe. Espejo de los 404 de cotizaciones y almacen. */
    SERVICE_NOT_FOUND("OPS-005", 404, "Resource not found",
        "El servicio indicado no existe"),

    /**
     * El estado actual del viaje no admite la accion pedida.
     *
     * <p>Es el tercero de los tres 409 de estado y hay que leerlo por descarte: OPS-001 rechaza
     * una TRANSICION invalida (pedir un estado al que no se puede ir desde el actual), OPS-004
     * rechaza los dos terminales inmutables, y este rechaza una accion que NO es un cambio de
     * estado pero que solo tiene sentido desde uno concreto — asignar recursos solo se puede
     * cuando el viaje todavia no los tiene.
     *
     * <p>El mensaje no nombra ni la accion ni el estado que haria falta: el contrato le asigna
     * este mismo codigo a los recursos de refuerzo, que exigen el estado CONTRARIO (el viaje ya
     * en ruta), asi que cualquier texto que prometa "solo desde pendiente de asignacion"
     * mentiria en la mitad de los casos.
     */
    ACTION_NOT_ALLOWED_FOR_STATUS("OPS-006", 409, "Conflict",
        "El estado del servicio no admite esta acción"),

    /**
     * Alta repetida en cuestion de segundos (doble-click o reintento del navegador): mismo
     * usuario, mismo cliente y misma ruta dentro de la ventana configurada. NO es una
     * restriccion de unicidad — dos viajes iguales separados en el tiempo son legitimos —,
     * y por eso lleva codigo propio: el cliente distingue este 409 benigno de un conflicto real.
     */
    DUPLICATE_SERVICE_DETECTED("OPS-007", 409, "Conflict",
        "Se detectó un servicio idéntico creado hace menos de 30 segundos"),

    /**
     * No se pudo tomar la fila del viaje: la espera se agoto porque otra operacion la tiene, o el
     * motor corto un abrazo mortal. Los dos son conflictos TRANSITORIOS: quien llama reintenta y
     * lo mas probable es que pase. Lleva codigo propio para que el cliente lo distinga del 409 de
     * un viaje inmutable, que no se destraba reintentando sino reabriendo el viaje.
     */
    SERVICE_LOCKED("OPS-008", 409, "Conflict",
        "No se pudo completar la operación por un bloqueo temporal en la base de datos, reintente en unos segundos"),

    /**
     * A la fila le falta un dato que la transicion necesita. Son CUATRO casos: iniciar un viaje
     * sin conductor y tracto, cerrar uno que no registra cuando arranco, reabrir uno cuyo rastro no
     * dice de donde venia (o dice uno del que no se vuelve), y reabrir uno con recursos de refuerzo,
     * cuya disponibilidad todavia no se puede verificar.
     *
     * <p>Los tres primeros NO pueden pasar por la aplicacion, y ese es el punto: a "pendiente de
     * inicio" solo se llega asignando, pasar a "en ruta" siempre escribe el inicio (el que venga en
     * el cuerpo, o el momento de la llamada), y toda salida deja su fila de rastro. Si esa guarda
     * dispara, la fila entro SIN pasar por la aplicacion — el cutover del sistema anterior, o una
     * escritura a mano. El cuarto es distinto: es un limite CONOCIDO de la reapertura, con fecha de
     * vencimiento (se cae cuando exista el endpoint de refuerzos), y tampoco se reintenta.
     *
     * <p>Por eso el mensaje NO le pide nada al usuario. No puede arreglarlo: la edicion corrige las
     * fechas reales que ya existen pero no las fija desde cero, asi que ofrecerle "corregi la fecha"
     * seria mandarlo a una puerta cerrada. Lo que corresponde es sanear el dato, y eso es del
     * script de migracion o de soporte.
     *
     * <p>Lleva codigo propio y no comparte el de los otros 409 de estado porque la interfaz tiene
     * que poder distinguirlo: los demas son errores de lo que el usuario acaba de pedir, este es un
     * problema de la fila que ya estaba ahi.
     */
    // El detalle por defecto es GENERICO a proposito: los cuatro sitios que lo lanzan lo pisan con
    // el suyo, y dejar aca el texto de uno solo ("no registra cuando inicio") desinforma sobre el
    // alcance del codigo a quien lo lea desde el catalogo.
    SERVICE_DATA_INCOMPLETE("OPS-009", 409, "Conflict",
        "Al viaje le falta un dato que esta transición necesita"),
    ;

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    OperationsError(String code, int status, String title, String detail) {
        this.code = code;
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    @Override public String code()   { return code; }
    @Override public int    status() { return status; }
    @Override public String title()  { return title; }
    @Override public String detail() { return detail; }
}
