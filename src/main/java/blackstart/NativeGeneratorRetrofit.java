package blackstart;

import arc.func.Prov;
import arc.util.Log;
import arc.util.Time;
import arc.util.io.Reads;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.GameState;
import mindustry.core.World;
import mindustry.gen.Building;
import mindustry.logic.LAccess;
import mindustry.world.Block;
import mindustry.world.blocks.power.*;
import java.util.List;

/** Output-only adapters; every resource, heat and protection update remains native. */
public final class NativeGeneratorRetrofit {
    private NativeGeneratorRetrofit(){}
    public static List<NativeGenerationProfile> profiles(){
        return List.of(
            new NativeGenerationProfile("combustion", Blocks.combustionGenerator, 180, "3s start; native combustible fuel."),
            new NativeGenerationProfile("steam", Blocks.steamGenerator, 480, "8s start; native fuel and water."),
            new NativeGenerationProfile("differential", Blocks.differentialGenerator, 720, "12s start; native pyratite and cryofluid."),
            new NativeGenerationProfile("rtg", Blocks.rtgGenerator, 0, "Passive native radioactive fuel output; no combustion ramp."),
            new NativeGenerationProfile("solar", Blocks.solarPanel, 60, "1s inverter connection; native sunlight and environment."),
            new NativeGenerationProfile("largeSolar", Blocks.largeSolarPanel, 60, "1s inverter connection; native sunlight and environment."),
            new NativeGenerationProfile("thermal", Blocks.thermalGenerator, 480, "8s start; native ground heat."),
            new NativeGenerationProfile("thorium", Blocks.thoriumReactor, 1500, "25s start; native fuel, cooling and overheat protection."),
            new NativeGenerationProfile("impact", Blocks.impactReactor, 1200, "20s minimum; native auxiliary power and slower warmup remain."),
            new NativeGenerationProfile("turbine", Blocks.turbineCondenser, 480, "8s start; native vent heat and water output."),
            new NativeGenerationProfile("chemical", Blocks.chemicalCombustionChamber, 180, "3s start; native arkycite and ozone."),
            new NativeGenerationProfile("pyrolysis", Blocks.pyrolysisGenerator, 600, "10s start; native slag, arkycite and water output."),
            new NativeGenerationProfile("flux", Blocks.fluxReactor, 1200, "20s start; native heat, cyanogen and instability protection."),
            new NativeGenerationProfile("neoplasia", Blocks.neoplasiaReactor, 1200, "20s start; native fuel, liquids, heat and overflow protection."));
    }
    public static void install(){for(NativeGenerationProfile profile : profiles()) install(profile);}
    private static void install(NativeGenerationProfile profile){
        Block block = profile.block();
        if(block == null || block.minfo.mod != null) return;
        Prov<Building> original = block.buildType;
        if(original instanceof NativeFactory factory && factory.profile.block() == block) return;
        if(original == null || original.getClass().getNestHost() != Block.class){
            Log.warn("[bs] native adapter skipped foreign factory: @", block.name);
            return;
        }
        Class<?> expected = block instanceof HeaterGenerator ? HeaterGenerator.HeaterGeneratorBuild.class
            : block instanceof ConsumeGenerator ? ConsumeGenerator.ConsumeGeneratorBuild.class
            : block instanceof SolarGenerator ? SolarGenerator.SolarGeneratorBuild.class
            : block instanceof ThermalGenerator ? ThermalGenerator.ThermalGeneratorBuild.class
            : block instanceof NuclearReactor ? NuclearReactor.NuclearReactorBuild.class
            : block instanceof ImpactReactor ? ImpactReactor.ImpactReactorBuild.class
            : block instanceof VariableReactor ? VariableReactor.VariableReactorBuild.class : null;
        Building probe = original.get();
        if(expected == null || probe == null || probe.getClass() != expected){
            Log.warn("[bs] native adapter skipped unfamiliar building: @", block.name);
            return;
        }
        block.buildType = new NativeFactory(profile);
    }
    public static boolean adapted(Building building){return building instanceof Adapted;}
    public static float rampFraction(Building building){
        return building instanceof Adapted adapted ? adapted.startup().fraction(building) : 1f;
    }
    public static NativeGenerationProfile profile(Building building){
        return building instanceof Adapted adapted ? adapted.startup().profile : null;
    }
    public static NativeGenerationState state(Building building){
        NativeGenerationProfile profile = profile(building);
        return profile == null ? null : new NativeGenerationState(profile, rampFraction(building),
            BlackstartFusionPolicy.nativeEnabled(profile.device()));
    }
    static boolean restore(Building b,float progress){
        if(!(b instanceof Adapted a) || !Float.isFinite(progress) || progress<0 || progress>1
            || !b.enabled || b.dead || !BlackstartFusionPolicy.nativeEnabled(a.startup().profile.device()))return false;
        Startup s=a.startup();s.beforeConsumption(b);
        float scale=resourceScale(b);if(!(scale>0))return false;
        s.resumeScale=scale;
        s.progress=progress;
        return progress>0;
    }
    /** Validate physical inputs, never the saved non-buffered power.status. Native update still pays once. */
    private static float resourceScale(Building b){
        if(!b.enabled || b.dead)return 0;
        if(b instanceof PowerGenerator.GeneratorBuild g && (!Float.isFinite(g.productionEfficiency) || g.productionEfficiency<0))return 0;
        if(b instanceof SolarGenerator.SolarGeneratorBuild solar){
            float sunlight=Vars.state.rules.solarMultiplier*Math.max(0,mindustry.world.meta.Attribute.light.env()
                +(Vars.state.rules.lighting?1-Vars.state.rules.ambientLight.a:1));
            if(!(sunlight>0))return 0;
            solar.productionEfficiency=Math.min(solar.productionEfficiency,sunlight);
        }
        if(b instanceof ThermalGenerator.ThermalGeneratorBuild thermal){
            float heat=thermal.sum+((ThermalGenerator)b.block).attribute.env();
            if(!(heat>0))return 0;
            thermal.productionEfficiency=Math.min(thermal.productionEfficiency,heat);
        }
        if(b instanceof VariableReactor.VariableReactorBuild reactor){
            float heat=reactor.calculateHeat(new float[4]);
            if(!(heat>0) || !Float.isFinite(heat))return 0;
            reactor.productionEfficiency=Math.min(reactor.productionEfficiency,Math.min(1,heat/((VariableReactor)b.block).maxHeat));
        }
        float scale=1;
        for(var c:b.block.consumers){
            if(c.optional || c instanceof mindustry.world.consumers.ConsumePower)continue;
            // Burning fuel is already paid and saved by GeneratorBuild.generateTime.
            if(b instanceof ConsumeGenerator.ConsumeGeneratorBuild g && g.generateTime>0
                && (c instanceof mindustry.world.consumers.ConsumeItemFilter || c instanceof mindustry.world.consumers.ConsumeItems))continue;
            float e=c.efficiency(b);if(!Float.isFinite(e) || e<=0)return 0;
            scale=Math.min(scale,e);
        }
        if(b instanceof NuclearReactor.NuclearReactorBuild && b.liquids!=null
            && b.liquids.get(mindustry.content.Liquids.cryofluid)<=0)return 0;
        return scale;
    }
    private interface Adapted { Startup startup(); }
    private static final class NativeFactory implements Prov<Building> {
        final NativeGenerationProfile profile;
        NativeFactory(NativeGenerationProfile profile){this.profile = profile;}
        @Override public Building get(){
            Block block = profile.block();
            if(block instanceof HeaterGenerator g) return new HeaterBuild(g, profile);
            if(block instanceof ConsumeGenerator g) return new ConsumeBuild(g, profile);
            if(block instanceof SolarGenerator g) return new SolarBuild(g, profile);
            if(block instanceof ThermalGenerator g) return new ThermalBuild(g, profile);
            if(block instanceof NuclearReactor g) return new NuclearBuild(g, profile);
            if(block instanceof ImpactReactor g) return new ImpactBuild(g, profile);
            if(block instanceof VariableReactor g) return new VariableBuild(g, profile);
            throw new IllegalStateException("Unsupported native generator: " + block.name);
        }
    }
    private static final class Startup {
        final NativeGenerationProfile profile;
        float progress;
        float resumeScale=1;
        boolean wasActive;
        long revision = -1;
        GameState state;
        World world;
        int syncReads;
        Startup(NativeGenerationProfile profile){this.profile = profile;}
        void beforeConsumption(Building building){
            boolean active = BlackstartFusionPolicy.nativeEnabled(profile.device());
            long currentRevision = BlackstartFusionPolicy.revision(profile.device());
            // Rules are replaced by ordinary host broadcasts, including unrelated mod tags.
            // World/state lifetime and explicit policy changes are the cold-start boundary.
            if(!active || !wasActive || !building.enabled || revision != currentRevision
                || state != Vars.state || world != Vars.world) progress = 0;
            wasActive = active;
            revision = currentRevision;
            state = Vars.state;
            world = Vars.world;
        }
        void afterConsumption(Building building){if(building.efficiency <= 0) progress = 0;}
        void afterTile(Building building, float nativeOutput){
            resumeScale=1;
            if(BlackstartFusionPolicy.nativeEnabled(profile.device()) && building.enabled && !building.dead
                && building.efficiency > 0 && nativeOutput > 0){
                float dt = Float.isFinite(Time.delta) ? Math.max(0, Time.delta) : 0;
                progress = profile.startupTicks() == 0 ? 1 : Math.min(1, progress + dt / profile.startupTicks());
            }else progress = 0;
        }
        float fraction(Building building){
            if(!BlackstartFusionPolicy.nativeEnabled(profile.device())) return 1;
            if(!building.enabled || building.dead || !wasActive || revision != BlackstartFusionPolicy.revision(profile.device())
                || Vars.state == null || state != Vars.state || world != Vars.world) return 0;
            return progress;
        }
        void read(){
            // Building.readSync delegates to readAll -> virtual read. Only a save read
            // resets transient startup; periodic native sync must preserve its clock.
            if(syncReads > 0) return;
            progress = 0; resumeScale=1; wasActive = false; revision = -1; state = null; world = null;
        }
    }

    private static final class ConsumeBuild extends ConsumeGenerator.ConsumeGeneratorBuild implements Adapted {
        final Startup startup;
        ConsumeBuild(ConsumeGenerator generator, NativeGenerationProfile profile){
            generator.super();
            startup = new Startup(profile);
        }
        @Override public Startup startup(){return startup;}
        @Override public void updateConsumption(){
            startup.beforeConsumption(this);
            super.updateConsumption();
            startup.afterConsumption(this);
        }
        
        @Override public void updateTile(){
            super.updateTile();
            startup.afterTile(this, super.getPowerProduction());
        }
        @Override public float getPowerProduction(){
            
            return super.getPowerProduction() * startup.fraction(this) * startup.resumeScale;
        }
        @Override public void control(LAccess type, double p1, double p2, double p3, double p4){
            super.control(type, p1, p2, p3, p4);
            if(!enabled){startup.progress = 0;if(type==LAccess.enabled)BlackstartContinuity.command(this);}
        }
        @Override public void readSync(Reads read, byte revision){
            startup.syncReads++;
            try{super.readSync(read, revision);}finally{
                startup.syncReads--;
                if(!enabled || dead) startup.progress = 0;
            }
        }
        @Override public void read(Reads read, byte revision){
            super.read(read, revision);
            startup.read();
        }
    }

    private static final class SolarBuild extends SolarGenerator.SolarGeneratorBuild implements Adapted {
        final Startup startup;
        SolarBuild(SolarGenerator generator, NativeGenerationProfile profile){
            generator.super();
            startup = new Startup(profile);
        }
        @Override public Startup startup(){return startup;}
        @Override public void updateConsumption(){
            startup.beforeConsumption(this);
            super.updateConsumption();
            startup.afterConsumption(this);
        }
        
        @Override public void updateTile(){
            super.updateTile();
            startup.afterTile(this, super.getPowerProduction());
        }
        @Override public float getPowerProduction(){
            
            return super.getPowerProduction() * startup.fraction(this) * startup.resumeScale;
        }
        @Override public void control(LAccess type, double p1, double p2, double p3, double p4){
            super.control(type, p1, p2, p3, p4);
            if(!enabled){startup.progress = 0;if(type==LAccess.enabled)BlackstartContinuity.command(this);}
        }
        @Override public void readSync(Reads read, byte revision){
            startup.syncReads++;
            try{super.readSync(read, revision);}finally{
                startup.syncReads--;
                if(!enabled || dead) startup.progress = 0;
            }
        }
        @Override public void read(Reads read, byte revision){
            super.read(read, revision);
            startup.read();
        }
    }

    private static final class ThermalBuild extends ThermalGenerator.ThermalGeneratorBuild implements Adapted {
        final Startup startup;
        ThermalBuild(ThermalGenerator generator, NativeGenerationProfile profile){
            generator.super();
            startup = new Startup(profile);
        }
        @Override public Startup startup(){return startup;}
        @Override public void updateConsumption(){
            startup.beforeConsumption(this);
            super.updateConsumption();
            startup.afterConsumption(this);
        }
        
        @Override public void updateTile(){
            super.updateTile();
            startup.afterTile(this, super.getPowerProduction());
        }
        @Override public float getPowerProduction(){
            
            return super.getPowerProduction() * startup.fraction(this) * startup.resumeScale;
        }
        @Override public void control(LAccess type, double p1, double p2, double p3, double p4){
            super.control(type, p1, p2, p3, p4);
            if(!enabled){startup.progress = 0;if(type==LAccess.enabled)BlackstartContinuity.command(this);}
        }
        @Override public void readSync(Reads read, byte revision){
            startup.syncReads++;
            try{super.readSync(read, revision);}finally{
                startup.syncReads--;
                if(!enabled || dead) startup.progress = 0;
            }
        }
        @Override public void read(Reads read, byte revision){
            super.read(read, revision);
            startup.read();
        }
    }

    private static final class NuclearBuild extends NuclearReactor.NuclearReactorBuild implements Adapted {
        final Startup startup;
        NuclearBuild(NuclearReactor generator, NativeGenerationProfile profile){
            generator.super();
            startup = new Startup(profile);
        }
        @Override public Startup startup(){return startup;}
        @Override public void updateConsumption(){
            startup.beforeConsumption(this);
            super.updateConsumption();
            startup.afterConsumption(this);
        }
        
        @Override public void updateTile(){
            super.updateTile();
            startup.afterTile(this, super.getPowerProduction());
        }
        @Override public float getPowerProduction(){
            
            return super.getPowerProduction() * startup.fraction(this) * startup.resumeScale;
        }
        @Override public void control(LAccess type, double p1, double p2, double p3, double p4){
            super.control(type, p1, p2, p3, p4);
            if(!enabled){startup.progress = 0;if(type==LAccess.enabled)BlackstartContinuity.command(this);}
        }
        @Override public void readSync(Reads read, byte revision){
            startup.syncReads++;
            try{super.readSync(read, revision);}finally{
                startup.syncReads--;
                if(!enabled || dead) startup.progress = 0;
            }
        }
        @Override public void read(Reads read, byte revision){
            super.read(read, revision);
            startup.read();
        }
    }

    private static final class ImpactBuild extends ImpactReactor.ImpactReactorBuild implements Adapted {
        final Startup startup;
        ImpactBuild(ImpactReactor generator, NativeGenerationProfile profile){
            generator.super();
            startup = new Startup(profile);
        }
        @Override public Startup startup(){return startup;}
        @Override public void updateConsumption(){
            startup.beforeConsumption(this);
            super.updateConsumption();
            startup.afterConsumption(this);
        }
        boolean nativeUpdating;
        @Override public void updateTile(){
            nativeUpdating = true;
            try{super.updateTile();}finally{nativeUpdating = false;}
            startup.afterTile(this, super.getPowerProduction());
        }
        @Override public float getPowerProduction(){
            if(nativeUpdating) return super.getPowerProduction();
            return super.getPowerProduction() * startup.fraction(this) * startup.resumeScale;
        }
        @Override public void control(LAccess type, double p1, double p2, double p3, double p4){
            super.control(type, p1, p2, p3, p4);
            if(!enabled){startup.progress = 0;if(type==LAccess.enabled)BlackstartContinuity.command(this);}
        }
        @Override public void readSync(Reads read, byte revision){
            startup.syncReads++;
            try{super.readSync(read, revision);}finally{
                startup.syncReads--;
                if(!enabled || dead) startup.progress = 0;
            }
        }
        @Override public void read(Reads read, byte revision){
            super.read(read, revision);
            startup.read();
        }
    }

    private static final class VariableBuild extends VariableReactor.VariableReactorBuild implements Adapted {
        final Startup startup;
        VariableBuild(VariableReactor generator, NativeGenerationProfile profile){
            generator.super();
            startup = new Startup(profile);
        }
        @Override public Startup startup(){return startup;}
        @Override public void updateConsumption(){
            startup.beforeConsumption(this);
            super.updateConsumption();
            startup.afterConsumption(this);
        }
        
        @Override public void updateTile(){
            super.updateTile();
            startup.afterTile(this, super.getPowerProduction());
        }
        @Override public float getPowerProduction(){
            
            return super.getPowerProduction() * startup.fraction(this) * startup.resumeScale;
        }
        @Override public void control(LAccess type, double p1, double p2, double p3, double p4){
            super.control(type, p1, p2, p3, p4);
            if(!enabled){startup.progress = 0;if(type==LAccess.enabled)BlackstartContinuity.command(this);}
        }
        @Override public void readSync(Reads read, byte revision){
            startup.syncReads++;
            try{super.readSync(read, revision);}finally{
                startup.syncReads--;
                if(!enabled || dead) startup.progress = 0;
            }
        }
        @Override public void read(Reads read, byte revision){
            super.read(read, revision);
            startup.read();
        }
    }

    private static final class HeaterBuild extends HeaterGenerator.HeaterGeneratorBuild implements Adapted {
        final Startup startup;
        HeaterBuild(HeaterGenerator generator, NativeGenerationProfile profile){
            generator.super();
            startup = new Startup(profile);
        }
        @Override public Startup startup(){return startup;}
        @Override public void updateConsumption(){
            startup.beforeConsumption(this);
            super.updateConsumption();
            startup.afterConsumption(this);
        }
        
        @Override public void updateTile(){
            super.updateTile();
            startup.afterTile(this, super.getPowerProduction());
        }
        @Override public float getPowerProduction(){
            
            return super.getPowerProduction() * startup.fraction(this) * startup.resumeScale;
        }
        @Override public void control(LAccess type, double p1, double p2, double p3, double p4){
            super.control(type, p1, p2, p3, p4);
            if(!enabled){startup.progress = 0;if(type==LAccess.enabled)BlackstartContinuity.command(this);}
        }
        @Override public void readSync(Reads read, byte revision){
            startup.syncReads++;
            try{super.readSync(read, revision);}finally{
                startup.syncReads--;
                if(!enabled || dead) startup.progress = 0;
            }
        }
        @Override public void read(Reads read, byte revision){
            super.read(read, revision);
            startup.read();
        }
    }
}
