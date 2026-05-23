package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.catalogs.quotationservicetype.model.QuotationServiceKind;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.cmd.CreateQuotationCommand;
import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;
import com.scaramutti.tms.shared.exception.CommonError;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reglas de negocio de la cotizacion (lineamiento #10 — SRP).
 *
 * Estado de la validacion vs lo que cubre Bean Validation:
 *  - Bean Validation (en el DTO): @NotNull, @NotEmpty, @Min, @Max, @Digits,
 *    @DecimalMin/Max, @Size, @Pattern, anidacion @Valid. Cubre lo declarativo.
 *  - QuotationValidatorService (aqui): reglas cross-field, cross-item, y de
 *    dominio que Bean Validation no puede expresar.
 *
 * Reglas validadas (en orden):
 *  1. Origin/destination required si quotationType=TRANSPORTE.
 *  2. itemNumber autogenerado o validado contiguo (1, 2, 3, ...).
 *  3. Max 5 items root (el item Integral cuenta como root; sus hijos no).
 *  4. unitPrice > 0 obligatorio en items root (incluido el padre INT). Solo
 *     los hijos del Integral pueden tener unitPrice=0 o null (su costo va
 *     embebido en el precio del padre, se documenta en internalReferencePrice).
 *  5. internalReferencePrice solo valido en hijos del Integral.
 *  6. parentItemNumber referencia a un itemNumber existente del mismo request.
 *  7. insuredAmount solo valido si serviceType es SEG (Seguro de Carga).
 *  8. standby NO aplica a items con kind=INTEGRAL (sus hijos si pueden).
 *  9. Medidas + cargoTypeId solo aplican a kind=SERVICIO (transporte):
 *     weightKg y cargoTypeId son obligatorios; length/width/height opcionales.
 *     En kind != SERVICIO esos campos DEBEN ser null (REJECT estricto).
 * 10. Servicio Integral:
 *     a. Solo un INT por cotizacion.
 *     b. INT debe ser itemNumber=1.
 *     c. INT debe tener >=2 hijos.
 *     d. Hijos deben incluir >=1 SERVICIO (kind=SERVICIO) + >=1 COMPLEMENTARIO (kind=COMPLEMENTARIO).
 *
 * Si alguna regla falla, tira ApiException con CommonError.VALIDATION_FAILED
 * (COM-001). El detail incluye el path del item afectado para debugging.
 *
 * NOTA: este service no consulta BD. Recibe el mapa de serviceTypes (id →
 * entity) ya cargado por el caller. Esto lo hace 100% testeable sin Quarkus.
 */
@ApplicationScoped
public class QuotationValidatorService {

    private static final int MAX_ROOT_ITEMS = 5;

    /**
     * Lista negra de kinds que NO pueden tener {@code standby} asociado.
     * <p>Hoy solo INTEGRAL: el Servicio Integral es un agregado del precio
     * mostrado al cliente, sus standby viven en los items hijos (transporte,
     * complementarios) — no en el padre. Agregar nuevos kinds aca es trivial
     * si la regla de negocio evoluciona.
     */
    private static final Set<QuotationServiceKind> KINDS_WITHOUT_STANDBY =
        EnumSet.of(QuotationServiceKind.INTEGRAL);

    /**
     * Valida el command + el mapa de serviceTypes precargados (id → entity).
     * El caller (CreateQuotationService) precarga los serviceTypes de UNA
     * sola query antes de invocar al validator.
     */
    public void validate(CreateQuotationCommand command, Map<Integer, QuotationServiceTypeSummary> serviceTypesById) {
        List<CreateQuotationCommand.Item> items = command.items();

        // Pre-compute itemNumber efectivo de cada item (1-based). Si el request
        // no envia itemNumber explicito, usamos el indice+1.
        // Esto se hace una sola vez para evitar O(n^2) en helpers posteriores.
        List<Integer> effectiveNumbers = computeEffectiveItemNumbers(items);

        validateOriginDestination(command);
        validateItemNumbers(items);
        validateMaxRootItems(items);
        validateUnitPricePerItem(items);
        validateInternalReferencePrice(items);
        validateParentReferences(items, effectiveNumbers);
        validateInsuredAmountServiceType(items, serviceTypesById);
        validateStandbyServiceType(items, serviceTypesById);
        validateMeasurementsAndCargoType(items, serviceTypesById);
        validateServicioIntegral(items, effectiveNumbers, serviceTypesById);
    }

    // ---------- Reglas individuales -----------------------------------------

    private void validateOriginDestination(CreateQuotationCommand command) {
        if (command.quotationType() == QuotationType.TRANSPORTE) {
            if (command.origin() == null || command.destination() == null) {
                throw CommonError.VALIDATION_FAILED.toException(
                    "origin y destination son requeridos para cotizaciones de tipo TRANSPORTE"
                );
            }
        }
    }

    private void validateItemNumbers(List<CreateQuotationCommand.Item> items) {
        // Si algun item envia itemNumber, debe ser contiguo (1, 2, 3, ...) sin gaps.
        // Si todos vienen sin itemNumber, el service los autogenera (no es problema aqui).
        // Aqui solo validamos: si AL MENOS UNO envia itemNumber, TODOS deben enviarlo
        // y ser contiguos. Caso mixto = error.
        boolean someHave = items.stream().anyMatch(i -> i.itemNumber() != null);
        boolean allHave = items.stream().allMatch(i -> i.itemNumber() != null);
        if (someHave && !allHave) {
            throw CommonError.VALIDATION_FAILED.toException(
                "itemNumber debe estar presente en TODOS los items o en NINGUNO"
            );
        }
        if (allHave) {
            // Validar contiguidad (1, 2, 3, ..., N).
            for (int i = 0; i < items.size(); i++) {
                int expected = i + 1;
                int actual = items.get(i).itemNumber();
                if (actual != expected) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "itemNumber debe ser contiguo desde 1; encontrado " + actual + " en posicion " + (i + 1)
                    );
                }
            }
        }
    }

    private void validateMaxRootItems(List<CreateQuotationCommand.Item> items) {
        // "Root" = item sin parentItemNumber. El item Integral cuenta como root.
        // Por ejemplo: 1 INT + 5 items adicionales no-Integral son 6 root → falla.
        long rootCount = items.stream().filter(i -> i.parentItemNumber() == null).count();
        if (rootCount > MAX_ROOT_ITEMS) {
            throw CommonError.VALIDATION_FAILED.toException(
                "Maximo " + MAX_ROOT_ITEMS + " items root permitidos (el Servicio Integral cuenta como root; "
                + "encontrados " + rootCount + ")"
            );
        }
    }

    private void validateUnitPricePerItem(List<CreateQuotationCommand.Item> items) {
        for (CreateQuotationCommand.Item item : items) {
            boolean isChild = item.parentItemNumber() != null;
            if (isChild) {
                // Hijo del Integral: unitPrice debe ser null o 0.
                if (item.unitPrice() != null && item.unitPrice().compareTo(BigDecimal.ZERO) != 0) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "Items hijos del Servicio Integral deben tener unitPrice=0 o null (usar internalReferencePrice)"
                    );
                }
            } else {
                // Item root: unitPrice obligatorio Y > 0. Solo los hijos del Integral
                // pueden ir sin precio (el padre INT tambien es root: SU unitPrice es
                // el precio agregado mostrado al cliente, debe ser > 0).
                if (item.unitPrice() == null) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "unitPrice es requerido para items root"
                    );
                }
                if (item.unitPrice().signum() <= 0) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "unitPrice debe ser mayor a 0 para items root"
                    );
                }
            }
        }
    }

    private void validateInternalReferencePrice(List<CreateQuotationCommand.Item> items) {
        for (CreateQuotationCommand.Item item : items) {
            boolean isChild = item.parentItemNumber() != null;
            if (!isChild && item.internalReferencePrice() != null) {
                throw CommonError.VALIDATION_FAILED.toException(
                    "internalReferencePrice solo es valido para items hijos del Servicio Integral"
                );
            }
        }
    }

    private void validateParentReferences(List<CreateQuotationCommand.Item> items, List<Integer> effectiveNumbers) {
        Map<Integer, Boolean> existingNumbers = new HashMap<>();
        for (Integer number : effectiveNumbers) {
            existingNumbers.put(number, true);
        }
        for (CreateQuotationCommand.Item item : items) {
            Integer parent = item.parentItemNumber();
            if (parent != null && !existingNumbers.containsKey(parent)) {
                throw CommonError.VALIDATION_FAILED.toException(
                    "parentItemNumber=" + parent + " referencia un itemNumber que no existe en el request"
                );
            }
        }
    }

    private void validateInsuredAmountServiceType(
            List<CreateQuotationCommand.Item> items,
            Map<Integer, QuotationServiceTypeSummary> serviceTypesById) {
        for (CreateQuotationCommand.Item item : items) {
            if (item.insuredAmount() == null) continue;
            QuotationServiceTypeSummary st = requireServiceType(serviceTypesById, item.serviceTypeId());
            if (!"SEG".equalsIgnoreCase(st.code())) {
                throw CommonError.VALIDATION_FAILED.toException(
                    "insuredAmount solo es valido para items con serviceType.code='SEG'"
                );
            }
        }
    }

    /**
     * Standby NO aplica a ciertos kinds (ver {@link #KINDS_WITHOUT_STANDBY}).
     * Hoy: solo INTEGRAL. Razon: el Servicio Integral es un agregado del precio;
     * los costos de standby viven en sus items hijos (transporte/complementarios).
     */
    private void validateStandbyServiceType(
            List<CreateQuotationCommand.Item> items,
            Map<Integer, QuotationServiceTypeSummary> serviceTypesById) {
        for (CreateQuotationCommand.Item item : items) {
            if (item.standby() == null) continue;
            QuotationServiceTypeSummary st = requireServiceType(serviceTypesById, item.serviceTypeId());
            QuotationServiceKind kind = resolveKind(st);
            if (KINDS_WITHOUT_STANDBY.contains(kind)) {
                throw CommonError.VALIDATION_FAILED.toException(
                    "standby no aplica al Servicio Integral (aplica a sus items hijos individualmente)"
                );
            }
        }
    }

    /**
     * Medidas fisicas (weightKg, lengthMeters, widthMeters, heightMeters) y
     * cargoTypeId aplican EXCLUSIVAMENTE a items de transporte (kind=SERVICIO,
     * prefijo S en el code). Las demas kinds (ALQUILER, COMPLEMENTARIO, INTEGRAL)
     * NO transportan carga fisica — su activo es el vehiculo/maquinaria/servicio.
     *
     * <p>Reglas estrictas:
     * <ul>
     *   <li>kind=SERVICIO: {@code weightKg} y {@code cargoTypeId} obligatorios
     *       (weight > 0). length/width/height son opcionales.</li>
     *   <li>kind != SERVICIO: weightKg, length/width/height y cargoTypeId
     *       deben estar AUSENTES (null). Si vienen, rechazo con COM-001 —
     *       indica payload incorrecto del frontend.</li>
     * </ul>
     *
     * <p><b>Rationale del REJECT estricto (vs. ignorar silenciosamente)</b>:
     * un frontend que envia weight a un item kind=ALQUILER esta indicando un
     * bug en su modelo (o un payload mal armado por integracion externa).
     * Rechazar explicito hace el bug visible inmediatamente, en vez de
     * silenciarlo y dejar al cliente preguntandose por que sus datos no
     * aparecen en el response. Patron consistente con
     * {@code validateInsuredAmountServiceType} (solo SEG).
     */
    private void validateMeasurementsAndCargoType(
            List<CreateQuotationCommand.Item> items,
            Map<Integer, QuotationServiceTypeSummary> serviceTypesById) {
        for (CreateQuotationCommand.Item item : items) {
            QuotationServiceTypeSummary st = requireServiceType(serviceTypesById, item.serviceTypeId());
            QuotationServiceKind kind = resolveKind(st);

            if (kind == QuotationServiceKind.SERVICIO) {
                // Transporte: weight + cargoType obligatorios.
                if (item.weightKg() == null || item.weightKg().signum() <= 0) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "weightKg es requerido y debe ser mayor a 0 para items de transporte (kind=SERVICIO)"
                    );
                }
                if (item.cargoTypeId() == null) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "cargoTypeId es requerido para items de transporte (kind=SERVICIO)"
                    );
                }
                // length/width/height opcionales — no validamos.
            } else {
                // ALQUILER, COMPLEMENTARIO, INTEGRAL: las medidas y cargoTypeId
                // NO aplican (el item no representa carga fisica). Si vienen,
                // es payload incorrecto.
                if (item.weightKg() != null) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "weightKg solo aplica a items de transporte (kind=SERVICIO)"
                    );
                }
                if (item.lengthMeters() != null) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "lengthMeters solo aplica a items de transporte (kind=SERVICIO)"
                    );
                }
                if (item.widthMeters() != null) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "widthMeters solo aplica a items de transporte (kind=SERVICIO)"
                    );
                }
                if (item.heightMeters() != null) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "heightMeters solo aplica a items de transporte (kind=SERVICIO)"
                    );
                }
                if (item.cargoTypeId() != null) {
                    throw CommonError.VALIDATION_FAILED.toException(
                        "cargoTypeId solo aplica a items de transporte (kind=SERVICIO)"
                    );
                }
            }
        }
    }

    // ---------- Servicio Integral -------------------------------------------

    private void validateServicioIntegral(
            List<CreateQuotationCommand.Item> items,
            List<Integer> effectiveNumbers,
            Map<Integer, QuotationServiceTypeSummary> serviceTypesById) {
        // 1. Detectar items INT (kind=INTEGRAL) usando el itemNumber efectivo
        // pre-computado para evitar List.indexOf por item.
        List<Integer> integralItemNumbers = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            CreateQuotationCommand.Item item = items.get(i);
            QuotationServiceTypeSummary st = serviceTypesById.get(item.serviceTypeId());
            if (st == null) continue;
            QuotationServiceKind kind = resolveKind(st);
            if (kind == QuotationServiceKind.INTEGRAL) {
                integralItemNumbers.add(effectiveNumbers.get(i));
            }
        }

        if (integralItemNumbers.isEmpty()) {
            return; // sin Integral, nada que validar.
        }

        // 1a. Solo un INT por cotizacion.
        if (integralItemNumbers.size() > 1) {
            throw CommonError.VALIDATION_FAILED.toException(
                "Solo puede haber un Servicio Integral por cotizacion"
            );
        }

        Integer integralNumber = integralItemNumbers.get(0);

        // 1b. INT debe ser itemNumber=1. Rationale: UX del wizard — el Integral
        // se renderiza en la parte superior del PDF/cotizacion como el "paquete"
        // que agrupa al resto. Si en el futuro se requiere flexibilidad de orden,
        // este check se relaja a "INT debe estar entre los root items".
        if (integralNumber != 1) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El Servicio Integral debe ser el primer item (itemNumber=1)"
            );
        }

        // 1c. INT debe tener >=2 hijos.
        List<CreateQuotationCommand.Item> children = items.stream()
            .filter(item -> Integer.valueOf(1).equals(item.parentItemNumber()))
            .toList();
        if (children.size() < 2) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El Servicio Integral debe tener al menos 2 items hijos (encontrados " + children.size() + ")"
            );
        }

        // 1d. Hijos deben incluir >=1 SERVICIO + >=1 COMPLEMENTARIO.
        // Nota: en el dominio de Scaramutti los services kind=SERVICIO son los de
        // transporte (codes con prefijo S — cama baja, gondola, plataforma, etc.).
        boolean hasServicio = false;
        boolean hasComplementario = false;
        for (CreateQuotationCommand.Item child : children) {
            QuotationServiceTypeSummary st = requireServiceType(serviceTypesById, child.serviceTypeId());
            QuotationServiceKind kind = resolveKind(st);
            if (kind == QuotationServiceKind.SERVICIO) hasServicio = true;
            else if (kind == QuotationServiceKind.COMPLEMENTARIO) hasComplementario = true;
        }
        if (!hasServicio) {
            throw CommonError.VALIDATION_FAILED.toException(
                "Los hijos del Servicio Integral deben incluir al menos un servicio de TRANSPORTE (kind=SERVICIO)"
            );
        }
        if (!hasComplementario) {
            throw CommonError.VALIDATION_FAILED.toException(
                "Los hijos del Servicio Integral deben incluir al menos un servicio COMPLEMENTARIO (kind=COMPLEMENTARIO)"
            );
        }
    }

    // ---------- Helpers ------------------------------------------------------

    /**
     * Devuelve el itemNumber efectivo de cada item en orden. Si el item envia
     * itemNumber explicito lo usa; sino usa el indice+1 (autogenerado).
     */
    private List<Integer> computeEffectiveItemNumbers(List<CreateQuotationCommand.Item> items) {
        List<Integer> numbers = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            CreateQuotationCommand.Item item = items.get(i);
            numbers.add(item.itemNumber() != null ? item.itemNumber() : (i + 1));
        }
        return numbers;
    }

    /**
     * Fail-fast invariant: el caller (CreateQuotationService) DEBE haber
     * pre-cargado todos los serviceTypeIds referenciados via
     * QuotationDependencyLoaderService. Si llegamos al validator con un id
     * no cargado, es un bug del orquestador, no del request.
     */
    private QuotationServiceTypeSummary requireServiceType(
            Map<Integer, QuotationServiceTypeSummary> serviceTypesById, Integer id) {
        QuotationServiceTypeSummary st = serviceTypesById.get(id);
        if (st == null) {
            throw CommonError.VALIDATION_FAILED.toException(
                "Invariante violada: serviceType id=" + id + " no fue precargado por el DependencyLoader"
            );
        }
        return st;
    }

    /**
     * Resuelve el kind del serviceType. La Response trae `kind` ya computado
     * como String (lo derivo el ServiceMapper al cargar). Convertimos al enum
     * para comparaciones tipadas. Si el kind viene corrupto (no es un nombre
     * valido de QuotationServiceKind), traducimos a COM-001 sin exponer el
     * dato interno.
     */
    private QuotationServiceKind resolveKind(QuotationServiceTypeSummary st) {
        try {
            return QuotationServiceKind.valueOf(st.kind());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw CommonError.VALIDATION_FAILED.toException(
                "serviceType tiene kind invalido (contacte al administrador)"
            );
        }
    }
}
