package smarthire.documents;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DocumentReferences {
    Map<UUID, StoredDocument.Format> committed();
    Optional<StoredDocument> find(UUID id);
    void commit(StoredDocument document);
}
