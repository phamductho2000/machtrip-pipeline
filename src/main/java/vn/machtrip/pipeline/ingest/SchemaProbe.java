package vn.machtrip.pipeline.ingest;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

/** Collects field paths and JSON types of dataset items (never values), to review an unknown output schema. */
public class SchemaProbe {

    private final Map<String, TreeSet<String>> types = new TreeMap<>();
    private final Map<String, Integer> counts = new TreeMap<>();
    private int items;

    public void add(JsonNode item) {
        items++;
        walk("", item);
    }

    private void walk(String path, JsonNode n) {
        if (!path.isEmpty()) {
            types.computeIfAbsent(path, k -> new TreeSet<>()).add(typeOf(n));
            counts.merge(path, 1, Integer::sum);
        }
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> walk(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), e.getValue()));
        } else if (n.isArray()) {
            n.forEach(child -> walk(path + "[]", child));
        }
    }

    private static String typeOf(JsonNode n) {
        return n.isObject() ? "object" : n.isArray() ? "array" : n.isTextual() ? "string" : n.isNumber() ? "number"
                : n.isBoolean() ? "boolean" : "null";
    }

    public String render() {
        StringBuilder sb = new StringBuilder("items: ").append(items).append('\n');
        types.forEach((path, t) -> sb.append(path).append(" : ").append(String.join("|", t))
                .append(" (in ").append(counts.get(path)).append(")\n"));
        return sb.toString();
    }
}
