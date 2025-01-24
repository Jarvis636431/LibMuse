package com.choosemuse.example.libmuse;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ImageView;

import com.choosemuse.libmuse.Accelerometer;
import com.choosemuse.libmuse.AnnotationData;
import com.choosemuse.libmuse.ConnectionState;
import com.choosemuse.libmuse.Eeg;
import com.choosemuse.libmuse.LibmuseVersion;
import com.choosemuse.libmuse.MessageType;
import com.choosemuse.libmuse.Muse;
import com.choosemuse.libmuse.MuseArtifactPacket;
import com.choosemuse.libmuse.MuseConfiguration;
import com.choosemuse.libmuse.MuseConnectionListener;
import com.choosemuse.libmuse.MuseConnectionPacket;
import com.choosemuse.libmuse.MuseDataListener;
import com.choosemuse.libmuse.MuseDataPacket;
import com.choosemuse.libmuse.MuseDataPacketType;
import com.choosemuse.libmuse.MuseFileFactory;
import com.choosemuse.libmuse.MuseFileReader;
import com.choosemuse.libmuse.MuseFileWriter;
import com.choosemuse.libmuse.MuseListener;
import com.choosemuse.libmuse.MuseManagerAndroid;
import com.choosemuse.libmuse.MuseVersion;
import com.choosemuse.libmuse.Result;
import com.choosemuse.libmuse.ResultLevel;
import com.intretech.eegcalculation.XmuseEEGCalculation;


import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import java.io.FileOutputStream;
import java.io.IOException;

import org.jtransforms.fft.DoubleFFT_1D;
import com.choosemuse.example.libmuse.LowPassFilter;


public class MainActivity extends Activity implements OnClickListener {

    private final String TAG = "TestLibMuseAndroid";
    private MuseManagerAndroid manager;
    private Muse muse;
    private ConnectionListener connectionListener;
    private DataListener  dataListener;
    private final double[] eegBuffer = new double[6];
    private boolean eegStale;
    private final int electrode_num = 4;//muse的电极数量
    private Handler handler;
    private ArrayAdapter<String> spinnerAdapter;
    private boolean dataTransmission = true;
    private final AtomicReference<MuseFileWriter> fileWriter = new AtomicReference<>();
    private final AtomicReference<Handler> fileHandler = new AtomicReference<>();
    private final static int REQUEST_PERMISSIONS = 0x123;

    //--------------------------------------
    // Lifecycle / Connection code

    private final List<double[]> fftDataList = new ArrayList<>();
    private int fftDataCounter = 0;
    //计算FFT 使用JTransforms库
    // 添加此方法以将经过 FFT 处理的 EEG 数据写入一个单独的 raw 文件
    private void writeFftEegDataToRaw(double[] data) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, "fft_eeg_data.raw");
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            for (double value : data) {
                fos.write(Double.toString(value).getBytes());
                fos.write(" ".getBytes());
            }
            fos.write("\n".getBytes());
        } catch (IOException e) {
            Log.e(TAG, "Error writing FFT EEG data to raw file", e);
        }
    }

    // 修改 performFFT 方法，在执行 FFT 后调用 writeFftEegDataToRaw
    private void performFFT(double[] data) {
        double[] fftData = Arrays.copyOfRange(data, 0, 4);
        DoubleFFT_1D fft = new DoubleFFT_1D(fftData.length);
        fft.realForward(fftData);
        System.out.println("FFT Result: " + Arrays.toString(fftData));
        fftDataList.add(fftData); // 将 fftData 添加到全局列表中

        fftDataCounter++;
        if (fftDataCounter >= 256 * 3) {
            writeFftEegDataToRaw(fftData); // 将经过 FFT 处理的数据写入文件
            fftDataCounter = 0; // 重置计数器
        }
    }

    private double[] filteredEeg;
    private LowPassFilter lpf;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        // 初始化 LowPassFilter 对象
        lpf = new LowPassFilter(0.1, eegBuffer.length);
        // 初始化 LowPassFilter 对象
        filteredEeg = new double[eegBuffer.length];

        //初始化 MuseManagerAndroid，用于管理 Muse 设备
        manager = MuseManagerAndroid.getInstance();
        manager.setContext(this);

        Log.i(TAG, "LibMuse version=" + LibmuseVersion.instance().getString());

        WeakReference<MainActivity> weakActivity =
                new WeakReference<>(this);
        //注册 Muse 的连接监听器和数据监听器，用于接收 Muse 设备的连接状态和数据
        connectionListener = new ConnectionListener(weakActivity);
        dataListener = new DataListener(weakActivity);
        //设置 MuseListener，用于接收 Muse 设备的连接状态
        manager.setMuseListener(new MuseL(weakActivity));

        //检查蓝牙BLE权限
        checkPermissionState();

        // 加载并初始化UI.
        initUI();
        initRawFile();

        //独立的线程，用于文件读写
        fileThread.start();

        //初始化Handler
        handler = new Handler(getMainLooper());
        handler.post(tickUi);
    }

    protected void onPause() {
        super.onPause();
        manager.stopListening();
    }

    @SuppressWarnings("unused")
    public boolean isBluetoothEnabled() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter != null) {
                return adapter.isEnabled();
            }
        }
        return false;
    }

//    点击事件的确定，包括刷新，连接，断开，暂停
    @Override
    public void onClick(View v) {

        if (v.getId() == R.id.ble_image_receive) {
            Intent bleIntent = new Intent(this, BLEImageReceiverActivity.class);
            startActivity(bleIntent);
        }

        if (v.getId() == R.id.refresh) {
            manager.stopListening();
            manager.startListening();

        } else if (v.getId() == R.id.connect) {

            manager.stopListening();

            List<Muse> availableMuses = manager.getMuses();
            Spinner musesSpinner = findViewById(R.id.muses_spinner);

            if (availableMuses.isEmpty() || musesSpinner.getAdapter().getCount() < 1) {
                Log.w(TAG, "There is nothing to connect to");
            } else {

                muse = availableMuses.get(musesSpinner.getSelectedItemPosition());
                muse.unregisterAllListeners();
                muse.registerConnectionListener(connectionListener);
                muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
                muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
                muse.registerDataListener(dataListener, MuseDataPacketType.BETA_RELATIVE);
                muse.registerDataListener(dataListener, MuseDataPacketType.THETA_RELATIVE);
                muse.registerDataListener(dataListener, MuseDataPacketType.HSI_PRECISION);
                muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
                muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
                muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
                muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);

                muse.runAsynchronously();
            }

        } else if (v.getId() == R.id.disconnect) {

            if (muse != null) {
                muse.disconnect();
            }

        } else if (v.getId() == R.id.pause) {

            if (muse != null) {
                dataTransmission = !dataTransmission;
                muse.enableDataTransmission(dataTransmission);
            }
        }
    }

//    权限检查

    private void checkPermissionState() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            permissions = new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
        }

        if (checkSelfPermission(permissions[0]) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) {
                granted = granted && result == PackageManager.PERMISSION_GRANTED;
            }
            if (!granted) {
                finish();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

//  监听Muse的连接状态
    public void museListChanged() {
        final List<Muse> list = manager.getMuses();
        spinnerAdapter.clear();
        for (Muse m : list) {
            spinnerAdapter.add(m.getName() + " - " + m.getMacAddress());
        }
    }

//    @SuppressWarnings("unused")
    public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {

        final ConnectionState current = p.getCurrentConnectionState();

        // Format a message to show the change of connection state in the UI.
        final String status = p.getPreviousConnectionState() + " -> " + current;
        Log.i(TAG, status);

        // Update the UI with the change in connection state.
        handler.post(() -> {

            final TextView statusText = findViewById(R.id.con_status);
            statusText.setText(status);

            final MuseVersion museVersion = muse.getMuseVersion();
            final TextView museVersionText = findViewById(R.id.version);
            if (museVersion != null) {
                final String version = museVersion.getFirmwareType() + " - "
                        + museVersion.getFirmwareVersion() + " - "
                        + museVersion.getProtocolVersion();
                museVersionText.setText(version);
            } else {
                museVersionText.setText(R.string.undefined);
            }
        });

        if (current == ConnectionState.DISCONNECTED) {
            Log.i(TAG, "Muse disconnected:" + muse.getName());
            saveFile();
            this.muse = null;
        }
    }


    private void writeFilteredEegDataToRaw(double[] data) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, "filtered_eeg_data.raw");
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            for (double value : data) {
                fos.write(Double.toString(value).getBytes());
                fos.write(" ".getBytes());
            }
            fos.write("\n".getBytes());
        } catch (IOException e) {
            Log.e(TAG, "Error writing filtered EEG data to raw file", e);
        }
    }

    @SuppressWarnings("unused")
    public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
        //先把所有的原始数据写入.muse,其中选出原始EEG数据写入.raw文件
        writeDataPacketToFile(p);
        //安卓端返回的数据
        // valuesSize returns the number of data values contained in the packet.
        @SuppressWarnings("unused") final long n = p.valuesSize();
        switch (p.packetType()) {
            case EEG:
                getEegChannelValues(eegBuffer,p);
//                System.out.println("EEG data: " + Arrays.toString(eegBuffer));
                eegStale = true;
                // 对 eegBuffer 进行滤波
                filteredEeg = lpf.filter(eegBuffer);
//                System.out.println("Filtered EEG data: " + Arrays.toString(filteredEeg));
                writeFilteredEegDataToRaw(filteredEeg);
                performFFT(filteredEeg); // 对滤波后的数据进行 FFT
                break;

            case BATTERY:
            case DRL_REF:
            case QUANTIZATION:
            default:
                break;
        }
    }


    @SuppressWarnings("unused")
    public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
    }

    //定义一个方法，可以获得EEG数据中的前4组数据，从而得到4个电极的佩戴情况。

    private void getEegChannelValues(double[] buffer, MuseDataPacket p) {
        buffer[0] = p.getEegChannelValue(Eeg.EEG1);
        buffer[1] = p.getEegChannelValue(Eeg.EEG2);
        buffer[2] = p.getEegChannelValue(Eeg.EEG3);
        buffer[3] = p.getEegChannelValue(Eeg.EEG4);
        buffer[4] = p.getEegChannelValue(Eeg.AUX_LEFT);
        buffer[5] = p.getEegChannelValue(Eeg.AUX_RIGHT);
    }




//  UI 部分

    private void initUI() {

        setContentView(R.layout.activity_main);//找到对应的xml文件
        Button bleImageReceiveButton = findViewById(R.id.ble_image_receive);
        bleImageReceiveButton.setOnClickListener(this);
        Button refreshButton = findViewById(R.id.refresh);
        refreshButton.setOnClickListener(this);
        Button connectButton = findViewById(R.id.connect);
        connectButton.setOnClickListener(this);
        Button disconnectButton = findViewById(R.id.disconnect);
        disconnectButton.setOnClickListener(this);
        Button pauseButton = findViewById(R.id.pause);
        pauseButton.setOnClickListener(this);

        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        Spinner musesSpinner = findViewById(R.id.muses_spinner);
        musesSpinner.setAdapter(spinnerAdapter);
    }

    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            if (eegStale) {
                updateEeg();
            }
            handler.postDelayed(tickUi, 1000 / 60);//每隔16.67s，重复执行这个任务
        }
    };

    private void updateEeg() {//实时更新EEG的值
        TextView tp9 = findViewById(R.id.eeg_tp9);//左后
        TextView fp1 = findViewById(R.id.eeg_af7);//左前
        TextView fp2 = findViewById(R.id.eeg_af8);//右前
        TextView tp10 = findViewById(R.id.eeg_tp10);//右后
        tp9.setText(String.format(Locale.getDefault(), "%6.2f", eegBuffer[0]));
        fp1.setText(String.format(Locale.getDefault(), "%6.2f", eegBuffer[1]));
        fp2.setText(String.format(Locale.getDefault(), "%6.2f", eegBuffer[2]));
        tp10.setText(String.format(Locale.getDefault(), "%6.2f", eegBuffer[3]));
    }

// 文件读写部分

    private final Thread fileThread = new Thread() {
        @Override
        public void run() {
            Looper.prepare();
            fileHandler.set(new Handler(getMainLooper()));
            final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            final File file = new File(dir, "new_muse_file.muse" );
            // MuseFileWriter will append to an existing file.
            // In this case, we want to start fresh so the file
            // if it exists.
            if (file.exists() && !file.delete()) {
                Log.e(TAG, "file not successfully deleted");
            }
            Log.i(TAG, "Writing data to: " + file.getAbsolutePath());
            fileWriter.set(MuseFileFactory.getMuseFileWriter(file));
            Looper.loop();
        }
    };

    private void initRawFile() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, "eeg_data.raw");
        if (file.exists() && !file.delete()) {
            Log.e(TAG, "file not successfully deleted");
        }
        File fftEegFile = new File(dir, "fft_eeg_data.raw");
        if (fftEegFile.exists() && !fftEegFile.delete()) {
            Log.e(TAG, "fft_eeg_data.raw file not successfully deleted");
        }
        File filteredEegFile = new File(dir, "filtered_eeg_data.raw");
        if (filteredEegFile.exists() && !filteredEegFile.delete()) {
            Log.e(TAG, "filtered_eeg_data.raw file not successfully deleted");
        }
    }

    private void writeDataPacketToFile(final MuseDataPacket p) {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(() -> {
                if (p.packetType() == MuseDataPacketType.EEG) {
                    writeEegDataToRaw(p);
                }
            });
        }
    }

    private void writeEegDataToRaw(MuseDataPacket p) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, "eeg_data.raw");
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            for (int i = 0; i < p.valuesSize(); i++) {
                fos.write(Double.toString(p.getEegChannelValue(Eeg.values()[i])).getBytes());
                fos.write(" ".getBytes());
            }
            fos.write("\n".getBytes());
        } catch (IOException e) {
            Log.e(TAG, "Error writing EEG data to raw file", e);
        }
    }

    private void saveFile() {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(() -> {
                MuseFileWriter w = fileWriter.get();
                // Annotation strings can be added to the file to
                // give context as to what is happening at that point in
                // time.  An annotation can be an arbitrary string or
                // may include additional AnnotationData.
                w.addAnnotationString(0, "Disconnected");
                w.flush();
                w.close();
            });
        }
    }


    @SuppressWarnings("unused")
//    用于监听Muse的连接状态
    static class MuseL extends MuseListener {
        final WeakReference<MainActivity> activityRef;

        MuseL(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void museListChanged() {
            activityRef.get().museListChanged();
        }
    }
 
    static class ConnectionListener extends MuseConnectionListener {
        final WeakReference<MainActivity> activityRef;
        ConnectionListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }
        @Override
        public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {
            activityRef.get().receiveMuseConnectionPacket(p, muse);
        }
    }

    static class DataListener extends MuseDataListener {
        final WeakReference<MainActivity> activityRef;

        DataListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }
        @Override
        public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
            activityRef.get().receiveMuseArtifactPacket(p, muse);
        }
        @Override
        public void receiveMuseDataPacket(MuseDataPacket packet, Muse muse) {
        }
    }
}


