package com.scaramutti.tms.quotations.dto;

import java.util.List;

/** Datos de la empresa emisora + T&C + cuentas bancarias para el PDF, leidos de system_settings. */
public record CompanyPdfSettings(
    String legalName,
    String address,
    String phone,
    String email,
    List<String> terms,
    List<BankAccount> bankAccounts
) {}
