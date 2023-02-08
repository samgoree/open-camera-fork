package net.sourceforge.opencamera;

import android.content.SharedPreferences;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.view.ViewGroup;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;
import org.pytorch.MemoryFormat;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.cameracontroller.CameraControllerException;
import net.sourceforge.opencamera.cameracontroller.RawImage;
import net.sourceforge.opencamera.ui.AestheticsGraphView;
import net.sourceforge.opencamera.ui.AestheticsIndicatorView;
import net.sourceforge.opencamera.ui.DrawPreview;
import net.sourceforge.opencamera.ui.DrawAestheticsIndicator;

public class AestheticsApplicationInterface extends MyApplicationInterface{

    private static final String TAG = "AestheticsAppInterface";


    public float threshold;
    public boolean show_message = false;
    public String message_text = "";
    private HashMap<String,String> model_to_name;
    public float aesthetics_score = 0;
    private boolean safe_to_take_photo;
    public static long delayInMS = 1000;
    public static int rollingAverageLength = 10;
    public static float thresholdRatio = 1.1f;
    private float[] previous_scores;
    private int previous_scores_position;

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
    private AestheticsGraph aestheticsGraph;
    private DrawAestheticsIndicator drawAestheticsIndicator;


    public AestheticsApplicationInterface(MainActivity main_activity, Bundle savedInstanceState) throws IOException {
        super(main_activity, savedInstanceState);
        this.main_activity = main_activity;

        this.drawPreview = new DrawPreview(main_activity, this);

        this.aestheticsIndicator = new AestheticsIndicator( this, this.main_activity);
        this.aestheticsGraph = new AestheticsGraph(this, this.main_activity);

        this.drawAestheticsIndicator = new DrawAestheticsIndicator(main_activity, this, this.aestheticsIndicator, this.aestheticsGraph);
        this.safe_to_take_photo = true;
        this.classify_thread = null;
        this.paused = false;
        this.pauseLock = new Object();
        this.takePhotoLock = new Object();

        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        this.model_to_name = new HashMap<String, String>();

        String[] model_files = main_activity.getResources().getStringArray(R.array.aesthetics_model_files);
        String[] model_names = main_activity.getResources().getStringArray(R.array.aesthetics_model_names);
        for(int i = 0; i < model_files.length; i++) {
            this.model_to_name.put(model_files[i], model_names[i]);
        }

        this.setModel(sharedPreferences.getString(PreferenceKeys.AestheticsModelKey, "blur.pt"));

        this.initialize_scores();
    }

    private void initialize_scores(){
        this.previous_scores = new float[rollingAverageLength * 2];
        for(int i=0;i<rollingAverageLength * 2;i++){ this.previous_scores[i] = 10000f; };
        previous_scores_position = 10;
        this.threshold = 10000f * thresholdRatio;
    }

    public String getSaveText(){
        //get model name
        String modelName = model_to_name.get(sharedPreferences.getString(PreferenceKeys.AestheticsModelKey, "blur.pt"));
        modelName = modelName.replace(' ', '_');
        //get settings
        boolean indicator = sharedPreferences.getBoolean(PreferenceKeys.AestheticsIndicatorKey, false);
        boolean capture = sharedPreferences.getBoolean(PreferenceKeys.AestheticsModeKey, false);
        String mode = "noindicator_normal";
        if(indicator && capture){
            mode = "indicator_capture";
        } else if(indicator){
            mode = "indicator_normal";
        } else if(capture){
            mode = "noindicator_capture";
        } else{
            return mode;
        }
        return mode + "_" + modelName;
    }

    private float classify(byte[] data){

        Bitmap resizedBitmap = decode_small_bitmap(data);
        final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                resizedBitmap,
                new float[]{0.0f, 0.0f, 0.0f},
                new float[]{1.0f, 1.0f, 1.0f},
                MemoryFormat.CHANNELS_LAST);
        
        final Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();

        // getting tensor content as java array of floats
        final float[] scores = outputTensor.getDataAsFloatArray();

        return scores[0];
    }

    private float classify_lu(byte[] data){

        Bitmap bitmap_g = decode_small_bitmap(data);
        Bitmap bitmap_l = decode_cropped_bitmap(data);
        final Tensor inputTensor_g = TensorImageUtils.bitmapToFloat32Tensor(
                bitmap_g,
                new float[]{0.42858347f, 0.38953418f, 0.34951788f},
                new float[]{0.19035769f, 0.18192622f, 0.19754064f},
                MemoryFormat.CHANNELS_LAST);
        final Tensor inputTensor_l = TensorImageUtils.bitmapToFloat32Tensor(
                bitmap_l,
                new float[]{0.42858347f, 0.38953418f, 0.34951788f},
                new float[]{0.19035769f, 0.18192622f, 0.19754064f},
                MemoryFormat.CHANNELS_LAST);

        final Tensor outputTensor = module.forward(IValue.from(inputTensor_l), IValue.from(inputTensor_g)).toTensor();
        final float[] scores = outputTensor.getDataAsFloatArray();

        return (float)(Math.exp(scores[1]) / (Math.exp(scores[0]) + Math.exp(scores[1])));
    }

    private float classify_sheng(byte[] data){
        Bitmap bitmap = decode_cropped_bitmap(data);

        //change RGB to BGR
        Paint paint = new Paint();
        ColorMatrixColorFilter cmcf = new ColorMatrixColorFilter(
                new float[]{0, 0, 1, 0, 0,
                            0, 1, 0, 0, 0,
                            1, 0, 0, 0, 0,
                            0, 0, 0, 1, 0}
        );
        paint.setColorFilter(cmcf);
        Canvas drawable = new Canvas(bitmap);
        drawable.drawBitmap(bitmap,0,0, paint);

        final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                bitmap,
                new float[]{0.406f, 0.456f, 0.485f},
                new float[]{0.225f, 0.224f, 0.229f},
                MemoryFormat.CHANNELS_LAST);

        final Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();
        final float[] scores = outputTensor.getDataAsFloatArray();

        return (float)(Math.exp(scores[1]) / (Math.exp(scores[0]) + Math.exp(scores[1])));
    }

    private float classify_resnet(byte[] data){
        Bitmap bitmap = decode_cropped_bitmap(data);

        //change RGB to BGR
        Paint paint = new Paint();
        ColorMatrixColorFilter cmcf = new ColorMatrixColorFilter(
                new float[]{0, 0, 1, 0, 0,
                        0, 1, 0, 0, 0,
                        1, 0, 0, 0, 0,
                        0, 0, 0, 1, 0}
        );
        paint.setColorFilter(cmcf);
        Canvas drawable = new Canvas(bitmap);
        drawable.drawBitmap(bitmap,0,0, paint);

        final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                bitmap,
                new float[]{0.406f, 0.456f, 0.485f},
                new float[]{0.225f, 0.224f, 0.229f},
                MemoryFormat.CHANNELS_LAST);

        final Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();
        final float[] scores = outputTensor.getDataAsFloatArray();

        return (float)(Math.exp(scores[1]) / (Math.exp(scores[0]) + Math.exp(scores[1])));
    }

    private Bitmap decode_cropped_bitmap(byte[] data){
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

        BitmapFactory.Options opt = new BitmapFactory.Options();
        int targetHeight, targetWidth;
        if(height < width) {
            targetHeight = 256;
            targetWidth = width * 256 / height;
            if(height > 1024) opt.inSampleSize = 8;
            else opt.inSampleSize = 4;
        }else{
            targetWidth = 256;
            targetHeight = height * 256 / width;
            if(width > 1024) opt.inSampleSize = 8;
            else opt.inSampleSize = 4;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
        bitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
        height = bitmap.getHeight();
        width = bitmap.getWidth();
        int left = Math.max(0, width / 2 - (224) / 2);
        //int right = Math.min(width, width / 2 + (opt.outWidth * opt.inSampleSize) / 2);
        int top = Math.max(0, height / 2 - (224) / 2);
        //int bottom = Math.min(height, height / 2 + (opt.outHeight * opt.inSampleSize) / 2);
        bitmap = Bitmap.createBitmap(bitmap, left, top, 224, 224);
        // crop to 224 by 224
        //rotate
        Matrix rotation = new Matrix();
        int angle = 0;
        if(main_activity.getDisplayRotation() == Surface.ROTATION_0) angle += 90;
        else if (main_activity.getDisplayRotation() == Surface.ROTATION_180) angle += 270;
        else if (main_activity.getDisplayRotation() == Surface.ROTATION_270) angle += 180;
        rotation.postRotate(angle);
        bitmap = Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(), bitmap.getHeight(),rotation,false);
        return bitmap;
    }

    private Bitmap decode_small_bitmap(byte[] data){
        // decode at low resolution to save time
        BitmapFactory.Options opt = new BitmapFactory.Options();
        //opt.outHeight = 224;
        //opt.outWidth = 224;
        opt.inSampleSize = 4;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opt);

        // center crop to get it square and rotate
        Bitmap croppedBitmap;
        Matrix rotation = new Matrix();
        rotation.postRotate(90);
        if(bitmap.getHeight() > bitmap.getWidth()) {
            croppedBitmap = Bitmap.createBitmap(bitmap,
                    0,
                    (int) ((bitmap.getHeight() / 2) - (bitmap.getWidth() / 2)),
                    bitmap.getWidth(),
                    bitmap.getWidth(),
                    rotation,
                    false);
        }else{
            croppedBitmap = Bitmap.createBitmap(bitmap,
                    (int) ((bitmap.getWidth() / 2) - (bitmap.getHeight() / 2)),
                    0,
                    bitmap.getHeight(),
                    bitmap.getHeight(),
                    rotation,
                    false);
        }
        // downsample to 224
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(
                croppedBitmap,
                224,
                224,
                true
        );
        return resizedBitmap;
    }

    public void start_take_photo_and_classify(){
        show_message = true;
        if(message_text == null) message_text = "";
        if(this.classify_thread != null && this.classify_thread.isAlive()){
            resume_take_photo_and_classify();
            return;
        }
        this.classify_thread = this.take_photo_and_classify_async(delayInMS);
        synchronized(takePhotoLock){
            safe_to_take_photo = true;
        }
        synchronized(pauseLock) {
            this.paused = false;
            pauseLock.notifyAll();
        }

    }

    public void stop_take_photo_and_classify(){
        show_message = false;
        if(this.classify_thread != null) this.classify_thread.interrupt();
        synchronized(pauseLock) {
            this.paused = true;
        }
    }

    public void pause_take_photo_and_classify(){
        show_message = false;
        synchronized(pauseLock){
            this.paused = true;
        }
    }
    public void resume_take_photo_and_classify() {
        show_message = true;
        if(this.classify_thread == null || !this.classify_thread.isAlive()){
            start_take_photo_and_classify();
        }
        synchronized(takePhotoLock){
            safe_to_take_photo = true;
        }
        synchronized (pauseLock) {
            this.paused = false;
            pauseLock.notifyAll();
        }
    }

    private Thread take_photo_and_classify_async(long delayInMS){
        Thread thread = new Thread(new Runnable () {
            @Override
            public void run() {

                while (true) {
                    if(main_activity.getPreview() != null) {
                        CameraController camera = main_activity.getPreview().getCameraController();
                        if (camera != null) {
                            camera.enableShutterSound(false);
                            CameraController.PictureCallback jpeg = new CameraController.PictureCallback() {
                                public void onPictureTaken(byte[] data) {

                                    float value = 0;
                                    String model_file_name = sharedPreferences.getString(PreferenceKeys.AestheticsModelKey, "blur.pt");
                                    if (model_file_name.equals("deep.pt")) {

                                        value = classify_lu(data);
                                    } else if (model_file_name.equals("mpada.pt")) {
                                        value = classify_sheng(data);
                                    } else if (model_file_name.equals("resnet.pt")) {
                                        value = classify_resnet(data);
                                    }else if (model_file_name.equals("blur.pt")) {
                                        value = classify(data);
                                        // subtract off mean, divide by 1/4 the std, add 0.5
                                        value = 0.5f + (value - 0.0245681f) / (0.0405277f * 4) ;
                                    } else{ // classical
                                        value = classify(data);
                                        // subtract off mean, divide by 1/4 the std, add 0.5
                                        value = 0.5f + (value - 0.7179374f) / (0.0306888f * 4) ;
                                    }

                                    //show_message = true;
                                    //message_text = "Quality: " + Double.toString((double) Math.round(value * 10000d) / 10000d) + "Threshold: " + Double.toString( (double) Math.round(threshold * 10000d) / 10000d);

                                    // if we have a good photo and we're in aesthetics capture mode
                                    if (value > threshold && sharedPreferences.getBoolean(PreferenceKeys.AestheticsModeKey, false)) {
                                        List<byte[]> images = new ArrayList<>();
                                        images.add(data);
                                        saveImage(false, images, new Date());
                                    }
                                    // the value we subtract off isn't necessarily the same as the new value
                                    // it will be rollingAverageLength behind where we currently are in the list
                                    float subtractedValue = previous_scores[(previous_scores_position - rollingAverageLength + previous_scores.length) % previous_scores.length];
                                    threshold -= subtractedValue * thresholdRatio / rollingAverageLength;
                                    threshold += value * thresholdRatio / rollingAverageLength;
                                    previous_scores[previous_scores_position] = value;
                                    drawAestheticsIndicator.draw(previous_scores, previous_scores_position);
                                    previous_scores_position = (previous_scores_position + 1) % previous_scores.length;
                                    if (MyDebug.LOG)
                                        Log.d(TAG, "Value:" + Float.toString(value) + " threshold:" + Float.toString(threshold));

                                    List<byte []> images = new ArrayList<>();
                                    images.add(data);
                                    saveImageSecondary(true, images, new Date());
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
                                    synchronized (takePhotoLock) {
                                        safe_to_take_photo = true;
                                        takePhotoLock.notifyAll();
                                    }
                                    if (MyDebug.LOG)
                                        Log.d(TAG, "aesthetetics application interface onFrontScreenTurnOn");
                                }
                            };
                            CameraController.ErrorCallback err = new CameraController.ErrorCallback() {
                                public void onError() {
                                    synchronized (takePhotoLock) {
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
                                    continue;
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
                                if(MyDebug.LOG) Log.d(TAG, "take_photo_and_classify pause interruped");
                                return;
                            }
                        }
                        if(MyDebug.LOG) Log.d(TAG, "pause lock finished");
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
            //bmp = decode_small_bitmap(images.get(i));
            //inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
            //        bmp,
            //        new float[] {0.0f, 0.0f, 0.0f},
            //        new float[] {1.0f, 1.0f, 1.0f},
            //        MemoryFormat.CHANNELS_LAST);
            float value = classify(images.get(i));
            if (value > max_quality){
                max_quality = value;
                max_quality_ind = i;
            }
        }
        //show_message = true;
        //message_text = "Saving image: " + Integer.toString(max_quality_ind + 1);
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

    public void setModel(String newModelPath){
        boolean resume;
        if(classify_thread != null && classify_thread.isAlive()) {
            pause_take_photo_and_classify();
            resume = true;
        } else resume = false;
        try {
            this.module = LiteModuleLoader.load(assetFilePath(this.main_activity, newModelPath));
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.initialize_scores();
        if(resume) {
            resume_take_photo_and_classify();
        }

        message_text = (String)model_to_name.get(newModelPath);
    }

    public AestheticsIndicatorView getAestheticsIndicatorView(){ return this.aestheticsIndicator.getSurface();}
    public AestheticsGraphView getAestheticsGraphView(){ return this.aestheticsGraph.getSurface();}
}
