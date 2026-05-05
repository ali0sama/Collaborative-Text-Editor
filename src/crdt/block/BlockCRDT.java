package crdt.block;

import java.util.ArrayList;
import java.util.List;

public class BlockCRDT {
    private final List<Block> blocks;

    private boolean hasBlock(BlockID id) {
        for (Block b : blocks) {
            if (b.getBlockId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public BlockCRDT() {
        this.blocks = new ArrayList<>();
    }

    public void insertBlock(BlockID id, int index) {
        Block newBlock = new Block(id);
        if (index >= 0 && index <= blocks.size()) {
            blocks.add(index, newBlock);
        }
    }

    public void deleteBlock(BlockID id) {
        for (Block b : blocks) {
            if (b.getBlockId().equals(id)) {
                b.delete();
                break;
            }
        }
    }

    public void moveBlock(BlockID id, int newIndex) {
        Block target = null;
        for (Block b : blocks) {
            if (b.getBlockId().equals(id)) {
                target = b;
                break;
            }
        }
        if (target != null) {
            blocks.remove(target);
            int safeIndex = Math.min(newIndex, blocks.size());
            blocks.add(safeIndex, target);
        }
    }

public List<Block> getVisibleBlocks() {
    List<Block> visible = new ArrayList<>();
    for (Block b : blocks) {
        if (b.isVisible()) {
            visible.add(b);
        }
    }
    return visible;
}

public List<Block> getAllBlocks() {
    return new ArrayList<>(blocks);
}


    public void insertBlock(Block b) {
    if (hasBlock(b.getBlockId())) {
        return;
    }

    int insertAt = 0;
    while (insertAt < blocks.size() && blocks.get(insertAt).getBlockId().compareTo(b.getBlockId()) <= 0) {
        insertAt++;
    }
    this.blocks.add(insertAt, b);
}

public void splitBlock(BlockID targetId, crdt.character.CharId splitPoint, BlockID newBlockId) {
    if (hasBlock(newBlockId)) {
        return;
    }

    for (int i = 0; i < blocks.size(); i++) {
        Block oldBlock = blocks.get(i);
        
        if (oldBlock.getBlockId().equals(targetId)) {
         
            List<crdt.character.CRDTChar> allChars = oldBlock.getContent().getAllChars();
            
            int splitIndex = -1;
            for (int j = 0; j < allChars.size(); j++) {
                if (allChars.get(j).id.equals(splitPoint)) {
                    splitIndex = j;
                    break;
                }
            }

            if (splitIndex != -1) {
               
                List<crdt.character.CRDTChar> stayChars = new ArrayList<>(allChars.subList(0, splitIndex + 1));
                List<crdt.character.CRDTChar> moveChars = new ArrayList<>(allChars.subList(splitIndex + 1, allChars.size()));

               
                Block newBlock = new Block(newBlockId);
                
             
                newBlock.getContent().bulkLoadLinear(moveChars);

            
                oldBlock.getContent().clear();
                oldBlock.getContent().bulkLoadLinear(stayChars);

                blocks.add(i + 1, newBlock);
            }
            return;
        }
    }
}

public String getDocumentText() {
    StringBuilder sb = new StringBuilder();
    for (Block b : getVisibleBlocks()) {
        sb.append(b.getContent().getDocument());
    }
    return sb.toString();
}

/**
 * Merges two blocks by appending all content from secondId into firstId,
 * then tombstoning secondId. Both blocks must be visible.
 *
 * Used for block-merge operations. Characters are relinked linearly so
 * the merged block preserves document order.
 */
public void mergeBlocks(BlockID firstId, BlockID secondId) {
    Block first = null;
    Block second = null;

    for (Block b : blocks) {
        if (b.getBlockId().equals(firstId))  first  = b;
        if (b.getBlockId().equals(secondId)) second = b;
    }

    if (first == null || second == null || !first.isVisible() || !second.isVisible()) return;

    // Collect all chars from both blocks in tree order (tombstones included for CRDT consistency)
    List<crdt.character.CRDTChar> merged = new ArrayList<>();
    merged.addAll(first.getContent().getAllChars());
    merged.addAll(second.getContent().getAllChars());

    // Rebuild first block linearly with combined content
    first.getContent().bulkLoadLinear(merged);

    // Tombstone the second block
    second.delete();
}

/**
 * Creates a new Block whose content is a character-level copy of the source block.
 * Each character in the copy receives a fresh CharId derived from the provided clock
 * and userID, so the copy is CRDT-independent from the original.
 *
 * The caller is responsible for inserting the returned block via insertBlock().
 * Returns null if the source block does not exist or is deleted.
 */
public Block copyBlockContent(BlockID sourceId, BlockID newBlockId, int userID, crdt.utils.Clock clock) {
    if (hasBlock(newBlockId)) return null;

    Block source = null;
    for (Block b : blocks) {
        if (b.getBlockId().equals(sourceId) && b.isVisible()) {
            source = b;
            break;
        }
    }
    if (source == null) return null;

    Block newBlock = new Block(newBlockId);
    List<crdt.character.CRDTChar> visibleChars = source.getContent().getVisibleChars();
    crdt.character.CharId prevId = null;

    for (crdt.character.CRDTChar c : visibleChars) {
        crdt.character.CharId newId = new crdt.character.CharId(clock.tick(), userID);
        newBlock.getContent().insert(newId, c.value, prevId);
        if (c.isBold())   newBlock.getContent().setBold(newId, true);
        if (c.isItalic()) newBlock.getContent().setItalic(newId, true);
        prevId = newId;
    }

    return newBlock;
}

}

