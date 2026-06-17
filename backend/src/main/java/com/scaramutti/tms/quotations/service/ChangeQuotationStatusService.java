package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.quotations.QuotationEtag;
import com.scaramutti.tms.quotations.QuotationsError;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.service.cmd.ChangeQuotationStatusCommand;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * Facade del cambio de estado de cotizacion (PATCH /quotations/{id}/status), ADR-004/006/007.
 *
 * <p>Orquestacion (orden estricto de checks, ADR-006):
 * <ol>
 *   <li><b>Cargar</b> (404 QUO-003 si no existe). Precede al If-Match: no se versiona lo inexistente.</li>
 *   <li><b>If-Match</b> (412 COM-004 si falta o no coincide) — via {@link com.scaramutti.tms.quotations.QuotationEtag}, mismo check que el PUT.</li>
 *   <li><b>Transicion valida</b> segun {@link QuotationStatusMachine} (409 QUO-005), sin tocar la fila.
 *       El destino tambien debe ser de usuario ({@code SENT}/{@code ACCEPTED}/{@code REJECTED}):
 *       {@code DRAFT}/{@code EXPIRED} no son destinos de usuario → tambien 409 QUO-005.</li>
 *   <li><b>Motivo de rechazo</b> (ADR-007): obligatorio Y exclusivo de {@code REJECTED}.
 *       {@code REJECTED} sin motivo (o blank tras trim) → 400 COM-001; destino ≠ {@code REJECTED}
 *       con motivo → 400 COM-001.</li>
 *   <li><b>Persistir</b>: {@code status} nuevo + {@code rejectionReason} (trimmeado solo si {@code REJECTED},
 *       null en otro caso) + {@code updatedBy} = usuario actual; {@code @PreUpdate} regenera {@code updatedAt}
 *       (= nuevo ETag). El {@code flush} fuerza el UPDATE antes de re-leer.</li>
 *   <li><b>Devolver</b> {@link QuotationResponse} completo reusando {@link GetQuotationService#getById(Long)}
 *       post-flush — no se duplica el ensamblado de items.</li>
 * </ol>
 *
 * <p>Concurrencia (ADR-006): mismo optimistic locking check-then-act sobre el ETag ({@code updatedAt})
 * que el PUT, sin {@code @Version} ni lock pesimista (deuda transversal del modulo ya aceptada).
 */
@ApplicationScoped
public class ChangeQuotationStatusService {

    /** Destinos que un usuario puede pedir via el PATCH. {@code EXPIRED} lo produce solo el job; a {@code DRAFT} no se vuelve. */
    private static final Set<QuotationStatus> USER_TARGETABLE =
        EnumSet.of(QuotationStatus.SENT, QuotationStatus.ACCEPTED, QuotationStatus.REJECTED);

    @Inject QuotationRepository quotationRepository;
    @Inject QuotationStatusMachine statusMachine;
    @Inject GetQuotationService getQuotationService;
    @Inject CurrentUser currentUser;

    @Transactional
    public QuotationResponse changeStatus(Long id, String ifMatch, ChangeQuotationStatusCommand command) {
        Integer userId = currentUser.requireId();

        // 1. Cargar (404 si no existe). Precede al If-Match.
        Quotation quotation = quotationRepository.findByIdOptional(id)
            .orElseThrow(() -> QuotationsError.NOT_FOUND.toException(
                "La cotizacion con id " + id + " no existe"
            ));

        // 2. Optimistic locking (mismo check que el PUT, via helper compartido).
        QuotationEtag.verify(ifMatch, quotation);

        // 3. Transicion valida (origen actual → destino pedido).
        QuotationStatus from = QuotationStatus.valueOf(quotation.status);
        QuotationStatus to = command.status();
        verifyTransition(from, to);

        // 4. Regla del motivo de rechazo (obligatorio Y exclusivo de REJECTED).
        String rejectionReason = verifyAndNormalizeRejectionReason(to, command.rejectionReason());

        // 5. Persistir sobre la entity managed (dirty-checking + flush fuerza el UPDATE
        //    y regenera updatedAt via @PreUpdate ANTES de re-leer en el paso 6).
        quotation.status = to.name();
        quotation.rejectionReason = rejectionReason;   // null salvo REJECTED
        quotation.updatedBy = userId;
        quotationRepository.flush();

        // 6. Re-armar el response completo (reusa el ensamblado de items del GET).
        return getQuotationService.getById(id);
    }

    /**
     * Valida la transicion {@code from → to}. Invalida → 409 QUO-005 (sin tocar la fila),
     * con el detail nombrando origen y destino concretos. Un destino que no es de usuario
     * ({@code DRAFT}/{@code EXPIRED}) tampoco es legal por el PATCH.
     */
    private void verifyTransition(QuotationStatus from, QuotationStatus to) {
        if (!USER_TARGETABLE.contains(to) || !statusMachine.canTransition(from, to)) {
            throw QuotationsError.INVALID_TRANSITION.toException(
                "No se puede pasar de " + from + " a " + to
            );
        }
    }

    /**
     * Regla del motivo (ADR-007), exclusiva de {@code REJECTED}:
     * <ul>
     *   <li>destino = {@code REJECTED} y motivo null/blank (tras trim) → 400 COM-001;</li>
     *   <li>destino ≠ {@code REJECTED} y motivo presente → 400 COM-001;</li>
     *   <li>destino = {@code REJECTED} con motivo → OK (se persiste trimmeado);</li>
     *   <li>destino ≠ {@code REJECTED} sin motivo → OK (se persiste null).</li>
     * </ul>
     * El largo y los caracteres de control ya los valida el DTO ({@code @Size}/{@code @Pattern}).
     */
    private String verifyAndNormalizeRejectionReason(QuotationStatus to, String rawReason) {
        String trimmed = rawReason == null ? null : rawReason.trim();
        boolean hasReason = trimmed != null && !trimmed.isEmpty();

        if (to == QuotationStatus.REJECTED) {
            if (!hasReason) {
                throw CommonError.VALIDATION_FAILED.toException(
                    "El motivo de rechazo es obligatorio al rechazar una cotizacion"
                );
            }
            return trimmed;
        }

        // Destino != REJECTED: el motivo es exclusivo de REJECTED → presente = error.
        if (hasReason) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El motivo de rechazo solo aplica al rechazar una cotizacion"
            );
        }
        return null;
    }
}
