package smarthire.ui;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.identity.Role;
import smarthire.jobs.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real asynchronous panels with the space available beside laptop navigation. */
@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class SavedItemsUiIntegrationTest {
    @TempDir Path root;

    @Test void discoverySaveControlsStayReachableOnCompactScreen() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            f.publish();
            JFrame frame = mount(() -> new JobsPanel(f.jobs, f.student, Role.STUDENT, null));
            try {
                await(() -> find(frame, JButton.class, "Save this search") != null);
                edt(() -> {
                    for (String label : new String[]{"Search", "More filters", "Save this search", "Clear", "Save job"}) {
                        var button = find(frame, JButton.class, label);
                        assertNotNull(button, label);
                        Component child = button;
                        while (child.getParent() != null && child != frame.getContentPane()) {
                            assertTrue(child.getX() >= 0 && child.getY() >= 0
                                    && child.getX() + child.getWidth() <= child.getParent().getWidth()
                                    && child.getY() + child.getHeight() <= child.getParent().getHeight(),
                                    label + " clipped by " + child.getParent().getClass().getSimpleName());
                            child = child.getParent();
                        }
                    }
                    return null;
                });
                edt(() -> { find(frame, JButton.class, "Save job").doClick(); return null; });
                await(() -> find(frame, JButton.class, "Save job").isEnabled()
                        && find(frame, JTextArea.class, null) != null);
                // Wait for the actual transaction, rather than only button feedback.
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (f.jobs.savedJobs(f.student).isEmpty() && System.nanoTime() < until) { Thread.sleep(40); }
                assertEquals(1, f.jobs.savedJobs(f.student).size());
                edt(() -> {
                    var timer = new Timer(100, event -> {
                        for (var window : java.awt.Window.getWindows()) {
                            if (window instanceof JDialog dialog && dialog.isVisible()) {
                                var pane = find(dialog, JOptionPane.class, null);
                                if (pane != null) { pane.setInputValue("UI saved search"); pane.setValue(JOptionPane.OK_OPTION); }
                            }
                        }
                    });
                    timer.start();
                    try { find(frame, JButton.class, "Save this search").doClick(); }
                    finally { timer.stop(); }
                    return null;
                });
                until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (f.jobs.savedSearches(f.student).isEmpty() && System.nanoTime() < until) { Thread.sleep(40); }
                assertEquals("UI saved search", f.jobs.savedSearches(f.student).getFirst().name());
            } finally { edt(() -> { frame.dispose(); return null; }); }
        }
    }

    @Test void savedPanelsLoadOpenPauseResumeAndRemove() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            long job = f.publish();
            f.jobs.saveBookmark(f.student, job);
            var opened = new AtomicReference<Long>();
            JFrame frame = mount(() -> new SavedJobsPanel(f.jobs, f.student, opened::set, null));
            try {
                await(() -> find(frame, JButton.class, "Open job") != null);
                edt(() -> { find(frame, JButton.class, "Open job").doClick(); return null; });
                assertEquals(job, opened.get());
                edt(() -> { find(frame, JButton.class, "Remove saved job").doClick(); return null; });
                await(() -> find(frame, JTable.class, null).getRowCount() == 0);
                assertTrue(f.jobs.savedJobs(f.student).isEmpty());
            } finally { edt(() -> { frame.dispose(); return null; }); }

            var criteria = new JobSearch("Workflow", f.category);
            f.jobs.saveSearch(f.student, new SavedSearch(0, "My search", criteria, true));
            var ran = new AtomicReference<JobSearch>();
            JFrame searches = mount(() -> new SavedSearchPanel(f.jobs, f.student, ran::set));
            try {
                await(() -> find(searches, JTable.class, null) != null);
                edt(() -> {
                    find(searches, JTable.class, null).setRowSelectionInterval(0, 0);
                    find(searches, JButton.class, "Run search").doClick();
                    find(searches, JButton.class, "Pause alerts").doClick();
                    return null;
                });
                assertEquals(criteria, ran.get());
                await(() -> "Paused".equals(find(searches, JTable.class, null).getValueAt(0, 3)));
                edt(() -> {
                    find(searches, JTable.class, null).setRowSelectionInterval(0, 0);
                    find(searches, JButton.class, "Resume alerts").doClick(); return null;
                });
                await(() -> "Active".equals(find(searches, JTable.class, null).getValueAt(0, 3)));
                edt(() -> {
                    find(searches, JTable.class, null).setRowSelectionInterval(0, 0);
                    confirmDialogs(() -> find(searches, JButton.class, "Edit").doClick(), dialog -> {
                        if ("Edit saved search".equals(dialog.getTitle())) {
                            find(dialog, JTextField.class, null).setText("Edited UI search");
                        }
                    });
                    return null;
                });
                await(() -> "Edited UI search".equals(find(searches, JTable.class, null).getValueAt(0, 0)));
                edt(() -> {
                    find(searches, JTable.class, null).setRowSelectionInterval(0, 0);
                    confirmDialogs(() -> find(searches, JButton.class, "Delete").doClick(), dialog -> {});
                    return null;
                });
                await(() -> find(searches, JTable.class, null).getRowCount() == 0);
                assertTrue(f.jobs.savedSearches(f.student).isEmpty());
            } finally { edt(() -> { searches.dispose(); return null; }); }
        }
    }

    @Test void savingFromMatchesPersistsTheSelectedPosting() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            long job = f.publish();
            f.confirm(f.student, java.util.List.of(f.skill));
            JFrame frame = mount(() -> new MatchesPanel(new smarthire.matching.MatchService(f.identity, f.cvs, f.jobs),
                    f.cvs, f.student, ignored -> {}, id -> f.jobs.saveBookmark(f.student, id), () -> {}));
            try {
                await(() -> find(frame, JTable.class, null).getRowCount() == 1);
                edt(() -> {
                    find(frame, JTable.class, null).setRowSelectionInterval(0, 0);
                    find(frame, JButton.class, "Save job").doClick(); return null;
                });
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (f.jobs.savedJobs(f.student).isEmpty() && System.nanoTime() < until) { Thread.sleep(40); }
                assertEquals(job, f.jobs.savedJobs(f.student).getFirst().jobId());
            } finally { edt(() -> { frame.dispose(); return null; }); }
        }
    }

    private static void confirmDialogs(Runnable action, java.util.function.Consumer<JDialog> edit) {
        var timer = new Timer(100, event -> {
            for (var window : java.awt.Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isVisible()) {
                    var pane = find(dialog, JOptionPane.class, null);
                    if (pane != null) { edit.accept(dialog); pane.setValue(JOptionPane.OK_OPTION); }
                }
            }
        });
        timer.start();
        try { action.run(); } finally { timer.stop(); }
    }

    private static JFrame mount(Callable<JPanel> panel) throws Exception {
        return edt(() -> {
            WorkspaceStyle.install();
            var frame = new JFrame("Saved workflow test");
            frame.setContentPane(panel.call());
            frame.setSize(800, 510);
            frame.setVisible(true);
            return frame;
        });
    }

    private static <T extends Component> T find(Container parent, Class<T> type, String label) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && (label == null || child instanceof JButton button && label.equals(button.getText()))) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                T found = find(container, type, label);
                if (found != null) { return found; }
            }
        }
        return null;
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < until) {
            if (edt(condition)) { return; }
            Thread.sleep(40);
        }
        fail("Timed out waiting for saved-item UI");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new java.util.concurrent.FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
