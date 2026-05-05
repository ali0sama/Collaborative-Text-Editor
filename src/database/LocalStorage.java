package database;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves a local copy of each document as a JSON file in the "local_docs/"
 * directory next to the running application. Each file is named {docId}.json
 * and contains the document metadata plus the serialized CRDT.
 *
 * This is independent of the server's MongoDB — it is a client-side backup
 * that survives server outages.
 */
public class LocalStorage {

    private static final String DIR = "local_docs";

    /** Saves (or overwrites) the local copy for this document. */
    public static void save(String docId, String name,
                            String editorCode, String viewerCode,
                            String crdtJson) throws IOException {
        Path dir = Paths.get(DIR);
        if (!Files.exists(dir)) Files.createDirectories(dir);

        JsonObject obj = new JsonObject();
        obj.addProperty("docId",      docId);
        obj.addProperty("name",       name);
        obj.addProperty("editorCode", editorCode != null ? editorCode : "");
        obj.addProperty("viewerCode", viewerCode != null ? viewerCode : "");
        obj.addProperty("crdtJson",   crdtJson);

        Files.write(
            Paths.get(DIR, docId + ".json"),
            obj.toString().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    /** Returns the serialized CRDT JSON for a document, or null if not found. */
    public static String loadCrdt(String docId) throws IOException {
        Path file = Paths.get(DIR, docId + ".json");
        if (!Files.exists(file)) return null;
        JsonObject obj = parse(file);
        return obj.has("crdtJson") ? obj.get("crdtJson").getAsString() : null;
    }

    /**
     * Lists all locally saved documents.
     * Returns a list of [docId, name, editorCode, viewerCode] arrays.
     */
    public static List<String[]> listAll() throws IOException {
        List<String[]> result = new ArrayList<>();
        Path dir = Paths.get(DIR);
        if (!Files.exists(dir)) return result;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path path : stream) {
                try {
                    JsonObject obj = parse(path);
                    result.add(new String[]{
                        obj.has("docId")      ? obj.get("docId").getAsString()      : "",
                        obj.has("name")       ? obj.get("name").getAsString()       : "",
                        obj.has("editorCode") ? obj.get("editorCode").getAsString() : "",
                        obj.has("viewerCode") ? obj.get("viewerCode").getAsString() : ""
                    });
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    /** Deletes the local copy of a document. */
    public static void delete(String docId) {
        try { Files.deleteIfExists(Paths.get(DIR, docId + ".json")); }
        catch (IOException ignored) {}
    }

    private static JsonObject parse(Path file) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }
}
