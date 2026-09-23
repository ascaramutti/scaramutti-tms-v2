package com.scaramutti.tms.workers.model;

/**
 * Que campo cambio, con el nombre que se guarda y la etiqueta que se muestra.
 *
 * <p>Vive en un enum y no como literales adentro del servicio, a diferencia de lo que hacen
 * operaciones y almacen, por un motivo que este modulo si tiene: TRES endpoints escriben en esta
 * bitacora, no uno. El cambio de estado va a escribir sus propias filas sobre la misma tabla, y
 * dos copias de una etiqueta es exactamente como una etiqueta se separa de la otra.
 *
 * <p>Y el desvio seria SILENCIOSO: el repositorio de la bitacora no tiene metodos de lectura a
 * proposito, asi que nada ni nadie mira estas filas hoy. Un nombre de campo mal escrito no lo
 * descubre ninguna pantalla; con el enum, lo descubre un caso que lo recorre.
 *
 * <p>El cambio de estado suma los suyos cuando llegue: hoy serian codigo sin llamador.
 */
public enum WorkerAuditField {

    FIRST_NAME              ("firstName",              "Nombre"),
    LAST_NAME               ("lastName",               "Apellido"),
    DOCUMENT_TYPE           ("documentType",           "Tipo de documento"),
    DOCUMENT_NUMBER         ("documentNumber",         "Número de documento"),
    PHONE                   ("phone",                  "Teléfono"),
    ROLE                    ("role",                   "Cargo"),
    HIRE_DATE               ("hireDate",               "Fecha de ingreso"),
    DRIVER_LICENSE_NUMBER   ("driver.licenseNumber",   "Licencia"),
    DRIVER_LICENSE_CATEGORY ("driver.licenseCategory", "Categoría de licencia"),
    DRIVER_STATUS           ("driver.status",          "Disponibilidad"),
    DRIVER_IS_ACTIVE        ("driver.isActive",        "Ficha de conductor"),
    USER_ROLE               ("user.role",              "Rol del usuario");

    private final String fieldName;
    private final String label;

    WorkerAuditField(String fieldName, String label) {
        this.fieldName = fieldName;
        this.label = label;
    }

    /** Lo que se guarda en la columna del campo. */
    public String fieldName() {
        return fieldName;
    }

    /** El texto que va a mostrar el historial, en el idioma del usuario. */
    public String label() {
        return label;
    }
}
