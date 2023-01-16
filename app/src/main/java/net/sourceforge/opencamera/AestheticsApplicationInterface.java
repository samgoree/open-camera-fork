package net.sourceforge.opencamera;

import android.content.SharedPreferences;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Bundle;
import android.preference.PreferenceManager;
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

    public boolean show_message = false;
    public String message_text = "";
    public float aesthetics_score = 0;
    private boolean safe_to_take_photo;
    public static long delayInMS = 500;

    private DrawPreview drawPreview;

    private int n_capture_images = 0; // how many calls to onPictureTaken() since the last call to onCaptureStarted()

    private Bitmap bitmap = null;
    private Module module = null;
    private MainActivity main_activity = null;
    private Thread classify_thread;
    private boolean paused;
    private Object pauseLock;
    private Object takePhotoLock;

    private SharedPreferences sharedPreferences;


    private AestheticsIndicator aestheticsIndicator;
    private DrawAestheticsIndicator drawAestheticsIndicator;

    public AestheticsApplicationInterface(MainActivity main_activity, Bundle savedInstanceState) throws IOException {
        super(main_activity, savedInstanceState);
        this.main_activity = main_activity;

        this.drawPreview = new DrawPreview(main_activity, this);

        ViewGroup takePhotoOrAesthetics = main_activity.findViewById(R.id.take_photo_or_aesthetics);

        this.aestheticsIndicator = new AestheticsIndicator( this, this.main_activity);

        this.drawAestheticsIndicator = new DrawAestheticsIndicator(main_activity, this, this.aestheticsIndicator);
        this.safe_to_take_photo = true;
        this.classify_thread = null;
        this.paused = false;
        this.pauseLock = new Object();
        this.takePhotoLock = new Object();

        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        this.setModel(sharedPreferences.getString(PreferenceKeys.AestheticsModelKey, "blur.pt"));

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

    private float[] classify_lu(Tensor inputTensorLocal, Tensor inputTensorGlobal){
        final Tensor outputTensor = module.forward(IValue.from(inputTensorLocal), IValue.from(inputTensorGlobal)).toTensor();
        final float[] scores = outputTensor.getDataAsFloatArray();

        return scores;
    }

    private Bitmap decode_cropped_bitmap(byte[] data){
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.outHeight = 224;
        opt.outWidth = 224;
        opt.inSampleSize = 2;
        BitmapRegionDecoder decoder;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                decoder = BitmapRegionDecoder.newInstance(data, 0, data.length);
            } else{
                decoder = BitmapRegionDecoder.newInstance(data, 0, data.length, false);
            }
        } catch(IOException e){
            if (MyDebug.LOG) Log.e(TAG, "runtime exception in decode_cropped_bitmap");
            e.printStackTrace();
            return null;
        }
        int height = decoder.getHeight();
        int width = decoder.getWidth();
        int left = Math.max(0, width / 2 - (opt.outWidth * opt.inSampleSize) / 2);
        int right = Math.min(width, width / 2 + (opt.outWidth * opt.inSampleSize) / 2);
        int top = Math.max(0, height / 2 - (opt.outHeight * opt.inSampleSize) / 2);
        int bottom = Math.min(height, height / 2 + (opt.outHeight * opt.inSampleSize) / 2);
        Bitmap bitmap = decoder.decodeRegion(new Rect(left,top,right,bottom), opt);
        return bitmap;
    }

    private Bitmap decode_small_bitmap(byte[] data){
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
        return resizedBitmap;
    }

    public void start_take_photo_and_classify(){
        this.classify_thread = this.take_photo_and_classify_async(delayInMS);
        synchronized(pauseLock) {
            this.paused = false;
        }
    }

    public void stop_take_photo_and_classify(){
        this.classify_thread.interrupt();
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

                                float value = 0;

                                if(sharedPreferences.getString(PreferenceKeys.AestheticsModelKey, "blur.pt").equals("deep.pt")){
                                    Bitmap bitmap_g = decode_small_bitmap(data);
                                    Bitmap bitmap_l = decode_cropped_bitmap(data);
                                    final Tensor inputTensor_g = TensorImageUtils.bitmapToFloat32Tensor(
                                            bitmap_g,
                                            new float[]{0.0f, 0.0f, 0.0f},
                                            new float[]{1.0f, 1.0f, 1.0f},
                                            MemoryFormat.CHANNELS_LAST);
                                    final Tensor inputTensor_l = TensorImageUtils.bitmapToFloat32Tensor(
                                            bitmap_l,
                                            new float[]{0.0f, 0.0f, 0.0f},
                                            new float[]{1.0f, 1.0f, 1.0f},
                                            MemoryFormat.CHANNELS_LAST);
                                    value = classify_lu(inputTensor_l, inputTensor_g)[1];
                                } else {
                                    Bitmap resizedBitmap = decode_small_bitmap(data);
                                    final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                                            resizedBitmap,
                                            new float[]{0.0f, 0.0f, 0.0f},
                                            new float[]{1.0f, 1.0f, 1.0f},
                                            MemoryFormat.CHANNELS_LAST);
                                    value = classify(inputTensor)[0];
                                }

                                show_message = true;
                                message_text = "Quality: " + Double.toString((double) Math.round(value * 10000d) / 10000d);
                                drawAestheticsIndicator.draw(value);
                                /*BitmapFactory.Options opt = new BitmapFactory.Options();
                                opt.inSampleSize = 2;
                                Bitmap thumbnail = BitmapFactory.decodeByteArray(data, 0, data.length, opt);

                                updateThumbnail(thumbnail, false);*/
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

    public boolean onBurstPictureTaken(List<byte []> images, Date current_date) {
        if( MyDebug.LOG )
            Log.d(TAG, "onBurstPictureTaken: received " + images.size() + " images");

        boolean success;

        double max_quality = -Integer.MAX_VALUE;
        int max_quality_ind = -1;
        Bitmap bmp;
        Tensor inputTensor;
        for(int i = 0; i < images.size(); i++){
            bmp = decode_small_bitmap(images.get(i));
            inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                    bmp,
                    new float[] {0.0f, 0.0f, 0.0f},
                    new float[] {1.0f, 1.0f, 1.0f},
                    MemoryFormat.CHANNELS_LAST);
            float value = classify(inputTensor)[0];
            if (value > max_quality){
                max_quality = value;
                max_quality_ind = i;
            }
        }
        show_message = true;
        message_text = "Saving image: " + Integer.toString(max_quality_ind + 1);
        List<byte []> save_images = new ArrayList<>();
        save_images.add(images.get(max_quality_ind));

        success = saveImage(true, save_images, current_date);
        return success;
    }

    @Override
    public boolean onPictureTaken(byte [] data, Date current_date) {

        if( MyDebug.LOG )
            Log.d(TAG, "onPictureTaken");

        n_capture_images++;
        if( MyDebug.LOG )
            Log.d(TAG, "n_capture_images is now " + n_capture_images);

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

    public boolean isAestheticsMode(){
        return this.sharedPreferences.getBoolean(PreferenceKeys.AestheticsModeKey, false);
    }

    public int getBurstNImages(){
        if(this.isAestheticsMode()){
            return 1;
        } else{
            String n_images_value = sharedPreferences.getString(PreferenceKeys.FastBurstNImagesPreferenceKey, "5");
            int n_images;
            try {
                n_images = Integer.parseInt(n_images_value);
            }
            catch(NumberFormatException e) {
                if( MyDebug.LOG )
                    Log.e(TAG, "failed to parse FastBurstNImagesPreferenceKey value: " + n_images_value);
                e.printStackTrace();
                n_images = 5;
            }
            return n_images;
        }
    }

    public void setModel(String newModelPath){
        pause_take_photo_and_classify();
        try {
            this.module = LiteModuleLoader.load(assetFilePath(this.main_activity, newModelPath));
        } catch (IOException e) {
            e.printStackTrace();
        }
        resume_take_photo_and_classify();
    }

    public AestheticsIndicatorView getAestheticsIndicatorView(){ return this.aestheticsIndicator.getSurface();}
}
