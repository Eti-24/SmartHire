package smarthire.ui;

import java.awt.BorderLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import smarthire.jobs.Job;
import smarthire.jobs.JobService;
import smarthire.skills.Skill;
import static smarthire.jobs.Job.*;

/** Captures input on the EDT; every service operation runs on a worker. */
public final class JobsPanel extends JPanel {
    private final JobService service;
    private final UUID session;
    private final Role role;
    private final long initialJobId;
    private final smarthire.applications.ApplicationService applications;
    private final JPanel body = new JPanel(new BorderLayout(10, 10));
    private final JTextArea message = WorkspaceStyle.message("Loading jobs…");
    private final JButton back = new JButton("Back to overview");
    private boolean busy;
    private List<Category> categories = List.of();
    private List<Skill> skills = List.of();

    public JobsPanel(JobService service, UUID session, Role role, Runnable onBack) {
        this(service, session, role, onBack, 0);
    }

    public JobsPanel(JobService service, UUID session, Role role, Runnable onBack, long initialJobId) {
        this(service, session, role, onBack, initialJobId, null);
    }

    public JobsPanel(JobService service, UUID session, Role role, Runnable onBack, long initialJobId,
            smarthire.applications.ApplicationService applications) {
        this(service, session, role, onBack, initialJobId, applications,
                initialJobId == 0 ? "Back to overview" : "Back to matches");
    }

    JobsPanel(JobService service, UUID session, Role role, Runnable onBack, long initialJobId,
            smarthire.applications.ApplicationService applications, String backLabel) {
        super(new BorderLayout(10, 10));
        this.service = service; this.session = session; this.role = role;
        this.initialJobId = initialJobId;
        this.applications = applications;
        back.setText(backLabel);
        if (onBack != null) {
            back.addActionListener(event -> onBack.run());
            add(back, BorderLayout.NORTH);
        }
        add(body, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        load();
    }

    private void load() {
        work(() -> new Catalog(service.categories(session), service.skills(session)), catalog -> {
            categories = catalog.categories(); skills = catalog.skills();
            if (initialJobId == 0) { browse("", null); }
            else { work(() -> service.open(session, initialJobId), this::details); }
        });
    }

    private void browse(String query, Long categoryId) {
        work(() -> service.list(session, query, categoryId), jobs -> {
            var controls = WorkspaceStyle.actions();
            var search = new JTextField(query, 12);
            var titleLabel = new JLabel("Job title");
            titleLabel.setLabelFor(search);
            var category = new JComboBox<Category>();
            category.addItem(new Category(0, "All categories"));
            category.setPrototypeDisplayValue(new Category(0, "Select a job category"));
            for (Category item : categories) {
                category.addItem(item);
                if (categoryId != null && item.id() == categoryId) { category.setSelectedItem(item); }
            }
            var categoryLabel = new JLabel("Category");
            categoryLabel.setLabelFor(category);
            Runnable filter = () -> {
                var selected = (Category) category.getSelectedItem();
                browse(search.getText(), selected.id() == 0 ? null : selected.id());
            };
            search.addActionListener(event -> filter.run());
            controls.add(titleLabel);
            controls.add(search);
            controls.add(categoryLabel);
            controls.add(category);
            button(controls, "Search", filter);
            button(controls, "Clear", () -> browse("", null));

            var model = readOnly("Job", role == Role.COMPANY ? "Category" : "Company", "Status");
            for (Job job : jobs) {
                model.addRow(new Object[]{job.details().title(),
                        role == Role.COMPANY ? categoryName(job.details().categoryId()) : job.companyName(),
                        WorkspaceStyle.label(job.status())});
            }
            var table = new JTable(model);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoCreateRowSorter(true);
            table.getAccessibleContext().setAccessibleName("Job postings");
            table.getColumnModel().getColumn(0).setPreferredWidth(180);
            WorkspaceStyle.statusColumn(table, 2);
            var preview = new WorkspaceStyle.Preview();
            preview.showRecord("No jobs found", "Try a different title or category.");
            var open = WorkspaceStyle.button("Open job", () -> {
                var job = WorkspaceStyle.selected(table, jobs);
                work(() -> service.open(session, job.id()), this::details);
            });
            var attach = WorkspaceStyle.button("Attach circular", () -> attachCircular(WorkspaceStyle.selected(table, jobs)));
            var save = WorkspaceStyle.button("Save job", () -> saveJob(WorkspaceStyle.selected(table, jobs)));
            open.setEnabled(false);
            attach.setEnabled(false);
            save.setEnabled(false);
            WorkspaceStyle.previewSelection(table, jobs, job -> {
                open.setEnabled(job != null);
                save.setEnabled(job != null);
                attach.setEnabled(job != null && job.status() != Status.CLOSED);
                if (job == null) { preview.showRecord("Jobs", "Select a posting to preview it."); }
                else {
                    var details = job.details();
                    preview.showRecord(WorkspaceStyle.label(job.status()), details.title() + "\n" + job.companyName()
                            + "\n\n" + categoryName(details.categoryId()) + " · " + details.location()
                            + "\nSalary " + details.salaryMin() + "–" + details.salaryMax()
                            + "\nUnfilled vacancies: " + job.remainingVacancies() + " of " + details.vacancyCount()
                            + "\nJob #" + job.id() + "\n\n" + details.description());
                }
            });
            var actions = WorkspaceStyle.actions();
            WorkspaceStyle.primary(open);
            actions.add(open);
            if (role == Role.STUDENT) { actions.add(save); }
            if (role == Role.COMPANY) {
                button(actions, "Create job", () -> editor(null));
                actions.add(attach);
            }
            if (role == Role.ADMIN) {
                button(actions, "Verify companies", this::companies);
                button(actions, "Categories", this::categoryEditor);
                button(actions, "Skills", this::skillEditor);
                button(actions, "Matching weights", this::policyEditor);
            }
            body.removeAll();
            body.add(controls, BorderLayout.NORTH);
            body.add(WorkspaceStyle.split(WorkspaceStyle.table(table), preview), BorderLayout.CENTER);
            body.add(actions, BorderLayout.SOUTH);
            message.setText(jobs.isEmpty() ? "No jobs match your search." : jobs.size() + " postings");
            refresh();
        });
    }

    private String categoryName(long id) { return categories.stream().filter(c -> c.id() == id).map(Category::name).findFirst().orElse("Unknown"); }

    private void saveJob(Job job) {
        work(() -> { service.saveBookmark(session, job.id()); return job; }, ignored -> message.setText("Job saved."));
    }

    private void details(Job job) {
        Details d = job.details();
        var tabs = new JTabbedPane();
        String summary = d.title() + "\n" + job.companyName() + " · " + categoryName(d.categoryId())
                + "\n" + d.location() + " · Salary " + d.salaryMin() + "–" + d.salaryMax()
                + "\nStatus  ·  " + WorkspaceStyle.label(job.status())
                + "\nUnfilled vacancies  ·  " + job.remainingVacancies() + " of " + d.vacancyCount()
                + (job.decision() == null || job.decision().isBlank() ? "" : "\n" + job.decision()) + "\n\n" + d.description();
        tabs.addTab("Posting", WorkspaceStyle.text(summary));
        tabs.addTab("Requirements", WorkspaceStyle.table(new JTable(requirementModel(d.requirements(), false))));
        var circularTab = new JPanel(new BorderLayout(0, 10));
        WorkspaceStyle.surface(circularTab, 12);
        circularTab.add(WorkspaceStyle.message("Read the extracted circular text or save the original PDF or DOCX."), BorderLayout.NORTH);
        var circularControls = WorkspaceStyle.actions();
        if (role == Role.COMPANY) {
            if (job.status() != Status.CLOSED) {
                button(circularControls, "Attach / replace circular", () -> attachCircular(job));
            } else { circularControls.add(new JLabel("This job is closed. Its circular cannot be changed.")); }
        }
        button(circularControls, "View circular", () -> work(() -> service.circular(session, job.id()), circular -> {
            if (circular.isEmpty()) { message.setText("This job has no circular attachment."); return; }
            var doc = circular.orElseThrow();
            var text = new JTextArea(doc.text(), 18, 55); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setCaretPosition(0);
            JOptionPane.showMessageDialog(this, new JScrollPane(text), doc.originalName(), JOptionPane.PLAIN_MESSAGE);
        }));
        button(circularControls, "Save circular", () -> work(() -> service.circular(session, job.id()), circular -> {
            if (circular.isEmpty()) { message.setText("This job has no circular attachment."); return; }
            var chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(circular.orElseThrow().originalName()));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
            var destination = chooser.getSelectedFile().toPath();
            work(() -> java.nio.file.Files.exists(destination), exists -> {
                if (exists && JOptionPane.showConfirmDialog(this, "Replace the existing file?", "Save circular",
                        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
                work(() -> {
                    java.nio.file.Files.write(destination, service.downloadCircular(session, job.id()));
                    return true;
                }, ignored -> message.setText("Circular saved."));
            });
        }));
        var circularBody = new JPanel(new BorderLayout());
        circularBody.setOpaque(false);
        circularBody.add(circularControls, BorderLayout.NORTH);
        circularTab.add(circularBody, BorderLayout.CENTER);
        tabs.addTab("Job circular", circularTab);
        var actions = WorkspaceStyle.actions();
        if (role == Role.STUDENT && applications != null) {
            button(actions, "Apply with my reviewed CV", () -> work(() -> applications.apply(session, job.id()),
                    submitted -> message.setText("Application submitted. Track it in My applications.")));
        }
        if (role == Role.COMPANY && job.status() != Status.CLOSED) {
            button(actions, "Edit and resubmit", () -> editor(job));
            button(actions, "Close job", () -> work(() -> { service.close(session, job.id()); return true; }, ignored -> browse("", null)));
        }
        if (role == Role.ADMIN && job.status() == Status.PENDING) {
            button(actions, "Approve", () -> work(() -> { service.moderate(session, job.id(), true, ""); return true; }, ignored -> browse("", null)));
            button(actions, "Reject", () -> {
                String reason = JOptionPane.showInputDialog(this, "Reason for rejection");
                if (reason != null) { work(() -> { service.moderate(session, job.id(), false, reason); return true; }, ignored -> browse("", null)); }
            });
        }
        if (role == Role.STUDENT) { button(actions, "Save job", () -> saveJob(job)); }
        button(actions, "Back to jobs", () -> browse("", null));
        body.removeAll();
        body.add(tabs, BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        message.setText("Required priority affects scoring. Only an explicit mandatory flag blocks eligibility.");
        refresh();
    }

    private void attachCircular(Job job) {
        if (job.status() == Status.CLOSED) { message.setText("Choose an open posting. Closed jobs cannot change their circular."); return; }
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Attach a circular to " + job.details().title());
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text PDF or DOCX (up to 5 MB)", "pdf", "docx"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
        var source = chooser.getSelectedFile().toPath();
        work(() -> { service.attachCircular(session, job.id(), source); return service.open(session, job.id()); }, updated -> {
            details(updated); message.setText("Circular attached. This posting is now awaiting admin approval.");
        });
    }

    private void editor(Job job) {
        var posting = WorkspaceStyle.form();
        var requirements = new JPanel(new BorderLayout(0, 10));
        WorkspaceStyle.surface(requirements, 12);
        Details d = job == null ? null : job.details();
        JTextField title = new JTextField(d == null ? "" : d.title());
        JTextArea description = new JTextArea(d == null ? "" : d.description(), 4, 40);
        description.setLineWrap(true); description.setWrapStyleWord(true);
        JComboBox<Category> category = new JComboBox<>(categories.toArray(Category[]::new));
        if (d != null) { categories.stream().filter(c -> c.id() == d.categoryId()).findFirst().ifPresent(category::setSelectedItem); }
        JTextField location = new JTextField(d == null ? "" : d.location());
        JTextField min = new JTextField(d == null ? "0" : d.salaryMin().toPlainString());
        JTextField max = new JTextField(d == null ? "0" : d.salaryMax().toPlainString());
        JTextField vacancies = new JTextField(d == null ? "1" : Integer.toString(d.vacancyCount()));
        field(posting, "Title", title);
        field(posting, "Description", new JScrollPane(description));
        field(posting, "Category", category);
        field(posting, "Location", location);
        field(posting, "Salary minimum", min);
        field(posting, "Salary maximum", max);
        field(posting, "Number of vacancies", vacancies);
        var guidance = WorkspaceStyle.message("Use months for experience and add an experience tag for a positive minimum. "
                + "Degree order is Secondary, Diploma, Bachelor, Master, Doctorate. The Mandatory checkbox controls eligibility.");
        requirements.add(guidance, BorderLayout.NORTH);
        DefaultTableModel model = requirementModel(d == null ? List.of() : d.requirements(), true);
        JTable table = new JTable(model);
        table.setSurrendersFocusOnKeystroke(true);
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JComboBox<>(Kind.values())));
        JComboBox<String> values = new JComboBox<>(); values.setEditable(true);
        for (Skill skill : skills) { values.addItem(skill.name()); }
        for (Degree degree : Degree.values()) { values.addItem(degree.name()); }
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(values));
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(Priority.values())));
        requirements.add(WorkspaceStyle.table(table), BorderLayout.CENTER);
        var tools = WorkspaceStyle.actions();
        button(tools, "Suggest from description", () -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) { return; }
            String source = description.getText(); work(() -> service.suggest(session, source), suggestions -> previewSuggestions(model, suggestions));
        });
        if (job != null) {
            button(tools, "Suggest from circular", () -> {
                if (table.isEditing() && !table.getCellEditor().stopCellEditing()) { return; }
                work(() -> service.suggest(session, service.circular(session, job.id()).orElseThrow(() -> new UserError("Attach a circular from the job details first.")).text()),
                        suggestions -> previewSuggestions(model, suggestions));
            });
        }
        button(tools, "Add requirement", () -> model.addRow(new Object[]{Kind.SKILL, "", 0, Priority.REQUIRED, false}));
        button(tools, "Remove row", () -> {
            if ((!table.isEditing() || table.getCellEditor().stopCellEditing()) && table.getSelectedRow() >= 0) {
                model.removeRow(table.getSelectedRow());
            }
        });
        requirements.add(tools, BorderLayout.SOUTH);
        var actions = WorkspaceStyle.actions();
        var submit = WorkspaceStyle.button("Submit for approval", () -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) { return; }
            try {
                if (category.getSelectedItem() == null) { throw new UserError("Ask an admin to create a category first."); }
                List<Requirement> items = new ArrayList<>();
                for (int row = 0; row < model.getRowCount(); row++) {
                    items.add(new Requirement((Kind) model.getValueAt(row, 0), String.valueOf(model.getValueAt(row, 1)),
                            Integer.parseInt(String.valueOf(model.getValueAt(row, 2))), (Priority) model.getValueAt(row, 3), (Boolean) model.getValueAt(row, 4)));
                }
                Details input = new Details(title.getText(), description.getText(), ((Category) category.getSelectedItem()).id(), location.getText(),
                        new BigDecimal(min.getText().strip()), new BigDecimal(max.getText().strip()), items,
                        Integer.parseInt(vacancies.getText().strip()));
                work(() -> service.submit(session, job == null ? 0 : job.id(), input), ignored -> browse("", null));
            } catch (NumberFormatException exception) { message.setText("Enter numeric salary values and whole numbers for months and vacancies."); }
            catch (UserError exception) { message.setText(exception.getMessage()); }
        });
        WorkspaceStyle.primary(submit);
        actions.add(submit);
        button(actions, "Cancel", () -> browse("", null));
        var tabs = new JTabbedPane();
        tabs.addTab("Posting information", WorkspaceStyle.scroll(posting));
        tabs.addTab("Requirements", requirements);
        body.removeAll();
        body.add(WorkspaceStyle.heading(job == null ? "Create a job" : "Edit posting"), BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        refresh();
        message.setText("Saving returns the entire job to pending approval. Add at least one scored requirement.");
    }

    private void companies() {
        work(() -> service.companies(session), companies -> {
            JPanel panel = form("Company verification"); JComboBox<Company> choice = new JComboBox<>(companies.toArray(Company[]::new));
            field(panel, "Company", choice);
            JCheckBox verified = new JCheckBox("Verified company"); panel.add(verified);
            choice.addActionListener(e -> { if (choice.getSelectedItem() instanceof Company c) { verified.setSelected(c.verified()); } });
            if (choice.getSelectedItem() instanceof Company c) { verified.setSelected(c.verified()); }
            var actions = WorkspaceStyle.actions();
            primary(actions, "Save verification", () -> {
                if (choice.getSelectedItem() instanceof Company c) {
                    boolean value = verified.isSelected(); work(() -> { service.verify(session, c.id(), value); return true; }, ignored -> companies());
                }
            });
            button(actions, "Back to jobs", () -> browse("", null)); show(panel, actions);
            message.setText("Removing verification immediately hides this company's jobs from students.");
        });
    }

    private void previewSuggestions(DefaultTableModel target, smarthire.jobs.RequirementSuggestions suggestions) {
        var preview = new JPanel(new BorderLayout(8, 8));
        preview.add(new JScrollPane(new JTable(requirementModel(suggestions.requirements(), false))), BorderLayout.CENTER);
        var notes = new JTextArea(String.join("\n", suggestions.reviewNotes()), 5, 55); notes.setEditable(false); notes.setLineWrap(true); notes.setWrapStyleWord(true);
        preview.add(notes, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, preview, "Review suggestions before adding", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) { return; }
        var seen = new java.util.HashSet<String>();
        for (int row = 0; row < target.getRowCount(); row++) { seen.add(requirementKey((Kind) target.getValueAt(row, 0), String.valueOf(target.getValueAt(row, 1)))); }
        int added = 0;
        for (var r : suggestions.requirements()) {
            if (seen.add(requirementKey(r.kind(), r.value()))) {
                target.addRow(new Object[]{r.kind(), displayRequirement(r), r.months(), r.priority(), false}); added++;
            }
        }
        message.setText(added + " suggestion(s) added for editing. Check the values, priority and mandatory flags before submitting.");
    }
    private String requirementKey(Kind kind, String value) {
        if (kind == Kind.DEGREE || kind == Kind.FIELD || kind == Kind.EXPERIENCE) { return kind.name(); }
        var vocabulary = new smarthire.skills.SkillVocabulary(skills);
        String canonical = vocabulary.findById(value).or(() -> vocabulary.resolve(value)).map(Skill::id)
                .orElse(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT).replaceAll("(?U)\\s+", " ").strip());
        return kind + ":" + canonical;
    }

    private void policyEditor() {
        work(() -> service.scoringPolicy(session), policy -> {
            JPanel panel = form("Matching weights"); panel.add(new JLabel("Current policy  ·  " + policy.version()));
            var inputs = new java.util.EnumMap<smarthire.matching.ScoringPolicy.Category, JTextField>(smarthire.matching.ScoringPolicy.Category.class);
            for (var category : smarthire.matching.ScoringPolicy.Category.values()) {
                var input = new JTextField(String.valueOf(policy.weights().getOrDefault(category, 0)));
                field(panel, category.name().toLowerCase(java.util.Locale.ROOT) + " weight", input); inputs.put(category, input);
            }
            panel.add(WorkspaceStyle.message("Weights are normalized for each job’s active categories. Original application scores stay unchanged."));
            var actions = WorkspaceStyle.actions();
            primary(actions, "Activate weights", () -> {
                try {
                    var weights = new java.util.EnumMap<smarthire.matching.ScoringPolicy.Category, Integer>(smarthire.matching.ScoringPolicy.Category.class);
                    inputs.forEach((category, input) -> weights.put(category, Integer.parseInt(input.getText().strip())));
                    work(() -> service.activatePolicy(session, weights), activated -> { policyEditor(); message.setText("Policy activated. Matches and applicant rankings recalculate on refresh."); });
                } catch (NumberFormatException e) { message.setText("Enter nonnegative whole-number weights with a positive total."); }
            });
            button(actions, "Back to jobs", () -> browse("", null)); show(panel, actions);
            message.setText("Activation checks all approved open jobs and all jobs with applications, including closed postings.");
        });
    }

    private void categoryEditor() {
        JPanel panel = form("Job categories"); JComboBox<Category> choice = new JComboBox<>(); choice.addItem(new Category(0, "New category")); categories.forEach(choice::addItem);
        JTextField name = new JTextField(); field(panel, "Category", choice); field(panel, "Name", name);
        choice.addActionListener(e -> name.setText(((Category) choice.getSelectedItem()).id() == 0 ? "" : ((Category) choice.getSelectedItem()).name()));
        var actions = WorkspaceStyle.actions();
        primary(actions, "Save category", () -> { long id = ((Category) choice.getSelectedItem()).id(); String value = name.getText(); work(() -> service.saveCategory(session, id, value), ignored -> load()); });
        button(actions, "Back to jobs", () -> browse("", null)); show(panel, actions);
    }

    private void skillEditor() {
        JPanel panel = form("Skills and aliases"); JComboBox<String> choice = new JComboBox<>(); choice.addItem("New skill"); skills.forEach(s -> choice.addItem(s.id()));
        JTextField id = new JTextField(), name = new JTextField(), aliases = new JTextField();
        JComboBox<Skill.Type> type = new JComboBox<>(Skill.Type.values());
        field(panel, "Skill", choice); field(panel, "Stable ID (lowercase, hyphens allowed)", id); field(panel, "Display name", name);
        field(panel, "Type", type); field(panel, "Aliases (comma separated)", aliases);
        choice.addActionListener(e -> {
            Skill selected = skills.stream().filter(s -> s.id().equals(choice.getSelectedItem())).findFirst().orElse(null);
            id.setEditable(selected == null); id.setText(selected == null ? "" : selected.id()); name.setText(selected == null ? "" : selected.name());
            aliases.setText(selected == null ? "" : String.join(", ", selected.aliases())); type.setSelectedItem(selected == null ? Skill.Type.TECHNICAL : selected.type());
        });
        var actions = WorkspaceStyle.actions();
        primary(actions, "Save skill", () -> {
            try {
                Skill skill = new Skill(id.getText().strip(), name.getText(), (Skill.Type) type.getSelectedItem(),
                        aliases.getText().isBlank() ? Set.of() : Set.copyOf(Arrays.stream(aliases.getText().split(",", -1)).map(String::strip).toList()));
                work(() -> { service.saveSkill(session, skill); return true; }, ignored -> load());
            } catch (IllegalArgumentException exception) { message.setText(exception.getMessage()); }
        });
        button(actions, "Back to jobs", () -> browse("", null)); show(panel, actions);
        message.setText("Canonical IDs stay stable when names or aliases change. Existing requirements retain those IDs.");
    }

    private DefaultTableModel requirementModel(List<Requirement> items, boolean editable) {
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Kind", "Value", "Months", "Priority", "Mandatory"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return editable; }
            @Override public Class<?> getColumnClass(int column) { return column == 4 ? Boolean.class : Object.class; }
        };
        for (Requirement r : items) { model.addRow(new Object[]{r.kind(), displayRequirement(r), r.months(), r.priority(), r.mandatory()}); }
        return model;
    }
    private String displayRequirement(Requirement r) {
        return switch (r.kind()) {
            case SKILL, PROJECT, EXPERIENCE_TAG -> skills.stream().filter(s -> s.id().equals(r.value())).map(Skill::name).findFirst().orElse(r.value());
            default -> r.value();
        };
    }
    private static DefaultTableModel readOnly(String... columns) { return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } }; }
    private static JPanel form(String title) {
        var panel = WorkspaceStyle.form();
        panel.add(WorkspaceStyle.heading(title));
        panel.add(Box.createVerticalStrut(10));
        return panel;
    }

    private static void field(JPanel panel, String label, JComponent input) { WorkspaceStyle.field(panel, label, input); }
    private static void button(JPanel panel, String label, Runnable action) { panel.add(WorkspaceStyle.button(label, action)); }
    private static void primary(JPanel panel, String label, Runnable action) {
        var button = WorkspaceStyle.button(label, action);
        WorkspaceStyle.primary(button);
        panel.add(button);
    }

    private void show(JPanel panel, JPanel actions) {
        body.removeAll();
        body.add(WorkspaceStyle.scroll(panel), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        refresh();
    }
    private void refresh() { revalidate(); repaint(); }
    private <T> void work(Callable<T> task, Consumer<T> success) {
        if (busy) { return; }
        busy = true;
        Runnable restore = WorkspaceStyle.disable(body);
        message.setText("Working…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false;
                if (!isDisplayable()) { return; }
                restore.run();
                try { T result = get(); message.setText(""); success.accept(result); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Interrupted. Try again."); }
                catch (java.util.concurrent.ExecutionException e) { message.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "The operation failed. Check the database and try again."); }
            }
        }.execute();
    }
    private record Catalog(List<Category> categories, List<Skill> skills) { }
}
