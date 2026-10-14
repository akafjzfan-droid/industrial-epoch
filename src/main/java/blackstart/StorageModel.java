package blackstart;

/** Energy accounting in game power-units and seconds. Dispatch returns interval-average power. */
public final class StorageModel {
    public final double capacity, chargeLimit, dischargeLimit;
    public final boolean forming;
    private double energy;
    public StorageModel(double capacity,double chargeLimit,double dischargeLimit,boolean forming) {
        DynamicsModel.positive(capacity,"capacity");
        DynamicsModel.nonnegative(chargeLimit,"chargeLimit");
        DynamicsModel.nonnegative(dischargeLimit,"dischargeLimit");
        this.capacity=capacity; this.chargeLimit=chargeLimit; this.dischargeLimit=dischargeLimit; this.forming=forming;
    }
    public double energy(){ return energy; }
    public boolean referenceReady(){ return forming && energy>0 && dischargeLimit>0; }
    public double dispatch(double requestedPower,double seconds,boolean liveReference) {
        DynamicsModel.finite(requestedPower,"requestedPower"); DynamicsModel.nonnegative(seconds,"seconds");
        if(seconds==0) return 0;
        if(requestedPower>0) {
            if(!liveReference && !referenceReady()) return 0;
            double power=Math.min(requestedPower,Math.min(dischargeLimit,energy/seconds));
            energy=Math.max(0,energy-power*seconds); return power;
        }
        if(!liveReference) return 0;
        double power=Math.min(-requestedPower,Math.min(chargeLimit,(capacity-energy)/seconds));
        energy=Math.min(capacity,energy+power*seconds); return -power;
    }
    /** Serialized energy is never allowed to create invalid or over-capacity state. */
    public void restoreEnergy(double value) {
        energy=Double.isFinite(value) ? Math.max(0,Math.min(capacity,value)) : 0;
    }
}
