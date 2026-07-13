package dev.nandobez.xpresso.gen;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Turns a sample API interaction into a ready-to-use backend client:
 *   - records mirroring the JSON shapes (request and/or response), nested objects included
 *   - a Spring @Component that calls the upstream URL and deserializes into those records
 *   - a @RestController exposing it under /api/&lt;name&gt;
 *   - optionally a thin @Service wrapper
 *
 * Handles two shapes of upstream:
 *   GET  — models the response; exposes list()/get(id) (array) or fetch() (single object).
 *   POST — models the request body (and response when a sample is available); exposes call(req).
 *
 * When the API needs a key, the client reads it from a Spring property backed by a .env entry,
 * never a hardcoded literal. Pure code generation: returns file-name → source pairs.
 */
public final class ApiClientGen {
    private ApiClientGen() {}

    public enum Kind { NONE, BEARER, HEADER, QUERY, TOKEN }

    /** Re-throws upstream 4xx/5xx with the real status + body, so callers see the actual error (not a blind 500). */
    private static final String ERR =
        "            .onStatus(org.springframework.http.HttpStatusCode::isError, (rq, rs) -> {\n" +
        "                throw new org.springframework.web.server.ResponseStatusException(\n" +
        "                    rs.getStatusCode(), new String(rs.getBody().readAllBytes()), null);\n" +
        "            })\n";

    /** Same, but lets 401 through (default handler throws Unauthorized) so withRetry() can re-login and retry. */
    private static final String ERR_NOT_401 =
        "            .onStatus(sc -> sc.isError() && sc.value() != 401, (rq, rs) -> {\n" +
        "                throw new org.springframework.web.server.ResponseStatusException(\n" +
        "                    rs.getStatusCode(), new String(rs.getBody().readAllBytes()), null);\n" +
        "            })\n";

    /** withRetry helper emitted into TOKEN clients: on 401, refresh the token and retry once. */
    private static final String RETRY_HELPER =
        "    private <T> T withRetry(java.util.function.Function<String, T> call) {\n" +
        "        try { return call.apply(auth.token()); }\n" +
        "        catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {\n" +
        "            auth.refresh();\n" +
        "            return call.apply(auth.token());\n" +
        "        }\n" +
        "    }\n\n";

    /** paramName: header or query-param name (unused for NONE/BEARER). envVar: the property/.env key. */
    public record Auth(Kind kind, String paramName, String envVar) {
        public static final Auth NONE = new Auth(Kind.NONE, null, null);
    }

    /** Everything needed to generate one client. responseSample may be null (untyped response). */
    public record Spec(String basePackage, String name, String url, String method,
                       JsonNode responseSample, JsonNode requestSample, boolean withService, Auth auth) {}

    private static final Set<String> RESERVED = Set.of(
        "abstract","assert","boolean","break","byte","case","catch","char","class","const","continue",
        "default","do","double","else","enum","extends","final","finally","float","for","goto","if",
        "implements","import","instanceof","int","interface","long","native","new","package","private",
        "protected","public","return","short","static","strictfp","super","switch","synchronized","this",
        "throw","throws","transient","try","void","volatile","while","record","var","yield","sealed","permits");

    /** name → source. Keys are simple file names like "Product.java" / "GeminiClient.java". */
    public static Map<String, String> generate(Spec s) {
        return "POST".equalsIgnoreCase(s.method()) ? generatePost(s) : generateGet(s);
    }

    /* ================================================= GET ================================================= */

    private static Map<String, String> generateGet(Spec s) {
        String pkg = s.basePackage() + ".client";
        JsonNode sample = s.responseSample();
        if (sample == null) throw new IllegalArgumentException("GET needs a response sample to model");
        boolean isList = sample.isArray();
        JsonNode element = isList ? (sample.isEmpty() ? null : sample.get(0)) : sample;
        if (!isList && element != null && element.isObject() && element.path("content").isArray()) {
            JsonNode c = element.get("content");
            if (!c.isEmpty()) { element = c.get(0); isList = true; }
        }
        if (element == null || !element.isObject())
            throw new IllegalArgumentException("sample must be a JSON object or a non-empty array of objects");

        String rootType = cap(singular(s.name()));
        var out = new LinkedHashMap<String, String>();
        out.put(rootType + ".java", recordFile(pkg, rootType, element));
        out.put(s.name() + "Client.java", getClient(pkg, s.name(), rootType, s.url(), isList, s.auth()));
        out.put(s.name() + "ApiController.java", getController(s.basePackage(), s.name(), rootType, isList, s.withService()));
        if (s.withService()) out.put(s.name() + "ApiService.java", getService(pkg, s.name(), rootType, isList));
        return out;
    }

    /* ================================================= POST ================================================ */

    private static Map<String, String> generatePost(Spec s) {
        String pkg = s.basePackage() + ".client";
        if (s.requestSample() == null || !s.requestSample().isObject())
            throw new IllegalArgumentException("POST needs a JSON object request body to model");
        String reqType = s.name() + "Request";
        boolean typedResp = s.responseSample() != null && s.responseSample().isObject();
        String respType = typedResp ? s.name() + "Response" : "com.fasterxml.jackson.databind.JsonNode";
        String respShort = typedResp ? s.name() + "Response" : "JsonNode";

        var out = new LinkedHashMap<String, String>();
        out.put(reqType + ".java", recordFile(pkg, reqType, s.requestSample()));
        if (typedResp) out.put(respType + ".java", recordFile(pkg, respType, s.responseSample()));
        out.put(s.name() + "Client.java", postClient(pkg, s.name(), reqType, respType, respShort, typedResp, s.url(), s.auth()));
        out.put(s.name() + "ApiController.java", postController(s.basePackage(), s.name(), reqType, respType, respShort, typedResp, s.withService()));
        if (s.withService()) out.put(s.name() + "ApiService.java", postService(pkg, s.name(), reqType, respType, respShort, typedResp));
        return out;
    }

    /* ------------------------------------------------- shape inference */

    /** A full .java file: one root record named rootType from obj, nested objects inlined. */
    private static String recordFile(String pkg, String rootType, JsonNode obj) {
        var records = new LinkedHashMap<String, JsonNode>();
        collect(rootType, obj, records);
        var sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import com.fasterxml.jackson.annotation.JsonIgnoreProperties;\n");
        sb.append("import com.fasterxml.jackson.annotation.JsonProperty;\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/** Generated from a sample payload. Add, remove, or retype fields as you like. */\n");
        sb.append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
        var nested = new ArrayList<>(records.keySet());
        nested.remove(rootType);
        emitRecord(sb, rootType, records.get(rootType), records, 0, nested);
        return sb.toString();
    }

    /** Register this object as a record and recurse into nested objects / arrays of objects. */
    private static void collect(String recordName, JsonNode obj, Map<String, JsonNode> into) {
        if (into.containsKey(recordName)) return;
        into.put(recordName, obj);
        for (var it = obj.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode v = e.getValue();
            if (v.isObject()) collect(cap(singular(e.getKey())), v, into);
            else if (v.isArray() && !v.isEmpty() && v.get(0).isObject())
                collect(cap(singular(e.getKey())), v.get(0), into);
        }
    }

    private static void emitRecord(StringBuilder sb, String type, JsonNode obj,
                                   Map<String, JsonNode> records, int indent, List<String> nestedToInline) {
        String pad = "    ".repeat(indent);
        sb.append(pad).append("public record ").append(type).append("(\n");
        var fields = new ArrayList<Map.Entry<String, JsonNode>>();
        obj.fields().forEachRemaining(fields::add);
        if (fields.isEmpty()) sb.append(pad).append("    // no fields in sample\n");
        for (int i = 0; i < fields.size(); i++) {
            var e = fields.get(i);
            String jsonName = e.getKey();
            String javaName = sanitize(jsonName);
            String type2 = javaType(jsonName, e.getValue());
            sb.append(pad).append("    ");
            if (!javaName.equals(jsonName)) sb.append("@JsonProperty(\"").append(jsonName).append("\") ");
            sb.append(type2).append(" ").append(javaName);
            sb.append(i < fields.size() - 1 ? ",\n" : "\n");
        }
        sb.append(pad).append(")");
        if (indent == 0 && !nestedToInline.isEmpty()) {
            sb.append(" {\n");
            for (String nn : nestedToInline)
                emitRecord(sb, nn, records.get(nn), records, 1, List.of());
            sb.append("}\n");
        } else {
            sb.append(" {}\n");
        }
    }

    private static String javaType(String field, JsonNode v) {
        if (v.isObject()) return cap(singular(field));
        if (v.isArray()) {
            if (v.isEmpty()) return "List<Object>";
            JsonNode first = v.get(0);
            if (first.isObject()) return "List<" + cap(singular(field)) + ">";
            return "List<" + scalarType(first) + ">";
        }
        return scalarType(v);
    }

    private static String scalarType(JsonNode v) {
        if (v.isTextual()) return "String";
        if (v.isBoolean()) return "Boolean";
        if (v.isIntegralNumber()) return "Long";
        if (v.isFloatingPointNumber()) return "Double";
        return "Object";
    }

    /* ------------------------------------------------- GET client / controller / service */

    private static String getClient(String pkg, String name, String rootType, String url, boolean isList, Auth auth) {
        boolean token = auth.kind() == Kind.TOKEN;
        boolean keyed = auth.kind() != Kind.NONE && !token;
        var sb = header(pkg, url);
        if (keyed) sb.insert(sb.indexOf("import org.springframework.core"),
            "import org.springframework.beans.factory.annotation.Value;\n");
        sb.append("@Component\n");
        sb.append("public class ").append(name).append("Client {\n\n");
        sb.append("    private static final String BASE = \"").append(url).append("\";\n");
        sb.append("    private final RestClient http = RestClient.create();\n");
        if (keyed) sb.append(keyField(auth));
        if (token) {
            sb.append("    private final AuthTokenProvider auth;\n\n");
            sb.append("    public ").append(name).append("Client(AuthTokenProvider auth) { this.auth = auth; }\n");
        }
        sb.append("\n");
        if (token) sb.append(RETRY_HELPER);
        String err = token ? ERR_NOT_401 : ERR;
        if (isList) {
            String listBody = "new ParameterizedTypeReference<List<" + rootType + ">>() {}";
            sb.append("    public List<").append(rootType).append("> list() {\n");
            sb.append(getMethodBody(token, getChain(auth, "BASE", ""), err, listBody));
            sb.append("    }\n\n");
            sb.append("    public ").append(rootType).append(" get(Object id) {\n");
            sb.append(getMethodBody(token, getChain(auth, "BASE + \"/{id}\"", "id"), err, rootType + ".class"));
            sb.append("    }\n");
        } else {
            sb.append("    public ").append(rootType).append(" fetch() {\n");
            sb.append(getMethodBody(token, getChain(auth, "BASE", ""), err, rootType + ".class"));
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** Method body: wrapped in withRetry(tok -> …) for TOKEN clients, plain otherwise. */
    private static String getMethodBody(boolean token, String chain, String err, String bodyCall) {
        if (token) {
            return "        return withRetry(tok -> " + chain + "\n"
                + "            .retrieve()\n" + err
                + "            .body(" + bodyCall + "));\n";
        }
        return "        return " + chain + "\n"
            + "            .retrieve()\n" + err
            + "            .body(" + bodyCall + ");\n";
    }

    private static String getChain(Auth auth, String baseExpr, String pathArgs) {
        String uriArgs = pathArgs.isEmpty() ? "" : ", " + pathArgs;
        return switch (auth.kind()) {
            case QUERY -> {
                String tmpl = baseExpr.endsWith("\"")
                    ? baseExpr.substring(0, baseExpr.length() - 1) + "?" + auth.paramName() + "={__k}\""
                    : baseExpr + " + \"?" + auth.paramName() + "={__k}\"";
                yield "http.get().uri(" + tmpl + uriArgs + ", apiKey)";
            }
            case BEARER -> "http.get().uri(" + baseExpr + uriArgs + ")\n            .header(\"Authorization\", \"Bearer \" + apiKey)";
            case HEADER -> "http.get().uri(" + baseExpr + uriArgs + ")\n            .header(\"" + auth.paramName() + "\", apiKey)";
            case TOKEN -> "http.get().uri(" + baseExpr + uriArgs + ")\n            .header(\"Authorization\", \"Bearer \" + tok)";
            default -> "http.get().uri(" + baseExpr + uriArgs + ")";
        };
    }

    private static String getController(String basePackage, String name, String rootType, boolean isList, boolean withService) {
        String dep = withService ? name + "ApiService" : name + "Client";
        String f = withService ? "service" : "client";
        var sb = ctrlHeader(basePackage, name, rootType, dep);
        if (isList) {
            sb.append("    @GetMapping\n    public List<").append(rootType).append("> all() {\n");
            sb.append("        return ").append(f).append(".").append(withService ? "all()" : "list()").append(";\n    }\n\n");
            sb.append("    @GetMapping(\"/{id}\")\n    public ").append(rootType).append(" one(@PathVariable Long id) {\n");
            sb.append("        return ").append(f).append(".").append(withService ? "byId(id)" : "get(id)").append(";\n    }\n");
        } else {
            sb.append("    @GetMapping\n    public ").append(rootType).append(" get() {\n");
            sb.append("        return ").append(f).append(".").append(withService ? "get()" : "fetch()").append(";\n    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String getService(String pkg, String name, String rootType, boolean isList) {
        var sb = svcHeader(pkg, name);
        if (isList) {
            sb.append("    public List<").append(rootType).append("> all() { return client.list(); }\n\n");
            sb.append("    public ").append(rootType).append(" byId(Object id) { return client.get(id); }\n");
        } else {
            sb.append("    public ").append(rootType).append(" get() { return client.fetch(); }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /* ------------------------------------------------- POST client / controller / service */

    private static String postClient(String pkg, String name, String reqType, String respType, String respShort,
                                     boolean typedResp, String url, Auth auth) {
        boolean token = auth.kind() == Kind.TOKEN;
        boolean keyed = auth.kind() != Kind.NONE && !token;
        var sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        if (keyed) sb.append("import org.springframework.beans.factory.annotation.Value;\n");
        if (!typedResp) sb.append("import com.fasterxml.jackson.databind.JsonNode;\n");
        sb.append("import org.springframework.stereotype.Component;\n");
        sb.append("import org.springframework.web.client.RestClient;\n\n");
        sb.append("/** Generated backend client for POST ").append(url).append(". Expand freely. */\n");
        sb.append("@Component\n");
        sb.append("public class ").append(name).append("Client {\n\n");
        sb.append("    private static final String BASE = \"").append(url).append("\";\n");
        sb.append("    private final RestClient http = RestClient.create();\n");
        if (keyed) sb.append(keyField(auth));
        if (token) {
            sb.append("    private final AuthTokenProvider auth;\n\n");
            sb.append("    public ").append(name).append("Client(AuthTokenProvider auth) { this.auth = auth; }\n");
        }
        sb.append("\n");
        if (token) sb.append(RETRY_HELPER);
        String uri = auth.kind() == Kind.QUERY ? "BASE + \"?" + auth.paramName() + "={__k}\", apiKey" : "BASE";
        String hdr = auth.kind() == Kind.BEARER ? "            .header(\"Authorization\", \"Bearer \" + apiKey)\n"
                   : auth.kind() == Kind.HEADER ? "            .header(\"" + auth.paramName() + "\", apiKey)\n"
                   : token ? "            .header(\"Authorization\", \"Bearer \" + tok)\n" : "";
        sb.append("    public ").append(respShort).append(" call(").append(reqType).append(" body) {\n");
        if (token) {
            sb.append("        return withRetry(tok -> http.post().uri(").append(uri).append(")\n");
            sb.append(hdr);
            sb.append("            .body(body).retrieve()\n").append(ERR_NOT_401);
            sb.append("            .body(").append(respShort).append(".class));\n");
        } else {
            sb.append("        return http.post().uri(").append(uri).append(")\n");
            sb.append(hdr);
            sb.append("            .body(body).retrieve()\n").append(ERR);
            sb.append("            .body(").append(respShort).append(".class);\n");
        }
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String postController(String basePackage, String name, String reqType, String respType,
                                        String respShort, boolean typedResp, boolean withService) {
        String dep = withService ? name + "ApiService" : name + "Client";
        String f = withService ? "service" : "client";
        var sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".web;\n\n");
        sb.append("import ").append(basePackage).append(".client.").append(reqType).append(";\n");
        if (typedResp) sb.append("import ").append(basePackage).append(".client.").append(respType).append(";\n");
        else sb.append("import com.fasterxml.jackson.databind.JsonNode;\n");
        sb.append("import ").append(basePackage).append(".client.").append(dep).append(";\n");
        sb.append("import org.springframework.web.bind.annotation.*;\n\n");
        sb.append("/** Exposes the ingested API through this backend. Adjust path/verbs as needed. */\n");
        sb.append("@RestController\n@RequestMapping(\"/api/").append(name.toLowerCase()).append("\")\n");
        sb.append("public class ").append(name).append("ApiController {\n\n");
        sb.append("    private final ").append(dep).append(" ").append(f).append(";\n\n");
        sb.append("    public ").append(name).append("ApiController(").append(dep).append(" ").append(f).append(") {\n");
        sb.append("        this.").append(f).append(" = ").append(f).append(";\n    }\n\n");
        sb.append("    @PostMapping\n    public ").append(respShort).append(" call(@RequestBody ").append(reqType).append(" body) {\n");
        sb.append("        return ").append(f).append(".call(body);\n    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String postService(String pkg, String name, String reqType, String respType,
                                     String respShort, boolean typedResp) {
        var sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        if (!typedResp) sb.append("import com.fasterxml.jackson.databind.JsonNode;\n");
        sb.append("import org.springframework.stereotype.Service;\n\n");
        sb.append("/** Thin wrapper so the rest of the app depends on a service, not the raw client. */\n");
        sb.append("@Service\n");
        sb.append("public class ").append(name).append("ApiService {\n\n");
        sb.append("    private final ").append(name).append("Client client;\n\n");
        sb.append("    public ").append(name).append("ApiService(").append(name).append("Client client) {\n");
        sb.append("        this.client = client;\n    }\n\n");
        sb.append("    public ").append(respShort).append(" call(").append(reqType).append(" body) { return client.call(body); }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /* ------------------------------------------------- shared header builders */

    private static StringBuilder header(String pkg, String url) {
        var sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import org.springframework.core.ParameterizedTypeReference;\n");
        sb.append("import org.springframework.stereotype.Component;\n");
        sb.append("import org.springframework.web.client.RestClient;\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/** Generated backend client for ").append(url).append(". Expand freely. */\n");
        return sb;
    }

    private static String keyField(Auth auth) {
        return "\n    /** Injected from the " + auth.envVar() + " property (see .env). */\n"
            + "    @Value(\"${" + auth.envVar() + ":}\")\n    private String apiKey;\n";
    }

    private static StringBuilder ctrlHeader(String basePackage, String name, String rootType, String dep) {
        var sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".web;\n\n");
        sb.append("import ").append(basePackage).append(".client.").append(rootType).append(";\n");
        sb.append("import ").append(basePackage).append(".client.").append(dep).append(";\n");
        sb.append("import org.springframework.web.bind.annotation.*;\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/** Exposes the ingested API through this backend. Adjust the path/verbs as needed. */\n");
        sb.append("@RestController\n@RequestMapping(\"/api/").append(name.toLowerCase()).append("\")\n");
        sb.append("public class ").append(name).append("ApiController {\n\n");
        sb.append("    private final ").append(dep).append(" ").append(dep.endsWith("Service") ? "service" : "client").append(";\n\n");
        sb.append("    public ").append(name).append("ApiController(").append(dep).append(" ")
          .append(dep.endsWith("Service") ? "service" : "client").append(") {\n");
        sb.append("        this.").append(dep.endsWith("Service") ? "service" : "client").append(" = ")
          .append(dep.endsWith("Service") ? "service" : "client").append(";\n    }\n\n");
        return sb;
    }

    private static StringBuilder svcHeader(String pkg, String name) {
        var sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import org.springframework.stereotype.Service;\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/** Thin wrapper so the rest of the app depends on a service, not the raw client. */\n");
        sb.append("@Service\n");
        sb.append("public class ").append(name).append("ApiService {\n\n");
        sb.append("    private final ").append(name).append("Client client;\n\n");
        sb.append("    public ").append(name).append("ApiService(").append(name).append("Client client) {\n");
        sb.append("        this.client = client;\n    }\n\n");
        return sb;
    }

    /* ------------------------------------------------- name helpers */

    private static String sanitize(String s) {
        var b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 0 && !Character.isJavaIdentifierStart(c)) b.append('_');
            if (Character.isJavaIdentifierPart(c)) b.append(c);
            else if (i > 0) b.append('_');
        }
        String out = b.length() == 0 ? "field" : b.toString();
        return RESERVED.contains(out) ? out + "_" : out;
    }

    static String cap(String s) {
        String x = sanitize(s);
        return x.isEmpty() ? x : Character.toUpperCase(x.charAt(0)) + x.substring(1);
    }

    static String singular(String s) {
        if (s.length() > 3 && s.endsWith("ies")) return s.substring(0, s.length() - 3) + "y";
        if (s.length() > 2 && s.endsWith("ses")) return s.substring(0, s.length() - 2);
        if (s.length() > 1 && s.endsWith("s") && !s.endsWith("ss")) return s.substring(0, s.length() - 1);
        return s;
    }
}
