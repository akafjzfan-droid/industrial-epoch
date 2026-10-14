package blackstart;

import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.consumers.ConsumePower;

/** Read-only, per-target L0 observation. Called by the existing lazy hover cache, never a world scan. */
final class VanillaPowerObservation {
    enum Kind { GENERATOR, BUFFERED, LOAD, NETWORK, UNKNOWN_SOURCE }

    /** Power is in engine power units/second; stored/capacity are engine energy units. NaN means unavailable. */
    record Snapshot(Kind kind, float output, float rated, float demand, float delivered,
                    float charge, float stored, float capacity) {}

    private VanillaPowerObservation() {}

    static Kind kind(Building b){
        if(b==null || b.block==null) return Kind.NETWORK;
        ConsumePower consumer=b.block.consPower;
        // Buffered status is stored charge, even though batteries also advertise outputsPower.
        return consumer!=null && consumer.buffered ? Kind.BUFFERED
            : b.block instanceof PowerGenerator ? Kind.GENERATOR
            : b.block.outputsPower ? Kind.UNKNOWN_SOURCE
            : consumer!=null && b.block.consumesPower ? Kind.LOAD : Kind.NETWORK;
    }

    static Snapshot read(Building b){
        float output=Float.NaN, rated=Float.NaN, demand=Float.NaN, delivered=Float.NaN;
        float charge=Float.NaN, stored=Float.NaN, capacity=Float.NaN;
        if(b==null || b.block==null) return new Snapshot(Kind.NETWORK,output,rated,demand,delivered,charge,stored,capacity);
        ConsumePower consumer=b.block.consPower;
        Kind kind=kind(b);
        if(b.power!=null){
            if(kind==Kind.BUFFERED){
                capacity=nonnegative(consumer.capacity);
                if(!(capacity>0f)) capacity=Float.NaN;
                charge=fraction(b.power.status);
                stored=nonnegative(charge*capacity);
            }else{
                if(kind==Kind.GENERATOR){
                    // Match PowerGraph production's timeScale, independently of frame delta.
                    output=perSecond(b.getPowerProduction(),b.timeScale());
                    // This is the block's base rating, not available capacity or an export budget.
                    rated=perSecond(((PowerGenerator)b.block).powerProduction,1f);
                }
                if(consumer!=null && b.block.consumesPower){
                    demand=b.shouldConsumePower ? perSecond(consumer.requestedPower(b),b.timeScale()) : 0f;
                    delivered=demand==0f ? 0f : nonnegative(demand*fraction(b.power.status));
                }
            }
        }
        return new Snapshot(kind,output,rated,demand,delivered,charge,stored,capacity);
    }

    private static float perSecond(float perTick,float scale){
        return nonnegative(nonnegative(perTick)*nonnegative(scale)*60f);
    }
    private static float nonnegative(float value){
        return Float.isFinite(value) && value>=0f ? value : Float.NaN;
    }
    private static float fraction(float value){
        return Float.isFinite(value) && value>=0f && value<=1f ? value : Float.NaN;
    }
}
