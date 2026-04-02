package com.example.demo;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    TextView tvStatus, tvBpm, tvLog;
    Button btnScan, btnSave;

    BluetoothAdapter bluetoothAdapter;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    Handler handler = new Handler(Looper.getMainLooper());

    byte[] buffer = new byte[4096];
    int bufLen = 0;
    boolean frameSynced = false;

    List<String> dataLog = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvBpm    = findViewById(R.id.tv_bpm);
        tvLog    = findViewById(R.id.tv_analysis);
        btnScan  = findViewById(R.id.btn_scan);
        btnSave  = findViewById(R.id.btn_analyze);

        btnSave.setText("保存CSV");
        btnSave.setEnabled(false);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        btnScan.setOnClickListener(v -> startScan());
        btnSave.setOnClickListener(v -> saveCSV());
    }

    void startScan() {
        // 先检查蓝牙是否开启
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }

        requestPermissions();

        // 延迟一秒等权限申请完成
        handler.postDelayed(() -> {
            scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner == null) {
                runOnUiThread(() ->
                        Toast.makeText(this, "蓝牙扫描器初始化失败，请检查权限",
                                Toast.LENGTH_SHORT).show());
                return;
            }
            tvStatus.setText("扫描中...");
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, scanCallback);
            handler.postDelayed(() -> scanner.stopScan(scanCallback), 10000);
        }, 1000);
    }

    ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name != null && (name.contains("SD") || name.contains("Edan")
                    || name.contains("edan") || name.contains("FHR"))) {
                scanner.stopScan(scanCallback);
                runOnUiThread(() -> tvStatus.setText("连接: " + name));
                device.connectGatt(MainActivity.this, false, gattCallback);
            }
        }
    };

    BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            gatt = g;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> tvStatus.setText("已连接，发现服务..."));
                g.discoverServices();
            } else {
                runOnUiThread(() -> tvStatus.setText("已断开"));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            for (BluetoothGattService service : g.getServices()) {
                for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                    if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        g.setCharacteristicNotification(ch, true);
                        BluetoothGattDescriptor desc = ch.getDescriptor(
                                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (desc != null) {
                            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(desc);
                        }
                        runOnUiThread(() -> {
                            tvStatus.setText("✅ 采集中");
                            btnSave.setEnabled(true);
                        });
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic) {
            processData(characteristic.getValue());
        }
    };

    void processData(byte[] data) {
        System.arraycopy(data, 0, buffer, bufLen, data.length);
        bufLen += data.length;

        while (true) {
            if (!frameSynced) {
                int idx = -1;
                for (int i = 0; i < bufLen; i++) {
                    if ((buffer[i] & 0xFF) == 0xFA) { idx = i; break; }
                }
                if (idx == -1) { bufLen = 0; return; }
                System.arraycopy(buffer, idx, buffer, 0, bufLen - idx);
                bufLen -= idx;
                frameSynced = true;
            }

            if (bufLen < 129) return;

            if ((buffer[128] & 0xFF) == 0xFB) {
                int bpm = buffer[126] & 0xFF;
                if (bpm >= 50 && bpm <= 210) {
                    String time = new SimpleDateFormat("HH:mm:ss",
                            Locale.getDefault()).format(new Date());
                    String row = time + "," + bpm;
                    dataLog.add(row);

                    runOnUiThread(() -> {
                        tvBpm.setText(bpm + " BPM");
                        tvBpm.setTextColor(
                                (bpm < 120 || bpm > 160) ? 0xFFFF6F00 : 0xFFE53935);
                        tvLog.setText("已采集 " + dataLog.size() + " 条数据\n"
                                + "最新: " + row);
                    });
                }
                System.arraycopy(buffer, 129, buffer, 0, bufLen - 129);
                bufLen -= 129;
                frameSynced = false;
            } else {
                System.arraycopy(buffer, 1, buffer, 0, bufLen - 1);
                bufLen--;
                frameSynced = false;
            }
        }
    }

    void saveCSV() {
        try {
            File dir = getExternalFilesDir(null);
            String fname = "fhr_" + new SimpleDateFormat("yyyyMMdd_HHmmss",
                    Locale.getDefault()).format(new Date()) + ".csv";
            File file = new File(dir, fname);
            FileWriter fw = new FileWriter(file);
            fw.write("时间,胎心率BPM\n");
            for (String row : dataLog) fw.write(row + "\n");
            fw.close();
            Toast.makeText(this, "已保存: " + file.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gatt != null) gatt.close();
    }
}