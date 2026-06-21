package com.scaramutti.tms.quotations.dto;

import java.util.List;

/**
 * Datos de la empresa emisora + cuentas bancarias para el PDF, leidos de system_settings.
 * Las CONDICIONES GENERALES del PDF ya NO viven aca: salen del catalogo por cotizacion
 * ({@code cotizaciones.conditions} via la junction) — el PDF las arma en buildView (US-006).
 *
 * <p>{@code bankAccountsIntro} es la frase de cabecera de la tabla de cuentas bancarias
 * ("El cliente debera realizar el pago ... en cualquiera de las siguientes cuentas:"): texto
 * fijo de empresa, NO una condicion del catalogo (es la cara visible del marcador
 * {@code [[BANK_ACCOUNTS]]}, RN-09). El PDF la imprime como ultima viñeta, justo antes de la tabla.
 */
public record CompanyPdfSettings(
    String legalName,
    String address,
    String phone,
    String email,
    String bankAccountsIntro,
    List<BankAccount> bankAccounts
) {}
