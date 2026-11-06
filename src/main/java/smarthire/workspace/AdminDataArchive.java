package smarthire.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import smarthire.documents.DocumentFailure;
import smarthire.documents.ManagedDocuments;
import smarthire.identity.UserError;
import static smarthire.workspace.AdminData.*;

final class AdminDataArchive {
    private final ZipOutputStream zip;

    private AdminDataArchive(ZipOutputStream zip) { this.zip = zip; }

    static ExportResult save(Path destination, Function<AdminDataArchive, ExportResult> build) {
        Path target = destination.toAbsolutePath().normalize();
        Path staged = null;
        try {
            if (Files.exists(target)) { throw new FileAlreadyExistsException(target.toString()); }
            staged = Files.createTempFile(target.getParent(), ".smarthire-export-", ".part");
            ExportResult result;
            try (var zip = new ZipOutputStream(Files.newOutputStream(staged))) {
                result = build.apply(new AdminDataArchive(zip));
            }
            Files.move(staged, target);
            return result;
        } catch (FileAlreadyExistsException exception) {
            throw new UserError("That export file already exists. Choose a new file name.");
        } catch (IOException | UncheckedIOException exception) {
            throw new UserError("Could not save the export. Check the destination and available space.");
        } finally {
            if (staged != null) {
                try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
            }
        }
    }

    ExportResult write(Store store, Target target, ManagedDocuments documents) {
        var originals = store.export(target, this::table);
        var unavailable = new StringBuilder();
        int missing = 0;
        for (var original : originals) {
            String name = original.id() + "." + original.format().extension();
            try { entry("originals/" + name, documents.read(original.id())); }
            catch (DocumentFailure exception) {
                if (exception.code() != DocumentFailure.Code.UNAVAILABLE) { throw exception; }
                unavailable.append(name).append('\n');
                missing++;
            }
        }
        entry("README.txt", ("SmartHire export for " + target.kind() + " / " + target.id() + "\n"
                + "Tables are UTF-8 text with tab-separated fields. Backslash escapes tabs, line breaks, backslashes and nulls.\n"
                + "Original files use their document IDs. See managed_documents.txt for the original names.\n"
                + "Unavailable or damaged originals (" + missing + ")\n" + unavailable).getBytes(StandardCharsets.UTF_8));
        return new ExportResult(missing);
    }

    private void table(Table table) {
        try {
            zip.putNextEntry(new ZipEntry(table.name() + ".txt"));
            line(table.columns());
            for (var row : table.rows()) { line(row); }
            zip.closeEntry();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    private void line(List<String> cells) throws IOException {
        String line = cells.stream().map(AdminDataArchive::escape).collect(Collectors.joining("\t")) + "\n";
        zip.write(line.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value == null ? "\\N" : value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    private void entry(String name, byte[] content) {
        try {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
}
