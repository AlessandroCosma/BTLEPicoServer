package com.alessandrocosma.btleserver;


import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.util.Log;

import java.util.UUID;


public class RemoteSensorProfile {

    private final static String TAG = RemoteSensorProfile.class.getSimpleName();

    /* Remote Sensor Service UUID */
    public static UUID REMOTE_SENSOR_SERVICE_UUID = UUID.fromString ("B340B65C-B8AE-49E7-8ED8-F79C61708475");
    /* Remote Sensor Data Characteristic UUID */
    public static UUID TEMPERATURE_NOTIFY_CHARACTERISTIC_UUID = UUID.fromString("FEE891B9-032A-43AF-8923-5E3A4FF989A3");
    /* Remote Sensor Descriptor UUID */
    //public static UUID REMOTE_SENSOR_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"")


    /**
     * Return a configured {@link BluetoothGattService} instance for the Remote Sensor Service.
     */
    public static BluetoothGattService createRemoteSensorService() {
        Log.d(TAG, "createRemoteSensorService()");
        BluetoothGattService service = new BluetoothGattService(REMOTE_SENSOR_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(TEMPERATURE_NOTIFY_CHARACTERISTIC_UUID,
                //Read-only characteristic, supports notifications
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);

        //BluetoothGattDescriptor config = new BluetoothGattDescriptor(REMOTE_SENSOR_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ);

        //characteristic.addDescriptor(config);
        service.addCharacteristic(characteristic);

        return service;
    }
}
