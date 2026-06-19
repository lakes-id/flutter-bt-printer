package com.sersoluciones.flutter_pos_printer_platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sersoluciones.flutter_pos_printer_platform.R
import java.nio.charset.Charset
import java.util.*

class USBPrinterService private constructor(private var mHandler: Handler?) {
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var currentDeviceId: Int? = null
    private val sessions = mutableMapOf<Int, UsbPrinterSession>()
    private val permissionQueue = ArrayDeque<UsbDevice>()
    private var permissionRequestInProgress = false
    private val permissionPendingDeviceIds = mutableSetOf<Int>()

    fun setHandler(handler: Handler?) {
        mHandler = handler
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if ((ACTION_USB_PERMISSION == action)) {
                val jobs: List<ArrayList<Int>>
                var session: UsbPrinterSession? = null
                synchronized(printLock) {
                    val usbDevice: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(
                            LOG_TAG,
                            "Success get permission for device ${usbDevice?.deviceId}, vendor_id: ${usbDevice?.vendorId} product_id: ${usbDevice?.productId}"
                        )

                        session = usbDevice?.let {
                            sessions[it.deviceId]
                        }

                        session?.state = STATE_USB_CONNECTED
                        session?.permissionPending = false

                        mHandler?.obtainMessage(
                            STATE_USB_CONNECTED
                        )?.sendToTarget()

                        jobs = session?.pendingJobs?.toList() ?: emptyList()

                        Log.i(
                            LOG_TAG,
                            "Permission granted. Replaying ${jobs.size} jobs"

                        )
                        session?.pendingJobs?.clear()
                        usbDevice?.let {
                            permissionPendingDeviceIds.remove(it.deviceId)
                        }

                        permissionRequestInProgress = false

                        processNextPermission()
                    } else {
                        usbDevice?.let {
                            sessions[it.deviceId]?.apply {
                                permissionPending = false
                                pendingJobs.clear()
                                state= STATE_USB_NONE
                            }
                        }


                        mHandler?.obtainMessage(
                            STATE_USB_NONE
                        )?.sendToTarget()

                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                context,
                                mContext?.getString(R.string.user_refuse_perm) +
                                        ": ${usbDevice?.deviceName}",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        permissionRequestInProgress = false

                        usbDevice?.let { it ->
                            permissionPendingDeviceIds.remove(it.deviceId)
                            permissionQueue.removeAll {
                                it.deviceId == usbDevice.deviceId
                            }
                        }

                        processNextPermission()

                        return
                    }
                }

                session?.let { printerSession ->
                    for (job in jobs) {
                        printBytes(printerSession, job)
                    }
                }

            } else if ((UsbManager.ACTION_USB_DEVICE_DETACHED == action)) {

                val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (usbDevice != null) {
                    val session: UsbPrinterSession?
                    synchronized(printLock) {
                        session = sessions.remove(usbDevice.deviceId)
                        if (currentDeviceId == usbDevice.deviceId) {
                            currentDeviceId = null
                        }
                        session?.apply {
                            closeConnectionIfExistsLocked(this)
                            state = STATE_USB_NONE
                        }
                    }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            context,
                            mContext?.getString(R.string.device_off),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                }
            }
        }
    }

    private fun currentSession(): UsbPrinterSession? {
        val deviceId = currentDeviceId ?: return null
        return sessions[deviceId]
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        ContextCompat.registerReceiver(
            mContext!!,
            mUsbDeviceReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.v(LOG_TAG, "ESC/POS Printer initialized")
    }

    // caller must hold printLock
    private fun closeConnectionIfExistsLocked(session: UsbPrinterSession) {
        session.connection?.let { connection ->
            session.usbInterface?.let {
                connection.releaseInterface(it)
            }
            connection.close()
        }

        session.connection = null
        session.usbInterface = null
        session.endpoint = null
    }

    fun closeConnectionIfExists() {
        synchronized(printLock) {
            currentSession()?.let {
                closeConnectionIfExistsLocked(it)
                it.state = STATE_USB_NONE
            }
            currentDeviceId = null
        }
    }

    val deviceList: List<UsbDevice>
        get() {
            if (mUSBManager == null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        mContext,
                        mContext?.getString(R.string.not_usb_manager),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return emptyList()
            }
            return ArrayList(mUSBManager!!.deviceList.values)
        }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        synchronized(printLock) {
            val currentSession = currentSession()
            val current = currentSession?.device

            if (current == null || current.vendorId != vendorId || current.productId != productId) {
                val usbDevices: List<UsbDevice> = deviceList
                for (usbDevice: UsbDevice in usbDevices) {
                    if ((usbDevice.vendorId == vendorId) && (usbDevice.productId == productId)) {
                        val session = sessions.getOrPut(usbDevice.deviceId) {
                            UsbPrinterSession(usbDevice)
                        }

                        currentDeviceId = usbDevice.deviceId

                        Log.v(
                            LOG_TAG,
                            "Request for device: vendor_id: " + usbDevice.vendorId + ", product_id: " + usbDevice.productId
                        )

                        Log.i(
                            LOG_TAG,
                            "Matched device: " +
                                    "id=${usbDevice.deviceId}, " +
                                    "name=${usbDevice.deviceName}, " +
                                    "vendor=${usbDevice.vendorId}, " +
                                    "product=${usbDevice.productId}, " +
                                    "hasPermission=${mUSBManager!!.hasPermission(usbDevice)}"
                        )
                        if (!mUSBManager!!.hasPermission(usbDevice)) {
                            if (!session.permissionPending) {
                                session.permissionPending = true
                                Log.i(
                                    LOG_TAG,
                                    "Requesting permission for device=${usbDevice.deviceId}"
                                )

                                if (permissionPendingDeviceIds.add(usbDevice.deviceId)) {
                                    permissionQueue.add(usbDevice)
                                }

                                processNextPermission()
                            }

                            session.state = STATE_USB_CONNECTING
                            mHandler?.obtainMessage(
                                STATE_USB_CONNECTING
                            )?.sendToTarget()
                        } else {
                            session.state = STATE_USB_CONNECTED
                            mHandler?.obtainMessage(STATE_USB_CONNECTED)?.sendToTarget()
                        }

                        return true
                    }
                }
                return false
            } else {
                mHandler?.obtainMessage(currentSession?.state ?: STATE_USB_NONE)?.sendToTarget()
            }
        }

        return true
    }

    private fun processNextPermission() {
        if (permissionRequestInProgress || permissionQueue.isEmpty()) {
            return
        }

        val device = permissionQueue.removeFirst()

        permissionRequestInProgress = true

        Log.i(
            LOG_TAG,
            "Requesting permission for device=${device.deviceId}"
        )

        mUSBManager!!.requestPermission(
            device,
            createPermissionIntent(device)
        )
    }

    private fun openConnection(session: UsbPrinterSession): Boolean {
        synchronized(printLock) {
            if (mUSBManager == null) {
                Log.e(LOG_TAG, "USB Manager is not initialized")
                return false
            }
            if (session.connection != null){
                Log.i(LOG_TAG, "USB Connection already connected")
                return true
            }
            val usbInterface = session.device.getInterface(0)
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        val usbDeviceConnection = mUSBManager!!.openDevice(session.device)
                        if (usbDeviceConnection == null) {
                            Log.e(LOG_TAG, "Failed to open USB Connection")
                            return false
                        }

                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                mContext,
                                mContext?.getString(R.string.connected_device),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return if (usbDeviceConnection.claimInterface(usbInterface, true)) {
                            session.endpoint = ep
                            session.usbInterface = usbInterface
                            session.connection = usbDeviceConnection
                            true
                        } else {
                            usbDeviceConnection.close()
                            Log.e(LOG_TAG, "Failed to retrieve usb connection")
                            false
                        }
                    }
                }
            }
            Log.e(LOG_TAG, "No BULK OUT endpoint found")
            return false
        }
    }

    fun printText(text: String): Boolean {
        Log.v(LOG_TAG, "Printing text")
        val session = currentSession() ?: return false
        val isConnected = openConnection(session)
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                synchronized(printLock) {
                    val bytes: ByteArray = text.toByteArray(Charset.forName("UTF-8"))
                    val b: Int =
                        session.connection!!.bulkTransfer(session.endpoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Return code: $b")
                }
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "Failed to connect to device")
            false
        }
    }

    fun printRawData(data: String): Boolean {
        Log.v(LOG_TAG, "Printing raw data: $data")
        val session = currentSession() ?: return false
        val isConnected = openConnection(session)
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                synchronized(printLock) {
                    val bytes: ByteArray = Base64.decode(data, Base64.DEFAULT)
                    val b: Int =
                        session.connection!!.bulkTransfer(session.endpoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Return code: $b")
                }
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "Failed to connected to device")
            false
        }
    }

    fun printBytes(bytes: ArrayList<Int>): Boolean {
        val session = currentSession() ?: return false
        return printBytes(session, bytes)
    }

    fun printBytes(session: UsbPrinterSession, bytes: ArrayList<Int>): Boolean {
        Log.v(LOG_TAG, "Printing bytes")

        synchronized(printLock) {
            if (session.permissionPending) {
                session.pendingJobs.add(ArrayList(bytes))
                Log.i(
                    LOG_TAG,
                    "Queued print job. Pending count=${session.pendingJobs.size}"
                )
                return true
            }
        }

        Log.i(
            LOG_TAG,
            "Printing on device=${session.device.deviceId}"
        )
        val isConnected = openConnection(session)
        if (isConnected) {

            val chunkSize = session.endpoint!!.maxPacketSize
            Log.v(LOG_TAG, "Max Packet Size: $chunkSize")
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                synchronized(printLock) {
                    val byteData = ByteArray(bytes.size)

                    for (i in bytes.indices) {
                        byteData[i] = bytes[i].toByte()
                    }
                    var b = 0
                    if (session.connection != null) {
                        if (byteData.size > chunkSize) {
                            var offset = 0

                            while (offset < byteData.size) {
                                val length = minOf(
                                    chunkSize,
                                    byteData.size - offset
                                )

                                val buffer = Arrays.copyOfRange(
                                    byteData,
                                    offset,
                                    offset + length
                                )

                                b = session.connection!!.bulkTransfer(
                                    session.endpoint,
                                    buffer,
                                    length,
                                    100000

                                )

                                if (b < 0) {
                                    Log.e(LOG_TAG, "bulkTransfer failed")
                                    break
                                }

                                offset += length
                            }
                        } else {
                            b = session.connection!!.bulkTransfer(
                                session.endpoint,
                                byteData,
                                byteData.size,
                                100000
                            )
                        }
                        Log.i(LOG_TAG, "Return code: $b")
                    }
                }
            }.start()
            return true
        } else {
            Log.v(LOG_TAG, "Failed to connected to device")
            return false
        }
    }

    private fun createPermissionIntent(device: UsbDevice): PendingIntent {
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            `package` = mContext!!.packageName
            putExtra("device_id", device.deviceId)
        }

        return PendingIntent.getBroadcast(
            mContext,
            device.deviceId, // unique request code
            intent,
            PendingIntent.FLAG_MUTABLE
        )

    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var mInstance: USBPrinterService? = null
        private const val LOG_TAG = "ESC POS Printer"
        private const val ACTION_USB_PERMISSION = "com.flutter_pos_printer.USB_PERMISSION"

        // Constants that indicate the current connection state
        const val STATE_USB_NONE = 0 // we're doing nothing
        const val STATE_USB_CONNECTING = 2 // now initiating an outgoing connection
        const val STATE_USB_CONNECTED = 3 // now connected to a remote device

        private val printLock = Any()

        fun getInstance(handler: Handler): USBPrinterService {
            if (mInstance == null) {
                mInstance = USBPrinterService(handler)
            }
            return mInstance!!
        }
    }
}

data class UsbPrinterSession(
    val device: UsbDevice,
    var connection: UsbDeviceConnection? = null,
    var usbInterface: UsbInterface? = null,
    var endpoint: UsbEndpoint? = null,
    var state: Int = USBPrinterService.STATE_USB_NONE,
    var permissionPending: Boolean = false,
    val pendingJobs: MutableList<ArrayList<Int>> = mutableListOf()
)

data class PendingPrintJob(
    val vendorId: Int,
    val productId: Int,
    val bytes: ArrayList<Int>
)