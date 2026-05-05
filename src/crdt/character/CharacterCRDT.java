package crdt.character;

import java.util.*;

public class CharacterCRDT {

    private final Map<CharId, CRDTChar> charMap = new HashMap<>();
    private final Map<CharId, List<CRDTChar>> children = new HashMap<>();
    private final Set<CharId> pendingDeletes = new HashSet<>();

    public CharacterCRDT() {
        children.put(null, new ArrayList<>());
    }

    public void insert(CharId id, char value, CharId parentID) {
        if (charMap.containsKey(id))
            return;

        CRDTChar newChar = new CRDTChar(id, value, parentID);
        charMap.put(id, newChar);

        children.computeIfAbsent(parentID, k -> new ArrayList<>()).add(newChar);
        // Descending order: higher counter (newer) comes first among siblings.
        // This ensures a character inserted at position P appears before older
        // characters that share the same parent, matching visual document order.
        children.get(parentID).sort((a, b) -> b.id.compareTo(a.id));

        if (pendingDeletes.remove(id)) {
            newChar.markDeleted();
        }
    }

    public void delete(CharId targetID) {
        CRDTChar target = charMap.get(targetID);
        if (target == null) {
            pendingDeletes.add(targetID);
            return;
        }
        target.markDeleted();
    }

    public String getDocument() {
        StringBuilder sb = new StringBuilder();
        collectText(null, sb);
        return sb.toString();
    }

    private void collectText(CharId parentID, StringBuilder sb) {
        List<CRDTChar> kids = children.get(parentID);
        if (kids == null)
            return;
        for (CRDTChar child : kids) {
            if (!child.isDeleted())
                sb.append(child.value);
            collectText(child.id, sb);
        }
    }

    public List<CRDTChar> getVisibleChars() {
        List<CRDTChar> result = new ArrayList<>();
        collectVisible(null, result);
        return result;
    }

    private void collectVisible(CharId parentID, List<CRDTChar> result) {
        List<CRDTChar> kids = children.get(parentID);
        if (kids == null)
            return;
        for (CRDTChar child : kids) {
            if (!child.isDeleted())
                result.add(child);
            collectVisible(child.id, result);
        }
    }

    public List<CRDTChar> getAllChars() {
        List<CRDTChar> result = new ArrayList<>();
        collectAll(null, result);
        return result;
    }

    private void collectAll(CharId parentID, List<CRDTChar> result) {
        List<CRDTChar> kids = children.get(parentID);
        if (kids == null)
            return;
        for (CRDTChar child : kids) {
            result.add(child);
            collectAll(child.id, result);
        }
    }

    public CRDTChar getChar(CharId id) {
        return charMap.get(id);
    }

    public boolean contains(CharId id) {
        return charMap.containsKey(id);
    }

    public int visibleSize() {
        return getVisibleChars().size();
    }

    public int totalSize() {
        return charMap.size();
    }

    /**
     * Re-inserts a character that was tombstoned by an undo.
     * If the char is already in charMap (tombstoned), un-tombstones it and restores formatting.
     * If it's new, falls through to a normal insert.
     */
    public void reinsert(CharId id, char value, CharId parentID, boolean bold, boolean italic) {
        CRDTChar existing = charMap.get(id);
        if (existing != null) {
            existing.unmarkDeleted();
            existing.setBold(bold);
            existing.setItalic(italic);
        } else {
            insert(id, value, parentID);
            if (bold)   setBold(id, true);
            if (italic) setItalic(id, true);
        }
    }

    public void setBold(CharId id, boolean bold) {
        CRDTChar c = charMap.get(id);
        if (c != null && !c.isDeleted())
            c.setBold(bold);
    }

    public void setItalic(CharId id, boolean italic) {
        CRDTChar c = charMap.get(id);
        if (c != null && !c.isDeleted())
            c.setItalic(italic);
    }

    public void merge(CharacterCRDT remote) {
        for (CRDTChar rc : remote.getAllChars()) {
            if (!charMap.containsKey(rc.id))
                insert(rc.id, rc.value, rc.parentID);
            if (rc.isDeleted())
                charMap.get(rc.id).markDeleted();
        }
    }

    // -----------------------------------------------------------------------
    // Methods added for BlockCRDT support (Member 3)
    // -----------------------------------------------------------------------

    /**
     * Count the number of visible lines (newline characters + 1).
     * Used by BlockCRDT to enforce the 2-10 line block size constraint.
     */
    public int getLineCount() {
        int lines = 1;
        for (CRDTChar c : charMap.values()) {
            if (!c.isDeleted() && c.value == '\n') lines++;
        }
        return lines;
    }

    /**
     * Remove all characters from this CRDT.
     * Used by BlockCRDT.splitBlock() when redistributing characters
     * between blocks after a split.
     */
    public void clear() {
        charMap.clear();
        children.clear();
        pendingDeletes.clear();
        children.put(null, new ArrayList<>());
    }

    /**
     * Bulk-load a list of CRDTChar nodes (including tombstones) into this CRDT.
     * Preserves each character's original parentID so the tree structure is maintained.
     * Skips duplicates (idempotent).
     *
     * Used by BlockCRDT to move characters between blocks during splits and merges.
     */
    public void bulkLoad(List<CRDTChar> chars) {
        for (CRDTChar c : chars) {
            if (!charMap.containsKey(c.id)) {
                charMap.put(c.id, c);
                children.computeIfAbsent(c.parentID, k -> new ArrayList<>()).add(c);
                children.get(c.parentID).sort((a, b) -> b.id.compareTo(a.id));
            }
            if (c.isDeleted() || pendingDeletes.remove(c.id)) {
                charMap.get(c.id).markDeleted();
            }
        }
    }

    public void bulkLoadLinear(List<CRDTChar> chars) {
        clear();
        CharId newParent = null;
        for (CRDTChar oldChar : chars) {
            CRDTChar newChar = new CRDTChar(oldChar.id, oldChar.value, newParent);
            if (oldChar.isDeleted()) newChar.markDeleted();
            newChar.setBold(oldChar.isBold());
            newChar.setItalic(oldChar.isItalic());

            charMap.put(newChar.id, newChar);
            children.computeIfAbsent(newParent, k -> new ArrayList<>()).add(newChar);
            children.get(newParent).sort((a, b) -> b.id.compareTo(a.id));

            newParent = newChar.id;
        }
    }
}
