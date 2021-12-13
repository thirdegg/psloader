package com.thirdegg.psloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.Git
import android.widget.TextView
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import org.eclipse.jgit.transport.JschConfigSessionFactory
import org.eclipse.jgit.transport.OpenSshConfig
import org.eclipse.jgit.transport.SshSessionFactory
import java.io.*
import java.lang.Exception
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import android.animation.ValueAnimator
import android.view.animation.BounceInterpolator

import android.view.animation.LinearInterpolator

class MainActivity : AppCompatActivity(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext = job

    private var server: FileServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var payloadJob: Job? = null

    private var ipAddressText: TextView? = null
    private var payloadLayout: LinearLayout? = null
    private var sendPayloadButton: Button? = null

    private var connectedIp:String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipAddressText = findViewById(R.id.ip_addr)
        payloadLayout = findViewById(R.id.payload_layout)
        sendPayloadButton = findViewById(R.id.send_payload)

        launch(Dispatchers.IO) {

            try {
                withContext(Dispatchers.Main) {
                    ipAddressText?.text = getString(R.string.fetching_exploit)
                }
                try {
                    updateExploit()
                } catch (e: GitAPIException) {
                    withContext(Dispatchers.Main) {
                        ipAddressText?.text = getString(R.string.error_fetching_exploit)
                    }
                    e.printStackTrace()
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    ipAddressText?.text = getString(R.string.fetching_payload)
                }
                try {
                    updatePayload()
                } catch (e: FileNotFoundException) {
                    withContext(Dispatchers.Main) {
                        ipAddressText?.text = getString(R.string.fetching_payload_not_found)
                    }
                    return@launch
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        ipAddressText?.text = getString(R.string.fetching_payload_io_exception)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    ipAddressText?.text = getString(R.string.start_server)
                }
                server = FileServer(getExploitDir(), { newConnectionIp ->
                    if (connectedIp != newConnectionIp) {
                        connectedIp = newConnectionIp
                    } else {
                        return@FileServer
                    }
                    GlobalScope.launch(Dispatchers.Main) {

                        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 600
                            interpolator = BounceInterpolator()
                            addUpdateListener { animation ->
                                (payloadLayout?.layoutParams as LinearLayout.LayoutParams).weight = animation.animatedValue as Float
                                payloadLayout?.requestLayout()
                            }
                        }
                        animator.start()

                        ipAddressText?.text = getString(R.string.connection_detected)
                        sendPayloadButton?.text = getString(R.string.try_send, newConnectionIp, PAYLOAD_PORT.toString())
                        sendPayloadButton?.setOnClickListener {
                            tryPayload(newConnectionIp)
                        }
                    }
                }, SERVER_PORT)
                server?.start()

                val address = getDeviceIpAddress()?.toString()
                if (address != null) {
                    withContext(Dispatchers.Main) {
                        ipAddressText?.text = getString(R.string.go_to, getDeviceIpAddress()?.hostAddress, SERVER_PORT.toString())
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        ipAddressText?.text = getString(R.string.address_not_found)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun tryPayload(connectionIp: String) {
        payloadJob?.cancel()
        payloadJob = launch(Dispatchers.IO) {
            payload(connectionIp, PAYLOAD_PORT, getPayloadPath())
        }
    }

    private fun payload(connectionIp: String, port: Int, payloadFile: File) {
        val sender = PayloadSender(connectionIp, port, payloadFile)
        try {
            sender.send()
        } catch (e1: UnknownHostException) {
            e1.printStackTrace()
        } catch (e1: IOException) {
            e1.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    private fun getExploitDir() = File(applicationContext?.cacheDir, "exploit/")
    private fun getPayloadPath() = File(applicationContext?.cacheDir, "payload.bin")

    private fun getDeviceIpAddress(): InetAddress? {

        val wifi = applicationContext?.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val connManager =
            applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        val wifiInfo = connManager?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
        if (wifi?.isWifiEnabled != true) return null
        if (wifiInfo?.isConnected != true) return null

        // default to Android localhost
        var result = InetAddress.getByName("10.0.0.2")
        try {
            // figure out our wifi address, otherwise bail
            val wifiinfo = wifi.connectionInfo
            val intaddr = wifiinfo.ipAddress
            val byteaddr = byteArrayOf(
                (intaddr and 0xff).toByte(), (intaddr shr 8 and 0xff).toByte(),
                (intaddr shr 16 and 0xff).toByte(), (intaddr shr 24 and 0xff).toByte()
            )
            result = InetAddress.getByAddress(byteaddr)
        } catch (ex: UnknownHostException) {
            ex.printStackTrace()
        }

        multicastLock = wifi.createMulticastLock(javaClass.name)?.apply {
            setReferenceCounted(true)
            acquire()
        }

        return result
    }

    private fun updatePayload() {
        val payloadUrl = getString(R.string.payload_url)
        if (getPayloadPath().exists()) return
        downloadFile(payloadUrl, getPayloadPath())
    }

    private fun downloadFile(url: String, outputFile: File) {
        URL(url).openStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun updateExploit() {
        val repoUrl = getString(R.string.exploit_url)
        if (getExploitDir().exists()) return
        SshSessionFactory.setInstance(object : JschConfigSessionFactory() {
            override fun configure(hc: OpenSshConfig.Host?, session: Session?) {
                session?.setConfig("StrictHostKeyChecking", "no")
            }
        })
        Git.cloneRepository()
            .setURI(repoUrl)
            .setDirectory(getExploitDir())
            .call()

    }


    companion object {
        const val SERVER_PORT = 8000
        const val PAYLOAD_PORT = 9020
    }
}