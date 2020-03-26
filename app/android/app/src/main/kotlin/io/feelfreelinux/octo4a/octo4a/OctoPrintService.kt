
package io.feelfreelinux.octo4a.octo4a

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDeviceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.*
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialDriver
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.text.format.Formatter
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONArray
import java.lang.Exception
import java.util.concurrent.Executors


class OctoPrintService : Service(), SerialInputOutputManager.Listener {
    override fun onRunError(e: Exception?) {
    }

    override fun onNewData(data: ByteArray?) {
        try {
            stdinStream?.write(data)
            stdinStream?.flush()
        } catch (e: Throwable) {

        }
    }

    enum class OctoPrintStatus {
        INSTALLING,
        STARTING_UP,
        RUNNING,
        STOPPED
    }

    val usbManager by lazy {
       getSystemService(Context.USB_SERVICE) as UsbManager
    }

    var ioManager: SerialInputOutputManager? = null

    val serialDevices
        get() = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).map { it.device.deviceName }

    val serialExecutor = Executors.newSingleThreadExecutor()

    var currentStatus = OctoPrintStatus.STOPPED

    var octoPrintProcess: Process? = null
    var stdinHandler: Process? = null
    var stdoutHandler: Process? = null
    var stdinStream: OutputStream? = null
    var stdoutStream: InputStream? = null
    var selectedDevice: UsbSerialDriver? = null

    companion object {

        const val CHANNEL_ID = "octo4a_notification_channel"
        const val LOGGER_TAG = "OCTO4A"
        const val NOTIFICATION_ID = 1
        const val BROADCAST_SERVICE_RECEIVE_ACTION = "io.feelfreelinux.octo4a.service_receive_event"
        const val EVENT_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        const val EVENT_USB_DETACHED  = "android.hardware.usb.action.USB_DEVICE_DETACHED"
        const val EXTRA_EVENTDATA = "EXTRA_EVENTDATA"
        const val BROADCAST_SERVICE_USB_GOT_ACCESS = "io.feelfreelinux.octo4a.usb_access_received"

        const val EVENT_TYPE_BEGIN_INSTALLATION = "beginInstallation"
        const val EVENT_TYPE_START_SERVER = "startServer"
        const val EVENT_TYPE_STOP_SERVER = "stopServer"
        const val EVENT_TYPE_QUERY_SERVER_STATUS = "queryServerStatus"
        const val EVENT_TYPE_UERY_SERIAL_DEVICES = "queryUsbDevices"
        const val EVENT_TYPE_SELECT_USB_DEVICE = "selectUsbDevice"
    }

    enum class InstallationStatuses {
        INSTALLING_BOOTSTRAP,
        DOWNLOADING_OCTOPRINT,
        INSTALLING_OCTOPRINT,
        BOOTING_OCTOPRINT,
        INSTALLATION_COMPLETE,
    }

    private val intentFilter by lazy {
        val filter = IntentFilter()
        filter.addAction(BROADCAST_SERVICE_RECEIVE_ACTION)
        filter.addAction(BROADCAST_SERVICE_USB_GOT_ACCESS)
        filter.addAction(EVENT_USB_ATTACHED)
        filter.addAction(EVENT_USB_DETACHED)

        filter
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == BROADCAST_SERVICE_RECEIVE_ACTION) {
                intent.getStringExtra(EXTRA_EVENTDATA)?.let {
                    val event = JSONObject(it)
                    when (event.getString("eventType")) {
                        EVENT_TYPE_BEGIN_INSTALLATION -> {
                            beginInstallation()
                        }
                        EVENT_TYPE_START_SERVER -> {
                            startupOctoPrint()
                        }
                        EVENT_TYPE_STOP_SERVER -> {
                            stopServer()
                        }

                        EVENT_TYPE_UERY_SERIAL_DEVICES -> {
                            updateDevicesList()
                        }

                        EVENT_TYPE_QUERY_SERVER_STATUS -> {
                            // re-setting the status forces status update
                            updateStatus(currentStatus)
                        }

                    }

                }
            } else if (intent?.action == BROADCAST_SERVICE_USB_GOT_ACCESS) {
                val granted = intent.extras!!.getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)
                if (granted) {
                    connectToUsbDevice()
                }
            } else if (intent?.action == EVENT_USB_DETACHED || intent?.action == EVENT_USB_ATTACHED) {
                updateDevicesList()
            }
        }
    }

    var usbPort: UsbSerialPort? = null

    private fun connectToUsbDevice() {
        selectedDevice = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).first()
    }

    private val notificationSubtitle: String
        get() {
            return when (currentStatus) {
                OctoPrintStatus.INSTALLING -> "Installation in progress"
                OctoPrintStatus.RUNNING -> "Server running"
                OctoPrintStatus.STARTING_UP -> "Starting up..."
                OctoPrintStatus.STOPPED -> "Server stopped"
            }
        }

    private val notificationBuilder by lazy {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("OctoPrint")
                .setContentText(notificationSubtitle)
                .setVibrate(null)
                .setSmallIcon(R.drawable.ic_print_black_24dp)
                .setContentIntent(pendingIntent)
    }

    override fun onCreate() {
        registerReceiver(broadcastReceiver, intentFilter)
        super.onCreate()
    }

    override fun onDestroy() {
        unregisterReceiver(broadcastReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startNotification()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun startNotification() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Octo4A Notification Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun updateDevicesList() {
        val intent = Intent(MainActivity.BROADCAST_RECEIVE_ACTION)

        if (serialDevices.isNotEmpty()) {
            val device = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).first()

            if (!usbManager.hasPermission(device!!.device)) {
                val mPendingIntent =
                        PendingIntent.getBroadcast(this@OctoPrintService, 0, Intent(BROADCAST_SERVICE_USB_GOT_ACCESS), 0)
                usbManager.requestPermission(device.device, mPendingIntent)
            } else {
                selectedDevice = device
            }


        }

        val jsonData = JSONObject()
        jsonData.put("eventType", "devicesList")
        jsonData.put("body", JSONObject().put("devices", JSONArray(serialDevices)))

        intent.putExtra(EXTRA_EVENTDATA, jsonData.toString())
        sendBroadcast(intent)
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.setContentText(notificationSubtitle).build())
    }

    fun beginInstallation() {
        Thread {
            updateStatus(OctoPrintStatus.INSTALLING)
            sendInstallationStatus(InstallationStatuses.INSTALLING_BOOTSTRAP)

            // Validate existence of home directory
            val homeFile = File(BootstrapUtils.HOME_PATH)
            if (!homeFile.exists()) {
                homeFile.mkdir()
            }

            // Download and unpack bootstrap
            Log.v("ASD", "UH BRUH")
            try {
                BootstrapUtils.setupBootstrap {
                    BootstrapUtils.runBashCommand("python3 -m ensurepip").waitAndPrintOutput()
                    sendInstallationStatus(InstallationStatuses.DOWNLOADING_OCTOPRINT)
                    BootstrapUtils.runBashCommand("curl -L https://github.com/foosel/OctoPrint/archive/devel.zip -o OctoPrint.zip").waitAndPrintOutput()
                    BootstrapUtils.runBashCommand("unzip OctoPrint.zip").waitAndPrintOutput()
                    sendInstallationStatus(InstallationStatuses.INSTALLING_OCTOPRINT)
                    BootstrapUtils.runBashCommand("mkfifo input")
                    BootstrapUtils.runBashCommand("mkfifo output")
                    BootstrapUtils.runBashCommand("mkdir -p .octoprint/plugins")
                    BootstrapUtils.runBashCommand("curl -L https://raw.githubusercontent.com/feelfreelinux/octo4a/master/octoprint-plugin/android_support.py -o .octoprint/plugins/android_support.py").waitAndPrintOutput()
                    BootstrapUtils.runBashCommand("cd OctoPrint-devel && python3 setup.py install").waitAndPrintOutput()
                    sendInstallationStatus(InstallationStatuses.BOOTING_OCTOPRINT)

                    startupOctoPrint(firstTime = true)
                }
            } catch (e: Exception) {
                throw(e)
                Log.v("ASD", e.message)
                e.printStackTrace()
            }
        }.start()
    }

    private fun startupOctoPrint(firstTime: Boolean = false) {
        if (octoPrintProcess != null && octoPrintProcess!!.isRunning()) {
            return
        }

        octoPrintProcess = BootstrapUtils.runBashCommand("octoprint")


        updateStatus(OctoPrintStatus.STARTING_UP)
        // Start thread that handles octoprint's output
        Thread {
            openVirtualSerialPort()

            try {
                octoPrintProcess!!.inputStream.reader().forEachLine {
                    Log.v(LOGGER_TAG, "octoprint: $it")

                    // TODO: Perhaps find a better way to handle it. Maybe some through plugin?
                    if (it.contains("Listening on")) {
                        updateStatus(OctoPrintStatus.RUNNING)

                        if (firstTime) {
                            // :)
                            sendInstallationStatus(InstallationStatuses.INSTALLATION_COMPLETE)
                        }
                    }
                }
            } catch (e: Throwable) {
                updateStatus(OctoPrintStatus.STOPPED)
            }
        }.start()
    }

    var vspHandlerThread: Thread? = null

    private fun openVirtualSerialPort() {
        // Open virtual serial port
        stdinHandler = BootstrapUtils.runBashCommand("cat -u < /data/data/io.feelfreelinux.octo4a/files/home/output")
        stdoutHandler = BootstrapUtils.runBashCommand("cat -u > /data/data/io.feelfreelinux.octo4a/files/home/input")
        stdinStream = stdoutHandler!!.outputStream
        stdoutStream = stdinHandler!!.inputStream

        vspHandlerThread = Thread {
            var usbConnection: UsbDeviceConnection? = null
            stdoutStream?.reader()?.forEachLine {
                if (it.contains("!octo4a:")) {
                    if (it.contains("!octo4a: BAUDRATE")) {
                        val baudrate = it.substringAfter("BAUDRATE").replace("\n", "").toInt()
                        ioManager?.stop()

                        usbConnection = usbManager.openDevice(selectedDevice!!.device)

                        usbPort = selectedDevice!!.ports.first()
                        usbPort?.open(usbConnection)
                        usbPort?.setParameters(baudrate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                        usbPort!!.dtr = true
                        usbPort!!.rts = true
                        ioManager = SerialInputOutputManager(usbPort, this)
                        serialExecutor.submit(ioManager)
                    } else if (it.contains("!octo4a: CLOSE")) {
                        try {
                            usbConnection?.close()
                        } catch (e: Throwable) { }

                        try {
                            usbPort?.close()
                        } catch (e: Throwable) { }
                    }

                } else {
                        usbPort?.write("$it\n".toByteArray(), 0)
                }
            }
        }
        vspHandlerThread?.start()
    }

    private fun stopServer() {
        // TODO: Do it more gracefully - send kill signal
        if (octoPrintProcess == null && !octoPrintProcess!!.isRunning()) {
            return
        }
        vspHandlerThread?.interrupt()
        octoPrintProcess?.destroy()
        updateStatus(OctoPrintStatus.STOPPED)
    }

    private fun sendInstallationStatus(status: InstallationStatuses) {
        val intent = Intent(MainActivity.BROADCAST_RECEIVE_ACTION)
        val jsonData = JSONObject()
        jsonData.put("eventType", "installationStatus")
        jsonData.put("body", JSONObject().put("status", status.name))

        intent.putExtra(EXTRA_EVENTDATA, jsonData.toString())
        sendBroadcast(intent)
    }

    private val ipAddress: String
        get() {
            val wm = applicationContext.getSystemService(WIFI_SERVICE)as WifiManager
            return Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        }

    private fun updateStatus(status: OctoPrintStatus) {
        currentStatus = status
        val intent = Intent(MainActivity.BROADCAST_RECEIVE_ACTION)
        val jsonData = JSONObject()

        updateNotification()

        jsonData.put("eventType", "serverStatus")
        val bodyObj = JSONObject().put("status", status.name)
        if (status == OctoPrintStatus.RUNNING) bodyObj.put("ipAddress", "$ipAddress:5000")
        jsonData.put("body", bodyObj)

        intent.putExtra(EXTRA_EVENTDATA, jsonData.toString())
        sendBroadcast(intent)
    }
}