package smarthire.ui;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import smarthire.documents.FileExport;

final class FileSaveDialog {
    static final String CV = "SmartHire-CV-Template.docx";
    static final String CIRCULAR = "SmartHire-Job-Circular-Template.docx";

    private FileSaveDialog() {}

    static void template(Component parent, String name, Consumer<String> message) {
        save(parent, name, "Word document", "docx", () -> {
            var source = FileSaveDialog.class.getResourceAsStream("/templates/" + name);
            if (source == null) { throw new IOException("Template resource missing."); }
            return source;
        }, "Template saved. Replace the bracketed prompts in Word before uploading.", message);
    }

    static void save(Component parent, String name, String format, String extension, Callable<InputStream> source,
            String success, Consumer<String> message) {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Save " + format);
        chooser.setFileFilter(new FileNameExtensionFilter(format + " (*." + extension + ")", extension));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setSelectedFile(new File(name));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) { return; }
        var destination = FileExport.withExtension(chooser.getSelectedFile().toPath(), extension);
        boolean overwrite = Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (overwrite && JOptionPane.showConfirmDialog(parent,
                "Replace " + destination.getFileName() + "?", "Confirm replacement",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) { return; }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                try (var input = source.call()) {
                    FileExport.save(input, destination, overwrite);
                }
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                    message.accept(success);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    var failure = exception.getCause();
                    message.accept(failure instanceof smarthire.identity.UserError ? failure.getMessage()
                            : "Could not save the file. Choose a writable destination and try again.");
                }
            }
        }.execute();
    }
}
