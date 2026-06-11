package com.scaramutti.tms.catalogs.quotationservicetype.model;

import com.scaramutti.tms.catalogs.CatalogsError;

/**
 * Categoría del servicio cotizable. Derivada del prefijo del `code`:
 *  - S → SERVICIO       (transporte ejecutado por la empresa)
 *  - A → ALQUILER       (cliente usa el recurso, sin operación nuestra)
 *  - C → COMPLEMENTARIO (add-on a un servicio principal)
 *  - I → INTEGRAL       (paquete con jerarquía padre+hijos)
 *
 * No es columna en BD: el backend la computa al armar el response. El frontend
 * la usa para filtrar el dropdown del wizard según el quotation_type elegido.
 */
public enum QuotationServiceKind {

    SERVICIO('S'),
    ALQUILER('A'),
    COMPLEMENTARIO('C'),
    INTEGRAL('I');

    private final char prefix;

    QuotationServiceKind(char prefix) {
        this.prefix = prefix;
    }

    public char prefix() {
        return prefix;
    }

    /**
     * Deriva el kind desde el primer caracter del code.
     * Tira ApiException (CAT-001 o CAT-002) si el code no respeta la convención.
     * Se usa al seedear (valida convención) y al mapear entity → response.
     */
    public static QuotationServiceKind fromCode(String code) {
        if (code == null || code.isEmpty()) {
            throw CatalogsError.QUOTATION_SERVICE_TYPE_CODE_REQUIRED.toException();
        }
        char firstChar = code.charAt(0);
        for (QuotationServiceKind kind : values()) {
            if (kind.prefix == firstChar) {
                return kind;
            }
        }
        throw CatalogsError.QUOTATION_SERVICE_TYPE_CODE_PREFIX_INVALID.toException(
            "El code '" + code + "' no empieza con un prefijo válido (S/A/C/I)"
        );
    }
}
