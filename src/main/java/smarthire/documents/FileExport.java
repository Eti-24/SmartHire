package smarthire.documents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Stage a complete export beside its destination before publishing it. */
public final class FileExport {
    private FileExport() {}

    public static Path withExtension(Path path, String extension) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + extension)
                ? path : path.resolveSibling(path.getFileName() + "." + extension);
    }

    public static void save(InputStream source, Path destination, boolean overwrite) throws IOException {
        Path target = destination.toAbsolutePath();
        Path staged = Files.createTempFile(target.getParent(), ".smarthire-", ".part");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            if (overwrite) {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(staged, target);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
