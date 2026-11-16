package com.scaramutti.tms.workers.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record WorkerAccountRef(
    @Schema(example = "12") Integer id,
    @Schema(example = "jperez") String username,
    @Schema(example = "true") Boolean isActive
) {}
