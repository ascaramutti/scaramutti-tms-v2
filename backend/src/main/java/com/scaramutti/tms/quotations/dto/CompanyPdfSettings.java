package com.scaramutti.tms.quotations.dto;

import java.util.List;

/**
 * Datos de la empresa emisora + cuentas bancarias para el PDF, leidos de system_settings.
 * Las CONDICIONES GENERALES del PDF ya NO viven aca: salen del catalogo por cotizacion
 * ({@code cotizaciones.conditions} via la junction) — el PDF las arma en buildView (US-006).
 */
public record CompanyPdfSettings(
    String legalName,
    String address,
    String phone,
    String email,
    List<BankAccount> bankAccounts
) {}
