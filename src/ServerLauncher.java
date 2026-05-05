import database.DatabaseManager;
import network.WebSocketServer;

/**
 * Launcher for the WebSocket Collaboration Server.
 *
 * Connects to MongoDB on startup so all file operations (create, save, rename,
 * delete) are persisted server-side. Clients require no local database.
 *
 * Run this FIRST before starting any client instances.
 */
public class ServerLauncher {
    public static void main(String[] args) {
        int port = 8081;

        System.out.println("========================================================");
        System.out.println("   Collaborative Text Editor - WebSocket Server");
        System.out.println("   Starting server on ws://localhost:" + port);
        System.out.println("========================================================\n");

        // Connect to MongoDB before starting the WebSocket server
        DatabaseManager db = null;
        try {
            db = new DatabaseManager();
            db.connect();
            System.out.println("[ServerLauncher] Connected to MongoDB (collab_editor)");
        } catch (Exception e) {
            System.err.println("[ServerLauncher] WARNING: MongoDB unavailable — file persistence disabled");
            System.err.println("  Reason: " + e.getMessage());
            db = null;
        }

        try {
            final DatabaseManager finalDb = db;
            WebSocketServer server = new WebSocketServer(port, finalDb);
            server.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop(1000);
                    if (finalDb != null) finalDb.disconnect();
                    System.out.println("Server stopped.");
                } catch (Exception ignored) {}
            }));

            System.out.println("\nServer is READY for connections!");
            System.out.println("   Clients should connect to: ws://YOUR_LAN_IP:" + port);
            System.out.println("   MongoDB persistence: " + (db != null ? "ENABLED" : "DISABLED"));
            System.out.println("\nWaiting for clients...\n");

            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
