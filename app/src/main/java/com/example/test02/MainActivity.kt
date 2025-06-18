package com.example.test02

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class MainActivity : ComponentActivity() {

    companion object {
        /** 반드시 페어링된 SPP 기기의 MAC 주소로 바꾼다 */
        private const val DEVICE_MAC = "00:11:22:33:44:55"
        /** 표준 SPP UUID */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        /** 전송할 텍스트 */
        private const val REQUEST_TEXT = "%#IDN?\r"
    }

    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null
    private lateinit var tvLog: TextView
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        val btnRequest: Button = findViewById(R.id.btnRequest)
        val adapter: BluetoothAdapter =
            BluetoothAdapter.getDefaultAdapter()
                ?: errorAndFinish("이 기기는 Bluetooth 를 지원하지 않습니다.")

        /* 런타임 권한 */
        var btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            Manifest.permission.BLUETOOTH

        val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) errorAndFinish("Bluetooth 권한이 거부되었습니다.")
            }

        if (ContextCompat.checkSelfPermission(this, btPermission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(btPermission)
        }

        btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN
        else
            Manifest.permission.BLUETOOTH

        if (ContextCompat.checkSelfPermission(this, btPermission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(btPermission)
        }

        val devices = getPairedBluetoothDevices();
        val device = devices.find { it.first == "raspberrypi" };

        scope.launch {
            try {
                val isConnected = ensureConnected(adapter, device?.second)
            } catch (e: Exception) {
                postLog("오류: ${e.message}")
            }
        }

//        socket?.let { startReading(it.inputStream) }

        /* 버튼 클릭 → SPP 전송 */
        btnRequest.setOnClickListener {
            scope.launch {
                try {
//                    val isConnected = ensureConnected(adapter, device?.second)
                    socket?.outputStream?.apply {
                        write(REQUEST_TEXT.toByteArray())
                        flush()
                    }
                    postLog("TX ▶ $REQUEST_TEXT")

                    socket?.let { startReading(it.inputStream) }
                } catch (e: Exception) {
                    postLog("오류: ${e.message}")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)

        if (requestCode == 1)
        {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            {
                // 권한이 허용되었을 경우
                errorAndFinish("BLUETOOTH_CONNECT 권한이 허용되었습니다.");
            }
            else
            {
                // 권한이 거부되었을 경우
                errorAndFinish("BLUETOOTH_CONNECT 권한이 거부되었습니다.");
            }
        }
    }

    /** SPP 소켓 연결 없으면 생성·연결 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun ensureConnected(adapter: BluetoothAdapter, mac: String?): Boolean {
        if (socket?.isConnected == true) return true;
        if (mac == null) return false;

        val device = adapter.getRemoteDevice(mac)
        postLog("연결 시도: ${device.name} ($mac)")
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.

            return true;
        }
        adapter.cancelDiscovery()           // 필수: 연결 속도/신뢰도 향상

        val tmp = device.createRfcommSocketToServiceRecord(SPP_UUID)
        tmp.connect()                        // 블로킹; 예외 발생 시 상위에서 처리
        socket = tmp
        postLog("연결 완료")

        return true;
    }

    private fun startReading(input: java.io.InputStream) {
        // 이미 돌고 있으면 재시작 방지
        if (readJob?.isActive == true) return

        readJob = scope.launch {
            val buffer = ByteArray(1024)
            while (isActive) {
                val bytes = try {
                    input.read(buffer)          // 블로킹
                } catch (e: Exception) {
                    postLog("RX 오류: ${e.message}")
                    break
                }

                try {
                    if (bytes > 0) {
                        val msg = String(buffer, 0, bytes, Charsets.UTF_8)
                        postLog("RX ◀ $msg")

                        val value = msg.removePrefix("0x").toInt(16)

                        val littleEndian2 = ByteBuffer.allocate(2)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putShort(value.toShort())
                            .array()

                        postLog("RX->littleEndian2 ◀ $littleEndian2")
                    }
                } catch (e: Exception) {
                    postLog("RX 오류: ${e.message}")
                }
            }
        }
    }

    /** UI 스레드로 로그 출력 */
    private suspend fun postLog(msg: String) =
        withContext(Dispatchers.Main) { tvLog.append("$msg\n") }

    private fun errorAndFinish(msg: String): Nothing {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
        throw IllegalStateException(msg)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getPairedBluetoothDevices(): List<Pair<String, String>> {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        val pairedDevices = mutableListOf<Pair<String, String>>()

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            return pairedDevices
        }

        bluetoothAdapter.bondedDevices?.forEach { device ->
            if (device.name != null){
//                pairedDevices.add(Pair(device.name ?: "Unknown", device.address))
                pairedDevices.add(Pair(device.name, device.address))
            }
        }

        return pairedDevices
    }

    override fun onDestroy() {
        super.onDestroy()
        readJob?.cancel()
        socket?.close()
        scope.cancel()
    }
}
