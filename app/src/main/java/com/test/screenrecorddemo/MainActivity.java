package com.test.screenrecorddemo;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;


public class MainActivity extends AppCompatActivity {
    private final String TAG = getClass().getSimpleName();
    private static final int NORMAL_PERMISSION_REQUEST_CODE = 1000;
    private static final int CAPTURE_PERMISSION_REQUEST_CODE = 1001;

    private Button btn_op;
    private boolean isStart = false;
    // if bindService set true, save screen data to mp4 by MediaRecorder,
    // else extract screen data to yuv420 file
    private boolean bindService = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestNormalPermissions();
        btn_op = (Button)findViewById(R.id.btn_op);
    }

    private void requestNormalPermissions() {
        String[] permissions = {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, NORMAL_PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == NORMAL_PERMISSION_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "permission granted", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private DisplayMetrics getDisplayMetrics() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getApplication().getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);

        return displayMetrics;
    }

    public void onBtnOp(View view) {

        if (isStart) {
            stopScreenRecord();
            btn_op.setText(R.string.op_start);
        } else {
            startScreenRecord();
            btn_op.setText(R.string.op_stop);
        }
        isStart = !isStart;
    }

    private void startScreenRecord() {
        MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) getApplication().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                CAPTURE_PERMISSION_REQUEST_CODE);
    }

    private void stopScreenRecord() {
        if (!bindService) {
            ScreenCapturer.instance().stopCapture();
            ScreenCapturer.instance().dispose();
            Intent intent = new Intent(this, ScreenCapturerService.class);
            stopService(intent);
        } else {
            if (connection != null) {
                unbindService(connection);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != CAPTURE_PERMISSION_REQUEST_CODE) {
            return;
        }

        if (resultCode == Activity.RESULT_OK) {
            DisplayMetrics displayMetrics = getDisplayMetrics();

            if (!bindService) {
                Intent intent = new Intent(this, ScreenCapturerService.class);
                startService(intent);
                ScreenCapturer.instance().initialize(getApplicationContext(), data, new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        Log.i(TAG, "Screen Share has stopped.");
                        Toast.makeText(getApplicationContext(), R.string.share_stop, Toast.LENGTH_LONG).show();
                    }
                });
                ScreenCapturer.instance().startCapture(displayMetrics.widthPixels, displayMetrics.heightPixels);
            } else {
                Intent intent = new Intent(this, ScreenRecordService.class);
                intent.putExtra("width", displayMetrics.widthPixels);
                intent.putExtra("height", displayMetrics.heightPixels);
                intent.putExtra("code", resultCode);
                intent.putExtra("data", data);

                bindService(intent, connection, BIND_AUTO_CREATE);
            }
        } else {
            isStart = false;
        }
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ScreenRecordService.ScreenRecordBinder binder = (ScreenRecordService.ScreenRecordBinder)service;
            ScreenRecordService screenRecordService = binder.getService();
            screenRecordService.setCallback(new ScreenRecordService.Callback() {
                @Override
                public void onStart() {
                    Log.i(TAG, "Screen Record start.");
                    btn_op.setText(R.string.op_stop);
                }

                @Override
                public void onStop() {
                    Log.i(TAG, "Screen Record stop.");
                    isStart = false;
                    btn_op.setText(R.string.op_start);
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addCategory(Intent.CATEGORY_HOME);
            startActivity(intent);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume()");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG,"onDestory()");

        stopScreenRecord();
    }
}