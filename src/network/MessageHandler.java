package network;

import java.util.Map;

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
 * - operation
 * - history
 * - userList
 * - cursor
 *
 * Member 2's server sends wrapped JSON messages, not raw operation JSON.
 */
public class MessageHandler {

    private final WebSocketClient client;
    private final CharacterCRDT crdt;
    private final Clock clock;
    private final Map<Integer, String> activeUsers;
    private final Map<Integer, CharId> remoteCursors;
    private final Runnable refreshCallback;

    public MessageHandler(
            WebSocketClient client,
            CharacterCRDT crdt,
            Clock clock,
            Map<Integer, String> activeUsers,
            Map<Integer, CharId> remoteCursors,
            Runnable refreshCallback
    ) {
        this.client = client;
        this.crdt = crdt;
        this.clock = clock;
        this.activeUsers = activeUsers;
        this.remoteCursors = remoteCursors;
        this.refreshCallback = refreshCallback;
    }

    /**
     * Main entry point for any raw server message.
     */
    public void handle(String rawMessage) {
        JsonObject json = JsonParser.parseString(rawMessage).getAsJsonObject();

        if (!json.has("action")) {
            System.err.println("[MessageHandler] Message missing 'action': " + rawMessage);
            return;
        }

        String action = json.get("action").getAsString();

        switch (action) {
            case "operation":
                handleOperation(json);
                break;

            case "history":
                handleHistory(json);
                break;

            case "userList":
                handleUserList(json);
                break;

            case "cursor":
                handleCursor(json);
                break;

            default:
                System.err.println("[MessageHandler] Unknown action: " + action);
        }
    }

    /**
     * Handle a live operation broadcast from the server.
     *
     * Expected shape:
     * {
     *   "action": "operation",
     *   "sessionID": "...",
     *   "userID": 1,
     *   "data": { ...operation json... }
     * }
     */
    private void handleOperation(JsonObject json) {
        try {
            if (!json.has("data")) {
                System.err.println("[MessageHandler] Operation message missing data");
                return;
            }

            String operationJson = json.get("data").toString();

            Operation op = OperationSerializer.deserialize(operationJson);

            // Sync logical clock with remote operation
            clock.update(op.clock);

            // Apply operation to local CRDT
            op.apply(crdt);
            triggerRefresh();

        } catch (Exception e) {
            System.err.println("[MessageHandler] Failed to handle operation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle a history message.
     *
     * Member 2 server sends:
     * {
     *   "action": "history",
     *   "count": N,
     *   "operations": "{...}|{...}|"
     * }
     */
    private void handleHistory(JsonObject json) {
        try {
            if (!json.has("operations")) {
                System.err.println("[MessageHandler] History message missing operations");
                return;
            }

            String operationsData = json.get("operations").getAsString();

            if (operationsData == null || operationsData.trim().isEmpty()) {
                System.out.println("[MessageHandler] No history to apply");
                triggerRefresh();
                return;
            }

            String[] parts = operationsData.split("\\|");

            for (String opJson : parts) {
                if (opJson == null || opJson.trim().isEmpty()) {
                    continue;
                }

                Operation op = OperationSerializer.deserialize(opJson.trim());

                // Sync logical clock with each remote operation
                clock.update(op.clock);

                // Apply to CRDT
                op.apply(crdt);
            }

            System.out.println("[MessageHandler] History applied successfully");
            triggerRefresh();

        } catch (Exception e) {
            System.err.println("[MessageHandler] Failed to handle history: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle active user list message.
     *
     * Member 2 server sends:
     * {
     *   "action": "userList",
     *   "users": "1:EDITOR|2:VIEWER|",
     *   "count": 2
     * }
     */
    private void handleUserList(JsonObject json) {
        try {
            synchronized (activeUsers) {
                activeUsers.clear();

                if (!json.has("users")) {
                    triggerRefresh();
                    return;
                }

                String usersData = json.get("users").getAsString();

                if (usersData == null || usersData.trim().isEmpty()) {
                    triggerRefresh();
                    return;
                }

                String[] userEntries = usersData.split("\\|");

                for (String entry : userEntries) {
                    if (entry == null || entry.trim().isEmpty()) {
                        continue;
                    }

                    String[] parts = entry.split(":");
                    if (parts.length != 2) {
                        continue;
                    }

                    int userID = Integer.parseInt(parts[0].trim());
                    String role = parts[1].trim();

                    activeUsers.put(userID, role);
                }
            }

            triggerRefresh();

        } catch (Exception e) {
            System.err.println("[MessageHandler] Failed to handle user list: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle cursor updates.
     *
     * Expected shape:
     * {
     *   "action": "cursor",
     *   "sessionID": "...",
     *   "userID": 2,
     *   "data": {
     *      "afterUserID": 1,
     *      "afterClock": 3
     *   }
     * }
     */
    private void handleCursor(JsonObject json) {
        try {
            if (!json.has("userID") || !json.has("data")) {
                System.err.println("[MessageHandler] Cursor message missing fields");
                return;
            }

            int senderUserID = json.get("userID").getAsInt();

            // Ignore our own echoed cursor if ever received
            if (senderUserID == client.getUserID()) {
                return;
            }

            JsonObject data = json.getAsJsonObject("data");

            int afterUserID = data.get("afterUserID").getAsInt();
            int afterClock = data.get("afterClock").getAsInt();

            CharId position = null;
            if (afterUserID != -1 && afterClock != -1) {
                position = new CharId(afterClock, afterUserID);
            }

            synchronized (remoteCursors) {
                remoteCursors.put(senderUserID, position);
            }

            triggerRefresh();

        } catch (Exception e) {
            System.err.println("[MessageHandler] Failed to handle cursor update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Triggers UI refresh if available.
     */
    private void triggerRefresh() {
        if (refreshCallback != null) {
            refreshCallback.run();
        }
    }
}