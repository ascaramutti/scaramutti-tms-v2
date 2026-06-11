package com.scaramutti.tms.catalogs.paymentterm.mapper;

import com.scaramutti.tms.catalogs.paymentterm.dto.PaymentTermResponse;
import com.scaramutti.tms.shared.entity.PaymentTerm;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * Mapper de la capa Service: traduce entidades del dominio a DTOs de salida.
 * Lo inyecta PaymentTermService cuando arma respuestas para el cliente.
 *
 * El metodo de lista lo genera MapStruct automaticamente usando el de single
 * por debajo — no hace falta escribir la implementacion.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface PaymentTermServiceMapper {

    PaymentTermResponse toPaymentTermResponse(PaymentTerm paymentTerm);

    List<PaymentTermResponse> toPaymentTermResponseList(List<PaymentTerm> paymentTerms);
}
