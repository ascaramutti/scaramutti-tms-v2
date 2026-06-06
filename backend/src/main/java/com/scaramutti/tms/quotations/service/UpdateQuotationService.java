package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.quotations.QuotationsError;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.dto.QuotationStandbyCostResponse;
import com.scaramutti.tms.quotations.mapper.QuotationServiceMapper;
import com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService.LoadedDependencies;
import com.scaramutti.tms.quotations.service.cmd.SaveQuotationCommand;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.entity.QuotationItem;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.QuotationItemRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import com.scaramutti.tms.shared.repository.QuotationStandbyCostRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Facade del flow de EDICION de cotizacion (PUT /quotations/{id}). Espejo de
 * {@link CreateQuotationService}: comparte sus piezas (dependency loader, validator,
 * calculator, persistencia de items, assembler) pero con orquestacion propia:
 * <ul>
 *   <li>optimistic locking via {@code If-Match} (412 COM-004 si la version no coincide),</li>
 *   <li>rechazo de campos inmutables (quotationType, clientId → 400 QUO-004),</li>
 *   <li>REPLACE de items (borra los viejos e inserta los nuevos),</li>
 *   <li>SIN anti-duplicado ni generacion de code (eso es solo del CREATE).</li>
 * </ul>
 *
 * <p>Campos inmutables preservados: {@code code}, {@code createdBy}, {@code createdAt},
 * {@code quotationType}, {@code status}, {@code clientId}. El {@code updatedBy} pasa al
 * usuario actual; el {@code updatedAt} lo regenera {@code @PreUpdate} (sirve de ETag).
 */
@ApplicationScoped
public class UpdateQuotationService {

    private static final Logger LOG = Logger.getLogger(UpdateQuotationService.class);

    @Inject QuotationRepository quotationRepository;
    @Inject QuotationItemRepository quotationItemRepository;
    @Inject QuotationStandbyCostRepository quotationStandbyCostRepository;
    @Inject UserRepository userRepository;

    @Inject QuotationDependencyLoaderService dependencyLoader;
    @Inject QuotationValidatorService validator;
    @Inject QuotationCalculatorService calculator;
    @Inject QuotationItemPersistenceService itemPersistence;
    @Inject QuotationResponseAssemblerService assembler;
    @Inject AuthServiceMapper authServiceMapper;
    @Inject QuotationServiceMapper quotationServiceMapper;

    @Inject CurrentUser currentUser;

    @Transactional
    public QuotationResponse updateQuotation(Long id, String ifMatch, SaveQuotationCommand command) {
        Integer userId = currentUser.requireId();

        // 1. Cargar (404 si no existe). Precede al If-Match: no se versiona lo inexistente.
        Quotation quotation = quotationRepository.findByIdOptional(id)
            .orElseThrow(() -> QuotationsError.NOT_FOUND.toException(
                "La cotizacion con id " + id + " no existe"
            ));

        // 2. Optimistic locking: el If-Match debe coincidir con la version actual (updatedAt).
        verifyIfMatch(ifMatch, quotation);

        // 3. Campos inmutables: la cotizacion pertenece a un cliente y su tipo define las reglas.
        verifyImmutableFields(command, quotation);

        // 4. Cargar dependencias + validar reglas de negocio (reuso del create).
        //    loadFor valida isActive=true en TODAS las FKs del request (las editables —currency/
        //    paymentTerm/serviceType/cargoType— y tambien el clientId inmutable): editar una
        //    cotizacion cuyo catalogo o cliente fue DESACTIVADO devuelve 400 COM-001. Es intencional
        //    — no se edita/re-cotiza con un catalogo retirado. Asimetrico (a proposito) con el GET
        //    (loadByIds, sin chequeo de isActive), que SI expone cotizaciones viejas con FKs inactivas.
        LoadedDependencies deps = dependencyLoader.loadFor(command);
        validator.validate(command, deps.serviceTypesById());

        // 5. Totales (reuso).
        QuotationCalculatorService.Totals totals = calculator.calculate(command.items());

        // 6. Actualizar la cabecera (solo campos editables; los inmutables se preservan).
        quotationServiceMapper.applyUpdate(quotation, command, userId);

        // 7. REPLACE de items: borrar standby PRIMERO (FK quotation_item_id NOT NULL), luego
        //    items. El flush fuerza los DELETE + el UPDATE de la cabecera (@PreUpdate regenera
        //    updatedAt) ANTES de reinsertar, evitando chocar con UNIQUE(quotation_id, item_number).
        quotationStandbyCostRepository.deleteByQuotationId(id);
        quotationItemRepository.deleteByQuotationId(id);
        quotationItemRepository.flush();

        List<QuotationItem> persistedItems = itemPersistence.persistItems(command, quotation);
        Map<Long, QuotationStandbyCostResponse> standbyByItemId =
            itemPersistence.persistStandbyCosts(command, quotation, persistedItems);

        // 8. Armar respuesta: createdBy original preservado, updatedBy = usuario actual.
        UserResponse createdBy = loadUser(quotation.createdBy);
        UserResponse updatedBy = quotation.createdBy.equals(userId)
            ? createdBy                                       // dedup: mismo usuario → 1 query
            : loadUser(userId);
        boolean isExpired = computeIsExpired(quotation);

        return assembler.assemble(
            quotation, persistedItems, standbyByItemId, totals, deps,
            createdBy, updatedBy, isExpired
        );
    }

    /**
     * Optimistic locking: compara el header {@code If-Match} contra la version actual
     * del recurso ({@code updatedAt}, mismo formato de ETag que sirve el GET/POST). Si
     * falta o no coincide → 412 COM-004 (otro usuario edito primero, hay que recargar).
     */
    private void verifyIfMatch(String ifMatch, Quotation quotation) {
        String currentEtag = "\"" + quotation.updatedAt.toString() + "\"";
        if (ifMatch == null || !currentEtag.equals(ifMatch)) {
            throw CommonError.PRECONDITION_FAILED.toException();
        }
    }

    /**
     * El {@code quotationType} y el {@code clientId} no pueden cambiar al editar: la
     * cotizacion pertenece a un cliente y su tipo define las reglas de items. Si el body
     * intenta cambiarlos → 400 QUO-004. ({@code code}/{@code createdBy}/{@code createdAt}/
     * {@code status} no vienen en el request, asi que no hay nada que comparar; se preservan.)
     */
    private void verifyImmutableFields(SaveQuotationCommand command, Quotation quotation) {
        if (command.quotationType() == null
                || !command.quotationType().name().equals(quotation.quotationType)) {
            throw QuotationsError.IMMUTABLE_FIELD.toException(
                "El tipo de cotizacion no puede modificarse"
            );
        }
        if (command.clientId() == null || !command.clientId().equals(quotation.clientId)) {
            throw QuotationsError.IMMUTABLE_FIELD.toException(
                "El cliente de la cotizacion no puede modificarse"
            );
        }
    }

    /** {@code isExpired = now() > createdAt + validityDays} (UTC). Recalculado porque validityDays pudo cambiar. */
    private boolean computeIsExpired(Quotation quotation) {
        OffsetDateTime expiresAt = quotation.createdAt.plusDays(quotation.validityDays);
        return OffsetDateTime.now(ZoneOffset.UTC).isAfter(expiresAt);
    }

    /** Carga el {@link User} por id. FK huerfana (usuario borrado) → COM-500 (bug de integridad). */
    private UserResponse loadUser(Integer userId) {
        User user = userRepository.findById(userId);
        if (user == null) {
            LOG.errorf("Orphan FK in quotation UPDATE path: user not found, userId=%s", userId);
            throw CommonError.INTERNAL_ERROR.toException(
                "La cotizacion referencia un usuario inexistente (id=" + userId + "). Reporte a soporte."
            );
        }
        return authServiceMapper.toUserResponse(user);
    }
}
