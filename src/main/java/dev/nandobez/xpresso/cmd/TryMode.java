package dev.nandobez.xpresso.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.net.URI;
import java.net.http.*;
import java.util.*;

import static dev.nandobez.xpresso.cmd.Tui.*;

/**
 * `api --try`: a tiny Postman-in-the-terminal.
 * Flow: pick an endpoint (fuzzy) → fill the body field-by-field → fire → render formatted ⇄ raw.
 *
 * Endpoint source: the running app's OpenAPI (/v3/api-docs), or a saved doc via --docs (offline).
 * Interactive by default; also fully scriptable (--list / --pick / --set / --body) so it runs in CI
 * and so the flow is testable without a TTY.
 */
public final class TryMode {

    private final ObjectMapper OM = new ObjectMapper();
    private final String base;          // running app, e.g. http://localhost:8080
    private final String docsFile;      // offline OpenAPI json (optional)
    private final boolean listOnly;
    private final String pick;          // non-interactive endpoint selector (fuzzy)
    private final String bodyArg;       // non-interactive body (json literal or @file)
    private final Map<String, String> sets; // non-interactive field overrides: path -> value
    private final boolean upstream;     // reserved: fire upstream instead of backend (future)

    TryMode(String base, String docsFile, boolean listOnly, String pick,
            String bodyArg, Map<String, String> sets, boolean upstream) {
        this.base = base; this.docsFile = docsFile; this.listOnly = listOnly;
        this.pick = pick; this.bodyArg = bodyArg; this.sets = sets; this.upstream = upstream;
    }

    record Endpoint(String method, String path, String summary, JsonNode reqExample) {
        boolean hasBody() { return reqExample != null && !reqExample.isNull(); }
        List<String> pathParams() {
            var out = new ArrayList<String>();
            var m = java.util.regex.Pattern.compile("\\{(\\w+)}").matcher(path);
            while (m.find()) out.add(m.group(1));
            return out;
        }
        String label() { return method + "  " + path; }
    }

    Integer run() throws Exception {
        List<Endpoint> eps = discover();
        if (eps.isEmpty()) {
            error("no endpoints found. Start the app (xpresso s) or pass --docs <api-docs.json>.");
            return 2;
        }
        if (listOnly) {
            String q = pick == null ? "" : pick;
            for (Endpoint e : rank(eps, q)) System.out.println("  " + pad(e.method(), 5) + " " + e.path()
                + (e.summary() == null || e.summary().isBlank() ? "" : DIM + "   " + e.summary() + R));
            return 0;
        }

        Endpoint ep;
        if (pick != null) ep = bestMatch(eps, pick);
        else {
            try { ep = System.console() != null ? pickRaw(eps) : pickInteractive(eps); }
            catch (Exception rawFailed) { ep = pickInteractive(eps); }  // no /dev/tty or stty → line mode
        }
        if (ep == null) { info("cancelled."); return 0; }

        // Path params
        var pathValues = new LinkedHashMap<String, String>();
        for (String pp : ep.pathParams()) {
            String v = sets.get(pp) != null ? sets.get(pp)
                     : prompt("  › " + col(BLD, pp) + "   ", pp + " · path");
            pathValues.put(pp, v == null ? "" : v);
        }

        // Body
        JsonNode body = null;
        if (ep.hasBody() || bodyArg != null) {
            if (bodyArg != null) body = OM.readTree(readBodyArg());
            else if (!sets.isEmpty()) body = applySets(ep.reqExample().deepCopy());
            else body = wizard(ep.reqExample());
        }

        // Fire against the running backend.
        String url = base + resolvePath(ep.path(), pathValues);
        long t0 = System.nanoTime();
        System.out.println();
        Spinner sp = Spinner.start(col(BLD, ep.method()) + " " + resolvePath(ep.path(), pathValues) + DIM + " …" + R);
        HttpResponse<String> res;
        try {
            var rb = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json");
            if (body != null) rb.header("Content-Type", "application/json")
                .method(ep.method(), HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body)));
            else rb.method(ep.method(), HttpRequest.BodyPublishers.noBody());
            res = HttpClient.newHttpClient().send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException e) {
            sp.stop(); error("connection refused at " + base + " — start the app first (xpresso s)"); return 2;
        } finally {
            sp.stop();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        JsonNode json = null;
        try { json = OM.readTree(res.body()); } catch (Exception ignore) {}
        rMethod = ep.method(); rPath = resolvePath(ep.path(), pathValues); rStatus = res.statusCode(); rMs = ms;
        boolean err = res.statusCode() >= 400;
        renderFormatted(json, res.body(), err);

        // Toggle raw / done (interactive; in scripted mode we just also print raw once).
        if (pick != null || bodyArg != null || !sets.isEmpty()) { renderRaw(json, res.body()); return err ? 1 : 0; }
        actionLoop(json, res.body());
        return err ? 1 : 0;
    }

    /* ------------------------------------------------- discovery (OpenAPI) */

    private List<Endpoint> discover() {
        JsonNode doc;
        try {
            String raw = docsFile != null
                ? java.nio.file.Files.readString(java.nio.file.Path.of(docsFile))
                : httpGet(base + "/v3/api-docs");
            doc = OM.readTree(raw);
        } catch (Exception e) { return List.of(); }

        JsonNode components = doc.path("components").path("schemas");
        var out = new ArrayList<Endpoint>();
        JsonNode paths = doc.path("paths");
        paths.fields().forEachRemaining(pe -> {
            String path = pe.getKey();
            pe.getValue().fields().forEachRemaining(me -> {
                String method = me.getKey().toUpperCase();
                if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) return;
                JsonNode op = me.getValue();
                String summary = op.path("summary").asText(op.path("operationId").asText(""));
                JsonNode schema = op.path("requestBody").path("content")
                    .path("application/json").path("schema");
                JsonNode example = schema.isMissingNode() ? null : example(schema, components, 0);
                out.add(new Endpoint(method, path, summary, example));
            });
        });
        out.sort(Comparator.comparing(Endpoint::path).thenComparing(Endpoint::method));
        return out;
    }

    /** Build a representative example object from an OpenAPI schema (resolving $ref). */
    private JsonNode example(JsonNode schema, JsonNode components, int depth) {
        if (schema == null || schema.isMissingNode() || depth > 12) return NullNode.instance;
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            return example(components.path(name), components, depth + 1);
        }
        if (schema.has("example")) return schema.get("example");
        String type = schema.path("type").asText("object");
        switch (type) {
            case "array" -> {
                var arr = OM.createArrayNode();
                arr.add(example(schema.path("items"), components, depth + 1));
                return arr;
            }
            case "string" -> { return new TextNode(schema.path("format").asText("").equals("date-time")
                ? "2026-01-01T00:00:00Z" : ""); }
            case "integer" -> { return new LongNode(0); }
            case "number" -> { return new DoubleNode(0.0); }
            case "boolean" -> { return BooleanNode.FALSE; }
            default -> {
                var obj = OM.createObjectNode();
                JsonNode props = schema.path("properties");
                props.fields().forEachRemaining(pe ->
                    obj.set(pe.getKey(), example(pe.getValue(), components, depth + 1)));
                return obj;
            }
        }
    }

    /* ------------------------------------------------- fuzzy match */

    /** fzf-ish score: all query chars appear in order; bonus for contiguity and word starts. -1 = no match. */
    static int score(String query, String cand) {
        if (query.isEmpty()) return 0;
        String q = query.toLowerCase(), c = cand.toLowerCase();
        int qi = 0, s = 0, streak = 0;
        for (int ci = 0; ci < c.length() && qi < q.length(); ci++) {
            if (c.charAt(ci) == q.charAt(qi)) {
                s += 1 + streak;
                if (ci == 0 || !Character.isLetterOrDigit(c.charAt(ci - 1))) s += 3; // word start
                streak++; qi++;
            } else streak = 0;
        }
        return qi == q.length() ? s : -1;
    }

    private List<Endpoint> rank(List<Endpoint> eps, String q) {
        if (q.isBlank()) return eps;
        return eps.stream()
            .map(e -> Map.entry(e, score(q, e.method() + " " + e.path() + " " + e.summary())))
            .filter(en -> en.getValue() >= 0)
            .sorted((a, b) -> b.getValue() - a.getValue())
            .map(Map.Entry::getKey).toList();
    }

    private Endpoint bestMatch(List<Endpoint> eps, String q) {
        var r = rank(eps, q);
        return r.isEmpty() ? null : r.get(0);
    }

    /* ------------------------------------------------- interactive picker (raw mode: live fuzzy + arrows) */

    private Endpoint pickRaw(List<Endpoint> eps) throws Exception {
        String saved = sttyGet();                         // throws if no /dev/tty → caller falls back
        sttyCmd("-echo -icanon min 1 time 0");
        System.out.print("\033[?1049h");                  // alternate screen
        System.out.flush();
        try {
            var q = new StringBuilder();
            int sel = 0;
            while (true) {
                var matches = rank(eps, q.toString());
                if (sel >= matches.size()) sel = Math.max(0, matches.size() - 1);
                drawPicker(q.toString(), matches, sel, eps.size());
                int c = System.in.read();
                if (c == -1 || c == 3) return null;                       // EOF / Ctrl-C
                if (c == 13 || c == 10) return matches.isEmpty() ? null : matches.get(sel);
                if (c == 127 || c == 8) { if (q.length() > 0) q.deleteCharAt(q.length() - 1); sel = 0; continue; }
                if (c == 27) {                                            // ESC or arrow sequence
                    if (System.in.available() == 0) return null;         // lone ESC = cancel
                    int a = System.in.read(), b = System.in.read();
                    if (a == '[') {
                        if (b == 'A') sel = Math.max(0, sel - 1);
                        else if (b == 'B') sel = Math.min(Math.max(0, matches.size() - 1), sel + 1);
                    }
                    continue;
                }
                if (c >= 32 && c < 127) { q.append((char) c); sel = 0; }
            }
        } finally {
            System.out.print("\033[?1049l");              // restore screen
            System.out.flush();
            sttyRestore(saved);
        }
    }

    private void drawPicker(String query, List<Endpoint> matches, int sel, int total) {
        var sb = new StringBuilder("\033[H\033[2J");      // home + clear
        sb.append("\n  ").append(BOXTL).append(" ").append(BLD).append("try").append(R)
          .append(DIM).append("   ").append(matches.size()).append("/").append(total)
          .append(" · ").append(base.replaceFirst("https?://", "")).append(R).append("\n");
        sb.append("  ").append(BAR2).append("  search ").append(BLD).append("▸ ").append(R).append(query).append("\n");
        sb.append("  ").append(BAR2).append("\n");
        int show = Math.min(10, matches.size());
        for (int i = 0; i < show; i++) {
            Endpoint e = matches.get(i);
            boolean on = i == sel;
            sb.append("  ").append(BAR2).append("  ").append(on ? col(BLD, "❯ ") : "  ")
              .append(on ? BLD : "").append(pad(e.method(), 5)).append(R).append(" ")
              .append(hiColor(e.path(), query))
              .append(e.summary().isBlank() ? "" : DIM + "   " + e.summary() + R).append("\n");
        }
        if (matches.size() > show)
            sb.append("  ").append(BAR2).append(DIM).append("  … +").append(matches.size() - show).append(R).append("\n");
        sb.append("  ").append(BOXBL).append(DIM)
          .append("  type to filter · ↑↓ move · ↵ open · esc cancel").append(R);
        System.out.print(sb);
        System.out.flush();
    }

    /** Highlight the fuzzy-matched chars in cand using color. */
    private String hiColor(String cand, String query) {
        if (query.isBlank() || !color) return cand;
        String q = query.toLowerCase(), c = cand.toLowerCase();
        var sb = new StringBuilder(); int qi = 0;
        for (int i = 0; i < cand.length(); i++) {
            if (qi < q.length() && c.charAt(i) == q.charAt(qi)) { sb.append(CYN).append(cand.charAt(i)).append(R); qi++; }
            else sb.append(cand.charAt(i));
        }
        return sb.toString();
    }

    /* stty helpers — capture/restore the controlling terminal's mode. */
    private static String sttyGet() throws Exception {
        var p = new ProcessBuilder("sh", "-c", "stty -g < /dev/tty").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0 || out.isEmpty()) throw new IllegalStateException("no tty");
        return out;
    }
    private static void sttyCmd(String args) throws Exception {
        new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty").inheritIO().start().waitFor();
    }
    private static void sttyRestore(String saved) {
        try { new ProcessBuilder("sh", "-c", "stty " + saved + " < /dev/tty").inheritIO().start().waitFor(); }
        catch (Exception ignore) {}
    }

    /* ------------------------------------------------- interactive picker (line-based, TTY-safe) */

    private Endpoint pickInteractive(List<Endpoint> eps) throws Exception {
        String query = "";
        while (true) {
            var matches = rank(eps, query);
            System.out.println();
            System.out.println("  " + BOXTL + " " + BLD + "try" + R + DIM + "   "
                + matches.size() + "/" + eps.size() + " · " + base.replaceFirst("https?://", "") + R);
            System.out.println("  " + BAR2 + "  search " + col(BLD, "▸ ") + query);
            int show = Math.min(8, matches.size());
            for (int i = 0; i < show; i++) {
                Endpoint e = matches.get(i);
                System.out.println("  " + BAR2 + "  " + (i == 0 ? col(BLD, "❯ ") : "  ")
                    + col(BLD, pad(e.method(), 5)) + " " + hi(e.path(), query)
                    + (e.summary().isBlank() ? "" : DIM + "   " + e.summary() + R));
            }
            if (matches.size() > show) System.out.println("  " + BAR2 + DIM + "  … +" + (matches.size() - show) + R);
            System.out.println("  " + BOXBL + DIM + "  type to filter · number to open · ↵ first · q quit" + R);

            String in = prompt("  ▸ ", null);
            if (in == null || in.equals("q")) return null;
            in = in.strip();
            if (in.isEmpty()) { if (!matches.isEmpty()) return matches.get(0); else continue; }
            if (in.matches("\\d+")) {
                int idx = Integer.parseInt(in) - 1;
                if (idx >= 0 && idx < matches.size()) return matches.get(idx);
            }
            query = in;               // treat as new filter
            if (rank(eps, query).size() == 1) return rank(eps, query).get(0);  // auto-open single match
        }
    }

    /** Highlight fuzzy-matched chars of the query in cand with [ ]. */
    static String hi(String cand, String query) {
        if (query.isBlank()) return cand;
        String q = query.toLowerCase(), c = cand.toLowerCase();
        var sb = new StringBuilder();
        int qi = 0;
        for (int i = 0; i < cand.length(); i++) {
            if (qi < q.length() && c.charAt(i) == q.charAt(qi)) { sb.append('[').append(cand.charAt(i)).append(']'); qi++; }
            else sb.append(cand.charAt(i));
        }
        return sb.toString();
    }

    /* ------------------------------------------------- wizard */

    /** Rebuild a JSON body by prompting for each leaf, looping on arrays — inside a box, with breathing room. */
    private JsonNode wizard(JsonNode example) throws Exception {
        System.out.println();
        System.out.println("  " + BOXTL + " " + col(BLD, "body") + R + DIM + "   ↵ keeps the example · type to override" + R);
        System.out.println("  " + BAR2);
        JsonNode r = build(example, "", 0);
        System.out.println("  " + BOXBL);
        return r;
    }

    private static String bar(int depth) { return "  " + BAR2 + "  " + "  ".repeat(depth); }

    private JsonNode build(JsonNode ex, String path, int depth) throws Exception {
        if (ex.isObject()) {
            var obj = OM.createObjectNode();
            var it = ex.fields();
            while (it.hasNext()) {
                var e = it.next();
                String childPath = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                JsonNode v = e.getValue();
                if (v.isObject() || v.isArray()) {
                    System.out.println(bar(depth) + DIM + e.getKey() + R);          // group header
                    obj.set(e.getKey(), build(v, childPath, depth + 1));
                } else {
                    obj.set(e.getKey(), leaf(e.getKey(), childPath, v, depth));
                }
            }
            return obj;
        }
        if (ex.isArray()) {
            var arr = OM.createArrayNode();
            JsonNode proto = ex.isEmpty() ? new TextNode("") : ex.get(0);
            int i = 0;
            while (true) {
                arr.add(build(proto, path + "[" + i + "]", depth));
                String more = prompt(bar(depth) + DIM + "+ another " + leafName(path) + "?  [y/N] " + R + col(BLD, "▸ "), null);
                System.out.println("  " + BAR2);
                if (more == null || !more.strip().equalsIgnoreCase("y")) break;
                i++;
            }
            return arr;
        }
        return leaf(leafName(path), path, ex, depth);
    }

    private JsonNode leaf(String name, String path, JsonNode ex, int depth) throws Exception {
        String type = ex.isTextual() ? "String" : ex.isIntegralNumber() ? "Long"
            : ex.isFloatingPointNumber() ? "Double" : ex.isBoolean() ? "Boolean" : "String";
        String def = ex.isNull() ? "" : ex.asText("");
        String ref = path + " · " + type + (def.isBlank() ? "" : " · e.g. " + cut(def, 20));
        // label line: bold name, grey reference aligned to the right
        String head = bar(depth) + col(BLD, name);
        int visible = 2 + 2 + depth * 2 + name.length();      // "  │  " + indent + name
        int gap = Math.max(3, 34 - visible);
        System.out.println(head + " ".repeat(gap) + DIM + ref + R);
        // input line
        String v = prompt(bar(depth) + col(BLD, "▸ "), null);
        System.out.println("  " + BAR2);                       // blank line = breathing room
        if (v == null || v.isEmpty()) return ex.isNull() ? new TextNode("") : ex;   // keep example
        return switch (type) {
            case "Long" -> { try { yield new LongNode(Long.parseLong(v.trim())); } catch (Exception e) { yield new TextNode(v); } }
            case "Double" -> { try { yield new DoubleNode(Double.parseDouble(v.trim())); } catch (Exception e) { yield new TextNode(v); } }
            case "Boolean" -> BooleanNode.valueOf(v.trim().equalsIgnoreCase("true") || v.trim().equals("y"));
            default -> new TextNode(v);
        };
    }

    private JsonNode applySets(JsonNode example) {
        for (var e : sets.entrySet()) setPath((ObjectNode) example, e.getKey(), e.getValue());
        return example;
    }

    private void setPath(JsonNode root, String path, String value) {
        // supports a.b, a.b[0].c
        String[] parts = path.split("\\.");
        JsonNode cur = root;
        for (int i = 0; i < parts.length; i++) {
            var m = java.util.regex.Pattern.compile("(\\w+)(?:\\[(\\d+)])?").matcher(parts[i]);
            if (!m.matches()) return;
            String key = m.group(1); Integer idx = m.group(2) == null ? null : Integer.parseInt(m.group(2));
            boolean last = i == parts.length - 1;
            if (last) {
                if (cur.isObject()) ((ObjectNode) cur).set(key, guess(value));
            } else {
                JsonNode next = cur.path(key);
                if (idx != null) next = next.path(idx);
                cur = next;
            }
        }
    }

    private JsonNode guess(String v) {
        if (v.matches("-?\\d+")) return new LongNode(Long.parseLong(v));
        if (v.matches("-?\\d*\\.\\d+")) return new DoubleNode(Double.parseDouble(v));
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return BooleanNode.valueOf(Boolean.parseBoolean(v));
        return new TextNode(v);
    }

    /* ------------------------------------------------- render */

    private static final int VAL_W = 42, LABEL_MAX = 22;

    private void renderFormatted(JsonNode json, String raw, boolean err) {
        System.out.println();
        boxHeader(err ? "error" : "response");
        if (json == null) {
            System.out.println("  " + BAR2 + "  " + (raw == null ? "" : raw));
            System.out.println("  " + BOXBL); return;
        }
        // Arrays of objects → table (much easier to scan than a flat wall of [i].field rows).
        if (!err && json.isArray() && json.size() > 0 && json.get(0).isObject()) {
            tableBody(json); System.out.println("  " + BOXBL); return;
        }
        System.out.println("  " + BAR2);
        var rows = new ArrayList<Object[]>();   // {label, JsonNode value, path}
        flattenDisplay(json, "", rows);
        int lw = Math.min(LABEL_MAX, rows.stream().mapToInt(r -> ((String) r[0]).length()).max().orElse(4));
        for (Object[] row : rows) {
            JsonNode nv = (JsonNode) row[1];
            String val = nv.isTextual() ? nv.asText() : nv.toString();
            String cutv = cut(val, VAL_W);
            System.out.println("  " + BAR2 + "  " + col(BLD, pad(cut((String) row[0], lw), lw)) + "   "
                + padPaint(nv, cutv, VAL_W) + "   " + DIM + row[2] + R);
        }
        System.out.println("  " + BOXBL);
    }

    /** Box header line with the request context + status, e.g. "╭─ response  POST /api/gemini  200 ✓ · 22.2s". */
    private void boxHeader(String kind) {
        String k = kind.equals("error") ? col(RED + BLD, "error") : col(BLD, "response");
        System.out.println("  " + BOXTL + " " + k + R + DIM + "   " + rMethod + " " + rPath + "   " + R
            + statusCell(rStatus) + DIM + " · " + fmtMs(rMs) + R);
    }

    private static String fmtMs(long ms) {
        return ms >= 1000 ? String.format(java.util.Locale.US, "%.1fs", ms / 1000.0) : ms + "ms";
    }

    /** Color a scalar by its JSON type: strings green, numbers yellow, booleans cyan, null dim. */
    private String paint(JsonNode n, String s) {
        if (!color || n == null) return s;
        if (n.isNumber()) return YLW + s + R;
        if (n.isBoolean()) return CYN + s + R;
        if (n.isTextual()) return GRN + s + R;
        if (n.isNull()) return DIM + s + R;
        return s;
    }

    /** Paint the text, then right-pad with plain spaces so color codes don't skew width. */
    private String padPaint(JsonNode n, String text, int w) {
        return paint(n, text) + " ".repeat(Math.max(0, w - text.length()));
    }

    /** Table body (rows prefixed with the box bar); the header line is printed by boxHeader(). */
    private void tableBody(JsonNode arr) {
        int rowsShown = Math.min(arr.size(), 15);
        var cols = new ArrayList<String>();
        collectCols(arr.get(0), "", cols);

        var keep = new ArrayList<String>();
        for (String c : cols) {
            int max = 0;
            for (int i = 0; i < rowsShown; i++) max = Math.max(max, cell(arr.get(i), c).length());
            if (max <= 45 || shortName(c).equals("title")) keep.add(c);
        }
        int dropped = cols.size() - keep.size();

        var width = new LinkedHashMap<String, Integer>();
        var use = new ArrayList<String>();
        int idxW = Math.max(1, String.valueOf(arr.size() - 1).length());
        int total = idxW + 2;
        for (String c : keep) {
            int w = shortName(c).length();
            for (int i = 0; i < rowsShown; i++) w = Math.max(w, cell(arr.get(i), c).length());
            w = Math.min(w, shortName(c).equals("title") ? 30 : 22);
            if (total + w + 3 > 118 && !use.isEmpty()) { dropped++; continue; }
            use.add(c); width.put(c, w); total += w + 3;
        }

        String pre = "  " + BAR2 + "  ";
        System.out.println("  " + BAR2 + DIM + "  " + arr.size() + " items" + R);
        System.out.println("  " + BAR2);
        var h = new StringBuilder(pre + DIM + pad("#", idxW) + R + "   ");
        for (String c : use) h.append(col(BLD, pad(shortName(c), width.get(c)))).append("   ");
        System.out.println(h.toString().stripTrailing());
        for (int i = 0; i < rowsShown; i++) {
            var r = new StringBuilder(pre + DIM + pad(String.valueOf(i), idxW) + R + "   ");
            for (String c : use) {
                JsonNode nv = cellNode(arr.get(i), c);
                r.append(padPaint(nv, cut(cell(arr.get(i), c), width.get(c)), width.get(c))).append("   ");
            }
            System.out.println(r.toString().stripTrailing());
        }
        if (arr.size() > rowsShown)
            System.out.println(pre + DIM + "… +" + (arr.size() - rowsShown) + " more" + R);
        if (dropped > 0)
            System.out.println(pre + DIM + dropped + " long column(s) hidden · [r] raw for the full JSON" + R);
    }

    private void collectCols(JsonNode obj, String prefix, List<String> out) {
        obj.fields().forEachRemaining(e -> {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonNode v = e.getValue();
            if (v.isObject()) collectCols(v, path, out);
            else if (!v.isArray()) out.add(path);          // scalars only; nested arrays stay in raw
        });
    }

    private String cell(JsonNode obj, String path) {
        JsonNode n = cellNode(obj, path);
        if (n == null || n.isNull()) return "";
        return n.isTextual() ? n.asText() : n.toString();
    }

    private JsonNode cellNode(JsonNode obj, String path) {
        JsonNode n = obj;
        for (String part : path.split("\\.")) { if (n == null) return null; n = n.get(part); }
        return n;
    }

    private static String shortName(String path) {
        int d = path.lastIndexOf('.');
        return d >= 0 ? path.substring(d + 1) : path;
    }

    private static String cut(String s, int w) {
        return s.length() <= w ? s : s.substring(0, Math.max(1, w - 1)) + "…";
    }

    /** Leaves → {leafLabel, JsonNode value, fullPath}. Objects/arrays recurse; scalars are rows. */
    private void flattenDisplay(JsonNode n, String path, List<Object[]> rows) {
        if (n.isObject()) {
            n.fields().forEachRemaining(e ->
                flattenDisplay(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), rows));
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) flattenDisplay(n.get(i), path + "[" + i + "]", rows);
        } else {
            rows.add(new Object[]{leafName(path), n, path});
        }
    }

    private void renderRaw(JsonNode json, String raw) throws Exception {
        System.out.println();
        System.out.println("  " + BOXTL + " " + col(BLD, "raw") + R + DIM + "   " + rMethod + " " + rPath + "   " + R
            + statusCell(rStatus) + DIM + " · " + fmtMs(rMs) + R);
        System.out.println("  " + BAR2);
        String pretty = json != null ? OM.writerWithDefaultPrettyPrinter().writeValueAsString(json) : raw;
        for (String line : pretty.split("\n")) System.out.println("  " + BAR2 + "  " + DIM + line + R);
        System.out.println("  " + BOXBL);
    }

    private void actionLoop(JsonNode json, String raw) throws Exception {
        boolean showRaw = false;
        while (true) {
            System.out.println("  " + DIM + (showRaw ? "[f] formatted" : "[r] raw") + "   [s] save   [↵] done" + R);
            String in = prompt("  ▸ ", null);
            if (in == null) return;
            in = in.strip().toLowerCase();
            if (in.isEmpty() || in.equals("d")) return;
            if (in.equals("r")) { renderRaw(json, raw); showRaw = true; }
            else if (in.equals("f")) { renderFormatted(json, raw, false); showRaw = false; }
            else if (in.equals("s")) {
                java.nio.file.Path f = java.nio.file.Path.of("response.json");
                java.nio.file.Files.writeString(f, json != null ? OM.writerWithDefaultPrettyPrinter().writeValueAsString(json) : raw);
                System.out.println("  " + DIM + "saved " + f + R);
            }
        }
    }

    /* ------------------------------------------------- small helpers */

    private String readBodyArg() throws Exception {
        if (bodyArg.startsWith("@")) return java.nio.file.Files.readString(java.nio.file.Path.of(bodyArg.substring(1)));
        return bodyArg;
    }

    private static String resolvePath(String path, Map<String, String> vals) {
        String out = path;
        for (var e : vals.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue());
        return out;
    }

    private String httpGet(String url) throws Exception {
        var r = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) throw new RuntimeException("GET " + url + " → " + r.statusCode());
        return r.body();
    }

    private static String leafName(String path) {
        String p = path.replaceAll("\\[\\d+]$", "");
        int dot = p.lastIndexOf('.');
        return dot >= 0 ? p.substring(dot + 1) : p;
    }

    private static String statusCell(int sc) {
        String c = sc < 300 ? GRN : sc < 500 ? YLW : RED;
        return c + sc + " " + (sc < 400 ? "✓" : "✗") + R;
    }

    /* One shared reader — a fresh BufferedReader per call would swallow buffered stdin lines. */
    private static java.io.BufferedReader IN;
    private static java.io.BufferedReader in() {
        if (IN == null) IN = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        return IN;
    }

    /* line-based prompt (works with or without a TTY, thanks to inheritIO) */
    private static String prompt(String label, String hintPath) {
        System.out.print(label); System.out.flush();
        try { return in().readLine(); }
        catch (Exception e) { return null; }
    }

    /** Prompt showing a default and a grey reference (path · type) on the right. */
    private static String promptRef(String label, String def, String hintRight) {
        String shownDef = def == null || def.isBlank() ? "" : DIM + def + R;
        String v = prompt(label + shownDef + "   " + hintRight + "\n  ▸ ", null);
        return v;
    }

    private final boolean color = System.getenv("NO_COLOR") == null;
    private String col(String code, String s) { return color ? code + s + R : s; }
    private static String pad(String s, int w) { return s.length() >= w ? s : s + " ".repeat(w - s.length()); }

    // request context for the result box header (set before rendering)
    private String rMethod = "", rPath = ""; private int rStatus; private long rMs;

    // box glyphs (colors RED/GRN/YLW/BLD/DIM/R come from Tui.*)
    private static final String BOXTL = "╭─", BOXBL = "╰─", BAR2 = "│";

    /** Braille spinner shown while an upstream call is in flight (TTY only; no-op when piped). */
    static final class Spinner {
        private static final boolean ON = System.console() != null && System.getenv("NO_COLOR") == null;
        private static final String[] FR = {"⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"};
        private final Thread t;
        private volatile boolean run = true;

        private Spinner(String label) {
            t = new Thread(() -> {
                long start = System.nanoTime();
                int i = 0;
                while (run) {
                    long s = (System.nanoTime() - start) / 1_000_000_000L;
                    System.out.print("\r  " + CYN + FR[i++ % FR.length] + R + " " + label
                        + DIM + "  " + s + "s" + R + "\033[K");
                    System.out.flush();
                    try { Thread.sleep(90); } catch (InterruptedException e) { break; }
                }
            });
            t.setDaemon(true);
        }
        static Spinner start(String label) {
            var sp = new Spinner(label);
            if (ON) sp.t.start();
            return sp;
        }
        void stop() {
            if (!run) return;
            run = false;
            t.interrupt();
            try { t.join(200); } catch (InterruptedException ignore) {}
            if (ON) { System.out.print("\r\033[K"); System.out.flush(); }
        }
    }
}
