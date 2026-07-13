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

    @Option(names = "--raw", description = "Print the raw pretty JSON instead of the formatted view.")
    boolean raw;

    private static final ObjectMapper OM = new ObjectMapper();

    // Local color gate (honors NO_COLOR / non-tty) for the formatted body view.
    private final boolean color = System.getenv("NO_COLOR") == null && System.console() != null;
    private static final String RST = "\033[0m", KEY = "\033[36m", STR = "\033[32m",
        NUM = "\033[33m", BOL = "\033[35m", DMc = "\033[2m", BDc = "\033[1m", RDc = "\033[31m";
    private String col(String code, String s) { return color ? code + s + RST : s; }

    public Integer call() throws Exception {
        String m = method.toUpperCase();
        String path = endpoint.startsWith("/") ? endpoint : "/api/" + endpoint;
        String url = base + path;
        banner("xpresso api " + m, url);

        String body = null;
        ObjectNode reqNode = null;
        if (hasBody(m)) {
            reqNode = OM.createObjectNode();
            if (mock) {
                try {
                    mergeMock(reqNode, m, path);
                } catch (Exception e) {
                    error("could not build mock from OpenAPI (" + e.getMessage() + ") — is the app running with springdoc?");
                    return 2;
                }
            }
            applyOverrides(reqNode);
            if (!reqNode.isEmpty()) body = OM.writeValueAsString(reqNode);
        }

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json");
        if (body != null) {
            rb.header("Content-Type", "application/json");
            rb.method(m, HttpRequest.BodyPublishers.ofString(body));
        } else {
            rb.method(m, HttpRequest.BodyPublishers.noBody());
        }

        long t0 = System.nanoTime();
        HttpResponse<String> res;
        try {
            res = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException e) {
            error("connection refused at " + base + " — start the app first (xpresso s)");
            return 2;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        int sc = res.statusCode();
        String scCol = sc < 300 ? STR : (sc < 500 ? NUM : RDc);
        System.out.println();
        System.out.println("  " + col(BDc, m) + " " + path + "  " + col(DMc, "→") + "  "
            + col(scCol, String.valueOf(sc)) + col(DMc, " · " + ms + "ms"));

        String out = res.body();
        JsonNode root = null;
        if (out != null && !out.isBlank()) {
            try { root = OM.readTree(out); } catch (Exception ignore) {}
        }

        // --raw: dump pretty JSON as-is (request body too) and stop.
        if (raw) {
            if (reqNode != null && !reqNode.isEmpty()) rawBlock("request", reqNode, null);
            rawBlock("response", root, root == null ? out : null);
            System.out.println();
            return sc < 400 ? 0 : 1;
        }

        // Formatted view — labeled sections (chosen UX).
        if (reqNode != null && !reqNode.isEmpty()) {
            System.out.println();
            kvSection("→ request", reqNode);
        }
        if (root != null && isPage(root)) {
            System.out.println();
            renderPage(root);
        } else if (root != null && root.isObject()) {
            System.out.println();
            kvSection("← " + respLabel(m), root);
        } else if (root != null) {
            System.out.println();
            System.out.println(indent(OM.writerWithDefaultPrettyPrinter().writeValueAsString(root)));
        } else if (out != null && !out.isBlank()) {
            System.out.println();
            System.out.println(indent(out));
        }
        System.out.println();
        return sc < 400 ? 0 : 1;
    }

    private static String respLabel(String m) {
        return switch (m) {
            case "POST" -> "created";
            case "PUT", "PATCH" -> "updated";
            default -> "response";
        };
    }

    /** Label + aligned key/value block (no JSON braces), colored by value type. */
    private void kvSection(String label, JsonNode o) {
        System.out.println("  " + col(DMc, label));
        int w = 0;
        for (var it = o.fieldNames(); it.hasNext(); ) w = Math.max(w, it.next().length());
        for (var it = o.fields(); it.hasNext(); ) {
            var e = it.next();
            System.out.println("      " + col(KEY, e.getKey())
                + " ".repeat(w - e.getKey().length() + 2) + jval(e.getValue()));
        }
    }

    // ---- formatted views ----

    private static boolean isPage(JsonNode n) {
        return n.isObject() && n.path("content").isArray()
            && (n.has("totalElements") || n.has("pageable"));
    }

    /** Aligned table: id (right), title (fixed), one column per scalar field, header + rule (chosen UX). */
    private void renderPage(JsonNode page) {
        int[] sz = termSize();
        boolean narrow = sz[1] < 70;
        JsonNode content = page.get("content");
        int total  = page.path("totalElements").asInt(content.size());
        int pageNo = page.path("number").asInt(0);
        int pages  = page.path("totalPages").asInt(1);

        int budget = Math.max(3, sz[0] - 7);
        int n = content.size();
        int show = Math.min(n, budget);

        String title = show > 0 ? pickTitle(content.get(0)) : null;
        java.util.List<String> vc = new java.util.ArrayList<>();
        if (show > 0)
            for (var it = content.get(0).fields(); it.hasNext(); ) {
                var e = it.next();
                if (e.getKey().equals("id") || e.getKey().equals(title) || !e.getValue().isValueNode()) continue;
                vc.add(e.getKey());
            }
        if (narrow && vc.size() > 2) vc = new java.util.ArrayList<>(vc.subList(0, 2));
        int titleCap = narrow ? 16 : 24;

        // column widths across the shown rows
        int idW = 1;
        int titleW = title == null ? 0 : "name".length();
        int[] cw = new int[vc.size()];
        for (int c = 0; c < vc.size(); c++) cw[c] = vc.get(c).length();
        for (int i = 0; i < show; i++) {
            JsonNode row = content.get(i);
            idW = Math.max(idW, row.path("id").asText("").length());
            if (title != null) titleW = Math.max(titleW, Math.min(titleCap, row.path(title).asText().length()));
            for (int c = 0; c < vc.size(); c++)
                cw[c] = Math.max(cw[c], plainCell(vc.get(c), row.get(vc.get(c))).length());
        }

        // header + rule
        StringBuilder hd = new StringBuilder("  " + padL("#", idW));
        StringBuilder rl = new StringBuilder("  " + rule(idW));
        if (title != null) { hd.append("  ").append(pad("name", titleW)); rl.append("  ").append(rule(titleW)); }
        for (int c = 0; c < vc.size(); c++) {
            boolean rt = numeric(content.get(0).get(vc.get(c)));
            hd.append("  ").append(rt ? padL(vc.get(c), cw[c]) : pad(vc.get(c), cw[c]));
            rl.append("  ").append(rule(cw[c]));
        }
        System.out.println(col(DMc, hd.toString()));
        System.out.println(col(DMc, rl.toString()));

        // rows
        for (int i = 0; i < show; i++) {
            JsonNode row = content.get(i);
            String id = row.path("id").asText("");
            StringBuilder ln = new StringBuilder("  ").append(" ".repeat(Math.max(0, idW - id.length())))
                .append(col(BDc, id));
            if (title != null) ln.append("  ").append(pad(cut(row.path(title).asText(), titleW), titleW));
            for (int c = 0; c < vc.size(); c++) ln.append("  ").append(cellColored(vc.get(c), row.get(vc.get(c)), cw[c]));
            System.out.println(ln.toString());
        }
        if (show < n) System.out.println("  " + col(DMc, "… " + (n - show) + " more (--raw for all)"));
        System.out.println();

        String footer = "↳ page " + (pageNo + 1) + "/" + pages + " · " + n + " of " + total;
        if (!narrow && !page.path("last").asBoolean(true))
            footer += " · next → api GET '" + endpoint + "?page=" + (pageNo + 1) + "'";
        System.out.println("  " + col(DMc, footer));
    }

    private static boolean numeric(JsonNode v) { return v != null && v.isNumber(); }

    private static boolean isMoney(String k) {
        String s = k.toLowerCase();
        return s.contains("price") || s.contains("amount") || s.contains("total")
            || s.contains("cost") || s.contains("salary") || s.contains("value") || s.contains("balance");
    }

    /** Uncolored cell text — used only to measure column width. */
    private String plainCell(String k, JsonNode v) {
        if (v == null || v.isNull()) return "";
        if (v.isBoolean()) return "●";
        if (v.isNumber() && isMoney(k)) return "$" + String.format(java.util.Locale.US, "%.2f", v.asDouble());
        return v.asText();
    }

    /** Colored, width-padded cell: numbers/money right-aligned, booleans as a state dot, strings left. */
    private String cellColored(String k, JsonNode v, int w) {
        if (v == null || v.isNull()) return pad("", w);
        if (v.isBoolean()) {
            String dot = v.asBoolean() ? col(STR, "●") : col(DMc, "○");
            return dot + " ".repeat(Math.max(0, w - 1));
        }
        if (v.isNumber()) {
            String plain = plainCell(k, v);
            return " ".repeat(Math.max(0, w - plain.length())) + col(NUM, plain);
        }
        return pad(cut(v.asText(), w), w);
    }

    private static String padL(String s, int w) { return s.length() >= w ? s : " ".repeat(w - s.length()) + s; }
    private static String rule(int w) { return "─".repeat(Math.max(1, w)); }
    private static String cut(String s, int w) { return s.length() <= w ? s : s.substring(0, Math.max(1, w - 1)) + "…"; }

    /** Terminal {rows, cols} via stty; falls back to COLUMNS/LINES env, then 24x80. */
    private static int[] termSize() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "stty size < /dev/tty 2>/dev/null")
                .redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            String[] parts = s.split("\\s+");
            if (parts.length == 2) return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception ignored) {}
        try {
            int c = Integer.parseInt(System.getenv().getOrDefault("COLUMNS", "0"));
            int r = Integer.parseInt(System.getenv().getOrDefault("LINES", "0"));
            if (c > 0 && r > 0) return new int[]{r, c};
        } catch (Exception ignored) {}
        return new int[]{24, 80};
    }

    private static String pickTitle(JsonNode o) {
        for (String pref : new String[]{"name", "title", "label"})
            if (o.has(pref) && o.get(pref).isTextual()) return pref;
        var it = o.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getKey().equals("id") && e.getValue().isTextual()) return e.getKey();
        }
        return null;
    }

    /** --raw block: blank · label · blank · colored payload (blank below comes from the next block/end). */
    private void rawBlock(String label, JsonNode node, String fallback) {
        System.out.println();
        System.out.println("  " + col(DMc, label));
        System.out.println();
        if (node != null) System.out.println(pad4(cjson(node, 0)));
        else if (fallback != null && !fallback.isBlank()) System.out.println(pad4(fallback));
    }

    /** Recursive colored JSON pretty-printer (keys cyan, punctuation dim, scalars via jval). */
    private String cjson(JsonNode n, int ind) {
        String pad = "  ".repeat(ind), pad2 = "  ".repeat(ind + 1);
        if (n.isObject()) {
            if (n.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            for (var it = n.fields(); it.hasNext(); ) {
                var e = it.next();
                sb.append(pad2).append(col(KEY, "\"" + e.getKey() + "\"")).append(col(DMc, ": "))
                  .append(cjson(e.getValue(), ind + 1)).append(it.hasNext() ? col(DMc, ",") : "").append("\n");
            }
            return sb.append(pad).append("}").toString();
        }
        if (n.isArray()) {
            if (n.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < n.size(); i++)
                sb.append(pad2).append(cjson(n.get(i), ind + 1)).append(i < n.size() - 1 ? col(DMc, ",") : "").append("\n");
            return sb.append(pad).append("]").toString();
        }
        return jval(n);
    }

    private static String pad4(String s) {
        var sb = new StringBuilder();
        for (String l : s.split("\n")) sb.append("    ").append(l).append("\n");
        return sb.toString().stripTrailing();
    }

    private String jval(JsonNode v) {
        if (v.isTextual()) return col(STR, "\"" + v.asText() + "\"");
        if (v.isNumber())  return col(NUM, v.asText());
        if (v.isBoolean()) return col(BOL, v.asText());
        if (v.isNull())    return col(DMc, "null");
        return v.toString();
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
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
