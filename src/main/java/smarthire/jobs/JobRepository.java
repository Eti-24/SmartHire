package smarthire.jobs;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import smarthire.skills.Skill;
import smarthire.documents.StoredDocument;
import smarthire.matching.ScoringPolicy;

/** One transaction per operation; writes lock the posting before changing it. */
public interface JobRepository {
    <T> T transaction(Function<Store, T> work);

    interface Store {
        boolean active(long userId);
        List<Job.Company> companies();
        void verify(long companyId, boolean verified);
        void audit(long adminId, smarthire.workspace.AdminActivity.Change change);
        List<Job.Category> categories();
        long saveCategory(long id, String name);
        List<Skill> skills();
        void saveSkill(Skill skill);
        List<Job> jobs();
        List<Job> search(JobSearch criteria, Long companyId, Instant availableAt);
        List<SavedSearch> savedSearches(long studentId);
        void saveSearch(long studentId, SavedSearch search);
        void deleteSearch(long studentId, long searchId);
        void notifySavedSearches(long jobId, Instant now);
        List<Job.HiringRecord> hiringHistory(Long companyId);
        List<Job.CompanyHire> companyHires(long companyId);
        List<JobService.SavedJob> savedJobs(long studentId, Instant now);
        void saveBookmark(long studentId, Job job);
        void removeBookmark(long studentId, long jobId);
        Optional<Job> lockJob(long id);
        boolean hasApplications(long jobId);
        ScoringPolicy activePolicy();
        void activatePolicy(ScoringPolicy policy);
        Optional<StoredDocument> circular(long jobId);
        void attach(long jobId, StoredDocument document);
        long saveJob(long companyId, long id, Job.Details details);
        void status(long id, Job.Status status, String decision);
    }
}
