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
import com.scaramutti.tms.quotations.service.cmd.SaveQuotationCommand;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.exception.CommonError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Carga las entidades referenciadas por una cotizacion (client, currency,
 * paymentTerm, serviceTypes, cargoTypes) y las convierte a Summaries del
 * bounded context Quotations.
 *
 * <p>Dos entry points publicos paralelos, diferenciados por el flag
 * {@code isCreatePath}:
 * <ul>
 *   <li>{@link #loadFor(SaveQuotationCommand)} — para CREATE (POST). Valida
 *       que TODAS las FKs esten {@code isActive=true}. Una FK inactiva o
 *       inexistente en el payload de creacion es invalido (input del usuario)
 *       — se traduce a {@code COM-001} (400 VALIDATION_FAILED).</li>
 *   <li>{@link #loadByIds(Integer, Integer, Integer, Set, Set)} — para READ
 *       (GET /{id} y futuro listado). NO valida isActive: una cotizacion vieja
 *       puede referenciar entidades que se desactivaron despues — el frontend
 *       igual debe poder mostrarla. Una FK INEXISTENTE en este path indica
 *       <b>bug de integridad referencial</b> (alguien borro un row del catalogo
 *       que la cotizacion referenciaba): se loguea como error y se traduce a
 *       {@code COM-500} (500 INTERNAL_ERROR) — no es input del usuario.</li>
 * </ul>
 *
 * <p>Pattern: <b>Anti-Corruption Layer</b>. Internamente llama a los services
 * de cada modulo (ClientService.findById, etc.) que devuelven sus Response
 * DTOs completos. Luego convierte cada Response a un <em>Summary</em>
 * especifico del contexto de cotizacion via {@link QuotationEmbeddedSummaryMapper}.
 *
 * <p>Performance: los maps (serviceTypes, cargoTypes) usan {@code findByIds}
 * (1 query con WHERE id IN), no N llamadas a findById.
 */
@ApplicationScoped
public class QuotationDependencyLoaderService {

    private static final Logger LOG = Logger.getLogger(QuotationDependencyLoaderService.class);

    @Inject ClientService clientService;
    @Inject CurrencyService currencyService;
    @Inject PaymentTermService paymentTermService;
    @Inject CargoTypeService cargoTypeService;
    @Inject QuotationServiceTypeService quotationServiceTypeService;
    @Inject QuotationEmbeddedSummaryMapper summaryMapper;

    /**
     * Carga dependencias para CREATE. Valida isActive en TODAS las FKs.
     * FK inexistente → COM-001 (400) "{field} no existe".
     */
    public LoadedDependencies loadFor(SaveQuotationCommand command) {
        return loadInternal(
            command.clientId(),
            command.currencyId(),
            command.paymentTermId(),
            collectServiceTypeIds(command),
            collectCargoTypeIds(command),
            true
        );
    }

    /**
     * Carga dependencias para READ. NO valida isActive — cotizaciones viejas
     * con entidades desactivadas se muestran igual. FK inexistente → loguea
     * error y tira COM-500 (500) — bug de integridad referencial.
     */
    public LoadedDependencies loadByIds(
        Integer clientId,
        Integer currencyId,
        Integer paymentTermId,
        Set<Integer> serviceTypeIds,
        Set<Integer> cargoTypeIds
    ) {
        return loadInternal(clientId, currencyId, paymentTermId, serviceTypeIds, cargoTypeIds, false);
    }

    // ---------- Implementacion compartida -----------------------------------

    private LoadedDependencies loadInternal(
        Integer clientId,
        Integer currencyId,
        Integer paymentTermId,
        Set<Integer> serviceTypeIds,
        Set<Integer> cargoTypeIds,
        boolean isCreatePath
    ) {
        QuotationClientSummary client = loadClient(clientId, isCreatePath);
        QuotationCurrencySummary currency = loadCurrency(currencyId, isCreatePath);
        QuotationPaymentTermSummary paymentTerm = loadOptionalPaymentTerm(paymentTermId, isCreatePath);
        Map<Integer, QuotationServiceTypeSummary> serviceTypesById =
            loadServiceTypes(serviceTypeIds, isCreatePath);
        Map<Integer, QuotationCargoTypeSummary> cargoTypesById =
            loadCargoTypes(cargoTypeIds, isCreatePath);

        return new LoadedDependencies(
            client, currency, paymentTerm, serviceTypesById, cargoTypesById
        );
    }

    // ---------- Loaders por FK (type-specific) ------------------------------

    private QuotationClientSummary loadClient(Integer id, boolean isCreatePath) {
        ClientResponse client = findOrTranslate(() -> clientService.findById(id), "clientId", isCreatePath);
        if (isCreatePath && !Boolean.TRUE.equals(client.isActive())) {
            throw CommonError.VALIDATION_FAILED.toException(fieldInactive("clientId"));
        }
        return summaryMapper.toClientSummary(client);
    }

    private QuotationCurrencySummary loadCurrency(Integer id, boolean isCreatePath) {
        CurrencyResponse currency = findOrTranslate(() -> currencyService.findById(id), "currencyId", isCreatePath);
        if (isCreatePath && !Boolean.TRUE.equals(currency.isActive())) {
            throw CommonError.VALIDATION_FAILED.toException(fieldInactive("currencyId"));
        }
        return summaryMapper.toCurrencySummary(currency);
    }

    private QuotationPaymentTermSummary loadOptionalPaymentTerm(Integer id, boolean isCreatePath) {
        if (id == null) return null;
        PaymentTermResponse paymentTerm = findOrTranslate(
            () -> paymentTermService.findById(id), "paymentTermId", isCreatePath
        );
        if (isCreatePath && !Boolean.TRUE.equals(paymentTerm.isActive())) {
            throw CommonError.VALIDATION_FAILED.toException(fieldInactive("paymentTermId"));
        }
        return summaryMapper.toPaymentTermSummary(paymentTerm);
    }

    /**
     * Carga los serviceTypes referenciados en UNA query. Si algun id pedido
     * no fue devuelto → COM-001 (CREATE) o COM-500 con log (READ). Si
     * {@code isCreatePath} y alguno esta inactivo → COM-001.
     */
    private Map<Integer, QuotationServiceTypeSummary> loadServiceTypes(Set<Integer> ids, boolean isCreatePath) {
        if (ids.isEmpty()) return new HashMap<>();

        Map<Integer, QuotationServiceTypeResponse> responseMap = new HashMap<>();
        for (QuotationServiceTypeResponse st : quotationServiceTypeService.findByIds(ids)) {
            responseMap.put(st.id(), st);
        }
        for (Integer id : ids) {
            if (!responseMap.containsKey(id)) {
                throwFkNotFound("serviceTypeId", isCreatePath);
            }
        }
        if (isCreatePath) {
            for (QuotationServiceTypeResponse st : responseMap.values()) {
                if (!Boolean.TRUE.equals(st.isActive())) {
                    throw CommonError.VALIDATION_FAILED.toException(fieldInactive("serviceTypeId"));
                }
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
     * {@link #loadServiceTypes(Set, boolean)} pero para cargoTypeId.
     */
    private Map<Integer, QuotationCargoTypeSummary> loadCargoTypes(Set<Integer> ids, boolean isCreatePath) {
        if (ids.isEmpty()) return new HashMap<>();

        Map<Integer, CargoTypeResponse> responseMap = new HashMap<>();
        for (CargoTypeResponse ct : cargoTypeService.findByIds(ids)) {
            responseMap.put(ct.id(), ct);
        }
        for (Integer id : ids) {
            if (!responseMap.containsKey(id)) {
                throwFkNotFound("cargoTypeId", isCreatePath);
            }
        }
        if (isCreatePath) {
            for (CargoTypeResponse ct : responseMap.values()) {
                if (!Boolean.TRUE.equals(ct.isActive())) {
                    throw CommonError.VALIDATION_FAILED.toException(fieldInactive("cargoTypeId"));
                }
            }
        }
        Map<Integer, QuotationCargoTypeSummary> summaryMap = new HashMap<>();
        for (Map.Entry<Integer, CargoTypeResponse> e : responseMap.entrySet()) {
            summaryMap.put(e.getKey(), summaryMapper.toCargoTypeSummary(e.getValue()));
        }
        return summaryMap;
    }

    /**
     * Ejecuta el lookup contra el service. Si tira NOT_FOUND (404), delega
     * a {@link #throwFkNotFound(String, boolean)} para decidir si traducir
     * a COM-001 (CREATE) o COM-500 con log (READ). Cualquier otra ApiException
     * se propaga sin tocar.
     */
    private <T> T findOrTranslate(Supplier<T> lookup, String fieldName, boolean isCreatePath) {
        try {
            return lookup.get();
        } catch (ApiException ex) {
            if (ex.status() == 404) {
                throwFkNotFound(fieldName, isCreatePath);
            }
            throw ex;
        }
    }

    /**
     * Tira la excepcion apropiada cuando una FK no existe.
     *
     * <p><b>CREATE path</b>: el usuario envio un id invalido en el payload →
     * COM-001 (400 VALIDATION_FAILED) con mensaje "{field} no existe". El
     * frontend muestra el error de validacion.
     *
     * <p><b>READ path</b>: el id viene de una entity persistida — alguien
     * borro un row del catalogo que la cotizacion referenciaba (orfandad).
     * Es <b>bug de integridad referencial</b>, no input invalido. Loguea
     * error y tira COM-500 — alerta para soporte/operaciones.
     */
    private void throwFkNotFound(String fieldName, boolean isCreatePath) {
        if (isCreatePath) {
            throw CommonError.VALIDATION_FAILED.toException(fieldNotExists(fieldName));
        }
        LOG.errorf("Orphan FK in quotation READ path: field=%s — borrado del catalogo dejo cotizacion referenciando entidad inexistente", fieldName);
        throw CommonError.INTERNAL_ERROR.toException(
            "La cotizacion tiene una referencia rota (" + fieldName + " inexistente). Reporte a soporte."
        );
    }

    // ---------- Extraccion de IDs desde el command --------------------------

    private Set<Integer> collectServiceTypeIds(SaveQuotationCommand command) {
        Set<Integer> ids = new HashSet<>();
        for (SaveQuotationCommand.Item item : command.items()) {
            if (item.serviceTypeId() != null) ids.add(item.serviceTypeId());
        }
        return ids;
    }

    private Set<Integer> collectCargoTypeIds(SaveQuotationCommand command) {
        Set<Integer> ids = new HashSet<>();
        for (SaveQuotationCommand.Item item : command.items()) {
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
