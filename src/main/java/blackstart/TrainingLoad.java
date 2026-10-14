package blackstart;

import arc.scene.ui.layout.Table;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerBlock;
import mindustry.world.meta.BuildVisibility;

/** Sandbox exercise load bank. Its demand is settled by the real native PowerGraph. */
public final class TrainingLoad extends PowerBlock {
    public static final int[] LEVELS={0,120,360,720,2000,6000};
    public TrainingLoad(String name){
        super(name);consumesPower=true;outputsPower=false;configurable=true;saveConfig=true;sync=true;
        buildVisibility=BuildVisibility.sandboxOnly;canOverdrive=false;
        consumePowerDynamic((TrainingLoadBuild b)->b.enabled?b.demand()/60f:0f);
        config(Integer.class,(TrainingLoadBuild b,Integer mode)->b.setMode(mode));
    }
    public class TrainingLoadBuild extends Building {
        private int mode;
        public int demand(){return LEVELS[mode];}
        public void setMode(int value){mode=Math.max(0,Math.min(LEVELS.length-1,value));shouldConsumePower=mode>0;}
        @Override public Integer config(){return mode;}
        @Override public byte version(){return 1;}
        @Override public void write(Writes w){super.write(w);w.b(mode);}
        @Override public void read(Reads r,byte revision){super.read(r,revision);setMode(revision>=1?r.ub():0);}
        @Override public void buildConfiguration(Table table){
            table.add(BlackstartText.get("blackstart.training.loadHelp","Demand in power/s; 2000 tests line capacity, 6000 tests source capacity")).wrap().width(360).colspan(3).row();
            table.label(()->String.format(java.util.Locale.ROOT,BlackstartText.get("blackstart.training.delivery","Delivered %.0f / %.0f power/s (%.1f%%)"),
                demand()*(power==null?0:power.status),(double)demand(),100d*(power==null?0:power.status))).colspan(3).row();
            for(int i=0;i<LEVELS.length;i++){final int value=i;table.button(Integer.toString(LEVELS[i]),()->configure(value)).size(110,40);if(i%3==2)table.row();}
        }
    }
}
