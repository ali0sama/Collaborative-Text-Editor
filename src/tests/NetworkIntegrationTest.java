package tests;

import java.net.URI;
import java.util.Map;

import crdt.character.CharacterCRDT;
import crdt.utils.Clock;
import network.WebSocketClient;
import network.WebSocketServer;
import operations.DeleteOperation;
import operations.InsertOperation;
import session.CollaborationSession.UserRole;

/**
 * Tests Member 3 work:
 * - WebSocket client connection
 * - join session
 * - sending operations
 * - receiving operations
 * - message handling
 * - history sync for late joiners
 * - user list updates
 */
public class NetworkIntegrationTest {

    private static final int PORT = 8090;
    private static final String SERVER_URI = "ws://localhost:" + PORT;
    private static final String SESSION_ID = "doc1";

    public void runAll() throws Exception {
        System.out.println("\n=== Network Integration Test ===");

        WebSocketServer server = new WebSocketServer(PORT, null);
        server.setReuseAddr(true);
        server.start();
        Thread.sleep(1000);

        CharacterCRDT crdt1 = new CharacterCRDT();
        CharacterCRDT crdt2 = new CharacterCRDT();
        CharacterCRDT crdt3 = new CharacterCRDT();

        Clock clock1 = new Clock();
        Clock clock2 = new Clock();
        Clock clock3 = new Clock();

        final WebSocketClient[] client1 = new WebSocketClient[1];
        final WebSocketClient[] client2 = new WebSocketClient[1];
        final WebSocketClient[] client3 = new WebSocketClient[1];

        client1[0] = new WebSocketClient(
                new URI(SERVER_URI),
                SESSION_ID,
                1,
                UserRole.EDITOR,
                crdt1,
                clock1,
                () -> System.out.println("[Client1 Refresh] Doc = " + crdt1.getDocument())
        );

        client2[0] = new WebSocketClient(
                new URI(SERVER_URI),
                SESSION_ID,
                2,
                UserRole.EDITOR,
                crdt2,
                clock2,
                () -> System.out.println("[Client2 Refresh] Doc = " + crdt2.getDocument())
        );

        client1[0].connectToServer();
        client2[0].connectToServer();

        waitUntil(() -> client1[0].isOpen() && client2[0].isOpen(), 5000, "Clients failed to connect");

        waitUntil(() -> client1[0].getActiveUsersSnapshot().size() == 2, 5000, "Client1 did not receive user list");
        waitUntil(() -> client2[0].getActiveUsersSnapshot().size() == 2, 5000, "Client2 did not receive user list");

        System.out.println("✓ Both clients connected and received user list");

        // =========================
        // Test 1: Insert operation
        // =========================
        int c1 = clock1.tick();
        InsertOperation insertH = new InsertOperation(1, c1, 'H', null);

        insertH.apply(crdt1);
        client1[0].sendOperation(insertH);

        waitUntil(() -> "H".equals(crdt2.getDocument()), 5000, "Client2 did not receive insert operation");

        assertEquals("H", crdt1.getDocument(), "Client1 local document incorrect after insert");
        assertEquals("H", crdt2.getDocument(), "Client2 remote document incorrect after insert");

        System.out.println("✓ Insert operation synchronized");

        // =========================
        // Test 2: Second insert
        // =========================
        int c2 = clock1.tick();
        InsertOperation insertI = new InsertOperation(1, c2, 'i', insertH.charID);

        insertI.apply(crdt1);
        client1[0].sendOperation(insertI);

        waitUntil(() -> "Hi".equals(crdt2.getDocument()), 5000, "Client2 did not receive second insert");

        assertEquals("Hi", crdt1.getDocument(), "Client1 local document incorrect after second insert");
        assertEquals("Hi", crdt2.getDocument(), "Client2 remote document incorrect after second insert");

        System.out.println("✓ Second insert synchronized");

        // =========================
        // Test 3: Delete operation
        // =========================
        int c3 = clock1.tick();
        DeleteOperation deleteI = new DeleteOperation(1, c3, insertI.charID);

        deleteI.apply(crdt1);
        client1[0].sendOperation(deleteI);

        waitUntil(() -> "H".equals(crdt2.getDocument()), 5000, "Client2 did not receive delete operation");

        assertEquals("H", crdt1.getDocument(), "Client1 local document incorrect after delete");
        assertEquals("H", crdt2.getDocument(), "Client2 remote document incorrect after delete");

        System.out.println("✓ Delete operation synchronized");

        // =========================
        // Test 4: Late join history
        // =========================
        client3[0] = new WebSocketClient(
                new URI(SERVER_URI),
                SESSION_ID,
                3,
                UserRole.EDITOR,
                crdt3,
                clock3,
                () -> System.out.println("[Client3 Refresh] Doc = " + crdt3.getDocument())
        );

        client3[0].connectToServer();

        waitUntil(() -> client3[0].isOpen(), 5000, "Client3 failed to connect");
        waitUntil(() -> "H".equals(crdt3.getDocument()), 5000, "Client3 did not receive history");

        assertEquals("H", crdt3.getDocument(), "Client3 history document incorrect");

        System.out.println("✓ Late join history works");

        // =========================
        // Test 5: User list update
        // =========================
        waitUntil(() -> client1[0].getActiveUsersSnapshot().size() == 3, 5000, "Client1 user list not updated to 3");
        waitUntil(() -> client2[0].getActiveUsersSnapshot().size() == 3, 5000, "Client2 user list not updated to 3");
        waitUntil(() -> client3[0].getActiveUsersSnapshot().size() == 3, 5000, "Client3 user list not updated to 3");

        printUsers("Client1 users", client1[0].getActiveUsersSnapshot());
        printUsers("Client2 users", client2[0].getActiveUsersSnapshot());
        printUsers("Client3 users", client3[0].getActiveUsersSnapshot());

        System.out.println("✓ User list update works");

        // =========================
        // Cleanup
        // =========================
        client1[0].disconnectFromServer();
        client2[0].disconnectFromServer();
        client3[0].disconnectFromServer();

        Thread.sleep(1000);
        server.stop();

        System.out.println("=== Network Integration Test PASSED ===\n");
    }

    private static void printUsers(String label, Map<Integer, String> users) {
        System.out.println(label + ": " + users);
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new RuntimeException(message + " | Expected: [" + expected + "] but got: [" + actual + "]");
        }
    }

    private static void waitUntil(Check condition, long timeoutMillis, String failMessage) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMillis) {
            if (condition.ok()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new RuntimeException(failMessage);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}