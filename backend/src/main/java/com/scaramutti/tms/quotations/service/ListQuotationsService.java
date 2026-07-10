package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.quotations.dto.QuotationSummaryResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationClientSummary;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.cmd.ListQuotationsQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.QuotationItem;
import com.scaramutti.tms.shared.repository.QuotationItemRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository.QuotationSummaryRow;
import com.scaramutti.tms.shared.service.UserLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servicio de listado paginado de cotizaciones (GET /quotations). Read-only,
 * SIN {@code @Transactional} (mismo patron que {@code ClientService.listClients}
 * y {@code GetQuotationService}).
 *
 * <p>Flujo, con <b>4 queries de repositorio</b> (sin N+1 por page size):
 * <ol>
 *   <li>{@code searchPaged(query)} — cabeceras filtradas + paginadas (1 query, con
 *       JOIN a clients/currencies para el filtro q y el client summary).</li>
 *   <li>{@code countSearch(query)} — total para la paginacion (1 query).</li>
 *   <li>{@code findByQuotationIds(ids)} — items de TODA la pagina (1 query). Se
 *       agrupan por quotationId y se calcula totalAmount (via
 *       {@link QuotationCalculatorService#calculateFromEntities} — MISMA funcion
 *       que el detalle → totales identicos) + itemsCount (items root).</li>
 *   <li>{@link UserLookup#requireAllById} — createdBy de toda la pagina (1 query,
 *       dedup). Nota: las asociaciones EAGER de {@code User} (worker, role)
 *       generan SELECTs extra de Hibernate por cada creador distinto K — no escala
 *       con el page size (K = tamaño del equipo de ventas), mismo costo que el
 *       login y el GET by id. Si se quisiera colapsar, {@code batch-fetch-size}
 *       global lo resolveria cross-modulo.</li>
 * </ol>
 *
 * <p>El cliente y la moneda vienen del JOIN en la query de cabeceras (no batch
 * aparte). {@code isExpired} se DERIVA del estado persistido ({@code status == EXPIRED},
 * ADR-005), no se recalcula por fechas.
 */
@ApplicationScoped
public class ListQuotationsService {

    @Inject QuotationRepository quotationRepository;
    @Inject QuotationItemRepository quotationItemRepository;
    @Inject UserLookup userLookup;
    @Inject QuotationCalculatorService calculator;

    public PageResponse<QuotationSummaryResponse> list(ListQuotationsQuery listQuotationsQuery) {
        List<QuotationSummaryRow> rows = quotationRepository.searchPaged(listQuotationsQuery);
        long totalElements = quotationRepository.countSearch(listQuotationsQuery);

        if (rows.isEmpty()) {
            return PageResponse.of(List.of(), listQuotationsQuery.page(), listQuotationsQuery.size(), totalElements);
        }

        Map<Long, List<QuotationItem>> itemsByQuotation = loadItemsByQuotation(rows);
        Map<Integer, UserResponse> usersById = userLookup.requireAllById(
            rows.stream().map(QuotationSummaryRow::createdBy).toList()
        );

        List<QuotationSummaryResponse> content = rows.stream()
            .map(row -> toSummary(
                row,
                itemsByQuotation.getOrDefault(row.id(), List.of()),
                usersById
            ))
            .toList();

        return PageResponse.of(content, listQuotationsQuery.page(), listQuotationsQuery.size(), totalElements);
    }

    /**
     * Batch-load de items de toda la pagina (1 query) agrupados por quotationId.
     */
    private Map<Long, List<QuotationItem>> loadItemsByQuotation(List<QuotationSummaryRow> rows) {
        List<Long> quotationIds = rows.stream().map(QuotationSummaryRow::id).toList();
        return quotationItemRepository.findByQuotationIds(quotationIds).stream()
            .collect(Collectors.groupingBy(item -> item.quotationId));
    }

    private QuotationSummaryResponse toSummary(
            QuotationSummaryRow row,
            List<QuotationItem> items,
            Map<Integer, UserResponse> usersById) {

        // totalAmount con la MISMA funcion que el detalle (snapshot IGV por item).
        QuotationCalculatorService.Totals totals = calculator.calculateFromEntities(items);
        // itemsCount = solo items root (los hijos del Integral no cuentan).
        int itemsCount = (int) items.stream().filter(item -> item.parentItemId == null).count();

        // expiresAt sigue siendo informativo (createdAt + validityDays), lo expone el contrato.
        OffsetDateTime expiresAt = row.createdAt().plusDays(row.validityDays());
        // isExpired se DERIVA del estado persistido (ADR-005): true sii status == EXPIRED
        // (lo mantiene el QuotationExpiryJob). NO se recalcula por fechas en el read-path.
        boolean isExpired = QuotationStatus.EXPIRED.name().equals(row.status());

        return new QuotationSummaryResponse(
            row.id(),
            row.code(),
            QuotationType.valueOf(row.quotationType()),
            QuotationStatus.valueOf(row.status()),
            new QuotationClientSummary(row.clientId(), row.clientName(), row.clientRuc()),
            row.currencyCode(),
            totals.totalAmount(),
            itemsCount,
            row.validityDays(),
            expiresAt,
            isExpired,
            row.origin(),
            row.destination(),
            row.createdAt(),
            usersById.get(row.createdBy())
        );
    }
}
