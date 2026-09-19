package fabscreen.platform.base.obico;

/** A local WebRTC peer whose Janus signaling is relayed by the Obico device WebSocket. */
public interface ObicoLiveStream {
    interface Listener {
        void onJanusMessage(String payload);

        void onStateChanged();
    }

    void start(String authToken, Listener listener);

    void stop();

    void sendJanus(String payload);

    /** True only while a camera is configured and the peer is ready to negotiate a stream. */
    boolean isReady();

    /** True only while a viewer DataChannel is open; JPEG fallback remains available otherwise. */
    default boolean isStreaming() { return false; }
}
