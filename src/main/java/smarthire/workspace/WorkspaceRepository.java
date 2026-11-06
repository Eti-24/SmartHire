package smarthire.workspace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import smarthire.identity.Role;
import smarthire.applications.Application;

public interface WorkspaceRepository {
    <T> T transaction(Function<Store, T> work);

    record Notice(UUID id, String message, Instant createdAt, Instant readAt, Long matchedJobId) { }
    record AccountSummary(long id, String name, String email, Role role, boolean active) { }
    record ApplicationSummary(UUID id, long jobId, String job, long companyId, String company,
            long studentId, String student, String email, Application.Status status, Instant submittedAt,
            double originalScore, String originalPolicy, String originalExplanation) { }
    record Dashboard(Map<String, Long> counts, Map<String, Long> applicationStatuses) {
        public Dashboard { counts = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(counts));
            applicationStatuses = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(applicationStatuses)); }
    }
    record Filter(Long jobId, Long companyId, Long studentId, Application.Status status) { }

    interface Store {
        void requireActive(long id, Role role);
        List<Notice> notices(long userId, Instant now);
        boolean markRead(long userId, UUID noticeId);
        List<AccountSummary> accounts();
        void setActive(long id, boolean active);
        AdminData.Store adminData();
        void audit(long adminId, AdminActivity.Change change);
        AdminActivity.Page activity(AdminActivity.Filter filter);
        List<ApplicationSummary> monitor(Filter filter);
        Dashboard dashboard(long userId, Role role, Instant now);
        RecruitmentReport recruitmentReport(Long companyId, RecruitmentReport.Filter filter);
    }
}
