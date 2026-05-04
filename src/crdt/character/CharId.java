package crdt.character;

import java.util.Objects;

public class CharId implements Comparable<CharId> {

    public final int counter;
    public final int userID;

    public CharId(int counter, int userID) {
        this.counter = counter;
        this.userID = userID;
    }

    // @Override
    // public int compareTo(CharId other) {
    //     if (this.counter != other.counter) {
    //         return Integer.compare(this.counter, other.counter);
    //     }
    //     return Integer.compare(this.userID, other.userID);
    // }


        @Override
    public int compareTo(CharId other) {
        if (this.userID != other.userID) {
            return Integer.compare(this.userID, other.userID);
        }
        return Integer.compare(this.counter, other.counter);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof CharId))
            return false;
        CharId other = (CharId) obj;
        return this.counter == other.counter && this.userID == other.userID;
    }

    @Override
    public int hashCode() {
        return Objects.hash(counter, userID);
    }

    @Override
    public String toString() {
        return "[user=" + userID + ", clock=" + counter + "]";
    }
}