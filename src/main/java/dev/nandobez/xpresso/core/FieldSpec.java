package dev.nandobez.xpresso.core;

/** A field declaration parsed from `name:type` syntax (Rails-style). */
public record FieldSpec(String name, String type, String javaType, String columnDef) {

    public static FieldSpec parse(String spec) {
        String[] p = spec.split(":", 2);
        if (p.length != 2) throw new IllegalArgumentException("expected name:type, got '" + spec + "'");
        String name = p[0];
        String type = p[1].toLowerCase();
        return switch (type) {
            case "string"            -> new FieldSpec(name, type, "String",         "VARCHAR(255)");
            case "text"              -> new FieldSpec(name, type, "String",         "TEXT");
            case "int", "integer"    -> new FieldSpec(name, type, "Integer",        "INTEGER");
            case "long", "bigint"    -> new FieldSpec(name, type, "Long",           "BIGINT");
            case "float"             -> new FieldSpec(name, type, "Float",          "FLOAT");
            case "double"            -> new FieldSpec(name, type, "Double",         "DOUBLE");
            case "decimal", "money"  -> new FieldSpec(name, type, "BigDecimal",     "DECIMAL(19,4)");
            case "bool", "boolean"   -> new FieldSpec(name, type, "Boolean",        "BOOLEAN");
            case "date"              -> new FieldSpec(name, type, "LocalDate",      "DATE");
            case "datetime",
                 "timestamp"         -> new FieldSpec(name, type, "Instant",        "TIMESTAMP");
            case "uuid"              -> new FieldSpec(name, type, "UUID",           "UUID");
            case "json"              -> new FieldSpec(name, type, "String",         "JSONB");
            default                  -> new FieldSpec(name, type, "String",         "VARCHAR(255)");
        };
    }

    /** Java import line needed by this field's type (or null). */
    public String importLine() {
        return switch (javaType) {
            case "BigDecimal" -> "import java.math.BigDecimal;";
            case "LocalDate"  -> "import java.time.LocalDate;";
            case "Instant"    -> "import java.time.Instant;";
            case "UUID"       -> "import java.util.UUID;";
            default -> null;
        };
    }
}
