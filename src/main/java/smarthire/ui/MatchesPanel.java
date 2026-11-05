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
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import smarthire.identity.UserError;
import smarthire.matching.MatchService;

/** All matching and database access runs outside the event-dispatch thread. */
final class MatchesPanel extends JPanel {
    private final MatchService service;
    private final UUID session;
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Job", "Company", "Match %", "Eligibility"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JTextArea explanation = new JTextArea(14, 45);
    private final JLabel status = new JLabel("Loading matches…");
    private final JButton refresh = new JButton("Refresh recommendations");
    private final JButton openJob = new JButton("Open selected job");
    private final JButton explain = new JButton("Why this match?");
    private final JButton compare = new JButton("Compare two jobs");
    private final JCheckBox scoreDetails = new JCheckBox("Show score details");
    private MatchService.Recommendation displayed;
    private List<MatchService.Recommendation> results = List.of();
    private boolean busy, departed;

    MatchesPanel(MatchService service, UUID session, Runnable back, Consumer<Long> onOpenJob) {
        super(new BorderLayout(12, 12));
        this.service = service; this.session = session;
        var controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
        var home = new JButton("Back to overview");
        controls.add(home); controls.add(refresh);
        add(controls, BorderLayout.NORTH);
        table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        explanation.setEditable(false);
        explanation.setLineWrap(true); explanation.setWrapStyleWord(true);
        var jobList = new JPanel(new BorderLayout(8, 8));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        actions.add(openJob); actions.add(explain); actions.add(compare);
        jobList.add(new JScrollPane(table), BorderLayout.CENTER); jobList.add(actions, BorderLayout.SOUTH);
        var matchDetails = new JPanel(new BorderLayout());
        matchDetails.add(scoreDetails, BorderLayout.NORTH); matchDetails.add(new JScrollPane(explanation), BorderLayout.CENTER);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, jobList, matchDetails);
        split.setResizeWeight(0.4);
        add(split, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        home.addActionListener(event -> { departed = true; back.run(); });
        refresh.addActionListener(event -> refresh());
        openJob.addActionListener(event -> {
            int selected = table.getSelectedRow();
            if (table.getSelectedRowCount() != 1) { status.setText("Select one job to open."); return; }
            departed = true;
            onOpenJob.accept(results.get(selected).job().id());
        });
        scoreDetails.addActionListener(event -> { if (displayed != null) { explain(displayed); } });
        explain.addActionListener(event -> {
            int selected = table.getSelectedRow();
            if (table.getSelectedRowCount() != 1) { status.setText("Select one job to see its explanation."); return; }
            long id = results.get(selected).job().id();
            displayed = null; explanation.setText("");
            run(() -> service.open(session, id), this::explain);
        });
        compare.addActionListener(event -> {
            int[] selected = table.getSelectedRows();
            if (selected.length != 2) { status.setText("Select two jobs using Ctrl or Shift, then choose Compare two jobs."); return; }
            long first = results.get(selected[0]).job().id(), second = results.get(selected[1]).job().id();
            run(() -> service.compare(session, first, second), this::compare);
        });
        refresh();
    }

    private void refresh() {
        displayed = null; explanation.setText(""); model.setRowCount(0); results = List.of();
        run(() -> service.recommend(session), recommendations -> {
            results = recommendations.jobs();
            if (recommendations.needsReview()) {
                status.setText("Confirm a CV review in My CV to get recommendations.");
                explanation.setText("Upload and confirm your qualifications in My CV, then return here. Unconfirmed extraction suggestions do not contribute to matches.");
                return;
            }
            for (var r : results) {
                model.addRow(new Object[]{r.job().details().title(), r.job().companyName(), format(r.match().percentage()),
                        r.match().eligible() ? "Eligible" : "Not eligible"});
            }
            status.setText(results.isEmpty() ? "No approved open jobs are available." : results.size() + " matches. Select one to open, or Ctrl-select two to compare.");
        });
    }

    private void explain(MatchService.Recommendation r) {
        displayed = r;
        var match = r.match();
        var text = new StringBuilder(r.job().details().title()).append(" — ").append(r.job().companyName())
                .append("\n\nMatch: ").append(format(match.percentage())).append("%")
                .append(match.eligible() ? "\nEligible — you meet all mandatory requirements." : "\nNot eligible — some mandatory requirements are missing.");
        if (!match.eligible()) {
            text.append("\n\nRequired before you can apply:\n");
            match.failedMandatory().forEach(item -> text.append("  • ").append(friendly(item)).append('\n'));
        }
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
        if (scoreDetails.isSelected()) {
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
        explanation.setText(text.toString()); explanation.setCaretPosition(0);
        status.setText("Based on your latest confirmed CV. The match score shows fit, not a hiring guarantee.");
    }

    private <T> void run(Callable<T> task, Consumer<T> success) {
        if (busy) { return; }
        busy = true; refresh.setEnabled(false); openJob.setEnabled(false); explain.setEnabled(false); compare.setEnabled(false); status.setText("Loading…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false;
                if (departed) { return; }
                refresh.setEnabled(true); openJob.setEnabled(true); explain.setEnabled(true); compare.setEnabled(true);
                try { success.accept(get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); status.setText("Interrupted. Refresh to try again."); }
                catch (ExecutionException e) {
                    status.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "Could not calculate matches. Check the database and try again.");
                }
            }
        }.execute();
    }

    private static String format(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    private void compare(MatchService.Comparison comparison) {
        var first = comparison.first(); var second = comparison.second();
        var model = new DefaultTableModel(new String[]{"", first.job().details().title(), second.job().details().title()}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        model.addRow(new Object[]{"Company", first.job().companyName(), second.job().companyName()});
        model.addRow(new Object[]{"Location", first.job().details().location(), second.job().details().location()});
        model.addRow(new Object[]{"Salary", first.job().details().salaryMin() + "–" + first.job().details().salaryMax(), second.job().details().salaryMin() + "–" + second.job().details().salaryMax()});
        model.addRow(new Object[]{"Match", format(first.match().percentage()) + "%", format(second.match().percentage()) + "%"});
        model.addRow(new Object[]{"Eligibility", first.match().eligible() ? "Eligible" : "Not eligible", second.match().eligible() ? "Eligible" : "Not eligible"});
        for (var category : smarthire.matching.ScoringPolicy.Category.values()) {
            model.addRow(new Object[]{categoryName(category), categorySummary(first, category), categorySummary(second, category)});
        }
        model.addRow(new Object[]{"Mandatory gaps", String.join("; ", first.match().failedMandatory()), String.join("; ", second.match().failedMandatory())});
        model.addRow(new Object[]{"Text similarity (TF-IDF/cosine)", format(comparison.firstTextSimilarity() * 100) + "%", format(comparison.secondTextSimilarity() * 100) + "%"});
        var panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JScrollPane(new JTable(model)), BorderLayout.CENTER);
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
