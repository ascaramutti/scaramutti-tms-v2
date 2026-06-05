package com.scaramutti.tms.quotations.dto;

/** Cuenta bancaria de la empresa emisora (pie del PDF de cotizacion). */
public record BankAccount(String bank, String account, String cci) {}
