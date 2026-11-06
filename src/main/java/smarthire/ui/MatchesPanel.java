package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import smarthire.cv.CvService;
import smarthire.identity.UserError;
import smarthire.matching.MatchService;

/** All matching and database access runs outside the event-dispatch thread. */
final class MatchesPanel extends JPanel {
    private final MatchService service;
    private final CvService cvs;
    private final UUID session;
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Job", "Company", "Eligibility", "Match"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final WorkspaceStyle.Preview preview = new WorkspaceStyle.Preview();
    private final JTextArea approval = WorkspaceStyle.message("");
    private final JTextArea status = WorkspaceStyle.message("Loading matches…");
    private final JButton refresh = new JButton("Refresh recommendations");
    private final JButton openJob = new JButton("Open selected job");
    private final JButton explain = new JButton("Why this match?");
    private final JButton compare = new JButton("Compare two jobs");
    private final JButton save = new JButton("Save job");
    private List<MatchService.Recommendation> results = List.of();
    private boolean busy;

    MatchesPanel(MatchService service, CvService cvs, UUID session, Consumer<Long> onOpenJob, Consumer<Long> onSaveJob,
            Runnable onEditCv) {
        super(new BorderLayout(0, 10));
        this.service = service;
        this.cvs = cvs;
        this.session = session;
        var header = new JPanel(new BorderLayout(0, 6));
        var controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        controls.add(refresh);
        header.add(approval, BorderLayout.NORTH);
        header.add(controls, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        WorkspaceStyle.statusColumn(table, 2);
        table.getColumnModel().getColumn(0).setPreferredWidth(155);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(54);
        table.getAccessibleContext().setAccessibleName("Job recommendations");
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) { updatePreview(); }
        });
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        WorkspaceStyle.primary(openJob);
        actions.add(openJob);
        actions.add(explain);
        actions.add(compare);
        actions.add(save);
        var footer = new JPanel(new BorderLayout(0, 8));
        footer.add(actions, BorderLayout.NORTH);
        footer.add(status, BorderLayout.SOUTH);
        add(WorkspaceStyle.split(WorkspaceStyle.table(table), preview), BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        refresh.addActionListener(event -> refresh());
        openJob.addActionListener(event -> onOpenJob.accept(selectedMatch().job().id()));
        save.addActionListener(event -> {
            long id = selectedMatch().job().id();
            run(() -> { onSaveJob.accept(id); return id; }, ignored -> status.setText("Job saved."));
        });
        explain.addActionListener(event -> {
            long id = selectedMatch().job().id();
            run(() -> {
                var recommendation = service.open(session, id);
                var approval = cvs.approval(session, recommendation.cvVersion());
                return new MatchDetail(recommendation, WorkspaceStyle.label(approval.status()));
            }, detail -> showExplanation(detail, onEditCv));
        });
        compare.addActionListener(event -> {
            int[] rows = table.getSelectedRows();
            long first = results.get(rows[0]).job().id(), second = results.get(rows[1]).job().id();
            run(() -> service.compare(session, first, second), this::compare);
        });
        refresh();
    }

    private MatchService.Recommendation selectedMatch() {
        return results.get(table.getSelectedRow());
    }

    private void refresh() {
        table.clearSelection();
        model.setRowCount(0);
        results = List.of();
        approval.setText("");
        preview.showRecord("Recommendations", "Loading your current matches…");
        run(() -> {
            var recommendations = service.recommend(session);
            String approvalStatus = recommendations.needsReview() ? "No confirmed CV"
                    : WorkspaceStyle.label(cvs.approval(session, recommendations.cvVersion()).status());
            return new MatchList(recommendations, approvalStatus);
        }, loaded -> {
            var recommendations = loaded.recommendations();
            approval.setText("CV approval  ·  " + loaded.approval()
                    + "\nEligibility is assessed against each job's mandatory requirements.");
            results = recommendations.jobs();
            for (var r : results) {
                model.addRow(new Object[]{r.job().details().title(), r.job().companyName(),
                        eligibility(r), format(r.match().percentage()) + "%"});
            }
            if (recommendations.needsReview()) {
                preview.showRecord("Confirm your CV", "Upload a CV and confirm your qualifications in My CV to get recommendations.");
                status.setText("");
            } else if (results.isEmpty()) {
                preview.showRecord("No jobs available", "Approved, open jobs will appear here when available.");
                status.setText("");
            } else {
                table.setRowSelectionInterval(0, 0);
                status.setText(results.size() + " matches. Eligible jobs appear first. Select two rows with Ctrl or Shift to compare.");
            }
        });
    }

    private void updatePreview() {
        updateActions();
        int count = table.getSelectedRowCount();
        if (count == 0) {
            preview.showRecord("Recommendations", "Select a job to see eligibility and match evidence.");
        } else if (count > 1) {
            preview.showRecord("Multiple jobs selected", "Select two jobs to compare their requirements, match scores and text similarity.");
        } else {
            var selected = selectedMatch();
            var match = selected.match();
            String mandatory = match.eligible() ? "You meet all mandatory requirements."
                    : match.failedMandatory().stream().map(item -> "• " + friendly(item))
                            .collect(java.util.stream.Collectors.joining("\n"));
            preview.showRecord(eligibility(selected), selected.job().details().title() + "\n"
                    + selected.job().companyName() + "\n\nMandatory requirements\n" + mandatory
                    + "\n\nMatch score\n" + format(match.percentage()) + "%");
        }
    }

    private void updateActions() {
        int count = table.getSelectedRowCount();
        refresh.setEnabled(!busy);
        table.setEnabled(!busy);
        openJob.setEnabled(!busy && count == 1);
        explain.setEnabled(!busy && count == 1);
        compare.setEnabled(!busy && count == 2);
        save.setEnabled(!busy && count == 1);
    }

    private static String eligibility(MatchService.Recommendation recommendation) {
        return recommendation.match().eligible() ? "Eligible" : "Not eligible";
    }

    private void showExplanation(MatchDetail detail, Runnable onEditCv) {
        var recommendation = detail.recommendation();
        var tabs = new JTabbedPane();
        tabs.addTab("Qualification evidence", WorkspaceStyle.text(explanation(recommendation, false)));
        tabs.addTab("What to improve", WorkspaceStyle.text(checklist(detail)));
        tabs.addTab("Score details", WorkspaceStyle.text(explanation(recommendation, true)));
        Object[] options = {"Close", "Go to My CV"};
        int selected = JOptionPane.showOptionDialog(this, tabs, "Why this match?", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (selected == 1) { onEditCv.run(); }
        status.setText("Explanation refreshed from your current confirmed CV and the available posting.");
    }

    private static String checklist(MatchDetail detail) {
        var match = detail.recommendation().match();
        var text = new StringBuilder("CV approval · ").append(detail.approval()).append("\n\n");
        for (boolean required : new boolean[]{true, false}) {
            text.append(required ? "Required to apply\n" : "\nCould improve your match\n");
            var gaps = match.gaps().stream().filter(gap -> gap.mandatory() == required).toList();
            if (gaps.isEmpty()) { text.append(required ? "All mandatory requirements are met.\n" : "No other qualification gaps.\n"); }
            for (var gap : gaps) {
                text.append("• ").append(friendly(gap.label())).append('\n');
                text.append("  ").append(switch (gap.kind()) {
                    case SKILL -> "Review your skills in My CV and add this skill if you have it.";
                    case PROJECT -> "Add a real project in My CV and tag it with this skill.";
                    case DEGREE, FIELD -> "Review your confirmed education in My CV against this requirement.";
                    case EXPERIENCE -> "Review your dated work evidence for " + String.join(", ", match.relevanceTags())
                            + ". Overlapping months count once.";
                    case CERTIFICATION -> "Add this certification in My CV if you have earned it.";
                    case EXPERIENCE_TAG -> throw new IllegalStateException("Relevance tags are not standalone requirements");
                }).append('\n');
            }
        }
        return text.toString();
    }

    private String explanation(MatchService.Recommendation r, boolean details) {
        var match = r.match();
        var text = new StringBuilder(r.job().details().title()).append(" — ").append(r.job().companyName())
                 .append(match.eligible() ? "\n\nEligible · You meet all mandatory requirements."
                        : "\n\nNot eligible · Some mandatory requirements are missing.");
        if (!match.eligible()) {
            text.append("\n\nRequired before you can apply:\n");
            match.failedMandatory().forEach(item -> text.append("  • ").append(friendly(item)).append('\n'));
        }
        text.append("\n\nMatch score: ").append(format(match.percentage())).append("%");
        text.append("\n\nYour match at a glance:\n");
        for (var c : match.contributions()) {
            text.append("  • ").append(categoryName(c.category())).append(": ").append(format(c.fraction() * 100)).append("% met\n");
        }
        var matched = match.contributions().stream().flatMap(c -> c.matched().stream()).toList();
        if (!matched.isEmpty()) {
            text.append("\nWhat matches:\n");
            matched.forEach(item -> text.append("  • ").append(friendly(item)).append('\n'));
        }
        var missing = match.contributions().stream().flatMap(c -> c.missing().stream()).toList();
        if (!missing.isEmpty()) {
            text.append("\nMissing or partly met:\n");
            missing.forEach(item -> text.append("  • ").append(friendly(item)).append('\n'));
        }
        if (!match.suggestions().isEmpty()) {
            text.append("\nWhat you can improve:\n");
            match.suggestions().forEach(s -> text.append("  • ").append(s).append('\n'));
        }
        if (details) {
            text.append("\nScore details\nPolicy: ").append(match.policyVersion()).append("\nCV review: ").append(r.cvVersion()).append('\n');
            for (var c : match.contributions()) {
                text.append(categoryName(c.category())).append(": ").append(format(c.normalizedWeight() * 100))
                        .append("% of the score; contributes ").append(format(c.percentagePoints())).append(" points.\n");
            }
            if (!match.relevanceTags().isEmpty()) {
                text.append("Counted experience: ").append(match.relevantMonths()).append(" months tagged with ")
                        .append(String.join(", ", match.relevanceTags())).append(". Overlapping months count once.\n");
            }
            text.append("Mandatory checks:\n");
            if (match.mandatoryChecks().isEmpty()) { text.append("  None specified.\n"); }
            match.mandatoryChecks().forEach(check -> text.append(check.satisfied() ? "  Met: " : "  Missing: ").append(friendly(check.requirement())).append('\n'));
        }
        return text.toString();
    }

    private <T> void run(Callable<T> task, Consumer<T> success) {
        if (busy) { return; }
        busy = true;
        updateActions();
        status.setText("Loading…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false;
                if (!isDisplayable()) { return; }
                updateActions();
                try { success.accept(get()); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("Interrupted. Refresh to try again.");
                } catch (ExecutionException e) {
                    showError(e.getCause() instanceof UserError ? e.getCause().getMessage()
                            : "Could not calculate matches. Check the database and try again.");
                }
            }
        }.execute();
    }

    private void showError(String error) {
        if (results.isEmpty()) { preview.showRecord("Matches unavailable", "Refresh recommendations to try again."); }
        status.setText(error);
    }

    private record MatchList(MatchService.Recommendations recommendations, String approval) { }
    private record MatchDetail(MatchService.Recommendation recommendation, String approval) { }

    private static String format(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    private void compare(MatchService.Comparison comparison) {
        var first = comparison.first(); var second = comparison.second();
        var model = new DefaultTableModel(new String[]{"", first.job().details().title(), second.job().details().title()}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        model.addRow(new Object[]{"Company", first.job().companyName(), second.job().companyName()});
        model.addRow(new Object[]{"Location", first.job().details().location(), second.job().details().location()});
        model.addRow(new Object[]{"Salary", first.job().details().salaryMin() + "–" + first.job().details().salaryMax(), second.job().details().salaryMin() + "–" + second.job().details().salaryMax()});
        model.addRow(new Object[]{"Eligibility", eligibility(first), eligibility(second)});
        model.addRow(new Object[]{"Mandatory gaps", String.join("; ", first.match().failedMandatory()), String.join("; ", second.match().failedMandatory())});
        model.addRow(new Object[]{"Match", format(first.match().percentage()) + "%", format(second.match().percentage()) + "%"});
        for (var category : smarthire.matching.ScoringPolicy.Category.values()) {
            model.addRow(new Object[]{categoryName(category), categorySummary(first, category), categorySummary(second, category)});
        }
        model.addRow(new Object[]{"Text similarity (TF-IDF/cosine)", format(comparison.firstTextSimilarity() * 100) + "%", format(comparison.secondTextSimilarity() * 100) + "%"});
        var panel = new JPanel(new BorderLayout(8, 8));
        var comparisonTable = new JTable(model);
        panel.add(WorkspaceStyle.table(comparisonTable), BorderLayout.CENTER);
        var note = new JTextArea("Text similarity measures wording overlap only. It does not change the structured match score or eligibility. Both jobs use the same CV review and policy.", 3, 55);
        note.setEditable(false); note.setLineWrap(true); note.setWrapStyleWord(true); panel.add(note, BorderLayout.SOUTH);
        javax.swing.JOptionPane.showMessageDialog(this, panel, "Compare jobs", javax.swing.JOptionPane.PLAIN_MESSAGE);
        status.setText("Comparison refreshed from the current CV and available jobs.");
    }

    private static String categorySummary(MatchService.Recommendation r, smarthire.matching.ScoringPolicy.Category category) {
        return r.match().contributions().stream().filter(c -> c.category() == category).findFirst()
                .map(c -> format(c.fraction() * 100) + "% met" + (c.missing().isEmpty() ? "" : " | Missing: " + String.join(", ", c.missing())))
                .orElse("Not required");
    }

    private static String categoryName(smarthire.matching.ScoringPolicy.Category category) {
        String name = category.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String friendly(String item) {
        return item.replace("(TECHNICAL)", "(technical skill)").replace("(SOFT)", "(soft skill)");
    }
}
