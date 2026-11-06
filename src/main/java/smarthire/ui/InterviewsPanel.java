package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import smarthire.applications.ApplicationService;
import smarthire.applications.ApplicationService.InterviewEntry;
import smarthire.applications.Interview;
import smarthire.identity.Role;
import smarthire.identity.UserError;

final class InterviewsPanel extends JPanel {
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private final ApplicationService service;
    private final UUID session;
    private final Role role;
    private final UUID applicationId;
    private final JPanel body = new JPanel(new BorderLayout(0, 8));
    private final JTextArea message = WorkspaceStyle.message("");

    InterviewsPanel(ApplicationService service, UUID session, Role role, UUID applicationId) {
        super(new BorderLayout(0, 8));
        this.service = service;
        this.session = session;
        this.role = role;
        this.applicationId = applicationId;
        add(body, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        load(() -> {});
    }

    private void load(Runnable change) {
        work(() -> {
            change.run();
            return applicationId == null ? service.interviews(session) : List.of(service.interview(session, applicationId));
        });
    }

    private void work(Callable<List<InterviewEntry>> task) {
        Runnable restore = WorkspaceStyle.disable(body);
        message.setText("Loading interviews…");
        new SwingWorker<List<InterviewEntry>, Void>() {
            @Override protected List<InterviewEntry> doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                if (!isDisplayable()) { return; }
                restore.run();
                try { render(get()); message.setText(""); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Loading interrupted."); }
                catch (ExecutionException e) {
                    message.setText(e.getCause() instanceof UserError error ? error.getMessage() : "Could not load interviews.");
                    if (body.getComponentCount() == 0) { render(List.of()); }
                }
            }
        }.execute();
    }

    private void render(List<InterviewEntry> entries) {
        Instant now = Instant.now();
        var ordered = new ArrayList<>(entries);
        ordered.sort(java.util.Comparator.comparing(entry -> !upcoming(entry.current(), now)));
        var model = new DefaultTableModel(new String[]{"Job", "Participant", "State", "Time"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (var entry : ordered) {
            Interview current = entry.current();
            model.addRow(new Object[]{entry.job(), entry.participant(), label(current, now),
                    current == null ? "" : format(current.slot().start(), current.slot().zone())});
        }
        var table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getAccessibleContext().setAccessibleName("Interviews and past schedules");
        var preview = new WorkspaceStyle.Preview();
        preview.showRecord("Interviews", "Upcoming interviews appear first. Select one to see its details and history.");
        var actions = WorkspaceStyle.actions();
        actions.add(WorkspaceStyle.button("Refresh", () -> load(() -> {})));
        var propose = WorkspaceStyle.button("Propose time", () -> propose(WorkspaceStyle.selected(table, ordered)));
        var cancel = action(table, ordered, "Cancel interview", Interview.Action.CANCEL);
        var accept = action(table, ordered, "Accept", Interview.Action.ACCEPT);
        var decline = action(table, ordered, "Decline", Interview.Action.DECLINE);
        var reschedule = action(table, ordered, "Request another time", Interview.Action.RESCHEDULE);
        for (var button : List.of(propose, cancel, accept, decline, reschedule)) { button.setEnabled(false); }
        if (role == Role.COMPANY) { actions.add(propose); actions.add(cancel); }
        else { actions.add(accept); actions.add(decline); actions.add(reschedule); }
        WorkspaceStyle.previewSelection(table, ordered, entry -> {
            Interview current = entry == null ? null : entry.current();
            boolean open = entry != null && entry.schedulingOpen();
            boolean future = current != null && current.slot().start().isAfter(Instant.now());
            boolean proposed = future && current.status() == Interview.Status.PROPOSED;
            propose.setEnabled(open && (current == null || !current.inProgress(Instant.now())));
            propose.setText(current != null && current.canCancel(Instant.now()) ? "Replace time" : "Propose time");
            cancel.setEnabled(open && current != null && current.canCancel(Instant.now()));
            accept.setEnabled(open && proposed);
            decline.setEnabled(open && proposed);
            reschedule.setEnabled(open && (proposed || future && current.status() == Interview.Status.CONFIRMED));
            if (entry == null) { preview.showRecord("Interviews", "Select an application to see its interview details."); return; }
            if (current == null) { preview.showRecord("No proposal", open ? "The company can propose an interview time." : "Scheduling requires a shortlisted application and a remaining vacancy."); return; }
            var slot = current.slot();
            var details = new StringBuilder(entry.job()).append("\n").append(entry.participant()).append("\n\n")
                    .append(format(slot.start(), slot.zone())).append(" to ").append(format(slot.end(), slot.zone()))
                    .append("\nTime zone: ").append(slot.zone()).append("\n").append(slot.location()).append("\n\nHistory\n");
            for (var event : entry.history()) {
                details.append(WorkspaceStyle.label(event.status())).append(" · ").append(format(event.changedAt(), event.slot().zone()))
                        .append(" · Slot: ").append(format(event.slot().start(), event.slot().zone()));
                if (!event.reason().isBlank()) { details.append(" · ").append(event.reason()); }
                details.append('\n');
            }
            preview.showRecord(label(current, now), details.toString());
        });
        body.removeAll();
        body.add(WorkspaceStyle.split(WorkspaceStyle.table(table), preview), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        body.revalidate();
        body.repaint();
    }

    private JButton action(JTable table, List<InterviewEntry> entries, String label, Interview.Action action) {
        return WorkspaceStyle.button(label, () -> {
            var entry = WorkspaceStyle.selected(table, entries);
            if (entry != null) { change(entry, action, null); }
        });
    }

    private void propose(InterviewEntry entry) {
        var current = entry.current();
        var slot = current == null ? null : current.slot();
        var start = new JTextField(slot == null ? "" : format(slot.start(), slot.zone()), 20);
        var end = new JTextField(slot == null ? "" : format(slot.end(), slot.zone()), 20);
        var zone = new JTextField(slot == null ? "Asia/Dhaka" : slot.zone().getId(), 20);
        var location = new JTextField(slot == null ? "" : slot.location(), 20);
        var form = new JPanel(new GridLayout(0, 2, 6, 6));
        field(form, "Start (YYYY-MM-DD HH:MM)", start);
        field(form, "End (YYYY-MM-DD HH:MM)", end);
        field(form, "Time zone", zone);
        field(form, "Location or meeting link", location);
        while (JOptionPane.showConfirmDialog(this, form, "Propose interview", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                ZoneId selectedZone = ZoneId.of(zone.getText().strip());
                change(entry, Interview.Action.PROPOSE, new Interview.Slot(
                        ApplicationService.interviewTime(LocalDateTime.parse(start.getText().strip(), INPUT), selectedZone),
                        ApplicationService.interviewTime(LocalDateTime.parse(end.getText().strip(), INPUT), selectedZone),
                        selectedZone, location.getText()));
                return;
            } catch (java.time.DateTimeException e) {
                JOptionPane.showMessageDialog(this, "Enter times as YYYY-MM-DD HH:MM and a valid zone such as Asia/Dhaka.");
            } catch (UserError e) { JOptionPane.showMessageDialog(this, e.getMessage()); }
        }
    }

    private static void field(JPanel form, String label, JTextField field) {
        var name = new JLabel(label);
        name.setLabelFor(field);
        form.add(name);
        form.add(field);
    }

    private void change(InterviewEntry entry, Interview.Action action, Interview.Slot slot) {
        int revision = entry.current() == null ? 0 : entry.current().revision();
        load(() -> service.changeInterview(session, entry.applicationId(), revision, action, slot));
    }

    private static boolean upcoming(Interview interview, Instant now) {
        return interview != null && (interview.canCancel(now)
                || interview.status() == Interview.Status.CONFIRMED && interview.slot().end().isAfter(now));
    }

    private static String label(Interview interview, Instant now) {
        if (interview == null) { return "No proposal"; }
        if (interview.status() == Interview.Status.PROPOSED && !interview.slot().start().isAfter(now)) { return "Elapsed proposal"; }
        if (interview.status() == Interview.Status.CONFIRMED && !interview.slot().end().isAfter(now)) { return "Past confirmed interview"; }
        return WorkspaceStyle.label(interview.status());
    }

    private static String format(Instant instant, ZoneId zone) { return INPUT.format(instant.atZone(zone)); }
}
