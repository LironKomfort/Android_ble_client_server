package com.cubx.serverble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.TsManager;
import android.util.Log;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static android.bluetooth.BluetoothGatt.GATT_FAILURE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BLE Server";

    /* Bluetooth API */
    private BluetoothManager mBluetoothManager;
    private BluetoothGattServer mBluetoothGattServer;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    /* Notify subscriber */
    private BluetoothDevice mRegisteredDevice = null;

    final int BUF_SIZE = 400;
    byte[] mBuf = new byte[BUF_SIZE];
    int mBufIdx = 0;
    int mMaxPayloadSize = 20;
    byte mPacketCount = 0;

    boolean mbCount = false;
    byte mCounter = 0;
    Thread mCounterThread = new Thread(()->{
        try {
            while(mbCount) {
                mBufIdx = mPacketCount = 0;
                byte[] value = ByteBuffer.allocate(4).putInt(mCounter).array();
                enqueueOperation(new Notify(SensorProfile.DATA_R, value));
                Thread.sleep(1000);
                mCounter++;
            }
        }
        catch (Exception e){

        }
    });

    abstract class BleOperationType { }

    class Notify extends BleOperationType {
        UUID mCharacteristic;
        byte[] mByteValue = null;
        String mStrValue = null;

        Notify(UUID characteristic, byte[] value) {
            mCharacteristic = characteristic;
            mByteValue = value;
        }

        Notify(UUID characteristic, String value) {
            mCharacteristic = characteristic;
            mStrValue = value;
        }
    }

    private ConcurrentLinkedQueue<BleOperationType> mOperationQueue = new ConcurrentLinkedQueue<>();
    private BleOperationType mPendingOperation = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnStart = findViewById(R.id.Start);
        btnStart.setOnClickListener(v ->{
            startAdvertising();
        });

        Button btnStop = findViewById(R.id.Stop);
        btnStop.setOnClickListener(v -> {
            stopAdvertising();
        });

        Button btnNotify = findViewById(R.id.Notify);
        btnNotify.setOnClickListener(v -> {
            byte[] value = ByteBuffer.allocate(4).putInt(mCounter).array();
            notifyRegisteredDevices(SensorProfile.DATA_R, value);
        });

        //Init mBuf
        byte val = 0;
        for (int i = 0; i < mBuf.length; i++)
        {
            mBuf[i] = val++;
        }

        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        // We can't continue without proper Bluetooth support
        if (!checkBluetoothSupport(bluetoothAdapter)) {
            finish();
        }

        TsManager manager = getSystemService(TsManager.class);
        boolean isNameChanged = BluetoothAdapter.getDefaultAdapter().setName(manager.getUniqueId());
        Log.d(TAG, "Set BT device name " + manager.getUniqueId() + ": " + isNameChanged);

        // Register for system Bluetooth events
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBluetoothReceiver, filter);
        if (!bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is currently disabled...enabling");
            bluetoothAdapter.enable();
        } else {
            Log.d(TAG, "Bluetooth enabled...starting services");
            startAdvertising();
            startServer();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        if (bluetoothAdapter.isEnabled()) {
            stopServer();
            stopAdvertising();
        }

        unregisterReceiver(mBluetoothReceiver);
    }

    /**
     * Verify the level of Bluetooth support provided by the hardware.
     * @param bluetoothAdapter System {@link BluetoothAdapter}.
     * @return true if Bluetooth is properly supported, false otherwise.
     */
    private boolean checkBluetoothSupport(BluetoothAdapter bluetoothAdapter) {

        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth is not supported");
            return false;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(TAG, "Bluetooth LE is not supported");
            return false;
        }

        return true;
    }

    /**
     * Listens for Bluetooth adapter events to enable/disable
     * advertising and server functionality.
     */
    private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);

            switch (state) {
                case BluetoothAdapter.STATE_ON:
                    startAdvertising();
                    startServer();
                    break;
                case BluetoothAdapter.STATE_OFF:
                    stopServer();
                    stopAdvertising();
                    break;
                default:
                    // Do nothing
            }
        }
    };

    /**
     * Begin advertising over Bluetooth that this device is connectable
     * and supports our Service.
     */
    private void startAdvertising() {
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        mBluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null) {
            Log.w(TAG, "Failed to create advertiser");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        //https://stackoverflow.com/questions/57756834/ble-advertise-data-size-limit
        // Advertise data size limit is 31 bytes. If larger - will cause error code 1 on start.
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(SensorProfile.SENSOR_SERVICE))
                .build();

        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();

        mBluetoothLeAdvertiser
                .startAdvertising(settings, data, scanResponse, mAdvertiseCallback);
    }

    /**
     * Stop Bluetooth advertisements.
     */
    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;
        Log.d(TAG, "LE Advertise Stopped");
        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    private BluetoothGattService createService() {
        BluetoothGattService service = new BluetoothGattService(SensorProfile.SENSOR_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Readable Data characteristic
        BluetoothGattCharacteristic dataR = new BluetoothGattCharacteristic(SensorProfile.DATA_R,
                //Read-only characteristic, supports notifications
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor configDescriptor = new BluetoothGattDescriptor(SensorProfile.CLIENT_CONFIG,
                //Read/write descriptor
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        dataR.addDescriptor(configDescriptor);
        service.addCharacteristic(dataR);

        // Writeable Data characteristic
        BluetoothGattCharacteristic dataW = new BluetoothGattCharacteristic(SensorProfile.DATA_W,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
        service.addCharacteristic(dataW);

        return service;
    }

    /**
     * Initialize the GATT server instance with the services/characteristics
     * from the Time Profile.
     */
    private void startServer() {
        mBluetoothGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mBluetoothGattServer == null) {
            Log.w(TAG, "Unable to create GATT server");
            return;
        }

        mBluetoothGattServer.addService(createService());
    }

    /**
     * Shut down the GATT server.
     */
    private void stopServer() {
        if (mBluetoothGattServer == null) return;

        mBluetoothGattServer.close();
    }

    /**
     * Callback to receive information about the advertisement process.
     */
    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "LE Advertise Started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "LE Advertise Failed: "+errorCode);
        }
    };

    /**
     * Callback to handle incoming requests to the GATT server.
     * All read/write requests for characteristics and descriptors are handled here.
     */
    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BluetoothDevice CONNECTED: " + device);
//                stopAdvertising();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
//                startAdvertising();
                Log.i(TAG, "BluetoothDevice DISCONNECTED: " + device);
                //Remove device from any active subscriptions
                mRegisteredDevice = null;
                mbCount = false;
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (SensorProfile.DATA_R.equals(characteristic.getUuid())) {
                Log.i(TAG, "onCharacteristicReadRequest, offset=" + offset + " mBufIdx=" + mBufIdx);
                byte[] payload;
                if (mBufIdx < BUF_SIZE) {
                    int bufSize = Math.min(mMaxPayloadSize, BUF_SIZE - mBufIdx);
                    payload = new byte[bufSize];
                    System.arraycopy(mBuf, mBufIdx, payload, 0, bufSize);
                    mPacketCount++;
                    payload[0] = mCounter;
                    payload[1] = mPacketCount;
                    payload[2] = (byte) ((BUF_SIZE/mMaxPayloadSize) + 1);
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, offset, payload);
                    mBufIdx += bufSize;
                } else {
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, 0, null);
                    mPacketCount = 0;
                }
            } else {
                // Invalid characteristic
                Log.w(TAG, "Invalid Characteristic Read: " + characteristic.getUuid());
                mBluetoothGattServer.sendResponse(device, requestId, GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (SensorProfile.DATA_W.equals(characteristic.getUuid())) {
                Log.d(TAG, "onCharacteristicWriteRequest, value=" + String.format("0x%2x%2x", value[1], value[0]));
                if (responseNeeded)
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, offset, value);
            } else {
                // Invalid characteristic
                Log.w(TAG, "Invalid Characteristic Write: " + characteristic.getUuid());
                if (responseNeeded)
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            if (SensorProfile.CLIENT_CONFIG.equals(descriptor.getUuid())) {
                Log.d(TAG, "Config descriptor read");
                byte[] returnValue;
                if (mRegisteredDevice == device) {
                    returnValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                } else {
                    returnValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                }
                mBluetoothGattServer.sendResponse(device, requestId, GATT_FAILURE, 0, returnValue);
            } else {
                Log.w(TAG, "Unknown descriptor read request");
                mBluetoothGattServer.sendResponse(device, requestId, GATT_FAILURE, 0, null);
            }
        }

        //If a client wants to be notified of any changes in the counter characteristic value,
        // it should write its intent on a config descriptor
        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (SensorProfile.CLIENT_CONFIG.equals(descriptor.getUuid())) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Subscribe device to notifications: " + device);
                    mRegisteredDevice = device;
                    mCounter = 0;
                    mbCount = true;
                    mCounterThread.start();
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Unsubscribe device from notifications: " + device);
                    mRegisteredDevice = null;
                }

                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, 0, null);
                }
            } else {
                Log.w(TAG, "Unknown descriptor write request");
                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_FAILURE, 0, null);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            mMaxPayloadSize = mtu - 3;
            Log.d(TAG, "onMtuChanged, mtu=" + mtu);
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            if (mPendingOperation instanceof Notify)
            {
                endOperation();
            }
        }
    };

    private void notifyRegisteredDevices(UUID characUUID, byte[] val) {
        BluetoothGattCharacteristic characteristic =
                mBluetoothGattServer.getService(SensorProfile.SENSOR_SERVICE).getCharacteristic(characUUID);

        if (mRegisteredDevice != null)
        {
            characteristic.setValue(val);
            mBluetoothGattServer.notifyCharacteristicChanged(mRegisteredDevice, characteristic, false);
            Log.d(TAG, "notifyRegisteredDevices " + mCounter);
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
        if (operation instanceof Notify)
        {
            Notify op = (Notify)operation;

            if (op.mByteValue != null)
                notifyRegisteredDevices(op.mCharacteristic, op.mByteValue);
            //TODO: add implementation for string/other data types
        }
    }

    synchronized void endOperation() {
        mPendingOperation = null;
        executeOperation();
    }
}