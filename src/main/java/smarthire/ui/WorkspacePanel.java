package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;
import smarthire.applications.Application;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import smarthire.workspace.WorkspaceService;
import smarthire.workspace.AdminData;
import smarthire.workspace.AdminActivity;
import static smarthire.workspace.WorkspaceRepository.*;

final class WorkspacePanel extends JPanel {
    enum Page { NOTICES, ACCOUNTS, DATA, MONITOR, ACTIVITY }

    private final WorkspaceService service;
    private final UUID session;
    private final Consumer<Long> openNoticeJob;
    private final JPanel body = new JPanel(new BorderLayout(0, 10));
    private final JTextArea message = WorkspaceStyle.message("Loading…");
    private boolean busy;

    WorkspacePanel(WorkspaceService service, UUID session, Page page) {
        this(service, session, page, null);
    }

    WorkspacePanel(WorkspaceService service, UUID session, Page page, Consumer<Long> openNoticeJob) {
        super(new BorderLayout(0, 10));
        this.service = service;
        this.session = session;
        this.openNoticeJob = openNoticeJob;
        add(body, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        switch (page) {
            case NOTICES -> notices();
            case ACCOUNTS -> accounts();
            case DATA -> data(AdminData.Kind.STUDENT, "");
            case MONITOR -> monitor(new Filter(null, null, null, null));
            case ACTIVITY -> activity(new AdminActivity.Filter(null, null, null, null, null));
        }
    }

    static JPanel dashboard(Dashboard data) {
        var panel = new JPanel(new BorderLayout(0, 12));
        var counts = new JPanel(new GridLayout(0, 4, 8, 8));
        data.counts().forEach((label, count) -> {
            int accent = counts.getComponentCount() % WorkspaceStyle.ACCENTS.length;
            var card = new JPanel(new BorderLayout(0, 2));
            WorkspaceStyle.surface(card, 12);
            card.setBackground(WorkspaceStyle.ACCENTS[accent]);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(4, 0, 0, 0, WorkspaceStyle.ACCENTS[accent]), card.getBorder()));
            var number = WorkspaceStyle.heading(Long.toString(count));
            number.setForeground(Color.WHITE);
            number.setFont(number.getFont().deriveFont(28f));
            var caption = WorkspaceStyle.message(label);
            caption.setForeground(Color.WHITE);
            card.add(number, BorderLayout.NORTH);
            card.add(caption, BorderLayout.CENTER);
            counts.add(card);
        });
        panel.add(counts, BorderLayout.NORTH);

        var applications = new JPanel(new BorderLayout(0, 6));
        WorkspaceStyle.surface(applications, 12);
        applications.add(WorkspaceStyle.heading("Application status"), BorderLayout.NORTH);
        if (data.applicationStatuses().values().stream().allMatch(count -> count == 0)) {
            applications.add(WorkspaceStyle.message("Application counts will appear when there are applications to track."));
        } else {
            var dataset = new DefaultCategoryDataset();
            data.applicationStatuses().forEach((label, count) -> dataset.addValue(count, "Applications", label));
            var chart = ChartFactory.createBarChart(null, "", "Applications", dataset, PlotOrientation.HORIZONTAL,
                    false, true, false);
            chart.setBackgroundPaint(Color.WHITE);
            var plot = chart.getCategoryPlot();
            plot.setBackgroundPaint(Color.WHITE);
            plot.setOutlineVisible(false);
            plot.setRangeGridlinePaint(WorkspaceStyle.LINE);
            plot.getRenderer().setSeriesPaint(0, WorkspaceStyle.TEAL);
            plot.getRangeAxis().setStandardTickUnits(org.jfree.chart.axis.NumberAxis.createIntegerTickUnits());
            applications.add(new ChartPanel(chart));
        }
        panel.add(applications, BorderLayout.CENTER);
        return panel;
    }

    private void notices() {
        run(() -> service.notices(session), entries -> {
            var table = table("Status", "Received", "Notification");
            for (var notice : entries) {
                model(table).addRow(new Object[]{notice.readAt() == null ? "New" : "Read",
                        notice.createdAt(), notice.message()});
            }
            table.getColumnModel().getColumn(2).setPreferredWidth(230);
            WorkspaceStyle.statusColumn(table, 0);
            dates(table, 1);
            var preview = new WorkspaceStyle.Preview();
            preview.showRecord("Inbox", "Your notifications will appear here.");
            var read = WorkspaceStyle.button("Read notification", () -> {
                var notice = WorkspaceStyle.selected(table, entries);
                run(() -> { service.markRead(session, notice.id()); return notice.message(); }, text -> {
                    JOptionPane.showMessageDialog(this, WorkspaceStyle.text(text), "Notification", JOptionPane.PLAIN_MESSAGE);
                    notices();
                });
            });
            WorkspaceStyle.primary(read);
            read.setEnabled(false);
            var open = WorkspaceStyle.button("Open matching posting", () -> {
                var notice = WorkspaceStyle.selected(table, entries);
                run(() -> { service.markRead(session, notice.id()); return notice.matchedJobId(); }, openNoticeJob);
            });
            open.setEnabled(false);
            WorkspaceStyle.previewSelection(table, entries, notice -> {
                read.setEnabled(notice != null);
                open.setEnabled(openNoticeJob != null && notice != null && notice.matchedJobId() != null);
                if (notice == null) { preview.showRecord("Inbox", "Select a notification to preview its message."); }
                else { preview.showRecord(notice.readAt() == null ? "New" : "Read",
                        WorkspaceStyle.date(notice.createdAt()) + "\n\n" + notice.message()); }
            });
            var actions = WorkspaceStyle.actions();
            actions.add(read);
            if (openNoticeJob != null) { actions.add(open); }
            actions.add(WorkspaceStyle.button("Refresh", this::notices));
            display(table, preview, actions, null);
            message.setText(entries.isEmpty() ? "Your inbox is empty." : "Opening a notification marks it as read.");
        });
    }

    private void accounts() {
        run(() -> service.accounts(session), entries -> {
            var table = table("Name", "Role", "Access");
            for (var account : entries) {
                model(table).addRow(new Object[]{account.name(), WorkspaceStyle.label(account.role()), access(account)});
            }
            WorkspaceStyle.statusColumn(table, 2);
            var preview = new WorkspaceStyle.Preview();
            preview.showRecord("Accounts", "No accounts to display.");
            var change = WorkspaceStyle.button("Change account access", () -> {
                var account = WorkspaceStyle.selected(table, entries);
                String title = account.active() ? "Disable account" : "Enable account";
                if (JOptionPane.showConfirmDialog(this, title + " for " + account.email() + "?", "Account access",
                        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
                run(() -> { service.setActive(session, account.id(), !account.active()); return true; }, ignored -> accounts());
            });
            change.setEnabled(false);
            WorkspaceStyle.previewSelection(table, entries, account -> {
                change.setEnabled(account != null && account.role() != Role.ADMIN);
                if (account == null) {
                    preview.showRecord("Accounts", "Select an account to inspect access.");
                } else {
                    change.setText(account.active() ? "Disable account" : "Enable account");
                    preview.showRecord(access(account), account.name() + "\n" + account.email() + "\n\n"
                            + WorkspaceStyle.label(account.role()) + " · Account #" + account.id()
                            + (account.role() == Role.ADMIN ? "\n\nAdministrator access is protected." : ""));
                }
            });
            var actions = WorkspaceStyle.actions();
            actions.add(change);
            actions.add(WorkspaceStyle.button("Refresh", this::accounts));
            display(table, preview, actions, null);
            message.setText("Disabling an account blocks future actions. Profiles, jobs and application history are retained.");
        });
    }

    private static String access(AccountSummary account) {
        return account.active() ? "Active" : "Disabled";
    }

    private void data(AdminData.Kind kind, String result) {
        run(() -> service.dataRecords(session, kind), entries -> {
            var type = new JComboBox<>(AdminData.Kind.values());
            type.setSelectedItem(kind);
            type.addActionListener(event -> data((AdminData.Kind) type.getSelectedItem(), ""));
            var controls = WorkspaceStyle.actions();
            controls.add(label("Record type", type));
            controls.add(type);
            controls.add(WorkspaceStyle.button("Refresh", () -> data(kind, "")));

            var table = table("Record", "Details", "ID");
            for (var entry : entries) { model(table).addRow(new Object[]{entry.name(), entry.detail(), entry.target().id()}); }
            table.getColumnModel().getColumn(0).setPreferredWidth(180);
            var preview = new WorkspaceStyle.Preview();
            preview.showRecord("No records", "Choose another record type to view its data.");
            var export = WorkspaceStyle.button("Export data…", () -> exportData(WorkspaceStyle.selected(table, entries)));
            var erase = WorkspaceStyle.button("Permanently erase…", () -> confirmErase(WorkspaceStyle.selected(table, entries), kind));
            erase.setBackground(WorkspaceStyle.DANGER);
            erase.setForeground(Color.WHITE);
            export.setEnabled(false);
            erase.setEnabled(false);
            WorkspaceStyle.previewSelection(table, entries, entry -> {
                export.setEnabled(entry != null);
                erase.setEnabled(entry != null);
                if (entry == null) { preview.showRecord("Data management", "Select a record to export or erase."); }
                else { preview.showRecord(kind.toString(), entry.name() + "\n" + entry.detail() + "\n\nID " + entry.target().id()); }
            });
            var actions = WorkspaceStyle.actions();
            actions.add(export);
            actions.add(erase);
            display(table, preview, actions, controls);
            message.setText(result.isEmpty() ? "Export is optional. Permanent erasure cannot be undone in SmartHire." : result);
        });
    }

    private void exportData(AdminData.Entry entry) {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Export SmartHire data");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ZIP archive", "zip"));
        chooser.setSelectedFile(new java.io.File("smarthire-" + entry.target().kind().name().toLowerCase(Locale.ROOT)
                + "-" + entry.target().id() + ".zip"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
        var file = chooser.getSelectedFile().toPath();
        var destination = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")
                ? file : file.resolveSibling(file.getFileName() + ".zip");
        run(() -> service.exportData(session, entry.target(), destination), result -> {
            String outcome = result.unavailableOriginals() == 0 ? "Export saved." : "Export saved. The archive lists "
                    + result.unavailableOriginals() + " unavailable originals.";
            message.setText(outcome + (result.auditOutcomeRecorded() ? "" : " The activity log could not record the final outcome."));
        });
    }

    private void confirmErase(AdminData.Entry entry, AdminData.Kind kind) {
        run(() -> service.eraseImpact(session, entry.target()), impact -> {
            String prompt = "Permanently erase " + impact.entry().name() + "?\nID " + impact.entry().target().id()
                    + "\n\nJob postings: " + impact.jobs() + "\nCV versions: " + impact.versions()
                    + "\nApplications: " + impact.applications() + "\nReferenced originals: " + impact.originals()
                    + " (shared originals are kept)\nSaved jobs: " + impact.bookmarks()
                    + "\nInterview events: " + impact.interviewEvents() + " · Notices: " + impact.interviewNotices()
                    + "\nRecruiter notes: " + impact.recruiterNotes() + " · Scorecards: " + impact.interviewFeedback()
                    + "\nSurviving postings that will close: " + impact.closures()
                    + "\nAdditional vacancies in postings still accepting applications: " + impact.additionalOpenVacancies()
                    + "\n\nThis action cannot be undone in SmartHire. Export is optional.";
            Object[] options = {"Cancel", "Permanently erase"};
            int choice = JOptionPane.showOptionDialog(this, prompt, "Confirm permanent erase", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (choice != 1) { return; }
            run(() -> service.eraseData(session, impact), result -> {
                String outcome = result.filesPendingCleanup() == 0 ? "Record permanently erased."
                        : "Database records erased. Some files could not be removed. Restart SmartHire to retry cleanup.";
                data(kind, outcome + (result.auditOutcomeRecorded() ? "" : " The activity log could not record the cleanup outcome."));
            });
        });
    }

    private void activity(AdminActivity.Filter filter) {
        run(() -> service.activity(session, filter), page -> {
            var controls = new JPanel(new GridLayout(0, 4, 8, 5));
            var action = new JComboBox<String>();
            action.addItem("All actions");
            for (var type : AdminActivity.Action.values()) { action.addItem(WorkspaceStyle.label(type)); }
            if (filter.action() != null) { action.setSelectedIndex(filter.action().ordinal() + 1); }
            var target = new JTextField(filter.targetId() == null ? "" : filter.targetId(), 8);
            var from = new JTextField(activityDate(filter.from(), 0), 10);
            var through = new JTextField(activityDate(filter.until(), -1), 10);
            controls.add(label("Action", action));
            controls.add(label("Target ID", target));
            controls.add(label("From date (UTC)", from));
            controls.add(label("Through date (UTC)", through));
            controls.add(action); controls.add(target); controls.add(from); controls.add(through);

            var table = table("Time", "Action", "Target", "Outcome");
            dates(table, 0);
            for (var entry : page.entries()) {
                var change = entry.change();
                model(table).addRow(new Object[]{entry.time(), WorkspaceStyle.label(change.action()), change.targetKind() + " " + change.targetId(), change.outcome()});
            }
            var preview = new WorkspaceStyle.Preview();
            preview.showRecord("Activity log", "Dates accept YYYY-MM-DD. Select an event for details.");
            WorkspaceStyle.previewSelection(table, page.entries(), entry -> {
                if (entry == null) { preview.showRecord("Activity log", "Select an event."); return; }
                var change = entry.change();
                preview.showRecord(change.outcome(), WorkspaceStyle.label(change.action()) + "\nAdmin #" + entry.adminId()
                        + "\nTarget " + change.targetKind() + " " + change.targetId() + "\n" + WorkspaceStyle.date(entry.time())
                        + (change.count() == null ? "" : "\nCount: " + change.count())
                        + (change.operationId() == null ? "" : "\nOperation: " + change.operationId()));
            });
            var actions = WorkspaceStyle.actions();
            actions.add(WorkspaceStyle.button("Filter / latest", () -> {
                try {
                    int index = action.getSelectedIndex();
                    var start = parseActivityDate(from.getText(), 0);
                    var until = parseActivityDate(through.getText(), 1);
                    if (start != null && until != null && !until.isAfter(start)) { throw new java.time.DateTimeException("Date order"); }
                    activity(new AdminActivity.Filter(index == 0 ? null : AdminActivity.Action.values()[index - 1],
                            target.getText().isBlank() ? null : target.getText().strip(), start, until, null));
                } catch (java.time.DateTimeException e) { message.setText("Enter dates in order using YYYY-MM-DD."); }
            }));
            var older = WorkspaceStyle.button("Older events", () -> activity(new AdminActivity.Filter(filter.action(), filter.targetId(),
                    filter.from(), filter.until(), page.nextBeforeId())));
            older.setEnabled(page.nextBeforeId() != null);
            actions.add(older);
            display(table, preview, actions, controls);
            if (page.entries().isEmpty()) { message.setText("No activity matches these filters."); }
        });
    }

    private static String activityDate(java.time.Instant instant, long adjustDays) {
        return instant == null ? "" : instant.atOffset(java.time.ZoneOffset.UTC).toLocalDate().plusDays(adjustDays).toString();
    }

    private static java.time.Instant parseActivityDate(String text, long adjustDays) {
        return text.isBlank() ? null : java.time.LocalDate.parse(text.strip()).plusDays(adjustDays)
                .atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
    }

    private void monitor(Filter filter) {
        run(() -> service.monitor(session, filter), entries -> {
            var controls = new JPanel(new GridLayout(0, 4, 8, 0));
            var job = new JTextField(value(filter.jobId()), 6);
            var company = new JTextField(value(filter.companyId()), 6);
            var student = new JTextField(value(filter.studentId()), 6);
            var status = new JComboBox<String>();
            status.addItem("All statuses");
            for (var state : Application.Status.values()) { status.addItem(WorkspaceStyle.label(state)); }
            if (filter.status() != null) { status.setSelectedIndex(filter.status().ordinal() + 1); }
            controls.add(label("Job ID", job));
            controls.add(label("Company account ID", company));
            controls.add(label("Student account ID", student));
            controls.add(label("Application status", status));
            controls.add(job);
            controls.add(company);
            controls.add(student);
            controls.add(status);

            var table = table("Job", "Student", "Status");
            for (var entry : entries) {
                model(table).addRow(new Object[]{entry.job(), entry.student(), WorkspaceStyle.label(entry.status())});
            }
            WorkspaceStyle.statusColumn(table, 2);
            var preview = new WorkspaceStyle.Preview();
            preview.showRecord("No applications found", "Applications that match the selected filters will appear here.");
            var open = WorkspaceStyle.button("View selected record", () -> {
                var id = WorkspaceStyle.selected(table, entries).id();
                run(() -> service.monitor(session, filter).stream().filter(a -> a.id().equals(id)).findFirst()
                        .orElseThrow(() -> new UserError("Application no longer available.")), entry -> {
                    String details = summary(entry) + "\n\nApplication ID " + entry.id()
                            + "\nOriginal policy " + entry.originalPolicy() + "\n\n" + entry.originalExplanation();
                    JOptionPane.showMessageDialog(this, WorkspaceStyle.text(details), "Read-only application record",
                            JOptionPane.PLAIN_MESSAGE);
                });
            });
            WorkspaceStyle.primary(open);
            open.setEnabled(false);
            WorkspaceStyle.previewSelection(table, entries, entry -> {
                open.setEnabled(entry != null);
                if (entry == null) { preview.showRecord("Applications", "Select a record to preview its submission."); }
                else { preview.showRecord(WorkspaceStyle.label(entry.status()), summary(entry)); }
            });
            var actions = WorkspaceStyle.actions();
            actions.add(open);
            actions.add(WorkspaceStyle.button("Filter / refresh", () -> {
                try {
                    int selected = status.getSelectedIndex();
                    monitor(new Filter(id(job), id(company), id(student), selected == 0 ? null
                            : Application.Status.values()[selected - 1]));
                } catch (NumberFormatException e) { message.setText("Enter numeric IDs or leave the fields blank."); }
            }));
            display(table, preview, actions, controls);
            message.setText("Read-only monitoring. Use the optional IDs to narrow the list.");
        });
    }

    private static String summary(ApplicationSummary entry) {
        return entry.job() + " · Job #" + entry.jobId() + "\n"
                + entry.company() + " · Account #" + entry.companyId() + "\n\n"
                + entry.student() + " · Account #" + entry.studentId() + "\n" + entry.email()
                + "\n\n" + WorkspaceStyle.label(entry.status()) + " · Submitted " + WorkspaceStyle.date(entry.submittedAt())
                + "\n\nOriginal submission score\n" + String.format(Locale.ROOT, "%.2f%%", entry.originalScore());
    }

    private static JLabel label(String text, JComponent input) {
        var label = new JLabel(text);
        label.setLabelFor(input);
        return label;
    }

    private static String value(Long id) { return id == null ? "" : id.toString(); }
    private static Long id(JTextField field) { return field.getText().isBlank() ? null : Long.valueOf(field.getText().strip()); }

    private static JTable table(String... columns) {
        var table = new JTable(new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        return table;
    }

    private static DefaultTableModel model(JTable table) { return (DefaultTableModel) table.getModel(); }

    private static void dates(JTable table, int column) {
        table.getColumnModel().getColumn(column).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                setText(WorkspaceStyle.date((java.time.Instant) value));
            }
        });
    }

    private void display(JTable table, WorkspaceStyle.Preview preview, JPanel actions, JPanel controls) {
        body.removeAll();
        if (controls != null) { body.add(controls, BorderLayout.NORTH); }
        body.add(WorkspaceStyle.split(WorkspaceStyle.table(table), preview), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }

    private <T> void run(Callable<T> task, Consumer<T> success) {
        if (busy) { return; }
        busy = true;
        Runnable restore = WorkspaceStyle.disable(body);
        message.setText("Loading…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false;
                if (!isDisplayable()) { return; }
                restore.run();
                try {
                    T result = get();
                    message.setText("");
                    success.accept(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    message.setText("Interrupted. Try again.");
                } catch (ExecutionException e) {
                    message.setText(e.getCause() instanceof UserError ? e.getCause().getMessage()
                            : "Could not load this page. Select it in the sidebar to try again.");
                }
            }
        }.execute();
    }
}
