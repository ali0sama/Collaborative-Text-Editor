package network;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import crdt.character.CharId;
import crdt.character.CharacterCRDT;
import crdt.utils.Clock;
import operations.Operation;
import serializations.OperationSerializer;
import session.CollaborationSession.UserRole;

/**
 * Client-side WebSocket for the collaborative editor.
 *
 * Responsibilities:
 * - Connect to the central WebSocket server
 * - Join a collaboration session
 * - Send operations, cursor updates
 * - Send file operations (create, list, save, rename, delete) to the server
 * - Receive all of the above and route via MessageHandler + callbacks
 * - Reconnect automatically when the connection drops
 */
public class WebSocketClient extends org.java_websocket.client.WebSocketClient {

    private final String   sessionID;
    private final int      userID;
    private final UserRole role;

    private final CharacterCRDT crdt;
    private final Clock         clock;
    private final MessageHandler messageHandler;
    private final Runnable       refreshCallback;
    private       Runnable       disconnectCallback;

    private final Map<Integer, String> activeUsers    = Collections.synchronizedMap(new HashMap<>());
    private final Map<Integer, CharId> remoteCursors  = Collections.synchronizedMap(new HashMap<>());

    // Auto-reconnect
    private final AtomicBoolean reconnecting       = new AtomicBoolean(false);
    private volatile boolean    manualClose        = false;
    private final int           reconnectDelayMillis = 3000;

    // ─── File-operation callbacks (one-shot: cleared after use) ──────────────
    private volatile Consumer<JsonObject> fileCreatedCallback;
    private volatile Consumer<JsonObject> fileListCallback;
    private volatile Runnable             fileSavedCallback;

    // ─── Persistent callbacks (set once, stay for the session) ───────────────
    private volatile Consumer<JsonObject> fileInfoCallback;
    private volatile Consumer<String>     documentStateCallback;
    private volatile Consumer<String>     fileRenamedCallback;
    private volatile Runnable             fileDeletedCallback;

    // ─── Constructor ─────────────────────────────────────────────────────────

    public WebSocketClient(
            URI serverUri, String sessionID, int userID, UserRole role,
            CharacterCRDT crdt, Clock clock, Runnable refreshCallback) {

        super(serverUri);
        this.sessionID       = sessionID;
        this.userID          = userID;
        this.role            = role;
        this.crdt            = crdt;
        this.clock           = clock;
        this.refreshCallback = refreshCallback;

        this.messageHandler = new MessageHandler(
                this, crdt, clock, activeUsers, remoteCursors, refreshCallback);
    }

    // ─── WebSocket lifecycle ─────────────────────────────────────────────────

    // Fired once immediately after the connection opens (used for auto-actions like createFile)
    private volatile Runnable onConnectedCallback;
    public void setOnConnectedCallback(Runnable cb) { this.onConnectedCallback = cb; }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("[WebSocketClient] Connected to server");
        sendJoinMessage();
        refreshUI();
        Runnable cb = onConnectedCallback;
        onConnectedCallback = null;
        if (cb != null) cb.run();
    }

    @Override
    public void onMessage(String message) {
        try {
            messageHandler.handle(message);
        } catch (Exception e) {
            System.err.println("[WebSocketClient] Failed to handle message: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[WebSocketClient] Connection closed. Code=" + code + ", reason=" + reason);
        if (!manualClose) {
            if (disconnectCallback != null) disconnectCallback.run();
            attemptReconnect();
        }
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("[WebSocketClient] Error: " + ex.getMessage());
    }

    // ─── Connect / disconnect ─────────────────────────────────────────────────

    public void setDisconnectCallback(Runnable cb) { this.disconnectCallback = cb; }

    public void connectToServer() {
        manualClose = false;
        this.connect();
    }

    public void disconnectFromServer() {
        manualClose = true;
        this.close();
    }

    // ─── Collaboration messages ───────────────────────────────────────────────

    private void sendJoinMessage() {
        JsonObject json = new JsonObject();
        json.addProperty("action",    "join");
        json.addProperty("sessionID", sessionID);
        json.addProperty("userID",    userID);
        json.addProperty("role",      role.name().toLowerCase());
        send(json.toString());
        System.out.println("[WebSocketClient] Sent join: " + json);
    }

    public void requestHistory() {
        if (!isOpen()) return;
        JsonObject json = new JsonObject();
        json.addProperty("action",    "getHistory");
        json.addProperty("sessionID", sessionID);
        json.addProperty("userID",    userID);
        send(json.toString());
    }

    public void sendOperation(Operation operation) {
        if (!isOpen())              return;
        if (role == UserRole.VIEWER) return;
        try {
            String opJson = OperationSerializer.serialize(operation);
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("action",    "operation");
            wrapper.addProperty("sessionID", sessionID);
            wrapper.addProperty("userID",    userID);
            wrapper.add("data", JsonParser.parseString(opJson));
            send(wrapper.toString());
        } catch (Exception e) {
            System.err.println("[WebSocketClient] sendOperation failed: " + e.getMessage());
        }
    }

    public void sendCursorPosition(CharId afterCharID) {
        if (!isOpen()) return;
        JsonObject data = new JsonObject();
        if (afterCharID == null) {
            data.addProperty("afterUserID", -1);
            data.addProperty("afterClock",  -1);
        } else {
            data.addProperty("afterUserID", afterCharID.userID);
            data.addProperty("afterClock",  afterCharID.counter);
        }
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("action",    "cursor");
        wrapper.addProperty("sessionID", sessionID);
        wrapper.addProperty("userID",    userID);
        wrapper.add("data", data);
        send(wrapper.toString());
    }

    // ─── File operation messages ──────────────────────────────────────────────

    public void sendCreateFile(String name, Consumer<JsonObject> callback) {
        if (!isOpen()) return;
        this.fileCreatedCallback = callback;
        JsonObject msg = new JsonObject();
        msg.addProperty("action", "createFile");
        msg.addProperty("userID", userID);
        msg.addProperty("name",   name);
        send(msg.toString());
    }

    public void sendListFiles(Consumer<JsonObject> callback) {
        if (!isOpen()) return;
        this.fileListCallback = callback;
        JsonObject msg = new JsonObject();
        msg.addProperty("action", "listFiles");
        msg.addProperty("userID", userID);
        send(msg.toString());
    }

    public void sendSaveFile(String crdtJson, Runnable callback) {
        if (!isOpen()) return;
        this.fileSavedCallback = callback;
        JsonObject msg = new JsonObject();
        msg.addProperty("action",    "saveFile");
        msg.addProperty("sessionID", sessionID);
        msg.addProperty("crdtJson",  crdtJson);
        send(msg.toString());
    }

    public void sendRenameFile(String newName) {
        if (!isOpen()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("action",    "renameFile");
        msg.addProperty("sessionID", sessionID);
        msg.addProperty("newName",   newName);
        send(msg.toString());
    }

    public void sendDeleteFile() {
        if (!isOpen()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("action",    "deleteFile");
        msg.addProperty("sessionID", sessionID);
        send(msg.toString());
    }

    // ─── Callback setters (persistent) ───────────────────────────────────────

    public void setFileInfoCallback(Consumer<JsonObject> cb)    { this.fileInfoCallback      = cb; }
    public void setDocumentStateCallback(Consumer<String> cb)   { this.documentStateCallback = cb; }
    public void setFileRenamedCallback(Consumer<String> cb)     { this.fileRenamedCallback   = cb; }
    public void setFileDeletedCallback(Runnable cb)             { this.fileDeletedCallback   = cb; }

    // ─── Callback getters / clearers (used by MessageHandler) ────────────────

    public Consumer<JsonObject> getAndClearFileCreatedCallback() {
        Consumer<JsonObject> cb = fileCreatedCallback; fileCreatedCallback = null; return cb;
    }
    public Consumer<JsonObject> getAndClearFileListCallback() {
        Consumer<JsonObject> cb = fileListCallback; fileListCallback = null; return cb;
    }
    public Runnable getAndClearFileSavedCallback() {
        Runnable cb = fileSavedCallback; fileSavedCallback = null; return cb;
    }
    public Consumer<JsonObject> getFileInfoCallback()      { return fileInfoCallback;      }
    public Consumer<String>    getDocumentStateCallback() { return documentStateCallback; }
    public Consumer<String>    getFileRenamedCallback()   { return fileRenamedCallback;   }
    public Runnable            getFileDeletedCallback()   { return fileDeletedCallback;   }

    // ─── Auto-reconnect ───────────────────────────────────────────────────────

    private void attemptReconnect() {
        if (!reconnecting.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                while (!manualClose && !isOpen()) {
                    System.out.println("[WebSocketClient] Reconnecting in " + reconnectDelayMillis + " ms...");
                    Thread.sleep(reconnectDelayMillis);
                    try {
                        this.reconnectBlocking();
                        if (isOpen()) { System.out.println("[WebSocketClient] Reconnected"); break; }
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

    // ─── Misc ─────────────────────────────────────────────────────────────────

    public void refreshUI() {
        if (refreshCallback != null) refreshCallback.run();
    }

    public String getServerUrl()                          { return getURI().toString(); }
    public String getDocumentText()                       { return crdt.getDocument(); }
    public int    getUserID()                             { return userID; }
    public String getSessionID()                          { return sessionID; }
    public CharacterCRDT getCRDT()                        { return crdt; }
    public Clock         getClock()                       { return clock; }

    public Map<Integer, String> getActiveUsersSnapshot() {
        synchronized (activeUsers) { return new HashMap<>(activeUsers); }
    }
    public Map<Integer, CharId> getRemoteCursorsSnapshot() {
        synchronized (remoteCursors) { return new HashMap<>(remoteCursors); }
    }
}
