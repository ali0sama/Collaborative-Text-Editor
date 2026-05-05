package database;

import crdt.block.BlockCRDT;

import java.sql.SQLException;
import java.util.List;

public class FileRepository {

    private final DatabaseManager dbManager;
    private final DocumentSerializer serializer;

    public FileRepository(DatabaseManager dbManager) {
        this.dbManager  = dbManager;
        this.serializer = new DocumentSerializer();
    }

    public void saveFile(String docId, String name, String editorCode, String viewerCode, BlockCRDT crdt)
            throws SQLException {
        String json = serializer.serialize(crdt);
        dbManager.saveDocument(docId, name, editorCode, viewerCode);
        dbManager.saveCRDTState(docId, json);
    }

    public BlockCRDT loadFile(String docId) throws SQLException {
        String json = dbManager.loadCRDTState(docId);
        if (json == null) return null;
        return serializer.deserialize(json);
    }

    /**
     * Returns all document records as String arrays: [id, name, editorCode, viewerCode]
     */
    public List<String[]> getAllFileRecords() throws SQLException {
        return dbManager.loadAllDocuments();
    }

    public void renameFile(String docId, String newName) throws SQLException {
        dbManager.renameDocument(docId, newName);
    }

    public void deleteFile(String docId) throws SQLException {
        dbManager.deleteDocument(docId);
    }
}
