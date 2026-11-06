package smarthire.ui;

import java.awt.BorderLayout;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import smarthire.jobs.JobService;

final class HiringHistoryPanel extends JPanel {
    private final JobService service;
    private final UUID session;
    private final Role role;
    private final Consumer<UUID> openApplication;
    private final JPanel results = new JPanel(new BorderLayout());
    private final javax.swing.JTextArea message = WorkspaceStyle.message("Loading hiring history…");
    private final javax.swing.JButton refresh = WorkspaceStyle.button("Refresh", this::load);

    HiringHistoryPanel(JobService service, UUID session, Role role, Consumer<UUID> openApplication) {
        super(new BorderLayout(0, 10));
        this.service = service;
        this.session = session;
        this.role = role;
        this.openApplication = openApplication;
        var actions = WorkspaceStyle.actions();
        actions.add(refresh);
        actions.add(new javax.swing.JLabel(role == Role.COMPANY ? "Hires by your company" : "Filled postings across SmartHire"));
        add(actions, BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        load();
    }

    private void load() {
        refresh.setEnabled(false);
        message.setText("Loading hiring history…");
        new SwingWorker<List<Row>, Void>() {
            @Override protected List<Row> doInBackground() {
                if (role == Role.COMPANY) {
                    return service.companyHires(session).stream().map(hire -> {
                        String date = hire.hiredAt() == null ? "Date unavailable" : WorkspaceStyle.date(hire.hiredAt());
                        return new Row(hire.title(), hire.applicant(), date, hire.title() + "\n" + hire.applicant() + "\n\n"
                                + date + "\n" + hire.hiredCount() + " of " + hire.vacancyCount() + " vacancies filled\nJob #" + hire.jobId(),
                                hire.applicationId());
                    }).toList();
                }
                return service.hiringHistory(session).stream().map(record -> new Row(record.title(), record.company(), record.category(),
                        record.title() + "\n" + record.company() + "\n\n" + record.category() + " · " + record.location()
                                + "\n" + record.vacancyCount() + " vacancies filled\nJob #" + record.jobId(), null)).toList();
            }

            @Override protected void done() {
                if (!isDisplayable()) { return; }
                refresh.setEnabled(true);
                try {
                    showRecords(get());
                    message.setText("");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    message.setText("Loading interrupted. Try refreshing.");
                } catch (ExecutionException exception) {
                    message.setText(exception.getCause() instanceof UserError error ? error.getMessage() : "Could not load hiring history.");
                }
            }
        }.execute();
    }

    private void showRecords(List<Row> records) {
        boolean company = role == Role.COMPANY;
        var model = new DefaultTableModel(company ? new String[]{"Job", "Applicant", "Hired"}
                : new String[]{"Job", "Company", "Category"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (var record : records) { model.addRow(new Object[]{record.title(), record.participant(), record.context()}); }
        var table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getAccessibleContext().setAccessibleName(company ? "Company hires" : "Filled job postings");
        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        var preview = new WorkspaceStyle.Preview();
        preview.showRecord(company ? "No hires" : "No filled postings", company ? "Your hires appear here."
                : "Postings appear here when all of their vacancies are filled.");
        var open = WorkspaceStyle.button("View application", () -> openApplication.accept(WorkspaceStyle.selected(table, records).applicationId()));
        open.setEnabled(false);
        WorkspaceStyle.previewSelection(table, records, record -> {
            open.setEnabled(record != null && record.applicationId() != null && openApplication != null);
            if (record == null) { preview.showRecord("Hiring history", "Select a record to see its details."); }
            else { preview.showRecord(company ? "Hired" : "Filled", record.preview()); }
        });
        results.removeAll();
        results.add(WorkspaceStyle.split(WorkspaceStyle.table(table), preview), BorderLayout.CENTER);
        if (company && openApplication != null) {
            var actions = WorkspaceStyle.actions();
            actions.add(open);
            results.add(actions, BorderLayout.SOUTH);
        }
        results.revalidate();
        results.repaint();
    }

    private record Row(String title, String participant, String context, String preview, UUID applicationId) { }
}
