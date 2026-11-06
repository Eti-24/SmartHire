package smarthire.workspace;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.nio.file.Path;
import smarthire.documents.DocumentFailure;
import smarthire.documents.ManagedDocuments;
import smarthire.identity.*;
import static smarthire.workspace.WorkspaceRepository.*;

/** Role and ownership checks live here; monitoring exposes no decision or CV mutation operation. */
public final class WorkspaceService {
    private final IdentityService identity;
    private final WorkspaceRepository repository;
    private final ManagedDocuments documents;
    public WorkspaceService(IdentityService identity, WorkspaceRepository repository) {
        this(identity, repository, null);
    }
    public WorkspaceService(IdentityService identity, WorkspaceRepository repository, ManagedDocuments documents) {
        this.identity = identity; this.repository = repository; this.documents = documents;
    }
    private <T> T access(UUID session, Role role, Function<Store, T> work) {
        Profile actor = role == null ? identity.profile(session) : identity.requireRole(session, role);
        return repository.transaction(store -> { store.requireActive(actor.id(), actor.role()); return work.apply(store); });
    }
    public List<Notice> notices(UUID session) {
        long id = identity.profile(session).id(); return access(session, null, s -> s.notices(id, java.time.Instant.now()));
    }
    public void markRead(UUID session, UUID noticeId) {
        long id = identity.profile(session).id();
        access(session, null, s -> { if (!s.markRead(id, noticeId)) { throw new UserError("Notification not found in your inbox."); } return null; });
    }
    public Dashboard dashboard(UUID session) {
        Profile actor = identity.profile(session); return access(session, null, s -> s.dashboard(actor.id(), actor.role(), java.time.Instant.now()));
    }
    public RecruitmentReport recruitmentReport(UUID session, RecruitmentReport.Filter filter) {
        Profile actor = identity.profile(session);
        if (actor.role() == Role.STUDENT) { throw new UserError("Recruitment reports are available to companies and admins."); }
        return access(session, actor.role(), store -> store.recruitmentReport(actor.role() == Role.COMPANY ? actor.id() : null, filter));
    }
    public List<AccountSummary> accounts(UUID session) { return access(session, Role.ADMIN, Store::accounts); }
    public void setActive(UUID session, long id, boolean active) {
        var admin = identity.requireRole(session, Role.ADMIN);
        access(session, Role.ADMIN, s -> {
            var account = s.accounts().stream().filter(a -> a.id() == id).findFirst().orElseThrow(() -> new UserError("Account not found."));
            if (account.role() == Role.ADMIN) { throw new UserError("Administrator accounts cannot be disabled here."); }
            if (account.active() == active) { return null; }
            s.setActive(id, active);
            s.audit(admin.id(), new AdminActivity.Change(AdminActivity.Action.ACCOUNT_ACCESS, account.role().name(),
                    Long.toString(id), active ? "ENABLED" : "DISABLED"));
            return null;
        });
    }
    public List<ApplicationSummary> monitor(UUID session, Filter filter) {
        if (filter == null) { filter = new Filter(null, null, null, null); }
        Filter selected = filter; return access(session, Role.ADMIN, s -> s.monitor(selected));
    }

    public List<AdminData.Entry> dataRecords(UUID session, AdminData.Kind kind) {
        return access(session, Role.ADMIN, store -> store.adminData().list(kind));
    }

    public AdminData.Impact eraseImpact(UUID session, AdminData.Target target) {
        return access(session, Role.ADMIN, store -> store.adminData().impact(target));
    }

    public AdminData.EraseResult eraseData(UUID session, AdminData.Impact confirmed) {
        var admin = identity.requireRole(session, Role.ADMIN);
        var target = confirmed.entry().target();
        UUID operation = UUID.randomUUID();
        var originals = access(session, Role.ADMIN, store -> {
            documentStore();
            var erased = store.adminData().erase(confirmed);
            store.audit(admin.id(), event(AdminActivity.Action.ERASE, target, "ERASED", confirmed.applications(), operation));
            return erased;
        });
        int pending = 0;
        for (var original : originals) {
            try { documents.removeUnreferenced(original.id(), original.format()); }
            catch (DocumentFailure exception) { pending++; }
        }
        boolean recorded = originals.isEmpty() || recordOutcome(session, admin.id(), event(AdminActivity.Action.ERASE, target,
                pending == 0 ? "CLEANUP COMPLETE" : "CLEANUP PENDING", (long) pending, operation));
        return new AdminData.EraseResult(pending, recorded);
    }

    public AdminData.ExportResult exportData(UUID session, AdminData.Target target, Path destination) {
        var admin = identity.requireRole(session, Role.ADMIN);
        UUID operation = UUID.randomUUID();
        var validated = access(session, Role.ADMIN, store -> {
            var confirmed = store.adminData().impact(target).entry().target();
            store.audit(admin.id(), event(AdminActivity.Action.EXPORT, confirmed, "STARTED", null, operation));
            return confirmed;
        });
        AdminData.ExportResult result;
        try {
            result = AdminDataArchive.save(destination, archive -> access(session, Role.ADMIN,
                    store -> archive.write(store.adminData(), validated, documentStore())));
        } catch (RuntimeException failure) {
            recordOutcome(session, admin.id(), event(AdminActivity.Action.EXPORT, validated, "FAILED", null, operation));
            throw failure;
        }
        boolean recorded = recordOutcome(session, admin.id(), event(AdminActivity.Action.EXPORT, validated,
                "SAVED", (long) result.unavailableOriginals(), operation));
        return new AdminData.ExportResult(result.unavailableOriginals(), recorded);
    }

    public AdminActivity.Page activity(UUID session, AdminActivity.Filter filter) {
        return access(session, Role.ADMIN, store -> store.activity(filter));
    }

    private boolean recordOutcome(UUID session, long admin, AdminActivity.Change change) {
        try { access(session, Role.ADMIN, store -> { store.audit(admin, change); return null; }); return true; }
        catch (UserError e) { return false; }
    }

    private static AdminActivity.Change event(AdminActivity.Action action, AdminData.Target target, String outcome, Long count, UUID operation) {
        return new AdminActivity.Change(action, target.kind().name(), target.id(), outcome, count, operation);
    }

    private ManagedDocuments documentStore() {
        if (documents == null) { throw new UserError("Document storage is unavailable. Restart SmartHire."); }
        return documents;
    }
}
