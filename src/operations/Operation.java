package operations;

import crdt.character.CharacterCRDT;

public abstract class Operation {

    public final int userID;
    public final int clock;

    public enum Type {
        INSERT, DELETE, FORMAT
    }

    protected Operation(int userID, int clock) {
        this.userID = userID;
        this.clock = clock;
    }

    public abstract Type getType();

    
    public abstract void apply(CharacterCRDT crdt);
}