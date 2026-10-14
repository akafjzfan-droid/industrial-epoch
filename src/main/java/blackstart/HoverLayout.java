package blackstart;

import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.util.Align;

/** Measures and draws with the identical absolute font scale; never truncates cached readouts. */
final class HoverLayout {
    final GlyphLayout[] lines;
    final float[] baselines;
    float width,height,pad,scale;
    private HoverLayout(int count){lines=new GlyphLayout[count];baselines=new float[count];for(int i=0;i<count;i++)lines[i]=new GlyphLayout();}

    static HoverLayout fit(Font font,String[] text,int count,float wantedScale,float maxWidth,float maxHeight,float wantedPad){
        HoverLayout result=new HoverLayout(count);
        Font.FontData data=font.getData();float oldX=data.scaleX,oldY=data.scaleY;
        try{
            result.scale=Math.max(.00001f,wantedScale);
            for(int attempt=0;attempt<40;attempt++){
                result.pad=Math.min(wantedPad*Math.min(1f,result.scale/Math.max(.00001f,wantedScale)),Math.min(maxWidth,maxHeight)*.1f);
                data.setScale(result.scale);
                float textWidth=Math.max(.0001f,maxWidth-result.pad*2),y=result.pad,w=0;
                for(int i=0;i<count;i++){
                    GlyphLayout layout=result.lines[i];layout.setText(font,text[i],Color.white,textWidth,Align.left,true);
                    float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
                    for(var run:layout.runs)for(var glyph:run.glyphs){
                        if(glyph.height==0)continue;
                        float bottom=data.ascent+run.y+glyph.yoffset*data.scaleY;
                        low=Math.min(low,bottom);high=Math.max(high,bottom+glyph.height*data.scaleY);
                    }
                    if(!Float.isFinite(low)){low=0;high=data.capHeight;}
                    result.baselines[i]=y+high;
                    y+=high-low+(i+1<count?data.lineHeight*.2f:0f);w=Math.max(w,layout.width);
                }
                result.width=w+result.pad*2;result.height=y+result.pad;
                if(result.width<=maxWidth+.00001f&&result.height<=maxHeight+.00001f)break;
                float ratio=Math.min(maxWidth/result.width,maxHeight/result.height);
                result.scale*=Math.max(.1f,Math.min(.9f,ratio*.9f));
            }
            return result;
        }finally{data.setScale(oldX,oldY);}
    }

    void draw(Font font,float x,float top,boolean[] dangling,Color danglingColor){
        Font.FontData data=font.getData();float oldX=data.scaleX,oldY=data.scaleY;
        Color color=font.getColor();float r=color.r,g=color.g,b=color.b,a=color.a;boolean integer=font.usesIntegerPositions();
        try{
            data.setScale(scale);font.setUseIntegerPositions(false);
            for(int i=0;i<lines.length;i++){
                font.setColor(dangling[i]?danglingColor:Color.white);
                font.getCache().clear();font.getCache().addText(lines[i],x+pad,top-baselines[i]);
                font.getCache().setColors(dangling[i]?danglingColor:Color.white);font.getCache().draw();
            }
        }finally{data.setScale(oldX,oldY);font.setColor(r,g,b,a);font.setUseIntegerPositions(integer);}
    }
}
