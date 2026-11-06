package smarthire;

import java.nio.file.Path;
import javax.swing.SwingUtilities;
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
        Path documentRoot = Path.of(System.getProperty("smarthire.documents", ".local/documents"));
        var opened = new smarthire.documents.ManagedDocuments(documentRoot, new smarthire.storage.JdbcDocumentReferences(database));
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
                var accounts = new JdbcAccounts(database);
                if (database.isCloud()) {
                    DemoSeed.provisionCloud(accounts, config.resolveSibling("aiven-demo-accounts.properties"));
                } else {
                    DemoSeed.provision(accounts);
                }
                DemoJobs.provision(database);
                System.out.println("Fictional demo accounts and jobs are ready. Existing records were preserved.");
            } catch (UserError exception) {
                System.err.println(exception.getMessage());
                System.exit(1);
            }
            return;
        }
        SwingUtilities.invokeLater(() -> {
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
                    identity -> new smarthire.workspace.WorkspaceService(identity, new smarthire.storage.JdbcWorkspace(Database.load(config, false)), documents),
                    identity -> new smarthire.cv.CvApprovalService(identity, new smarthire.storage.JdbcCvApprovals(Database.load(config, false)), documents));
        });
    }
}
