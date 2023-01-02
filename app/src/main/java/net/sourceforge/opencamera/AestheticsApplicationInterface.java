package net.sourceforge.opencamera;

import android.os.Bundle;
import android.util.Log;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.ViewGroup;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;
import org.pytorch.MemoryFormat;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.cameracontroller.CameraControllerException;
import net.sourceforge.opencamera.cameracontroller.RawImage;
import net.sourceforge.opencamera.ui.AestheticsIndicatorView;
import net.sourceforge.opencamera.ui.DrawPreview;
import net.sourceforge.opencamera.ui.DrawAestheticsIndicator;

public class AestheticsApplicationInterface extends MyApplicationInterface{

    private static final String TAG = "AestheticsAppInterface";

    public boolean show_imagenet = false;
    public String imagenet_text = "";
    public float aesthetics_score = 0;
    private boolean safe_to_take_photo;
    public static long delayInMS = 5000;

    private DrawPreview drawPreview;

    private int n_capture_images = 0; // how many calls to onPictureTaken() since the last call to onCaptureStarted()

    private Bitmap bitmap = null;
    private Module module = null;
    private MainActivity main_activity = null;
    private Thread classify_thread;
    private boolean paused;
    private Object pauseLock;
    private Object takePhotoLock;


    private AestheticsIndicator aestheticsIndicator;
    private DrawAestheticsIndicator drawAestheticsIndicator;

    public AestheticsApplicationInterface(MainActivity main_activity, Bundle savedInstanceState) throws IOException {
        super(main_activity, savedInstanceState);
        this.main_activity = main_activity;
        this.module = LiteModuleLoader.load(assetFilePath(main_activity, "test.pt"));
        this.drawPreview = new DrawPreview(main_activity, this);

        ViewGroup takePhotoOrAesthetics = main_activity.findViewById(R.id.take_photo_or_aesthetics);

        this.aestheticsIndicator = new AestheticsIndicator( this, this.main_activity);

        this.drawAestheticsIndicator = new DrawAestheticsIndicator(main_activity, this, this.aestheticsIndicator);
        this.safe_to_take_photo = true;
        this.classify_thread = null;
        this.paused = false;
        this.pauseLock = new Object();
        this.takePhotoLock = new Object();
    }

    public DrawAestheticsIndicator getDrawAestheticsIndicator(){
        return this.drawAestheticsIndicator;
    }

    private float[] classify(Tensor inputTensor){
        
        final Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();

        // getting tensor content as java array of floats
        final float[] scores = outputTensor.getDataAsFloatArray();

        return scores;
    }

    public void start_take_photo_and_classify(){
        this.classify_thread = this.take_photo_and_classify_async(delayInMS);
        synchronized(pauseLock) {
            this.paused = false;
        }
    }

    public void pause_take_photo_and_classify(){
        synchronized(pauseLock){
            this.paused = true;
        }
    }
    public void resume_take_photo_and_classify(){
        synchronized (pauseLock){
            this.paused = false;
            pauseLock.notifyAll();
        }
    }

    private Thread take_photo_and_classify_async(long delayInMS){
        Thread thread = new Thread(new Runnable () {
            @Override
            public void run() {

                while (true) {
                    CameraController camera = main_activity.getPreview().getCameraController();
                    if (camera != null) {
                        camera.enableShutterSound(false);
                        CameraController.PictureCallback jpeg = new CameraController.PictureCallback() {
                            public void onPictureTaken(byte[] data) {
                                try {
                                    camera.startPreview();
                                } catch (CameraControllerException e) {
                                    if (MyDebug.LOG)
                                        Log.e(TAG, "error from aesthetics application interface start preview");
                                    e.printStackTrace();
                                }
                                // decode at low resolution to save time
                                BitmapFactory.Options opt = new BitmapFactory.Options();
                                opt.outHeight = 224;
                                opt.outWidth = 224;
                                opt.inSampleSize = 4;
                                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opt);

                                // center crop to get it square
                                Bitmap croppedBitmap;
                                if(bitmap.getHeight() > bitmap.getWidth()) {
                                    croppedBitmap = Bitmap.createBitmap(bitmap,
                                            0,
                                            (int) ((bitmap.getHeight() / 2) - (bitmap.getWidth() / 2)),
                                            bitmap.getWidth(),
                                            bitmap.getWidth());
                                }else{
                                    croppedBitmap = Bitmap.createBitmap(bitmap,
                                            (int) ((bitmap.getWidth() / 2) - (bitmap.getHeight() / 2)),
                                            0,
                                            bitmap.getHeight(),
                                            bitmap.getHeight());
                                }
                                // downsample to 224
                                Bitmap resizedBitmap = Bitmap.createScaledBitmap(
                                        croppedBitmap,
                                        224,
                                        224,
                                        false
                                );

                                final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                                        resizedBitmap,
                                        new float[] {0.0f, 0.0f, 0.0f},
                                        new float[] {1.0f, 1.0f, 1.0f},
                                        MemoryFormat.CHANNELS_LAST);
                                float value = classify(inputTensor)[0];
                                // searching for the index with maximum score
                            /*float maxScore = -Float.MAX_VALUE;
                            int maxScoreIdx = -1;
                            for (int i = 0; i < scores.length; i++) {
                                if (scores[i] > maxScore) {
                                    maxScore = scores[i];
                                    maxScoreIdx = i;
                                }
                            }
                            String className = ImageNetClasses.IMAGENET_CLASSES[maxScoreIdx];
                            show_imagenet = true;
                            imagenet_text = "Class: " + className + " Score: " + Float.toString(maxScore);
                            */
                                show_imagenet = true;
                                imagenet_text = "Quality: " + Double.toString((double) Math.round(value * 10000d) / 10000d);
                                drawAestheticsIndicator.draw(value);
                                this.onCompleted();
                            }

                            public void onStarted() {
                                if (MyDebug.LOG)
                                    Log.d(TAG, "aesthetetics application interface onStarted");
                            } // called immediately before we start capturing the picture

                            public void onCompleted() {
                                synchronized (takePhotoLock) {
                                    safe_to_take_photo = true;
                                    takePhotoLock.notifyAll();
                                }
                                if (MyDebug.LOG)
                                    Log.d(TAG, "aesthetetics application interface onCompleted");
                            }

                            public void onRawPictureTaken(RawImage raw_image) {
                                if (MyDebug.LOG)
                                    Log.d(TAG, "aesthetetics application interface onRawPictureTaken");
                            }

                            /**
                             * Only called if burst is requested.
                             */
                            public void onBurstPictureTaken(List<byte[]> images) {
                                if (MyDebug.LOG)
                                    Log.d(TAG, "aesthetetics application interface onBurstPictureTaken");
                            }

                            /**
                             * Only called if burst is requested.
                             */
                            public void onRawBurstPictureTaken(List<RawImage> raw_images) {
                                if (MyDebug.LOG)
                                    Log.d(TAG, "aesthetetics application interface onRawBurstPictureTaken");
                            }

                            /* This is called for flash_frontscreen_auto or flash_frontscreen_on mode to indicate the caller should light up the screen
                             * (for flash_frontscreen_auto it will only be called if the scene is considered dark enough to require the screen flash).
                             * The screen flash can be removed when or after onCompleted() is called.
                             */
                            /* This is called for when burst mode is BURSTTYPE_FOCUS or BURSTTYPE_CONTINUOUS, to ask whether it's safe to take
                             * n_raw extra RAW images and n_jpegs extra JPEG images, or whether to wait.
                             */
                            public boolean imageQueueWouldBlock(int n_raw, int n_jpegs) {
                                if (MyDebug.LOG)
                                    Log.d(TAG, "aesthetetics application interface imageQueueWouldBlock");
                                return false;
                            }

                            public void onFrontScreenTurnOn() {
                                synchronized(takePhotoLock) {
                                    safe_to_take_photo = true;
                                    takePhotoLock.notifyAll();
                                }
                                if (MyDebug.LOG)
                                    Log.d(TAG, "aesthetetics application interface onFrontScreenTurnOn");
                            }
                        };
                        CameraController.ErrorCallback err = new CameraController.ErrorCallback() {
                            public void onError() {
                                synchronized(takePhotoLock) {
                                    safe_to_take_photo = true;
                                    takePhotoLock.notifyAll();
                                }
                                if (MyDebug.LOG)
                                    Log.e(TAG, "error from aesthetics application interface takePicture");
                            }
                        };
                        synchronized (takePhotoLock) {

                            try {
                                while (!safe_to_take_photo) {
                                    takePhotoLock.wait();
                                }
                            } catch (InterruptedException e) {
                            }
                            safe_to_take_photo = false;
                            try {
                                camera.takePicture(jpeg, err);

                            } catch (RuntimeException e) {
                                if (MyDebug.LOG) Log.e(TAG, "runtime exception in takePicture");
                                e.printStackTrace();
                                safe_to_take_photo = true;
                                takePhotoLock.notifyAll();
                            }

                        }
                    }
                    try {
                        Thread.sleep(delayInMS);
                    } catch (InterruptedException e) {
                        continue;
                    }
                    synchronized (pauseLock) {
                        while (paused) {
                            try {
                                pauseLock.wait();
                            } catch (InterruptedException e) {
                            }
                        }
                    }

                }

            }

        });
        thread.start();
        return thread;
    }



    @Override
    public boolean onPictureTaken(byte [] data, Date current_date) {

        if( MyDebug.LOG )
            Log.d(TAG, "onPictureTaken");

        n_capture_images++;
        if( MyDebug.LOG )
            Log.d(TAG, "n_capture_images is now " + n_capture_images);

        long start_time = System.currentTimeMillis();

        Bitmap bitmap = BitmapFactory.decodeByteArray(data , 0, data.length);

        List<byte []> images = new ArrayList<>();
        images.add(data);

        boolean success = saveImage(false, images, current_date);

        if( MyDebug.LOG )
            Log.d(TAG, "onPictureTaken complete, success: " + success);
        return success;
    }

    public static String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }

    public AestheticsIndicatorView getAestheticsIndicatorView(){ return this.aestheticsIndicator.getSurface();}
}
