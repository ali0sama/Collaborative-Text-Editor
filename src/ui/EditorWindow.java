package ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import crdt.block.BlockCRDT;
import crdt.character.CharId;
import database.DatabaseManager;
import database.FileRepository;
import filemanagement.FileManager;
import filemanagement.PermissionDeniedException;
import filemanagement.PermissionManager;
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
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class EditorWindow extends JFrame {

    // ─── Document State ───────────────────────────────────────────────────────

    private String currentDocId      = null;
    private String currentDocName    = "Untitled";
    private String currentEditorCode = null;
    private String currentViewerCode = null;
    private UserRole currentRole;                       // for network / display
    private PermissionManager.UserRole permRole;        // for file-op permission checks

    // ─── Backend ─────────────────────────────────────────────────────────────

    private final int             localUserID;
    private final DatabaseManager dbManager;
    private final FileRepository  fileRepository;
    private final FileManager     fileManager;
    private       UndoRedoManager undoRedoManager;

    // ─── UI Components ────────────────────────────────────────────────────────

    private final EditorPane editorPane;
    private final UserPanel  userPanel;

    private final JToggleButton boldBtn;
    private final JToggleButton italicBtn;
    private final JButton       undoBtn;
    private final JButton       redoBtn;
    private final JButton       shareBtn;

    private final JLabel statusLabel;
    private final JLabel roleLabel;
    private final JLabel docNameLabel;
    private final JLabel savedLabel;

    // ─── Network ──────────────────────────────────────────────────────────────

    private WebSocketClient wsClient;

    // ─── Auto-Save ────────────────────────────────────────────────────────────

    private Timer autoSaveTimer;

    // ─── Constructor ─────────────────────────────────────────────────────────

    public EditorWindow(int userID, String sessionID, CollaborationSession session, boolean isEditor) {
        this.localUserID = userID;
        this.currentRole = isEditor ? UserRole.EDITOR : UserRole.VIEWER;
        this.permRole    = isEditor ? PermissionManager.UserRole.EDITOR : PermissionManager.UserRole.VIEWER;

        // --- Backend initialization
        DatabaseManager dm = null;
        FileRepository  fr = null;
        try {
            dm = new DatabaseManager();
            dm.connect();
            fr = new FileRepository(dm);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(null,
                "Database initialization failed: " + e.getMessage(),
                "Startup Error", JOptionPane.ERROR_MESSAGE);
        }
        this.dbManager      = dm;
        this.fileRepository = fr;
        this.fileManager    = (fr != null) ? new FileManager(fr) : null;

        // --- EditorPane (must come before UndoRedoManager so we can pass its Clock)
        editorPane = new EditorPane(userID, sessionID, isEditor);
        userPanel  = new UserPanel(session);

        this.undoRedoManager = new UndoRedoManager(userID, editorPane.getClock());
        editorPane.setUndoRedoManager(undoRedoManager);

        // --- Menu bar
        setJMenuBar(buildMenuBar());

        // --- Toolbar
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

        // --- Status bar
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        docNameLabel = new JLabel(currentDocName);
        docNameLabel.setFont(docNameLabel.getFont().deriveFont(Font.BOLD));

        roleLabel = new JLabel(isEditor ? "Editor" : "Viewer");
        roleLabel.setForeground(isEditor ? new Color(0, 100, 0) : new Color(110, 110, 110));

        statusLabel = new JLabel("Disconnected");
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

        // --- Layout
        setLayout(new BorderLayout());
        add(toolbar,    BorderLayout.NORTH);
        add(editorPane, BorderLayout.CENTER);
        add(userPanel,  BorderLayout.EAST);
        add(southPanel, BorderLayout.SOUTH);

        // --- Keyboard shortcuts (Ctrl+Z / Ctrl+Y)
        bindKeyboardShortcuts();

        // --- Sync Bold/Italic button highlight with caret position
        editorPane.setOnFormattingChange(() -> {
            boldBtn.setSelected(editorPane.isBoldAtCaret());
            italicBtn.setSelected(editorPane.isItalicAtCaret());
        });

        // --- Apply role restrictions to all buttons
        applyRoleMode(isEditor);

        // --- Start 30-second auto-save timer
        startAutoSave();

        // --- Frame setup
        updateTitle();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ─── Menu Bar ─────────────────────────────────────────────────────────────

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem newItem    = new JMenuItem("New File");
        JMenuItem openItem   = new JMenuItem("Open File");
        JMenuItem saveItem   = new JMenuItem("Save");
        JMenuItem renameItem = new JMenuItem("Rename File");
        JMenuItem deleteItem = new JMenuItem("Delete File");

        newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));

        newItem.addActionListener(e    -> handleNewFile());
        openItem.addActionListener(e   -> handleOpenFile());
        saveItem.addActionListener(e   -> triggerSave());
        renameItem.addActionListener(e -> handleRenameFile());
        deleteItem.addActionListener(e -> handleDeleteFile());

        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.add(renameItem);
        fileMenu.add(deleteItem);
        fileMenu.addSeparator();

        JMenuItem importItem = new JMenuItem("Import .txt");
        JMenuItem exportItem = new JMenuItem("Export .txt");
        importItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK));
        importItem.addActionListener(e -> handleImport());
        exportItem.addActionListener(e -> handleExport());

        fileMenu.add(importItem);
        fileMenu.add(exportItem);

        menuBar.add(fileMenu);
        return menuBar;
    }

    // ─── Keyboard Shortcuts ───────────────────────────────────────────────────

    private void bindKeyboardShortcuts() {
        InputMap  im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");

        am.put("undo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { handleUndo(); }
        });
        am.put("redo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { handleRedo(); }
        });
        am.put("save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { triggerSave(); }
        });
    }

    // ─── File Menu Handlers ───────────────────────────────────────────────────

    private void handleNewFile() {
        if (fileManager == null) { showBackendUnavailable(); return; }
        String name = JOptionPane.showInputDialog(this, "Enter file name:", "New File", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) return;

        try {
            String docId = fileManager.createNewFile(name.trim());
            currentDocId   = docId;
            currentDocName = name.trim();
            permRole       = PermissionManager.UserRole.EDITOR;

            // Retrieve the codes generated internally by createNewFile
            for (String[] rec : fileRepository.getAllFileRecords()) {
                if (rec[0].equals(docId)) {
                    currentEditorCode = rec[2];
                    currentViewerCode = rec[3];
                    break;
                }
            }

            editorPane.clearDocument();
            resetUndoRedo();
            updateTitle();
            showSaved("New file created");
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this,
                "Could not create file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleOpenFile() {
        if (fileManager == null) { showBackendUnavailable(); return; }
        try {
            List<String[]> records = fileRepository.getAllFileRecords();
            if (records.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "No saved files found.", "Open File", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            String[] displayNames = new String[records.size()];
            for (int i = 0; i < records.size(); i++) {
                String[] r = records.get(i);
                displayNames[i] = r[1] + "  [" + r[0].substring(0, 8) + "...]";
            }

            String chosen = (String) JOptionPane.showInputDialog(
                this, "Select a file to open:", "Open File",
                JOptionPane.PLAIN_MESSAGE, null, displayNames, displayNames[0]);
            if (chosen == null) return;

            int idx = -1;
            for (int i = 0; i < displayNames.length; i++) {
                if (displayNames[i].equals(chosen)) { idx = i; break; }
            }
            if (idx < 0) return;

            String[] rec    = records.get(idx);
            BlockCRDT loaded = fileManager.openFile(rec[0]);
            if (loaded == null) {
                JOptionPane.showMessageDialog(this,
                    "File content could not be loaded.", "Open File", JOptionPane.ERROR_MESSAGE);
                return;
            }

            currentDocId      = rec[0];
            currentDocName    = rec[1];
            currentEditorCode = rec[2];
            currentViewerCode = rec[3];
            permRole          = PermissionManager.UserRole.EDITOR;

            editorPane.loadFromBlockCRDT(loaded);
            resetUndoRedo();
            updateTitle();
            showSaved("Opened");
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this,
                "Could not open file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleRenameFile() {
        if (currentDocId == null) {
            JOptionPane.showMessageDialog(this,
                "No file is currently open.", "Rename File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            PermissionManager.enforce(permRole, "RENAME");
        } catch (PermissionDeniedException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String newName = JOptionPane.showInputDialog(this, "Enter new name:", currentDocName);
        if (newName == null || newName.trim().isEmpty()) return;

        try {
            fileManager.renameFile(currentDocId, newName.trim());
            currentDocName = newName.trim();
            updateTitle();
            showSaved("Renamed");
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this,
                "Could not rename file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleDeleteFile() {
        if (currentDocId == null) {
            JOptionPane.showMessageDialog(this,
                "No file is currently open.", "Delete File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            PermissionManager.enforce(permRole, "DELETE");
        } catch (PermissionDeniedException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int choice = JOptionPane.showConfirmDialog(this,
            "Delete \"" + currentDocName + "\"? This cannot be undone.",
            "Delete File", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        try {
            fileManager.deleteFile(currentDocId);
            currentDocId      = null;
            currentDocName    = "Untitled";
            currentEditorCode = null;
            currentViewerCode = null;
            editorPane.clearDocument();
            resetUndoRedo();
            updateTitle();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this,
                "Could not delete file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ─── Import / Export ──────────────────────────────────────────────────────

    private void handleImport() {
        BlockCRDT imported = ImportExportManager.importWithChooser(localUserID, editorPane.getClock(), this);
        if (imported == null) return;
        editorPane.loadFromBlockCRDT(imported);
        resetUndoRedo();
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

    // ─── Share Dialog ─────────────────────────────────────────────────────────

    private void handleShare() {
        if (currentDocId == null) {
            JOptionPane.showMessageDialog(this,
                "No file is open. Create or open a file first.",
                "Share", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            PermissionManager.enforce(permRole, "VIEW_CODES");
        } catch (PermissionDeniedException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Permission Denied", JOptionPane.WARNING_MESSAGE);
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
        JTextField editorField = new JTextField(
            currentEditorCode != null ? currentEditorCode : "N/A", 12);
        editorField.setEditable(false);
        editorField.setFont(new Font("Monospaced", Font.BOLD, 15));
        dialog.add(editorField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        dialog.add(new JLabel("Viewer Code:"), gbc);
        gbc.gridx = 1;
        JTextField viewerField = new JTextField(
            currentViewerCode != null ? currentViewerCode : "N/A", 12);
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

    // ─── Join by Code Dialog ──────────────────────────────────────────────────

    private void showJoinByCodeDialog() {
        JDialog dialog = new JDialog(this, "Join by Share Code", true);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 12, 8, 12);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        dialog.add(new JLabel("Enter 8-character share code:"), gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        JTextField codeField = new JTextField(12);
        codeField.setFont(new Font("Monospaced", Font.BOLD, 15));
        dialog.add(codeField, gbc);

        JButton joinBtn = new JButton("Join");
        joinBtn.addActionListener(e -> {
            String code = codeField.getText().trim().toUpperCase();
            if (code.length() != 8 || !code.matches("[A-Z0-9]+")) {
                JOptionPane.showMessageDialog(dialog,
                    "Code must be exactly 8 alphanumeric characters (A-Z, 0-9).",
                    "Invalid Code", JOptionPane.ERROR_MESSAGE);
                return;
            }
            dialog.dispose();

            if (fileRepository == null) { showBackendUnavailable(); return; }
            try {
                Map<String, Object> result = ShareCodeManager.joinByCode(fileRepository, code);
                if (result == null) {
                    JOptionPane.showMessageDialog(this,
                        "No document found for that code.", "Not Found", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                String                     docId    = (String) result.get("docId");
                PermissionManager.UserRole joinRole = (PermissionManager.UserRole) result.get("role");
                boolean                    canEdit  = PermissionManager.canEdit(joinRole);

                BlockCRDT loaded = fileManager.openFile(docId);
                if (loaded == null) {
                    JOptionPane.showMessageDialog(this,
                        "File content could not be loaded.", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // Fetch full metadata for the joined document
                for (String[] rec : fileRepository.getAllFileRecords()) {
                    if (rec[0].equals(docId)) {
                        currentDocName    = rec[1];
                        currentEditorCode = rec[2];
                        currentViewerCode = rec[3];
                        break;
                    }
                }
                currentDocId = docId;
                permRole     = joinRole;
                currentRole  = canEdit ? UserRole.EDITOR : UserRole.VIEWER;

                editorPane.loadFromBlockCRDT(loaded);
                resetUndoRedo();
                applyRoleMode(canEdit);
                updateTitle();
                showSaved("Joined");
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(this,
                    "Could not join: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        gbc.gridx = 0; gbc.gridy = 2; gbc.anchor = GridBagConstraints.CENTER;
        dialog.add(joinBtn, gbc);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ─── Connect to Server Dialog (WebSocket) ─────────────────────────────────

    private void showConnectDialog() {
        JDialog dialog = new JDialog(this, "Connect to Server", true);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;

        JTextField   serverField  = new JTextField("ws://127.0.0.1:8081", 20);
        JTextField   sessionField = new JTextField(16);
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
        rolePanel.add(editorRadio);
        rolePanel.add(viewerRadio);
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

    // ─── Auto-Save ────────────────────────────────────────────────────────────

    private void startAutoSave() {
        autoSaveTimer = new Timer("auto-save", true);
        autoSaveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                SwingUtilities.invokeLater(() -> triggerSave());
            }
        }, 30_000, 30_000);
    }

    public void triggerSave() {
        if (currentDocId == null || fileRepository == null) return;
        if (currentEditorCode == null || currentViewerCode == null) return;
        try {
            fileRepository.saveFile(
                currentDocId, currentDocName,
                currentEditorCode, currentViewerCode,
                editorPane.getAsBlockCRDT());
            showSaved("Saved");
        } catch (SQLException e) {
            System.err.println("[EditorWindow] Auto-save failed: " + e.getMessage());
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

    // ─── Role Mode ────────────────────────────────────────────────────────────

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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void updateTitle() {
        setTitle("Collaborative Text Editor — " + currentDocName);
        docNameLabel.setText(currentDocName);
    }

    private static JLabel makeSep() {
        return new JLabel(" | ");
    }

    private void showBackendUnavailable() {
        JOptionPane.showMessageDialog(this,
            "Database is not available. Check startup logs.",
            "Backend Error", JOptionPane.ERROR_MESSAGE);
    }

    /** Recreates UndoRedoManager after loading a new document so the Clock reference stays valid. */
    private void resetUndoRedo() {
        undoRedoManager = new UndoRedoManager(localUserID, editorPane.getClock());
        editorPane.setUndoRedoManager(undoRedoManager);
        if (wsClient != null) undoRedoManager.setWebSocketClient(wsClient);
    }

    // ─── Server Connection ────────────────────────────────────────────────────

    private void connectToServer(String serverUrl, String sid, int uid, String role) {
        if (wsClient != null) {
            wsClient.disconnectFromServer();
            wsClient = null;
        }

        editorPane.setSessionInfo(sid, uid);
        setStatus("Connecting…");

        UserRole userRole = "editor".equalsIgnoreCase(role) ? UserRole.EDITOR : UserRole.VIEWER;
        currentRole = userRole;
        applyRoleMode(userRole == UserRole.EDITOR);

        Runnable onRemoteUpdate = () -> SwingUtilities.invokeLater(() -> {
            try {
                editorPane.refreshDisplay();
                editorPane.updateRemoteCursorsFromCharIds(wsClient.getRemoteCursorsSnapshot());

                CollaborationSession updated = new CollaborationSession(sid);
                wsClient.getActiveUsersSnapshot().forEach((userId, r) ->
                    updated.addUser(userId, "EDITOR".equalsIgnoreCase(r) ? UserRole.EDITOR : UserRole.VIEWER)
                );
                userPanel.setSession(updated);
            } catch (Exception ex) {
                System.err.println("[EditorWindow] Remote update error: " + ex.getMessage());
            }
            setStatus("Connected");
        });

        try {
            URI uri = new URI(serverUrl);
            wsClient = new WebSocketClient(
                uri, sid, uid, userRole,
                editorPane.getCRDT(),
                editorPane.getClock(),
                onRemoteUpdate
            );
            editorPane.setNetworkSender(new NetworkSenderAdapter(wsClient));
            undoRedoManager.setWebSocketClient(wsClient);
            wsClient.setDisconnectCallback(() ->
                SwingUtilities.invokeLater(() -> setStatus("Disconnected — reconnecting…")));
            wsClient.connectToServer();
        } catch (URISyntaxException e) {
            JOptionPane.showMessageDialog(this, "Invalid server URL.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

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

    // ─── Network Sender Adapter ───────────────────────────────────────────────

    private static class NetworkSenderAdapter implements EditorPane.NetworkSender {

        private final WebSocketClient wsClient;

        NetworkSenderAdapter(WebSocketClient wsClient) {
            this.wsClient = wsClient;
        }

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
                        JsonObject data        = json.getAsJsonObject("data");
                        int        afterUserID = data.get("afterUserID").getAsInt();
                        int        afterClock  = data.get("afterClock").getAsInt();
                        CharId     charId      = (afterUserID == -1 || afterClock == -1)
                                ? null : new CharId(afterClock, afterUserID);
                        wsClient.sendCursorPosition(charId);
                        break;
                    }
                    default:
                        System.err.println("[NetworkSenderAdapter] Unknown action: " + action);
                }
            } catch (Exception e) {
                System.err.println("[NetworkSenderAdapter] Failed to route message: " + e.getMessage());
            }
        }

        @Override public void connect(String serverUrl) { wsClient.connectToServer(); }
        @Override public void disconnect()              { wsClient.disconnectFromServer(); }
        @Override public boolean isConnected()          { return wsClient.isOpen(); }
    }

    // ─── Entry Point (local testing) ─────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            CollaborationSession session = new CollaborationSession("test-session");
            new EditorWindow(1, "test-session", session, true);
        });
    }
}
