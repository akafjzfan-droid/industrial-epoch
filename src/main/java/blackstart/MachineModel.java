package blackstart;

/** Startup chain and mechanical output. Self-start follows from resource availability and aux=0. */
public final class MachineModel {
    public enum Phase { OFF, WAIT_RESOURCE, WAIT_AUX, STARTING, RUNNING }
    public final double ratedPower, auxiliaryPower, startupSeconds, minimumPower, rampPerSecond, responseSeconds;
    private boolean requested;
    private double elapsed, output;
    private Phase phase=Phase.OFF;
    public MachineModel(double ratedPower,double auxiliaryPower,double startupSeconds,double minimumPower,
                        double rampPerSecond,double responseSeconds) {
        DynamicsModel.positive(ratedPower,"ratedPower"); DynamicsModel.nonnegative(auxiliaryPower,"auxiliaryPower");
        DynamicsModel.nonnegative(startupSeconds,"startupSeconds"); DynamicsModel.nonnegative(minimumPower,"minimumPower");
        DynamicsModel.nonnegative(rampPerSecond,"rampPerSecond"); DynamicsModel.positive(responseSeconds,"responseSeconds");
        if(minimumPower>ratedPower) throw new IllegalArgumentException("minimum exceeds rating");
        this.ratedPower=ratedPower;this.auxiliaryPower=auxiliaryPower;this.startupSeconds=startupSeconds;
        this.minimumPower=minimumPower;this.rampPerSecond=rampPerSecond;this.responseSeconds=responseSeconds;
    }
    public void request(boolean enabled){ requested=enabled; }
    public boolean requested(){return requested;}
    public boolean running(){return phase==Phase.RUNNING;}
    public Phase phase(){return phase;}
    public double elapsed(){return elapsed;}
    public double output(){return output;}
    public double auxiliaryDemand(){return requested ? auxiliaryPower : 0;}
    public void advance(double seconds,boolean resourceReady,double deliveredAuxiliary,double target) {
        DynamicsModel.nonnegative(seconds,"seconds");DynamicsModel.nonnegative(deliveredAuxiliary,"deliveredAuxiliary");
        DynamicsModel.finite(target,"target");
        if(!requested){ reset(Phase.OFF);return; }
        if(!resourceReady){reset(Phase.WAIT_RESOURCE);return;}
        if(deliveredAuxiliary+1e-6*Math.max(1,auxiliaryPower)<auxiliaryPower){reset(Phase.WAIT_AUX);return;}
        if(!running()) {
            phase=Phase.STARTING;elapsed+=seconds;output=0;
            if(elapsed+1e-9>=startupSeconds){elapsed=startupSeconds;phase=Phase.RUNNING;}
            return; // No retroactive generation during a start interval.
        }
        double bounded=Math.max(minimumPower,Math.min(ratedPower,target));
        output=DynamicsModel.governorStep(output,bounded,responseSeconds,rampPerSecond,seconds);
    }
    private void reset(Phase next){phase=next;elapsed=output=0;}
    /** Safe load semantics: an enabled machine re-establishes its startup chain, never loads as running. */
    public void restoreRequest(boolean enabled){requested=enabled;reset(Phase.OFF);}
    /** Only the complete-save coordinator may supply mechanical state; no fuel or power is restored here. */
    boolean restoreState(int savedPhase,double savedElapsed,double savedOutput){
        if(!requested || savedPhase<0 || savedPhase>=Phase.values().length || !Double.isFinite(savedElapsed)
            || !Double.isFinite(savedOutput) || savedElapsed<0 || savedElapsed>startupSeconds
            || savedOutput<0 || savedOutput>ratedPower)return false;
        Phase next=Phase.values()[savedPhase];
        if(next==Phase.RUNNING ? savedElapsed!=startupSeconds : savedOutput!=0)return false;
        if(next!=Phase.STARTING && next!=Phase.RUNNING && savedElapsed!=0)return false;
        phase=next;elapsed=savedElapsed;output=savedOutput;return true;
    }
}
