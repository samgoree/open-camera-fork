package net.sourceforge.opencamera;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.widget.TextView;

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

import org.pytorch.LiteModuleLoader;
import android.content.Context;

public class AestheticsApplicationInterface extends MyApplicationInterface{

    private static final String TAG = "AestheticsAppInterface";

    private int n_capture_images = 0; // how many calls to onPictureTaken() since the last call to onCaptureStarted()

    private Bitmap bitmap = null;
    private Module module = null;
    private MainActivity main_activity = null;

    public AestheticsApplicationInterface(MainActivity main_activity, Bundle savedInstanceState) throws IOException {
        super(main_activity, savedInstanceState);
        this.main_activity = main_activity;
        this.module = LiteModuleLoader.load(assetFilePath(main_activity, "model.pt"));
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

        final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(bitmap,
                TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB, MemoryFormat.CHANNELS_LAST);

        // running the model
        final Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();

        // getting tensor content as java array of floats
        final float[] scores = outputTensor.getDataAsFloatArray();

        // searching for the index with maximum score
        float maxScore = -Float.MAX_VALUE;
        int maxScoreIdx = -1;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > maxScore) {
                maxScore = scores[i];
                maxScoreIdx = i;
            }
        }

        String className = ImageNetClasses.IMAGENET_CLASSES[maxScoreIdx];

        List<byte []> images = new ArrayList<>();
        images.add(data);

        AlertDialog.Builder builder = new AlertDialog.Builder(this.main_activity);
        long time_diff = System.currentTimeMillis() - start_time;
        builder.setMessage("Class:" + className + "\nTime: " + Float.toString(time_diff / 1000.0f) + "s.").setTitle("imagenet");
        AlertDialog alert = builder.create();

        alert.show();

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
}
