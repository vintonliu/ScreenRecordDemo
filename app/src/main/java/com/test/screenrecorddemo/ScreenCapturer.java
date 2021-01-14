package com.test.screenrecorddemo;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.test.mp.ContextUtils;
import com.test.mp.EglBase;
import com.test.mp.Logging;
import com.test.mp.ScreenCapturerAndroid;
import com.test.mp.SurfaceTextureHelper;
import com.test.mp.VideoCapturer;
import com.test.mp.VideoFrame;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScreenCapturer implements com.test.mp.CapturerObserver {
    private static final String TAG = "ScreenCapturer";

    private static final ScreenCapturer ourInstance = new ScreenCapturer();

    // Executor thread is started once in private ctor and is used for all
    // peer connection API calls to ensure new peer connection factory is
    // created on the same thread as previously destroyed factory.
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Intent mediaProjectionPermissionResultData;
    private MediaProjection.Callback mediaProjectionCallback;
    private VideoCapturer videoCapturer = null;
    private SurfaceTextureHelper surfaceTextureHelper = null;
    private EglBase rootEglBase = null;
    private int captureWidth = 720;
    private int captureHeight = 1280;
    private int framerate = 10;
    private boolean isSaveFile = true;
    private FileOutputStream outputStream = null;
    private boolean isInitialized = false;

    public static ScreenCapturer instance() {
        return ourInstance;
    }

    private ScreenCapturer() {

    }

    private boolean checkInitialize() {
        return isInitialized;
    }

    public boolean initialize(Context applicationContext,
                           Intent mediaProjectionPermissionResultData,
                           MediaProjection.Callback mediaProjectionCallback) {
        Log.i(TAG, "ScreenCapturer.initialize().");
        if (checkInitialize()) {
            Log.i(TAG, "ScreenCapturer.inialize() already initialize.");
            return true;
        }

        if (mediaProjectionPermissionResultData == null) {
            return false;
        }

        ContextUtils.initialize(applicationContext);

        this.mediaProjectionPermissionResultData = mediaProjectionPermissionResultData;
        this.mediaProjectionCallback = mediaProjectionCallback;
        this.videoCapturer = createScreenCapturer();

        if (videoCapturer == null) {
            return false;
        }

        isInitialized = true;

        executor.execute(()-> {
            rootEglBase = EglBase.create();
            surfaceTextureHelper =
                    SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
            videoCapturer.initialize(surfaceTextureHelper, applicationContext, this);

            if (isSaveFile) {
                File file = new File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath(),
                        "screen_record.yuv");
                try {
                    outputStream = new FileOutputStream(file);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
        });

        return true;
    }

    public boolean startCapture(int width, int height) {
        Log.i(TAG, "ScreenCapturer.startCapture() width: " + width + " height: " + height);
        if (!checkInitialize()) {
            Log.i(TAG, "ScreenCapturer.startCapture() not initialized.");
            return false;
        }

        if (videoCapturer == null) {
            return false;
        }

        captureWidth = width;
        captureHeight = height;
        executor.execute(() -> {
            videoCapturer.startCapture(captureWidth, captureHeight, framerate);
        });

        return true;
    }

    public boolean stopCapture() {
        Log.i(TAG, "ScreenCapturer.stopCapture().");
        if (!checkInitialize()) {
            Log.i(TAG, "ScreenCapturer.stopCapture() not initialized.");
            return false;
        }

        if (videoCapturer == null) {
            return false;
        }

        executor.execute(() -> {
            try {
                outputStream.close();
                outputStream = null;
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException | RuntimeException e) {
                Logging.e(TAG, e.toString());
            }
        });


        return true;
    }

    public void dispose() {
        Log.i(TAG, "ScreenCapturer.dispose().");
        if (!checkInitialize()) {
            Log.i(TAG, "ScreenCapturer.dispose() not initialized.");
            return;
        }

        isInitialized = false;
        executor.execute(() -> {
            if (videoCapturer != null) {
                videoCapturer.dispose();
                videoCapturer = null;
            }

            if (surfaceTextureHelper != null) {
                surfaceTextureHelper.dispose();
                surfaceTextureHelper = null;
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private @Nullable VideoCapturer createScreenCapturer() {
        if (mediaProjectionPermissionResultData == null) {
            return null;
        }

        return new ScreenCapturerAndroid(mediaProjectionPermissionResultData, mediaProjectionCallback);
    }

    private DisplayMetrics getDisplayMetrics() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) ContextUtils.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);

        return displayMetrics;
    }

    /**
     * Notify if the capturer have been started successfully or not.
     *
     * @param success
     */
    @Override
    public void onCapturerStarted(boolean success) {
        Log.i(TAG, "ScreenCapturer.onCapturerStarted().");
    }

    /**
     * Notify that the capturer has been stopped.
     */
    @Override
    public void onCapturerStopped() {
        Log.i(TAG, "ScreenCapturer.onCapturerStopped().");
    }

    /**
     * Delivers a captured frame.
     *
     * @param frame
     */
    @Override
    public void onFrameCaptured(VideoFrame frame) {
        Log.i(TAG, "ScreenCapturer.onFrameCaptured().");
        if (!checkInitialize()) {
            return;
        }

        executor.execute(() -> {
            VideoFrame.I420Buffer buffer = frame.getBuffer().toI420();

            // We draw into a buffer laid out like
            //
            //    +---------+
            //    |         |
            //    |  Y      |
            //    |         |
            //    |         |
            //    +----+----+
            //    |    U    |
            //    +---------|
            //    |    V    |
            //    +----+----+
            //
            int width = buffer.getWidth();
            int height = buffer.getHeight();
            int stride = buffer.getStrideY();
            int size = width * height * 3 / 2;
            int ySize = buffer.getStrideV() * buffer.getHeight();
            byte[] data = new byte[size];
            int uvStride = buffer.getStrideU() / 2;
            int uvHeight = buffer.getHeight() / 2;
            int uvSize = uvStride * uvHeight;

            // Read Y
            buffer.getDataY().get(data, buffer.getDataY().position(), buffer.getDataY().limit());

            // Read U
            for (int i = 0; i < uvHeight; i++) { // row
                for (int j = 0; j < uvStride; j++) { // column
                    byte u = buffer.getDataU().get(i * width + j);
                    data[ySize + i * uvStride + j] = u;
                }
            }

            // Read V
            for (int i = 0; i < uvHeight; i++) { // row
                for (int j = 0; j < uvStride; j++) { // column
                    byte v = buffer.getDataV().get(i * width + j);
                    data[ySize + uvSize + i * uvStride + j] = v;
                }
            }

            if (isSaveFile && outputStream != null) {
                try {
                    // Write to file
                    outputStream.write(data);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (surfaceTextureHelper != null) {
                surfaceTextureHelper.returnTextureFrame();
            }
        });
    }
}
