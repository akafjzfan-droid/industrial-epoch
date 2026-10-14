package blackstart;

import mindustry.world.Block;

/** Immutable, read-only description shared by settings and building readouts. */
public record NativeGenerationProfile(String device, Block block, float startupTicks, String description){
    public String descriptionKey(){return "blackstart.nativeFusion." + device + ".description";}
}
