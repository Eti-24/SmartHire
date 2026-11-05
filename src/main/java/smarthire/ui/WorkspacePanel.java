package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.data.category.DefaultCategoryDataset;
import smarthire.applications.Application;
import smarthire.identity.UserError;
import smarthire.workspace.WorkspaceService;
import static smarthire.workspace.WorkspaceRepository.*;

final class WorkspacePanel extends JPanel {
    private final WorkspaceService service;
    private final UUID session;
    private final JPanel body = new JPanel(new BorderLayout(8, 8));
    private final JLabel message = new JLabel("Loading…");
    private boolean busy, departed;

    WorkspacePanel(WorkspaceService service, UUID session, String page, Runnable back) {
        super(new BorderLayout(8, 8)); this.service = service; this.session = session;
        var home = new JButton("Back to overview"); home.addActionListener(e -> { departed = true; back.run(); });
        add(home, BorderLayout.NORTH); add(body, BorderLayout.CENTER); add(message, BorderLayout.SOUTH);
        switch (page) { case "accounts" -> accounts(); case "monitor" -> monitor(new Filter(null, null, null, null)); default -> notices(); }
    }
    static JPanel dashboard(Dashboard data) {
        var panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(Color.WHITE);
        var counts = new JTextArea(); counts.setEditable(false); counts.setFont(new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 16));
        data.counts().forEach((label, count) -> counts.append(label + ": " + count + "\n"));
        panel.add(counts, BorderLayout.NORTH);
        var dataset = new DefaultCategoryDataset(); data.applicationStatuses().forEach((label, count) -> dataset.addValue(count, "Applications", label));
        var chart = ChartFactory.createBarChart("Application status", "", "Count", dataset);
        chart.setBackgroundPaint(Color.WHITE); chart.removeLegend();
        chart.getCategoryPlot().setBackgroundPaint(Color.WHITE);
        chart.getCategoryPlot().setRangeGridlinePaint(new Color(220, 230, 237));
        chart.getCategoryPlot().getRenderer().setSeriesPaint(0, new Color(8, 127, 140));
        chart.getCategoryPlot().getRangeAxis().setStandardTickUnits(org.jfree.chart.axis.NumberAxis.createIntegerTickUnits());
        var view = new ChartPanel(chart); view.setPreferredSize(new Dimension(520, 280));
        panel.add(view, BorderLayout.CENTER); return panel;
    }
    private void notices() {
        run(() -> service.notices(session), entries -> {
            var table = table(new String[]{"Read", "Date", "Notification"});
            for (var n : entries) { model(table).addRow(new Object[]{n.readAt() == null ? "New" : "Read", n.createdAt(), n.message()}); }
            table.getColumnModel().getColumn(2).setPreferredWidth(500);
            var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
            button(actions, "Read selected", () -> {
                int row = selected(table); if (row < 0) { return; }
                var notice = entries.get(row);
                run(() -> { service.markRead(session, notice.id()); return notice.message(); }, text -> {
                    JOptionPane.showMessageDialog(this, text, "Notification", JOptionPane.INFORMATION_MESSAGE); notices();
                });
            });
            button(actions, "Refresh", this::notices);
            display(table, actions, null); message.setText(entries.isEmpty() ? "Your inbox is empty." : "Select a notification to read its full message.");
        });
    }
    private void accounts() {
        run(() -> service.accounts(session), entries -> {
            var table = table(new String[]{"ID", "Name", "Email", "Role", "Active"});
            for (var a : entries) { model(table).addRow(new Object[]{a.id(), a.name(), a.email(), a.role(), a.active()}); }
            var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
            button(actions, "Enable / disable selected", () -> {
                int row = selected(table); if (row < 0) { return; } var a = entries.get(row);
                if (JOptionPane.showConfirmDialog(this, (a.active() ? "Disable " : "Enable ") + a.email() + "?", "Account access", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
                run(() -> { service.setActive(session, a.id(), !a.active()); return true; }, ignored -> accounts());
            });
            button(actions, "Refresh", this::accounts); display(table, actions, null);
            message.setText("Disabling blocks future actions. Profiles, jobs and application history are retained.");
        });
    }
    private void monitor(Filter filter) {
        run(() -> service.monitor(session, filter), entries -> {
            var controls = new JPanel(new java.awt.GridLayout(0, 2, 8, 6));
            var job = new JTextField(value(filter.jobId()), 6); var company = new JTextField(value(filter.companyId()), 6); var student = new JTextField(value(filter.studentId()), 6);
            controls.add(new JLabel("Job ID (optional)")); controls.add(job);
            controls.add(new JLabel("Company account ID (optional)")); controls.add(company);
            controls.add(new JLabel("Student account ID (optional)")); controls.add(student);
            var status = new JComboBox<String>(); status.addItem("All statuses");
            for (var state : Application.Status.values()) { status.addItem(state.name()); }
            if (filter.status() != null) { status.setSelectedItem(filter.status().name()); }
            controls.add(new JLabel("Status")); controls.add(status);
            var table = table(new String[]{"Job ID", "Job", "Company ID", "Company", "Student ID", "Student", "Status", "Original %"});
            for (var a : entries) { model(table).addRow(new Object[]{a.jobId(), a.job(), a.companyId(), a.company(), a.studentId(), a.student(), a.status(), String.format(java.util.Locale.ROOT, "%.2f", a.originalScore())}); }
            var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
            button(actions, "Filter / refresh", () -> {
                try { monitor(new Filter(id(job), id(company), id(student), status.getSelectedIndex() == 0 ? null : Application.Status.valueOf((String) status.getSelectedItem()))); }
                catch (NumberFormatException e) { message.setText("Enter numeric IDs or leave them blank."); }
            });
            button(actions, "View selected record", () -> {
                int row = selected(table); if (row < 0) { return; } var id = entries.get(row).id();
                run(() -> service.monitor(session, filter).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(() -> new UserError("Application no longer available.")), a -> {
                    var text = new JTextArea("Application: " + a.id() + "\nJob #" + a.jobId() + ": " + a.job() + "\nCompany: " + a.company()
                            + "\nStudent: " + a.student() + " (" + a.email() + ")\nStatus: " + a.status() + "\nSubmitted: " + a.submittedAt() + "\n\nOriginal assessment\n" + a.originalExplanation(), 22, 65);
                    text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
                    JOptionPane.showMessageDialog(this, new JScrollPane(text), "Read-only application record", JOptionPane.INFORMATION_MESSAGE);
                });
            });
            display(table, actions, controls); message.setText(entries.isEmpty() ? "No applications match these filters." : "Read-only monitoring. Decisions remain with the student and owning company.");
        });
    }
    private static String value(Long id) { return id == null ? "" : id.toString(); }
    private static Long id(JTextField field) { return field.getText().isBlank() ? null : Long.valueOf(field.getText().strip()); }
    private static JTable table(String[] columns) {
        var table = new JTable(new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true); return table;
    }
    private static DefaultTableModel model(JTable table) { return (DefaultTableModel) table.getModel(); }
    private int selected(JTable table) { if (table.getSelectedRow() < 0) { message.setText("Select a row first."); return -1; } return table.convertRowIndexToModel(table.getSelectedRow()); }
    private void display(JTable table, JPanel actions, JPanel controls) {
        body.removeAll(); if (controls != null) { body.add(controls, BorderLayout.NORTH); }
        body.add(new JScrollPane(table), BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH); revalidate(); repaint();
    }
    private static void button(JPanel panel, String text, Runnable action) { var b = new JButton(text); b.addActionListener(e -> action.run()); panel.add(b); }
    private <T> void run(Callable<T> task, Consumer<T> success) {
        if (busy || departed) { return; } busy = true; message.setText("Working…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false; if (departed) { return; }
                try { T result = get(); message.setText("Ready"); success.accept(result); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Interrupted. Try again."); }
                catch (java.util.concurrent.ExecutionException e) { message.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "Could not load this page. Please try again."); }
            }
        }.execute();
    }
}
