package database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence layer backed by MongoDB.
 *
 * Database : collab_editor
 * Collection: documents
 *
 * Each document has the shape:
 * {
 *   "id"         : "<UUID>",
 *   "name"       : "My File",
 *   "editorCode" : "EXXXXXXX",
 *   "viewerCode" : "VXXXXXXX",
 *   "createdAt"  : <epoch ms>,
 *   "crdtJson"   : "<serialized BlockCRDT>"   // updated separately
 * }
 *
 * The public interface is unchanged from the SQLite version so FileRepository
 * and everything above it requires no modification.
 */
public class DatabaseManager {

    private static final String CONNECTION_STRING = "mongodb://localhost:27017";
    private static final String DATABASE_NAME     = "collab_editor";
    private static final String COLLECTION_NAME   = "documents";

    private MongoClient                mongoClient;
    private MongoCollection<Document>  collection;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    public void connect() throws SQLException {
        try {
            mongoClient = MongoClients.create(CONNECTION_STRING);
            MongoDatabase db = mongoClient.getDatabase(DATABASE_NAME);
            collection = db.getCollection(COLLECTION_NAME);
            // Unique index on "id" field for fast lookups and upsert safety
            collection.createIndex(Indexes.ascending("id"),
                    new IndexOptions().unique(true));
        } catch (Exception e) {
            throw new SQLException("MongoDB connect failed: " + e.getMessage(), e);
        }
    }

    public void disconnect() throws SQLException {
        if (mongoClient != null) {
            try { mongoClient.close(); } catch (Exception ignored) {}
        }
    }

    // ─── Documents ───────────────────────────────────────────────────────────

    /**
     * Upserts the document metadata (does not touch crdtJson).
     */
    public void saveDocument(String docId, String name,
                             String editorCode, String viewerCode) throws SQLException {
        try {
            Document doc = new Document("id", docId)
                    .append("name",       name)
                    .append("editorCode", editorCode)
                    .append("viewerCode", viewerCode)
                    .append("createdAt",  System.currentTimeMillis());

            collection.replaceOne(
                    Filters.eq("id", docId),
                    doc,
                    new ReplaceOptions().upsert(true));
        } catch (Exception e) {
            throw new SQLException("saveDocument failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns all documents as [id, name, editorCode, viewerCode] arrays.
     */
    public List<String[]> loadAllDocuments() throws SQLException {
        try {
            List<String[]> records = new ArrayList<>();
            try (MongoCursor<Document> cursor = collection.find().iterator()) {
                while (cursor.hasNext()) {
                    Document d = cursor.next();
                    records.add(new String[]{
                        d.getString("id"),
                        d.getString("name"),
                        d.getString("editorCode"),
                        d.getString("viewerCode")
                    });
                }
            }
            return records;
        } catch (Exception e) {
            throw new SQLException("loadAllDocuments failed: " + e.getMessage(), e);
        }
    }

    public void renameDocument(String docId, String newName) throws SQLException {
        try {
            collection.updateOne(
                    Filters.eq("id", docId),
                    Updates.set("name", newName));
        } catch (Exception e) {
            throw new SQLException("renameDocument failed: " + e.getMessage(), e);
        }
    }

    public void deleteDocument(String docId) throws SQLException {
        try {
            collection.deleteOne(Filters.eq("id", docId));
        } catch (Exception e) {
            throw new SQLException("deleteDocument failed: " + e.getMessage(), e);
        }
    }

    // ─── CRDT State ───────────────────────────────────────────────────────────

    /**
     * Saves (or updates) the serialized CRDT JSON for a document.
     * Uses updateOne so the document metadata written by saveDocument is preserved.
     */
    public void saveCRDTState(String docId, String crdtJson) throws SQLException {
        try {
            collection.updateOne(
                    Filters.eq("id", docId),
                    Updates.set("crdtJson", crdtJson));
        } catch (Exception e) {
            throw new SQLException("saveCRDTState failed: " + e.getMessage(), e);
        }
    }

    /**
     * Loads the serialized CRDT JSON for a document, or null if not found.
     */
    public String loadCRDTState(String docId) throws SQLException {
        try {
            Document d = collection.find(Filters.eq("id", docId)).first();
            return (d == null) ? null : d.getString("crdtJson");
        } catch (Exception e) {
            throw new SQLException("loadCRDTState failed: " + e.getMessage(), e);
        }
    }

    /**
     * Finds a document by the 7-character session ID (the shared suffix of
     * editorCode and viewerCode). Returns [id, name, editorCode, viewerCode]
     * or null if not found.
     */
    public String[] findBySessionId(String sessionId) throws SQLException {
        try {
            Document d = collection.find(Filters.or(
                Filters.eq("editorCode", "E" + sessionId),
                Filters.eq("viewerCode", "V" + sessionId)
            )).first();
            if (d == null) return null;
            return new String[]{
                d.getString("id"),
                d.getString("name"),
                d.getString("editorCode"),
                d.getString("viewerCode")
            };
        } catch (Exception e) {
            throw new SQLException("findBySessionId failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the serialized CRDT JSON for the document identified by its
     * 7-character session ID, or null if not found.
     */
    public String loadCrdtBySessionId(String sessionId) throws SQLException {
        try {
            Document d = collection.find(Filters.or(
                Filters.eq("editorCode", "E" + sessionId),
                Filters.eq("viewerCode", "V" + sessionId)
            )).first();
            return (d == null) ? null : d.getString("crdtJson");
        } catch (Exception e) {
            throw new SQLException("loadCrdtBySessionId failed: " + e.getMessage(), e);
        }
    }
}
