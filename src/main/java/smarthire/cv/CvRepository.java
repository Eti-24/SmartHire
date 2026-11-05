package smarthire.cv;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import smarthire.documents.StoredDocument;
import smarthire.skills.SkillVocabulary;

public interface CvRepository {
    <T> T transaction(long studentId, Function<Store, T> work);

    interface Store {
        Optional<CvProfile> current();
        Optional<CvProfile> find(UUID version);
        List<CvProfile.Version> history();
        SkillVocabulary vocabulary();
        void save(CvProfile profile, StoredDocument newDocument);
        default CvApproval approval(UUID version) {
            if (find(version).isEmpty()) { throw new smarthire.identity.UserError("This CV version is not available to your account."); }
            return CvApproval.pending();
        }
    }
}
