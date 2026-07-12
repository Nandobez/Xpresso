package dev.nandobez.xpresso.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "api",
    description = "Call the running app's REST API. With -m, fills the request body with a valid mock read from OpenAPI.")
public class ApiCmd implements Callable<Integer> {

    @Parameters(index = "0", description = "HTTP method: GET | POST | PUT | PATCH | DELETE.")
    String method;

    @Parameters(index = "1", description = "Endpoint: 'products', '/api/products', or 'products/5'.")
    String endpoint;

    @Parameters(index = "2..*", arity = "0..*", description = "Body overrides as key=value (e.g. name=Coffee price=9.90).")
    List<String> overrides;

    @Option(names = {"-m", "--mock"}, description = "Fill the request body with a valid mock from the OpenAPI schema.")
    boolean mock;

    @Option(names = "--base", defaultValue = "http://localhost:8080", description = "Base URL of the running app.")
    String base;

    private static final ObjectMapper OM = new ObjectMapper();

    public Integer call() throws Exception {
        String m = method.toUpperCase();
        String path = endpoint.startsWith("/") ? endpoint : "/api/" + endpoint;
        String url = base + path;
        banner("xpresso api " + m, url);

        String body = null;
        if (hasBody(m)) {
            ObjectNode node = OM.createObjectNode();
            if (mock) {
                try {
                    mergeMock(node, m, path);
                } catch (Exception e) {
                    error("could not build mock from OpenAPI (" + e.getMessage() + ") — is the app running with springdoc?");
                    return 2;
                }
            }
            applyOverrides(node);
            if (!node.isEmpty()) body = OM.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            if (body != null) { info("request body:"); System.out.println(indent(body)); }
        }

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json");
        if (body != null) {
            rb.header("Content-Type", "application/json");
            rb.method(m, HttpRequest.BodyPublishers.ofString(body));
        } else {
            rb.method(m, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> res;
        try {
            res = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException e) {
            error("connection refused at " + base + " — start the app first (xpresso s)");
            return 2;
        }

        int sc = res.statusCode();
        String label = sc < 300 ? GRN + sc + R : (sc < 500 ? YLW + sc + R : RED + sc + R);
        System.out.println("    " + BLD + m + R + " " + path + "  ->  " + label);
        String out = res.body();
        if (out != null && !out.isBlank()) {
            try { out = OM.writerWithDefaultPrettyPrinter().writeValueAsString(OM.readTree(out)); }
            catch (Exception ignore) { /* not json, print raw */ }
            System.out.println(indent(out));
        }
        return sc < 400 ? 0 : 1;
    }

    private static boolean hasBody(String m) {
        return m.equals("POST") || m.equals("PUT") || m.equals("PATCH");
    }

    /** Fetch /v3/api-docs, resolve the request schema for (method, path), and fill node with mock values. */
    private void mergeMock(ObjectNode node, String m, String path) throws Exception {
        JsonNode api = OM.readTree(URI.create(base + "/v3/api-docs").toURL());
        JsonNode paths = api.path("paths");
        JsonNode op = matchPath(paths, path).path(m.toLowerCase());
        JsonNode schema = op.path("requestBody").path("content").path("application/json").path("schema");
        schema = resolve(api, schema);
        JsonNode props = schema.path("properties");
        var it = props.fields();
        while (it.hasNext()) {
            var e = it.next();
            node.set(e.getKey(), mockValue(api, e.getKey(), resolve(api, e.getValue())));
        }
    }

    /** Match a concrete path against templated OpenAPI keys (numeric segments match {param}). */
    private static JsonNode matchPath(JsonNode paths, String concrete) {
        if (paths.has(concrete)) return paths.get(concrete);
        String[] cs = concrete.split("/");
        var it = paths.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            String[] ks = key.split("/");
            if (ks.length != cs.length) continue;
            boolean ok = true;
            for (int i = 0; i < ks.length; i++) {
                boolean param = ks[i].startsWith("{") && ks[i].endsWith("}");
                if (!param && !ks[i].equals(cs[i])) { ok = false; break; }
            }
            if (ok) return paths.get(key);
        }
        return OM.createObjectNode();
    }

    private static JsonNode resolve(JsonNode api, JsonNode node) {
        if (node != null && node.has("$ref")) {
            String ref = node.get("$ref").asText();          // #/components/schemas/XRequest
            JsonNode cur = api;
            for (String seg : ref.replace("#/", "").split("/")) cur = cur.path(seg);
            return cur;
        }
        return node == null ? OM.createObjectNode() : node;
    }

    /** Pick a valid mock value, preferring name heuristics, then schema type/format/constraints. */
    private JsonNode mockValue(JsonNode api, String name, JsonNode schema) {
        String n = name.toLowerCase();
        if (schema.has("enum")) return schema.get("enum").get(0);
        String type = schema.path("type").asText("string");
        String format = schema.path("format").asText("");

        // name heuristics for strings (springdoc often omits format)
        if (type.equals("string")) {
            if (n.contains("email"))                       return t("user@example.com");
            if (n.contains("url") || n.contains("website") || n.contains("link")) return t("https://example.com");
            if (n.endsWith("name"))                        return t("Sample Name");
            if (n.contains("phone"))                       return t("+1-555-0100");
            if (format.equals("email"))                    return t("user@example.com");
            if (format.equals("date"))                     return t("2024-01-01");
            if (format.equals("date-time"))                return t("2024-01-01T00:00:00Z");
            if (format.equals("uri") || format.equals("url")) return t("https://example.com");
            if (format.equals("uuid"))                     return t("00000000-0000-0000-0000-000000000001");
            int min = schema.path("minLength").asInt(0);
            String s = "sample";
            while (s.length() < min) s += "x";
            return t(s);
        }
        if (type.equals("integer")) {
            long min = schema.has("minimum") ? schema.get("minimum").asLong()
                     + (schema.path("exclusiveMinimum").asBoolean(false) ? 1 : 0) : 1;
            return OM.getNodeFactory().numberNode(min);
        }
        if (type.equals("number")) {
            double min = schema.has("minimum") ? schema.get("minimum").asDouble()
                       + (schema.path("exclusiveMinimum").asBoolean(false) ? 0.01 : 0) : 9.99;
            return OM.getNodeFactory().numberNode(min);
        }
        if (type.equals("boolean")) return OM.getNodeFactory().booleanNode(true);
        if (type.equals("array")) {
            var arr = OM.createArrayNode();
            arr.add(mockValue(api, name, resolve(api, schema.path("items"))));
            return arr;
        }
        if (type.equals("object")) {
            ObjectNode obj = OM.createObjectNode();
            var it = schema.path("properties").fields();
            while (it.hasNext()) { var e = it.next(); obj.set(e.getKey(), mockValue(api, e.getKey(), resolve(api, e.getValue()))); }
            return obj;
        }
        return t("sample");
    }

    private JsonNode t(String s) { return OM.getNodeFactory().textNode(s); }

    /** Apply key=value overrides, coercing booleans/numbers, leaving everything else as text. */
    private void applyOverrides(ObjectNode node) {
        if (overrides == null) return;
        for (String kv : overrides) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
            if (v.equals("true") || v.equals("false")) node.put(k, Boolean.parseBoolean(v));
            else if (v.matches("-?\\d+")) node.put(k, Long.parseLong(v));
            else if (v.matches("-?\\d+\\.\\d+")) node.put(k, Double.parseDouble(v));
            else node.put(k, v);
        }
    }

    private static String indent(String s) {
        var sb = new StringBuilder();
        for (String line : s.split("\n")) sb.append("      ").append(line).append("\n");
        return sb.toString().stripTrailing();
    }
}
