package smarthire.ui;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import smarthire.identity.UserError;
import smarthire.jobs.JobSearch;

final class JobSearchForm {
    private JobSearchForm() {}

    static JobSearch edit(JPanel parent, JobSearch current) {
        ZoneId zone = ZoneId.systemDefault();
        JPanel form = WorkspaceStyle.form();
        var location = field(form, "Location", current.location());
        var minimum = field(form, "Minimum salary", display(current.minimumSalary()));
        var maximum = field(form, "Maximum salary", display(current.maximumSalary()));
        var from = field(form, "Closing from (YYYY-MM-DD)", date(current.closingFrom(), zone, 0));
        var through = field(form, "Closing through (YYYY-MM-DD)", date(current.closingBefore(), zone, -1));
        form.add(new JLabel("Closing dates use " + zone + ". Blank fields are unrestricted."));
        while (JOptionPane.showConfirmDialog(parent, form, "Job search filters",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                return new JobSearch(current.title(), current.categoryId(), location.getText(),
                        amount(minimum), amount(maximum), bound(from, zone, 0), bound(through, zone, 1));
            } catch (UserError | IllegalArgumentException | java.time.DateTimeException exception) {
                JOptionPane.showMessageDialog(parent, exception instanceof UserError ? exception.getMessage()
                        : "Use numeric salary values and dates in YYYY-MM-DD format.", "Check filters", JOptionPane.WARNING_MESSAGE);
            }
        }
        return null;
    }

    private static JTextField field(JPanel form, String label, String text) {
        var value = new JTextField(text, 18);
        var name = new JLabel(label);
        name.setLabelFor(value);
        form.add(name);
        form.add(value);
        return value;
    }

    private static String display(BigDecimal value) { return value == null ? "" : value.toPlainString(); }
    private static BigDecimal amount(JTextField field) {
        return field.getText().isBlank() ? null : new BigDecimal(field.getText().strip());
    }
    private static String date(Instant time, ZoneId zone, int offset) {
        return time == null ? "" : time.atZone(zone).toLocalDate().plusDays(offset).toString();
    }
    private static Instant bound(JTextField field, ZoneId zone, int offset) {
        if (field.getText().isBlank()) { return null; }
        var date = LocalDate.parse(field.getText().strip()).plusDays(offset);
        if (date.getYear() < 1001 || date.getYear() > 9998) { throw new UserError("Choose a date from 1001 through 9998."); }
        return date.atStartOfDay(zone).toInstant();
    }
}
