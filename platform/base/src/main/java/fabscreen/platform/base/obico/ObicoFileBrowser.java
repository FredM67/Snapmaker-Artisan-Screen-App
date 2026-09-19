package fabscreen.platform.base.obico;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import fabscreen.platform.base.lib.file.FabLocalFile;
import fabscreen.platform.base.lib.file.FabUsbFile;
import fabscreen.platform.base.lib.file.IFile;
import fabscreen.platform.base.lib.file.FabUsbPartition;
import fabscreen.platform.base.service.IAppService;
import fabscreen.platform.base.service.IFileManagerService;

/** Read-only, bounded OctoPrint-style view of Artisan's G-code storage for Obico. */
public final class ObicoFileBrowser {
    private static final int MAX_PATH_BYTES = 4096;
    private static final int MAX_SEGMENT_BYTES = 255;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_VISITED = 8000;
    private static final int MAX_RESULTS = 2000;
    private static final long MAX_SCAN_NANOS = 2_000_000_000L;

    private ObicoFileBrowser() {
    }

    public static JsonObject listFiles(
            IAppService appService,
            IFileManagerService fileManager,
            String path,
            boolean recursive,
            Integer level,
            String filter
    ) throws IOException {
        return listFiles(
                appService.getFilesDir(),
                usbRoot(fileManager),
                path,
                recursive,
                level,
                filter);
    }

    public static IFile resolveFile(
            IAppService appService,
            IFileManagerService fileManager,
            String path
    ) throws IOException {
        SelectedPath selected = parsePath(path, true);
        File root = "artisan".equals(selected.source)
                ? appService.getFilesDir()
                : usbRoot(fileManager);
        if (root == null) {
            throw new IOException("USB storage is not connected.");
        }
        File file = resolve(root, selected.relativePath);
        if (!file.isFile() || !isGcode(file.getName()) || file.length() <= 0L) {
            throw new IOException("Selected G-code file is unavailable.");
        }
        return "artisan".equals(selected.source)
                ? new FabLocalFile(file)
                : new FabUsbFile(file);
    }

    // File-root overload keeps path and listing policy testable without Android services.
    static JsonObject listFiles(
            File localRoot,
            File usbRoot,
            String path,
            boolean recursive,
            Integer level,
            String filter
    ) throws IOException {
        if (localRoot == null || !localRoot.isDirectory()) {
            throw new IOException("Artisan file storage is unavailable.");
        }
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.US);
        if (needle.length() > 255) {
            throw new IOException("Search term is too long.");
        }
        SelectedPath selected = needle.isEmpty() ? parsePath(path, false) : null;
        ScanBudget budget = new ScanBudget();
        JsonObject items = new JsonObject();

        if (!needle.isEmpty()) {
            collectMatches(localRoot, "artisan", "", needle, 0, items, budget);
            if (usbRoot != null && usbRoot.isDirectory()) {
                collectMatches(usbRoot, "usb", "", needle, 0, items, budget);
            }
        } else if (selected == null) {
            addStorage(items, localRoot, "artisan", "Artisan storage", budget);
            if (usbRoot != null && usbRoot.isDirectory()) {
                addStorage(items, usbRoot, "usb", "USB storage", budget);
            }
        } else {
            File root = "artisan".equals(selected.source) ? localRoot : usbRoot;
            if (root == null || !root.isDirectory()) {
                throw new IOException("Requested storage is unavailable.");
            }
            File directory = selected.relativePath.isEmpty()
                    ? root.getCanonicalFile()
                    : resolve(root, selected.relativePath);
            if (!directory.isDirectory()) {
                throw new IOException("Requested folder is unavailable.");
            }
            int childLevels = recursive ? MAX_DEPTH : Math.max(0, Math.min(1, level == null ? 0 : level));
            collectChildren(root, directory, selected.source, selected.relativePath,
                    0, childLevels, items, budget);
        }

        JsonObject result = new JsonObject();
        result.add("local", items);
        return result;
    }

    static File resolve(File root, String relativePath) throws IOException {
        if (root == null) {
            throw new IOException("Storage is unavailable.");
        }
        File canonicalRoot = root.getCanonicalFile();
        if (!canonicalRoot.isDirectory()) {
            throw new IOException("Storage is unavailable.");
        }
        File absolute = new File(canonicalRoot,
                relativePath.replace('/', File.separatorChar)).getAbsoluteFile();
        File canonical = absolute.getCanonicalFile();
        String rootPath = canonicalRoot.getPath();
        if (!canonical.getPath().startsWith(rootPath + File.separator)
                || !canonical.getPath().equals(absolute.getPath())) {
            throw new IOException("Selected path is outside storage or uses a link.");
        }
        return canonical;
    }

    static SelectedPath parsePath(String path, boolean requireFile) throws IOException {
        if (path == null || path.isEmpty()) {
            if (requireFile) throw new IOException("A G-code path is required.");
            return null;
        }
        if (path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES
                || path.startsWith("/")
                || path.endsWith("/")
                || path.indexOf('\\') >= 0) {
            throw new IOException("Unsafe G-code path.");
        }
        String[] segments = path.split("/", -1);
        if (!"artisan".equals(segments[0]) && !"usb".equals(segments[0])) {
            throw new IOException("Unknown G-code storage.");
        }
        if (requireFile && segments.length < 2) {
            throw new IOException("A G-code file is required.");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.startsWith(".")
                    || segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
                throw new IOException("Unsafe G-code path segment.");
            }
            for (int i = 0; i < segment.length(); i++) {
                if (Character.isISOControl(segment.charAt(i))) {
                    throw new IOException("Unsafe G-code path character.");
                }
            }
        }
        String relative = path.substring(segments[0].length());
        if (relative.startsWith("/")) relative = relative.substring(1);
        if (requireFile && !isGcode(segments[segments.length - 1])) {
            throw new IOException("Only .gcode files can be selected.");
        }
        return new SelectedPath(segments[0], relative);
    }

    private static File usbRoot(IFileManagerService fileManager) {
        if (fileManager == null) return null;
        FabUsbPartition partition = fileManager.getFabUsbDevice();
        if (partition == null || partition.getRootFile() == null) return null;
        return new File(partition.getRootFile().getAbsolutePath());
    }

    private static void addStorage(
            JsonObject items, File root, String source, String display, ScanBudget budget
    ) throws IOException {
        JsonObject folder = folder(source, source, display);
        collectChildren(root, root.getCanonicalFile(), source, "", 0, 0,
                folder.getAsJsonObject("children"), budget);
        items.add(source, folder);
    }

    private static void collectChildren(
            File root,
            File directory,
            String source,
            String relativeDirectory,
            int depth,
            int childLevels,
            JsonObject output,
            ScanBudget budget
    ) throws IOException {
        if (depth > MAX_DEPTH || !budget.canContinue()) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            if (!budget.visit()) return;
            String name = child.getName();
            if (!safeEntryName(name)) continue;
            String relative = relativeDirectory.isEmpty() ? name : relativeDirectory + "/" + name;
            if ((source + "/" + relative).getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
                continue;
            }
            File resolved;
            try {
                resolved = resolve(root, relative);
            } catch (IOException ignored) {
                continue;
            }
            String path = source + "/" + relative;
            if (resolved.isDirectory()) {
                JsonObject entry = folder(path, name, name);
                if (childLevels > 0) {
                    collectChildren(root, resolved, source, relative, depth + 1,
                            childLevels - 1, entry.getAsJsonObject("children"), budget);
                }
                output.add(name, entry);
                budget.result();
            } else if (resolved.isFile() && isGcode(name)) {
                output.add(name, file(path, name, resolved));
                budget.result();
            }
            if (!budget.canContinue()) return;
        }
    }

    private static void collectMatches(
            File root,
            String source,
            String relativeDirectory,
            String needle,
            int depth,
            JsonObject output,
            ScanBudget budget
    ) throws IOException {
        if (depth > MAX_DEPTH || !budget.canContinue()) return;
        File directory = relativeDirectory.isEmpty()
                ? root.getCanonicalFile() : resolve(root, relativeDirectory);
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            if (!budget.visit()) return;
            String name = child.getName();
            if (!safeEntryName(name)) continue;
            String relative = relativeDirectory.isEmpty() ? name : relativeDirectory + "/" + name;
            if ((source + "/" + relative).getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
                continue;
            }
            File resolved;
            try {
                resolved = resolve(root, relative);
            } catch (IOException ignored) {
                continue;
            }
            if (resolved.isDirectory()) {
                collectMatches(root, source, relative, needle, depth + 1, output, budget);
            } else if (resolved.isFile() && isGcode(name)
                    && name.toLowerCase(Locale.US).contains(needle)) {
                String path = source + "/" + relative;
                output.add(path, file(path, name, resolved));
                budget.result();
            }
            if (!budget.canContinue()) return;
        }
    }

    private static JsonObject folder(String path, String name, String display) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("display", display);
        entry.addProperty("path", path);
        entry.addProperty("type", "folder");
        entry.add("children", new JsonObject());
        return entry;
    }

    private static JsonObject file(String path, String name, File source) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("display", name);
        entry.addProperty("path", path);
        entry.addProperty("type", "machinecode");
        entry.addProperty("origin", "local");
        entry.addProperty("size", source.length());
        entry.addProperty("date", Math.max(0L, source.lastModified() / 1000L));
        return entry;
    }

    private static boolean isGcode(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".gcode");
    }

    private static boolean safeEntryName(String name) {
        if (name == null || name.isEmpty() || name.startsWith(".")
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        return true;
    }

    static final class SelectedPath {
        final String source;
        final String relativePath;

        SelectedPath(String source, String relativePath) {
            this.source = source;
            this.relativePath = relativePath;
        }
    }

    private static final class ScanBudget {
        final long deadline = System.nanoTime() + MAX_SCAN_NANOS;
        int visited;
        int results;

        boolean canContinue() {
            return visited < MAX_VISITED
                    && results < MAX_RESULTS
                    && System.nanoTime() < deadline;
        }

        boolean visit() {
            if (!canContinue()) return false;
            visited++;
            return true;
        }

        void result() {
            results++;
        }
    }
}
