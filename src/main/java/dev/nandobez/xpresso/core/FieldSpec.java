package dev.nandobez.xpresso.core;

import java.util.*;
import java.util.regex.*;

/** A field declaration parsed from Rails-style syntax with constraints and relations.
 *
 * <p>Examples:
 * <pre>
 *   title:string                          → String, VARCHAR(255)
 *   name:string!notblank{120}             → String length 120, @NotBlank
 *   email:string!unique!email             → unique column + @Email validation
 *   price:decimal!positive                → BigDecimal + @Positive
 *   user:belongs_to                       → @ManyToOne User user
 *   comments:has_many                     → @OneToMany List<Comment> comments
 *   profile:has_one                       → @OneToOne Profile profile
 *   role:enum(USER,ADMIN,MOD)             → custom enum + @Enumerated(STRING)
 * </pre>
 */
public record FieldSpec(
    String name,
    String type,
    String javaType,
    String columnDef,
    Kind kind,
    String targetEntity,
    Set<String> flags,
    Integer length,
    List<String> enumValues
) {
    public enum Kind { SCALAR, BELONGS_TO, HAS_MANY, HAS_ONE, ENUM }

    private static final Pattern SPEC_RE =
        Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*):([a-zA-Z_]+)(\\((.*?)\\))?((?:!\\w+)*)(\\{(\\d+)\\})?$");

    public static FieldSpec parse(String spec) {
        Matcher m = SPEC_RE.matcher(spec);
        if (!m.matches()) throw new IllegalArgumentException("invalid field spec: '" + spec + "'");

        String name      = m.group(1);
        String type      = m.group(2).toLowerCase();
        String parenArgs = m.group(4);                                                 // e.g. enum(A,B)
        String flagBlock = m.group(5);                                                 // e.g. !notblank!email
        Integer length   = m.group(7) == null ? null : Integer.parseInt(m.group(7));   // e.g. {255}

        Set<String> flags = new LinkedHashSet<>();
        if (flagBlock != null && !flagBlock.isEmpty()) {
            for (String f : flagBlock.substring(1).split("!"))
                if (!f.isEmpty()) flags.add(f.toLowerCase());
        }

        return switch (type) {
            case "belongs_to" -> {
                String target = parenArgs != null ? parenArgs : capitalize(name);
                yield new FieldSpec(name, type, target, "BIGINT", Kind.BELONGS_TO, target, flags, null, null);
            }
            case "has_many" -> {
                String target = parenArgs != null ? parenArgs : capitalize(singularize(name));
                yield new FieldSpec(name, type, "List<" + target + ">", "", Kind.HAS_MANY, target, flags, null, null);
            }
            case "has_one" -> {
                String target = parenArgs != null ? parenArgs : capitalize(name);
                yield new FieldSpec(name, type, target, "", Kind.HAS_ONE, target, flags, null, null);
            }
            case "enum" -> {
                List<String> values = parenArgs == null ? List.of() :
                    Arrays.stream(parenArgs.split(",")).map(String::trim).toList();
                String enumType = capitalize(name);
                yield new FieldSpec(name, type, enumType, "VARCHAR(64)", Kind.ENUM, null, flags, length, values);
            }
            default -> scalar(name, type, flags, length);
        };
    }

    private static FieldSpec scalar(String name, String type, Set<String> flags, Integer length) {
        String javaType;
        String columnDef;
        switch (type) {
            case "string"            -> { javaType = "String";     columnDef = "VARCHAR(" + (length == null ? 255 : length) + ")"; }
            case "text"              -> { javaType = "String";     columnDef = "TEXT"; }
            case "int", "integer"    -> { javaType = "Integer";    columnDef = "INTEGER"; }
            case "long", "bigint"    -> { javaType = "Long";       columnDef = "BIGINT"; }
            case "float"             -> { javaType = "Float";      columnDef = "FLOAT"; }
            case "double"            -> { javaType = "Double";     columnDef = "DOUBLE PRECISION"; }
            case "decimal", "money"  -> { javaType = "BigDecimal"; columnDef = "DECIMAL(19,4)"; }
            case "bool", "boolean"   -> { javaType = "Boolean";    columnDef = "BOOLEAN"; }
            case "date"              -> { javaType = "LocalDate";  columnDef = "DATE"; }
            case "datetime",
                 "timestamp"         -> { javaType = "Instant";    columnDef = "TIMESTAMP"; }
            case "uuid"              -> { javaType = "UUID";       columnDef = "UUID"; }
            case "json"              -> { javaType = "String";     columnDef = "JSONB"; }
            default                  -> { javaType = "String";     columnDef = "VARCHAR(" + (length == null ? 255 : length) + ")"; }
        }
        return new FieldSpec(name, type, javaType, columnDef, Kind.SCALAR, null, flags, length, null);
    }

    /** Java imports required by this field. */
    public Set<String> imports() {
        Set<String> out = new TreeSet<>();
        switch (javaType) {
            case "BigDecimal" -> out.add("import java.math.BigDecimal;");
            case "LocalDate"  -> out.add("import java.time.LocalDate;");
            case "Instant"    -> out.add("import java.time.Instant;");
            case "UUID"       -> out.add("import java.util.UUID;");
            default -> {}
        }
        if (javaType.startsWith("List<")) out.add("import java.util.List;");
        // validation imports
        for (String f : flags) {
            switch (f) {
                case "notblank","notempty","notnull","size","min","max","email",
                     "pattern","positive","negative","past","future","url"
                    -> out.add("import jakarta.validation.constraints.*;");
            }
        }
        return out;
    }

    /** Validation annotations for this field (e.g. "@NotBlank @Email"). */
    public String validationAnnotations() {
        var sb = new StringBuilder();
        for (String f : flags) {
            switch (f) {
                case "notblank" -> sb.append("@NotBlank ");
                case "notempty" -> sb.append("@NotEmpty ");
                case "notnull"  -> sb.append("@NotNull ");
                case "email"    -> sb.append("@Email ");
                case "url"      -> sb.append("@URL ");
                case "positive" -> sb.append("@Positive ");
                case "negative" -> sb.append("@Negative ");
                case "past"     -> sb.append("@Past ");
                case "future"   -> sb.append("@Future ");
            }
        }
        return sb.toString().trim();
    }

    /** Column attributes (length / unique / nullable / etc) as a single annotation arg string. */
    public String columnAttrs(String dbColumnName) {
        var parts = new ArrayList<String>();
        parts.add("name = \"" + dbColumnName + "\"");
        if (length != null && (javaType.equals("String"))) parts.add("length = " + length);
        if (flags.contains("unique")) parts.add("unique = true");
        if (flags.contains("notnull") || flags.contains("required")) parts.add("nullable = false");
        return String.join(", ", parts);
    }

    public boolean isRelation() {
        return kind == Kind.BELONGS_TO || kind == Kind.HAS_MANY || kind == Kind.HAS_ONE;
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static String singularize(String s) {
        String low = s.toLowerCase();
        if (low.endsWith("ies")) return s.substring(0, s.length() - 3) + "y";
        if (low.endsWith("ses") || low.endsWith("xes") || low.endsWith("zes")
            || low.endsWith("ches") || low.endsWith("shes")) return s.substring(0, s.length() - 2);
        if (low.endsWith("s") && !low.endsWith("ss")) return s.substring(0, s.length() - 1);
        return s;
    }
}
