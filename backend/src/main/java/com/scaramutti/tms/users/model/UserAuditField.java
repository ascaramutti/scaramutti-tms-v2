package com.scaramutti.tms.users.model;

public enum UserAuditField {

    IS_ACTIVE ("isActive", "Cuenta"),
    USERNAME  ("username", "Usuario");

    private final String fieldName;
    private final String label;

    UserAuditField(String fieldName, String label) {
        this.fieldName = fieldName;
        this.label = label;
    }

    public String fieldName() {
        return fieldName;
    }

    public String label() {
        return label;
    }
}
