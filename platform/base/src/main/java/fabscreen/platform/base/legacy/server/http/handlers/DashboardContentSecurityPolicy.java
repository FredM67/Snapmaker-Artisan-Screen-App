package fabscreen.platform.base.legacy.server.http.handlers;

/** Security policy for the authenticated local dashboard. */
final class DashboardContentSecurityPolicy {
    static final String VALUE =
            "default-src 'self'; img-src 'self' data: blob:; style-src 'unsafe-inline'; "
                    + "script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; "
                    + "base-uri 'none'; form-action 'none'";

    private DashboardContentSecurityPolicy() {
    }
}
