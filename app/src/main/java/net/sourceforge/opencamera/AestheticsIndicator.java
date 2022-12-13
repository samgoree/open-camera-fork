package net.sourceforge.opencamera;

import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import net.sourceforge.opencamera.ui.AestheticsIndicatorView;

public class AestheticsIndicator implements SurfaceHolder.Callback {

    private AestheticsIndicatorView aestheticsIndicatorView;
    private MainActivity mainActivity;

    private boolean has_surface;

    public AestheticsIndicator(AestheticsApplicationInterface aai, MainActivity mainActivity){
        this.mainActivity = mainActivity;
        this.aestheticsIndicatorView = mainActivity.findViewById(R.id.aesthetics_indicator_view);
        this.aestheticsIndicatorView.getHolder().addCallback(this);
        this.aestheticsIndicatorView.setWillNotDraw(false);
    }

    public AestheticsIndicatorView getSurface(){
        return this.aestheticsIndicatorView;
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
