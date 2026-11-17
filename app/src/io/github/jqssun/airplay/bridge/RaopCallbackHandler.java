package io.github.jqssun.airplay.bridge;

/** Callbacks resolved by JNI and invoked on the native server's threads. */
public interface RaopCallbackHandler {
    void onVideoData(byte[] data, long ntpTimeNs, boolean isH265);
    void onAudioFormat(int ct, int spf, boolean usingScreen);
    void onVideoSize(float sourceWidth, float sourceHeight, float width, float height);

    /** Sender volume in decibels, with -144 representing mute. */
    void onClientVolume(float db);
    float onClientVolumeQuery();
    void onAudioTeardown();

    void onConnectionInit();
    void onConnectionDestroy();
    void onConnectionReset(int reason);
    void onDisplayPin(String pin);
    void onMirrorRunning(boolean running);

    default void onMetadata(byte[] data) {}
    default void onCoverArt(byte[] data) {}
    default void onProgress(long start, long current, long end) {}
    default void onDacpId(String dacpId, String activeRemote) {}

    default void onVideoPlay(String location, float startPositionSeconds) {}
    default void onVideoScrub(float positionSeconds) {}
    default void onVideoRate(float rate) {}
    default void onVideoStop() {}
    default void onVideoSessionPoll() {}

    // The native logger also resolves this method. Logcat already receives its output.
    default void onLog(String message) {}
}
