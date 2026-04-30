package io;

import crdt.block.Block;
import crdt.block.BlockCRDT;
import crdt.block.BlockID;
import crdt.character.CharId;
import crdt.character.CRDTChar;
import crdt.utils.Clock;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ImportExportManager {

    // -------------------------------------------------------------------------
    // Core methods (no UI — testable in isolation)
    // -------------------------------------------------------------------------

    /**
     * Writes the visible content of a BlockCRDT to a plain-text file.
     * Bold runs are wrapped in *...*  and italic runs in _..._ .
     * Both bold+italic together are wrapped in *_..._* .
     * Blocks are separated by newline characters.
     */
    public static void exportToTxt(BlockCRDT crdt, String filePath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(filePath), StandardCharsets.UTF_8))) {

            List<Block> blocks = crdt.getVisibleBlocks();
            for (int b = 0; b < blocks.size(); b++) {
                List<CRDTChar> chars = blocks.get(b).getContent().getVisibleChars();
                writeFormattedChars(writer, chars);
                if (b < blocks.size() - 1) {
                    writer.write('\n');
                }
            }
        }
    }

    /**
     * Reads a .txt file and builds a BlockCRDT from it.
     * Each line in the file becomes one Block.
     * Bold markers (*...*) and italic markers (_..._) are parsed and applied
     * to the corresponding CRDTChar nodes.
     */
    public static BlockCRDT importFromTxt(String filePath, int userID, Clock clock) throws IOException {
        BlockCRDT blockCRDT = new BlockCRDT();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                Block block = new Block(new BlockID(clock.tick(), userID));
                parseLine(line, block, userID, clock);
                blockCRDT.insertBlock(block);
            }
        }

        return blockCRDT;
    }

    // -------------------------------------------------------------------------
    // UI convenience methods (show JFileChooser, catch IOException)
    // -------------------------------------------------------------------------

    /**
     * Opens a save dialog filtered to .txt files, then exports the given BlockCRDT.
     * Shows an error dialog on failure.
     */
    public static void exportWithChooser(BlockCRDT crdt, Component parent) {
        JFileChooser chooser = buildChooser();
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        String path = ensureTxtExtension(chooser.getSelectedFile().getAbsolutePath());
        try {
            exportToTxt(crdt, path);
        } catch (IOException e) {
            showError(parent, e);
        }
    }

    /**
     * Opens an open dialog filtered to .txt files, then imports the selected file.
     * Returns null and shows an error dialog on failure.
     */
    public static BlockCRDT importWithChooser(int userID, Clock clock, Component parent) {
        JFileChooser chooser = buildChooser();
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;

        try {
            return importFromTxt(chooser.getSelectedFile().getAbsolutePath(), userID, clock);
        } catch (IOException e) {
            showError(parent, e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void writeFormattedChars(BufferedWriter writer, List<CRDTChar> chars) throws IOException {
        if (chars.isEmpty()) return;

        // Group into runs with the same (bold, italic) combination
        int i = 0;
        while (i < chars.size()) {
            CRDTChar first = chars.get(i);
            boolean bold = first.isBold();
            boolean italic = first.isItalic();

            // Collect all consecutive chars with matching formatting
            StringBuilder run = new StringBuilder();
            while (i < chars.size() && chars.get(i).isBold() == bold && chars.get(i).isItalic() == italic) {
                run.append(chars.get(i).value);
                i++;
            }

            // Emit with markers
            if (bold && italic) {
                writer.write("*_");
                writer.write(run.toString());
                writer.write("_*");
            } else if (bold) {
                writer.write('*');
                writer.write(run.toString());
                writer.write('*');
            } else if (italic) {
                writer.write('_');
                writer.write(run.toString());
                writer.write('_');
            } else {
                writer.write(run.toString());
            }
        }
    }

    /**
     * Parses bold (*...*) and italic (_..._) markers from a line and inserts
     * each character into the block's CharacterCRDT with the correct formatting flags.
     */
    private static void parseLine(String line, Block block, int userID, Clock clock) {
        boolean inBold = false;
        boolean inItalic = false;
        CharId prevCharId = null;

        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);

            // Detect *_ or _* as combined bold+italic open/close markers
            if (ch == '*' && i + 1 < line.length() && line.charAt(i + 1) == '_') {
                inBold = true;
                inItalic = true;
                i += 2;
                continue;
            }
            if (ch == '_' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                inBold = false;
                inItalic = false;
                i += 2;
                continue;
            }

            if (ch == '*') {
                inBold = !inBold;
                i++;
                continue;
            }
            if (ch == '_') {
                inItalic = !inItalic;
                i++;
                continue;
            }

            // Regular character — insert into the block's CRDT
            CharId charId = new CharId(clock.tick(), userID);
            block.getContent().insert(charId, ch, prevCharId);
            if (inBold)   block.getContent().setBold(charId, true);
            if (inItalic) block.getContent().setItalic(charId, true);
            prevCharId = charId;
            i++;
        }
    }

    private static JFileChooser buildChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Text files (*.txt)", "txt"));
        chooser.setAcceptAllFileFilterUsed(false);
        return chooser;
    }

    private static String ensureTxtExtension(String path) {
        return path.endsWith(".txt") ? path : path + ".txt";
    }

    private static void showError(Component parent, IOException e) {
        JOptionPane.showMessageDialog(
                parent,
                "File error: " + e.getMessage(),
                "File Error",
                JOptionPane.ERROR_MESSAGE
        );
    }
}
