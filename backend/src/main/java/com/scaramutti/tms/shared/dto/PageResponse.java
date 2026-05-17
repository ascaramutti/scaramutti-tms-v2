package com.scaramutti.tms.shared.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Wrapper de respuesta paginada. Mapea 1:1 al schema `PageMeta + content[]`
 * del contrato OpenAPI (ver api/openapi.yaml#PageMeta y PageOfClient).
 * Generico para reutilizar en listClients, listQuotations, listServices, etc.
 *
 * Los flags derivados (totalPages, first, last, empty, numberOfElements) los
 * calcula la factory `of(content, page, size, totalElements)` — el caller solo
 * provee los 4 inputs primarios. Asi mantenemos consistencia entre endpoints.
 */
public record PageResponse<T>(

    @Schema(description = "Pagina actual (base 0)", example = "0")
    Integer page,

    @Schema(description = "Tamanio de pagina solicitado", example = "20")
    Integer size,

    @Schema(description = "Total de elementos coincidentes con los filtros", example = "137")
    Long totalElements,

    @Schema(description = "Total de paginas", example = "7")
    Integer totalPages,

    @Schema(description = "Cantidad de items en la pagina actual (puede ser < size en la ultima)", example = "20")
    Integer numberOfElements,

    @Schema(description = "Es la primera pagina (page == 0)")
    Boolean first,

    @Schema(description = "Es la ultima pagina (no hay mas resultados despues)")
    Boolean last,

    @Schema(description = "La pagina actual esta vacia (sin resultados)")
    Boolean empty,

    @Schema(description = "Items de la pagina")
    List<T> content
) {

    /**
     * Factory: construye PageResponse a partir del minimo necesario.
     * Calcula totalPages con ceil(totalElements / size). first/last/empty
     * derivados sin necesidad de un re-query.
     *
     * Casos clave cubiertos:
     *  - totalElements == 0 → totalPages=0, first=true, last=true, empty=true.
     *  - page mas alla de totalPages-1 (overflow) → content vacio, last=true.
     *  - Pagina unica con resultados → first=true, last=true.
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = (totalElements == 0) ? 0 : (int) Math.ceil((double) totalElements / size);
        int numberOfElements = content.size();
        boolean empty = numberOfElements == 0;
        boolean first = page == 0;
        boolean last = totalPages == 0 || page >= totalPages - 1;
        return new PageResponse<>(
            page, size, totalElements, totalPages, numberOfElements,
            first, last, empty, content
        );
    }
}
