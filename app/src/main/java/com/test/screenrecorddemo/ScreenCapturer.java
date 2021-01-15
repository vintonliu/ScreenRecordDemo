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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScreenCapturer implements com.test.mp.CapturerObserver {
    private static final String TAG = "ScreenCapturer";

    interface Events {
        /**
         * Notify if the capturer have been started successfully or not.
         */
        void onCapturerStarted(boolean success);

        /**
         * Notify that the capturer has been stopped.
         */
        void onCapturerStopped();
    }

    private final Events dummyEvents = new Events() {
        @Override
        public void onCapturerStarted(boolean success) {
            Log.i(TAG, "<Dummy Events> capturer start " + (success ? "success" : "failed"));
        }

        @Override
        public void onCapturerStopped() {
            Log.i(TAG, "<Dummy Events> capturer stopped.");
        }
    };

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
    private Events events = null;
    private int captureWidth = 720;
    private int captureHeight = 1280;
    private int framerate = 10;
    private boolean isSaveFile = true;
    private FileOutputStream outputStream = null;
    private boolean isInitialized = false;
    private boolean isCaptureStarted = false;

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
                            MediaProjection.Callback mediaProjectionCallback,
                            @Nullable Events events) {
        Log.i(TAG, "ScreenCapturer.initialize().");
        if (checkInitialize()) {
            Log.i(TAG, "ScreenCapturer.inialize() already initialize.");
            return true;
        }

        if (mediaProjectionPermissionResultData == null) {
            return false;
        }

        if (events == null) {
            events = dummyEvents;
        }

        this.events = events;

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
            Log.w(TAG, "ScreenCapturer.startCapture() not initialized.");
            return false;
        }

        if (videoCapturer == null) {
            return false;
        }

        if (isCaptureStarted) {
            return true;
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
            Log.w(TAG, "ScreenCapturer.stopCapture() not initialized.");
            return false;
        }

        if (!isCaptureStarted || videoCapturer == null) {
            return false;
        }

        executor.execute(() -> {
            if (isSaveFile) {
                try {
                    outputStream.close();
                    outputStream = null;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException | RuntimeException e) {
                Logging.e(TAG, "ScreenCapturer.stopCapture() exception: ", e);
            }
        });

        return true;
    }

    public void dispose() {
        Log.i(TAG, "ScreenCapturer.dispose().");
        if (!checkInitialize()) {
            Log.w(TAG, "ScreenCapturer.dispose() not initialized.");
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
        Log.i(TAG, "ScreenCapturer.onCapturerStarted() " + (success ? "success " : "failed"));
        isCaptureStarted = success;
        events.onCapturerStarted(success);
    }

    /**
     * Notify that the capturer has been stopped.
     */
    @Override
    public void onCapturerStopped() {
        Log.i(TAG, "ScreenCapturer.onCapturerStopped().");
        isCaptureStarted = false;
        events.onCapturerStopped();
    }

    /**
     * Delivers a captured frame.
     *
     * @param frame
     */
    @Override
    public void onFrameCaptured(VideoFrame frame) {
//        Log.i(TAG, "ScreenCapturer.onFrameCaptured().");
        if (!checkInitialize()) {
            return;
        }

        executor.execute(() -> {
            long start = System.currentTimeMillis();

            VideoFrame.I420Buffer buffer = frame.getBuffer().toI420();
            Log.d(TAG, "process frame to i420 took " + (System.currentTimeMillis() - start) + "ms");

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
            int uvStride = buffer.getStrideU() / 2;
            int uvHeight = buffer.getHeight() / 2;
            int uvSize = stride * uvHeight;
            ByteArrayOutputStream os = new ByteArrayOutputStream(size);

            // Read Y
            long start1 = System.currentTimeMillis();
            os.write(buffer.getDataY().array(), buffer.getDataY().position(), buffer.getDataY().limit());
            Log.d(TAG, "read Y took " + (System.currentTimeMillis() - start1) + "ms");

            start1 = System.currentTimeMillis();
            ByteBuffer uvbuf = ByteBuffer.allocate(uvSize);
            byte[] uv = new byte[uvSize];
            byte[] u = new byte[uvSize/2];
            byte[] v = new byte[uvSize/2];

            // Read UV from direct buffer
            System.arraycopy(buffer.getDataU().array(), ySize, uv, 0, uvSize);
            uvbuf.put(uv);
            uvbuf.flip();
            for (int row = 0; row < uvHeight; row++) {
//                uvbuf.position(row * width);
//                Log.i(TAG, "U position: " + uvbuf.position() + " row: " + row);
                // Read U
                uvbuf.get(u, row * uvStride, uvStride);
                // Read V
                uvbuf.get(v, row * uvStride, uvStride);
            }
            os.write(u, 0, u.length);
            os.write(v, 0, v.length);
            Log.d(TAG, "read UV took " + (System.currentTimeMillis() - start1) + "ms");

            if (isSaveFile && outputStream != null) {
                start1 = System.currentTimeMillis();
                try {
                    // Write to file
                    outputStream.write(os.toByteArray());
                    Log.d(TAG, "write file took " + (System.currentTimeMillis() - start1) + "ms");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (surfaceTextureHelper != null) {
                surfaceTextureHelper.returnTextureFrame();
            }

            long diff = System.currentTimeMillis() - start;
            if (diff >= 50) {
                Log.i(TAG, "process frame total took too long " + diff + "ms");
            }
        });
    }
}
