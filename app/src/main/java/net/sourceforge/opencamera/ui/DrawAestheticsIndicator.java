package net.sourceforge.opencamera.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.SurfaceHolder;

import net.sourceforge.opencamera.AestheticsApplicationInterface;
import net.sourceforge.opencamera.AestheticsIndicator;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyApplicationInterface;

public class DrawAestheticsIndicator {
    MainActivity mainActivity;
    AestheticsApplicationInterface applicationInterface;
    AestheticsIndicator aestheticsIndicator;
    public DrawAestheticsIndicator(MainActivity main_activity, AestheticsApplicationInterface application_interface, AestheticsIndicator ai){
        this.mainActivity = main_activity;
        this.applicationInterface = application_interface;
        this.aestheticsIndicator = ai;
    }

    public void onDraw(Canvas canvas, double score) {
        Paint p = new Paint();
        p.setARGB(255,0,0,255);
        canvas.drawCircle(125,125,(float)(100), p);
    }

    public void draw(double score){
        SurfaceHolder holder = this.applicationInterface.getAestheticsIndicatorView().getHolder();
        Canvas c = holder.lockCanvas();
        this.onDraw(c, score);
        holder.unlockCanvasAndPost(c);
    }

}
