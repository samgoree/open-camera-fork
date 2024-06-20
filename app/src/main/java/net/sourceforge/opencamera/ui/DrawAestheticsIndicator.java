package net.sourceforge.opencamera.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.SurfaceHolder;
import android.view.View;

import net.sourceforge.opencamera.AestheticsApplicationInterface;
import net.sourceforge.opencamera.AestheticsGraph;
import net.sourceforge.opencamera.AestheticsIndicator;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyApplicationInterface;

import java.util.Queue;

public class DrawAestheticsIndicator {
    MainActivity mainActivity;
    AestheticsApplicationInterface applicationInterface;
    AestheticsIndicator aestheticsIndicator;
    AestheticsGraph aestheticsGraph;

    private static float graph_x_min = 0;
    private float graph_x_max;
    private static float graph_y_min = 0;
    private static float graph_y_max = 1;

    private static int center_color = Color.argb(255,123,134,142);
    private static int edge_color = Color.argb(255,38,169,108);


    public DrawAestheticsIndicator(MainActivity main_activity,
                                   AestheticsApplicationInterface application_interface,
                                   AestheticsIndicator ai,
                                   AestheticsGraph ag){
        this.mainActivity = main_activity;
        this.applicationInterface = application_interface;
        this.aestheticsIndicator = ai;
        this.aestheticsGraph = ag;

    }

    public void drawTrapezoid(Canvas canvas, Paint paint, float x1, float y1, float x2, float y_2left, float y_2right){
        Path path = new Path();
        path.moveTo(x1,y1);
        path.lineTo(x1,y_2left);
        path.lineTo(x2,y_2right);
        path.lineTo(x2,y1);
        path.lineTo(x1,y1);
        canvas.drawPath(path, paint);
    }

    public void drawIndicator(Canvas canvas, float score) {
        canvas.drawARGB(255,0,0,0);

        /*Paint p = new Paint();
        float area = (score * 100);
        int circles = (int)(Math.sqrt(area) * 10);
        for(int i = circles; i > 0; i-= 1) {
            float fraction = ((float)i / 100);
            p.setARGB(255, (int) (-fraction * 128 + 128), 0, (int) (fraction * 128 + 128));
            canvas.drawCircle(125, 125, i, p);
        }*/
    }

    private float valueToYCoordinate(float value, Canvas canvas){
        int size = canvas.getHeight();
        if(value > graph_y_max) value = graph_y_max;
        if(value < graph_y_min) value = graph_y_min;
        // y is inverted, so we subtract the value from y_max to measure how far down we should be
        return size * (graph_y_max - value) / (graph_y_max - graph_y_min);
    }

    private float valueToXCoordinate(float value, Canvas canvas){
        int size = canvas.getWidth();
        if(value > graph_x_max) value = graph_x_max;
        if(value < graph_x_min) value = graph_x_min;
        return size * (value - graph_x_min) / (graph_x_max - graph_x_min);
    }

    public void drawGraph(Canvas canvas, float[] score, int startPosition, boolean drawLine, float lineHeight){
        Paint p = new Paint();
        canvas.drawARGB(255,0,0,0);


        p.setShader(new LinearGradient(0, 0, 0, canvas.getHeight(), edge_color, center_color, Shader.TileMode.CLAMP));

        this.graph_x_max = score.length;
        int currentPosition = startPosition;
        int nextPosition = (startPosition + 1) % score.length;

        for(int i = 0; i < score.length-1; i++) {
            currentPosition = nextPosition;
            nextPosition = (nextPosition + 1) % score.length;
            if(score[currentPosition] == 10000) continue;
            drawTrapezoid(canvas, p,
                    valueToXCoordinate((float)i, canvas),
                    canvas.getHeight(),
                    valueToXCoordinate((float)i+1, canvas),
                    valueToYCoordinate((float) Math.pow(score[currentPosition],2), canvas),
                    valueToYCoordinate((float) Math.pow(score[nextPosition],2), canvas));
        }
        if(drawLine) {
            p.setColor(Color.argb(255, 100, 120, 200));
            p.setShader(null);
            canvas.drawLine(0, valueToYCoordinate(lineHeight, canvas), valueToXCoordinate((float) score.length, canvas), valueToYCoordinate(lineHeight, canvas), p);
        }
    }

    public void draw(float[] scores1, float[] scores2, int newestScorePosition1, int newestScorePosition2){
        SurfaceHolder holder;
        Canvas c;
        boolean drawLine = false;
        if(this.applicationInterface.getAestheticsIndicatorView().getVisibility() == View.VISIBLE) {
            holder = this.applicationInterface.getAestheticsIndicatorView().getHolder();
            c = holder.lockCanvas();
            this.drawIndicator(c, scores[newestScorePosition]);
            holder.unlockCanvasAndPost(c);
            drawLine = true;
        }
        if(this.applicationInterface.getAestheticsGraphView().getVisibility() == View.VISIBLE) {
            holder = this.applicationInterface.getAestheticsGraphView().getHolder();
            c = holder.lockCanvas();
            this.drawGraph(c, scores, newestScorePosition, drawLine, applicationInterface.threshold);
            holder.unlockCanvasAndPost(c);
        }
        if(this.applicationInterface.getAestheticsIndicatorView2().getVisibility() == View.VISIBLE) {
            holder = this.applicationInterface.getAestheticsIndicatorView2().getHolder();
            c = holder.lockCanvas();
            this.drawIndicator(c, scores[newestScorePosition]);
            holder.unlockCanvasAndPost(c);
            drawLine = true;
        }
        if(this.applicationInterface.getAestheticsGraphView2().getVisibility() == View.VISIBLE) {
            holder = this.applicationInterface.getAestheticsGraphView2().getHolder();
            c = holder.lockCanvas();
            this.drawGraph(c, scores, newestScorePosition, drawLine, applicationInterface.threshold2);
            holder.unlockCanvasAndPost(c);
        }
    }




}
