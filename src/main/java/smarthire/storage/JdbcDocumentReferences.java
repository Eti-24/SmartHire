package smarthire.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import smarthire.documents.DocumentFailure;
import smarthire.documents.DocumentReferences;
import smarthire.documents.StoredDocument;

public final class JdbcDocumentReferences implements DocumentReferences {
    private final Database database;
    public JdbcDocumentReferences(Database database) { this.database = database; }

    @Override public Map<UUID, StoredDocument.Format> committed() {
        try (var connection = database.connect(); var statement = connection.prepareStatement("SELECT id,format FROM managed_documents"); var rows = statement.executeQuery()) {
            Map<UUID, StoredDocument.Format> result = new LinkedHashMap<>();
            while (rows.next()) { result.put(UUID.fromString(rows.getString(1)), StoredDocument.Format.valueOf(rows.getString(2))); }
            return Map.copyOf(result);
        } catch (SQLException exception) { throw failure(); }
    }

    @Override public Optional<StoredDocument> find(UUID id) {
        try (var connection = database.connect(); var statement = connection.prepareStatement("SELECT * FROM managed_documents WHERE id=?")) {
            statement.setString(1, id.toString());
            try (var row = statement.executeQuery()) {
                return row.next() ? Optional.of(new StoredDocument(id, StoredDocument.Format.valueOf(row.getString("format")), row.getString("original_name"),
                        row.getLong("byte_size"), row.getString("sha256"), row.getString("extracted_text"))) : Optional.empty();
            }
        } catch (SQLException exception) { throw failure(); }
    }

    @Override public void commit(StoredDocument document) {
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            try { insert(connection, document); connection.commit(); }
            catch (SQLException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw failure(); }
    }

    /** Insert with the owning CV/job reference in the SAME transaction; caller owns commit/rollback. */
    public static void insert(Connection connection, StoredDocument document) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO managed_documents(id,format,original_name,byte_size,sha256,extracted_text) VALUES (?,?,?,?,?,?)")) {
            statement.setString(1, document.id().toString()); statement.setString(2, document.format().name()); statement.setString(3, document.originalName());
            statement.setLong(4, document.size()); statement.setString(5, document.sha256()); statement.setString(6, document.text()); statement.executeUpdate();
        }
    }
    private static DocumentFailure failure() { return new DocumentFailure(DocumentFailure.Code.STORAGE, "Cannot check or commit document references. Start MySQL and retry; saved files have been retained."); }
}
