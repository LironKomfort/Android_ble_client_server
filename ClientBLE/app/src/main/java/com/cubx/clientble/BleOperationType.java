package com.cubx.clientble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import java.util.UUID;

abstract public class BleOperationType {
    public static final int WRITE_TYPE_BYTE_ARRAY = 0;
    public static final int WRITE_TYPE_INT = 1;
    public static final int WRITE_TYPE_STRING = 2;
}

class Connect extends BleOperationType {
    BluetoothDevice mDevice;
    Connect (BluetoothDevice device) {
        mDevice = device;
    }
}

class Disconnect extends BleOperationType {
    BluetoothDevice mDevice;
    Disconnect (BluetoothDevice device) {
        mDevice = device;
    }
}

class DiscoverServices extends BleOperationType {

}

class CharacteristicWrite extends BleOperationType {
    UUID mCharacUUID;
    int mWriteType;
    byte[] mValue;

    CharacteristicWrite (UUID characUUID, int writeType, byte[] value) {
        mCharacUUID = characUUID;
        mWriteType = writeType;
        mValue = value;
    }
}

class CharacteristicRead extends BleOperationType {
    BluetoothGattCharacteristic mCharacteristic;

    CharacteristicRead (BluetoothGattCharacteristic characteristic) {
        mCharacteristic = characteristic;
    }
}

class NotificationEnable extends BleOperationType {
    UUID mCharacteristicUUID;
    UUID mDescriptorUUID;

    NotificationEnable (UUID characteristicUUID, UUID descriptorUUID) {
        mCharacteristicUUID = characteristicUUID;
        mDescriptorUUID = descriptorUUID;
    }
}

class MtuRequest extends BleOperationType {
    int mMTU;

    MtuRequest (int mtu) {
        mMTU = mtu;
    }
}