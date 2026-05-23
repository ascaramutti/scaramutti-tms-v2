package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.cargotypes.service.CargoTypeService;
import com.scaramutti.tms.catalogs.currency.dto.CurrencyResponse;
import com.scaramutti.tms.catalogs.currency.service.CurrencyService;
import com.scaramutti.tms.catalogs.paymentterm.dto.PaymentTermResponse;
import com.scaramutti.tms.catalogs.paymentterm.service.PaymentTermService;
import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.catalogs.quotationservicetype.service.QuotationServiceTypeService;
import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.service.ClientService;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCargoTypeSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationClientSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCurrencySummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationPaymentTermSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;
import com.scaramutti.tms.quotations.mapper.QuotationEmbeddedSummaryMapper;
import com.scaramutti.tms.quotations.service.cmd.CreateQuotationCommand;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.exception.CommonError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Carga y valida la existencia + estado activo de TODAS las entidades
 * referenciadas por un CreateQuotationCommand (client, currency, paymentTerm,
 * serviceTypes, cargoTypes).
 *
 * <p>Pattern: <b>Anti-Corruption Layer</b>. Internamente llama a los services
 * de cada modulo (ClientService.findById, etc.) que devuelven sus Response
 * DTOs completos. Luego convierte cada Response a un <em>Summary</em>
 * especifico del contexto de cotizacion via {@link QuotationEmbeddedSummaryMapper}
 * — preservando solo los campos relevantes y aislando este modulo de cambios
 * futuros en los DTOs de otros modulos.
 *
 * <p>Traduccion de errores: los services tiran NOT_FOUND (CLI-003, CAT-003,
 * etc. con status 404) cuando no encuentran la entidad. Desde POST /quotations
 * eso es un payload invalido (no un recurso ausente en la URL), asi que el
 * loader captura el NOT_FOUND y lo retraduce a {@code COM-001} (400 VALIDATION_FAILED)
 * con un mensaje generico apuntando al field del payload.
 *
 * <p>Chequeo de {@code isActive}: lo hace el loader sobre el Response (antes
 * de convertir a Summary), no los services. Razon: el endpoint REST GET /{id}
 * debe poder devolver entidades inactivas (admin las ve para reactivarlas).
 * Solo desde el contexto "crear cotizacion" tiene sentido rechazar inactivas.
 *
 * <p>Performance: los maps (serviceTypes, cargoTypes) usan {@code findByIds}
 * (1 query con WHERE id IN), no N llamadas a findById.
 */
@ApplicationScoped
public class QuotationDependencyLoaderService {

    @Inject ClientService clientService;
    @Inject CurrencyService currencyService;
    @Inject PaymentTermService paymentTermService;
    @Inject CargoTypeService cargoTypeService;
    @Inject QuotationServiceTypeService quotationServiceTypeService;
    @Inject QuotationEmbeddedSummaryMapper summaryMapper;

    public LoadedDependencies loadFor(CreateQuotationCommand command) {
        QuotationClientSummary client = requireActiveClient(command.clientId());
        QuotationCurrencySummary currency = requireActiveCurrency(command.currencyId());
        QuotationPaymentTermSummary paymentTerm = loadOptionalActivePaymentTerm(command.paymentTermId());

        Map<Integer, QuotationServiceTypeSummary> serviceTypesById =
            loadActiveServiceTypes(collectServiceTypeIds(command));
        Map<Integer, QuotationCargoTypeSummary> cargoTypesById =
            loadActiveCargoTypes(collectCargoTypeIds(command));

        return new LoadedDependencies(
            client, currency, paymentTerm, serviceTypesById, cargoTypesById
        );
    }

    // ---------- Loaders por FK (type-specific) ------------------------------

    private QuotationClientSummary requireActiveClient(Integer id) {
        ClientResponse client = findOrTranslate(() -> clientService.findById(id), fieldNotExists("clientId"));
        if (!Boolean.TRUE.equals(client.isActive())) {
            throw CommonError.VALIDATION_FAILED.toException(fieldInactive("clientId"));
        }
        return summaryMapper.toClientSummary(client);
    }

    private QuotationCurrencySummary requireActiveCurrency(Integer id) {
        CurrencyResponse currency = findOrTranslate(() -> currencyService.findById(id), fieldNotExists("currencyId"));
        if (!Boolean.TRUE.equals(currency.isActive())) {
            throw CommonError.VALIDATION_FAILED.toException(fieldInactive("currencyId"));
        }
        return summaryMapper.toCurrencySummary(currency);
    }

    private QuotationPaymentTermSummary loadOptionalActivePaymentTerm(Integer id) {
        if (id == null) return null;
        PaymentTermResponse paymentTerm = findOrTranslate(
            () -> paymentTermService.findById(id), fieldNotExists("paymentTermId")
        );
        if (!Boolean.TRUE.equals(paymentTerm.isActive())) {
            throw CommonError.VALIDATION_FAILED.toException(fieldInactive("paymentTermId"));
        }
        return summaryMapper.toPaymentTermSummary(paymentTerm);
    }

    /**
     * Carga los serviceTypes referenciados en UNA query. Si algun id pedido
     * no fue devuelto → COM-001 ("serviceTypeId no existe"). Si todos existen
     * pero alguno esta inactivo → COM-001 ("serviceTypeId esta inactivo").
     * Devuelve Map de Summary (no Response) para uso en el assembler.
     */
    private Map<Integer, QuotationServiceTypeSummary> loadActiveServiceTypes(Set<Integer> ids) {
        if (ids.isEmpty()) return new HashMap<>();

        Map<Integer, QuotationServiceTypeResponse> responseMap = new HashMap<>();
        for (QuotationServiceTypeResponse st : quotationServiceTypeService.findByIds(ids)) {
            responseMap.put(st.id(), st);
        }
        for (Integer id : ids) {
            if (!responseMap.containsKey(id)) {
                throw CommonError.VALIDATION_FAILED.toException(fieldNotExists("serviceTypeId"));
            }
        }
        for (QuotationServiceTypeResponse st : responseMap.values()) {
            if (!Boolean.TRUE.equals(st.isActive())) {
                throw CommonError.VALIDATION_FAILED.toException(fieldInactive("serviceTypeId"));
            }
        }
        Map<Integer, QuotationServiceTypeSummary> summaryMap = new HashMap<>();
        for (Map.Entry<Integer, QuotationServiceTypeResponse> e : responseMap.entrySet()) {
            summaryMap.put(e.getKey(), summaryMapper.toServiceTypeSummary(e.getValue()));
        }
        return summaryMap;
    }

    /**
     * Carga los cargoTypes referenciados en UNA query. Misma logica que
     * loadActiveServiceTypes pero para cargoTypeId.
     */
    private Map<Integer, QuotationCargoTypeSummary> loadActiveCargoTypes(Set<Integer> ids) {
        if (ids.isEmpty()) return new HashMap<>();

        Map<Integer, CargoTypeResponse> responseMap = new HashMap<>();
        for (CargoTypeResponse ct : cargoTypeService.findByIds(ids)) {
            responseMap.put(ct.id(), ct);
        }
        for (Integer id : ids) {
            if (!responseMap.containsKey(id)) {
                throw CommonError.VALIDATION_FAILED.toException(fieldNotExists("cargoTypeId"));
            }
        }
        for (CargoTypeResponse ct : responseMap.values()) {
            if (!Boolean.TRUE.equals(ct.isActive())) {
                throw CommonError.VALIDATION_FAILED.toException(fieldInactive("cargoTypeId"));
            }
        }
        Map<Integer, QuotationCargoTypeSummary> summaryMap = new HashMap<>();
        for (Map.Entry<Integer, CargoTypeResponse> e : responseMap.entrySet()) {
            summaryMap.put(e.getKey(), summaryMapper.toCargoTypeSummary(e.getValue()));
        }
        return summaryMap;
    }

    /**
     * Ejecuta el lookup contra el service. Si el service tira NOT_FOUND
     * (status 404), lo retraduce a COM-001 (400) con un mensaje generico
     * apuntando al field del payload — sin exponer el id (el usuario sabe
     * que envio). Cualquier otra ApiException se propaga sin tocar.
     */
    private <T> T findOrTranslate(Supplier<T> lookup, String fieldNotFoundMessage) {
        try {
            return lookup.get();
        } catch (ApiException ex) {
            if (ex.status() == 404) {
                throw CommonError.VALIDATION_FAILED.toException(fieldNotFoundMessage);
            }
            throw ex;
        }
    }

    // ---------- Extraccion de IDs desde el command --------------------------

    private Set<Integer> collectServiceTypeIds(CreateQuotationCommand command) {
        Set<Integer> ids = new HashSet<>();
        for (CreateQuotationCommand.Item item : command.items()) {
            if (item.serviceTypeId() != null) ids.add(item.serviceTypeId());
        }
        return ids;
    }

    private Set<Integer> collectCargoTypeIds(CreateQuotationCommand command) {
        Set<Integer> ids = new HashSet<>();
        for (CreateQuotationCommand.Item item : command.items()) {
            if (item.cargoTypeId() != null) ids.add(item.cargoTypeId());
        }
        return ids;
    }

    /**
     * Helpers de mensaje. Deduplican el patron repetitivo "{field} no existe"
     * / "{field} esta inactivo" para los 5 FK paths.
     */
    private static String fieldNotExists(String field) {
        return field + " no existe";
    }

    private static String fieldInactive(String field) {
        return field + " esta inactivo";
    }

    /**
     * Resultado del loader. Inmutable, contiene los Summaries listos para
     * que el assembler arme el response final sin re-mapear nada.
     */
    public record LoadedDependencies(
        QuotationClientSummary client,
        QuotationCurrencySummary currency,
        QuotationPaymentTermSummary paymentTerm,
        Map<Integer, QuotationServiceTypeSummary> serviceTypesById,
        Map<Integer, QuotationCargoTypeSummary> cargoTypesById
    ) {}
}
