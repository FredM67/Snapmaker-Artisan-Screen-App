package fabscreen.platform.base.obico;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads an Obico-hosted G-code file into FabScreen's private storage.
 *
 * <p>Redirects are handled manually so every destination is checked before a connection is
 * made. DNS answers are then pinned for the lifetime of the download to avoid validating one
 * address and connecting to another. A completed file is published only after it has been fully
 * streamed to a unique staging file in the same directory.</p>
 */
public final class ObicoGcodeDownloader {
    public interface ResultCallback {
        void onSuccess(File file);

        void onError(String safeMessage);
    }

    /** A single in-flight download. Cancellation is idempotent. */
    public interface DownloadHandle {
        /** Cancels network and file I/O and reports one cancellation error to the callback. */
        void cancel();

        boolean isCancelled();
    }

    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final String UPLOAD_DIRECTORY = "ObicoUpload";
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final Object PUBLISH_LOCK = new Object();
    private static final Executor VALIDATION_EXECUTOR = Executors.newCachedThreadPool(
            new DownloaderThreadFactory());

    private final OkHttpClient baseClient;
    private final Executor validationExecutor;
    private final HostResolver resolver;

    public ObicoGcodeDownloader() {
        this(defaultClient(), VALIDATION_EXECUTOR, InetAddress::getAllByName);
    }

    ObicoGcodeDownloader(
            OkHttpClient baseClient,
            Executor validationExecutor,
            HostResolver resolver) {
        if (baseClient == null || validationExecutor == null || resolver == null) {
            throw new IllegalArgumentException("Downloader dependencies are required");
        }
        this.baseClient = baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
        this.validationExecutor = validationExecutor;
        this.resolver = resolver;
    }

    /**
     * Starts a download without blocking the caller.
     *
     * @param appFilesDirectory the application's private files directory
     * @param configuredServerUrl the configured Obico server URL
     * @param allowInsecureServer whether that configured server may use HTTP
     * @param downloadUrl the absolute URL supplied by Obico
     * @param requestedSafeFilename Obico's requested local filename
     * @param callback receives exactly one terminal result
     */
    public DownloadHandle download(
            File appFilesDirectory,
            String configuredServerUrl,
            boolean allowInsecureServer,
            String downloadUrl,
            String requestedSafeFilename,
            ResultCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("A result callback is required");
        }
        Completion completion = new Completion(callback);
        DownloadHandleImpl handle = new DownloadHandleImpl(completion);
        try {
            validationExecutor.execute(() -> startDownload(
                    appFilesDirectory,
                    configuredServerUrl,
                    allowInsecureServer,
                    downloadUrl,
                    requestedSafeFilename,
                    handle));
        } catch (RejectedExecutionException exception) {
            handle.fail("The G-code download could not be started.");
        }
        return handle;
    }

    private void startDownload(
            File appFilesDirectory,
            String configuredServerUrl,
            boolean allowInsecureServer,
            String downloadUrl,
            String requestedSafeFilename,
            DownloadHandleImpl handle) {
        if (handle.isComplete()) {
            return;
        }
        File partFile = null;
        try {
            String safeFilename = sanitizeGcodeFilename(requestedSafeFilename);
            HttpUrl serverUrl = validateServerUrl(configuredServerUrl, allowInsecureServer);
            HttpUrl initialUrl = validateDownloadUrl(
                    serverUrl,
                    allowInsecureServer,
                    downloadUrl);
            File uploadDirectory = prepareUploadDirectory(appFilesDirectory);
            partFile = File.createTempFile(".obico-", ".part", uploadDirectory);
            if (!handle.attachPartFile(partFile)) {
                deleteQuietly(partFile);
                return;
            }

            DownloadOperation operation = new DownloadOperation(
                    serverUrl,
                    allowInsecureServer,
                    safeFilename,
                    uploadDirectory,
                    partFile,
                    handle);
            operation.follow(initialUrl, 0);
        } catch (IllegalArgumentException exception) {
            deleteQuietly(partFile);
            handle.fail(exception.getMessage());
        } catch (IOException exception) {
            deleteQuietly(partFile);
            handle.fail("FabScreen could not prepare storage for this G-code file.");
        } catch (RuntimeException exception) {
            deleteQuietly(partFile);
            handle.fail("The G-code download could not be started.");
        }
    }

    private final class DownloadOperation {
        private final HttpUrl serverUrl;
        private final boolean allowInsecureServer;
        private final String safeFilename;
        private final File uploadDirectory;
        private final File partFile;
        private final DownloadHandleImpl handle;
        private final Map<String, List<InetAddress>> approvedAddresses = new ConcurrentHashMap<>();
        private final OkHttpClient client;

        DownloadOperation(
                HttpUrl serverUrl,
                boolean allowInsecureServer,
                String safeFilename,
                File uploadDirectory,
                File partFile,
                DownloadHandleImpl handle) {
            this.serverUrl = serverUrl;
            this.allowInsecureServer = allowInsecureServer;
            this.safeFilename = safeFilename;
            this.uploadDirectory = uploadDirectory;
            this.partFile = partFile;
            this.handle = handle;
            this.client = baseClient.newBuilder().dns(new PinnedDns(approvedAddresses)).build();
        }

        void follow(HttpUrl target, int redirectCount) {
            if (handle.isComplete()) {
                deleteQuietly(partFile);
                return;
            }
            try {
                approveTarget(serverUrl, target, resolver, approvedAddresses);
            } catch (IllegalArgumentException exception) {
                fail(exception.getMessage());
                return;
            }

            Request request = new Request.Builder()
                    .url(target)
                    .get()
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "FabScreen-Obico")
                    .build();
            try {
                Call call = client.newCall(request);
                if (!handle.attachCall(call)) {
                    call.cancel();
                    return;
                }
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException exception) {
                        handle.detachCall(call);
                        fail("The G-code file could not be downloaded.");
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        try {
                            handleResponse(response, redirectCount);
                        } finally {
                            handle.detachCall(call);
                        }
                    }
                });
            } catch (RuntimeException exception) {
                fail("The G-code file could not be downloaded.");
            }
        }

        private void handleResponse(Response response, int redirectCount) {
            try (Response closeableResponse = response) {
                if (handle.isComplete()) {
                    return;
                }
                if (isRedirect(response.code())) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        fail("The G-code download was redirected too many times.");
                        return;
                    }
                    String location = response.header("Location");
                    HttpUrl next = location == null ? null : response.request().url().resolve(location);
                    if (next == null) {
                        fail("The G-code download returned an invalid redirect.");
                        return;
                    }
                    final HttpUrl validated;
                    try {
                        validated = validateDownloadUrl(serverUrl, allowInsecureServer, next.toString());
                    } catch (IllegalArgumentException exception) {
                        fail(exception.getMessage());
                        return;
                    }
                    executeValidation(() -> follow(validated, redirectCount + 1));
                    return;
                }

                if (!response.isSuccessful()) {
                    fail("The G-code download failed (HTTP " + response.code() + ").");
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    fail("The G-code download returned no file data.");
                    return;
                }
                if (!handle.attachResource(body)) {
                    return;
                }
                long declaredLength = body.contentLength();
                if (declaredLength > MAX_FILE_BYTES) {
                    fail("The G-code file is larger than the 2 GiB download limit.");
                    return;
                }
                try {
                    streamAndPublish(body);
                } finally {
                    handle.detachResource(body);
                }
            } catch (IOException exception) {
                fail("FabScreen could not save the downloaded G-code file.");
            } catch (RuntimeException exception) {
                fail("FabScreen could not process the downloaded G-code file.");
            }
        }

        private void streamAndPublish(ResponseBody body) throws IOException {
            long total = 0L;
            byte[] buffer = new byte[BUFFER_BYTES];
            try (InputStream input = new BufferedInputStream(body.byteStream());
                 FileOutputStream fileOutput = new FileOutputStream(partFile, false);
                 BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    if (total > MAX_FILE_BYTES - read) {
                        throw new FileTooLargeException();
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                output.flush();
                fileOutput.getFD().sync();
            } catch (FileTooLargeException exception) {
                fail("The G-code file is larger than the 2 GiB download limit.");
                return;
            }

            handle.publish(partFile, uploadDirectory, safeFilename);
        }

        private void executeValidation(Runnable runnable) {
            try {
                validationExecutor.execute(runnable);
            } catch (RejectedExecutionException exception) {
                fail("The G-code download could not continue.");
            }
        }

        private void fail(String safeMessage) {
            handle.fail(safeMessage);
        }
    }

    static HttpUrl validateServerUrl(String serverUrl, boolean allowInsecureServer) {
        HttpUrl parsed = parseHttpUrl(serverUrl, "The configured Obico server URL is invalid.");
        if (!parsed.username().isEmpty() || !parsed.password().isEmpty()
                || parsed.query() != null || parsed.fragment() != null) {
            throw new IllegalArgumentException("The configured Obico server URL is invalid.");
        }
        if ("http".equals(parsed.scheme()) && !allowInsecureServer) {
            throw new IllegalArgumentException("The configured Obico server must use HTTPS.");
        }
        return parsed;
    }

    static HttpUrl validateDownloadUrl(
            HttpUrl configuredServer,
            boolean allowInsecureServer,
            String candidateUrl) {
        if (configuredServer == null) {
            throw new IllegalArgumentException("The configured Obico server URL is invalid.");
        }
        HttpUrl candidate = parseHttpUrl(candidateUrl, "The G-code download URL is invalid.");
        if (!candidate.username().isEmpty() || !candidate.password().isEmpty()
                || candidate.fragment() != null) {
            throw new IllegalArgumentException("The G-code download URL is not allowed.");
        }
        if ("http".equals(candidate.scheme())
                && !(allowInsecureServer && sameOrigin(configuredServer, candidate))) {
            throw new IllegalArgumentException("The G-code download must use HTTPS.");
        }
        return candidate;
    }

    static String sanitizeGcodeFilename(String requestedFilename) {
        if (requestedFilename == null) {
            throw new IllegalArgumentException("The G-code filename is missing.");
        }
        String value = requestedFilename.trim().replace('\\', '/');
        int separator = value.lastIndexOf('/');
        if (separator >= 0) {
            value = value.substring(separator + 1);
        }
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)
                || !value.toLowerCase(Locale.US).endsWith(".gcode")) {
            throw new IllegalArgumentException("Only .gcode files can be downloaded.");
        }

        String stem = value.substring(0, value.length() - ".gcode".length());
        StringBuilder sanitized = new StringBuilder(Math.min(stem.length(), 180));
        for (int index = 0; index < stem.length() && sanitized.length() < 180; index++) {
            char character = stem.charAt(index);
            if (character < 0x20 || character == 0x7f
                    || character == '<' || character == '>' || character == ':'
                    || character == '"' || character == '/' || character == '\\'
                    || character == '|' || character == '?' || character == '*') {
                sanitized.append('_');
            } else {
                sanitized.append(character);
            }
        }
        while (sanitized.length() > 0) {
            char trailing = sanitized.charAt(sanitized.length() - 1);
            if (trailing != '.' && trailing != ' ') {
                break;
            }
            sanitized.deleteCharAt(sanitized.length() - 1);
        }
        int firstVisible = 0;
        while (firstVisible < sanitized.length()
                && (sanitized.charAt(firstVisible) == '.'
                || sanitized.charAt(firstVisible) == ' ')) {
            firstVisible++;
        }
        String cleanStem = sanitized.substring(firstVisible).trim();
        if (cleanStem.isEmpty()) {
            throw new IllegalArgumentException("The G-code filename is invalid.");
        }
        return cleanStem + ".gcode";
    }

    static boolean sameOrigin(HttpUrl first, HttpUrl second) {
        return first != null
                && second != null
                && first.scheme().equalsIgnoreCase(second.scheme())
                && first.host().equalsIgnoreCase(second.host())
                && first.port() == second.port();
    }

    static boolean isNonPublicAddress(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 0 || first == 10 || first == 127 || first >= 224) return true;
            if (first == 100 && second >= 64 && second <= 127) return true;
            if (first == 169 && second == 254) return true;
            if (first == 172 && second >= 16 && second <= 31) return true;
            if (first == 192 && second == 168) return true;
            if (first == 198 && (second == 18 || second == 19)) return true;
            // IETF protocol assignments and documentation-only networks are not Internet targets.
            if (first == 192 && second == 0) return true;
            if (first == 192 && second == 0 && (bytes[2] & 0xff) == 2) return true;
            if (first == 198 && second == 51 && (bytes[2] & 0xff) == 100) return true;
            if (first == 203 && second == 0 && (bytes[2] & 0xff) == 113) return true;
            return false;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return (first & 0xfe) == 0xfc; // RFC 4193 unique-local address space.
        }
        return true;
    }

    static void validateResolvedTarget(
            HttpUrl configuredServer,
            HttpUrl target,
            InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("The G-code download host could not be resolved.");
        }
        if (sameOrigin(configuredServer, target)) {
            return;
        }
        for (InetAddress address : addresses) {
            if (isNonPublicAddress(address)) {
                throw new IllegalArgumentException("The G-code download URL points to a private network.");
            }
        }
    }

    private static void approveTarget(
            HttpUrl configuredServer,
            HttpUrl target,
            HostResolver resolver,
            Map<String, List<InetAddress>> approvedAddresses) {
        try {
            InetAddress[] resolved = resolver.resolve(target.host());
            validateResolvedTarget(configuredServer, target, resolved);
            List<InetAddress> pinned = new ArrayList<>(resolved.length);
            Collections.addAll(pinned, resolved);
            approvedAddresses.put(target.host(), Collections.unmodifiableList(pinned));
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("The G-code download host could not be resolved.");
        }
    }

    private static HttpUrl parseHttpUrl(String value, String safeError) {
        if (value == null) {
            throw new IllegalArgumentException(safeError);
        }
        HttpUrl parsed = HttpUrl.parse(value.trim());
        if (parsed == null
                || !("https".equals(parsed.scheme()) || "http".equals(parsed.scheme()))
                || parsed.host().isEmpty()) {
            throw new IllegalArgumentException(safeError);
        }
        return parsed;
    }

    private static File prepareUploadDirectory(File appFilesDirectory) throws IOException {
        if (appFilesDirectory == null) {
            throw new IOException("Missing app files directory");
        }
        File root = appFilesDirectory.getCanonicalFile();
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory()) {
            throw new IOException("Invalid app files directory");
        }
        File upload = new File(root, UPLOAD_DIRECTORY).getCanonicalFile();
        if (!root.equals(upload.getParentFile())) {
            throw new IOException("Invalid upload directory");
        }
        if ((!upload.exists() && !upload.mkdirs()) || !upload.isDirectory()) {
            throw new IOException("Unable to create upload directory");
        }
        // Resolve again after creation so an existing link cannot escape the private files root.
        File resolvedUpload = upload.getCanonicalFile();
        if (!root.equals(resolvedUpload.getParentFile())) {
            throw new IOException("Invalid upload directory");
        }
        return resolvedUpload;
    }

    private static File publishUnique(File partFile, File directory, String safeFilename)
            throws IOException {
        synchronized (PUBLISH_LOCK) {
            int extensionIndex = safeFilename.toLowerCase(Locale.US).lastIndexOf(".gcode");
            String stem = safeFilename.substring(0, extensionIndex);
            for (int suffix = 0; suffix < 10_000; suffix++) {
                String name = suffix == 0
                        ? safeFilename
                        : stem + " (" + suffix + ").gcode";
                File destination = new File(directory, name);
                if (destination.exists()) {
                    continue;
                }
                if (partFile.renameTo(destination)) {
                    return destination;
                }
                if (!partFile.exists()) {
                    throw new IOException("Staging file disappeared");
                }
            }
        }
        throw new IOException("No unique destination available");
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            // Best effort: a failed deletion must not cause a second callback.
            file.delete();
        }
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15L, TimeUnit.SECONDS)
                .readTimeout(60L, TimeUnit.SECONDS)
                .writeTimeout(30L, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
    }

    private static final class PinnedDns implements Dns {
        private final Map<String, List<InetAddress>> approvedAddresses;

        PinnedDns(Map<String, List<InetAddress>> approvedAddresses) {
            this.approvedAddresses = approvedAddresses;
        }

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> result = approvedAddresses.get(hostname);
            if (result == null || result.isEmpty()) {
                throw new UnknownHostException("Host was not approved");
            }
            return result;
        }
    }

    private static final class DownloadHandleImpl implements DownloadHandle {
        private final Object lock = new Object();
        private final Completion completion;
        private boolean cancelled;
        private Call activeCall;
        private Closeable activeResource;
        private File partFile;

        DownloadHandleImpl(Completion completion) {
            this.completion = completion;
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                if (completion.isComplete()) {
                    return;
                }
                cancelled = true;
                if (activeCall != null) {
                    activeCall.cancel();
                    activeCall = null;
                }
                closeQuietly(activeResource);
                activeResource = null;
                deleteQuietly(partFile);
                completion.error("The G-code download was cancelled.");
            }
        }

        @Override
        public boolean isCancelled() {
            synchronized (lock) {
                return cancelled;
            }
        }

        boolean isComplete() {
            return completion.isComplete();
        }

        boolean attachPartFile(File file) {
            synchronized (lock) {
                if (completion.isComplete()) {
                    return false;
                }
                partFile = file;
                return true;
            }
        }

        boolean attachCall(Call call) {
            synchronized (lock) {
                if (completion.isComplete()) {
                    return false;
                }
                activeCall = call;
                return true;
            }
        }

        void detachCall(Call call) {
            synchronized (lock) {
                if (activeCall == call) {
                    activeCall = null;
                }
            }
        }

        boolean attachResource(Closeable resource) {
            synchronized (lock) {
                if (completion.isComplete()) {
                    closeQuietly(resource);
                    return false;
                }
                activeResource = resource;
                return true;
            }
        }

        void detachResource(Closeable resource) {
            synchronized (lock) {
                if (activeResource == resource) {
                    activeResource = null;
                }
            }
        }

        void publish(File stagedFile, File directory, String safeFilename) throws IOException {
            synchronized (lock) {
                if (completion.isComplete()) {
                    deleteQuietly(stagedFile);
                    return;
                }
                File published = publishUnique(stagedFile, directory, safeFilename);
                partFile = null;
                completion.success(published);
            }
        }

        void fail(String safeMessage) {
            synchronized (lock) {
                if (completion.isComplete()) {
                    // A cancelled stream may report its I/O failure after cancel() returns.
                    deleteQuietly(partFile);
                    return;
                }
                if (activeCall != null) {
                    activeCall.cancel();
                    activeCall = null;
                }
                closeQuietly(activeResource);
                activeResource = null;
                deleteQuietly(partFile);
                completion.error(safeMessage);
            }
        }
    }

    private static final class Completion {
        private final AtomicBoolean complete = new AtomicBoolean();
        private final ResultCallback callback;

        Completion(ResultCallback callback) {
            this.callback = callback;
        }

        boolean isComplete() {
            return complete.get();
        }

        void success(File file) {
            if (complete.compareAndSet(false, true)) {
                callback.onSuccess(file);
            }
        }

        void error(String safeMessage) {
            if (complete.compareAndSet(false, true)) {
                callback.onError(safeMessage == null || safeMessage.trim().isEmpty()
                        ? "The G-code download failed."
                        : safeMessage);
            }
        }
    }

    private static final class FileTooLargeException extends IOException {
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best effort. The owning I/O path will also close its resource.
        }
    }

    private static final class DownloaderThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "obico-download-validation");
            thread.setDaemon(true);
            return thread;
        }
    }
}
