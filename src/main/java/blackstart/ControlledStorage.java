package blackstart;

import arc.scene.ui.layout.Table;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerBlock;
import java.util.Locale;

/** Storage with an explicit energy ledger. Native buffered battery accounting is not also enabled. */
public class ControlledStorage extends PowerBlock {
    public final double capacity,chargeLimit,dischargeLimit;
    public final boolean forming;
    public ControlledStorage(String name,double capacity,double chargeLimit,double dischargeLimit,boolean forming) {
        super(name);
        new StorageModel(capacity,chargeLimit,dischargeLimit,forming); // validate content parameters
        this.capacity=capacity;this.chargeLimit=chargeLimit;this.dischargeLimit=dischargeLimit;this.forming=forming;
        outputsPower=true;consumesPower=true;configurable=true;saveConfig=true;sync=true;canOverdrive=false;
        consumePowerDynamic((ControlledStorageBuild b)->(float)(b.chargeRequest/60d));
        config(Integer.class,(ControlledStorageBuild b,Integer mode)->b.setMode(mode));
    }
    public class ControlledStorageBuild extends Building {
        @Override public void created(){super.created();BlackstartDynamics.register(this);}
        @Override public void onRemoved(){BlackstartDynamics.unregister(this);super.onRemoved();}
        private final StorageModel storage=new StorageModel(capacity,chargeLimit,dischargeLimit,forming);
        private int mode; // 0 hold, 1 charge, 2 supply; all start with zero energy.
        private double output,chargeRequest;
        private boolean chargePending;
        public double storedEnergy(){return storage.energy();}
        public int mode(){return mode;}
        double chargeRequest(){return chargeRequest;}
        void publishFrameOutput(double supplied,double requested,double seconds){
            output=enabled?supplied:0;
            chargeRequest=enabled?Math.min(requested,Math.max(0,(capacity-storage.energy())/seconds)):0;
            shouldConsumePower=chargeRequest>0;chargePending=chargeRequest>0;
        }
        public boolean referenceReady(){return enabled && mode==2 && storage.referenceReady();}
        public void setMode(int value){mode=Math.max(0,Math.min(2,value));shouldConsumePower=mode==1;}
        public void prepareFrame(double seconds,boolean liveReference,double demand) {
            DynamicsModel.nonnegative(seconds,"seconds");DynamicsModel.nonnegative(demand,"demand");
            output=chargeRequest=0;chargePending=false;
            if(!enabled || seconds==0) return;
            if(mode==1 && liveReference) chargeRequest=Math.min(chargeLimit,(capacity-storage.energy())/seconds);
            if(mode==2) output=storage.dispatch(demand,seconds,liveReference);
            shouldConsumePower=chargeRequest>0;
            chargePending=chargeRequest>0;
        }
        /** Called after native PowerGraph.update; only fulfilled charge demand becomes stored energy. */
        public void finishFrame(double seconds) {
            double satisfaction=power==null || !Float.isFinite(power.status)?0:Math.max(0,Math.min(1,power.status));
            if(chargePending) storage.dispatch(-chargeRequest*satisfaction,seconds,true);
            chargePending=false; // retain the demand for the next existing link-settlement measurement
        }
        @Override public float getPowerProduction(){return (float)(output/60d);}
        @Override public Integer config(){return mode;}
        @Override public byte version(){return 1;}
        @Override public void write(Writes w){super.write(w);w.b(mode);w.d(storage.energy());}
        @Override public void read(Reads r,byte revision){
            super.read(r,revision);output=chargeRequest=0;chargePending=false;
            if(revision>=1){setMode(r.ub());storage.restoreEnergy(r.d());}
            else{setMode(0);storage.restoreEnergy(0);}
        }
        @Override public void writeSync(Writes w){super.writeSync(w);w.d(output);w.d(chargeRequest);}
        @Override public void readSync(Reads r,byte revision){super.readSync(r,revision);output=r.d();chargeRequest=r.d();}
        @Override public void buildConfiguration(Table table){
            table.label(()->String.format(Locale.ROOT,BlackstartText.get("blackstart.storage.energy","Energy %.0f / %.0f"),storage.energy(),capacity)).colspan(3).row();
            for(int i=0;i<3;i++){final int value=i;table.button(BlackstartText.get("blackstart.storage.mode."+i,new String[]{"Hold","Charge","Supply"}[i]),()->configure(value)).size(110,40);}
        }
    }
}
