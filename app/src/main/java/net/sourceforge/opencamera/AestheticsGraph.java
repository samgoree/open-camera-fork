package net.sourceforge.opencamera;

import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import net.sourceforge.opencamera.ui.AestheticsGraphView;
import net.sourceforge.opencamera.ui.AestheticsIndicatorView;

public class AestheticsGraph implements SurfaceHolder.Callback{
    private AestheticsGraphView aestheticsGraphView;
    private AestheticsGraphView aestheticsGraphView2;
    private MainActivity mainActivity;

    private boolean has_surface;

    public AestheticsGraph(AestheticsApplicationInterface aai, MainActivity mainActivity){
        this.mainActivity = mainActivity;
        this.aestheticsGraphView = mainActivity.findViewById(R.id.indicator_graph);
        this.aestheticsGraphView.getHolder().addCallback(this);
        this.aestheticsGraphView.setWillNotDraw(false);

        this.aestheticsGraphView2 = mainActivity.findViewById(R.id.indicator_graph_2);
        this.aestheticsGraphView2.getHolder().addCallback(this);
        this.aestheticsGraphView2.setWillNotDraw(false);
    }

    public AestheticsGraphView getSurface(){
        return this.aestheticsGraphView;
    }
    public AestheticsGraphView getSurface2(){
        return this.aestheticsGraphView2;
    }

    public boolean hasSurface(){
        return has_surface;
    }
    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        this.has_surface = true;
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        this.has_surface = false;
    }
}
