package ui;

import crdt.block.Block;
import crdt.block.BlockCRDT;
import crdt.block.BlockID;
import crdt.character.CRDTChar;
import crdt.character.CharId;
import crdt.character.CharacterCRDT;
import crdt.utils.Clock;
import cursor.CursorTracker;
import undoredo.UndoRedoManager;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import operations.*;
import operations.FormatOperation;
import serializations.OperationSerializer;

public class EditorPane extends JPanel {

    // ─── Network Sender Interface ─────────────────────────────────────────────

    public interface NetworkSender {
        void sendMessage(String jsonMessage);
        void connect(String serverUrl);
        void disconnect();
        boolean isConnected();
    }

    // ─── Fields ──────────────────────────────────────────────────────────────

    private final JTextPane textPane;
    private CharacterCRDT crdt;
    private Clock clock;
    private int localUserID;
    private String sessionID;
    private boolean isEditor;

    private boolean suppressDocumentEvents = false;
    private CharId caretCharId = null;

    // Saved selection so bold/italic buttons work even after losing focus
    private int savedSelStart = 0;
    private int savedSelEnd   = 0;

    // Sticky typing mode (Google Docs / Word style).
    // Swing's internal AttributeTracker resets input attributes on every caret move,
    // so we track the user's intent separately and re-apply it after each keystroke.
    private boolean typingBold   = false;
    private boolean typingItalic = false;
    private boolean justInserted = false; // true for one caret event after each insert

    private NetworkSender networkSender;
    private UndoRedoManager undoRedoManager;

    // Callback fired on caret moves so EditorWindow can sync toolbar button states
    private Runnable onFormattingChange;

    private final CursorTracker cursorTracker = new CursorTracker();

    private static final Color[] CURSOR_COLORS = {
        Color.RED,
        new Color(30, 100, 210),
        new Color(20, 150, 20),
        new Color(210, 120, 0),
        new Color(140, 30, 170),
        new Color(0, 160, 160)
    };

    // ─── Constructor ─────────────────────────────────────────────────────────

    public EditorPane(int userID, String sessionID, boolean isEditor) {
        this.localUserID = userID;
        this.sessionID   = sessionID;
        this.isEditor    = isEditor;
        this.crdt        = new CharacterCRDT();
        this.clock       = new Clock();

        textPane = new JTextPane();
        textPane.setEditable(isEditor);
        textPane.setFont(new Font("Monospaced", Font.PLAIN, 14));

        JScrollPane scrollPane = new JScrollPane(textPane);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        setLayout(new BorderLayout());
        add(scrollPane, BorderLayout.CENTER);

        attachDocumentListener();
        attachCaretListener();
    }

    public void setEditingEnabled(boolean enabled) {
        this.isEditor = enabled;
        textPane.setEditable(enabled);
    }

    // ─── Document Listener ───────────────────────────────────────────────────

    private void attachDocumentListener() {
        textPane.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!isEditor || suppressDocumentEvents) return;
                try {
                    int offset = e.getOffset();
                    int length = e.getLength();
                    String inserted = e.getDocument().getText(offset, length);

                    List<CRDTChar> visible = crdt.getVisibleChars();

                    AttributeSet inputAttrs = textPane.getInputAttributes();
                    boolean bold   = StyleConstants.isBold(inputAttrs);
                    boolean italic = StyleConstants.isItalic(inputAttrs);

                    for (int i = 0; i < length; i++) {
                        char ch  = inserted.charAt(i);
                        int  pos = offset + i;

                        CharId parentID = (pos == 0 || visible.isEmpty())
                                ? null
                                : visible.get(Math.min(pos - 1, visible.size() - 1)).id;

                        int    t  = clock.tick();
                        CharId id = new CharId(t, localUserID);

                        // Newlines carry no formatting — applying bold/italic to \n shifts
                        // the closing marker onto the next line and corrupts the export.
                        boolean charBold   = (ch != '\n') && bold;
                        boolean charItalic = (ch != '\n') && italic;

                        // Build op first; op.apply() inserts the char AND sets bold/italic in CRDT
                        InsertOperation op = new InsertOperation(localUserID, t, ch, parentID, charBold, charItalic);
                        op.apply(crdt);

                        if (undoRedoManager != null) undoRedoManager.recordOperation(op);
                        if (networkSender != null && networkSender.isConnected()) {
                            networkSender.sendMessage(buildOperationEnvelope(op));
                        }

                        caretCharId = id;
                        visible = crdt.getVisibleChars();
                        justInserted = true;
                    }
                } catch (BadLocationException ex) {
                    ex.printStackTrace();
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!isEditor || suppressDocumentEvents) return;
                int offset = e.getOffset();
                int length = e.getLength();

                List<CRDTChar> visible = crdt.getVisibleChars();

                for (int i = 0; i < length; i++) {
                    int idx = offset + i;
                    if (idx >= visible.size()) break;

                    CharId target = visible.get(idx).id;
                    crdt.delete(target);

                    // Always create the op — needed for undo even when offline
                    int t = clock.tick();
                    DeleteOperation op = new DeleteOperation(localUserID, t, target);
                    if (undoRedoManager != null) undoRedoManager.recordOperation(op);
                    if (networkSender != null && networkSender.isConnected()) {
                        networkSender.sendMessage(buildOperationEnvelope(op));
                    }
                }

                List<CRDTChar> after = crdt.getVisibleChars();
                caretCharId = (offset == 0 || after.isEmpty())
                        ? null
                        : after.get(Math.min(offset - 1, after.size() - 1)).id;
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Attribute-only changes from refreshDisplay() — no CRDT action needed
            }
        });
    }

    // ─── Caret Listener ──────────────────────────────────────────────────────

    private void attachCaretListener() {
        textPane.addCaretListener(e -> {
            // Only overwrite saved selection when there is an actual selection.
            // A button click fires a caret event with dot==mark, which would
            // wipe the saved range before applyFormatting can read it.
            int selStart = Math.min(e.getDot(), e.getMark());
            int selEnd   = Math.max(e.getDot(), e.getMark());
            if (selStart != selEnd) {
                savedSelStart = selStart;
                savedSelEnd   = selEnd;
            }

            if (!isEditor || suppressDocumentEvents) return;
            int pos = e.getDot();
            List<CRDTChar> visible = crdt.getVisibleChars();
            caretCharId = (pos == 0 || visible.isEmpty())
                    ? null
                    : visible.get(Math.min(pos - 1, visible.size() - 1)).id;

            if (networkSender != null && networkSender.isConnected()) {
                networkSender.sendMessage(buildCursorEnvelope());
            }

            // Swing's AttributeTracker fires before our listener and resets the
            // input attributes to match the character at the new caret position.
            // When the user just typed, we override that reset to keep typing mode.
            // When the user clicked/moved, we adopt the character's formatting.
            if (selStart == selEnd) {
                MutableAttributeSet inputAttrs = textPane.getInputAttributes();
                if (justInserted) {
                    StyleConstants.setBold(inputAttrs, typingBold);
                    StyleConstants.setItalic(inputAttrs, typingItalic);
                    justInserted = false;
                } else {
                    typingBold   = StyleConstants.isBold(inputAttrs);
                    typingItalic = StyleConstants.isItalic(inputAttrs);
                }
                // No active selection — clear stale saved range so a later B/I click
                // cannot accidentally reformat text the user is no longer looking at.
                savedSelStart = 0;
                savedSelEnd   = 0;
            }

            if (onFormattingChange != null) onFormattingChange.run();
        });
    }

    // ─── Display Refresh ─────────────────────────────────────────────────────

    public void refreshDisplay() {
        // Before rebuilding, anchor caretCharId to the current visual position if not
        // already set. This means remote inserts before our cursor will shift it correctly
        // instead of leaving it at a stale offset.
        if (caretCharId == null) {
            int pos = textPane.getCaretPosition();
            List<CRDTChar> pre = crdt.getVisibleChars();
            if (pos > 0 && !pre.isEmpty()) {
                caretCharId = pre.get(Math.min(pos - 1, pre.size() - 1)).id;
            }
        }

        suppressDocumentEvents = true;
        try {
            List<CRDTChar> chars = crdt.getVisibleChars();
            StyledDocument doc   = textPane.getStyledDocument();

            if (doc.getLength() > 0) doc.remove(0, doc.getLength());

            if (chars.isEmpty()) { renderRemoteCursors(); return; }

            StringBuilder sb = new StringBuilder();
            for (CRDTChar c : chars) sb.append(c.value);
            doc.insertString(0, sb.toString(), null);

            // Apply bold/italic in batched runs for efficiency
            int     groupStart  = 0;
            boolean groupBold   = chars.get(0).isBold();
            boolean groupItalic = chars.get(0).isItalic();

            for (int i = 1; i <= chars.size(); i++) {
                boolean curBold   = (i < chars.size()) ? chars.get(i).isBold()   : !groupBold;
                boolean curItalic = (i < chars.size()) ? chars.get(i).isItalic() : !groupItalic;

                if (curBold != groupBold || curItalic != groupItalic) {
                    SimpleAttributeSet attrs = new SimpleAttributeSet();
                    StyleConstants.setBold(attrs, groupBold);
                    StyleConstants.setItalic(attrs, groupItalic);
                    doc.setCharacterAttributes(groupStart, i - groupStart, attrs, true);
                    groupStart = i;
                    if (i < chars.size()) { groupBold = curBold; groupItalic = curItalic; }
                }
            }

            // Restore caret via CRDT char id (accounts for remote inserts shifting offsets)
            int newPos = 0;
            if (caretCharId != null) {
                for (int i = 0; i < chars.size(); i++) {
                    if (chars.get(i).id.equals(caretCharId)) { newPos = i + 1; break; }
                }
            }
            textPane.setCaretPosition(Math.min(newPos, doc.getLength()));

        } catch (BadLocationException e) {
            e.printStackTrace();
        } finally {
            suppressDocumentEvents = false;
        }
        renderRemoteCursors();
    }

    // ─── Remote Cursor Rendering ─────────────────────────────────────────────

    private void renderRemoteCursors() {
        Highlighter hl = textPane.getHighlighter();
        hl.removeAllHighlights();
        int docLen = textPane.getDocument().getLength();
        if (docLen == 0) return;

        for (Map.Entry<Integer, Integer> entry : cursorTracker.getAll().entrySet()) {
            int   uid      = entry.getKey();
            int   caretPos = Math.max(0, Math.min(entry.getValue(), docLen));
            int   anchor   = Math.max(0, Math.min(caretPos, docLen - 1));
            Color color    = CURSOR_COLORS[uid % CURSOR_COLORS.length];
            try {
                hl.addHighlight(anchor, anchor + 1,
                        new CursorPainter(color, caretPos, "User " + uid));
            } catch (BadLocationException e) {
                // stale cursor — skip
            }
        }
    }

    private static class CursorPainter implements Highlighter.HighlightPainter {
        private static final int BAR_WIDTH  = 2;
        private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 10);

        private final Color  color;
        private final int    caretPos;
        private final String label;

        CursorPainter(Color color, int caretPos, String label) {
            this.color    = color;
            this.caretPos = caretPos;
            this.label    = label;
        }

        @Override @SuppressWarnings("deprecation")
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                int len = c.getDocument().getLength();
                if (len == 0) return;

                // modelToView is valid for positions 0..len inclusive
                int      clamped = Math.max(0, Math.min(caretPos, len));
                Rectangle r      = c.modelToView(clamped);
                if (r == null) return;

                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);

                // Cursor bar — 2px wide, full line height
                g2.setColor(color);
                g2.fillRect(r.x, r.y, BAR_WIDTH, r.height);

                // Label flag above the cursor bar
                g2.setFont(LABEL_FONT);
                FontMetrics fm   = g2.getFontMetrics();
                int         tw   = fm.stringWidth(label);
                int         pad  = 3;
                int         lw   = tw + pad * 2;
                int         lh   = fm.getAscent() + fm.getDescent() + 2;
                int         lx   = r.x;
                int         ly   = r.y - lh;
                if (ly < 0) ly = r.y + r.height;   // flip below if clipped at top

                g2.setColor(color);
                g2.fillRect(lx, ly, lw, lh);
                g2.setColor(Color.WHITE);
                g2.drawString(label, lx + pad, ly + fm.getAscent() + 1);

                g2.dispose();
            } catch (BadLocationException e) { /* ignore */ }
        }
    }

    // ─── Formatting ──────────────────────────────────────────────────────────

    public void applyBold()   { if (!isEditor) return; applyFormatting(true,  false); }
    public void applyItalic() { if (!isEditor) return; applyFormatting(false, true);  }

    private void applyFormatting(boolean toggleBold, boolean toggleItalic) {
        int start = textPane.getSelectionStart();
        int end   = textPane.getSelectionEnd();
        if (start == end) { start = savedSelStart; end = savedSelEnd; }

        if (start != end) {
            List<CRDTChar> visible  = crdt.getVisibleChars();
            List<CRDTChar> selected = new ArrayList<>();
            for (int i = start; i < end && i < visible.size(); i++) selected.add(visible.get(i));
            if (selected.isEmpty()) return;

            if (toggleBold) {
                boolean allBold = selected.stream().allMatch(CRDTChar::isBold);
                for (CRDTChar c : selected) crdt.setBold(c.id, !allBold);
            }
            if (toggleItalic) {
                boolean allItalic = selected.stream().allMatch(CRDTChar::isItalic);
                for (CRDTChar c : selected) crdt.setItalic(c.id, !allItalic);
            }

            // Broadcast one FormatOperation per affected character so peers update their display
            if (networkSender != null && networkSender.isConnected()) {
                for (CRDTChar c : selected) {
                    int t = clock.tick();
                    FormatOperation op = new FormatOperation(localUserID, t, c.id, c.isBold(), c.isItalic());
                    networkSender.sendMessage(buildOperationEnvelope(op));
                }
            }
            refreshDisplay();
        } else {
            // No selection — toggle sticky typing mode
            MutableAttributeSet inputAttrs = textPane.getInputAttributes();
            if (toggleBold) {
                typingBold = !typingBold;
                StyleConstants.setBold(inputAttrs, typingBold);
            }
            if (toggleItalic) {
                typingItalic = !typingItalic;
                StyleConstants.setItalic(inputAttrs, typingItalic);
            }
        }
        if (onFormattingChange != null) onFormattingChange.run();
    }

    public boolean isBoldAtCaret() {
        // When text is selected, reflect the document's character attributes.
        // When no selection, return our tracked typing mode (immune to Swing's resets).
        if (textPane.getSelectionStart() != textPane.getSelectionEnd()) {
            return StyleConstants.isBold(textPane.getInputAttributes());
        }
        return typingBold;
    }

    public boolean isItalicAtCaret() {
        if (textPane.getSelectionStart() != textPane.getSelectionEnd()) {
            return StyleConstants.isItalic(textPane.getInputAttributes());
        }
        return typingItalic;
    }

    public void setOnFormattingChange(Runnable r) { this.onFormattingChange = r; }

    // ─── BlockCRDT Bridge ────────────────────────────────────────────────────

    /** Wraps the current CharacterCRDT in a single-block BlockCRDT for saving/exporting. */
    public BlockCRDT getAsBlockCRDT() {
        BlockCRDT blockCRDT = new BlockCRDT();
        Block block = new Block(new BlockID(1, localUserID));
        block.getContent().bulkLoad(crdt.getAllChars());
        blockCRDT.insertBlock(block);
        return blockCRDT;
    }

    /** Replaces the editor's content with the first visible block(s) from a loaded BlockCRDT. */
    public void loadFromBlockCRDT(BlockCRDT blockCRDT) {
        // clear() reuses the same CharacterCRDT instance so WebSocketClient's reference stays valid
        crdt.clear();
        cursorTracker.clear();
        caretCharId   = null;
        savedSelStart = 0;
        savedSelEnd   = 0;
        typingBold    = false;
        typingItalic  = false;

        for (Block block : blockCRDT.getVisibleBlocks()) {
            crdt.bulkLoad(block.getContent().getAllChars());
        }
        refreshDisplay();
    }

    // ─── Plain Text Helpers ───────────────────────────────────────────────────

    public String getPlainText() { return textPane.getText(); }

    public void clearDocument() { loadPlainText(""); }

    public void loadPlainText(String text) {
        crdt = new CharacterCRDT();
        clock = new Clock();
        cursorTracker.clear();
        caretCharId  = null;
        typingBold   = false;
        typingItalic = false;

        for (int i = 0; i < text.length(); i++) {
            int    t        = clock.tick();
            CharId id       = new CharId(t, localUserID);
            CharId parentID = (i == 0) ? null : new CharId(t - 1, localUserID);
            crdt.insert(id, text.charAt(i), parentID);
        }
        refreshDisplay();
    }

    // ─── Integration Points ───────────────────────────────────────────────────

    public void setNetworkSender(NetworkSender sender) { this.networkSender = sender; }

    public void setUndoRedoManager(UndoRedoManager mgr) { this.undoRedoManager = mgr; }

    public CharacterCRDT getCRDT()  { return crdt;  }
    public Clock         getClock() { return clock; }

    public void setSessionInfo(String sid, int uid) { this.sessionID = sid; this.localUserID = uid; }

    public void updateRemoteCursorsFromCharIds(Map<Integer, CharId> charIdCursors) {
        cursorTracker.clear();
        List<CRDTChar> visible = crdt.getVisibleChars();
        for (Map.Entry<Integer, CharId> entry : charIdCursors.entrySet()) {
            CharId cid = entry.getValue();
            if (cid == null) {
                cursorTracker.update(entry.getKey(), 0);
            } else {
                boolean found = false;
                for (int i = 0; i < visible.size(); i++) {
                    if (visible.get(i).id.equals(cid)) {
                        cursorTracker.update(entry.getKey(), i + 1);
                        found = true; break;
                    }
                }
                if (!found) cursorTracker.update(entry.getKey(), visible.size());
            }
        }
        renderRemoteCursors();
    }

    // ─── Envelope Builders ───────────────────────────────────────────────────

    private String buildOperationEnvelope(Operation op) {
        String opJson = OperationSerializer.serialize(op);
        return "{\"action\":\"operation\""
            + ",\"sessionID\":\"" + sessionID + "\""
            + ",\"userID\":" + localUserID
            + ",\"role\":\"" + (isEditor ? "editor" : "viewer") + "\""
            + ",\"data\":" + opJson + "}";
    }

    private String buildCursorEnvelope() {
        int afterUserID = (caretCharId == null) ? -1 : caretCharId.userID;
        int afterClock  = (caretCharId == null) ? -1 : caretCharId.counter;
        return "{\"action\":\"cursor\""
            + ",\"sessionID\":\"" + sessionID + "\""
            + ",\"userID\":" + localUserID
            + ",\"role\":\"" + (isEditor ? "editor" : "viewer") + "\""
            + ",\"data\":{\"afterUserID\":" + afterUserID + ",\"afterClock\":" + afterClock + "}}";
    }
}
