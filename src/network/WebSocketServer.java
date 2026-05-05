package network;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import database.DatabaseManager;
import filemanagement.ShareCodeManager;
import session.CollaborationSession;
import session.CollaborationSession.UserRole;
import session.UserPresence;

/**
 * Central WebSocket hub for real-time collaboration.
 *
 * Connects to MongoDB on startup (via DatabaseManager) so that:
 * - File creation, listing, saving, renaming and deletion are all persisted
 *   server-side — clients need no local database.
 * - Joining clients receive the saved CRDT state from MongoDB when no
 *   in-memory session history exists (e.g. after a server restart).
 *
 * Message protocol — client → server:
 *   join, operation, cursor, getHistory,
 *   createFile, listFiles, saveFile, renameFile, deleteFile
 *
 * Message protocol — server → client:
 *   history, operation, cursor, userList,
 *   fileCreated, fileList, fileSaved, documentState, fileRenamed, fileDeleted, error
 */
public class WebSocketServer extends org.java_websocket.server.WebSocketServer {

    private final Map<String, CollaborationSession> sessions    = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<WebSocket, UserConnectionInfo> connectionMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long SESSION_GRACE_PERIOD_SECONDS = 300;
    private final ScheduledExecutorService sessionCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-cleaner");
        t.setDaemon(true);
        return t;
    });

    private final DatabaseManager db;

    private static final String ACTION_JOIN        = "join";
    private static final String ACTION_OPERATION   = "operation";
    private static final String ACTION_CURSOR      = "cursor";
    private static final String ACTION_GET_HISTORY = "getHistory";
    private static final String ACTION_CREATE_FILE = "createFile";
    private static final String ACTION_LIST_FILES  = "listFiles";
    private static final String ACTION_SAVE_FILE   = "saveFile";
    private static final String ACTION_RENAME_FILE = "renameFile";
    private static final String ACTION_DELETE_FILE = "deleteFile";

    // ─── Inner class ─────────────────────────────────────────────────────────

    private static class UserConnectionInfo {
        final String   sessionID;
        final String   docId;      // MongoDB document ID (null if not found)
        final int      userID;
        final UserRole role;

        UserConnectionInfo(String sessionID, String docId, int userID, UserRole role) {
            this.sessionID = sessionID;
            this.docId     = docId;
            this.userID    = userID;
            this.role      = role;
        }
    }

    // ─── Constructor ─────────────────────────────────────────────────────────

    /**
     * @param port Port to listen on (default: 8081 per ServerLauncher)
     * @param db   Connected DatabaseManager for MongoDB persistence (may be null for no persistence)
     */
    public WebSocketServer(int port, DatabaseManager db) {
        super(new InetSocketAddress("0.0.0.0", port));
        this.db = db;
        System.out.println("[WebSocketServer] Initialized on port " + port);
    }

    // ─── WebSocket lifecycle ─────────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[WebSocketServer] New connection: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json   = JsonParser.parseString(message).getAsJsonObject();
            String     action = json.get("action").getAsString();

            switch (action) {
                case ACTION_JOIN:        handleJoin(conn, json);        break;
                case ACTION_OPERATION:   handleOperation(conn, json);   break;
                case ACTION_CURSOR:      handleCursorUpdate(conn, json); break;
                case ACTION_GET_HISTORY: handleGetHistory(conn, json);  break;
                case ACTION_CREATE_FILE: handleCreateFile(conn, json);  break;
                case ACTION_LIST_FILES:  handleListFiles(conn);         break;
                case ACTION_SAVE_FILE:   handleSaveFile(conn, json);    break;
                case ACTION_RENAME_FILE: handleRenameFile(conn, json);  break;
                case ACTION_DELETE_FILE: handleDeleteFile(conn);        break;
                default:
                    System.err.println("[WebSocketServer] Unknown action: " + action);
            }
        } catch (Exception e) {
            System.err.println("[WebSocketServer] Error processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        UserConnectionInfo info = connectionMap.remove(conn);
        if (info == null) return;

        CollaborationSession session = sessions.get(info.sessionID);
        if (session == null) return;

        session.removeUser(info.userID);
        System.out.printf("[WebSocketServer] User %d left session %s%n", info.userID, info.sessionID);

        if (!session.isEmpty()) {
            broadcastUserList(session, info.sessionID);
        } else {
            final String sid = info.sessionID;
            sessionCleaner.schedule(() -> {
                CollaborationSession s = sessions.get(sid);
                if (s != null && s.isEmpty()) {
                    sessions.remove(sid);
                    System.out.println("[WebSocketServer] Session " + sid + " removed after grace period");
                }
            }, SESSION_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS);
            System.out.println("[WebSocketServer] Session " + info.sessionID
                    + " empty — will clean up in " + SESSION_GRACE_PERIOD_SECONDS + "s");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WebSocketServer] Error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[WebSocketServer] Server started successfully");
    }

    // ─── Collaboration handlers ───────────────────────────────────────────────

    private void handleJoin(WebSocket conn, JsonObject json) {
        try {
            String   sessionID = json.get("sessionID").getAsString();
            int      userID    = json.get("userID").getAsInt();
            UserRole role      = UserRole.valueOf(json.get("role").getAsString().toUpperCase());

            CollaborationSession session = sessions.computeIfAbsent(
                    sessionID, k -> new CollaborationSession(sessionID));
            session.addUser(userID, role);

            // Resolve the MongoDB docId from the session ID
            String docId = null;
            if (db != null) {
                try {
                    String[] doc = db.findBySessionId(sessionID);
                    if (doc != null) docId = doc[0];
                } catch (Exception ignored) {}
            }

            connectionMap.put(conn, new UserConnectionInfo(sessionID, docId, userID, role));
            System.out.printf("[WebSocketServer] User %d joined session %s as %s%n",
                    userID, sessionID, role);

            // Send file metadata so the joining client knows the document id/name/codes
            if (db != null) {
                try {
                    String[] doc = db.findBySessionId(sessionID);
                    if (doc != null) {
                        JsonObject meta = new JsonObject();
                        meta.addProperty("action", "fileInfo");
                        meta.addProperty("docId", doc[0]);
                        meta.addProperty("name",  doc[1]);
                        // Viewers must not see the share codes (spec requirement)
                        if (role == UserRole.EDITOR) {
                            meta.addProperty("editorCode", doc[2]);
                            meta.addProperty("viewerCode", doc[3]);
                        }
                        conn.send(meta.toString());
                    }
                } catch (Exception ignored) {}
            }

            // Send document state: prefer in-memory history, fall back to MongoDB snapshot
            List<String> history = session.getOperationHistory();
            if (!history.isEmpty()) {
                sendHistory(conn, history);
            } else if (db != null) {
                try {
                    String crdtJson = db.loadCrdtBySessionId(sessionID);
                    if (crdtJson != null) {
                        JsonObject msg = new JsonObject();
                        msg.addProperty("action", "documentState");
                        msg.addProperty("crdtJson", crdtJson);
                        conn.send(msg.toString());
                    } else {
                        sendHistory(conn, Collections.emptyList());
                    }
                } catch (Exception e) {
                    sendHistory(conn, Collections.emptyList());
                }
            } else {
                sendHistory(conn, Collections.emptyList());
            }

            broadcastUserList(session, sessionID);

        } catch (Exception e) {
            System.err.println("[WebSocketServer] handleJoin error: " + e.getMessage());
        }
    }

    private void handleOperation(WebSocket conn, JsonObject json) {
        try {
            UserConnectionInfo info = connectionMap.get(conn);
            if (info == null) return;

            if (info.role == UserRole.VIEWER) {
                System.err.printf("[WebSocketServer] Viewer %d attempted edit in session %s%n",
                        info.userID, info.sessionID);
                return;
            }

            CollaborationSession session = sessions.get(info.sessionID);
            if (session == null) return;

            String operationJson = json.get("data").toString();
            session.addOperation(operationJson);
            broadcastToSession(info.sessionID, json.toString(), conn);

        } catch (Exception e) {
            System.err.println("[WebSocketServer] handleOperation error: " + e.getMessage());
        }
    }

    private void handleCursorUpdate(WebSocket conn, JsonObject json) {
        UserConnectionInfo info = connectionMap.get(conn);
        if (info != null) broadcastToSession(info.sessionID, json.toString(), conn);
    }

    private void handleGetHistory(WebSocket conn, JsonObject json) {
        try {
            String               sessionID = json.get("sessionID").getAsString();
            CollaborationSession session   = sessions.get(sessionID);
            if (session == null) return;
            sendHistory(conn, session.getOperationHistory());
        } catch (Exception e) {
            System.err.println("[WebSocketServer] handleGetHistory error: " + e.getMessage());
        }
    }

    // ─── File operation handlers ──────────────────────────────────────────────

    private void handleCreateFile(WebSocket conn, JsonObject json) {
        try {
            String   name    = json.get("name").getAsString().trim();
            String   docId   = java.util.UUID.randomUUID().toString();
            String[] codes   = ShareCodeManager.generateLinkedCodes();
            String   eCode   = codes[0];
            String   vCode   = codes[1];
            String   sessionId = ShareCodeManager.extractSessionId(eCode);

            if (db != null) db.saveDocument(docId, name, eCode, vCode);

            JsonObject resp = new JsonObject();
            resp.addProperty("action",      "fileCreated");
            resp.addProperty("docId",       docId);
            resp.addProperty("sessionId",   sessionId);
            resp.addProperty("editorCode",  eCode);
            resp.addProperty("viewerCode",  vCode);
            resp.addProperty("name",        name);
            conn.send(resp.toString());

        } catch (Exception e) {
            sendError(conn, "createFile failed: " + e.getMessage());
        }
    }

    private void handleListFiles(WebSocket conn) {
        try {
            List<String[]> files = (db != null) ? db.loadAllDocuments() : Collections.emptyList();

            JsonArray arr = new JsonArray();
            for (String[] f : files) {
                JsonObject o = new JsonObject();
                o.addProperty("id",         f[0]);
                o.addProperty("name",       f[1]);
                o.addProperty("editorCode", f[2]);
                o.addProperty("viewerCode", f[3]);
                arr.add(o);
            }

            JsonObject resp = new JsonObject();
            resp.addProperty("action", "fileList");
            resp.add("files", arr);
            conn.send(resp.toString());

        } catch (Exception e) {
            sendError(conn, "listFiles failed: " + e.getMessage());
        }
    }

    private void handleSaveFile(WebSocket conn, JsonObject json) {
        try {
            UserConnectionInfo info = connectionMap.get(conn);
            if (info == null) return;

            if (info.role == UserRole.VIEWER) return;

            String crdtJson = json.get("crdtJson").getAsString();
            if (db != null && info.docId != null) {
                db.saveCRDTState(info.docId, crdtJson);
            }

            JsonObject resp = new JsonObject();
            resp.addProperty("action", "fileSaved");
            conn.send(resp.toString());

        } catch (Exception e) {
            sendError(conn, "saveFile failed: " + e.getMessage());
        }
    }

    private void handleRenameFile(WebSocket conn, JsonObject json) {
        try {
            UserConnectionInfo info = connectionMap.get(conn);
            if (info == null || info.role == UserRole.VIEWER) return;

            String newName = json.get("newName").getAsString().trim();
            if (db != null && info.docId != null) db.renameDocument(info.docId, newName);

            JsonObject notify = new JsonObject();
            notify.addProperty("action",  "fileRenamed");
            notify.addProperty("newName", newName);
            broadcastToSession(info.sessionID, notify.toString(), null);

        } catch (Exception e) {
            sendError(conn, "renameFile failed: " + e.getMessage());
        }
    }

    private void handleDeleteFile(WebSocket conn) {
        try {
            UserConnectionInfo info = connectionMap.get(conn);
            if (info == null || info.role == UserRole.VIEWER) return;

            if (db != null && info.docId != null) db.deleteDocument(info.docId);
            sessions.remove(info.sessionID);

            JsonObject notify = new JsonObject();
            notify.addProperty("action", "fileDeleted");
            broadcastToSession(info.sessionID, notify.toString(), null);

        } catch (Exception e) {
            sendError(conn, "deleteFile failed: " + e.getMessage());
        }
    }

    // ─── Broadcast helpers ────────────────────────────────────────────────────

    private void broadcastToSession(String sessionID, String message, WebSocket exclude) {
        for (WebSocket client : getConnections()) {
            if (client.equals(exclude)) continue;
            UserConnectionInfo info = connectionMap.get(client);
            if (info != null && info.sessionID.equals(sessionID) && client.isOpen()) {
                client.send(message);
            }
        }
    }

    private void broadcastUserList(CollaborationSession session, String sessionID) {
        StringBuilder sb = new StringBuilder();
        for (UserPresence u : session.getActiveUsers())
            sb.append(u.getUserID()).append(":").append(u.getRole()).append("|");

        JsonObject msg = new JsonObject();
        msg.addProperty("action", "userList");
        msg.addProperty("users",  sb.toString());
        msg.addProperty("count",  session.getUserCount());
        String msgStr = msg.toString();

        for (WebSocket client : getConnections()) {
            UserConnectionInfo info = connectionMap.get(client);
            if (info != null && info.sessionID.equals(sessionID) && client.isOpen())
                client.send(msgStr);
        }
    }

    private void sendHistory(WebSocket conn, List<String> history) {
        StringBuilder sb = new StringBuilder();
        for (String op : history) sb.append(op).append("|");

        JsonObject msg = new JsonObject();
        msg.addProperty("action",     "history");
        msg.addProperty("count",      history.size());
        msg.addProperty("operations", sb.toString());
        conn.send(msg.toString());
    }

    private void sendError(WebSocket conn, String message) {
        JsonObject msg = new JsonObject();
        msg.addProperty("action",  "error");
        msg.addProperty("message", message);
        if (conn.isOpen()) conn.send(msg.toString());
        System.err.println("[WebSocketServer] " + message);
    }

    // ─── Accessors (for testing) ──────────────────────────────────────────────

    public int getActiveSessionCount()         { return sessions.size(); }
    public Set<String> getActiveSessionIDs()   { return new HashSet<>(sessions.keySet()); }
    public CollaborationSession getSession(String id) { return sessions.get(id); }
}
