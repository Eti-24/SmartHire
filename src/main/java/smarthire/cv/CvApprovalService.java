package smarthire.cv;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import smarthire.documents.ManagedDocuments;
import smarthire.identity.*;
import static smarthire.cv.CvApprovalRepository.*;

/** Admin review of confirmed CVs; pending drafts are never exposed. */
public final class CvApprovalService {
    private final IdentityService identity;
    private final CvApprovalRepository repository;
    private final ManagedDocuments documents;
    public CvApprovalService(IdentityService identity, CvApprovalRepository repository, ManagedDocuments documents) {
        this.identity = identity; this.repository = repository; this.documents = documents;
    }
    private <T> T access(UUID session, Function<Store, T> work) {
        var admin = identity.requireRole(session, Role.ADMIN);
        return repository.transaction(store -> { store.requireActive(admin.id(), Role.ADMIN); return work.apply(store); });
    }
    public List<Submission> list(UUID session, CvApproval.Status filter) { return access(session, s -> s.list(filter)); }
    public Review open(UUID session, UUID version) { return access(session, s -> find(s, version)); }
    public byte[] download(UUID session, UUID version) { return access(session, s -> documents.read(find(s, version).cv().documentId())); }
    public void decide(UUID session, UUID version, CvApproval.Status status, String reason) {
        var admin = identity.requireRole(session, Role.ADMIN);
        if (status != CvApproval.Status.APPROVED && status != CvApproval.Status.REJECTED) { throw new UserError("Choose Approve or Reject."); }
        String explanation = reason == null ? "" : reason.strip();
        if (explanation.length() > 1000 || status == CvApproval.Status.REJECTED && explanation.isBlank()) { throw new UserError("Rejection needs a reason of 1–1000 characters."); }
        access(session, store -> {
            var review = find(store, version);
            store.requireActive(review.cv().studentId(), Role.STUDENT);
            if (!version.equals(store.current(review.cv().studentId()))) { throw new UserError("This CV has been replaced. Refresh and review the current version."); }
            if (review.submission().approval().status() != CvApproval.Status.PENDING) { throw new UserError("This version already has a decision. Refresh the list."); }
            if (status == CvApproval.Status.APPROVED) { documents.read(review.cv().documentId()); }
            store.decide(version, new CvApproval(status, explanation, admin.id(), Instant.now())); return null;
        });
    }
    private static Review find(Store store, UUID version) { return store.find(version).orElseThrow(() -> new UserError("Confirmed CV version not found.")); }
}
