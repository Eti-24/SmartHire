package smarthire.ui;

import java.awt.BorderLayout;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import smarthire.identity.UserError;
import smarthire.jobs.Job.Category;
import smarthire.jobs.JobSearch;
import smarthire.jobs.JobService;
import smarthire.jobs.SavedSearch;

final class SavedSearchPanel extends JPanel {
    private final JobService jobs;
    private final UUID session;
    private final Consumer<JobSearch> runSearch;
    private final JPanel body = new JPanel(new BorderLayout(0, 8));
    private final JTextArea message = WorkspaceStyle.message("");

    SavedSearchPanel(JobService jobs, UUID session, Consumer<JobSearch> runSearch) {
        super(new BorderLayout(0, 8));
        this.jobs = jobs;
        this.session = session;
        this.runSearch = runSearch;
        add(body, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        load(() -> {});
    }

    private record Records(List<SavedSearch> searches, List<Category> categories) {}

    private void load(Runnable update) {
        var restore = WorkspaceStyle.disable(body);
        message.setText("Loading saved searches…");
        new SwingWorker<Records, Void>() {
            @Override protected Records doInBackground() {
                update.run();
                return new Records(jobs.savedSearches(session), jobs.categories(session));
            }
            @Override protected void done() {
                if (!isDisplayable()) { return; }
                restore.run();
                try { render(get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Loading interrupted."); }
                catch (ExecutionException e) {
                    message.setText(e.getCause() instanceof UserError error ? error.getMessage() : "Could not update saved searches.");
                }
            }
        }.execute();
    }

    private void render(Records records) {
        var model = new DefaultTableModel(new String[]{"Name", "Job title contains", "Location contains", "Alerts"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (var search : records.searches()) {
            model.addRow(new Object[]{search.name(), search.criteria().title(), search.criteria().location(),
                    search.active() ? "Active" : "Paused"});
        }
        var table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var run = WorkspaceStyle.button("Run search", () -> runSearch.accept(WorkspaceStyle.selected(table, records.searches()).criteria()));
        var edit = WorkspaceStyle.button("Edit", () -> edit(WorkspaceStyle.selected(table, records.searches()), records.categories()));
        var pause = WorkspaceStyle.button("Pause or resume", () -> {
            var selected = WorkspaceStyle.selected(table, records.searches());
            load(() -> jobs.saveSearch(session, new SavedSearch(selected.id(), selected.name(), selected.criteria(), !selected.active())));
        });
        var delete = WorkspaceStyle.button("Delete", () -> {
            var selected = WorkspaceStyle.selected(table, records.searches());
            if (JOptionPane.showConfirmDialog(this, "Delete saved search “" + selected.name() + "”?",
                    "Delete saved search", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                load(() -> jobs.deleteSearch(session, selected.id()));
            }
        });
        var actions = WorkspaceStyle.actions();
        var buttons = List.of(run, edit, pause, delete);
        buttons.forEach(button -> { actions.add(button); button.setEnabled(false); });
        table.getSelectionModel().addListSelectionListener(event -> {
            var selected = WorkspaceStyle.selected(table, records.searches());
            buttons.forEach(button -> button.setEnabled(selected != null));
            if (selected != null) { pause.setText(selected.active() ? "Pause alerts" : "Resume alerts"); }
        });
        actions.add(WorkspaceStyle.button("Refresh", () -> load(() -> {})));
        body.removeAll();
        body.add(WorkspaceStyle.table(table), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        message.setText(records.searches().isEmpty() ? "Use Save this search in Find jobs to create a saved search."
                : "You can run a paused search. Alerts arrive only when a matching posting is approved.");
        body.revalidate();
        body.repaint();
    }

    private void edit(SavedSearch search, List<Category> categories) {
        var criteria = search.criteria();
        var form = WorkspaceStyle.form();
        var name = new JTextField(search.name(), 18);
        var title = new JTextField(criteria.title(), 18);
        var category = new JComboBox<Category>();
        category.addItem(new Category(0, "All categories"));
        for (Category item : categories) {
            category.addItem(item);
            if (criteria.categoryId() != null && item.id() == criteria.categoryId()) { category.setSelectedItem(item); }
        }
        form.add(new JLabel("Search name")); form.add(name);
        form.add(new JLabel("Job title contains")); form.add(title);
        form.add(new JLabel("Category")); form.add(category);
        if (JOptionPane.showConfirmDialog(this, form, "Edit saved search", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) { return; }
        try {
            long id = ((Category) category.getSelectedItem()).id();
            var updated = JobSearchForm.edit(this, new JobSearch(title.getText(), id == 0 ? null : id, criteria.location(),
                    criteria.minimumSalary(), criteria.maximumSalary(), criteria.closingFrom(), criteria.closingBefore()));
            String searchName = name.getText();
            if (updated != null) { load(() -> jobs.saveSearch(session, new SavedSearch(search.id(), searchName, updated, search.active()))); }
        } catch (UserError error) { message.setText(error.getMessage()); }
    }
}
