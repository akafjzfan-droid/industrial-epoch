package blackstart;

import arc.Core;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Scl;
import mindustry.ui.dialogs.BaseDialog;

/** Stage pixel limits converted once to Arc's scaled cell units. */
public final class ResponsiveUi {
    private ResponsiveUi(){}
    static float units(float pixels){return pixels/Math.max(.0001f,Scl.scl(1f));}

    /** Bind once after adding the close button. Reflows on stage size or UI scale changes. */
    public static void bindDialog(BaseDialog dialog,Cell<ScrollPane> pane,float preferredWidth,float heightFraction){
        float[] prior={-1,-1,-1};
        Runnable resize=()->{
            if(Core.scene==null)return;
            float w=Core.scene.getWidth()-Core.scene.marginLeft-Core.scene.marginRight;
            float h=Core.scene.getHeight()-Core.scene.marginTop-Core.scene.marginBottom;
            float scale=Scl.scl(1f);
            if(w!=prior[0]||h!=prior[1]||scale!=prior[2]||dialog.getWidth()>w||dialog.getHeight()>h*heightFraction+.1f){
                fitDialog(dialog,pane,w,h,preferredWidth,heightFraction);
                prior[0]=w;prior[1]=h;prior[2]=scale;
            }
        };
        // Dialog.show/hide clear dialog actions; the pane's action survives those lifecycle calls.
        pane.get().addAction(new arc.scene.Action(){
            @Override public boolean act(float delta){resize.run();return false;}
        });
        dialog.shown(resize);
    }

    public static void fitDialog(BaseDialog dialog,Cell<ScrollPane> pane,float stageWidth,float stageHeight,float preferredWidth,float heightFraction){
        float w=Math.max(1,Math.min(Scl.scl(preferredWidth),stageWidth-8));
        float h=Math.max(1,stageHeight*heightFraction);
        dialog.setFillParent(false);dialog.title.setWrap(true);
        // Keep the title and close row useful even in a very small, highly scaled window.
        float chromeScale=Math.min(1f,h/Math.max(1,Scl.scl(150f)));
        dialog.title.setFontScale(chromeScale);
        dialog.titleTable.margin(0);dialog.cont.margin(0);dialog.buttons.margin(0);dialog.margin(0);
        for(var cell:dialog.getCells())cell.minWidth(0).width(units(w)).pad(0);
        for(var cell:dialog.buttons.getCells()){
            cell.minSize(0).size(units(Math.min(Scl.scl(210f),w)),units(Math.min(Scl.scl(48f),h*.22f))).pad(0);
            if(cell.get() instanceof TextButton button)button.getLabel().setFontScale(chromeScale);
        }
        pane.get().setScrollingDisabled(true,false);
        // Arc treats maxHeight=0 as unbounded, so height(0) still measures the whole body.
        // Use a positive one-pixel body while measuring title and close-row overhead.
        pane.minSize(0).width(units(w)).height(units(1f)).pad(0);
        // Cell edits do not invalidate their containing Table's cached preferred size.
        // Give the wrapped title its width before measuring the chrome.
        dialog.cont.invalidateHierarchy();dialog.titleTable.invalidateHierarchy();dialog.buttons.invalidateHierarchy();
        dialog.setSize(w,h);dialog.validate();
        // The window background's minimum height is not title/close-row overhead.
        // In a scaled small window it can exceed the whole viewport, so measure actual rows.
        float overhead=dialog.titleTable.getPrefHeight()+dialog.buttons.getPrefHeight();
        pane.height(units(Math.max(1,h-overhead)));
        dialog.cont.invalidateHierarchy();dialog.setSize(w,h);dialog.validate();dialog.centerWindow();
    }
}
