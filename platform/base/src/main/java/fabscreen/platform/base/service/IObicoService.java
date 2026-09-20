package fabscreen.platform.base.service;

import org.json.JSONObject;

/** Process-level Obico integration exposed to the authenticated local dashboard. */
public interface IObicoService {
    /** Returns token-free configuration, connection, and linking state. */
    JSONObject getPublicStateJson();

    /** Persists validated public configuration and restarts the connector when required. */
    JSONObject applyConfiguration(JSONObject input);

    /** Starts automatic linking, or verifies the supplied optional six-digit code. */
    JSONObject beginLink(String sixDigitCode);

    /** Queues a non-mutating authenticated connection test. */
    JSONObject testConnection();

    /** Stops the connector and removes the encrypted printer credential. */
    JSONObject disconnect();
}
