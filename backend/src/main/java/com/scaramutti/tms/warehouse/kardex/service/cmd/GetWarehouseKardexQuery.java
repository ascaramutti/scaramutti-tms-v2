package com.scaramutti.tms.warehouse.kardex.service.cmd;

import java.time.LocalDate;

/**
 * Query interna del kardex (GET /warehouse/products/{id}/kardex).
 *
 *  - productId: requerido, valida existencia (WH-003) en el service.
 *  - dateFrom/dateTo: rango opcional sobre movedAt en zona Lima (UTC-5), ambos
 *    inclusive del dia completo (el repo interpreta dateTo como
 *    {@code < dateTo+1dia}). null = sin filtro. dateFrom &gt; dateTo NO es un
 *    400: produce una pagina vacia (mismo criterio que ListQuotationsQuery,
 *    que tampoco valida cross-field).
 *  - page/size: paginacion, validada @Min/@Max en el Resource.
 */
public record GetWarehouseKardexQuery(
    Integer productId,
    LocalDate dateFrom,
    LocalDate dateTo,
    int page,
    int size
) {}
