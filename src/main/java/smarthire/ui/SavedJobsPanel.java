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
import smarthire.identity.UserError;
import smarthire.jobs.JobService;
import smarthire.jobs.JobService.SavedJob;
import smarthire.jobs.JobService.SavedStatus;

final class SavedJobsPanel extends JPanel {
    private final JobService service;
    private final UUID session;
    private final Consumer<Long> openPosting;
    private final Consumer<UUID> openApplication;
    private final JPanel body = new JPanel(new BorderLayout(0, 10));
    private final javax.swing.JTextArea message = WorkspaceStyle.message("");

    SavedJobsPanel(JobService service, UUID session, Consumer<Long> openPosting, Consumer<UUID> openApplication) {
        super(new BorderLayout(0, 10));
        this.service = service;
        this.session = session;
        this.openPosting = openPosting;
        this.openApplication = openApplication;
        add(body, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        load(null);
    }

    private void load(Long removeId) {
        Runnable restore = WorkspaceStyle.disable(body);
        message.setText("Loading saved jobs…");
        new SwingWorker<List<SavedJob>, Void>() {
            @Override protected List<SavedJob> doInBackground() {
                if (removeId != null) { service.removeBookmark(session, removeId); }
                return service.savedJobs(session);
            }

            @Override protected void done() {
                if (!isDisplayable()) { return; }
                restore.run();
                try {
                    showRecords(get());
                    message.setText(removeId == null ? "" : "Saved job removed.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    message.setText("Loading interrupted. Try refreshing.");
                } catch (ExecutionException exception) {
                    message.setText(exception.getCause() instanceof UserError error ? error.getMessage() : "Could not load saved jobs.");
                    if (body.getComponentCount() == 0) { showRecords(List.of()); }
                }
            }
        }.execute();
    }

    private void showRecords(List<SavedJob> records) {
        var model = new DefaultTableModel(new String[]{"Job", "Status"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (var record : records) { model.addRow(new Object[]{record.title(), WorkspaceStyle.label(record.status())}); }
        var table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getAccessibleContext().setAccessibleName("Saved job postings");
        var preview = new WorkspaceStyle.Preview();
        var open = WorkspaceStyle.button("Open job", () -> openPosting.accept(WorkspaceStyle.selected(table, records).jobId()));
        var application = WorkspaceStyle.button("View application",
                () -> openApplication.accept(WorkspaceStyle.selected(table, records).applicationId()));
        var remove = WorkspaceStyle.button("Remove saved job", () -> load(WorkspaceStyle.selected(table, records).jobId()));
        open.setEnabled(false);
        application.setEnabled(false);
        remove.setEnabled(false);
        preview.showRecord("No saved jobs", "Use Save job in Find jobs or Job matches.");
        WorkspaceStyle.previewSelection(table, records, record -> {
            boolean applied = record != null && record.applicationId() != null && openApplication != null;
            open.setEnabled(record != null && record.status() == SavedStatus.AVAILABLE && !applied);
            application.setEnabled(applied);
            remove.setEnabled(record != null);
            if (record == null) { preview.showRecord("Saved jobs", "Select a saved posting."); }
            else { preview.showRecord(WorkspaceStyle.label(record.status()), record.title()); }
        });
        var actions = WorkspaceStyle.actions();
        actions.add(open);
        if (openApplication != null) { actions.add(application); }
        actions.add(remove);
        actions.add(WorkspaceStyle.button("Refresh", () -> load(null)));
        body.removeAll();
        body.add(WorkspaceStyle.split(WorkspaceStyle.table(table), preview), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        body.revalidate();
        body.repaint();
    }
}
