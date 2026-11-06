package smarthire.applications;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import smarthire.cv.CvRepository;
import smarthire.identity.Profile;
import smarthire.identity.Role;
import smarthire.jobs.JobRepository;

public interface ApplicationRepository {
    <T> T transaction(Function<Store, T> work);
    interface Store {
        Profile activeActor(long id, Role role);
        JobRepository.Store jobs();
        CvRepository.Store cvs(long studentId);
        List<Application> list(long actorId, Role role);
        Optional<Application> lock(UUID id);
        void insert(Application application);
        void status(Application previous, Application.Status status, Role actorRole, Instant time);
        List<ApplicationTimeline.Event> statusEvents(UUID id);
        Optional<Instant> hiredAt(UUID id);
        List<Interview> interviewHistory(UUID applicationId);
        List<Interview> currentInterviews(Long studentId, Long companyId, Long jobId);
        void addInterview(Interview event, long studentId, long companyId);
        RecruiterFeedback feedback(UUID applicationId);
        void saveFeedback(UUID applicationId, RecruiterFeedback feedback);
    }
}
