package smarthire.cv;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import smarthire.identity.Role;

public interface CvApprovalRepository {
    <T> T transaction(Function<Store, T> work);
    record Submission(UUID version, long studentId, String name, String email, boolean active,
            String originalName, Instant confirmedAt, CvApproval approval) { }
    record Review(Submission submission, CvProfile cv, boolean current) { }
    interface Store {
        void requireActive(long id, Role role);
        List<Submission> list(CvApproval.Status status);
        Optional<Review> find(UUID version);
        UUID current(long studentId);
        void decide(UUID version, CvApproval approval);
    }
}
