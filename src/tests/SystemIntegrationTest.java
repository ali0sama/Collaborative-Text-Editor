package tests;

import crdt.block.Block;
import crdt.block.BlockCRDT;
import crdt.block.BlockID;
import crdt.character.CharId;
import crdt.character.CRDTChar;
import crdt.character.CharacterCRDT;
import crdt.utils.Clock;
import database.DatabaseManager;
import database.FileRepository;
import filemanagement.FileManager;
import filemanagement.PermissionManager;
import filemanagement.ShareCodeManager;
import io.ImportExportManager;
import operations.InsertOperation;
import undoredo.UndoRedoManager;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * End-to-end integration tests that exercise the full Phase 3 backend stack:
 * persistence, sharing codes, permissions, undo/redo, and import/export.
 */
public class SystemIntegrationTest {

    private static int passed = 0;
    private static int failed = 0;

    private DatabaseManager db;
    private FileRepository  repo;
    private FileManager     fileManager;

    // ─── Entry Points ─────────────────────────────────────────────────────────

    public void runAll() {
        passed = 0; failed = 0;

        try {
            setUp();
            test1_PersistenceRoundTrip();
            test2_EditorCodeJoin();
            test3_ViewerPermission();
            test4_Undo();
            test5_RedoAvailability();
            test6_ImportExportWithBold();
            test7_ConcurrentPersistence();
            test8_DeleteAndReload();
        } catch (Exception e) {
            System.err.println("[SystemIntegrationTest] Fatal setup error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            tearDown();
        }

        System.out.println("\n=== System Integration Tests: " + passed + " passed, " + failed + " failed ===\n");
    }

    // ─── Setup / Teardown ─────────────────────────────────────────────────────

    private void setUp() throws SQLException {
        db = new DatabaseManager();
        db.connect();
        repo        = new FileRepository(db);
        fileManager = new FileManager(repo);
    }

    private void tearDown() {
        try { db.disconnect(); } catch (SQLException ignored) {}
    }

    // ─── Test 1: Persistence round-trip ───────────────────────────────────────

    /**
     * Insert "Hello World" into a CRDT, save it, reload it, and assert the text matches.
     */
    private void test1_PersistenceRoundTrip() throws SQLException {
        System.out.println("\n[T1] Persistence round-trip");
        String docId = fileManager.createNewFile("T1-RoundTrip");
        String[] codes = getCodesForDoc(docId);

        BlockCRDT toSave = buildBlockCRDT("Hello World", 1);
        repo.saveFile(docId, "T1-RoundTrip", codes[0], codes[1], toSave);

        BlockCRDT loaded = fileManager.openFile(docId);
        checkNotNull("T1: loadFile not null", loaded);
        check("T1: text matches after save+reload", "Hello World",
              loaded != null ? loaded.getDocumentText() : "");

        repo.deleteFile(docId);
    }

    // ─── Test 2: Editor code join gets EDITOR role ────────────────────────────

    /**
     * Create a file, get its editor share code, join using that code, assert EDITOR role.
     */
    private void test2_EditorCodeJoin() throws SQLException {
        System.out.println("\n[T2] Editor code join");
        String docId = fileManager.createNewFile("T2-ShareEditor");
        String[] codes = getCodesForDoc(docId);
        String editorCode = codes[0];

        Map<String, Object> result = ShareCodeManager.joinByCode(repo, editorCode);
        checkNotNull("T2: joinByCode not null", result);
        if (result != null) {
            PermissionManager.UserRole role = (PermissionManager.UserRole) result.get("role");
            check("T2: editor code gives EDITOR role",
                  PermissionManager.UserRole.EDITOR.name(),
                  role != null ? role.name() : "null");
        }

        repo.deleteFile(docId);
    }

    // ─── Test 3: Viewer code join — canEdit() returns false ───────────────────

    /**
     * Join with the viewer code and assert PermissionManager.canEdit() is false.
     */
    private void test3_ViewerPermission() throws SQLException {
        System.out.println("\n[T3] Viewer permission");
        String docId = fileManager.createNewFile("T3-ShareViewer");
        String[] codes = getCodesForDoc(docId);
        String viewerCode = codes[1];

        Map<String, Object> result = ShareCodeManager.joinByCode(repo, viewerCode);
        checkNotNull("T3: joinByCode not null", result);
        if (result != null) {
            PermissionManager.UserRole role = (PermissionManager.UserRole) result.get("role");
            checkBool("T3: viewer code gives VIEWER role",
                      PermissionManager.UserRole.VIEWER, role);
            checkBool("T3: canEdit() returns false", false, PermissionManager.canEdit(role));
        }

        repo.deleteFile(docId);
    }

    // ─── Test 4: Undo — removes last two inserts ──────────────────────────────

    /**
     * Insert A, B, C into a CRDT via UndoRedoManager. Undo twice and assert only A remains.
     */
    private void test4_Undo() {
        System.out.println("\n[T4] Undo");
        CharacterCRDT crdt = new CharacterCRDT();
        Clock          clock = new Clock();
        UndoRedoManager mgr  = new UndoRedoManager(1, clock);

        int t1 = clock.tick(); CharId id1 = new CharId(t1, 1);
        crdt.insert(id1, 'A', null);
        mgr.recordOperation(new InsertOperation(1, t1, 'A', null));

        int t2 = clock.tick(); CharId id2 = new CharId(t2, 1);
        crdt.insert(id2, 'B', id1);
        mgr.recordOperation(new InsertOperation(1, t2, 'B', id1));

        int t3 = clock.tick(); CharId id3 = new CharId(t3, 1);
        crdt.insert(id3, 'C', id2);
        mgr.recordOperation(new InsertOperation(1, t3, 'C', id2));

        check("T4a: initial state", "ABC", crdt.getDocument());
        mgr.undo(crdt);
        check("T4b: after undo C", "AB", crdt.getDocument());
        mgr.undo(crdt);
        check("T4c: after undo B", "A", crdt.getDocument());
    }

    // ─── Test 5: Redo availability ────────────────────────────────────────────

    /**
     * After two undos, the redo stack must be non-empty and canRedo() must be true.
     */
    private void test5_RedoAvailability() {
        System.out.println("\n[T5] Redo availability");
        CharacterCRDT crdt = new CharacterCRDT();
        Clock          clock = new Clock();
        UndoRedoManager mgr  = new UndoRedoManager(1, clock);

        int t1 = clock.tick(); CharId id1 = new CharId(t1, 1);
        crdt.insert(id1, 'X', null);
        mgr.recordOperation(new InsertOperation(1, t1, 'X', null));

        int t2 = clock.tick(); CharId id2 = new CharId(t2, 1);
        crdt.insert(id2, 'Y', id1);
        mgr.recordOperation(new InsertOperation(1, t2, 'Y', id1));

        int t3 = clock.tick(); CharId id3 = new CharId(t3, 1);
        crdt.insert(id3, 'Z', id2);
        mgr.recordOperation(new InsertOperation(1, t3, 'Z', id2));

        checkBool("T5a: canUndo before undo", true, mgr.canUndo());
        checkBool("T5b: canRedo before undo", false, mgr.canRedo());

        // Undo Z, then undo Y — X's insert still remains in the undo stack
        mgr.undo(crdt);
        mgr.undo(crdt);

        checkBool("T5c: canUndo still true (X insert remains)", true, mgr.canUndo());
        checkBool("T5d: canRedo true (Y and Z queued)", true, mgr.canRedo());
    }

    // ─── Test 6: Import/Export preserves text and bold flag ───────────────────

    /**
     * Export a BlockCRDT with bold "Hello" to a temp .txt file, re-import it,
     * and verify both the text and the bold formatting flag are preserved.
     */
    private void test6_ImportExportWithBold() {
        System.out.println("\n[T6] Import/Export with bold");
        try {
            // Build bold "Hello"
            BlockCRDT exportCRDT = new BlockCRDT();
            Block block = new Block(new BlockID(1, 99));
            String text = "Hello";
            CharId prev = null;
            for (int i = 0; i < text.length(); i++) {
                CharId id = new CharId(i + 1, 99);
                block.getContent().insert(id, text.charAt(i), prev);
                block.getContent().setBold(id, true);
                prev = id;
            }
            exportCRDT.insertBlock(block);

            String tmpPath = System.getProperty("java.io.tmpdir") + "/collab_sys_test_t6.txt";
            ImportExportManager.exportToTxt(exportCRDT, tmpPath);

            BlockCRDT imported = ImportExportManager.importFromTxt(tmpPath, 99, new Clock());
            checkNotNull("T6a: imported not null", imported);

            if (imported != null) {
                check("T6b: text preserved after export+import", text, imported.getDocumentText());

                List<Block> visibleBlocks = imported.getVisibleBlocks();
                if (!visibleBlocks.isEmpty()) {
                    List<CRDTChar> chars = visibleBlocks.get(0).getContent().getVisibleChars();
                    boolean allBold = !chars.isEmpty() && chars.stream().allMatch(CRDTChar::isBold);
                    checkBool("T6c: bold flag preserved", true, allBold);
                } else {
                    fail("T6c: imported BlockCRDT has no visible blocks");
                }
            }

            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(tmpPath));
        } catch (IOException e) {
            fail("T6: IOException — " + e.getMessage());
        }
    }

    // ─── Test 7: Concurrent persistence ──────────────────────────────────────

    /**
     * Two users independently insert at the root. After exchanging operations they converge.
     * Save the merged CRDT to DB, reload it, and assert the content is identical.
     */
    private void test7_ConcurrentPersistence() throws SQLException {
        System.out.println("\n[T7] Concurrent persistence");

        CharacterCRDT crdt1 = new CharacterCRDT();
        CharacterCRDT crdt2 = new CharacterCRDT();

        CharId id1 = new CharId(1, 1);  // user 1, clock 1
        CharId id2 = new CharId(1, 2);  // user 2, clock 1 (same logical time, different user)

        crdt1.insert(id1, 'A', null);
        crdt2.insert(id2, 'B', null);

        // Exchange (simulate network sync)
        crdt1.insert(id2, 'B', null);
        crdt2.insert(id1, 'A', null);

        String text1 = crdt1.getDocument();
        String text2 = crdt2.getDocument();
        check("T7a: both CRDTs converge after sync", text1, text2);

        // Wrap crdt1 in BlockCRDT and persist it
        BlockCRDT toSave = new BlockCRDT();
        Block b = new Block(new BlockID(1, 1));
        b.getContent().bulkLoad(crdt1.getAllChars());
        toSave.insertBlock(b);

        String docId = fileManager.createNewFile("T7-Concurrent");
        String[] codes = getCodesForDoc(docId);
        repo.saveFile(docId, "T7-Concurrent", codes[0], codes[1], toSave);

        BlockCRDT reloaded = fileManager.openFile(docId);
        checkNotNull("T7b: reloaded not null", reloaded);
        check("T7c: reloaded text matches merged content",
              text1, reloaded != null ? reloaded.getDocumentText() : "");

        repo.deleteFile(docId);
    }

    // ─── Test 8: Delete and reload returns null ───────────────────────────────

    /**
     * Save a document, delete it, then attempt to reload it — expect null.
     */
    private void test8_DeleteAndReload() throws SQLException {
        System.out.println("\n[T8] Delete and reload");
        String docId = fileManager.createNewFile("T8-Delete");
        String[] codes = getCodesForDoc(docId);
        repo.saveFile(docId, "T8-Delete", codes[0], codes[1], new BlockCRDT());
        repo.deleteFile(docId);

        BlockCRDT result = fileManager.openFile(docId);
        checkNull("T8: loadFile returns null after delete", result);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Fetches [editorCode, viewerCode] for the given docId from the DB. */
    private String[] getCodesForDoc(String docId) throws SQLException {
        for (String[] rec : repo.getAllFileRecords()) {
            if (rec[0].equals(docId)) return new String[]{ rec[2], rec[3] };
        }
        return new String[]{ "EDITORXX", "VIEWERXX" };
    }

    /** Builds a single-block BlockCRDT containing the given plain text. */
    private static BlockCRDT buildBlockCRDT(String text, int userID) {
        BlockCRDT blockCRDT = new BlockCRDT();
        Block block = new Block(new BlockID(1, userID));
        CharId prev = null;
        for (int i = 0; i < text.length(); i++) {
            CharId id = new CharId(i + 1, userID);
            block.getContent().insert(id, text.charAt(i), prev);
            prev = id;
        }
        blockCRDT.insertBlock(block);
        return blockCRDT;
    }

    // ─── Assertion Helpers ────────────────────────────────────────────────────

    private static void check(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            System.out.println("[PASS] " + name);
            passed++;
        } else {
            System.out.println("[FAIL] " + name);
            System.out.println("       Expected : \"" + expected + "\"");
            System.out.println("       Got      : \"" + actual + "\"");
            failed++;
        }
    }

    private static void checkBool(String name, boolean expected, boolean actual) {
        if (expected == actual) {
            System.out.println("[PASS] " + name);
            passed++;
        } else {
            System.out.println("[FAIL] " + name + " — expected " + expected + " got " + actual);
            failed++;
        }
    }

    private static <T> void checkBool(String name, T expected, T actual) {
        if (expected == actual) {
            System.out.println("[PASS] " + name);
            passed++;
        } else {
            System.out.println("[FAIL] " + name + " — expected " + expected + " got " + actual);
            failed++;
        }
    }

    private static void checkNull(String name, Object actual) {
        if (actual == null) {
            System.out.println("[PASS] " + name);
            passed++;
        } else {
            System.out.println("[FAIL] " + name + " — expected null, got " + actual);
            failed++;
        }
    }

    private static void checkNotNull(String name, Object actual) {
        if (actual != null) {
            System.out.println("[PASS] " + name);
            passed++;
        } else {
            System.out.println("[FAIL] " + name + " — expected non-null, got null");
            failed++;
        }
    }

    private static void fail(String name) {
        System.out.println("[FAIL] " + name);
        failed++;
    }
}
