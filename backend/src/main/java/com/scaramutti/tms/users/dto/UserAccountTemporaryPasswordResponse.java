package com.scaramutti.tms.users.dto;

public record UserAccountTemporaryPasswordResponse(
    UserAccountDetailResponse account,
    String temporaryPassword
) {

    // El de un record imprime cada campo: un log o el mensaje de una excepcion llevarian la clave.
    @Override
    public String toString() {
        return "UserAccountTemporaryPasswordResponse[account=" + account + ", temporaryPassword=***]";
    }
}
