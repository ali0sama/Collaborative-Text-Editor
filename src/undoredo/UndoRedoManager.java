package undoredo;

import crdt.character.CharacterCRDT;
import crdt.character.CRDTChar;
import crdt.utils.Clock;
import network.WebSocketClient;
import operations.DeleteOperation;
import operations.InsertOperation;
import operations.Operation;

import java.util.ArrayDeque;
import java.util.Deque;

public class UndoRedoManager {

    private static final int MAX_SIZE = 100;

    private final Deque<Operation> undoStack = new ArrayDeque<>();
    private final Deque<Operation> redoStack = new ArrayDeque<>();

    private final int localUserID;
    private final Clock clock;
    private WebSocketClient wsClient;

    public UndoRedoManager(int localUserID, Clock clock) {
        this.localUserID = localUserID;
        this.clock = clock;
    }

    public void setWebSocketClient(WebSocketClient wsClient) {
        this.wsClient = wsClient;
    }

    /**
     * Called after every local insert or delete.
     * Pushes the operation onto the undo stack and clears the redo stack.
     */
    public void recordOperation(Operation op) {
        undoStack.push(op);
        redoStack.clear();
        trimStack(undoStack);
    }

    /**
     * Called when a remote operation arrives so the user can also undo others' changes.
     * Does NOT clear the redo stack (remote ops don't invalidate local redo history).
     */
    public void onRemoteOperation(Operation op) {
        undoStack.push(op);
        trimStack(undoStack);
    }

    /**
     * Reverses the most recent operation on the undo stack.
     * Applies the inverse locally, moves the original to the redo stack,
     * and broadcasts the inverse to peers.
     */
    public void undo(CharacterCRDT crdt) {
        if (undoStack.isEmpty()) return;

        Operation op = undoStack.pop();
        Operation inverse = computeInverse(op, crdt);
        inverse.apply(crdt);
        redoStack.push(op);

        if (wsClient != null) {
            wsClient.sendOperation(inverse);
        }
    }

    /**
     * Re-applies the most recently undone operation.
     * Applies it locally, moves it back to the undo stack,
     * and broadcasts it to peers.
     */
    public void redo(CharacterCRDT crdt) {
        if (redoStack.isEmpty()) return;

        Operation op = redoStack.pop();

        if (op instanceof InsertOperation) {
            // crdt.insert() is a no-op for tombstoned IDs, so use reinsert() instead
            InsertOperation ins = (InsertOperation) op;
            crdt.reinsert(ins.charID, ins.value, ins.parentID, ins.bold, ins.italic);
        } else {
            op.apply(crdt);
        }

        undoStack.push(op);
        trimStack(undoStack);

        if (wsClient != null) {
            wsClient.sendOperation(op);
        }
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    // -------------------------------------------------------------------------

    private void trimStack(Deque<Operation> stack) {
        while (stack.size() > MAX_SIZE) {
            stack.removeLast(); // remove oldest (bottom of stack)
        }
    }

    /**
     * Computes the logical inverse of an operation.
     *
     * Insert inverse: delete the character that was inserted.
     * Delete inverse: re-insert the tombstoned character (still in charMap after deletion).
     */
    private Operation computeInverse(Operation op, CharacterCRDT crdt) {
        if (op instanceof InsertOperation) {
            InsertOperation ins = (InsertOperation) op;
            return new DeleteOperation(localUserID, clock.tick(), ins.charID);
        }

        // DeleteOperation — character is tombstoned but still in charMap
        DeleteOperation del = (DeleteOperation) op;
        CRDTChar original = crdt.getChar(del.targetID);
        if (original == null) {
            // Defensive: character never arrived locally; nothing to invert
            return del;
        }
        return new InsertOperation(
                localUserID,
                clock.tick(),
                original.value,
                original.parentID,
                original.isBold(),
                original.isItalic()
        );
    }
}
