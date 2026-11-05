package smarthire.workspace;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import smarthire.identity.*;
import static smarthire.workspace.WorkspaceRepository.*;

/** Role and ownership checks live here; monitoring exposes no decision or CV mutation operation. */
public final class WorkspaceService {
    private final IdentityService identity;
    private final WorkspaceRepository repository;
    public WorkspaceService(IdentityService identity, WorkspaceRepository repository) {
        this.identity = identity; this.repository = repository;
    }
    private <T> T access(UUID session, Role role, Function<Store, T> work) {
        Profile actor = role == null ? identity.profile(session) : identity.requireRole(session, role);
        return repository.transaction(store -> { store.requireActive(actor.id(), actor.role()); return work.apply(store); });
    }
    public List<Notice> notices(UUID session) {
        long id = identity.profile(session).id(); return access(session, null, s -> s.notices(id));
    }
    public void markRead(UUID session, UUID noticeId) {
        long id = identity.profile(session).id();
        access(session, null, s -> { if (!s.markRead(id, noticeId)) { throw new UserError("Notification not found in your inbox."); } return null; });
    }
    public Dashboard dashboard(UUID session) {
        Profile actor = identity.profile(session); return access(session, null, s -> s.dashboard(actor.id(), actor.role()));
    }
    public List<AccountSummary> accounts(UUID session) { return access(session, Role.ADMIN, Store::accounts); }
    public void setActive(UUID session, long id, boolean active) {
        access(session, Role.ADMIN, s -> {
            var account = s.accounts().stream().filter(a -> a.id() == id).findFirst().orElseThrow(() -> new UserError("Account not found."));
            if (account.role() == Role.ADMIN) { throw new UserError("Administrator accounts cannot be disabled here."); }
            s.setActive(id, active); return null;
        });
    }
    public List<ApplicationSummary> monitor(UUID session, Filter filter) {
        if (filter == null) { filter = new Filter(null, null, null, null); }
        Filter selected = filter; return access(session, Role.ADMIN, s -> s.monitor(selected));
    }
}
