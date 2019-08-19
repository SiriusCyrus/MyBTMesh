package com.android.bluetooth.mybtmesh;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {


    private BluetoothManager bluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private BluetoothGattServer mBluetoothGattServer;
    private int REQUEST_ENABLE_BT = 1;
    private Handler mHandler;
    private static final long SCAN_PERIOD = 5000;
    private BluetoothLeScanner mLEScanner;
    private ScanSettings settings;
    private List<ScanFilter> filters;
    private BluetoothGatt mGatt;
    private BluetoothDevice myDevice;
    List<BluetoothDevice> results = new ArrayList<BluetoothDevice>();


    String PROXY_SERVICE_UUID = "00001828-0000-1000-8000-00805f9b34fb";
    String PROXY_SERVICE_DATA_UUID = "00001828-0000-1000-8000-00805f9b34fc";
    String MESH_DATA_IN_UUID = "00002add-0000-1000-8000-00805f9b34fb";
    String MESH_DATA_OUT_UUID = "00002ade-0000-1000-8000-00805f9b34fb";


    // usually provided via provisioning and configuration of the node
    String hex_iv_index = "12345677";
    String hex_netkey = "7dd7364cd842ad18c17c2b820c84c3d6";
    String hex_appkey = "63964771734fbd76e3b40519d1d94a48";
    StringBuffer buf = new StringBuffer();
    byte[] globalWByte, globalRByte;
    String transmittedPDUMsg;
    String receivedPDUMsg;

    private ListAdapter ble_device_list_adapter;
    private int device_count=0;
    private ArrayList<BluetoothDevice> ble_devices;
    boolean writeFlag = true;

    Object lock = new Object();
    volatile boolean pduSent = false;
    int maxTry = 3;
    int retryCount = 0;


    public void createBTProxyNode() {

        BluetoothAdapter mBluetoothAdapter;
        Handler mHandler;

        mBluetoothAdapter = bluetoothManager.getAdapter();
        Log.e("Proxy name********", mBluetoothAdapter.getName());

        mBluetoothGattServer = bluetoothManager.openGattServer(this, mGattServerCallback);
        if (mBluetoothGattServer == null) {
            Log.e("Proxy ", "Unable to create GATT server");
            return;
        }
        Log.e("Proxy ", mBluetoothGattServer.toString());

        BluetoothGattService meshProxyService = new BluetoothGattService(java.util.UUID.fromString(PROXY_SERVICE_UUID),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        /* Data IN */
        BluetoothGattCharacteristic meshProxyDataIn = new BluetoothGattCharacteristic(java.util.UUID.fromString(MESH_DATA_IN_UUID), BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        meshProxyService.addCharacteristic(meshProxyDataIn);

        /* Data OUT */
        BluetoothGattCharacteristic meshProxyDataOut = new BluetoothGattCharacteristic(java.util.UUID.fromString(MESH_DATA_OUT_UUID), BluetoothGattCharacteristic.PROPERTY_BROADCAST,
                BluetoothGattCharacteristic.PERMISSION_READ);
        meshProxyService.addCharacteristic(meshProxyDataOut);


        mBluetoothGattServer.addService(meshProxyService);


    }

    Handler myHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            Bundle mybundle = msg.getData();
            String edittext = mybundle.getString("pdu");
            Log.e("handleMsg","::"+edittext);
            EditText pduArea = (EditText) findViewById(R.id.pduData);
            pduArea.setText(edittext);
        }
    };

    /**
     * Callback to handle incoming requests to the GATT server.
     * All read/write requests for characteristics and descriptors are handled here.
     */
    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            myDevice = device;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("mGattServerCallback", "BluetoothDevice CONNECTED: " + device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("mGattServerCallback", "BluetoothDevice DISCONNECTED: " + device);

            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {

            super.onCharacteristicWriteRequest(device,requestId,characteristic,preparedWrite,responseNeeded,offset,value);

            StringBuffer buffer = new StringBuffer();
            for (byte val : value) {
                buffer.append(Character.toString((char) val));

            }
            receivedPDUMsg = buffer.toString();

            Log.e("onCharacteristicWrite", device.getAddress() + "  :: msg :: " + receivedPDUMsg);
            Log.e("onCharacteristicWrite", device.getAddress() + "  :: requestId :: " + requestId);

            if(!receivedPDUMsg.equals(transmittedPDUMsg)){

                Message msg = myHandler.obtainMessage();
                Bundle bundle = new Bundle();

                bundle.putString("pdu",receivedPDUMsg);

                msg.setData(bundle);
                myHandler.sendMessage(msg);

                // relay msg to other mesh devices for new requests
                relayMsg(device);
            }


        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            long now = System.currentTimeMillis();

            mBluetoothGattServer.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    globalRByte);
            Log.e("onCharacteristicRead", device.getName());

        }
    };

    private void relayMsg(BluetoothDevice sourceDevice) {

        boolean errorStatus = false;
        for (BluetoothDevice device : ble_devices) {
            //if(!device.getAddress().equals(sourceDevice.getAddress())){
                pduSent = false;
                synchronized(lock) {
                    retryCount = 0;
                    BluetoothGatt gatt = device.connectGatt(this, false, mainGattCallback, BluetoothDevice.TRANSPORT_LE);
                    errorStatus = checkNResendPdu(device);
                    Log.e("relayMsg", "Device :: " +device.getAddress());
                }
            //}

        }
        Log.e("relayMsg", "Source :: " +sourceDevice.getAddress());


    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {


        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mHandler = new Handler();

        ble_device_list_adapter = new ListAdapter();

        ListView listView = (ListView) this.findViewById(R.id.deviceList);
        listView.setAdapter(ble_device_list_adapter);

        /* Check to make sure BLE is supported */
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE Not Supported", Toast.LENGTH_SHORT).show();
            finish();
        }
        /* Get a Bluetooth Adapter Object */
        bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        Log.e("onCreate", mBluetoothAdapter.getName());


    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_CANCELED) {
                //Bluetooth not enabled.
                finish();
                return;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * gets called on clicking establish proxy button
     */
    public void establishProxyNode(View view) {
        Log.e("OnScan********", "");

        // check bluetooth is available on on
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Log.e("OnScan********", "null");
            Intent enableBtIntent = new Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            return;
        }

        startAdvertising();
        createBTProxyNode();

        Toast.makeText(this,"Proxy Node established",Toast.LENGTH_SHORT).show();;
    }

    /**
     * Begin advertising over Bluetooth that this device is connectable
     */
    private void startAdvertising() {
        mBluetoothAdapter = bluetoothManager.getAdapter();
        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null) {
            Log.e("startAdvertising", "Failed to create advertiser");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();


        // advertising fields
        //byte[] flags = {0x02, 0x01, 0x06};
        //byte[] complete_list_of_16_bit_uuids = {0x03, 0x03, 0x28, 0x18};
        // Service Data:
        //   Len, Type and UUID: 0x0C, 0x16, 0x28, 0x18
        //   Identification Type: 0x00
        //   Network ID (generated from netkey with K3) : 0x3e, 0xca, 0xff, 0x67, 0x2f, 0x67, 0x33, 0x70
        //byte[] service_data_len_type_uuid = {0x0C, 0x16, 0x28, 0x18};

        byte[] serviceData = new byte[9];
        byte[] identification_type = {0x00};
        byte[] networkID = {0x3e, (byte) 0xca, (byte) 0xff, 0x67, 0x2f, 0x67, 0x33, 0x70};

        ByteBuffer buff = ByteBuffer.wrap(serviceData);
        buff.put(identification_type);
        buff.put(networkID);

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(java.util.UUID.fromString(PROXY_SERVICE_UUID)))
                .addServiceData(new ParcelUuid(java.util.UUID.fromString(PROXY_SERVICE_DATA_UUID)), buff.array())
                .build();

        mBluetoothLeAdvertiser
                .startAdvertising(settings, data, mAdvertiseCallback);

    }

    /**
     * Callback to receive information about the advertisement process.
     */
    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.e("AdvertiseCallback", "LE Advertise Started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e("AdvertiseCallback", "LE Advertise Failed: " + errorCode);
        }
    };

    /**
     * Stop Bluetooth advertisements.
     */
    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;

        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
        Log.e("stopAdvertising", "LE Advertise Started.");
    }

    /**
     * Shut down the GATT server.
     */
    private void stopServer() {
        if (mBluetoothGattServer == null) return;

        mBluetoothGattServer.close();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();


        if (mBluetoothAdapter.isEnabled()) {
            stopServer();
            stopAdvertising();
        }
    }


    /**
     * Scan and connect all mesh proxy devices
     */

    public void scanAllDevices(View myView) {
        scanLeDevice(true);
    }

    private void scanLeDevice(final boolean enable) {

        mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
        Log.e("scan********", mLEScanner.toString());
        if (enable) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {

                    mLEScanner.stopScan(mScanCallback);

                }

            }, SCAN_PERIOD);


            Log.e("name********", mBluetoothAdapter.getName() + " , " + mBluetoothAdapter.getAddress());

            List<ScanFilter> filters;
            filters = new ArrayList<ScanFilter>();
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(PROXY_SERVICE_UUID)).build();
            filters.add(filter);
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();



            //mLEScanner.startScan(mScanCallback);
            mLEScanner.startScan(filters,settings,mScanCallback);

        } else {

            mLEScanner.stopScan(mScanCallback);

            Toast.makeText(this,"Scan ended",Toast.LENGTH_SHORT).show();;
        }
    }


    private ScanCallback mScanCallback = new ScanCallback() {


        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            //* Connect to device found *//*
            Log.e("callbackType", String.valueOf(callbackType));

            BluetoothDevice btDevice = result.getDevice();
            Log.e("btDevice", btDevice.toString() + "  ..... " + btDevice.getName());
            results.add(result.getDevice());

            candidateBleDevice(result.getDevice(), result.getScanRecord().getBytes(), result.getRssi());

            //connectToDevice(btDevice);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            //* Process a batch scan results *//*
            for (ScanResult sr : results) {
                Log.e("Scan Item: ", sr.toString());

            }
        }

    };


    private final BluetoothGattCallback mainGattCallback = new BluetoothGattCallback() {

        @Override
         public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        Log.e("mainGattCallback", "CONNECTED");
                        gatt.discoverServices();
                        break;
                    case BluetoothProfile.STATE_DISCONNECTED:
                        Log.e("mainGattCallback", "DISCONNECTED");
                        gatt.close();
                        break;
                    default:
                        Log.e("mainGattCallback", "STATE_OTHER");
                }


        }


        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {


            synchronized(lock) {
                BluetoothGattService proxyService = gatt.getService(java.util.UUID.fromString(PROXY_SERVICE_UUID));
                BluetoothGattCharacteristic meshProxyDataIn = proxyService.getCharacteristic(java.util.UUID.fromString(MESH_DATA_IN_UUID));
                EditText myedit = (EditText) findViewById(R.id.pduData);
                //meshProxyDataIn.setValue( myedit.getText().toString());
                char[] data = new char[myedit.getText().length()];
                myedit.getText().getChars(0, myedit.getText().length(), data, 0);
                meshProxyDataIn.setValue(String.valueOf(data));
                gatt.writeCharacteristic(meshProxyDataIn);
                //writeFlag = true;
                Log.e("Sending data : ", meshProxyDataIn.getStringValue(0) + " , device " + gatt.getDevice());
                transmittedPDUMsg = meshProxyDataIn.getStringValue(0);
                pduSent = true;
                lock.notify();
            }


        }

    };




    public void candidateBleDevice(final BluetoothDevice device, byte[] scan_record, int rssi) {

                ble_device_list_adapter.addDevice(device);
                ble_device_list_adapter.notifyDataSetChanged();
                device_count++;

    }

    static class ViewHolder {
        public TextView text;
        public TextView bdaddr;
    }

    private class ListAdapter extends BaseAdapter {


        public ListAdapter() {
            super();
            ble_devices = new ArrayList<BluetoothDevice>();
        }

        public void addDevice(BluetoothDevice device) {
            if (!ble_devices.contains(device)) {
                ble_devices.add(device);
            }
        }

        public boolean contains(BluetoothDevice device) {
            return ble_devices.contains(device);
        }

        public BluetoothDevice getDevice(int position) {
            return ble_devices.get(position);
        }

        public void clear() {
            ble_devices.clear();
        }

        @Override
        public int getCount() {
            return ble_devices.size();
        }

        @Override
        public Object getItem(int i) {
            return ble_devices.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            ViewHolder viewHolder;
            if (view == null) {
                view = MainActivity.this.getLayoutInflater().inflate(R.layout.list_row, null);
                viewHolder = new ViewHolder();
                viewHolder.text = (TextView) view.findViewById(R.id.textView);
                viewHolder.bdaddr = (TextView) view.findViewById(R.id.bdaddr);
                view.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) view.getTag();
            }
            BluetoothDevice device = ble_devices.get(i);
            String deviceName = device.getName();
            if (deviceName != null && deviceName.length() > 0) {
                viewHolder.text.setText(deviceName);
            } else {
                viewHolder.text.setText("unknown device");
            }
            viewHolder.bdaddr.setText(device.getAddress());
            return view;
        }
    }

    public void sendPdu(View view){
        boolean errorStatus = false;
        for (BluetoothDevice device : ble_devices) {
            pduSent = false;

            // mainGattCallback async issue fix, wait until all callbacks completed
            // then proceed to next device
            synchronized(lock) {
                retryCount = 0;
                BluetoothGatt gatt = device.connectGatt(this, false, mainGattCallback, BluetoothDevice.TRANSPORT_LE);
                errorStatus = checkNResendPdu(device);
                if(errorStatus){
                    Toast.makeText(this,"ERROR: "+device.getAddress()+" Not Connected/OOR",Toast.LENGTH_LONG).show();
                }
            }

        }

        Toast.makeText(this,"PDU Sent.",Toast.LENGTH_SHORT).show();

    }

    public boolean checkNResendPdu(BluetoothDevice device){
        boolean errorStatus = false;
        try {
            while(!pduSent){
                lock.wait(2000);
                // recheck if pdusent successfully, else send again
                if(!pduSent){
                    BluetoothGatt gatt = device.connectGatt(this, false, mainGattCallback, BluetoothDevice.TRANSPORT_LE);
                    retryCount++;
                    if(retryCount < maxTry){
                        checkNResendPdu(device);
                    }else{
                        Log.e("Sending data : ", "Error...");
                        errorStatus = true;
                        break;
                    }
                }

            }

        } catch (InterruptedException e) {

        }

        return errorStatus;
    }




}
