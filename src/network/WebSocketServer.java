package network;

import java.net.InetSocketAddress;
import java.util.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import session.CollaborationSession;
import session.CollaborationSession.UserRole;
import session.UserPresence;

/**
 * CollaborativeWebSocketServer is the central hub for real-time collaboration.
 * 
 * Responsibilities:
 * - Accept incoming WebSocket connections from clients
 * - Manage multiple collaboration sessions (one per document)
 * - Validate user permissions (reject edits from viewers)
 * - Broadcast operations to all clients in a session
 * - Handle client disconnects and cleanup
 * 
 * Protocol:
 * Client sends messages with format:
 * {
 * "action": "join|operation|cursor",
 * "sessionID": "docID",
 * "userID": 1,
 * "role": "editor|viewer",
 * "data": {...}
 * }
 */
public class WebSocketServer extends org.java_websocket.server.WebSocketServer {

    // Map of sessionID -> CollaborationSession (ConcurrentHashMap for thread-safe concurrent joins)
    private final Map<String, CollaborationSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    // Map of WebSocket -> (sessionID, userID) for tracking connections
    private final Map<WebSocket, UserConnectionInfo> connectionMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String ACTION_JOIN = "join";
    private static final String ACTION_OPERATION = "operation";
    private static final String ACTION_CURSOR = "cursor";
    private static final String ACTION_GET_HISTORY = "getHistory";
    private static final String ACTION_USER_LIST = "userList";

    /**
     * Inner class to track which session and user a WebSocket belongs to
     */
    private static class UserConnectionInfo {
        String sessionID;
        int userID;
        UserRole role;

        UserConnectionInfo(String sessionID, int userID, UserRole role) {
            this.sessionID = sessionID;
            this.userID = userID;
            this.role = role;
        }
    }

    /**
     * Constructor
     * 
     * @param port Port to listen on (default: 8080)
     */
    public WebSocketServer(int port) {
        // Bind to all interfaces so LAN clients can connect
        super(new InetSocketAddress("0.0.0.0", port));
        System.out.println("[WebSocketServer] Initialized on port " + port);
    }

    /**
     * Called when a client connects
     */
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[WebSocketServer] New connection from " + conn.getRemoteSocketAddress());
    }

    /**
     * Called when a message is received from a client
     */
    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String action = json.get("action").getAsString();

            switch (action) {
                case ACTION_JOIN:
                    handleJoin(conn, json);
                    break;

                case ACTION_OPERATION:
                    handleOperation(conn, json);
                    break;

                case ACTION_CURSOR:
                    handleCursorUpdate(conn, json);
                    break;

                case ACTION_GET_HISTORY:
                    handleGetHistory(conn, json);
                    break;

                default:
                    System.err.println("[WebSocketServer] Unknown action: " + action);
            }
        } catch (Exception e) {
            System.err.println("[WebSocketServer] Error processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle JOIN action: User joins a collaboration session
     */
    private void handleJoin(WebSocket conn, JsonObject json) {
        try {
            String sessionID = json.get("sessionID").getAsString();
            int userID = json.get("userID").getAsInt();
            String roleStr = json.get("role").getAsString();
            UserRole role = UserRole.valueOf(roleStr.toUpperCase());

            // Get or create the session
            CollaborationSession session = sessions.computeIfAbsent(sessionID,
                    k -> new CollaborationSession(sessionID));

            // Add user to session
            session.addUser(userID, role);
            connectionMap.put(conn, new UserConnectionInfo(sessionID, userID, role));

            System.out.println(String.format("[WebSocketServer] User %d joined session %s as %s",
                    userID, sessionID, role));

            // Send operation history to the new user
            List<String> history = session.getOperationHistory();
            JsonObject historyMsg = new JsonObject();
            historyMsg.addProperty("action", "history");
            historyMsg.addProperty("count", history.size());
            StringBuilder historyData = new StringBuilder();
            for (String op : history) {
                historyData.append(op).append("|");
            }
            historyMsg.addProperty("operations", historyData.toString());
            conn.send(historyMsg.toString());

            // Broadcast user list to all clients in this session
            broadcastUserList(session, sessionID);

        } catch (Exception e) {
            System.err.println("[WebSocketServer] Error in handleJoin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle OPERATION action: User sends an edit operation
     */
    private void handleOperation(WebSocket conn, JsonObject json) {
        try {
            UserConnectionInfo info = connectionMap.get(conn);
            if (info == null) {
                System.err.println("[WebSocketServer] Operation from unknown connection");
                return;
            }

            String sessionID = info.sessionID;
            int userID = info.userID;

            // Check permission: only editors can send operations
            if (info.role == UserRole.VIEWER) {
                System.err.println(String.format("[WebSocketServer] Viewer %d attempted edit in session %s",
                        userID, sessionID));
                return;
            }

            // Get the session
            CollaborationSession session = sessions.get(sessionID);
            if (session == null) {
                System.err.println("[WebSocketServer] Session not found: " + sessionID);
                return;
            }

            // Store the operation in history
            String operationJson = json.get("data").toString();
            session.addOperation(operationJson);

            System.out.println(String.format("[WebSocketServer] Broadcast operation from user %d in session %s",
                    userID, sessionID));

            // Broadcast to all OTHER clients in the same session
            broadcastToSession(sessionID, json.toString(), conn);

        } catch (Exception e) {
            System.err.println("[WebSocketServer] Error in handleOperation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle CURSOR action: User sends cursor position update
     */
    private void handleCursorUpdate(WebSocket conn, JsonObject json) {
        try {
            UserConnectionInfo info = connectionMap.get(conn);
            if (info == null)
                return;

            String sessionID = info.sessionID;

            // Broadcast cursor update to all OTHER clients in the session
            broadcastToSession(sessionID, json.toString(), conn);

        } catch (Exception e) {
            System.err.println("[WebSocketServer] Error in handleCursorUpdate: " + e.getMessage());
        }
    }

    /**
     * Handle GET_HISTORY action: Client requests operation history
     */
    private void handleGetHistory(WebSocket conn, JsonObject json) {
        try {
            String sessionID = json.get("sessionID").getAsString();
            CollaborationSession session = sessions.get(sessionID);

            if (session == null) {
                System.err.println("[WebSocketServer] Session not found for history: " + sessionID);
                return;
            }

            JsonObject response = new JsonObject();
            response.addProperty("action", "history");
            List<String> history = session.getOperationHistory();
            response.addProperty("count", history.size());

            StringBuilder historyData = new StringBuilder();
            for (String op : history) {
                historyData.append(op).append("|");
            }
            response.addProperty("operations", historyData.toString());
            conn.send(response.toString());

        } catch (Exception e) {
            System.err.println("[WebSocketServer] Error in handleGetHistory: " + e.getMessage());
        }
    }

    /**
     * Broadcast a message to all clients in a specific session
     */
    private void broadcastToSession(String sessionID, String message, WebSocket excludeConn) {
        CollaborationSession session = sessions.get(sessionID);
        if (session == null)
            return;

        for (WebSocket client : getConnections()) {
            if (client.equals(excludeConn))
                continue; // Don't send back to sender

            UserConnectionInfo info = connectionMap.get(client);
            if (info != null && info.sessionID.equals(sessionID)) {
                if (client.isOpen()) {
                    client.send(message);
                }
            }
        }
    }

    /**
     * Broadcast user list to all clients in a session
     */
    private void broadcastUserList(CollaborationSession session, String sessionID) {
        JsonObject userListMsg = new JsonObject();
        userListMsg.addProperty("action", "userList");

        StringBuilder userList = new StringBuilder();
        for (UserPresence user : session.getActiveUsers()) {
            userList.append(user.getUserID()).append(":").append(user.getRole()).append("|");
        }
        userListMsg.addProperty("users", userList.toString());
        userListMsg.addProperty("count", session.getUserCount());

        String msgStr = userListMsg.toString();

        for (WebSocket client : getConnections()) {
            UserConnectionInfo info = connectionMap.get(client);
            if (info != null && info.sessionID.equals(sessionID)) {
                if (client.isOpen()) {
                    client.send(msgStr);
                }
            }
        }
    }

    /**
     * Called when a client disconnects
     */
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        UserConnectionInfo info = connectionMap.remove(conn);
        if (info != null) {
            CollaborationSession session = sessions.get(info.sessionID);
            if (session != null) {
                session.removeUser(info.userID);
                System.out.println(String.format("[WebSocketServer] User %d left session %s",
                        info.userID, info.sessionID));

                // Broadcast updated user list
                if (!session.isEmpty()) {
                    broadcastUserList(session, info.sessionID);
                } else {
                    // Clean up empty sessions
                    sessions.remove(info.sessionID);
                    System.out.println("[WebSocketServer] Session " + info.sessionID + " removed (empty)");
                }
            }
        }
    }

    /**
     * Called on error
     */
    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WebSocketServer] Error: " + ex.getMessage());
        ex.printStackTrace();
    }

    /**
     * Called when the server starts
     */
    @Override
    public void onStart() {
        System.out.println("[WebSocketServer] Server started successfully");
    }

    /**
     * Get number of active sessions
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * Get active session IDs
     */
    public Set<String> getActiveSessionIDs() {
        return new HashSet<>(sessions.keySet());
    }

    /**
     * Get a specific session (for testing/debugging)
     */
    public CollaborationSession getSession(String sessionID) {
        return sessions.get(sessionID);
    }

    /**
     * Main method to start the server
     */
    public static void main(String[] args) {
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        WebSocketServer server = new WebSocketServer(port);
        server.start();
        System.out.println("[WebSocketServer] Server running on port " + port);
    }
}
