package crdt.character;

public class CRDTChar {

    public final CharId id;
    public final char value;
    public final CharId parentID;

    private boolean deleted;
    private boolean bold;
    private boolean italic;

    public CRDTChar(CharId id, char value, CharId parentID) {
        this.id = id;
        this.value = value;
        this.parentID = parentID;
        this.deleted = false;
        this.bold = false;
        this.italic = false;
    }

    public void markDeleted() {
        this.deleted = true;
    }

    public void unmarkDeleted() {
        this.deleted = false;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public boolean isBold() {
        return bold;
    }

    public boolean isItalic() {
        return italic;
    }

    public void setBold(boolean b) {
        this.bold = b;
    }

    public void setItalic(boolean i) {
        this.italic = i;
    }

    @Override
    public String toString() {
        return "CRDTChar{id=" + id + ", value='" + value + "', deleted=" + deleted + "}";
    }
}