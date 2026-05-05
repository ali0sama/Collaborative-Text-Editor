package network;

import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import crdt.character.CharId;
import crdt.character.CharacterCRDT;
import crdt.utils.Clock;
import operations.Operation;
import serializations.OperationSerializer;

/**
 * Handles all incoming WebSocket messages on the client side.
 *
 * Supported server messages:
 *   operation, history, userList, cursor,
 *   documentState, fileCreated, fileList, fileSaved, fileRenamed, fileDeleted, error
 */
public class MessageHandler {

    private final WebSocketClient      client;
    private final CharacterCRDT        crdt;
    private final Clock                clock;
    private final Map<Integer, String> activeUsers;
    private final Map<Integer, CharId> remoteCursors;
    private final Runnable             refreshCallback;

    public MessageHandler(
            WebSocketClient client, CharacterCRDT crdt, Clock clock,
            Map<Integer, String> activeUsers, Map<Integer, CharId> remoteCursors,
            Runnable refreshCallback) {

        this.client          = client;
        this.crdt            = crdt;
        this.clock           = clock;
        this.activeUsers     = activeUsers;
        this.remoteCursors   = remoteCursors;
        this.refreshCallback = refreshCallback;
    }

    public void handle(String rawMessage) {
        JsonObject json = JsonParser.parseString(rawMessage).getAsJsonObject();

        if (!json.has("action")) {
            System.err.println("[MessageHandler] Missing 'action': " + rawMessage);
            return;
        }

        switch (json.get("action").getAsString()) {
            case "operation":     handleOperation(json);     break;
            case "history":       handleHistory(json);       break;
            case "userList":      handleUserList(json);      break;
            case "cursor":        handleCursor(json);        break;
            case "documentState": handleDocumentState(json); break;
            case "fileInfo":      handleFileInfo(json);      break;
            case "fileCreated":   handleFileCreated(json);   break;
            case "fileList":      handleFileList(json);      break;
            case "fileSaved":     handleFileSaved();         break;
            case "fileRenamed":   handleFileRenamed(json);   break;
            case "fileDeleted":   handleFileDeleted();       break;
            case "error":
                System.err.println("[MessageHandler] Server error: "
                        + (json.has("message") ? json.get("message").getAsString() : "unknown"));
                break;
            default:
                System.err.println("[MessageHandler] Unknown action: "
                        + json.get("action").getAsString());
        }
    }

    // ─── Collaboration handlers ───────────────────────────────────────────────

    private void handleOperation(JsonObject json) {
        try {
            String    opJson = json.get("data").toString();
            Operation op     = OperationSerializer.deserialize(opJson);
            clock.update(op.clock);
            op.apply(crdt);
            triggerRefresh();
        } catch (Exception e) {
            System.err.println("[MessageHandler] handleOperation failed: " + e.getMessage());
        }
    }

    private void handleHistory(JsonObject json) {
        try {
            String data = json.has("operations") ? json.get("operations").getAsString() : "";
            if (data.trim().isEmpty()) { triggerRefresh(); return; }

            for (String opJson : data.split("\\|")) {
                if (opJson.trim().isEmpty()) continue;
                Operation op = OperationSerializer.deserialize(opJson.trim());
                clock.update(op.clock);
                op.apply(crdt);
            }
            System.out.println("[MessageHandler] History applied");
            triggerRefresh();
        } catch (Exception e) {
            System.err.println("[MessageHandler] handleHistory failed: " + e.getMessage());
        }
    }

    private void handleUserList(JsonObject json) {
        try {
            synchronized (activeUsers) {
                activeUsers.clear();
                if (!json.has("users")) { triggerRefresh(); return; }
                for (String entry : json.get("users").getAsString().split("\\|")) {
                    if (entry.trim().isEmpty()) continue;
                    String[] parts = entry.split(":");
                    if (parts.length == 2)
                        activeUsers.put(Integer.parseInt(parts[0].trim()), parts[1].trim());
                }
            }
            triggerRefresh();
        } catch (Exception e) {
            System.err.println("[MessageHandler] handleUserList failed: " + e.getMessage());
        }
    }

    private void handleCursor(JsonObject json) {
        try {
            if (!json.has("userID") || !json.has("data")) return;
            int senderID = json.get("userID").getAsInt();
            if (senderID == client.getUserID()) return;

            JsonObject data = json.getAsJsonObject("data");
            int afterUserID = data.get("afterUserID").getAsInt();
            int afterClock  = data.get("afterClock").getAsInt();
            CharId position = (afterUserID == -1 || afterClock == -1)
                    ? null : new CharId(afterClock, afterUserID);

            synchronized (remoteCursors) { remoteCursors.put(senderID, position); }
            triggerRefresh();
        } catch (Exception e) {
            System.err.println("[MessageHandler] handleCursor failed: " + e.getMessage());
        }
    }

    // ─── Document state (sent on join when no in-memory history) ─────────────

    private void handleDocumentState(JsonObject json) {
        try {
            String crdtJson = json.get("crdtJson").getAsString();
            Consumer<String> cb = client.getDocumentStateCallback();
            if (cb != null) cb.accept(crdtJson);
            triggerRefresh();
        } catch (Exception e) {
            System.err.println("[MessageHandler] handleDocumentState failed: " + e.getMessage());
        }
    }

    // ─── File operation response handlers ────────────────────────────────────

    private void handleFileInfo(JsonObject json) {
        Consumer<JsonObject> cb = client.getFileInfoCallback();
        if (cb != null) cb.accept(json);
    }

    private void handleFileCreated(JsonObject json) {
        Consumer<JsonObject> cb = client.getAndClearFileCreatedCallback();
        if (cb != null) cb.accept(json);
    }

    private void handleFileList(JsonObject json) {
        Consumer<JsonObject> cb = client.getAndClearFileListCallback();
        if (cb != null) cb.accept(json);
    }

    private void handleFileSaved() {
        Runnable cb = client.getAndClearFileSavedCallback();
        if (cb != null) cb.run();
    }

    private void handleFileRenamed(JsonObject json) {
        String newName = json.has("newName") ? json.get("newName").getAsString() : null;
        if (newName == null) return;
        Consumer<String> cb = client.getFileRenamedCallback();
        if (cb != null) cb.accept(newName);
        triggerRefresh();
    }

    private void handleFileDeleted() {
        Runnable cb = client.getFileDeletedCallback();
        if (cb != null) cb.run();
        triggerRefresh();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void triggerRefresh() {
        if (refreshCallback != null) refreshCallback.run();
    }
}
