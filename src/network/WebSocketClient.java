package network;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonObject;

import crdt.character.CharId;
import crdt.character.CharacterCRDT;
import crdt.utils.Clock;
import session.CollaborationSession.UserRole;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client-side WebSocket for the collaborative editor.
 *
 * Responsibilities:
 * - Connect to the central WebSocket server
 * - Join a collaboration session
 * - Send operations
 * - Receive operations/history/user list/cursor updates
 * - Reconnect automatically when the connection drops
 *
 * This class is written to work even before Member 4 finishes the UI.
 * UI refresh is handled through a Runnable callback.
 */
public class WebSocketClient extends org.java_websocket.client.WebSocketClient {

    private final String sessionID;
    private final int userID;
    private final UserRole role;

    private final CharacterCRDT crdt;
    private final Clock clock;

    private final MessageHandler messageHandler;

    // Optional callback to refresh UI after remote updates
    private final Runnable refreshCallback;

    // Called when connection drops unexpectedly (not on manual close)
    private Runnable disconnectCallback;

    // Stores latest active users from "userList" messages
    private final Map<Integer, String> activeUsers = Collections.synchronizedMap(new HashMap<>());

    // Stores latest remote cursor positions
    private final Map<Integer, CharId> remoteCursors = Collections.synchronizedMap(new HashMap<>());

    // Auto-reconnect control
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile boolean manualClose = false;
    private final int reconnectDelayMillis = 3000;

    /**
     * Constructor
     *
     * @param serverUri       e.g. ws://localhost:8080
     * @param sessionID       document/session ID
     * @param userID          local user ID
     * @param role            EDITOR or VIEWER
     * @param crdt            local CharacterCRDT
     * @param clock           local logical clock
     * @param refreshCallback optional UI refresh callback (can be null)
     */
    public WebSocketClient(
            URI serverUri,
            String sessionID,
            int userID,
            UserRole role,
            CharacterCRDT crdt,
            Clock clock,
            Runnable refreshCallback
    ) {
        super(serverUri);
        this.sessionID = sessionID;
        this.userID = userID;
        this.role = role;
        this.crdt = crdt;
        this.clock = clock;
        this.refreshCallback = refreshCallback;

        this.messageHandler = new MessageHandler(
                this,
                crdt,
                clock,
                activeUsers,
                remoteCursors,
                refreshCallback
        );
    }

    /**
     * Called when connection is opened.
     * Sends a JOIN message immediately.
     */
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("[WebSocketClient] Connected to server");
        sendJoinMessage();
        refreshUI();
    }

    /**
     * Called when a message is received from the server.
     */
    @Override
    public void onMessage(String message) {
        try {
            messageHandler.handle(message);
        } catch (Exception e) {
            System.err.println("[WebSocketClient] Failed to handle message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Called when the connection closes.
     */
    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[WebSocketClient] Connection closed. Code=" + code + ", reason=" + reason);

        if (!manualClose) {
            if (disconnectCallback != null) disconnectCallback.run();
            attemptReconnect();
        }
    }

    /**
     * Called on WebSocket error.
     */
    @Override
    public void onError(Exception ex) {
        System.err.println("[WebSocketClient] Error: " + ex.getMessage());
    }

    /**
     * Connect safely.
     */
    public void setDisconnectCallback(Runnable cb) { this.disconnectCallback = cb; }

    public void connectToServer() {
        manualClose = false;
        this.connect();
    }

    /**
     * Close safely without triggering auto-reconnect.
     */
    public void disconnectFromServer() {
        manualClose = true;
        this.close();
    }

    /**
     * Sends the JOIN message required by Member 2's server.
     *
     * Format:
     * {
     *   "action": "join",
     *   "sessionID": "...",
     *   "userID": 1,
     *   "role": "editor"
     * }
     */
    private void sendJoinMessage() {
        JsonObject json = new JsonObject();
        json.addProperty("action", "join");
        json.addProperty("sessionID", sessionID);
        json.addProperty("userID", userID);
        json.addProperty("role", role.name().toLowerCase());

        send(json.toString());
        System.out.println("[WebSocketClient] Sent join message: " + json);
    }

    /**
     * Request full history explicitly if needed.
     * Server already sends history after join, but this is still useful.
     */
    public void requestHistory() {
        if (!isOpen()) {
            System.err.println("[WebSocketClient] Cannot request history: connection is not open");
            return;
        }

        JsonObject json = new JsonObject();
        json.addProperty("action", "getHistory");
        json.addProperty("sessionID", sessionID);
        json.addProperty("userID", userID);

        send(json.toString());
        System.out.println("[WebSocketClient] Requested history");
    }

    /**
     * Send an operation to the server.
     *
     * Message format:
     * {
     *   "action": "operation",
     *   "sessionID": "doc1",
     *   "userID": 1,
     *   "data": { ...operation json... }
     * }
     */
    public void sendOperation(operations.Operation operation) {
        if (!isOpen()) {
            System.err.println("[WebSocketClient] Cannot send operation: connection is not open");
            return;
        }

        if (role == UserRole.VIEWER) {
            System.err.println("[WebSocketClient] Viewer cannot send edit operations");
            return;
        }

        try {
            String operationJson = serializations.OperationSerializer.serialize(operation);

            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("action", "operation");
            wrapper.addProperty("sessionID", sessionID);
            wrapper.addProperty("userID", userID);
            wrapper.add("data", com.google.gson.JsonParser.parseString(operationJson));

            send(wrapper.toString());
        } catch (Exception e) {
            System.err.println("[WebSocketClient] Failed to send operation: " + e.getMessage());
        }
    }

    /**
     * Send a cursor update.
     *
     * data:
     * {
     *   "afterUserID": ...,
     *   "afterClock": ...
     * }
     *
     * If cursor is at document start, both values are -1.
     */
    public void sendCursorPosition(CharId afterCharID) {
        if (!isOpen()) {
            System.err.println("[WebSocketClient] Cannot send cursor update: connection is not open");
            return;
        }

        JsonObject data = new JsonObject();

        if (afterCharID == null) {
            data.addProperty("afterUserID", -1);
            data.addProperty("afterClock", -1);
        } else {
            data.addProperty("afterUserID", afterCharID.userID);
            data.addProperty("afterClock", afterCharID.counter);
        }

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("action", "cursor");
        wrapper.addProperty("sessionID", sessionID);
        wrapper.addProperty("userID", userID);
        wrapper.add("data", data);

        send(wrapper.toString());
    }

    /**
     * Try to reconnect automatically after a connection drop.
     */
    private void attemptReconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }

        new Thread(() -> {
            try {
                while (!manualClose && !isOpen()) {
                    System.out.println("[WebSocketClient] Reconnecting in " + reconnectDelayMillis + " ms...");
                    Thread.sleep(reconnectDelayMillis);

                    try {
                        this.reconnectBlocking();
                        if (isOpen()) {
                            System.out.println("[WebSocketClient] Reconnected successfully");
                            break;
                        }
                    } catch (Exception e) {
                        System.err.println("[WebSocketClient] Reconnect failed: " + e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                reconnecting.set(false);
            }
        }, "WebSocketClient-Reconnect-Thread").start();
    }

    /**
     * Refresh UI if callback exists.
     */
    public void refreshUI() {
        if (refreshCallback != null) {
            refreshCallback.run();
        }
    }

    /**
     * Get latest visible text from CRDT.
     */
    public String getDocumentText() {
        return crdt.getDocument();
    }

    /**
     * Get immutable copy of active users.
     */
    public Map<Integer, String> getActiveUsersSnapshot() {
        synchronized (activeUsers) {
            return new HashMap<>(activeUsers);
        }
    }

    /**
     * Get immutable copy of remote cursors.
     */
    public Map<Integer, CharId> getRemoteCursorsSnapshot() {
        synchronized (remoteCursors) {
            return new HashMap<>(remoteCursors);
        }
    }

    /**
     * Local user ID getter.
     */
    public int getUserID() {
        return userID;
    }

    /**
     * Session ID getter.
     */
    public String getSessionID() {
        return sessionID;
    }

    /**
     * CRDT getter if needed externally.
     */
    public CharacterCRDT getCRDT() {
        return crdt;
    }

    /**
     * Clock getter if needed externally.
     */
    public Clock getClock() {
        return clock;
    }
}