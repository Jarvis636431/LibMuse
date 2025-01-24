package com.choosemuse.example.libmuse;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

public class BLEImageReceiverActivity extends AppCompatActivity {
    private static final String TAG = "BLEImageReceiver";
    private static final String DEVICE_NAME = "FV-CARE";
    private static final UUID SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("87654321-4321-6789-4321-6789abcdef01");
    private static final String SECRET_KEY = "secret_key";
    private static final String NEW_SECRET_KEY = "new_secret_key";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic targetCharacteristic;
    private ImageView imageView;

    private ByteArrayBuffer dataBuffer = new ByteArrayBuffer();
    private ByteArrayBuffer consolidatedBuffer = new ByteArrayBuffer();
    private int requestCounter = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_image_receiver);

        imageView = findViewById(R.id.imageView);
        initBluetoothAdapter();
    }

    private void initBluetoothAdapter() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        if (checkBluetoothPermissions()) {
            startBleScan();
        }
    }

    private boolean checkBluetoothPermissions() {
        String[] permissions = {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
        };

        boolean allPermissionsGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
            }
        }

        if (!allPermissionsGranted) {
            ActivityCompat.requestPermissions(this, permissions, 1);
            return false;
        }
        return true;
    }

    private void startBleScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        bluetoothLeScanner.startScan(scanCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            // 使用 OuterActivity.this 替代 this
            if (ActivityCompat.checkSelfPermission(BLEImageReceiverActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (device.getName() != null && device.getName().equals(DEVICE_NAME)) {
                bluetoothLeScanner.stopScan(this); // 此处仍需修改，见下文
                connectToDevice(device);
            }
        }
    };


    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (ActivityCompat.checkSelfPermission(BLEImageReceiverActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service != null) {
                targetCharacteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
                if (targetCharacteristic != null) {
                    if (ActivityCompat.checkSelfPermission(BLEImageReceiverActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        // TODO: Consider calling
                        //    ActivityCompat#requestPermissions
                        // here to request the missing permissions, and then overriding
                        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                        //                                          int[] grantResults)
                        // to handle the case where the user grants the permission. See the documentation
                        // for ActivityCompat#requestPermissions for more details.
                        return;
                    }
                    gatt.setCharacteristicNotification(targetCharacteristic, true);
                    startImageTransfer();
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data != null) {
                processReceivedData(data);
            }
        }
    };

    private void startImageTransfer() {
        sendCommand("READ_SECRET:" + SECRET_KEY);
    }

    private void sendCommand(String command) {
        if (targetCharacteristic != null) {
            targetCharacteristic.setValue(command.getBytes());
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            bluetoothGatt.writeCharacteristic(targetCharacteristic);
        }
    }

    private void processReceivedData(byte[] data) {
        Log.d(TAG, "Received " + data.length + " bytes");

        // 追加数据到缓存
        dataBuffer.append(data);
        int chunkSize = Math.min(dataBuffer.size(), 12288);
        byte[] chunk = dataBuffer.read(chunkSize);
        consolidatedBuffer.append(chunk);

        Log.d(TAG, "Received " + chunkSize + " bytes. Total received: " + consolidatedBuffer.size());

        // 如果已接收到完整的图像数据，尝试保存图像
        if (chunkSize == 12288) {
            sendCommand("READ_SECRET:" + NEW_SECRET_KEY);
        } else {
            saveReceivedImage();
        }
    }

    private void saveReceivedImage() {
        if (consolidatedBuffer.size() > 12288) {
            byte[] imageData = consolidatedBuffer.toByteArray();

            Log.d(TAG, "Image data size: " + imageData.length);

            // 尝试解码图像
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image");
            } else {
                Log.d(TAG, "Image decoded successfully");
            }

            runOnUiThread(() -> {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                    saveImageToFile(bitmap);
                } else {
                    Log.e(TAG, "Bitmap is null, cannot set image");
                }
            });

            consolidatedBuffer.clear();
            requestCounter++;
        }
    }

    private void saveImageToFile(Bitmap bitmap) {
        if (bitmap != null) {
            File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File imageFile = new File(storageDir, "image_" + requestCounter + ".jpg");

            try (FileOutputStream out = new FileOutputStream(imageFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                Log.d(TAG, "Image saved: " + imageFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to save image", e);
            }
        } else {
            Log.e(TAG, "Bitmap is null, cannot save image");
        }
    }

    private static class ByteArrayBuffer {
        private byte[] buffer = new byte[0];

        public void append(byte[] data) {
            byte[] newBuffer = new byte[buffer.length + data.length];
            System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
            System.arraycopy(data, 0, newBuffer, buffer.length, data.length);
            buffer = newBuffer;
        }

        public byte[] read(int length) {
            byte[] chunk = Arrays.copyOfRange(buffer, 0, length);
            buffer = Arrays.copyOfRange(buffer, length, buffer.length);
            return chunk;
        }

        public int size() {
            return buffer.length;
        }

        public void clear() {
            buffer = new byte[0];
        }

        public byte[] toByteArray() {
            return buffer;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            bluetoothGatt.close();
        }
    }
}