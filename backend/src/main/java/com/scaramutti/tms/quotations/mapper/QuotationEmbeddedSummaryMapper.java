package com.scaramutti.tms.quotations.mapper;

import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.catalogs.currency.dto.CurrencyResponse;
import com.scaramutti.tms.catalogs.paymentterm.dto.PaymentTermResponse;
import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCargoTypeSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationClientSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationConditionSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCurrencySummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationPaymentTermSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;
import com.scaramutti.tms.shared.entity.Condition;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * Adapter / Anti-Corruption Layer entre los Response DTOs de otros modulos
 * (Clients, Catalogs, CargoTypes) y los Summary DTOs del modulo Quotations.
 *
 * <p>Razon de existir: cada Response viene diseñado para su endpoint REST
 * de origen (ej. {@code GET /clients/{id}} devuelve todo el cliente). En el
 * contexto de cotizacion solo necesitamos un subset (ej. id+name+ruc) — y
 * exponer mas seria leak (ej. {@code createdAt}, {@code isActive}, campos
 * futuros sensibles).
 *
 * <p>Este mapper se invoca exclusivamente en {@link com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService}
 * justo despues de obtener cada Response del service correspondiente.
 *
 * <p>MapStruct genera la impl al compilar — todos los mappeos son 1:1 por
 * nombre de campo (subset), cero {@code expression}.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface QuotationEmbeddedSummaryMapper {

    QuotationClientSummary toClientSummary(ClientResponse client);

    QuotationCurrencySummary toCurrencySummary(CurrencyResponse currency);

    QuotationPaymentTermSummary toPaymentTermSummary(PaymentTermResponse paymentTerm);

    QuotationServiceTypeSummary toServiceTypeSummary(QuotationServiceTypeResponse serviceType);

    QuotationCargoTypeSummary toCargoTypeSummary(CargoTypeResponse cargoType);

    /**
     * A diferencia de los otros {@code toXxxSummary} (que mapean de un Response DTO), este mapea
     * de la ENTITY {@link Condition} directo: el detalle de cotizacion resuelve las condiciones
     * por FK desde la junction, sin pasar por el service del catalogo. Mapeo 1:1 por nombre
     * (id/text/displayOrder/isActive); SI expone {@code isActive} (snapshot historico, RN-05).
     */
    QuotationConditionSummary toConditionSummary(Condition condition);

    List<QuotationConditionSummary> toConditionSummaries(List<Condition> conditions);
}
