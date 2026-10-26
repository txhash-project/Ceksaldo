package id.opensource.ceksaldotol

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NfcJsBridge(), "AndroidNfcBridge")

        webView.loadUrl("file:///android_asset/index.html")

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
            adapter.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag?.let { handleNfcTag(it) }
    }

    private fun handleNfcTag(tag: Tag) {
        val uidHex = tag.id.joinToString(" ") { String.format("%02X", it) }
        var cardType = "Kartu E-Money NFC"
        var balance = -1

        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            try {
                isoDep.connect()
                // Select Mandiri e-Money Applet AID: A0 00 00 00 03 00 00 00
                val selectEmoney = byteArrayOf(
                    0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
                    0x08.toByte(), 0xA0.toByte(), 0x00.toByte(), 0x00.toByte(),
                    0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
                )
                val resp = isoDep.transceive(selectEmoney)
                if (resp != null && resp.size >= 2 &&
                    resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0x00.toByte()
                ) {
                    cardType = "Mandiri e-Money"
                    // Read Balance APDU: 00 B5 00 00 0A
                    val readBal = byteArrayOf(0x00, 0xB5.toByte(), 0x00, 0x00, 0x0A)
                    val balResp = isoDep.transceive(readBal)
                    if (balResp != null && balResp.size >= 6) {
                        balance = ByteBuffer.wrap(balResp, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    }
                } else {
                    // Check BNI TapCash AID: A0 00 00 06 72 30 10
                    val selectTapCash = byteArrayOf(
                        0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
                        0xA0.toByte(), 0x00, 0x00, 0x06, 0x72, 0x30, 0x10
                    )
                    val tcResp = isoDep.transceive(selectTapCash)
                    if (tcResp != null && tcResp.size >= 2 && tcResp[tcResp.size - 2] == 0x90.toByte()) {
                        cardType = "BNI TapCash"
                    }
                }
                isoDep.close()
            } catch (e: Exception) {
                try { isoDep.close() } catch (_: Exception) {}
            }
        }

        val jsCall = "window.onCardDetectedFromNative('$uidHex', '$cardType', $balance);"
        webView.post { webView.evaluateJavascript(jsCall, null) }
    }

    inner class NfcJsBridge {
        @JavascriptInterface
        fun triggerNfcCheck() {
            runOnUiThread {
                if (nfcAdapter == null) {
                    Toast.makeText(this@MainActivity, "Perangkat ini tidak memiliki sensor NFC", Toast.LENGTH_LONG).show()
                } else if (nfcAdapter?.isEnabled == false) {
                    Toast.makeText(this@MainActivity, "Silakan aktifkan NFC di Pengaturan HP Anda", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "NFC Siap! Tempelkan kartu di belakang HP", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
