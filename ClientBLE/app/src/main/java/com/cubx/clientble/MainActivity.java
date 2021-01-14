package com.cubx.clientble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static com.cubx.clientble.BleOperationType.WRITE_TYPE_BYTE_ARRAY;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "BLE Client";
    private final int LOCATION_PERMISSION = 66;
    private final String SENSOR_ID = "DonisiSensor";

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;

    private BluetoothLeScanner mBluetoothLeScanner;
    private boolean mBLEScanning;

    private BluetoothGatt mBluetoothGatt;

    private Button mBLEScanBtn;
    private Button mBLEWrite;

    private ConcurrentLinkedQueue<BleOperationType> mOperationQueue = new ConcurrentLinkedQueue<>();
    private BleOperationType mPendingOperation = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate");

        mBLEScanBtn = findViewById(R.id.BLEScanBtn);
        mBLEWrite = findViewById(R.id.BLEWrite);

        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        // We can't continue without proper Bluetooth support
        if (!checkBluetoothSupport()) {
            finish();
        }
        else{
            if (!mBluetoothAdapter.isEnabled()) {
                Log.d(TAG, "Bluetooth is currently disabled...enabling");
                mBluetoothAdapter.enable();
            }
        }

        requestLocationPermission();
    }

    private boolean checkBluetoothSupport() {
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth is not supported");
            return false;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(TAG, "Bluetooth LE is not supported");
            return false;
        }

        return true;
    }

    private void requestLocationPermission(){
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION);
        }
        else {
            init();
        }
    }

    private void init(){
        mBluetoothLeScanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();

        mBLEScanBtn.setOnClickListener(v ->
                toggleLeScan()
        );

        mBLEWrite.setOnClickListener(v -> {
            byte[] value = {(byte) 0xAD, (byte) 0xDE};
            enqueueOperation(new CharacteristicWrite(SensorProfile.DATA_W, WRITE_TYPE_BYTE_ARRAY, value));
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == LOCATION_PERMISSION){
            if(grantResults[0] == PackageManager.PERMISSION_GRANTED){
                init();
            }
            else {
                requestLocationPermission();
            }
        }
    }

    private void toggleLeScan(){
        if(mBLEScanning){
            mBLEScanning = false;
            mBLEScanBtn.setText("BLE start");
            mBluetoothLeScanner.stopScan(mLeScanCallback);
        }
        else {
            mBLEScanning = true;
            mBLEScanBtn.setText("BLE stop");

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();

            ScanFilter scanFilter = new ScanFilter.Builder().
                    setServiceUuid(new ParcelUuid(SensorProfile.SENSOR_SERVICE)).build();

            mBluetoothLeScanner.startScan(Arrays.asList(scanFilter), settings, mLeScanCallback);
        }
    }

    synchronized void enqueueOperation(BleOperationType operation) {
        mOperationQueue.add(operation);
        if (mPendingOperation == null)
        {
            executeOperation();
        }
    }

    synchronized void executeOperation() {
        if (mPendingOperation != null)
        {
            Log.e(TAG, "executeOperation() called when an operation is pending, aborting.");
            return;
        }

        BleOperationType operation = mOperationQueue.poll();
        if (operation == null)
        {
            return;
        }

        mPendingOperation = operation;
        if (operation instanceof Connect)
        {
            BluetoothDevice device = ((Connect)operation).mDevice;
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        }
        else if (operation instanceof MtuRequest)
        {
            mBluetoothGatt.requestMtu(((MtuRequest)operation).mMTU);
        }
        else if (operation instanceof DiscoverServices)
        {
            mBluetoothGatt.discoverServices();
        }
        else if (operation instanceof NotificationEnable)
        {
            NotificationEnable op = (NotificationEnable)operation;
            notificationEnable(op.mCharacteristicUUID, op.mDescriptorUUID);
        }
        else if (operation instanceof CharacteristicRead)
        {
            mBluetoothGatt.readCharacteristic(((CharacteristicRead)operation).mCharacteristic);
        }
        else if (operation instanceof CharacteristicWrite)
        {
            CharacteristicWrite op = (CharacteristicWrite)operation;
            writeCharacteristic(op.mCharacUUID, op.mWriteType, op.mValue);
        }
    }

    synchronized void endOperation() {
        mPendingOperation = null;
        executeOperation();
    }

    private void writeCharacteristic(UUID characUUID, int writeType, byte[] value) {
        //check mBluetoothGatt is available
        if (mBluetoothGatt == null) {
            Log.e(TAG, "No connection");
            return;
        }
        BluetoothGattService Service = mBluetoothGatt.getService(SensorProfile.SENSOR_SERVICE);
        if (Service == null) {
            Log.e(TAG, "Service not found!");
            return;
        }
        BluetoothGattCharacteristic charac = Service.getCharacteristic(characUUID);
        if (charac == null) {
            Log.e(TAG, "Characteristic not found!");
            return;
        }

        // TODO: Check writeType and convert byte array if needed
        charac.setValue(value);

        boolean status = mBluetoothGatt.writeCharacteristic(charac);
        if (!status)
        {
            Log.e(TAG, "Write failed");
        }
    }

    private void notificationEnable(UUID characteristicUUID, UUID descriptorUUID)
    {
        BluetoothGattService service = mBluetoothGatt.getService(SensorProfile.SENSOR_SERVICE);
        // Get the counter characteristic
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);

        // Enable notifications for this characteristic locally
        mBluetoothGatt.setCharacteristicNotification(characteristic, true);

        // Write on the config descriptor to be notified when the value changes
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(descriptorUUID);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(descriptor);
    }

    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "App stopped");
        close();
    }

    // Device scan callback.
    private ScanCallback mLeScanCallback =
        new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                Log.d(TAG, "onScanResult. Name = " + result.getDevice().getName() + ". Address = " + result.getDevice().getAddress());
                if(result.getDevice().getName() != null && result.getScanRecord().getDeviceName().equals(SENSOR_ID)){
                    Log.d(TAG, "device found. calling stopScan");
                    toggleLeScan();
                    enqueueOperation(new Connect(result.getDevice()));
                    enqueueOperation(new MtuRequest(512));
                    enqueueOperation(new DiscoverServices());
                    enqueueOperation(new NotificationEnable(SensorProfile.DATA_R, SensorProfile.CLIENT_CONFIG));
                }
            }

            @Override
            public void onScanFailed(int errorCode){
                super.onScanFailed(errorCode);
                Log.e(TAG, "onScanFailed. errorCode = " + errorCode);
            }
        };

    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            switch (newState){
                case STATE_DISCONNECTED:
                    Log.d(TAG, "onConnectionStateChange. State = STATE_DISCONNECTED");
                    mPendingOperation = null;
                    mOperationQueue.clear();
                    break;
                case STATE_CONNECTING:
                    Log.d(TAG, "onConnectionStateChange. State = STATE_CONNECTING");
                    break;
                case STATE_CONNECTED:
                    Log.d(TAG, "onConnectionStateChange. State = STATE_CONNECTED");
                    if (mPendingOperation instanceof Connect)
                        endOperation();
                    break;
                case STATE_DISCONNECTING:
                    Log.d(TAG, "onConnectionStateChange. State = STATE_DISCONNECTING");
                    break;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered");
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Handle the error
                return;
            }
            if (mPendingOperation instanceof DiscoverServices)
            {
                endOperation();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            byte[] dataRecv = characteristic.getValue();
            if (dataRecv.length > 0)
            {
                Log.d(TAG, "onCharacteristicRead " + dataRecv[0] + ", " +
                        "packet " + dataRecv[1] + "/" + dataRecv[2] +
                        ", length=" + dataRecv.length);
            }
            if (mPendingOperation instanceof CharacteristicRead)
            {
                endOperation();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicWrite");
            if (mPendingOperation instanceof CharacteristicWrite)
            {
                endOperation();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            enqueueOperation(new CharacteristicRead(characteristic));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorWrite");
            if (mPendingOperation instanceof NotificationEnable)
            {
                endOperation();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "onMtuChanged, mtu=" + mtu);
            if (mPendingOperation instanceof MtuRequest)
                endOperation();
        }
    };
}
