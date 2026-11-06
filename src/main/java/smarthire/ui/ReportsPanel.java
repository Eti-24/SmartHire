package smarthire.ui;

import java.awt.BorderLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import smarthire.identity.UserError;
import smarthire.workspace.RecruitmentReport;
import smarthire.workspace.WorkspaceService;

final class ReportsPanel extends JPanel {
    private final WorkspaceService service;
    private final UUID session;
    private final JTextField from;
    private final JTextField through;
    private final JTextField zone;
    private final JTextField posting = new JTextField(8);
    private final JTextField category = new JTextField(8);
    private final JTextArea message = WorkspaceStyle.message("");
    private final JTextArea results = WorkspaceStyle.message("");
    private final JButton run;

    ReportsPanel(WorkspaceService service, UUID session) {
        super(new BorderLayout(0, 8));
        this.service = service;
        this.session = session;
        LocalDate today = LocalDate.now();
        from = new JTextField(today.minusDays(29).toString(), 16);
        through = new JTextField(today.toString(), 16);
        zone = new JTextField(ZoneId.systemDefault().getId(), 16);
        var form = WorkspaceStyle.form();
        field(form, "Submitted from (YYYY-MM-DD)", from);
        field(form, "Submitted through (YYYY-MM-DD)", through);
        field(form, "Report time zone", zone);
        field(form, "Posting ID (optional)", posting);
        field(form, "Category ID (optional)", category);
        run = WorkspaceStyle.button("Run report", this::load);
        form.add(run);
        add(form, BorderLayout.NORTH);
        add(new JScrollPane(results), BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        load();
    }

    private static void field(JPanel form, String label, JTextField value) {
        var name = new JLabel(label);
        name.setLabelFor(value);
        form.add(name);
        form.add(value);
    }

    private void load() {
        RecruitmentReport.Filter filter;
        String period;
        try {
            var start = LocalDate.parse(from.getText().strip());
            var end = LocalDate.parse(through.getText().strip());
            var timeZone = ZoneId.of(zone.getText().strip());
            filter = new RecruitmentReport.Filter(start.atStartOfDay(timeZone).toInstant(),
                    end.plusDays(1).atStartOfDay(timeZone).toInstant(), id(posting), id(category));
            period = start + " through " + end + " (" + timeZone + ")";
        } catch (UserError | IllegalArgumentException | java.time.DateTimeException e) {
            message.setText(e instanceof UserError ? e.getMessage() : "Enter valid dates, a time zone, and whole-number IDs.");
            return;
        }
        run.setEnabled(false);
        message.setText("Loading report…");
        new SwingWorker<RecruitmentReport, Void>() {
            @Override protected RecruitmentReport doInBackground() { return service.recruitmentReport(session, filter); }
            @Override protected void done() {
                if (!isDisplayable()) { return; }
                run.setEnabled(true);
                try {
                    var report = get();
                    results.setText("Application cohort  ·  " + period
                            + "\nRetained applications: " + report.submissions()
                            + "\nCurrently hired: " + ratio(report.hired(), report.submissions())
                            + "\nAverage time to first shortlist: " + duration(report.averageShortlistSeconds(), report.shortlistSamples())
                            + "\nAverage time to hire: " + duration(report.averageHireSeconds(), report.hireSamples())
                            + "\n\nCurrent capacity  ·  all submission dates"
                            + "\nVacancies filled: " + ratio(report.filled(), report.vacancies()));
                    results.setCaretPosition(0);
                    message.setText("The cohort includes withdrawn and rejected applications. Time averages use recorded events only.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    message.setText("Report interrupted. Try again.");
                } catch (ExecutionException e) {
                    message.setText(e.getCause() instanceof UserError error ? error.getMessage() : "Could not load the report.");
                }
            }
        }.execute();
    }

    private static Long id(JTextField field) { return field.getText().isBlank() ? null : Long.valueOf(field.getText().strip()); }
    private static String ratio(long count, long total) {
        return total == 0 ? "No data" : count + " of " + total + " ("
                + BigDecimal.valueOf(count).movePointRight(2).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP) + "%)";
    }
    private static String duration(BigDecimal seconds, long samples) {
        return seconds == null ? "No data" : seconds.divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP)
                + " hours (" + samples + " dated records)";
    }
}
