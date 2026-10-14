package blackstart;

/** Read-only lesson progression: success comes from measured physical facts, never grants power. */
public final class TrainingModel {
    public enum Failure { NONE, AUXILIARY, LOAD, LINK }
    public record Facts(boolean sourceRunning,boolean reference,double auxiliaryDelivered,double auxiliaryRequired,
                        boolean mainRunning,int loadBatches,double load,double available,double linkCapacity,
                        boolean synchronism,boolean interconnected){}
    public record Status(int completed,Failure failure,Facts facts){}
    private int completed;
    public Status observe(Facts f){
        Failure failure=f.load>f.available+1e-6?Failure.LOAD:f.load>f.linkCapacity+1e-6?Failure.LINK:
            f.sourceRunning && f.auxiliaryRequired>0 && f.auxiliaryDelivered+1e-6*Math.max(1,f.auxiliaryRequired)<f.auxiliaryRequired?Failure.AUXILIARY:Failure.NONE;
        boolean ready=switch(completed){
            case 0 -> f.sourceRunning;
            case 1 -> f.reference;
            case 2 -> f.auxiliaryRequired>0 && f.auxiliaryDelivered+1e-6*Math.max(1,f.auxiliaryRequired)>=f.auxiliaryRequired;
            case 3 -> f.mainRunning;
            case 4 -> f.mainRunning && f.loadBatches>=2 && failure==Failure.NONE;
            case 5 -> f.synchronism && failure==Failure.NONE;
            case 6 -> f.interconnected && failure==Failure.NONE;
            default -> false;
        };
        if(ready)completed++;
        return new Status(completed,failure,f);
    }
}
