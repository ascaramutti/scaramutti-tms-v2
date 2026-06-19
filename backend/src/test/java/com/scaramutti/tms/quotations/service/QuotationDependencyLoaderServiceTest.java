package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.cargotypes.CargoTypesError;
import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.cargotypes.service.CargoTypeService;
import com.scaramutti.tms.catalogs.CatalogsError;
import com.scaramutti.tms.catalogs.currency.dto.CurrencyResponse;
import com.scaramutti.tms.catalogs.currency.service.CurrencyService;
import com.scaramutti.tms.catalogs.paymentterm.dto.PaymentTermResponse;
import com.scaramutti.tms.catalogs.paymentterm.service.PaymentTermService;
import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.catalogs.quotationservicetype.service.QuotationServiceTypeService;
import com.scaramutti.tms.clients.ClientsError;
import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.service.ClientService;
import com.scaramutti.tms.quotations.mapper.QuotationEmbeddedSummaryMapper;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService.LoadedDependencies;
import com.scaramutti.tms.quotations.service.cmd.SaveQuotationCommand;
import com.scaramutti.tms.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit del QuotationDependencyLoaderService.
 *
 * Cubre las 5 FK paths que carga + sus errores COM-001 + el mapeo Response→Summary:
 *  - Happy path: cliente, currency, paymentTerm, serviceTypes (multiples),
 *    cargoTypes (multiples). Verifica que retorna Summaries con subset correcto.
 *  - paymentTermId NULL → paymentTerm null, sin lookup.
 *  - clientId / currencyId / paymentTermId / serviceTypeId / cargoTypeId
 *    inexistentes → COM-001 con field name generico (sin valor).
 *  - isActive=false en cualquier FK → COM-001 con "inactivo".
 *  - Items sin cargoTypeId → no llama a CargoTypeService.
 *  - Items con serviceTypeIds repetidos → un solo findByIds (Set dedup).
 *  - Early-exit: si client falla, no se llama a currency.
 *
 * El mapper se instancia REAL (no mock) — es 1:1 trivial, mockearlo agrega
 * stubs sin valor. Lo asignamos directamente al field package-private del loader.
 */
@ExtendWith(MockitoExtension.class)
class QuotationDependencyLoaderServiceTest {

    @Mock ClientService clientService;
    @Mock CurrencyService currencyService;
    @Mock PaymentTermService paymentTermService;
    @Mock CargoTypeService cargoTypeService;
    @Mock QuotationServiceTypeService quotationServiceTypeService;

    @InjectMocks QuotationDependencyLoaderService loader;

    @BeforeEach
    void initMapper() {
        loader.summaryMapper = Mappers.getMapper(QuotationEmbeddedSummaryMapper.class);
    }

    private SaveQuotationCommand.Item item(Integer serviceTypeId, Integer cargoTypeId) {
        return new SaveQuotationCommand.Item(
            null, null, serviceTypeId, cargoTypeId, null,
            null, null, null, null, 1, new BigDecimal("100"), null,
            null, null
        );
    }

    private SaveQuotationCommand command(Integer paymentTermId, List<SaveQuotationCommand.Item> items) {
        return new SaveQuotationCommand(
            QuotationType.TRANSPORTE, 1, "contact", null, 1, paymentTermId,
            null, 15, "Lima", "Cusco", null, null, items, null
        );
    }

    private ClientResponse sampleClient() {
        return new ClientResponse(1, "ACME", "20100100100", null, null, true, OffsetDateTime.now());
    }

    private ClientResponse inactiveClient() {
        return new ClientResponse(1, "ACME", "20100100100", null, null, false, OffsetDateTime.now());
    }

    private CurrencyResponse sampleCurrency() {
        return new CurrencyResponse(1, "USD", "$", "Dolar", true);
    }

    private CurrencyResponse inactiveCurrency() {
        return new CurrencyResponse(1, "USD", "$", "Dolar", false);
    }

    private PaymentTermResponse samplePaymentTerm() {
        return new PaymentTermResponse(1, "Contado", 0, true);
    }

    private PaymentTermResponse inactivePaymentTerm() {
        return new PaymentTermResponse(1, "Contado", 0, false);
    }

    private QuotationServiceTypeResponse sampleServiceType(int id, String code) {
        return new QuotationServiceTypeResponse(id, code, "test-" + code, "SERVICIO", null, true);
    }

    private QuotationServiceTypeResponse inactiveServiceType(int id, String code) {
        return new QuotationServiceTypeResponse(id, code, "test-" + code, "SERVICIO", null, false);
    }

    private CargoTypeResponse sampleCargoType(int id, String name) {
        return new CargoTypeResponse(id, name, null, null, null, null, null, true);
    }

    private CargoTypeResponse inactiveCargoType(int id, String name) {
        return new CargoTypeResponse(id, name, null, null, null, null, null, false);
    }

    // ---------- Happy path ---------------------------------------------------

    @Test
    void loadFor_happyPath_loadsAllDependenciesAsSummaries() {
        var cmd = command(1, List.of(item(1, 5), item(2, null)));

        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(paymentTermService.findById(1)).thenReturn(samplePaymentTerm());
        when(quotationServiceTypeService.findByIds(any()))
            .thenReturn(List.of(sampleServiceType(1, "SCB"), sampleServiceType(2, "AGRU")));
        when(cargoTypeService.findByIds(any()))
            .thenReturn(List.of(sampleCargoType(5, "Maquinaria")));

        LoadedDependencies deps = loader.loadFor(cmd);

        assertNotNull(deps);
        // Summary fields, no Response fields:
        assertNotNull(deps.client());
        assertEquals(1, deps.client().id());
        assertEquals("ACME", deps.client().name());
        assertEquals("20100100100", deps.client().ruc());
        // ClientSummary NO tiene phone, contactName, isActive ni createdAt — esto es
        // intencional y forzado por la signature del record. Si alguien intentara
        // hacer deps.client().isActive() no compila — eso es la garantia ACL.

        assertNotNull(deps.currency());
        assertEquals("USD", deps.currency().code());

        assertNotNull(deps.paymentTerm());
        assertEquals("Contado", deps.paymentTerm().name());

        assertEquals(2, deps.serviceTypesById().size());
        assertTrue(deps.serviceTypesById().containsKey(1));
        assertEquals("SERVICIO", deps.serviceTypesById().get(1).kind());

        assertEquals(1, deps.cargoTypesById().size());
        assertEquals("Maquinaria", deps.cargoTypesById().get(5).name());
    }

    // ---------- paymentTerm opcional ----------------------------------------

    @Test
    void loadFor_paymentTermIdNull_returnsNullWithoutLookup() {
        var cmd = command(null, List.of(item(1, null)));

        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(quotationServiceTypeService.findByIds(any()))
            .thenReturn(List.of(sampleServiceType(1, "SCB")));

        LoadedDependencies deps = loader.loadFor(cmd);

        assertNull(deps.paymentTerm());
        verify(paymentTermService, never()).findById(anyInt());
    }

    // ---------- Errores de FK inexistente (COM-001) -------------------------

    @Test
    void loadFor_clientIdNotFound_throwsCOM001() {
        var cmd = command(null, List.of(item(1, null)));

        when(clientService.findById(1)).thenThrow(ClientsError.NOT_FOUND.toException());

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("clientId"));
        assertTrue(ex.getMessage().contains("no existe"));

        verify(currencyService, never()).findById(anyInt());
    }

    @Test
    void loadFor_currencyIdNotFound_throwsCOM001() {
        var cmd = command(null, List.of(item(1, null)));

        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenThrow(CatalogsError.CURRENCY_NOT_FOUND.toException());

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("currencyId"));

        verify(paymentTermService, never()).findById(anyInt());
    }

    @Test
    void loadFor_paymentTermIdProvidedButNotFound_throwsCOM001() {
        var cmd = command(99, List.of(item(1, null)));

        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(paymentTermService.findById(99)).thenThrow(CatalogsError.PAYMENT_TERM_NOT_FOUND.toException());

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("paymentTermId"));
    }

    @Test
    void loadFor_serviceTypeIdNotFound_throwsCOM001() {
        var cmd = command(null, List.of(item(999, null)));

        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(quotationServiceTypeService.findByIds(any())).thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("serviceTypeId"));
    }

    @Test
    void loadFor_cargoTypeIdNotFound_throwsCOM001() {
        var cmd = command(null, List.of(item(1, 999)));

        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(quotationServiceTypeService.findByIds(any())).thenReturn(List.of(sampleServiceType(1, "SCB")));
        when(cargoTypeService.findByIds(any())).thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("cargoTypeId"));
    }

    // ---------- Dedup de IDs repetidos --------------------------------------

    @Test
    void loadFor_duplicateServiceTypeIds_loadsOnlyOnce() {
        var cmd = command(null, List.of(item(1, null), item(1, null), item(1, null)));

        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(quotationServiceTypeService.findByIds(any())).thenReturn(List.of(sampleServiceType(1, "SCB")));

        LoadedDependencies deps = loader.loadFor(cmd);

        assertEquals(1, deps.serviceTypesById().size());
        verify(quotationServiceTypeService).findByIds(any());
    }

    @Test
    void loadFor_noCargoTypeIdsInItems_doesNotCallCargoService() {
        var cmd = command(null, List.of(item(1, null), item(2, null)));

        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(quotationServiceTypeService.findByIds(any()))
            .thenReturn(List.of(sampleServiceType(1, "SCB"), sampleServiceType(2, "AGRU")));

        LoadedDependencies deps = loader.loadFor(cmd);

        assertTrue(deps.cargoTypesById().isEmpty());
        verify(cargoTypeService, never()).findByIds(any());
    }

    // ---------- isActive=false en FKs (COM-001) -----------------------------

    @Test
    void loadFor_clientInactive_throwsCOM001() {
        var cmd = command(null, List.of(item(1, null)));
        when(clientService.findById(1)).thenReturn(inactiveClient());

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("clientId"));
        assertTrue(ex.getMessage().toLowerCase().contains("inactivo"));
    }

    @Test
    void loadFor_currencyInactive_throwsCOM001() {
        var cmd = command(null, List.of(item(1, null)));
        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(inactiveCurrency());

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("currencyId"));
        assertTrue(ex.getMessage().toLowerCase().contains("inactivo"));
    }

    @Test
    void loadFor_paymentTermInactive_throwsCOM001() {
        var cmd = command(1, List.of(item(1, null)));
        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(paymentTermService.findById(1)).thenReturn(inactivePaymentTerm());

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("paymentTermId"));
        assertTrue(ex.getMessage().toLowerCase().contains("inactivo"));
    }

    @Test
    void loadFor_serviceTypeInactive_throwsCOM001() {
        var cmd = command(null, List.of(item(1, null)));
        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(quotationServiceTypeService.findByIds(any())).thenReturn(List.of(inactiveServiceType(1, "SCB")));

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("serviceTypeId"));
        assertTrue(ex.getMessage().toLowerCase().contains("inactivo"));
    }

    @Test
    void loadFor_cargoTypeInactive_throwsCOM001() {
        var cmd = command(null, List.of(item(1, 5)));
        when(clientService.findById(1)).thenReturn(sampleClient());
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(quotationServiceTypeService.findByIds(any())).thenReturn(List.of(sampleServiceType(1, "SCB")));
        when(cargoTypeService.findByIds(any())).thenReturn(List.of(inactiveCargoType(5, "Maquinaria")));

        ApiException ex = assertThrows(ApiException.class, () -> loader.loadFor(cmd));
        assertEquals("COM-001", ex.code());
        assertTrue(ex.getMessage().contains("cargoTypeId"));
        assertTrue(ex.getMessage().toLowerCase().contains("inactivo"));
    }

    // ---------- Summary mapping correctness ---------------------------------

    @Test
    void loadFor_clientResponse_mapsToSummaryWithSubsetOnly() {
        // El ClientResponse trae phone, contactName, isActive, createdAt.
        // El Summary SOLO debe tener id, name, ruc.
        var cmd = command(null, List.of(item(1, null)));
        ClientResponse fullClient = new ClientResponse(
            1, "ACME", "20100100100", "987654321", "Juan Master",
            true, OffsetDateTime.parse("2026-01-01T00:00:00Z")
        );

        when(clientService.findById(1)).thenReturn(fullClient);
        when(currencyService.findById(1)).thenReturn(sampleCurrency());
        when(quotationServiceTypeService.findByIds(Set.of(1))).thenReturn(List.of(sampleServiceType(1, "SCB")));

        LoadedDependencies deps = loader.loadFor(cmd);

        // Solo 3 campos en el Summary, ninguno de los "leaky":
        assertEquals(1, deps.client().id());
        assertEquals("ACME", deps.client().name());
        assertEquals("20100100100", deps.client().ruc());
        // No hay deps.client().phone() ni .contactName() etc. — el record no los tiene.
    }
}
