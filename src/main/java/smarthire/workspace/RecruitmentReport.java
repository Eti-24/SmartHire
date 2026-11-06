package smarthire.workspace;

import java.math.BigDecimal;
import java.time.Instant;
import smarthire.identity.UserError;

public record RecruitmentReport(long submissions, long hired, long shortlistSamples, BigDecimal averageShortlistSeconds,
        long hireSamples, BigDecimal averageHireSeconds, long vacancies, long filled) {
    public record Filter(Instant from, Instant until, Long jobId, Long categoryId) {
        public Filter {
            if (from == null || until == null || !from.isBefore(until)) { throw new UserError("Choose a valid report date range."); }
            if (jobId != null && jobId <= 0 || categoryId != null && categoryId <= 0) {
                throw new UserError("Choose a valid posting and category.");
            }
        }
    }
}
