package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import smarthire.applications.ApplicationService;
import smarthire.applications.RecruiterFeedback;
import smarthire.identity.UserError;

final class RecruiterFeedbackPanel extends JPanel {
    private final ApplicationService service;
    private final UUID session;
    private final UUID applicationId;
    private final JPanel body = new JPanel(new BorderLayout(0, 8));
    private final JTextArea message = WorkspaceStyle.message("");

    RecruiterFeedbackPanel(ApplicationService service, UUID session, UUID applicationId) {
        super(new BorderLayout(0, 8));
        this.service = service;
        this.session = session;
        this.applicationId = applicationId;
        add(body, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        reload();
    }

    private void reload() { work(() -> service.feedback(session, applicationId), this::render); }

    private void render(RecruiterFeedback.Review review) {
        var form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(new JLabel("Private to your company in applicant review. Included in authorized admin exports."));
        var saved = review.feedback();
        var notes = textarea(saved.notes());
        form.add(label("Recruiter notes", notes));
        form.add(new JScrollPane(notes));
        var score = saved.scorecard();
        Integer occurrence = score == null ? review.completedInterviewRevision() : Integer.valueOf(score.interviewRevision());
        var include = new JCheckBox("Keep an interview scorecard", score != null);
        include.setEnabled(occurrence != null);
        var technical = rating(score == null ? null : score.technical());
        var communication = rating(score == null ? null : score.communication());
        var fit = rating(score == null ? null : score.roleFit());
        var comments = textarea(score == null ? "" : score.comments());
        form.add(include);
        if (occurrence == null) { form.add(new JLabel("A scorecard is available after a confirmed interview ends.")); }
        var ratings = new JPanel(new GridLayout(0, 2, 5, 5));
        ratings.add(label("Technical evidence", technical)); ratings.add(technical);
        ratings.add(label("Communication", communication)); ratings.add(communication);
        ratings.add(label("Role fit", fit)); ratings.add(fit);
        form.add(ratings);
        form.add(label("Interview feedback", comments));
        form.add(new JScrollPane(comments));
        Runnable updateScoreControls = () -> {
            boolean enabled = include.isSelected();
            for (var component : List.of(technical, communication, fit, comments)) { component.setEnabled(enabled); }
        };
        include.addActionListener(event -> updateScoreControls.run());
        updateScoreControls.run();
        var actions = WorkspaceStyle.actions();
        actions.add(WorkspaceStyle.button("Save feedback", () -> {
            String noteText = notes.getText();
            var scorecard = include.isSelected() ? new RecruiterFeedback.Scorecard(occurrence,
                    selectedRating(technical), selectedRating(communication), selectedRating(fit), comments.getText()) : null;
            work(() -> service.saveFeedback(session, applicationId, saved.revision(), noteText, scorecard), updated -> {
                render(updated);
                message.setText("Recruiter feedback saved.");
            });
        }));
        actions.add(WorkspaceStyle.button("Refresh", this::reload));
        actions.add(WorkspaceStyle.button("Compare feedback", this::compare));
        body.removeAll();
        body.add(WorkspaceStyle.scroll(form), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        body.revalidate();
        body.repaint();
    }

    private void compare() {
        work(() -> service.feedbackCandidates(session, applicationId), candidates -> {
            if (candidates.isEmpty()) { message.setText("There are no other applicants to this posting."); return; }
            var select = new JComboBox<>(candidates.toArray(RecruiterFeedback.Candidate[]::new));
            if (JOptionPane.showConfirmDialog(this, select, "Choose another applicant", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) { return; }
            var chosen = (RecruiterFeedback.Candidate) select.getSelectedItem();
            work(() -> service.compareFeedback(session, applicationId, chosen.applicationId()), reviews -> {
                var columns = new JPanel(new GridLayout(1, 2, 10, 0));
                for (var review : reviews) { columns.add(WorkspaceStyle.text(summary(review))); }
                JOptionPane.showMessageDialog(this, columns, "Compare interview feedback", JOptionPane.PLAIN_MESSAGE);
            });
        });
    }

    private static String summary(RecruiterFeedback.Review review) {
        var feedback = review.feedback();
        var score = feedback.scorecard();
        return review.applicant() + "\n\nTechnical evidence: " + displayRating(score == null ? null : score.technical())
                + "\nCommunication: " + displayRating(score == null ? null : score.communication())
                + "\nRole fit: " + displayRating(score == null ? null : score.roleFit())
                + "\n\nInterview feedback\n" + (score == null ? "" : score.comments()) + "\n\nRecruiter notes\n" + feedback.notes();
    }

    private static String displayRating(Integer value) { return value == null ? "Not rated" : value + " / 5"; }
    private static Integer selectedRating(JComboBox<String> box) { return box.getSelectedIndex() == 0 ? null : box.getSelectedIndex(); }
    private static JComboBox<String> rating(Integer value) {
        var box = new JComboBox<>(new String[]{"Not rated", "1", "2", "3", "4", "5"});
        box.setSelectedIndex(value == null ? 0 : value);
        return box;
    }

    private static JTextArea textarea(String value) {
        var area = new JTextArea(value, 4, 25);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static JLabel label(String value, JComponent target) {
        var label = new JLabel(value);
        label.setLabelFor(target);
        return label;
    }

    private <T> void work(Callable<T> task, Consumer<T> success) {
        Runnable restore = WorkspaceStyle.disable(body);
        message.setText("Working…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                if (!isDisplayable()) { return; }
                restore.run();
                try { T result = get(); message.setText(""); success.accept(result); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Loading interrupted."); }
                catch (ExecutionException e) { message.setText(e.getCause() instanceof UserError error ? error.getMessage() : "Could not load or save feedback."); }
            }
        }.execute();
    }
}
