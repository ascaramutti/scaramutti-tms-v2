package com.scaramutti.tms.auth.service.cmd;

public record ChangePasswordCommand(
    Integer userId,
    String currentPassword,
    String newPassword
) {}
