package serializations;

import operations.*;
import crdt.character.CharId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import operations.FormatOperation;

/**
 * Converts Operation objects to/from JSON strings for network transmission.
 * 
 * JSON Format:
 * Insert: { "op": "insert", "userID": 1, "clock": 3, "value": "A", "parentUserID": 1, "parentClock": 2 }
 * Delete:  { "op": "delete", "userID": 1, "clock": 4, "targetUserID": 1, "targetClock": 3 }
 */
public class OperationSerializer {

    /**
     * Converts an Operation to a JSON string
     */
    public static String serialize(Operation op) {
        if (op.getType() == Operation.Type.INSERT) {
            return serializeInsert((InsertOperation) op);
        } else if (op.getType() == Operation.Type.DELETE) {
            return serializeDelete((DeleteOperation) op);
        } else if (op.getType() == Operation.Type.FORMAT) {
            return serializeFormat((FormatOperation) op);
        }
        throw new IllegalArgumentException("Unknown operation type: " + op.getType());
    }

    /**
     * Serializes an InsertOperation to JSON
     */
    private static String serializeInsert(InsertOperation op) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"op\": \"insert\", ");
        json.append("\"userID\": ").append(op.userID).append(", ");
        json.append("\"clock\": ").append(op.clock).append(", ");
        json.append("\"value\": \"").append(escapeJson(String.valueOf(op.value))).append("\", ");
        
        // Handle parentID - if null, use null values
        if (op.parentID != null) {
            json.append("\"parentUserID\": ").append(op.parentID.userID).append(", ");
            json.append("\"parentClock\": ").append(op.parentID.counter);
        } else {
            json.append("\"parentUserID\": null, ");
            json.append("\"parentClock\": null");
        }
        
        // Add formatting flags
        json.append(", ");
        json.append("\"bold\": ").append(op.bold).append(", ");
        json.append("\"italic\": ").append(op.italic);
        
        json.append("}");
        return json.toString();
    }

    private static String serializeFormat(FormatOperation op) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"op\": \"format\", ");
        json.append("\"userID\": ").append(op.userID).append(", ");
        json.append("\"clock\": ").append(op.clock).append(", ");
        json.append("\"targetUserID\": ").append(op.targetID.userID).append(", ");
        json.append("\"targetClock\": ").append(op.targetID.counter).append(", ");
        json.append("\"bold\": ").append(op.bold).append(", ");
        json.append("\"italic\": ").append(op.italic);
        json.append("}");
        return json.toString();
    }

    /**
     * Serializes a DeleteOperation to JSON
     */
    private static String serializeDelete(DeleteOperation op) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"op\": \"delete\", ");
        json.append("\"userID\": ").append(op.userID).append(", ");
        json.append("\"clock\": ").append(op.clock).append(", ");
        json.append("\"targetUserID\": ").append(op.targetID.userID).append(", ");
        json.append("\"targetClock\": ").append(op.targetID.counter);
        json.append("}");
        return json.toString();
    }

    /**
     * Converts a JSON string back to an Operation object
     */
    public static Operation deserialize(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        if (!obj.has("op")) {
            throw new IllegalArgumentException("Key not found in JSON: op");
        }

        String opType = obj.get("op").getAsString();

        if ("insert".equals(opType)) {
            return deserializeInsert(obj);
        } else if ("delete".equals(opType)) {
            return deserializeDelete(obj);
        } else if ("format".equals(opType)) {
            return deserializeFormat(obj);
        }
        throw new IllegalArgumentException("Unknown operation type in JSON: " + opType);
    }

    /**
     * Deserializes an InsertOperation from JSON
     */
    private static InsertOperation deserializeInsert(JsonObject obj) {
        int userID = obj.get("userID").getAsInt();
        int clock = obj.get("clock").getAsInt();
        String value = obj.get("value").getAsString();
        char charValue = value.isEmpty() ? '\0' : value.charAt(0);

        // Handle parentID (nullable)
        CharId parentID = null;
        Integer parentUserID = (obj.has("parentUserID") && !obj.get("parentUserID").isJsonNull())
                ? obj.get("parentUserID").getAsInt() : null;
        Integer parentClock = (obj.has("parentClock") && !obj.get("parentClock").isJsonNull())
                ? obj.get("parentClock").getAsInt() : null;

        if (parentUserID != null && parentClock != null) {
            parentID = new CharId(parentClock, parentUserID);
        }
        
        // Extract formatting flags
        boolean bold = obj.has("bold") && obj.get("bold").getAsBoolean();
        boolean italic = obj.has("italic") && obj.get("italic").getAsBoolean();
        
        return new InsertOperation(userID, clock, charValue, parentID, bold, italic);
    }

    private static FormatOperation deserializeFormat(JsonObject obj) {
        int userID       = obj.get("userID").getAsInt();
        int clock        = obj.get("clock").getAsInt();
        int targetUserID = obj.get("targetUserID").getAsInt();
        int targetClock  = obj.get("targetClock").getAsInt();
        boolean bold     = obj.has("bold")   && obj.get("bold").getAsBoolean();
        boolean italic   = obj.has("italic") && obj.get("italic").getAsBoolean();
        return new FormatOperation(userID, clock, new CharId(targetClock, targetUserID), bold, italic);
    }

    /**
     * Deserializes a DeleteOperation from JSON
     */
    private static DeleteOperation deserializeDelete(JsonObject obj) {
        int userID = obj.get("userID").getAsInt();
        int clock = obj.get("clock").getAsInt();
        int targetUserID = obj.get("targetUserID").getAsInt();
        int targetClock = obj.get("targetClock").getAsInt();
        
        CharId targetID = new CharId(targetClock, targetUserID);
        return new DeleteOperation(userID, clock, targetID);
    }

    // ============= JSON Parsing Helper Methods =============

    /**
     * Extracts a string value from JSON by key
     */
    private static String extractStringValue(String json, String key) {
        String pattern = "\"" + key + "\": \"";
        int startIdx = json.indexOf(pattern);
        if (startIdx == -1) {
            throw new IllegalArgumentException("Key not found in JSON: " + key);
        }
        
        startIdx += pattern.length();
        int endIdx = json.indexOf("\"", startIdx);
        if (endIdx == -1) {
            throw new IllegalArgumentException("Malformed JSON string value for key: " + key);
        }
        
        return unescapeJson(json.substring(startIdx, endIdx));
    }

    /**
     * Extracts an integer value from JSON by key
     */
    private static int extractIntValue(String json, String key) {
        String pattern = "\"" + key + "\": ";
        int startIdx = json.indexOf(pattern);
        if (startIdx == -1) {
            throw new IllegalArgumentException("Key not found in JSON: " + key);
        }
        
        startIdx += pattern.length();
        int endIdx = startIdx;
        
        // Find end of number (comma, closing brace, or null)
        while (endIdx < json.length() && json.charAt(endIdx) != ',' && json.charAt(endIdx) != '}') {
            endIdx++;
        }
        
        String value = json.substring(startIdx, endIdx).trim();
        if ("null".equals(value)) {
            throw new IllegalArgumentException("Cannot convert null to int for key: " + key);
        }
        return Integer.parseInt(value);
    }

    /**
     * Extracts an optional integer value from JSON by key
     * Returns null if the value is "null" or if the key is not found
     */
    private static Integer extractOptionalIntValue(String json, String key) {
        String pattern = "\"" + key + "\": ";
        int startIdx = json.indexOf(pattern);
        if (startIdx == -1) {
            return null;
        }
        
        startIdx += pattern.length();
        int endIdx = startIdx;
        
        // Find end of value (comma or closing brace)
        while (endIdx < json.length() && json.charAt(endIdx) != ',' && json.charAt(endIdx) != '}') {
            endIdx++;
        }
        
        String value = json.substring(startIdx, endIdx).trim();
        if ("null".equals(value)) {
            return null;
        }
        return Integer.parseInt(value);
    }

    /**
     * Extracts a boolean value from JSON by key
     */
    private static boolean extractBooleanValue(String json, String key) {
        String pattern = "\"" + key + "\": ";
        int startIdx = json.indexOf(pattern);
        if (startIdx == -1) {
            return false; // Default to false if not present
        }
        
        startIdx += pattern.length();
        int endIdx = startIdx;
        
        // Find end of value (comma or closing brace)
        while (endIdx < json.length() && json.charAt(endIdx) != ',' && json.charAt(endIdx) != '}') {
            endIdx++;
        }
        
        String value = json.substring(startIdx, endIdx).trim();
        return Boolean.parseBoolean(value);
    }

    /**
     * Escapes special characters for JSON strings
     */
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * Unescapes special characters from JSON strings
     */
    private static String unescapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\n", "\n")
                  .replace("\\r", "\r")
                  .replace("\\t", "\t")
                  .replace("\\\"", "\"")
                  .replace("\\\\", "\\");
    }
}
