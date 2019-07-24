package com.alessandrocosma.btleclient;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.support.v7.app.AppCompatActivity;
import android.support.v4.app.ActivityCompat;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;
import android.Manifest.*;
import java.util.List;
import java.util.Set;


import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    /* Remote Sensor Service UUID */
    public static UUID REMOTE_SENSOR_SERVICE_UUID = UUID.fromString ("B340B65C-B8AE-49E7-8ED8-F79C61708475");
    /* Remote Sensor Data Characteristic UUID */
    public static UUID TEMPERATURE_NOTIFY_CHARACTERISTIC_UUID = UUID.fromString("FEE891B9-032A-43AF-8923-5E3A4FF989A3");

    public static final String EXTRAS_DEVICE_OBJECT = "DEVICE_OBJECT";
    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private BluetoothGattCharacteristic mNotifyCharacteristic;

    // Nome del dispositivo BT
    private String mRemoteDeviceName;
    // Indirizzo MAC del dispositivo BT
    private String mRemoteDeviceAddress;
    // Oggetto "BluetoothDevice" a cui mi connetto (server)
    private BluetoothDevice mRemoteServer;

    private BluetoothLeService mBluetoothLeService;

    // BluetoothManager: utilizzato per determinare se il bluetooth è abilitato e disponibile
    private BluetoothManager mBluetoothManager;
    // BluetoothAdapter: utilizzato per gestire il ricevitore BT
    private BluetoothAdapter mBluetoothAdapter;

    private boolean mConnected = false;

    EditText tempValueEditText;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActivityCompat.requestPermissions(this,new String[]{permission.BLUETOOTH},1);
        ActivityCompat.requestPermissions(this,new String[]{permission.BLUETOOTH_ADMIN},1);
        ActivityCompat.requestPermissions(this,new String[]{permission.ACCESS_COARSE_LOCATION},1);

        // Initializes a Bluetooth adapter.
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        tempValueEditText = (EditText) findViewById(R.id.TempEditText);

        final Intent intent = getIntent();
        mRemoteServer = getIntent().getExtras().getParcelable("DEVICE_OBJECT");
        mRemoteDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mRemoteDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        Log.e(TAG, "Device to connect: "+ mRemoteDeviceName +"--> "+ mRemoteDeviceAddress);


        // register pairing state receiver
        IntentFilter bondChangedIntent = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bondChangedReceived, bondChangedIntent);

        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mRemoteDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }

        if(mRemoteServer == null){
            Log.e(TAG, "Unable to find BLE server!");
            MainActivity.this.finish();
        }

        // Start the connection (automatic pairing if necessary)
        Intent gattServiceIntent = new Intent(MainActivity.this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        // Checks if devices are bondend. Initiates a pairing phase or starts the connection
        /*
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        // Se la lista dei dispositivi associati ha almeno un dispositivo presente il server
        // cui voglio connettermi è presente nella lista allora mi connetto direttamente
        if(pairedDevices.size() > 0 && pairedDevices.contains(mRemoteServer)) {
            // start the connection
            if (!mConnected) {
                Intent gattServiceIntent = new Intent(MainActivity.this, BluetoothLeService.class);
                bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
            }
        }
        else {
            // start pairing
            mRemoteServer.createBond();
        }
        */
    }


    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart()");
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
    }

    @Override
    protected void onStop(){
        super.onStop();
        Log.d(TAG, "onPause()");
        unregisterReceiver(mGattUpdateReceiver);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");
        if(mServiceConnection != null)
            unbindService(mServiceConnection);
        unregisterReceiver(bondChangedReceived);
    }


    private  BroadcastReceiver bondChangedReceived = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "onReceive() action: "+action);

            if(action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)){
                Log.d(TAG, "Changed bond state");
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device.getBondState() == BluetoothDevice.BOND_BONDING) {
                    Log.d(TAG, " BOND_BONDING with "+device.getName());
                }
                if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                    Log.d(TAG, " BOND_NONE");
                }
                if (device.getBondState() == BluetoothDevice.ERROR) {
                    Log.d(TAG, " ERROR");
                }
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, " BOND with "+device.getName());
                }
            }
        }
    };


    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    // Handles various events fired by the Service.
        private BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "onReceive() action: "+action);

            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                List<BluetoothGattService> services = mBluetoothLeService.getSupportedGattServices();
                if(services != null){
                    for (BluetoothGattService gattService : services) {
                        if(gattService.getUuid().equals(REMOTE_SENSOR_SERVICE_UUID)){
                            final BluetoothGattCharacteristic characteristic = gattService.getCharacteristic(TEMPERATURE_NOTIFY_CHARACTERISTIC_UUID);
                            if (characteristic != null) {
                                final int charaProp = characteristic.getProperties();
                                if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                                    // If there is an active notification on a characteristic, clear
                                    // it first so it doesn't update the data field on the user interface.
                                    if (mNotifyCharacteristic != null) {
                                        mBluetoothLeService.setCharacteristicNotification(
                                                mNotifyCharacteristic, false);
                                        mNotifyCharacteristic = null;
                                    }
                                    mBluetoothLeService.readCharacteristic(characteristic);
                                }
                                if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                                    mNotifyCharacteristic = characteristic;
                                    mBluetoothLeService.setCharacteristicNotification(
                                            characteristic, true);
                                }
                            }
                        }
                    }
                }
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                String data = intent.getStringExtra(BluetoothLeService.EXTRA_DATA);
                if (data == null)
                    return;
                if(Float.parseFloat(data) <= -274.0f)
                    tempValueEditText.setText("error", TextView.BufferType.EDITABLE);
                else {
                    tempValueEditText.setText(data, TextView.BufferType.EDITABLE);
                    Log.d(TAG, "temperature = "+data);
                }
            }
        }
    };


    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Log.d(TAG, "onServiceConnected()");
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }

            Boolean result = mBluetoothLeService.connect(mRemoteDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
            mConnected = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(TAG, "onServiceDisconnected()");
            mBluetoothLeService = null;
            mConnected = false;
        }
    };
}
