package fabscreen.platform.base.obico;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure protocol mapping for the Obico OctoPrint-compatible agent API. */
public final class ObicoProtocol {
    public static final String AGENT_NAME = "fabscreen-artisan";
    public static final String AGENT_VERSION = "2.8.3";
    // Obico's Free-plan browser player only enables WebRTC for recognized agent families.
    // Obico's printer-local file browser requires the OctoPrint agent version to be >= 2.3.0.
    // Keep this in sync with the subset of file passthrough calls implemented below.
    public static final String PLAYER_COMPAT_AGENT_NAME = "octoprint_obico";
    public static final String PLAYER_COMPAT_AGENT_VERSION = "2.3.0";
    public static final int PRIMARY_MJPEG_STREAM_ID = 2;
    public static final int MAX_INBOUND_MESSAGE_CHARS = 256 * 1024;
    public static final int MAX_COMMANDS_PER_MESSAGE = 8;
    private static final int MAX_PASSTHRU_REFERENCE_CHARS = 128;
    private static final int MAX_DOWNLOAD_URL_CHARS = 4096;
    private static final int MAX_DOWNLOAD_FILENAME_CHARS = 255;
    private static final int MAX_LOCAL_PATH_CHARS = 512;
    private static final int MAX_LOCAL_FILTER_CHARS = 128;
    private static final Pattern PASSTHRU_REFERENCE =
            Pattern.compile("[A-Za-z0-9_.:-]{1," + MAX_PASSTHRU_REFERENCE_CHARS + "}");
    private static final Pattern CLOUD_GCODE_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern EXTRUSION_COMMAND = Pattern.compile(
            "M83\\nT([01])\\nG1 E(-?(?:1|10|50)) F300");
    private static final Pattern PRINT_SPEED_COMMAND = Pattern.compile("M220 S([0-9]{1,3})");
    private static final Pattern FLOW_RATE_COMMAND = Pattern.compile("M221 S([0-9]{1,3})");
    private static final Pattern FAN_SPEED_COMMAND = Pattern.compile("M106 S([0-9]{1,3})");

    private ObicoProtocol() {
    }

    /**
     * Canonicalizes a server root while preventing credential-bearing or ambiguous URLs.
     */
    public static String canonicalServerUrl(String value, boolean allowInsecureServer) {
        String input = value == null ? "" : value.trim();
        if (input.isEmpty()) {
            input = ObicoSettings.DEFAULT_SERVER_URL;
        }
        if (!input.contains("://")) {
            input = "https://" + input;
        }

        final URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid Obico server URL", exception);
        }

        String scheme = uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.US);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("Obico server URL must use HTTPS");
        }
        if ("http".equals(scheme) && !allowInsecureServer) {
            throw new IllegalArgumentException(
                    "Insecure Obico servers are disabled; use HTTPS or explicitly allow HTTP");
        }
        if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("Obico server URL must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Obico server URL must not contain credentials");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Obico server URL must not contain a query or fragment");
        }
        int port = uri.getPort();
        if (port < -1 || port == 0 || port > 65535) {
            throw new IllegalArgumentException("Obico server URL contains an invalid port");
        }

        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "";
        } else {
            if (path.contains("/../") || path.endsWith("/..") || path.contains("/./")) {
                throw new IllegalArgumentException("Obico server URL contains an unsafe path");
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }

        try {
            return new URI(
                    scheme,
                    null,
                    uri.getHost().toLowerCase(Locale.US),
                    port,
                    path,
                    null,
                    null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid Obico server URL", exception);
        }
    }

    public static String endpointUrl(
            String serverUrl,
            boolean allowInsecureServer,
            String endpointPath) {
        if (endpointPath == null || !endpointPath.startsWith("/") || endpointPath.startsWith("//")) {
            throw new IllegalArgumentException("Endpoint path must be absolute");
        }
        return canonicalServerUrl(serverUrl, allowInsecureServer) + endpointPath;
    }

    public static String websocketUrl(String serverUrl, boolean allowInsecureServer) {
        String base = canonicalServerUrl(serverUrl, allowInsecureServer);
        if (base.startsWith("https://")) {
            return "wss://" + base.substring("https://".length()) + "/ws/dev/";
        }
        return "ws://" + base.substring("http://".length()) + "/ws/dev/";
    }

    public static String statusPayload(
            ObicoPrinterSnapshot snapshot,
            String printEvent,
            boolean withSettings,
            boolean cameraConfigured) {
        return statusPayload(snapshot, printEvent, withSettings, cameraConfigured, false);
    }

    /** Advertises a live stream only after its media transport is ready for Janus viewers. */
    public static String statusPayload(
            ObicoPrinterSnapshot snapshot,
            String printEvent,
            boolean withSettings,
            boolean cameraConfigured,
            boolean liveStreamReady) {
        ObicoPrinterSnapshot safeSnapshot = snapshot == null
                ? ObicoPrinterSnapshot.offline()
                : snapshot;
        JsonObject root = new JsonObject();
        if (safeSnapshot.getState() != ObicoPrintState.OFFLINE) {
            root.addProperty(
                    "current_print_ts",
                    safeSnapshot.getStartedAtMillis() == null
                            ? -1L
                            : safeSnapshot.getStartedAtMillis() / 1000L);
            root.add("status", statusObject(safeSnapshot));
        }
        if (printEvent != null && !printEvent.trim().isEmpty()) {
            JsonObject event = new JsonObject();
            event.addProperty("event_type", bounded(printEvent, 64));
            root.add("event", event);
        }
        if (withSettings) {
            root.add("settings", settingsObject(cameraConfigured, liveStreamReady));
        }
        return root.toString();
    }

    private static JsonObject statusObject(ObicoPrinterSnapshot snapshot) {
        ObicoPrintState state = snapshot.getState();
        JsonObject flags = new JsonObject();
        flags.addProperty("operational", state != ObicoPrintState.OFFLINE);
        flags.addProperty("paused", state == ObicoPrintState.PAUSED);
        flags.addProperty("printing", state == ObicoPrintState.PRINTING);
        flags.addProperty("cancelling", false);
        flags.addProperty("pausing", false);
        flags.addProperty("error", state == ObicoPrintState.ERROR);
        flags.addProperty("ready", state == ObicoPrintState.OPERATIONAL);
        flags.addProperty("closedOrError", state == ObicoPrintState.OFFLINE);

        JsonObject stateObject = new JsonObject();
        stateObject.addProperty("text", state.getWireName());
        stateObject.add("flags", flags);
        addNullableString(stateObject, "error", snapshot.getError());

        JsonObject file = new JsonObject();
        addNullableString(file, "name", snapshot.getFileName());
        addNullableString(file, "path", snapshot.getFileName());
        addNullableString(file, "display", snapshot.getFileName());
        addNullableNumber(file, "obico_g_code_file_id", snapshot.getObicoGcodeFileId());

        JsonObject job = new JsonObject();
        job.add("file", file);
        job.add("estimatedPrintTime", com.google.gson.JsonNull.INSTANCE);
        job.add("user", com.google.gson.JsonNull.INSTANCE);

        JsonObject progress = new JsonObject();
        addNullableNumber(progress, "completion", snapshot.getCompletion());
        progress.addProperty("filepos", snapshot.getFilePosition());
        addNullableNumber(progress, "printTime", snapshot.getPrintTimeSeconds());
        addNullableNumber(progress, "printTimeLeft", snapshot.getPrintTimeLeftSeconds());
        progress.add("filamentUsed", com.google.gson.JsonNull.INSTANCE);

        JsonObject temperatures = new JsonObject();
        for (ObicoTemperature temperature : snapshot.getTemperatures()) {
            if (temperature == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("actual", temperature.getActual());
            item.addProperty("offset", 0);
            addNullableNumber(item, "target", temperature.getTarget());
            temperatures.add(temperature.getName(), item);
        }

        JsonObject status = new JsonObject();
        status.addProperty("_ts", System.currentTimeMillis() / 1000.0d);
        status.add("state", stateObject);
        addNullableNumber(status, "currentZ", snapshot.getCurrentZ());
        status.add("job", job);
        status.add("progress", progress);
        status.add("temperatures", temperatures);
        addNullableNumber(status, "currentFeedRate", snapshot.getCurrentFeedRate());
        addNullableNumber(status, "currentFlowRate", snapshot.getCurrentFlowRate());
        addNullableNumber(status, "currentFanSpeed", snapshot.getCurrentFanSpeed());
        return status;
    }

    private static JsonObject settingsObject(
            boolean cameraConfigured,
            boolean liveStreamReady) {
        JsonArray webcams = new JsonArray();
        if (cameraConfigured) {
            JsonObject webcam = new JsonObject();
            webcam.addProperty("name", "FabScreen Camera");
            webcam.addProperty("is_primary_camera", true);
            webcam.addProperty("is_nozzle_camera", false);
            if (liveStreamReady) {
                webcam.addProperty("stream_mode", "mjpeg_webrtc");
                webcam.addProperty("stream_id", PRIMARY_MJPEG_STREAM_ID);
                webcam.addProperty("data_channel_available", false);
            } else {
                webcam.addProperty("stream_mode", "jpeg");
            }
            webcam.addProperty("streamRatio", "16:9");
            webcams.add(webcam);
        }

        JsonObject temperature = new JsonObject();
        temperature.add("profiles", new JsonArray());
        JsonObject agent = new JsonObject();
        agent.addProperty("name", PLAYER_COMPAT_AGENT_NAME);
        agent.addProperty("version", PLAYER_COMPAT_AGENT_VERSION);
        JsonArray platform = new JsonArray();
        platform.add("Android");
        platform.add("Snapmaker Artisan");
        platform.add("");
        platform.add("");
        platform.add("armeabi-v7a");
        platform.add("FabScreen");

        JsonObject settings = new JsonObject();
        settings.add("webcams", webcams);
        settings.add("data_channel_id", com.google.gson.JsonNull.INSTANCE);
        settings.add("temperature", temperature);
        settings.add("agent", agent);
        settings.add("platform_uname", platform);
        settings.add("installed_plugins", new JsonArray());
        return settings;
    }

    public static List<ObicoRemoteCommand> parseRemoteCommands(String message) {
        if (message == null || message.length() > MAX_INBOUND_MESSAGE_CHARS) {
            return Collections.emptyList();
        }
        JsonObject root = parseObject(message);
        if (root == null || !root.has("commands") || !root.get("commands").isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray commands = root.getAsJsonArray("commands");
        List<ObicoRemoteCommand> result = new ArrayList<>();
        int count = Math.min(commands.size(), MAX_COMMANDS_PER_MESSAGE);
        for (int index = 0; index < count; index++) {
            JsonElement element = commands.get(index);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject command = element.getAsJsonObject();
            String name = stringValue(command.get("cmd"), 32);
            ObicoRemoteCommand.Type type = ObicoRemoteCommand.Type.fromWireName(name);
            if (type == null) {
                continue;
            }
            String serverId = firstString(command, "id", "cmd_id", "command_id", "created_at");
            String fingerprint = serverId.isEmpty()
                    ? type.getWireName() + ":" + sha256(command.toString())
                    : "id:" + serverId;
            result.add(new ObicoRemoteCommand(type, serverId, fingerprint));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Detects and validates the small subset of Obico's OctoPrint passthrough RPCs implemented by
     * FabScreen. Unknown targets, functions, arbitrary G-code, and malformed arguments are
     * rejected before they can reach a machine service.
     */
    public static ObicoPassthruParseResult parsePassthruRequest(String message) {
        if (message == null) {
            return ObicoPassthruParseResult.absent();
        }
        if (message.length() > MAX_INBOUND_MESSAGE_CHARS) {
            return ObicoPassthruParseResult.rejected("", "Passthrough payload is too large");
        }
        JsonObject root = parseObject(message);
        if (root == null || !root.has("passthru")) {
            return ObicoPassthruParseResult.absent();
        }
        if (!root.get("passthru").isJsonObject()) {
            return ObicoPassthruParseResult.rejected("", "Malformed passthrough request");
        }

        JsonObject passthru = root.getAsJsonObject("passthru");
        String reference = strictString(passthru.get("ref"), MAX_PASSTHRU_REFERENCE_CHARS);
        if (!isValidPassthruReference(reference)) {
            return ObicoPassthruParseResult.rejected("", "Invalid passthrough reference");
        }
        String target = strictString(passthru.get("target"), 64);
        String function = strictString(passthru.get("func"), 64);
        JsonArray args = passthru.has("args") && passthru.get("args").isJsonArray()
                ? passthru.getAsJsonArray("args")
                : null;
        JsonObject kwargs = passthru.has("kwargs") && passthru.get("kwargs").isJsonObject()
                ? passthru.getAsJsonObject("kwargs")
                : null;
        if (passthru.has("args") && !passthru.get("args").isJsonArray()) {
            return rejectedArgs(reference, "Invalid passthrough arguments");
        }
        if (passthru.has("kwargs") && !passthru.get("kwargs").isJsonObject()) {
            return rejectedArgs(reference, "Invalid passthrough options");
        }

        if ("_printer".equals(target)) {
            if ("select_file".equals(function)) {
                return parseSelectFile(reference, args, kwargs);
            }
            if ("jog".equals(function)) {
                return parseJog(reference, args);
            }
            if ("home".equals(function)) {
                return parseHome(reference, args);
            }
            if ("set_temperature".equals(function)) {
                return parseSetTemperature(reference, args);
            }
            if ("commands".equals(function)) {
                return parsePrinterCommand(reference, args);
            }
        } else if ("_file_manager".equals(target) && "list_files".equals(function)) {
            return parseListFiles(reference, args, kwargs);
        } else if ("file_downloader".equals(target) && "download".equals(function)) {
            return parseFileDownload(reference, args);
        }
        return ObicoPassthruParseResult.rejected(
                reference,
                "Unsupported Obico passthrough request");
    }

    /** Builds the response expected by Obico for a successful void passthrough call. */
    public static String passthruAckPayload(String reference) {
        return passthruResponse(reference, JsonNull.INSTANCE, null);
    }

    /** Builds the response used by file-operation calls that return a short status string. */
    public static String passthruStringAckPayload(String reference, String returnValue) {
        String safeValue = bounded(returnValue, 256);
        return passthruResponse(
                reference,
                safeValue.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(safeValue),
                null);
    }

    /** Builds an Obico passthrough response whose return value is structured JSON. */
    public static String passthruJsonAckPayload(String reference, JsonElement returnValue) {
        if (returnValue == null || returnValue.isJsonNull()) {
            throw new IllegalArgumentException("Passthrough return value must be JSON");
        }
        return passthruResponse(reference, returnValue, null);
    }

    /** Builds the response expected after an Obico cloud-file download has been accepted. */
    public static String passthruDownloadAckPayload(String reference, String targetPath) {
        if (!isSafeGcodeLeafName(targetPath)) {
            throw new IllegalArgumentException("Invalid Obico download target path");
        }
        JsonObject result = new JsonObject();
        result.addProperty("target_path", targetPath);
        return passthruResponse(reference, result, null);
    }

    /** Builds an Obico passthrough error response without echoing untrusted request data. */
    public static String passthruErrorPayload(String reference, String error) {
        String safeError = bounded(error, 256);
        return passthruResponse(
                reference,
                null,
                safeError.isEmpty() ? "Request rejected" : safeError);
    }

    private static ObicoPassthruParseResult parseListFiles(
            String reference,
            JsonArray args,
            JsonObject kwargs) {
        if ((args != null && args.size() != 0) || kwargs == null
                || kwargs.entrySet().size() > 4) {
            return rejectedArgs(reference, "Invalid file list arguments");
        }
        for (java.util.Map.Entry<String, JsonElement> entry : kwargs.entrySet()) {
            String key = entry.getKey();
            if (!("path".equals(key) || "recursive".equals(key)
                    || "level".equals(key) || "filter".equals(key))) {
                return rejectedArgs(reference, "Unsupported file list option");
            }
        }

        String path = null;
        if (kwargs.has("path") && !kwargs.get("path").isJsonNull()) {
            JsonElement value = kwargs.get("path");
            if (!isStrictString(value, MAX_LOCAL_PATH_CHARS)) {
                return rejectedArgs(reference, "Invalid file list path");
            }
            path = value.getAsString();
            if (!path.isEmpty() && !isSafeRelativePath(path, MAX_LOCAL_PATH_CHARS)) {
                return rejectedArgs(reference, "Invalid file list path");
            }
        }

        boolean recursive = false;
        if (kwargs.has("recursive")) {
            JsonElement value = kwargs.get("recursive");
            if (!isStrictBoolean(value)) {
                return rejectedArgs(reference, "Invalid recursive option");
            }
            recursive = value.getAsBoolean();
        }

        Integer level = null;
        if (kwargs.has("level")) {
            level = exactInteger(kwargs.get("level"));
            if (level == null || level < 0 || level > 16) {
                return rejectedArgs(reference, "Invalid file list level");
            }
        }

        String filter = null;
        if (kwargs.has("filter")) {
            JsonElement value = kwargs.get("filter");
            if (!isStrictString(value, MAX_LOCAL_FILTER_CHARS)) {
                return rejectedArgs(reference, "Invalid file list filter");
            }
            filter = value.getAsString();
        }

        return ObicoPassthruParseResult.accepted(
                ObicoPassthruRequest.listFiles(reference, path, recursive, level, filter));
    }

    private static ObicoPassthruParseResult parseSelectFile(
            String reference,
            JsonArray args,
            JsonObject kwargs) {
        if (args == null || args.size() != 2 || !args.get(1).isJsonNull()
                || !isStrictString(args.get(0), MAX_LOCAL_PATH_CHARS)) {
            return rejectedArgs(reference, "Invalid selected file arguments");
        }
        String path = args.get(0).getAsString();
        if (!isSafeRelativePath(path, MAX_LOCAL_PATH_CHARS)
                || !path.toLowerCase(Locale.US).endsWith(".gcode")) {
            return rejectedArgs(reference, "Invalid selected G-code path");
        }
        if (kwargs != null && (kwargs.entrySet().size() > 1
                || (kwargs.entrySet().size() == 1 && !kwargs.has("printAfterSelect")))) {
            return rejectedArgs(reference, "Unsupported file selection option");
        }
        boolean printAfterSelect = false;
        if (kwargs != null && kwargs.has("printAfterSelect")) {
            JsonElement value = kwargs.get("printAfterSelect");
            if (isStrictBoolean(value)) {
                printAfterSelect = value.getAsBoolean();
            } else if (isStrictString(value, 5)) {
                String text = value.getAsString();
                if (!("true".equals(text) || "false".equals(text))) {
                    return rejectedArgs(reference, "Invalid print-after-select option");
                }
                printAfterSelect = "true".equals(text);
            } else {
                return rejectedArgs(reference, "Invalid print-after-select option");
            }
        }
        return ObicoPassthruParseResult.accepted(
                ObicoPassthruRequest.selectFile(reference, path, printAfterSelect));
    }

    private static ObicoPassthruParseResult parseJog(String reference, JsonArray args) {
        if (args == null || args.size() != 1 || !args.get(0).isJsonObject()) {
            return rejectedArgs(reference, "Invalid jog arguments");
        }
        JsonObject axes = args.get(0).getAsJsonObject();
        if (axes.entrySet().size() != 1) {
            return rejectedArgs(reference, "A jog request must contain one axis");
        }
        java.util.Map.Entry<String, JsonElement> entry = axes.entrySet().iterator().next();
        String axis = entry.getKey();
        Double distance = finiteNumber(entry.getValue());
        if (!("x".equals(axis) || "y".equals(axis) || "z".equals(axis))
                || distance == null
                || distance == 0.0d
                || Math.abs(distance) < 0.01d
                || Math.abs(distance) > 100.0d) {
            return rejectedArgs(reference, "Invalid jog axis or distance");
        }
        return ObicoPassthruParseResult.accepted(
                ObicoPassthruRequest.jog(reference, axis, distance));
    }

    private static ObicoPassthruParseResult parseHome(String reference, JsonArray args) {
        if (args == null || args.size() != 1) {
            return rejectedArgs(reference, "Invalid home arguments");
        }
        JsonElement axesValue = args.get(0);
        List<String> axes = new ArrayList<>();
        if (axesValue.isJsonArray()) {
            JsonArray array = axesValue.getAsJsonArray();
            if (array.size() < 1 || array.size() > 3) {
                return rejectedArgs(reference, "Invalid home axes");
            }
            for (JsonElement element : array) {
                axes.add(strictString(element, 1));
            }
        } else {
            axes.add(strictString(axesValue, 1));
        }
        Set<String> unique = new HashSet<>();
        for (String axis : axes) {
            if (!("x".equals(axis) || "y".equals(axis) || "z".equals(axis))
                    || !unique.add(axis)) {
                return rejectedArgs(reference, "Invalid home axes");
            }
        }
        return ObicoPassthruParseResult.accepted(
                ObicoPassthruRequest.home(reference, axes));
    }

    private static ObicoPassthruParseResult parseSetTemperature(
            String reference,
            JsonArray args) {
        if (args == null || args.size() != 2) {
            return rejectedArgs(reference, "Invalid temperature arguments");
        }
        String heater = strictString(args.get(0), 16).toLowerCase(Locale.US);
        Integer target = exactInteger(args.get(1));
        boolean extruder = "tool0".equals(heater)
                || "tool1".equals(heater)
                || "extruder0".equals(heater)
                || "extruder1".equals(heater);
        boolean bed = "bed".equals(heater) || "bed1".equals(heater);
        int maximum = extruder ? 300 : bed ? 110 : -1;
        if (target == null || maximum < 0 || target < 0 || target > maximum) {
            return rejectedArgs(reference, "Invalid heater or target temperature");
        }
        return ObicoPassthruParseResult.accepted(
                ObicoPassthruRequest.setTemperature(reference, heater, target));
    }

    private static ObicoPassthruParseResult parsePrinterCommand(
            String reference,
            JsonArray args) {
        if (args == null || args.size() != 1) {
            return rejectedArgs(reference, "Invalid printer command arguments");
        }
        String command = strictCommandString(args.get(0), 96);
        Matcher matcher = EXTRUSION_COMMAND.matcher(command);
        if (matcher.matches()) {
            int extruderIndex = Integer.parseInt(matcher.group(1));
            double distance = Double.parseDouble(matcher.group(2));
            return ObicoPassthruParseResult.accepted(ObicoPassthruRequest.extrude(
                    reference,
                    extruderIndex,
                    distance,
                    300));
        }

        matcher = PRINT_SPEED_COMMAND.matcher(command);
        if (matcher.matches()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value >= 10 && value <= 500) {
                return ObicoPassthruParseResult.accepted(ObicoPassthruRequest.percentage(
                        ObicoPassthruRequest.Type.SET_PRINT_SPEED,
                        reference,
                        value));
            }
        }

        matcher = FLOW_RATE_COMMAND.matcher(command);
        if (matcher.matches()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value >= 10 && value <= 200) {
                return ObicoPassthruParseResult.accepted(ObicoPassthruRequest.percentage(
                        ObicoPassthruRequest.Type.SET_FLOW_RATE,
                        reference,
                        value));
            }
        }

        matcher = FAN_SPEED_COMMAND.matcher(command);
        if (matcher.matches()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value <= 255) {
                return ObicoPassthruParseResult.accepted(
                        ObicoPassthruRequest.fanSpeed(reference, value));
            }
        }
        if ("M107".equals(command)) {
            return ObicoPassthruParseResult.accepted(
                    ObicoPassthruRequest.fanSpeed(reference, 0));
        }
        return ObicoPassthruParseResult.rejected(
                reference,
                "This Obico printer command is not supported");
    }

    private static ObicoPassthruParseResult parseFileDownload(
            String reference,
            JsonArray args) {
        if (args == null || args.size() != 1 || !args.get(0).isJsonObject()) {
            return rejectedArgs(reference, "Invalid cloud file arguments");
        }
        JsonObject file = args.get(0).getAsJsonObject();
        String id = strictIdentifier(file.get("id"), 19);
        String url = strictString(file.get("url"), MAX_DOWNLOAD_URL_CHARS);
        String filename = strictString(file.get("filename"), MAX_DOWNLOAD_FILENAME_CHARS);
        String safeFilename = strictString(
                file.get("safe_filename"),
                MAX_DOWNLOAD_FILENAME_CHARS);
        if (!CLOUD_GCODE_ID.matcher(id).matches()
                || !isSecureDownloadUrl(url)
                || !isSafeGcodeLeafName(filename)
                || !isSafeGcodeLeafName(safeFilename)) {
            return rejectedArgs(reference, "Invalid Obico cloud file metadata");
        }
        return ObicoPassthruParseResult.accepted(ObicoPassthruRequest.download(
                reference,
                new ObicoPassthruRequest.DownloadFile(id, url, filename, safeFilename)));
    }

    private static ObicoPassthruParseResult rejectedArgs(String reference, String error) {
        return ObicoPassthruParseResult.rejected(reference, error);
    }

    private static String passthruResponse(
            String reference,
            JsonElement returnValue,
            String error) {
        if (!isValidPassthruReference(reference)) {
            throw new IllegalArgumentException("Invalid Obico passthrough reference");
        }
        JsonObject response = new JsonObject();
        response.addProperty("ref", reference);
        if (error == null) {
            response.add("ret", returnValue == null ? JsonNull.INSTANCE : returnValue);
        } else {
            response.addProperty("error", error);
        }
        JsonObject root = new JsonObject();
        root.add("passthru", response);
        return root.toString();
    }

    private static boolean isValidPassthruReference(String reference) {
        return reference != null && PASSTHRU_REFERENCE.matcher(reference).matches();
    }

    private static String strictString(JsonElement element, int maximum) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        try {
            if (!element.getAsJsonPrimitive().isString()) return "";
            String value = element.getAsString();
            if (value == null || value.length() > maximum) return "";
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) return "";
            }
            return value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean isStrictString(JsonElement element, int maximum) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                && element.getAsString().equals(strictString(element, maximum));
    }

    private static boolean isStrictBoolean(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isSafeRelativePath(String path, int maximum) {
        if (path == null || path.isEmpty() || path.length() > maximum
                || path.startsWith("/") || path.endsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            return false;
        }
        String[] components = path.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                return false;
            }
        }
        for (int index = 0; index < path.length(); index++) {
            if (Character.isISOControl(path.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String strictIdentifier(JsonElement element, int maximum) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        try {
            if (!element.getAsJsonPrimitive().isString()
                    && !element.getAsJsonPrimitive().isNumber()) return "";
            String value = element.getAsString();
            if (value == null || value.length() > maximum) return "";
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) return "";
            }
            return value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String strictCommandString(JsonElement element, int maximum) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        try {
            if (!element.getAsJsonPrimitive().isString()) return "";
            String value = element.getAsString();
            if (value == null || value.length() > maximum) return "";
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '\r') {
                    if (index + 1 >= value.length() || value.charAt(index + 1) != '\n') return "";
                } else if (current != '\n' && Character.isISOControl(current)) {
                    return "";
                }
            }
            return value.replace("\r\n", "\n");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Double finiteNumber(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        try {
            if (!element.getAsJsonPrimitive().isNumber()) return null;
            double value = element.getAsDouble();
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer exactInteger(JsonElement element) {
        Double value = finiteNumber(element);
        if (value == null || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return null;
        return value.intValue();
    }

    private static boolean isSecureDownloadUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().trim().isEmpty()
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static boolean isSafeGcodeLeafName(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_DOWNLOAD_FILENAME_CHARS
                || value.startsWith(".")
                || value.contains("/")
                || value.contains("\\")
                || value.indexOf(':') >= 0
                || !value.toLowerCase(Locale.US).endsWith(".gcode")) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return false;
        }
        return true;
    }

    public static RemoteViewingStatus parseRemoteViewingStatus(String message) {
        if (message == null || message.length() > MAX_INBOUND_MESSAGE_CHARS) {
            return null;
        }
        JsonObject root = parseObject(message);
        if (root == null || !root.has("remote_status") || !root.get("remote_status").isJsonObject()) {
            return null;
        }
        JsonObject remote = root.getAsJsonObject("remote_status");
        Boolean viewing = remoteBooleanValue(remote.get("viewing"));
        Boolean shouldWatch = remoteBooleanValue(remote.get("should_watch"));
        // Obico sends these flags independently. Missing (or invalid) fields must not
        // reset the other flag, and an empty update must not refresh its timestamp.
        return viewing == null && shouldWatch == null
                ? null : new RemoteViewingStatus(viewing, shouldWatch);
    }

    public static String transitionEvent(
            ObicoPrintState previous,
            ObicoPrintState current,
            boolean cancelRequested) {
        return transitionEvent(previous, current, cancelRequested, false);
    }

    public static String transitionEvent(
            ObicoPrintState previous,
            ObicoPrintState current,
            boolean cancelRequested,
            boolean terminalFailureReported) {
        if (previous == null || current == null) {
            return null;
        }
        if (previous == ObicoPrintState.OPERATIONAL && current == ObicoPrintState.PRINTING) {
            return "PrintStarted";
        }
        if (previous == ObicoPrintState.PAUSED && current == ObicoPrintState.PRINTING) {
            return "PrintResumed";
        }
        if (previous == ObicoPrintState.PRINTING && current == ObicoPrintState.PAUSED) {
            return "PrintPaused";
        }
        if (terminalFailureReported
                && previous.isActive()
                && current == ObicoPrintState.OPERATIONAL) {
            return "PrintFailed";
        }
        if (cancelRequested && previous.isActive() && current == ObicoPrintState.OPERATIONAL) {
            return "PrintCancelled";
        }
        if (previous.isActive() && current == ObicoPrintState.OPERATIONAL) {
            return "PrintDone";
        }
        if (previous.isActive() && current == ObicoPrintState.ERROR) {
            return "PrintFailed";
        }
        return null;
    }

    public static boolean isPrivateOrLoopbackAddress(String address) {
        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            return inetAddress.isAnyLocalAddress()
                    || inetAddress.isLoopbackAddress()
                    || inetAddress.isSiteLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JsonObject parseObject(String value) {
        try {
            JsonElement root = new JsonParser().parse(value);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstString(JsonObject object, String... names) {
        for (String name : names) {
            String value = stringValue(object.get(name), 128);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String stringValue(JsonElement element, int maximum) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        try {
            return bounded(element.getAsString(), maximum);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean booleanValue(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return false;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Boolean remoteBooleanValue(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean() : null;
    }

    private static String bounded(String value, int maximum) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\u0000', ' ').trim();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(encoded.length * 2);
            for (byte current : encoded) {
                result.append(String.format(Locale.US, "%02x", current & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void addNullableString(JsonObject object, String name, String value) {
        if (value == null) {
            object.add(name, com.google.gson.JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }

    private static void addNullableNumber(JsonObject object, String name, Number value) {
        if (value == null) {
            object.add(name, com.google.gson.JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }

    public static final class RemoteViewingStatus {
        private final Boolean viewing;
        private final Boolean shouldWatch;

        RemoteViewingStatus(Boolean viewing, Boolean shouldWatch) {
            this.viewing = viewing;
            this.shouldWatch = shouldWatch;
        }

        public boolean isViewing() {
            return Boolean.TRUE.equals(viewing);
        }

        public boolean isShouldWatch() {
            return Boolean.TRUE.equals(shouldWatch);
        }

        public boolean hasViewing() {
            return viewing != null;
        }

        public boolean hasShouldWatch() {
            return shouldWatch != null;
        }

        public boolean resolveViewing(boolean previous) {
            return viewing == null ? previous : viewing;
        }

        public boolean resolveShouldWatch(boolean previous) {
            return shouldWatch == null ? previous : shouldWatch;
        }
    }
}
