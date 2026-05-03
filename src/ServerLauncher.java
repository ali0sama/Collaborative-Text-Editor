import network.WebSocketServer;

/**
 * Launcher for the WebSocket Collaboration Server
 * 
 * Run this FIRST before starting any client instances
 */
public class ServerLauncher {
    public static void main(String[] args) {
        int port = 8081;
        
        System.out.println("========================================================");
        System.out.println("   Collaborative Text Editor - WebSocket Server");
        System.out.println("   Starting server on ws://localhost:" + port);
        System.out.println("========================================================\n");
        
        try {
            WebSocketServer server = new WebSocketServer(port);
            server.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop(1000);
                    System.out.println("Server stopped.");
                } catch (Exception ignored) {
                    // Best-effort shutdown.
                }
            }));
            
            System.out.println("\nServer is READY for connections!");
            System.out.println("   Clients should connect to: ws://YOUR_LAN_IP:" + port);
            System.out.println("\nWaiting for clients...\n");

            // Keep launcher process alive until Ctrl+C.
            Thread.currentThread().join();
            
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
