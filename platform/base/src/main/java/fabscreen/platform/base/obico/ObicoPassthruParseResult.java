package fabscreen.platform.base.obico;

/** Result of detecting and validating one Obico passthrough RPC envelope. */
public final class ObicoPassthruParseResult {
    private static final ObicoPassthruParseResult ABSENT =
            new ObicoPassthruParseResult(false, null, "", "");

    private final boolean present;
    private final ObicoPassthruRequest request;
    private final String reference;
    private final String error;

    private ObicoPassthruParseResult(
            boolean present,
            ObicoPassthruRequest request,
            String reference,
            String error) {
        this.present = present;
        this.request = request;
        this.reference = reference == null ? "" : reference;
        this.error = error == null ? "" : error;
    }

    static ObicoPassthruParseResult absent() {
        return ABSENT;
    }

    static ObicoPassthruParseResult accepted(ObicoPassthruRequest request) {
        return new ObicoPassthruParseResult(
                true,
                request,
                request == null ? "" : request.getReference(),
                "");
    }

    static ObicoPassthruParseResult rejected(String reference, String error) {
        return new ObicoPassthruParseResult(true, null, reference, error);
    }

    public boolean isPresent() {
        return present;
    }

    public boolean isAccepted() {
        return request != null;
    }

    public ObicoPassthruRequest getRequest() {
        return request;
    }

    /** Empty when the incoming reference itself was missing or invalid. */
    public String getReference() {
        return reference;
    }

    public String getError() {
        return error;
    }
}
