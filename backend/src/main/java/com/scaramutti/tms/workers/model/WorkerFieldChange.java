package com.scaramutti.tms.workers.model;

/** Un campo que cambio, con lo que habia y lo que quedo. Los valores viajan ya formateados. */
public record WorkerFieldChange(WorkerAuditField field, String oldValue, String newValue) {}
