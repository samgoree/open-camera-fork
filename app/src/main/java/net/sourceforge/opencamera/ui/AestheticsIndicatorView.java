package net.sourceforge.opencamera.ui;


import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.SurfaceView;
import android.view.View;

import net.sourceforge.opencamera.AestheticsApplicationInterface;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.preview.Preview;

/*
This is the view corresponding to the aesthetics indicator
We need it to draw on the canvas, but the actual drawing happens
in DrawAestheticsIndicator.java
 */

public class AestheticsIndicatorView extends SurfaceView {

    public AestheticsIndicatorView(Context context, AttributeSet attrs){

        super(context, attrs);
    }

    public void onPause(){

    }

    public void onResume(){
    }
}
