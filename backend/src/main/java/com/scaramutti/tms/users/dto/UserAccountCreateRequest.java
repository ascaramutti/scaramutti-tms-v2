package com.scaramutti.tms.users.dto;

import jakarta.validation.constraints.NotNull;

public record UserAccountCreateRequest(@NotNull Integer workerId) {}
