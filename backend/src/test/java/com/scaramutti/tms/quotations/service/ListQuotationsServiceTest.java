package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.quotations.dto.QuotationSummaryResponse;
import com.scaramutti.tms.quotations.service.cmd.ListQuotationsQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.QuotationItem;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.repository.QuotationItemRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository.QuotationSummaryRow;
import com.scaramutti.tms.shared.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit del ListQuotationsService. Mockea repo/itemRepo/userRepo/calculator/mapper.
 * Aisla: mapeo de PageMeta, itemsCount root-only, totalAmount del calculator,
 * isExpired runtime, batch de users, y short-circuit en resultado vacío.
 *
 * NO testea el SQL (cubierto por integration). Los stubs comunes viven en
 * {@link #stubHappyPath} (Mockito strict — invocado solo donde se necesita).
 */
@ExtendWith(MockitoExtension.class)
class ListQuotationsServiceTest {

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationItemRepository quotationItemRepository;
    @Mock UserRepository userRepository;
    @Mock QuotationCalculatorService calculator;
    @Mock AuthServiceMapper authServiceMapper;

    @InjectMocks ListQuotationsService service;

    private ListQuotationsQuery query;

    @BeforeEach
    void initQuery() {
        // page 0, size 20, sin filtros.
        query = new ListQuotationsQuery(null, null, null, null, null, null, null, null, null, null, 0, 20);
    }

    private QuotationSummaryRow row(long id, OffsetDateTime createdAt, int validityDays, int createdBy) {
        return new QuotationSummaryRow(
            id, "2026-" + String.format("%05d", id), "TRANSPORTE", "DRAFT",
            1, "ACME SAC", "20100100100", "USD", validityDays,
            "Lima", "Cusco", createdAt, createdBy
        );
    }

    private QuotationItem rootItem(long quotationId) {
        QuotationItem it = new QuotationItem();
        it.quotationId = quotationId;
        it.parentItemId = null;
        return it;
    }

    private QuotationItem childItem(long quotationId) {
        QuotationItem it = new QuotationItem();
        it.quotationId = quotationId;
        it.parentItemId = 99L;
        return it;
    }

    private User user(int id) {
        User u = new User();
        u.id = id;
        return u;
    }

    private UserResponse userResponse(int id) {
        return new UserResponse(id, "admin", "Admin Sistema", "Admin", "admin", true);
    }

    /** Stubs default del happy path: 1 fila fresca, 1 item root, 1 user. */
    private void stubHappyPath(List<QuotationSummaryRow> rows, long total) {
        when(quotationRepository.searchPaged(query)).thenReturn(rows);
        when(quotationRepository.countSearch(query)).thenReturn(total);
        when(quotationItemRepository.findByQuotationIds(any()))
            .thenReturn(rows.stream().map(r -> rootItem(r.id())).toList());
        when(userRepository.list(anyString(), any(Object.class))).thenReturn(List.of(user(42)));
        when(authServiceMapper.toUserResponse(any())).thenReturn(userResponse(42));
        when(calculator.calculateFromEntities(any()))
            .thenReturn(new QuotationCalculatorService.Totals(
                new BigDecimal("1000.00"), new BigDecimal("180.00"), new BigDecimal("1180.00")));
    }

    // ---------- Resultado vacío ---------------------------------------------

    @Test
    void list_emptyResult_returnsEmptyPageResponse() {
        when(quotationRepository.searchPaged(query)).thenReturn(List.of());
        when(quotationRepository.countSearch(query)).thenReturn(0L);

        PageResponse<QuotationSummaryResponse> result = service.list(query);

        assertTrue(result.content().isEmpty());
        assertEquals(0L, result.totalElements());
        assertTrue(result.empty());
        assertTrue(result.first());
        assertTrue(result.last());
        // Short-circuit: no carga items ni users si no hay filas.
        verifyNoInteractions(quotationItemRepository, userRepository, calculator, authServiceMapper);
    }

    // ---------- Mapeo + PageMeta --------------------------------------------

    @Test
    void list_singleRow_mapsAllFields() {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        stubHappyPath(List.of(row(1L, createdAt, 15, 42)), 1L);

        PageResponse<QuotationSummaryResponse> result = service.list(query);

        assertEquals(1, result.content().size());
        QuotationSummaryResponse summary = result.content().get(0);
        assertEquals(1L, summary.id());
        assertEquals("USD", summary.currencyCode());
        assertEquals(new BigDecimal("1180.00"), summary.totalAmount());
        assertEquals(1, summary.itemsCount());
        assertEquals(1, summary.client().id());
        assertEquals("ACME SAC", summary.client().name());
        assertEquals(42, summary.createdBy().id());
        assertEquals(createdAt.plusDays(15), summary.expiresAt());
    }

    @Test
    void list_multiplePages_firstPageMeta() {
        stubHappyPath(List.of(row(1L, OffsetDateTime.now(ZoneOffset.UTC), 15, 42)), 50L);

        PageResponse<QuotationSummaryResponse> result = service.list(query);

        assertEquals(0, result.page());
        assertEquals(50L, result.totalElements());
        assertEquals(3, result.totalPages());   // ceil(50/20)
        assertTrue(result.first());
        assertFalse(result.last());
    }

    @Test
    void list_lastPage_firstFalseLastTrue() {
        ListQuotationsQuery lastPageQuery =
            new ListQuotationsQuery(null, null, null, null, null, null, null, null, null, null, 2, 20);
        when(quotationRepository.searchPaged(lastPageQuery))
            .thenReturn(List.of(row(1L, OffsetDateTime.now(ZoneOffset.UTC), 15, 42)));
        when(quotationRepository.countSearch(lastPageQuery)).thenReturn(50L);
        when(quotationItemRepository.findByQuotationIds(any())).thenReturn(List.of(rootItem(1L)));
        when(userRepository.list(anyString(), any(Object.class))).thenReturn(List.of(user(42)));
        when(authServiceMapper.toUserResponse(any())).thenReturn(userResponse(42));
        when(calculator.calculateFromEntities(any()))
            .thenReturn(new QuotationCalculatorService.Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        PageResponse<QuotationSummaryResponse> result = service.list(lastPageQuery);

        assertFalse(result.first());
        assertTrue(result.last());
    }

    // ---------- itemsCount root-only ----------------------------------------

    @Test
    void list_itemsCount_countsRootItemsOnly() {
        // Una cotización con 1 root + 2 hijos → itemsCount = 1 (no 3).
        when(quotationRepository.searchPaged(query))
            .thenReturn(List.of(row(1L, OffsetDateTime.now(ZoneOffset.UTC), 15, 42)));
        when(quotationRepository.countSearch(query)).thenReturn(1L);
        when(quotationItemRepository.findByQuotationIds(any()))
            .thenReturn(List.of(rootItem(1L), childItem(1L), childItem(1L)));
        when(userRepository.list(anyString(), any(Object.class))).thenReturn(List.of(user(42)));
        when(authServiceMapper.toUserResponse(any())).thenReturn(userResponse(42));
        when(calculator.calculateFromEntities(any()))
            .thenReturn(new QuotationCalculatorService.Totals(
                new BigDecimal("8000.00"), new BigDecimal("1440.00"), new BigDecimal("9440.00")));

        PageResponse<QuotationSummaryResponse> result = service.list(query);

        assertEquals(1, result.content().get(0).itemsCount());
        assertEquals(new BigDecimal("9440.00"), result.content().get(0).totalAmount());
    }

    // ---------- isExpired runtime -------------------------------------------

    @Test
    void list_isExpiredTrue_forOldQuotation() {
        OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
        stubHappyPath(List.of(row(1L, old, 15, 42)), 1L);   // venció hace 15 días

        PageResponse<QuotationSummaryResponse> result = service.list(query);

        assertTrue(result.content().get(0).isExpired());
    }

    @Test
    void list_isExpiredFalse_forFreshQuotation() {
        OffsetDateTime fresh = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        stubHappyPath(List.of(row(1L, fresh, 15, 42)), 1L);   // vigente

        PageResponse<QuotationSummaryResponse> result = service.list(query);

        assertFalse(result.content().get(0).isExpired());
    }

    // ---------- Batch de users (sin N+1) ------------------------------------

    @Test
    void list_loadsUsersInOneBatchQuery() {
        // 2 filas con el mismo createdBy → 1 sola query de users (Set dedup).
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        when(quotationRepository.searchPaged(query))
            .thenReturn(List.of(row(1L, now, 15, 42), row(2L, now, 15, 42)));
        when(quotationRepository.countSearch(query)).thenReturn(2L);
        when(quotationItemRepository.findByQuotationIds(any()))
            .thenReturn(List.of(rootItem(1L), rootItem(2L)));
        when(userRepository.list(anyString(), any(Object.class))).thenReturn(List.of(user(42)));
        when(authServiceMapper.toUserResponse(any())).thenReturn(userResponse(42));
        when(calculator.calculateFromEntities(any()))
            .thenReturn(new QuotationCalculatorService.Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        service.list(query);

        // Una sola query de items (batch) y una sola de users (batch) — sin N+1.
        verify(quotationItemRepository, times(1)).findByQuotationIds(any());
        verify(userRepository, times(1)).list(anyString(), any(Object.class));
    }
}
