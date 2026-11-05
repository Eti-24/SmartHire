package smarthire;

import java.nio.file.Path;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import smarthire.identity.IdentityService;
import smarthire.identity.UserError;
import smarthire.storage.Database;
import smarthire.storage.JdbcAccounts;
import smarthire.ui.SmartHireWindow;

public final class Main {
    private Main() { }
    private static smarthire.documents.ManagedDocuments documents;

    private static synchronized void initializeDocuments(Database database) {
        if (documents != null) { return; }
        var opened = new smarthire.documents.ManagedDocuments(Path.of(".local/documents"), new smarthire.storage.JdbcDocumentReferences(database));
        try {
            var recovery = opened.reconcile();
            if (!recovery.unavailableReferences().isEmpty()) {
                System.err.println("Saved document files unavailable; history retained: " + recovery.unavailableReferences());
            }
            documents = opened;
            Runtime.getRuntime().addShutdownHook(new Thread(opened::close, "document-store-close"));
        } catch (RuntimeException exception) { opened.close(); throw exception; }
    }

    public static void main(String[] args) {
        Path config = Path.of(System.getProperty("smarthire.config", ".local/application.properties"));
        if (args.length == 1 && args[0].equals("--seed-demo")) {
            try {
                Database database = Database.load(config, false);
                database.initialize();
                DemoSeed.provision(new JdbcAccounts(database));
                DemoJobs.provision(database);
                System.out.println("Fictional demo accounts are ready. Existing accounts were preserved.");
            } catch (UserError exception) {
                System.err.println(exception.getMessage());
                System.exit(1);
            }
            return;
        }
        SwingUtilities.invokeLater(() -> {
            UIManager.put("Panel.background", java.awt.Color.WHITE);
            UIManager.put("Label.font", new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 14));
            SmartHireWindow window = new SmartHireWindow();
            window.setVisible(true);
            window.connect(() -> {
                Database database = Database.load(config, false);
                database.initialize();
                initializeDocuments(database);
                return new IdentityService(new JdbcAccounts(database));
            }, identity -> new smarthire.jobs.JobService(identity, new smarthire.storage.JdbcJobs(Database.load(config, false)), documents),
                    identity -> new smarthire.cv.CvService(identity, new smarthire.storage.JdbcCvs(Database.load(config, false)), documents),
                    identity -> new smarthire.applications.ApplicationService(identity, new smarthire.storage.JdbcApplications(Database.load(config, false)), documents),
                    identity -> new smarthire.workspace.WorkspaceService(identity, new smarthire.storage.JdbcWorkspace(Database.load(config, false))),
                    identity -> new smarthire.cv.CvApprovalService(identity, new smarthire.storage.JdbcCvApprovals(Database.load(config, false)), documents));
        });
    }
}
