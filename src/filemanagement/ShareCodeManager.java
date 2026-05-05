package filemanagement;

import database.FileRepository;
import java.sql.SQLException;
import java.util.Random;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import filemanagement.PermissionManager.UserRole;

public class ShareCodeManager {

    /**
     * Generates a linked pair of codes that share the same 7-character suffix.
     * index 0 = editor code (E + suffix), index 1 = viewer code (V + suffix).
     * The shared suffix is the session ID — both codes route to the same server session.
     */
    public static String[] generateLinkedCodes() {
        String suffix = generateRandomAlphanumeric(7);
        return new String[]{"E" + suffix, "V" + suffix};
    }

    /**
     * Extracts the session ID from any share code by stripping the role prefix.
     * "EABC1234" and "VABC1234" both return "ABC1234" — the same session.
     */
    public static String extractSessionId(String code) {
        if (code != null && code.length() == 8) return code.substring(1);
        return code;
    }

    // Returns true if this code grants editor access (first char 'E').
    public static boolean isEditorCode(String code) {
        return code != null && code.length() == 8 && code.charAt(0) == 'E';
    }

    private static String generateRandomAlphanumeric(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        Random rnd = new Random();
        while (sb.length() < length) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // Task 2: Look up the code in the database and return docId and role [cite: 87]
    public static Map<String, Object> joinByCode(FileRepository repo, String code) throws SQLException {
        List<String[]> allDocs = repo.getAllFileRecords();
        for (String[] doc : allDocs) {
            if (doc[2].equals(code)) { // Editor code match
                Map<String, Object> result = new HashMap<>();
                result.put("docId", doc[0]);
                result.put("role", UserRole.EDITOR);
                return result;
            }
            if (doc[3].equals(code)) { // Viewer code match
                Map<String, Object> result = new HashMap<>();
                result.put("docId", doc[0]);
                result.put("role", UserRole.VIEWER);
                return result;
            }
        }
        return null;
    }
}