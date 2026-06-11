package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.quotations.dto.QuotationConfigResponse;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Ensambla la {@link QuotationConfigResponse} desde las {@code @ConfigProperty}
 * del modulo + la constante {@link QuotationValidatorService#MAX_ROOT_ITEMS}.
 *
 * <p>Existe como service (en vez de leer {@code @ConfigProperty} desde el
 * Resource) por tres razones:
 * <ol>
 *   <li><b>Testabilidad</b>: el Resource queda thin (solo HTTP); el service
 *       se testea sin levantar JAX-RS.</li>
 *   <li><b>Consistencia con el modulo</b>: ningun Resource lee
 *       {@code @ConfigProperty} directamente —
 *       {@link QuotationCalculatorService} y {@link CreateQuotationService}
 *       ya inyectan {@code app.quotations.default-igv-percentage} en services.</li>
 *   <li><b>Extensibilidad</b>: si el config requiere derivacion futura
 *       (ej. computar {@code maxItemsTotal} desde otros parametros), la
 *       logica vive en un unico lugar.</li>
 * </ol>
 *
 * <p>Las {@code @ConfigProperty} NO declaran {@code defaultValue}: si la
 * property falta en {@code application.properties}, la app falla al
 * arrancar (fail-fast). Esto evita drift entre el default del codigo y el
 * valor operacional real, y garantiza que un borrado accidental de la
 * property sea visible inmediatamente en vez de silenciarse con un valor
 * del codigo que pudo haber quedado obsoleto.
 */
@ApplicationScoped
public class QuotationConfigService {

    @ConfigProperty(name = "app.quotations.default-igv-percentage")
    BigDecimal defaultIgvPercentage;

    @ConfigProperty(name = "app.quotations.default-validity-days")
    int defaultValidityDays;

    /**
     * Devuelve la configuracion runtime del modulo. Pure function — sin BD,
     * sin side effects, sin estado. El header {@code Cache-Control} lo agrega
     * el Resource (no es responsabilidad del service).
     */
    public QuotationConfigResponse getConfig() {
        // setScale(2, HALF_UP) garantiza contrato estable: el response siempre
        // devuelve el IGV con 2 decimales (18.00) sin importar como este escrito
        // en application.properties (18, 18.0, 18.00, etc.). Consistente con
        // el scale que persistimos en quotation_items.igv_percentage NUMERIC(5,2).
        return new QuotationConfigResponse(
            defaultIgvPercentage.setScale(2, RoundingMode.HALF_UP),
            QuotationValidatorService.MAX_ROOT_ITEMS,
            defaultValidityDays
        );
    }
}
