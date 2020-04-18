package org.openchaos.android.fooping2

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


// TODO: refactor data sources into plugins
// TODO: refactor exfiltrators into plugins
// TODO: refactor transformers into plugins


class MainWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    private val TAG: String = this.javaClass.simpleName

    private lateinit var cryptKeySpec: SecretKeySpec
    private lateinit var hmacKeySpec: SecretKeySpec

    private lateinit var socket: DatagramSocket


    @Suppress("NOTHING_TO_INLINE")
    private inline fun roundValue(value: Double, scale: Int): Double {
        // TODO: is .stripTrailingZeros() required or cargo-cult from v1?
        return BigDecimal.valueOf(value).setScale(scale, BigDecimal.ROUND_HALF_UP).stripTrailingZeros().toDouble()
    }

    private fun sendMessage(json: JSONObject) {
        Log.d(TAG, "sendMessage(): $json")

        val cipher = Cipher.getInstance("AES/CFB8/NoPadding").apply { init(Cipher.ENCRYPT_MODE, cryptKeySpec) }
        val buffer = ByteArrayOutputStream()

        // TODO: header flags

        // store IV block first
        buffer.write(cipher.iv)

        // compress and encrypt
        GZIPOutputStream(CipherOutputStream(buffer, cipher)).apply {
            write(json.toString().toByteArray())
            close()
        }

        // append HMAC
        buffer.write(Mac.getInstance("HmacSHA256").apply { init(hmacKeySpec) }.doFinal(buffer.toByteArray()))

        // TODO: check maximum datagram size
        Log.d(TAG, "sending ${buffer.size()} bytes")
        socket.send(DatagramPacket(buffer.toByteArray(), buffer.size(), socket.remoteSocketAddress))
    }

    @SuppressLint("MissingPermission")
    override fun doWork(): Result {
        Log.d(TAG, "doWork()")

        val ts = System.currentTimeMillis()
        val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        // initialize keys
        with (MessageDigest.getInstance("SHA-256")) {
            val secret = prefs.getString("SecretKey", null)
            if (secret.isNullOrEmpty()) {
                Log.e(TAG, "No encryption key")
                return Result.failure()
            }

            val cryptKeyHash = digest(secret.toByteArray())
            cryptKeySpec = SecretKeySpec(cryptKeyHash, "AES") // encryption key = sha256(secret)
            hmacKeySpec = SecretKeySpec(digest(cryptKeyHash), "HmacSHA256") // hmac key = sha256(sha256(secret))
        }

        // verify config, prepare socket
        try {
            val hostName = prefs.getString("Host", null)
            if (hostName.isNullOrBlank()) {
                Log.e(TAG, "Server name not set")
                return Result.failure()
            }

            val portNumber = prefs.getString("Port", null)?.toIntOrNull()
            if (portNumber == null || portNumber < 1 || portNumber > 65535) {
                Log.e(TAG, "Server port not set or invalid")
                return Result.failure()
            }

            // TODO: try all addresses (IPv4/IPv6) on connection failure
            val hostAddress = InetAddress.getByName(hostName)
            Log.i(TAG, "Server address: $hostAddress")

            socket = DatagramSocket().apply { connect(hostAddress, portNumber) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not connect socket", e)
            return Result.failure()
        }

        // Simple ping
        try {
            sendMessage(JSONObject().put("ts", ts))
        } catch (e: Exception) {
            Log.e(TAG, "ActionPing failed", e)
            socket.close()
            return Result.retry() // defer work if this packet didn't get sent
        }

        // Battery info
        try {
            if (prefs.getBoolean("ActionBattery", false)) {
                sendMessage(
                    JSONObject()
                        .put("ts", ts)
                        .put("battery", JSONObject().apply {
                            (applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))?.apply {
                                put("health", getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
                                put("status", getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                                put("plug", getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
                                put("volt", getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
                                put("temp", getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
                                put("tech", getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
                                val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                                val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                                if (level >= 0 && scale > 0) {
                                    put("pct", roundValue(level.toDouble() / scale.toDouble() * 100, 2))
                                } else {
                                    Log.w(TAG, "Battery level unknown")
                                    put("pct", -1)
                                }
                            } ?: apply {
                                Log.w(TAG, "No battery information available")
                            }
                        })
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ActionBattery failed", e)
        }

        // TODO: NetworkInfo is deprecated. Combine data from NetworkCapabilities, TelephonyManager
        // Network connection status
        try {
            if (prefs.getBoolean("ActionConn", false)) {
                sendMessage(
                    JSONObject()
                        .put("ts", ts)
                        .put("conn_active", JSONObject().apply {
                            (applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?)
                                ?.activeNetworkInfo
                                ?.apply {
                                    put("type", typeName)
                                    put("subtype", subtypeName)
                                    put("connected", isConnected)
                                    put("available", isAvailable)
                                    put("roaming", isRoaming)
                                    put("failover", isFailover)
                                    put("extra", extraInfo)
                                    put("reason", reason)
                                } ?: apply {
                                    Log.d(TAG, "No connection information available")
                            }
                        })
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ActionConn failed", e)
        }

        // Cached GPS location
        try {
            if (prefs.getBoolean("ActionLocGPS", false)) {
                sendMessage(JSONObject()
                    .put("ts", ts)
                    .put("loc_gps", JSONObject().apply {
                        locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?.apply {
                                put("ts", time)
                                put("lat", latitude)
                                put("lon", longitude)
                                if (hasAltitude()) put("alt", roundValue(altitude, 4))
                                if (hasAccuracy()) put("acc", roundValue(accuracy.toDouble(), 4))
                                if (hasSpeed()) put("speed", roundValue(speed.toDouble(), 4))
                                if (hasBearing()) put("bearing", roundValue(bearing.toDouble(), 4))
                            } ?: apply {
                                Log.d(TAG, "No GPS location available")
                                // put("ts", -1)
                            }
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ActionLocGPS failed", e)
        }

        // Cached network location
        try {
            if (prefs.getBoolean("ActionLocNet", false)) {
                sendMessage(JSONObject()
                    .put("ts", ts)
                    .put("loc_net", JSONObject().apply {
                        locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                            ?.apply {
                                put("ts", time)
                                put("lat", latitude)
                                put("lon", longitude)
                                if (hasAltitude()) put("alt", roundValue(altitude, 4))
                                if (hasAccuracy()) put("acc", roundValue(accuracy.toDouble(), 4))
                                if (hasSpeed()) put("speed", roundValue(speed.toDouble(), 4))
                                if (hasBearing()) put("bearing", roundValue(bearing.toDouble(), 4))
                            } ?: apply {
                                Log.d(TAG, "No network location available")
                                // put("ts", -1)
                            }
                       }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ActionLocNet failed", e)
        }

        // Cached WIFI scan results
        try {
            if (prefs.getBoolean("ActionWifi", false)) {
                sendMessage(
                    JSONObject()
                        .put("ts", ts)
                        .put("wifi", JSONArray().apply {
                            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?)
                                ?.scanResults?.forEach { ap ->
                                    put(JSONObject().apply {
                                        put("BSSID", ap.BSSID)
                                        put("SSID", ap.SSID)
                                        put("freq", ap.frequency)
                                        put("level",ap.level)
                                        put("width", ap.channelWidth)
                                        put("caps", ap.capabilities)
                                        put("freq0", ap.centerFreq0.takeIf { it != 0 })
                                        put("freq1", ap.centerFreq1.takeIf { it != 0 })
                                        put("802.11mc", ap.is80211mcResponder.takeIf { it })
                                        put("passpoint", ap.isPasspointNetwork.takeIf { it })
                                        put("operator", ap.operatorFriendlyName.ifBlank { null })
                                        put("venue", ap.venueName.ifBlank { null })
                                        // put("_TBD_", ap.wifiStandard) // TODO: requires SDK 30+
                                    })
                            } ?: apply {
                                Log.d(TAG, "No wifi scan available")
                            }
                        })
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ActionWifi failed", e)
        }

        socket.close()
        return Result.success()
    }
}
