package smarthire.jobs;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import smarthire.identity.UserError;

public record JobSearch(String title, Long categoryId, String location, BigDecimal minimumSalary,
        BigDecimal maximumSalary, Instant closingFrom, Instant closingBefore) {
    public JobSearch {
        title = normalize(title);
        location = normalize(location);
        if (categoryId != null && categoryId <= 0) { throw new UserError("Choose a valid category."); }
        validateSalary(minimumSalary);
        validateSalary(maximumSalary);
        if (minimumSalary != null && maximumSalary != null && minimumSalary.compareTo(maximumSalary) > 0) {
            throw new UserError("The maximum salary must be at least the minimum salary.");
        }
        if (closingFrom != null && closingBefore != null && !closingFrom.isBefore(closingBefore)) {
            throw new UserError("The closing date range is reversed.");
        }
    }

    public JobSearch(String title, Long categoryId) {
        this(title, categoryId, "", null, null, null, null);
    }

    private static String normalize(String value) {
        String text = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (text.length() > 180) { throw new UserError("Use at most 180 characters per search field."); }
        return text;
    }

    private static void validateSalary(BigDecimal amount) {
        if (amount != null && (amount.signum() < 0 || amount.compareTo(new BigDecimal("9999999999.99")) > 0
                || amount.stripTrailingZeros().scale() > 2)) {
            throw new UserError("Enter salary amounts between 0 and 9,999,999,999.99.");
        }
    }
}
