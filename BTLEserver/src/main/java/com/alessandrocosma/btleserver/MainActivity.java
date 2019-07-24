package com.alessandrocosma.btleserver;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.companion.BluetoothLeDeviceFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.android.things.bluetooth.BluetoothConfigManager;
import com.google.android.things.contrib.driver.bmx280.Bmx280;
import com.google.android.things.contrib.driver.rainbowhat.RainbowHat;
import com.google.android.things.bluetooth.BluetoothPairingCallback;
import com.google.android.things.bluetooth.BluetoothConnectionManager;
import com.google.android.things.bluetooth.PairingParams;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.lang.reflect.Method;

public class MainActivity extends Activity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String ADAPTER_FRIENDLY_NAME = "PicoPiServer";

    private static final int DISCOVERABLE_TIMEOUT_MS = 60000;
    private static final int REQUEST_CODE_ENABLE_DISCOVERABLE = 100;


    // BluetoothManager:  used to determine if bluetooth is enabled and available
    private BluetoothManager mBluetoothManager;
    // BluetoothConfigManager: used to set the I / O capability of the Android Things device
    private BluetoothConfigManager mBluetoothConfigManager;
    // BluetoothGattServer: used to configure the BT server
    private BluetoothGattServer mBluetoothGattServer;
    // BluetoothAdapter: used to manage the BT receiver
    private BluetoothAdapter mBluetoothAdapter;
    // BluetoothLeAdvertiser: used for transmission
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;

    private BluetoothConnectionManager mBluetoothConnectionManager;

    // Insieme per contenere i dispositivi che si connettono
    private Set<BluetoothDevice> mRegisteredDevices = new HashSet<>();
    // Insieme per contenere i dispositivi associati
    private Set<BluetoothDevice> pairedDevices = null;

    // temperature sensor
    private Bmx280 tempSensor;

    private Handler handler;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothConfigManager = BluetoothConfigManager.getInstance();
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        mBluetoothConnectionManager = BluetoothConnectionManager.getInstance();

        // Parameters for the I/O capability of the device

            // Setto la capacità del device a IO_CAPABILITY_NONE: il device non ha capacità di I/O
            mBluetoothConfigManager.setLeIoCapability(BluetoothConfigManager.IO_CAPABILITY_NONE);

            // Setto la capacità del device a IO_CAPABILITY_IO: il device ha un display e può accettare
            // input YES/NO
            //mBluetoothConfigManager.setLeIoCapability(BluetoothConfigManager.IO_CAPABILITY_IO);

            // Setto la capacità del device a IO_CAPABILITY_OUT:: il device ha solo una display
            //mBluetoothConfigManager.setLeIoCapability(BluetoothConfigManager.IO_CAPABILITY_OUT);

            // Setto la capacità del device a IO_CAPABILITY_IN:: il device accetta solo input da tastiera
            //mBluetoothConfigManager.setLeIoCapability(BluetoothConfigManager.IO_CAPABILITY_IN);

            // Setto la capacita IO_CAPABILITY_KBDISP
            //mBluetoothConfigManager.setLeIoCapability(BluetoothConfigManager.IO_CAPABILITY_KBDISP);


        // Register the adapter state change receiver
        IntentFilter state_change_filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(adapterStateChangeReceiver, state_change_filter);

        // Register more BT broadcast receivers
        IntentFilter bond_change_filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bluetoothDeviceReceiver, bond_change_filter);
        IntentFilter pairing_request_filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        registerReceiver(bluetoothDeviceReceiver, pairing_request_filter);

        mBluetoothConnectionManager.registerPairingCallback(mBluetoothPairingCallback);

        // Init the handler
        handler = new Handler(Looper.getMainLooper());

        // Rimuovo i dispositivi BT associati --> java.lang.UnsupportedOperationException
        //mBluetoothAdapter.getBondedDevices().clear();

        // Rimuovo i dispositivi BT associati --> correct
        /*
        pairedDevices =  mBluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device: pairedDevices){
            try {
                Method m = device.getClass()
                        .getMethod("removeBond", (Class[]) null);
                m.invoke(device, (Object[]) null);
            } catch (Exception e) {
                Log.e("Removing has been failed.", e.getMessage());
            }
        }
        */


        listBondedDevices();

        // apro la connessione con il sensore di temperatura
        try {
            tempSensor = openTempSensor();
        }
        catch (IOException e){
            Log.e(TAG, "Unable to open temperatre sensor!");
            MainActivity.this.finish();
        }


        if (!mBluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is currently disabled...enabling");
            mBluetoothAdapter.enable();
        }else {
            Log.d(TAG, "Bluetooth enabled...starting services");
            // Enable Pairing mode (discoverable)
            enableDiscoverable();
            startAdvertising();
            startGattServer();
        }

    }

    @Override
    protected void onDestroy(){
        super.onDestroy();

        handler.removeCallbacks(reportTemperature);

        if(tempSensor != null){
            try {
                closeTempSensor();
            }
            catch (IOException e){
                Log.e(TAG, "Unable to close temperature sensor");
            }
        }


        if (mBluetoothAdapter.isEnabled()) {
            stopGattServer();
            stopAdvertising();
        }


        unregisterReceiver(adapterStateChangeReceiver);
        unregisterReceiver(bluetoothDeviceReceiver);
        mBluetoothConnectionManager.unregisterPairingCallback(mBluetoothPairingCallback);
    }


    /*
     * Listens for Bluetooth adapter events to enable/disable
     * advertising and server functionality.
     * Ricevitore per ricevere messaggi di sistema, per esempio quando il sistema informa
     * che l'adattatore Bluetooth è stato attivato o disattivato
     */
    private BroadcastReceiver adapterStateChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onRecive for action: "+intent.getAction());

            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);

            switch (state) {
                case BluetoothAdapter.STATE_ON:
                    Log.i(TAG, "BT Adapter is on");
                    listBondedDevices();
                    startAdvertising();
                    startGattServer();
                    break;
                case BluetoothAdapter.STATE_OFF:
                    stopGattServer();
                    stopAdvertising();
                    break;
                default:
                    // Do nothing
            }
        }
    };


    private BroadcastReceiver bluetoothDeviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if( BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals( intent.getAction() ) ) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                switch (device.getBondState()) {
                    case BluetoothDevice.BOND_BONDING:
                        Log.d(TAG, "start pairing with "+ device.getAddress());
                        break;
                    case BluetoothDevice.BOND_BONDED:
                        Log.d(TAG, "finish pairing with "+device.getAddress());
                        break;
                    case BluetoothDevice.BOND_NONE:
                        Log.d(TAG, "cancel");
                        break;
                    case BluetoothDevice.ERROR:
                        Log.d(TAG, "error");
                    default:
                        break;
                }

            }

            if( BluetoothDevice.ACTION_PAIRING_REQUEST.equals( intent.getAction())){
                Log.e(TAG, "ACTION_PAIRING_REQUEST");
            }
        }
    };

    private void listBondedDevices(){
        pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            Log.d(TAG, "|Bondend Devices|");
            for (BluetoothDevice device: pairedDevices){
                Log.d(TAG, "Device = "+device.getName()+"| Address = "+device.getAddress());
            }
        }
    }


    /*
     * Begin advertising over Bluetooth that this device is connectable and supports the Service.
     * AdvertisingService utilizzato per trasmettere i servizi disponibili tramite Bluetooth
     */
    private void startAdvertising() {
        Log.d(TAG, "startBLEAdvertising()");

        Log.d(TAG, "Set up Bluetooth Adapter name ");
        mBluetoothAdapter.setName(ADAPTER_FRIENDLY_NAME);

        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();

        if (mBluetoothLeAdvertiser == null) {
            Log.w(TAG, "Failed to create advertiser");
            return;
        }


        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0) // 0 remove the time limit
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .build();

        /*
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true) //Set it true
                .setTimeout(0) // 0 remove the time limit
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(RemoteSensorProfile.REMOTE_SENSOR_SERVICE_UUID))
                .build();

        */

        mBluetoothLeAdvertiser
                .startAdvertising(settings, data, mAdvertiseCallback);
    }

    /*
     * Stop Bluetooth advertisements.
     */
    private void stopAdvertising() {
        Log.i(TAG, "Stop BLE Advertising");
        if (mBluetoothLeAdvertiser == null) return;

        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    /*
     * Initialize the GATT server instance with the services/characteristics
     * from the Remote LED Profile.
     */
    private void startGattServer() {
        Log.d(TAG, "startGattServer()");
        mBluetoothGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mBluetoothGattServer == null) {
            Log.w(TAG, "Unable to create GATT server");
            return;
        }

        mBluetoothGattServer.addService(RemoteSensorProfile.createRemoteSensorService());
        handler.post(reportTemperature);
    }

    /*
     * Shut down the GATT server.
     */
    private void stopGattServer() {
        Log.d(TAG, "StopGattServer()");
        if (mBluetoothGattServer == null) return;

        mBluetoothGattServer.close();
    }

    /*
     * Callback to receive information about the advertisement process.
     */
    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            //super.onStartSuccess(settingsInEffect);
            Log.i(TAG, "LE Advertise Started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            //super.onStartFailure(errorCode);
            String error = "";
            switch (errorCode) {
                case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                    error = "Already started";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                    error = "Data too large";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    error = "Feature unsupported";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                    error = "Internal error";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    error = "Too many advertisers";
                    break;
                default:
                    error = "Unknown";

                    Log.e(TAG, "LE Advertise Failed: " + error);
            }
        }
    };

    /*
     * CallBack per il server Bluetooth
     * Callback to handle incoming requests to the GATT server.
     * All read/write requests for characteristics and descriptors are handled here.
     */
    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BluetoothDevice CONNECTED: " + device);
                mRegisteredDevices.add(device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BluetoothDevice DISCONNECTED: " + device);
                //Remove device from any active subscriptions
                mRegisteredDevices.remove(device);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (RemoteSensorProfile.TEMPERATURE_NOTIFY_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                Log.i(TAG, "Read characteristic data");
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null);
            } else {
                // Invalid characteristic
                Log.w(TAG, "Invalid characteristic read: " + characteristic.getUuid());
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            mBluetoothGattServer.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0,
                    null);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (responseNeeded) {
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null);
            }
        }
    };


    private BluetoothPairingCallback mBluetoothPairingCallback = new BluetoothPairingCallback() {

        @Override
        public void onPairingInitiated(BluetoothDevice bluetoothDevice,
                                       PairingParams pairingParams) {
            // Handle incoming pairing request or confirmation of outgoing pairing request
            handlePairingRequest(bluetoothDevice, pairingParams);
        }

        @Override
        public void onPaired(BluetoothDevice bluetoothDevice) {
            // Device pairing complete
            Log.i(TAG,"Device pairing complete");
        }

        @Override
        public void onUnpaired(BluetoothDevice bluetoothDevice) {
            // Device unpaired
            Log.i(TAG, "Device unpaired");
        }

        @Override
        public void onPairingError(BluetoothDevice bluetoothDevice,
                                   BluetoothPairingCallback.PairingError pairingError) {
            // Something went wrong!
            Log.e(TAG,"Pairing Error "+pairingError.getErrorCode());
        }
    };

    private void handlePairingRequest(BluetoothDevice bluetoothDevice, PairingParams pairingParams) {
        String pin;
        switch (pairingParams.getPairingType()) {
            case PairingParams.PAIRING_VARIANT_DISPLAY_PIN:
            case PairingParams.PAIRING_VARIANT_DISPLAY_PASSKEY:
                Log.e(TAG, "case 1");
                // Display the required PIN to the user
                pin = pairingParams.getPairingPin();
                Log.d(TAG, "Display Passkey - " + pin);
                break;
            case PairingParams.PAIRING_VARIANT_PIN:
            case PairingParams.PAIRING_VARIANT_PIN_16_DIGITS:
                Log.e(TAG, "case 2");
                // Obtain PIN from the user
                pin = "0000";
                // Pass the result to complete pairing
                mBluetoothConnectionManager.finishPairing(bluetoothDevice, pin);
                break;
            case PairingParams.PAIRING_VARIANT_CONSENT:
                Log.e(TAG, "case 3");
                mBluetoothConnectionManager.finishPairing(bluetoothDevice);
                break;
            case PairingParams.PAIRING_VARIANT_PASSKEY_CONFIRMATION:
                Log.e(TAG, "case 4");
                // Show confirmation of pairing to the user:
                // Pairing Passkey is null and in the PicoPi there is no confirmation requst
                // Complete the pairing process
                mBluetoothConnectionManager.finishPairing(bluetoothDevice);
                break;
        }
    }


    /*
     * Send a TEMPERATURE_NOTIFY_CHARACTERISTIC to any devices that are subscribed
     * to the characteristic.
     */
    private void notifyRegisteredDevices() {
        if (mRegisteredDevices.isEmpty()) {

            Log.i(TAG, "No subscribers registered");
            return;
        }

        pairedDevices = mBluetoothAdapter.getBondedDevices();
        //Log.i(TAG, "Sending update to " + " subscribers");

        // For each BT device registered
        for (BluetoothDevice device : mRegisteredDevices) {

            if(!pairedDevices.contains(device)){
                continue;
            }

            BluetoothGattCharacteristic temperatureDataCharacteristic = mBluetoothGattServer
                    .getService(RemoteSensorProfile.REMOTE_SENSOR_SERVICE_UUID)
                    .getCharacteristic(RemoteSensorProfile.TEMPERATURE_NOTIFY_CHARACTERISTIC_UUID);
            float temp = readTemperature();
            String temperature = "";
            temperature = String.valueOf(temp);
            temperatureDataCharacteristic.setValue(temperature);
            boolean success = mBluetoothGattServer.notifyCharacteristicChanged(device, temperatureDataCharacteristic, false);
            Log.e(TAG, "temperature = "+temperature+" - SUCCESS = "+success);

        }
    }


    /*
     * Enable the current {@link BluetoothAdapter} to be discovered (available for pairing) for
     * the next {@link #DISCOVERABLE_TIMEOUT_MS} ms.
     */
    private void enableDiscoverable() {
        Log.d(TAG, "Registering for discovery.");
        Intent discoverableIntent =
                new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                DISCOVERABLE_TIMEOUT_MS);
        startActivityForResult(discoverableIntent, REQUEST_CODE_ENABLE_DISCOVERABLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_ENABLE_DISCOVERABLE) {
            Log.d(TAG, "Enable discoverable returned with result " + resultCode);

            // ResultCode, as described in BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE, is either
            // RESULT_CANCELED or the number of milliseconds that the device will stay in
            // discoverable mode. In a regular Android device, the user will see a popup requesting
            // authorization, and if they cancel, RESULT_CANCELED is returned. In Android Things,
            // on the other hand, the authorization for pairing is always given without user
            // interference, so RESULT_CANCELED should never be returned.
            if (resultCode == RESULT_CANCELED) {
                Log.e(TAG, "Enable discoverable has been cancelled by the user. " +
                        "This should never happen in an Android Things device.");
                return;
            }
            Log.i(TAG,  "Bluetooth adapter successfully set to discoverable mode.\n " +
                        "Any source can find it with the name " + ADAPTER_FRIENDLY_NAME +
                        "\n and pair for the next " + DISCOVERABLE_TIMEOUT_MS + " ms. \n" +
                        "Try looking for it on your phone, for example.");

            // There is nothing else required here.
            // Most relevant Bluetooth events, like connection/disconnection, will
            // generate corresponding broadcast intents or profile proxy events that you can
            // listen to and react appropriately.
        }
    }


    private Bmx280 openTempSensor() throws IOException{
        tempSensor = RainbowHat.openSensor();
        tempSensor.setTemperatureOversampling(Bmx280.OVERSAMPLING_1X);
        tempSensor.setMode(Bmx280.MODE_NORMAL);

        return tempSensor;
    }



    private void closeTempSensor() throws IOException{
        tempSensor.close();
    }

    private float readTemperature(){
        float temperature = -274;
        if (tempSensor != null){
            try {
                temperature = tempSensor.readTemperature();
                BigDecimal tempBG = new BigDecimal(temperature);
                tempBG = tempBG.setScale(2, BigDecimal.ROUND_HALF_UP);
                temperature = (tempBG.floatValue());
            }
            catch (IOException e){
                Log.e(TAG, "Unable to read temperature from sensor");
            }
        }
        return temperature;
    }


    private final Runnable reportTemperature = new Runnable() {


        @Override
        public void run() {
            notifyRegisteredDevices();
            handler.postDelayed(reportTemperature, TimeUnit.SECONDS.toMillis(2));
        }
    };

}
