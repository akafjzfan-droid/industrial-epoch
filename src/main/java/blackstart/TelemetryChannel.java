package blackstart;

import java.util.Base64;

/** Bounded atomic reassembly. A world-specific client nonce must bind the server epoch first. */
public final class TelemetryChannel {
    public static final int PART_CHARS=24000,MAX_PARTS=64;
    public static final long MAX_AGE_MILLIS=500;
    private long nonce,epoch,lastSequence=-1,pendingSequence=-1,lastReceived=-1;
    private boolean bound;
    private String[] parts;
    private int received;
    public void begin(long nonce){this.nonce=nonce;bound=false;lastSequence=pendingSequence=lastReceived=-1;parts=null;received=0;}
    public boolean bind(long nonce,long epoch){
        if(this.nonce!=nonce)return false;
        if(bound)return this.epoch==epoch;
        this.epoch=epoch;bound=true;return true;
    }
    public boolean bound(){return bound;}
    public boolean fresh(long now){return lastReceived>=0 && now>=lastReceived && now-lastReceived<=MAX_AGE_MILLIS;}
    public byte[] accept(String packet,long now){
        if(!bound || packet==null || packet.length()>PART_CHARS+150)return null;
        try{
            String[] fields=packet.split("\\|",6);
            if(fields.length!=6 || !fields[0].equals("D") || Long.parseLong(fields[1])!=epoch)return null;
            long sequence=Long.parseLong(fields[2]);int index=Integer.parseInt(fields[3]),count=Integer.parseInt(fields[4]);
            if(sequence<=lastSequence || sequence<pendingSequence || count<1 || count>MAX_PARTS || index<0 || index>=count || fields[5].length()>PART_CHARS)return null;
            if(sequence!=pendingSequence){pendingSequence=sequence;parts=new String[count];received=0;}
            if(parts.length!=count)return null;
            if(parts[index]==null){parts[index]=fields[5];received++;}
            if(received!=count)return null;
            byte[] payload=Base64.getDecoder().decode(String.join("",parts));
            lastSequence=sequence;lastReceived=now;parts=null;return payload;
        }catch(IllegalArgumentException ex){return null;}
    }
    public static String[] fragment(byte[] bytes,long epoch,long sequence){
        if(bytes==null || bytes.length>MAX_PARTS*PART_CHARS*3/4)throw new IllegalArgumentException("telemetry frame too large");
        String encoded=Base64.getEncoder().encodeToString(bytes);
        int count=Math.max(1,(encoded.length()+PART_CHARS-1)/PART_CHARS);String[] packets=new String[count];
        for(int i=0;i<count;i++)packets[i]="D|"+epoch+"|"+sequence+"|"+i+"|"+count+"|"+encoded.substring(i*PART_CHARS,Math.min(encoded.length(),(i+1)*PART_CHARS));
        return packets;
    }
}
