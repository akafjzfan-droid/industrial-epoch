package blackstart;

import arc.scene.ui.layout.Table;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Items;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.blocks.power.PowerBlock;
import java.util.Locale;

/** Generic resource-backed controllable machine, not a claim to model a specific fuel technology. */
public class ControlledGenerator extends PowerBlock {
    public static final double COAL_ENERGY=60000,IDLE_POWER=10;
    public final double ratedPower,auxiliaryPower,startupSeconds,minimumPower,rampPerSecond,responseSeconds;
    public ControlledGenerator(String name,double ratedPower,double auxiliaryPower,double startupSeconds,
                               double minimumPower,double rampPerSecond,double responseSeconds) {
        super(name);
        new MachineModel(ratedPower,auxiliaryPower,startupSeconds,minimumPower,rampPerSecond,responseSeconds);
        this.ratedPower=ratedPower;this.auxiliaryPower=auxiliaryPower;this.startupSeconds=startupSeconds;
        this.minimumPower=minimumPower;this.rampPerSecond=rampPerSecond;this.responseSeconds=responseSeconds;
        outputsPower=true;consumesPower=true;hasItems=true;itemCapacity=20;
        configurable=true;saveConfig=true;sync=true;canOverdrive=false;
        consumePowerDynamic((ControlledGeneratorBuild b)->b.enabled?(float)(b.model.auxiliaryDemand()/60d):0f);
        config(Boolean.class,(ControlledGeneratorBuild b,Boolean on)->b.setRequested(on));
        config(Integer.class,(ControlledGeneratorBuild b,Integer value)->{
            if(value>=2000 && value<=4025){
                int bits=value-2000,dispatch=(bits&1023)-1;
                if(dispatch<=1000){b.setDispatchPermille(dispatch);b.setRequested((bits&1024)!=0);}
            }else if(value>=-1 && value<=1000)b.setDispatchPermille(value);
        });
    }
    public class ControlledGeneratorBuild extends Building {
        @Override public void created(){super.created();BlackstartDynamics.register(this);}
        @Override public void onRemoved(){BlackstartDynamics.unregister(this);super.onRemoved();}
        private final MachineModel model=new MachineModel(ratedPower,auxiliaryPower,startupSeconds,minimumPower,rampPerSecond,responseSeconds);
        private double fuelEnergy,output;
        private byte remotePhase;
        private boolean syncing;
        private int dispatchPermille=-1;
        public MachineModel machine(){return model;}
        public double fuelEnergy(){return fuelEnergy;}
        public int dispatchPermille(){return dispatchPermille;}
        public void setDispatchPermille(int value){dispatchPermille=Math.max(-1,Math.min(1000,value));}
        void publishFrameOutput(double power){output=enabled?power:0;}
        public void setRequested(boolean value){if(!value)BlackstartContinuity.command(this);model.request(value);shouldConsumePower=value && auxiliaryPower>0;}
        public void prepareFrame(double seconds,double targetPower) {
            DynamicsModel.nonnegative(seconds,"seconds");DynamicsModel.finite(targetPower,"targetPower");
            if(seconds==0)return;
            if(!enabled){output=0;model.restoreRequest(model.requested());shouldConsumePower=false;return;}
            shouldConsumePower=model.requested() && auxiliaryPower>0;
            // Reserve enough chemical energy for this frame before advertising electrical output.
            double required=(ratedPower+IDLE_POWER)*seconds;
            while(model.requested() && fuelEnergy<required && items!=null && items.get(Items.coal)>0){items.remove(Items.coal,1);fuelEnergy+=COAL_ENERGY;}
            double satisfaction=power==null || !Float.isFinite(power.status)?0:Math.max(0,Math.min(1,power.status));
            // The first real graph has no current auxiliary measurement. Hold mechanical progress,
            // never advance a partial start, and still pay every advertised joule below.
            if(!BlackstartContinuity.awaitingMeasurement(this) || !model.requested() || fuelEnergy<IDLE_POWER*seconds)
                model.advance(seconds,fuelEnergy>=IDLE_POWER*seconds,auxiliaryPower*satisfaction,targetPower);
            double idle=model.requested()?Math.min(fuelEnergy,IDLE_POWER*seconds):0;
            output=Math.min(model.output(),Math.max(0,(fuelEnergy-idle)/seconds));
            fuelEnergy=Math.max(0,fuelEnergy-idle-output*seconds);
        }
        @Override public boolean acceptItem(Building source,Item item){return item==Items.coal && items!=null && items.get(item)<itemCapacity;}
        @Override public float getPowerProduction(){return (float)(output/60d);}
        @Override public Integer config(){return 2000+(model.requested()?1024:0)+dispatchPermille+1;}
        @Override public byte version(){return 2;}
        @Override public void write(Writes w){super.write(w);w.bool(model.requested());w.d(fuelEnergy);w.s(dispatchPermille);}
        @Override public void read(Reads r,byte revision){
            super.read(r,revision);
            boolean requested=revision>=1 && r.bool();double fuel=revision>=1?r.d():0;
            setDispatchPermille(revision>=2?r.s():-1);
            fuelEnergy=Double.isFinite(fuel)?Math.max(0,fuel):0;
            if(syncing)model.request(requested);else{model.restoreRequest(requested);output=0;}
        }
        @Override public void writeSync(Writes w){super.writeSync(w);w.d(output);w.b(model.phase().ordinal());}
        @Override public void readSync(Reads r,byte revision){
            syncing=true;try{super.readSync(r,revision);}finally{syncing=false;}
            output=r.d();remotePhase=r.b();
        }
        private String phaseText(){
            int phase=mindustry.Vars.net!=null && mindustry.Vars.net.client()?remotePhase:model.phase().ordinal();
            if(phase<0 || phase>=MachineModel.Phase.values().length)phase=0;
            String name=MachineModel.Phase.values()[phase].name();
            return BlackstartText.get("blackstart.machine.phase."+name,name);
        }
        @Override public void buildConfiguration(Table table){
            table.label(this::phaseText).colspan(2).row();
            table.label(()->String.format(Locale.ROOT,BlackstartText.get("blackstart.machine.output","Output %.0f / %.0f"),output,ratedPower)).colspan(2).row();
            table.button(BlackstartText.get("blackstart.machine.start","Start"),()->configure(true)).size(120,40);
            table.button(BlackstartText.get("blackstart.machine.stop","Stop"),()->configure(false)).size(120,40);
            table.row();table.label(()->dispatchPermille<0?BlackstartText.get("blackstart.machine.dispatch.auto","Initial-load dispatch"):
                String.format(Locale.ROOT,BlackstartText.get("blackstart.machine.dispatch","Dispatch %.0f power/s"),ratedPower*dispatchPermille/1000d)).colspan(2).row();
            table.button(BlackstartText.get("blackstart.machine.dispatch.down","Lower 10%"),()->configure(Math.max(0,dispatchPermille-100))).size(120,40);
            table.button(BlackstartText.get("blackstart.machine.dispatch.up","Raise 10%"),()->configure(Math.min(1000,Math.max(0,dispatchPermille)+100))).size(120,40);
            table.row();table.add(BlackstartText.get("blackstart.machine.dispatch.help","Primary droop leaves a steady frequency offset. Adjust dispatch or load before synchronizing; changes still obey ramp and response limits.")).wrap().width(260).colspan(2);
        }
    }
}
