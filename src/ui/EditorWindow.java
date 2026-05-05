package ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import crdt.block.BlockCRDT;
import crdt.character.CharId;
import database.DocumentSerializer;
import filemanagement.ShareCodeManager;
import io.ImportExportManager;
import network.WebSocketClient;
import operations.Operation;
import serializations.OperationSerializer;
import session.CollaborationSession;
import session.CollaborationSession.UserRole;
import undoredo.UndoRedoManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Main application window.
 *
 * All file operations (create, open, save, rename, delete) go through the
 * WebSocket server, which persists everything in MongoDB. Clients need no
 * local database.
 */
public class EditorWindow extends JFrame {

    // ─── Document state ───────────────────────────────────────────────────────
    private String   currentDocId      = null;
    private String   currentDocName    = "Untitled";
    private String   currentEditorCode = null;
    private String   currentViewerCode = null;
    private UserRole currentRole;
    private String   lastServerUrl     = "ws://127.0.0.1:8081";

    // ─── Core ─────────────────────────────────────────────────────────────────
    private final int            localUserID;
    private       UndoRedoManager undoRedoManager;
    private       WebSocketClient wsClient;

    // ─── UI ───────────────────────────────────────────────────────────────────
    private final EditorPane editorPane;
    private final UserPanel  userPanel;

    private final JToggleButton boldBtn;
    private final JToggleButton italicBtn;
    private final JButton       undoBtn;
    private final JButton       redoBtn;
    private final JButton       shareBtn;
    private       JButton       disconnectBtn;

    private final JLabel statusLabel;
    private final JLabel roleLabel;
    private final JLabel docNameLabel;
    private final JLabel savedLabel;

    private Timer autoSaveTimer;

    // ─── Constructor ─────────────────────────────────────────────────────────

    public EditorWindow(int userID, String sessionID, CollaborationSession session, boolean isEditor) {
        this.localUserID = userID;
        this.currentRole = isEditor ? UserRole.EDITOR : UserRole.VIEWER;

        editorPane = new EditorPane(userID, sessionID, isEditor);
        userPanel  = new UserPanel(session);

        this.undoRedoManager = new UndoRedoManager(userID, editorPane.getClock());
        editorPane.setUndoRedoManager(undoRedoManager);

        setJMenuBar(buildMenuBar());

        // ── Toolbar ──────────────────────────────────────────────────────────
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);

        boldBtn = new JToggleButton("B");
        boldBtn.setFont(boldBtn.getFont().deriveFont(Font.BOLD));
        boldBtn.setToolTipText("Bold");
        boldBtn.setFocusable(false);
        boldBtn.addActionListener(e -> editorPane.applyBold());

        italicBtn = new JToggleButton("I");
        italicBtn.setFont(italicBtn.getFont().deriveFont(Font.ITALIC));
        italicBtn.setToolTipText("Italic");
        italicBtn.setFocusable(false);
        italicBtn.addActionListener(e -> editorPane.applyItalic());

        undoBtn = new JButton("Undo");
        undoBtn.setToolTipText("Undo (Ctrl+Z)");
        undoBtn.addActionListener(e -> handleUndo());

        redoBtn = new JButton("Redo");
        redoBtn.setToolTipText("Redo (Ctrl+Y)");
        redoBtn.addActionListener(e -> handleRedo());

        shareBtn = new JButton("Share");
        shareBtn.setToolTipText("Show sharing codes for this document");
        shareBtn.addActionListener(e -> handleShare());

        JButton joinBtn = new JButton("Join");
        joinBtn.setToolTipText("Join a session by entering a share code");
        joinBtn.addActionListener(e -> showJoinByCodeDialog());

        JButton connectBtn = new JButton("Connect");
        connectBtn.setToolTipText("Connect to a collaboration server");
        connectBtn.addActionListener(e -> showConnectDialog());

        disconnectBtn = new JButton("Disconnect");
        disconnectBtn.setToolTipText("Disconnect from the current session");
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(e -> handleDisconnect());

        toolbar.add(boldBtn);
        toolbar.add(italicBtn);
        toolbar.addSeparator();
        toolbar.add(undoBtn);
        toolbar.add(redoBtn);
        toolbar.addSeparator();
        toolbar.add(shareBtn);
        toolbar.add(joinBtn);
        toolbar.addSeparator();
        toolbar.add(connectBtn);
        toolbar.add(disconnectBtn);

        // ── Status bar ───────────────────────────────────────────────────────
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        docNameLabel = new JLabel(currentDocName);
        docNameLabel.setFont(docNameLabel.getFont().deriveFont(Font.BOLD));
        roleLabel    = new JLabel(isEditor ? "Editor" : "Viewer");
        roleLabel.setForeground(isEditor ? new Color(0, 100, 0) : new Color(110, 110, 110));
        statusLabel  = new JLabel("Disconnected");
        statusLabel.setForeground(Color.GRAY);
        leftStatus.add(docNameLabel);
        leftStatus.add(makeSep());
        leftStatus.add(roleLabel);
        leftStatus.add(makeSep());
        leftStatus.add(statusLabel);

        JPanel rightStatus = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 3));
        savedLabel = new JLabel("");
        savedLabel.setForeground(new Color(0, 140, 0));
        rightStatus.add(savedLabel);

        southPanel.add(leftStatus,  BorderLayout.WEST);
        southPanel.add(rightStatus, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(toolbar,    BorderLayout.NORTH);
        add(editorPane, BorderLayout.CENTER);
        add(userPanel,  BorderLayout.EAST);
        add(southPanel, BorderLayout.SOUTH);

        bindKeyboardShortcuts();

        editorPane.setOnFormattingChange(() -> {
            boldBtn.setSelected(editorPane.isBoldAtCaret());
            italicBtn.setSelected(editorPane.isItalicAtCaret());
        });

        applyRoleMode(isEditor);
        startAutoSave();

        updateTitle();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ─── Menu bar ─────────────────────────────────────────────────────────────

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem newItem    = new JMenuItem("New File");
        JMenuItem openItem   = new JMenuItem("Open File");
        JMenuItem saveItem   = new JMenuItem("Save");
        JMenuItem renameItem = new JMenuItem("Rename File");
        JMenuItem deleteItem = new JMenuItem("Delete File");
        JMenuItem importItem = new JMenuItem("Import .txt");
        JMenuItem exportItem = new JMenuItem("Export .txt");

        newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        importItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK));

        newItem.addActionListener(e    -> handleNewFile());
        openItem.addActionListener(e   -> handleOpenFile());
        saveItem.addActionListener(e   -> triggerSave());
        renameItem.addActionListener(e -> handleRenameFile());
        deleteItem.addActionListener(e -> handleDeleteFile());
        importItem.addActionListener(e -> handleImport());
        exportItem.addActionListener(e -> handleExport());

        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.add(renameItem);
        fileMenu.add(deleteItem);
        fileMenu.addSeparator();
        fileMenu.add(importItem);
        fileMenu.add(exportItem);

        menuBar.add(fileMenu);
        return menuBar;
    }

    // ─── Keyboard shortcuts ───────────────────────────────────────────────────

    private void bindKeyboardShortcuts() {
        InputMap  im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
        am.put("undo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { handleUndo(); } });
        am.put("redo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { handleRedo(); } });
        am.put("save", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { triggerSave(); } });
    }

    // ─── File menu handlers ───────────────────────────────────────────────────

    private void handleNewFile() {
        String name = JOptionPane.showInputDialog(this, "Enter file name:", "New File", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) return;
        final String finalName = name.trim();

        if (isConnected()) {
            sendCreateFileRequest(finalName);
        } else {
            askServerInfoThenDo(() -> sendCreateFileRequest(finalName));
        }
    }

    private void sendCreateFileRequest(String name) {
        wsClient.sendCreateFile(name, response -> SwingUtilities.invokeLater(() -> {
            currentDocId      = response.get("docId").getAsString();
            currentDocName    = response.get("name").getAsString();
            currentEditorCode = response.get("editorCode").getAsString();
            currentViewerCode = response.get("viewerCode").getAsString();
            String sessionId  = response.get("sessionId").getAsString();
            int    uid        = wsClient.getUserID();

            editorPane.clearDocument();
            resetUndoRedo();
            updateTitle();
            connectToServer(lastServerUrl, sessionId, uid, "editor");
            showSaved("Created on server");
        }));
    }

    private void handleOpenFile() {
        if (isConnected()) {
            requestFileList();
        } else {
            askServerInfoThenDo(this::requestFileList);
        }
    }

    private void requestFileList() {
        wsClient.sendListFiles(response -> SwingUtilities.invokeLater(() -> {
            JsonArray arr = response.has("files") ? response.getAsJsonArray("files") : new JsonArray();
            if (arr.size() == 0) {
                JOptionPane.showMessageDialog(this,
                    "No files found on server.", "Open File", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            List<String[]> records = new ArrayList<>();
            String[] displayNames  = new String[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                JsonObject f = arr.get(i).getAsJsonObject();
                records.add(new String[]{
                    f.get("id").getAsString(),
                    f.get("name").getAsString(),
                    f.get("editorCode").getAsString(),
                    f.get("viewerCode").getAsString()
                });
                displayNames[i] = f.get("name").getAsString()
                        + "  [" + f.get("id").getAsString().substring(0, 8) + "...]";
            }

            String chosen = (String) JOptionPane.showInputDialog(
                this, "Select a file to open:", "Open File",
                JOptionPane.PLAIN_MESSAGE, null, displayNames, displayNames[0]);
            if (chosen == null) return;

            int idx = -1;
            for (int i = 0; i < displayNames.length; i++)
                if (displayNames[i].equals(chosen)) { idx = i; break; }
            if (idx < 0) return;

            String[] rec     = records.get(idx);
            String sessionId = ShareCodeManager.extractSessionId(rec[2]);
            int uid          = wsClient.getUserID();

            currentDocId      = rec[0];
            currentDocName    = rec[1];
            currentEditorCode = rec[2];
            currentViewerCode = rec[3];

            editorPane.clearDocument();
            resetUndoRedo();
            updateTitle();

            connectToServer(lastServerUrl, sessionId, uid, "editor");
        }));
    }

    private void handleRenameFile() {
        if (!isConnected()) { showNotConnected(); return; }
        if (currentRole != UserRole.EDITOR) {
            JOptionPane.showMessageDialog(this, "Viewers cannot rename files.", "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (currentDocId == null) {
            JOptionPane.showMessageDialog(this, "No file is currently open.", "Rename File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String newName = JOptionPane.showInputDialog(this, "Enter new name:", currentDocName);
        if (newName == null || newName.trim().isEmpty()) return;

        wsClient.sendRenameFile(newName.trim());
        // Server broadcasts fileRenamed to all — our own callback updates the title
    }

    private void handleDeleteFile() {
        if (!isConnected()) { showNotConnected(); return; }
        if (currentRole != UserRole.EDITOR) {
            JOptionPane.showMessageDialog(this, "Viewers cannot delete files.", "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (currentDocId == null) {
            JOptionPane.showMessageDialog(this, "No file is currently open.", "Delete File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
            "Delete \"" + currentDocName + "\"? This cannot be undone.",
            "Delete File", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        wsClient.sendDeleteFile();
        // Server broadcasts fileDeleted — our callback clears the editor
    }

    // ─── Import / Export ──────────────────────────────────────────────────────

    private void handleImport() {
        BlockCRDT imported = ImportExportManager.importWithChooser(localUserID, editorPane.getClock(), this);
        if (imported == null) return;

        String name = JOptionPane.showInputDialog(this,
            "Enter a name for the imported file:", "Import .txt", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) return;
        final String finalName = name.trim();

        Runnable doImport = () -> wsClient.sendCreateFile(finalName, response -> SwingUtilities.invokeLater(() -> {
            currentDocId      = response.get("docId").getAsString();
            currentDocName    = response.get("name").getAsString();
            currentEditorCode = response.get("editorCode").getAsString();
            currentViewerCode = response.get("viewerCode").getAsString();
            String sessionId  = response.get("sessionId").getAsString();
            int uid           = wsClient.getUserID();

            editorPane.loadFromBlockCRDT(imported);
            resetUndoRedo();
            updateTitle();

            connectToServer(lastServerUrl, sessionId, uid, "editor");
            // Save the imported content once connected (slight delay for connection)
            new Timer("import-save", true).schedule(new TimerTask() {
                @Override public void run() { SwingUtilities.invokeLater(() -> triggerSave()); }
            }, 1000);
        }));

        if (isConnected()) doImport.run();
        else askServerInfoThenDo(doImport);
    }

    private void handleExport() {
        ImportExportManager.exportWithChooser(editorPane.getAsBlockCRDT(), this);
    }

    // ─── Undo / Redo ──────────────────────────────────────────────────────────

    private void handleUndo() {
        if (currentRole != UserRole.EDITOR || !undoRedoManager.canUndo()) return;
        undoRedoManager.undo(editorPane.getCRDT());
        editorPane.refreshDisplay();
    }

    private void handleRedo() {
        if (currentRole != UserRole.EDITOR || !undoRedoManager.canRedo()) return;
        undoRedoManager.redo(editorPane.getCRDT());
        editorPane.refreshDisplay();
    }

    // ─── Share dialog ─────────────────────────────────────────────────────────

    private void handleShare() {
        if (currentDocId == null) {
            JOptionPane.showMessageDialog(this,
                "No file is open. Create or open a file first.",
                "Share", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (currentRole != UserRole.EDITOR) {
            JOptionPane.showMessageDialog(this,
                "Viewers cannot view share codes.", "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(this, "Share Codes", true);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 12, 8, 12);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        dialog.add(new JLabel("Editor Code:"), gbc);
        gbc.gridx = 1;
        JTextField editorField = new JTextField(currentEditorCode != null ? currentEditorCode : "N/A", 12);
        editorField.setEditable(false);
        editorField.setFont(new Font("Monospaced", Font.BOLD, 15));
        dialog.add(editorField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        dialog.add(new JLabel("Viewer Code:"), gbc);
        gbc.gridx = 1;
        JTextField viewerField = new JTextField(currentViewerCode != null ? currentViewerCode : "N/A", 12);
        viewerField.setEditable(false);
        viewerField.setFont(new Font("Monospaced", Font.BOLD, 15));
        dialog.add(viewerField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.anchor = GridBagConstraints.CENTER;
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        dialog.add(closeBtn, gbc);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ─── Join by share code ───────────────────────────────────────────────────

    private void showJoinByCodeDialog() {
        JDialog dialog = new JDialog(this, "Join Session", true);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 12, 8, 12);
        gbc.anchor = GridBagConstraints.WEST;

        JTextField serverField = new JTextField(lastServerUrl, 22);
        JTextField codeField   = new JTextField(12);
        codeField.setFont(new Font("Monospaced", Font.BOLD, 15));
        JTextField userIdField = new JTextField(String.valueOf(localUserID), 8);
        JLabel     roleHint    = new JLabel("(E… = editor, V… = viewer — set by your code)");
        roleHint.setForeground(Color.GRAY);

        gbc.gridx = 0; gbc.gridy = 0; dialog.add(new JLabel("Server URL:"), gbc);
        gbc.gridx = 1;               dialog.add(serverField, gbc);
        gbc.gridx = 0; gbc.gridy = 1; dialog.add(new JLabel("Share Code:"), gbc);
        gbc.gridx = 1;               dialog.add(codeField, gbc);
        gbc.gridx = 0; gbc.gridy = 2; dialog.add(new JLabel("User ID:"), gbc);
        gbc.gridx = 1;               dialog.add(userIdField, gbc);
        gbc.gridx = 0; gbc.gridy = 3; dialog.add(new JLabel("Role:"), gbc);
        gbc.gridx = 1;               dialog.add(roleHint, gbc);

        JButton joinBtn = new JButton("Join");
        joinBtn.addActionListener(e -> {
            String code      = codeField.getText().trim().toUpperCase();
            String serverUrl = serverField.getText().trim();
            String uidStr    = userIdField.getText().trim();

            if (serverUrl.isEmpty() || uidStr.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Please fill in Server URL and User ID.");
                return;
            }
            if (code.length() != 8 || !code.matches("[EV][A-Z0-9]{7}")) {
                JOptionPane.showMessageDialog(dialog,
                    "Invalid code. Editor codes start with E, viewer codes with V.",
                    "Invalid Code", JOptionPane.ERROR_MESSAGE);
                return;
            }
            int uid;
            try { uid = Integer.parseInt(uidStr); }
            catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dialog, "User ID must be a number.");
                return;
            }

            String sessionId = ShareCodeManager.extractSessionId(code);
            String role      = ShareCodeManager.isEditorCode(code) ? "editor" : "viewer";
            dialog.dispose();

            editorPane.clearDocument();
            resetUndoRedo();
            currentDocName = "Shared Document";
            currentDocId   = null;
            updateTitle();
            connectToServer(serverUrl, sessionId, uid, role);
        });

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.anchor = GridBagConstraints.CENTER;
        dialog.add(joinBtn, gbc);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ─── Connect dialog ───────────────────────────────────────────────────────

    private void showConnectDialog() {
        JDialog dialog = new JDialog(this, "Connect to Server", true);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;

        JTextField   serverField  = new JTextField(lastServerUrl, 22);
        JTextField   sessionField = new JTextField(
            currentEditorCode != null ? ShareCodeManager.extractSessionId(currentEditorCode) : "", 16);
        JTextField   userIdField  = new JTextField(16);
        JRadioButton editorRadio  = new JRadioButton("Editor", true);
        JRadioButton viewerRadio  = new JRadioButton("Viewer");
        ButtonGroup  roleGroup    = new ButtonGroup();
        roleGroup.add(editorRadio);
        roleGroup.add(viewerRadio);

        gbc.gridx = 0; gbc.gridy = 0; dialog.add(new JLabel("Server URL:"), gbc);
        gbc.gridx = 1;               dialog.add(serverField, gbc);
        gbc.gridx = 0; gbc.gridy = 1; dialog.add(new JLabel("Session ID:"), gbc);
        gbc.gridx = 1;               dialog.add(sessionField, gbc);
        gbc.gridx = 0; gbc.gridy = 2; dialog.add(new JLabel("User ID:"), gbc);
        gbc.gridx = 1;               dialog.add(userIdField, gbc);
        gbc.gridx = 0; gbc.gridy = 3; dialog.add(new JLabel("Role:"), gbc);
        JPanel rolePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rolePanel.add(editorRadio); rolePanel.add(viewerRadio);
        gbc.gridx = 1; dialog.add(rolePanel, gbc);

        JButton ok = new JButton("Connect");
        ok.addActionListener(e -> {
            String serverUrl = serverField.getText().trim();
            String sid       = sessionField.getText().trim();
            String uidStr    = userIdField.getText().trim();
            if (serverUrl.isEmpty() || sid.isEmpty() || uidStr.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Please fill in all fields.");
                return;
            }
            try {
                int    uid  = Integer.parseInt(uidStr);
                String role = editorRadio.isSelected() ? "editor" : "viewer";
                dialog.dispose();
                connectToServer(serverUrl, sid, uid, role);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dialog, "User ID must be a number.");
            }
        });

        gbc.gridx = 1; gbc.gridy = 4; gbc.anchor = GridBagConstraints.EAST;
        dialog.add(ok, gbc);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ─── Disconnect ───────────────────────────────────────────────────────────

    private void handleDisconnect() {
        if (wsClient != null) {
            wsClient.disconnectFromServer();
            wsClient = null;
        }
        disconnectBtn.setEnabled(false);
        setStatus("Disconnected");
        userPanel.setSession(null);
        editorPane.updateRemoteCursorsFromCharIds(Collections.emptyMap());
    }

    // ─── Auto-save ────────────────────────────────────────────────────────────

    private void startAutoSave() {
        autoSaveTimer = new Timer("auto-save", true);
        autoSaveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                SwingUtilities.invokeLater(() -> triggerSave());
            }
        }, 30_000, 30_000);
    }

    public void triggerSave() {
        if (!isConnected() || currentRole != UserRole.EDITOR) return;
        String crdtJson = new DocumentSerializer().serialize(editorPane.getAsBlockCRDT());

        // Save to server MongoDB
        wsClient.sendSaveFile(crdtJson, () ->
            SwingUtilities.invokeLater(() -> showSaved("Saved")));

        // Save local copy to local_docs/{docId}.json
        if (currentDocId != null) {
            try {
                database.LocalStorage.save(
                    currentDocId, currentDocName,
                    currentEditorCode, currentViewerCode,
                    crdtJson);
            } catch (java.io.IOException e) {
                System.err.println("[EditorWindow] Local save failed: " + e.getMessage());
            }
        }
    }

    private void showSaved(String message) {
        savedLabel.setText(message);
        new Timer("saved-fade", true).schedule(new TimerTask() {
            @Override public void run() {
                SwingUtilities.invokeLater(() -> savedLabel.setText(""));
            }
        }, 3_000);
    }

    // ─── Role mode ────────────────────────────────────────────────────────────

    private void applyRoleMode(boolean canEdit) {
        editorPane.setEditingEnabled(canEdit);
        boldBtn.setEnabled(canEdit);
        italicBtn.setEnabled(canEdit);
        undoBtn.setEnabled(canEdit);
        redoBtn.setEnabled(canEdit);
        shareBtn.setVisible(canEdit);
        roleLabel.setText(canEdit ? "Editor" : "Viewer");
        roleLabel.setForeground(canEdit ? new Color(0, 100, 0) : new Color(110, 110, 110));
    }

    // ─── Server connection ────────────────────────────────────────────────────

    private void connectToServer(String serverUrl, String sid, int uid, String role) {
        if (wsClient != null) {
            wsClient.disconnectFromServer();
            wsClient = null;
        }

        this.lastServerUrl = serverUrl;
        editorPane.setSessionInfo(sid, uid);
        setStatus("Connecting…");

        UserRole userRole = "editor".equalsIgnoreCase(role) ? UserRole.EDITOR : UserRole.VIEWER;
        currentRole = userRole;
        applyRoleMode(userRole == UserRole.EDITOR);

        Runnable onRemoteUpdate = () -> SwingUtilities.invokeLater(() -> {
            try {
                editorPane.refreshDisplay();

                // Update remote cursors
                if (wsClient != null)
                    editorPane.updateRemoteCursorsFromCharIds(wsClient.getRemoteCursorsSnapshot());

                // Sync active-users panel — also prune stale cursors
                if (wsClient != null) {
                    CollaborationSession updated = new CollaborationSession(sid);
                    Map<Integer, String> snap = wsClient.getActiveUsersSnapshot();
                    snap.forEach((userId, r) ->
                        updated.addUser(userId, "EDITOR".equalsIgnoreCase(r)
                            ? UserRole.EDITOR : UserRole.VIEWER));
                    userPanel.setSession(updated);

                    // Remove cursor entries for users who have left
                    editorPane.clearCursorsNotIn(snap.keySet());
                }
            } catch (Exception ex) {
                System.err.println("[EditorWindow] Remote update error: " + ex.getMessage());
            }
            setStatus("Connected");
            disconnectBtn.setEnabled(true);
        });

        try {
            URI uri = new URI(serverUrl);
            wsClient = new WebSocketClient(
                uri, sid, uid, userRole,
                editorPane.getCRDT(), editorPane.getClock(), onRemoteUpdate);

            editorPane.setNetworkSender(new NetworkSenderAdapter(wsClient));
            undoRedoManager.setWebSocketClient(wsClient);

            // File metadata sent by server on join — populates id/name/codes for save & rename
            wsClient.setFileInfoCallback(info -> SwingUtilities.invokeLater(() -> {
                currentDocId   = info.get("docId").getAsString();
                currentDocName = info.get("name").getAsString();
                if (info.has("editorCode")) currentEditorCode = info.get("editorCode").getAsString();
                if (info.has("viewerCode")) currentViewerCode = info.get("viewerCode").getAsString();
                updateTitle();
            }));

            // Document loaded from MongoDB on join (server sends documentState)
            wsClient.setDocumentStateCallback(crdtJson -> SwingUtilities.invokeLater(() -> {
                try {
                    BlockCRDT loaded = new DocumentSerializer().deserialize(crdtJson);
                    if (loaded != null) {
                        editorPane.loadFromBlockCRDT(loaded);
                        resetUndoRedo();
                    }
                } catch (Exception ex) {
                    System.err.println("[EditorWindow] documentState load error: " + ex.getMessage());
                }
            }));

            // Another user renamed this document
            wsClient.setFileRenamedCallback(newName -> SwingUtilities.invokeLater(() -> {
                currentDocName = newName;
                updateTitle();
            }));

            // Another user (or us) deleted this document
            wsClient.setFileDeletedCallback(() -> SwingUtilities.invokeLater(() -> {
                currentDocId = null; currentDocName = "Untitled";
                currentEditorCode = null; currentViewerCode = null;
                editorPane.clearDocument();
                resetUndoRedo();
                updateTitle();
                JOptionPane.showMessageDialog(this,
                    "This document was deleted.", "Document Deleted", JOptionPane.WARNING_MESSAGE);
            }));

            wsClient.setDisconnectCallback(() -> SwingUtilities.invokeLater(() -> {
                setStatus("Disconnected — reconnecting…");
                disconnectBtn.setEnabled(false);
            }));

            wsClient.connectToServer();

        } catch (URISyntaxException e) {
            JOptionPane.showMessageDialog(this, "Invalid server URL.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private boolean isConnected() {
        return wsClient != null && wsClient.isOpen();
    }

    private void showNotConnected() {
        JOptionPane.showMessageDialog(this,
            "Connect to the server first (use the Connect button).",
            "Not Connected", JOptionPane.WARNING_MESSAGE);
    }

    /**
     * If the user is not connected, shows a small dialog asking for the server
     * URL and user ID, connects, then runs {@code action} once the connection
     * opens. If already connected, runs {@code action} immediately.
     */
    private void askServerInfoThenDo(Runnable action) {
        JPanel panel = new JPanel(new GridLayout(2, 2, 8, 6));
        JTextField serverField = new JTextField(lastServerUrl);
        JTextField userIdField = new JTextField(String.valueOf(localUserID));
        panel.add(new JLabel("Server URL:"));  panel.add(serverField);
        panel.add(new JLabel("User ID:"));     panel.add(userIdField);

        int result = JOptionPane.showConfirmDialog(this, panel,
            "Connect to Server", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String serverUrl = serverField.getText().trim();
        String uidStr    = userIdField.getText().trim();
        if (serverUrl.isEmpty() || uidStr.isEmpty()) return;

        int uid;
        try { uid = Integer.parseInt(uidStr); }
        catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "User ID must be a number.");
            return;
        }

        connectThenDo(serverUrl, uid, action);
    }

    /**
     * Connects to the server with a temporary placeholder session, then fires
     * {@code action} the moment the socket opens. Used when a file operation
     * is triggered before the user has manually connected.
     */
    private void connectThenDo(String serverUrl, int uid, Runnable action) {
        if (wsClient != null) { wsClient.disconnectFromServer(); wsClient = null; }

        lastServerUrl = serverUrl;
        setStatus("Connecting…");
        currentRole = UserRole.EDITOR;
        applyRoleMode(true);

        try {
            URI uri = new URI(serverUrl);
            // Use a placeholder session; the real session is set after the file response
            wsClient = new WebSocketClient(uri, "TEMP_" + uid, uid, UserRole.EDITOR,
                editorPane.getCRDT(), editorPane.getClock(),
                () -> SwingUtilities.invokeLater(() -> setStatus("Connected")));

            // Wire all persistent callbacks the same way connectToServer does
            wsClient.setFileInfoCallback(info -> SwingUtilities.invokeLater(() -> {
                currentDocId   = info.get("docId").getAsString();
                currentDocName = info.get("name").getAsString();
                if (info.has("editorCode")) currentEditorCode = info.get("editorCode").getAsString();
                if (info.has("viewerCode")) currentViewerCode = info.get("viewerCode").getAsString();
                updateTitle();
            }));
            wsClient.setDocumentStateCallback(crdtJson -> SwingUtilities.invokeLater(() -> {
                try {
                    BlockCRDT loaded = new DocumentSerializer().deserialize(crdtJson);
                    if (loaded != null) { editorPane.loadFromBlockCRDT(loaded); resetUndoRedo(); }
                } catch (Exception ex) { System.err.println("[EditorWindow] documentState error: " + ex.getMessage()); }
            }));
            wsClient.setFileRenamedCallback(newName -> SwingUtilities.invokeLater(() -> {
                currentDocName = newName; updateTitle();
            }));
            wsClient.setFileDeletedCallback(() -> SwingUtilities.invokeLater(() -> {
                currentDocId = null; currentDocName = "Untitled";
                currentEditorCode = null; currentViewerCode = null;
                editorPane.clearDocument(); resetUndoRedo(); updateTitle();
                JOptionPane.showMessageDialog(this, "This document was deleted.",
                    "Document Deleted", JOptionPane.WARNING_MESSAGE);
            }));
            wsClient.setDisconnectCallback(() -> SwingUtilities.invokeLater(() -> {
                setStatus("Disconnected — reconnecting…");
                disconnectBtn.setEnabled(false);
            }));

            // Fire the caller's action as soon as the socket opens
            wsClient.setOnConnectedCallback(() -> SwingUtilities.invokeLater(() -> {
                disconnectBtn.setEnabled(true);
                action.run();
            }));

            editorPane.setNetworkSender(new NetworkSenderAdapter(wsClient));
            undoRedoManager.setWebSocketClient(wsClient);
            wsClient.connectToServer();

        } catch (URISyntaxException e) {
            JOptionPane.showMessageDialog(this, "Invalid server URL.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateTitle() {
        setTitle("Collaborative Text Editor — " + currentDocName);
        docNameLabel.setText(currentDocName);
    }

    private static JLabel makeSep() { return new JLabel(" | "); }

    private void resetUndoRedo() {
        undoRedoManager = new UndoRedoManager(localUserID, editorPane.getClock());
        editorPane.setUndoRedoManager(undoRedoManager);
        if (wsClient != null) undoRedoManager.setWebSocketClient(wsClient);
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
        if ("Connected".equals(status))
            statusLabel.setForeground(new Color(0, 140, 0));
        else if (status.startsWith("Disconnected"))
            statusLabel.setForeground(new Color(180, 0, 0));
        else
            statusLabel.setForeground(Color.GRAY);
    }

    public void updateSession(CollaborationSession newSession) {
        userPanel.setSession(newSession);
    }

    // ─── NetworkSenderAdapter ────────────────────────────────────────────────

    private static class NetworkSenderAdapter implements EditorPane.NetworkSender {
        private final WebSocketClient wsClient;
        NetworkSenderAdapter(WebSocketClient wsClient) { this.wsClient = wsClient; }

        @Override
        public void sendMessage(String jsonMessage) {
            try {
                JsonObject json   = JsonParser.parseString(jsonMessage).getAsJsonObject();
                String     action = json.get("action").getAsString();
                switch (action) {
                    case "operation": {
                        JsonObject data = json.getAsJsonObject("data");
                        Operation op = OperationSerializer.deserialize(data.toString());
                        wsClient.sendOperation(op);
                        break;
                    }
                    case "cursor": {
                        JsonObject data     = json.getAsJsonObject("data");
                        int afterUserID = data.get("afterUserID").getAsInt();
                        int afterClock  = data.get("afterClock").getAsInt();
                        CharId charId   = (afterUserID == -1 || afterClock == -1)
                                ? null : new CharId(afterClock, afterUserID);
                        wsClient.sendCursorPosition(charId);
                        break;
                    }
                    default:
                        System.err.println("[NetworkSenderAdapter] Unknown action: " + action);
                }
            } catch (Exception e) {
                System.err.println("[NetworkSenderAdapter] Failed: " + e.getMessage());
            }
        }

        @Override public void connect(String serverUrl) { wsClient.connectToServer(); }
        @Override public void disconnect()              { wsClient.disconnectFromServer(); }
        @Override public boolean isConnected()          { return wsClient.isOpen(); }
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            CollaborationSession session = new CollaborationSession("test-session");
            new EditorWindow(1, "test-session", session, true);
        });
    }
}
