package com.test.screenrecorddemo;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodecInfo;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ScreenRecordService extends Service {
    public interface Callback {
        void onStart();
        void onStop();
    }

    private final String TAG = getClass().getSimpleName();
    private static final int DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
    private static final int VIRTUAL_DISPLAY_DPI = 400;
    private ScreenRecordBinder screenRecordBinder = new ScreenRecordBinder();

    private int mWidth;
    private int mHeight;
    private int resultCode;
    private Intent recordIntent;

    @Nullable private MediaProjection mMediaProjection;
    @Nullable private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;

    private Callback callback;


    public ScreenRecordService() {
    }

    public class ScreenRecordBinder extends Binder {
        public ScreenRecordService getService () {
            return ScreenRecordService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind()");
        mWidth = intent.getIntExtra("width", 720);
        mHeight = intent.getIntExtra("height", 1280);
        resultCode = intent.getIntExtra("code", Activity.RESULT_OK);
        recordIntent = intent.getParcelableExtra("data");

        mMediaProjection = createMediaProjection();
        mMediaRecorder = createMediaRecorder();
        mVirtualDisplay = createVirtualDisplay();

        createNotificationChannel();
        Log.i(TAG, "onBind() width: " + mWidth + " height: " + mHeight);

        // TODO: Return the communication channel to the service.
        return screenRecordBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand()");
        mWidth = intent.getIntExtra("width", 720);
        mHeight = intent.getIntExtra("height", 1280);
        resultCode = intent.getIntExtra("code", Activity.RESULT_OK);
        recordIntent = intent.getParcelableExtra("data");

        mMediaProjection = createMediaProjection();
        mMediaRecorder = createMediaRecorder();
        mVirtualDisplay = createVirtualDisplay();

        createNotificationChannel();

        Log.i(TAG, "onStartCommand() width: " + mWidth + " height: " + mHeight);
        return Service.START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        Notification.Builder builder = new Notification.Builder(getApplicationContext());
        Intent intent = new Intent(this, MainActivity.class);
        builder.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0))
                .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.mipmap.ic_launcher))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentText(getString(R.string.screen_record_running))
                .setWhen(System.currentTimeMillis());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId("notification_id");
        }

        //前台服务notification适配
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel("notification_id", "notification_name", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        Notification notification = builder.build(); // 获取构建好的Notification
        notification.defaults = Notification.DEFAULT_SOUND; //设置为默认的声音
        startForeground(110, notification);
    }

    private MediaProjection createMediaProjection() {
        MediaProjectionManager mediaProjectionManager =
            (MediaProjectionManager)getApplication().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, recordIntent);

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (callback != null) {
                    callback.onStop();
                }
            }
        }, null);

        return mediaProjection;
    }

    private MediaRecorder createMediaRecorder() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String filename = "screen_record_" + format.format(new Date()) + ".mp4";
        String outfile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + File.separator + filename;

        MediaRecorder mediaRecorder = new MediaRecorder();
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(outfile);
        mediaRecorder.setVideoSize(mWidth, mHeight);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoEncodingBitRate(2000);
        mediaRecorder.setVideoFrameRate(10);

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return mediaRecorder;
    }

    private VirtualDisplay createVirtualDisplay() {
        return mMediaProjection.createVirtualDisplay("test-screen-record",
                mWidth, mHeight, VIRTUAL_DISPLAY_DPI,
                DISPLAY_FLAGS, mMediaRecorder.getSurface(),
                null, null);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestory()");

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        if (mMediaRecorder != null) {
            mMediaRecorder.setOnErrorListener(null);
            mMediaRecorder.stop();
            mMediaRecorder.reset();
        }

        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }
}