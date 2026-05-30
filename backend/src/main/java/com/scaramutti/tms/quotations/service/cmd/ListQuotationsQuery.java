package com.scaramutti.tms.quotations.service.cmd;

import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;

import java.time.LocalDate;

/**
 * Query interna del service para listQuotations. Encapsula los 11 filtros
 * opcionales + paginacion. Todos los filtros nullable = sin filtro; el repo
 * arma el WHERE dinamico con AND solo de los presentes.
 *
 *  - q:             texto libre ya trim ("" → null) por el ResourceMapper. NO se
 *                   uppercasea (el repo usa ILIKE case-insensitive sobre code,
 *                   client.name, client.ruc, origin, destination). null = sin busqueda.
 *  - status/quotationType: enums; null = sin filtro. El repo filtra por .name().
 *  - clientId/createdById/currencyId: FK exactas; null = sin filtro.
 *  - cargoTypeId/serviceTypeId: "al menos un item (root o hijo) con ese tipo"
 *                   → EXISTS subquery en el repo. null = sin filtro.
 *  - dateFrom/dateTo: rango sobre createdAt en zona Lima (UTC-5). dateTo inclusivo
 *                   del dia completo (el repo lo convierte a < dateTo+1dia). null = sin filtro.
 *  - page:          base 0, validado @Min(0) en el Resource.
 *  - size:          1..100, validado @Min(1)/@Max(100) en el Resource.
 *
 * El service no transforma el Query — solo lo orquesta entre repo, batch loaders
 * y mapper.
 */
public record ListQuotationsQuery(
    String q,
    QuotationStatus status,
    QuotationType quotationType,
    Integer clientId,
    Integer createdById,
    Integer currencyId,
    Integer cargoTypeId,
    Integer serviceTypeId,
    LocalDate dateFrom,
    LocalDate dateTo,
    int page,
    int size
) {}
