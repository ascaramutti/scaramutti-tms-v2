package com.scaramutti.tms.auth.service.cmd;

/** Command interno del service. Desacopla la capa REST del dominio Auth. */
public record LoginCommand(String username, String password) {}
