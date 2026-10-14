package blackstart;

/** Snapshot; the adapter's transient clocks are never added to native save bytes. */
public record NativeGenerationState(NativeGenerationProfile profile, float rampFraction, boolean adapting){}
