package blackstart;

import java.util.ArrayList;
import java.util.List;

/** Read-only view/export of the existing ring. Never writes files or changes its lifecycle. */
public final class BlackstartBlackbox {
    public record Row(long tick,String kind,int count,int x,int y,int aux,float value,ProtectionExplanation.Detail explanation) {}
    private BlackstartBlackbox() {}
    public static List<Row> snapshot() {
        List<Row> rows=new ArrayList<>(BlackstartEvents.size());
        for(int i=0;i<BlackstartEvents.size();i++) {
            BlackstartEvents.Entry e=BlackstartEvents.get(i);
            rows.add(new Row(e.tick,e.kind.name(),e.count,e.x,e.y,e.aux,e.value,ProtectionExplanation.get(e)));
        }
        return List.copyOf(rows);
    }
    public static String exportJson() {
        StringBuilder out=new StringBuilder("{\"schema\":2,\"seq\":").append(BlackstartEvents.seq()).append(",\"events\":[");
        boolean comma=false;
        for(Row row:snapshot()) {
            if(comma) out.append(','); comma=true;
            out.append("{\"tick\":").append(row.tick).append(",\"kind\":\"").append(row.kind)
                .append("\",\"count\":").append(row.count).append(",\"x\":").append(row.x)
                .append(",\"y\":").append(row.y).append(",\"aux\":").append(row.aux).append(",\"value\":")
                .append(Float.isFinite(row.value)?Float.toString(row.value):"null");
            if(row.explanation!=null) appendExplanation(out,row.explanation);   // M4 schema2:旁表行附四问
            out.append('}');
        }
        return out.append("]}").toString();
    }
    /** M4 schema2:explanation 对象 = 四问文本(describe,与面板同源)+ 旁表数值;非有限 double → null。 */
    private static void appendExplanation(StringBuilder out,ProtectionExplanation.Detail d){
        ProtectionExplanation.Text t=ProtectionExplanation.describe(d);
        out.append(",\"explanation\":{\"measured\":\"").append(escape(t.measured()))
            .append("\",\"device\":\"").append(escape(t.device()))
            .append("\",\"affected\":\"").append(escape(t.affected()))
            .append("\",\"recovery\":\"").append(escape(t.recovery()))
            .append("\",\"zoneAnchor\":").append(d.zoneAnchor())
            .append(",\"zoneMembers\":").append(d.zoneMembers())
            .append(",\"island\":").append(d.island())
            .append(",\"threshold\":").append(num(d.threshold()))
            .append(",\"duration\":").append(num(d.duration()))
            .append(",\"recloseDelay\":").append(num(d.recloseDelay()))
            .append(",\"recloseMax\":").append(d.recloseMax()).append('}');
    }
    private static String num(double v){return Double.isFinite(v)?Double.toString(v):"null";}
    /** JSON 字符串转义(引号/反斜杠/控制符;导出为低频按钮/命令路径,不优化)。 */
    private static String escape(String s){
        StringBuilder b=new StringBuilder(s.length()+8);
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c=='"') b.append("\\\"");
            else if(c=='\\') b.append("\\\\");
            else if(c=='\n') b.append("\\n");
            else if(c=='\r') b.append("\\r");
            else if(c=='\t') b.append("\\t");
            else if(c<0x20) b.append(String.format("\\u%04x",(int)c));
            else b.append(c);
        }
        return b.toString();
    }
}
