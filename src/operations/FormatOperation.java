package operations;

import crdt.character.CharId;
import crdt.character.CharacterCRDT;

public class FormatOperation extends Operation {

    public final CharId targetID;
    public final boolean bold;
    public final boolean italic;

    public FormatOperation(int userID, int clock, CharId targetID, boolean bold, boolean italic) {
        super(userID, clock);
        this.targetID = targetID;
        this.bold     = bold;
        this.italic   = italic;
    }

    @Override
    public Type getType() { return Type.FORMAT; }

    @Override
    public void apply(CharacterCRDT crdt) {
        crdt.setBold(targetID, bold);
        crdt.setItalic(targetID, italic);
    }
}
