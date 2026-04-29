package be.mygod.vpnhotspot.manage

import android.os.Build
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import be.mygod.vpnhotspot.root.fixPath
import be.mygod.vpnhotspot.util.RootSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * Root NCM mode switches the live configfs gadget to `ffs.adb + ncm.usb0`, then lets Ethernet
 * tethering configure `usb0` because some devices do not expose NCM as a USB tethering type.
 */
object UsbTethering {
    const val KEY_MODE = "service.usbTetheringMode"
    private const val DOLLAR = '$'

    enum class Mode {
        System,
        Ncm,
        ;

        companion object {
            fun current() = try {
                valueOf(app.pref.getString(KEY_MODE, null) ?: "")
            } catch (_: IllegalArgumentException) {
                System
            }
        }
    }

    fun isActiveNcmInterface(iface: String?) = iface == "usb0"

    fun start(callback: TetheringManagerCompat.StartTetheringCallback) {
        val mode = if (Build.VERSION.SDK_INT >= 30) Mode.current() else Mode.System
        val useNcm = Build.VERSION.SDK_INT >= 30 && mode == Mode.Ncm
        Timber.i("USB tether start requested, sdk=${Build.VERSION.SDK_INT}, mode=$mode, useNcm=$useNcm")
        if (!useNcm) {
            Timber.i("Starting USB tethering with system USB mode")
            TetheringManagerCompat.startTethering(TetheringManagerCompat.TETHERING_USB, true, callback)
            return
        }
        startRootNcm(callback)
    }

    private fun startRootNcm(callback: TetheringManagerCompat.StartTetheringCallback) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                executeScript(applyNcmScript())
                TetheringManagerCompat.startTethering(TetheringManagerCompat.TETHERING_ETHERNET, true, callback)
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply NCM gadget function")
                callback.onException(e)
            }
        }
    }

    fun stop(usingNcm: Boolean, callback: TetheringManagerCompat.StopTetheringCallback) {
        if (Build.VERSION.SDK_INT < 30 || !usingNcm) {
            Timber.i("USB tether stop requested, usingNcm=$usingNcm, type=${TetheringManagerCompat.TETHERING_USB}")
            TetheringManagerCompat.stopTethering(TetheringManagerCompat.TETHERING_USB, callback)
            return
        }
        Timber.i("USB tether stop requested, usingNcm=true, types=${TetheringManagerCompat.TETHERING_ETHERNET},${TetheringManagerCompat.TETHERING_USB}")
        val stopCallback = object : TetheringManagerCompat.StopTetheringCallback {
            private var pending = 2
            private var firstError: Int? = null
            private var firstException: Exception? = null

            @Synchronized
            private fun finish(error: Int? = null, exception: Exception? = null) {
                if (firstError == null) firstError = error
                if (firstException == null) firstException = exception
                if (--pending != 0) return
                firstException?.let {
                    callback.onException(it)
                    return
                }
                firstError?.let {
                    callback.onStopTetheringFailed(it)
                    return
                }
                callback.onStopTetheringSucceeded()
            }

            override fun onStopTetheringSucceeded() = finish()
            override fun onStopTetheringFailed(error: Int) = finish(error = error)
            override fun onException(e: Exception) = finish(exception = e)
        }
        TetheringManagerCompat.stopTethering(TetheringManagerCompat.TETHERING_ETHERNET, stopCallback)
        TetheringManagerCompat.stopTethering(TetheringManagerCompat.TETHERING_USB, stopCallback)
    }

    private suspend fun executeScript(script: String) {
        try {
            executeProcess(listOf("su", "-M", "-c", script))
        } catch (e: Exception) {
            try {
                RootSession.use { it.exec(script) }
            } catch (eRoot: Exception) {
                eRoot.addSuppressed(e)
                throw eRoot
            }
        }
    }

    private suspend fun executeProcess(command: List<String>) = withContext(Dispatchers.IO) {
        val (exit, out, err) = coroutineScope {
            val process = ProcessBuilder(command).fixPath().start()
            val stdout = async { process.inputStream.bufferedReader().readText() }
            val stderr = async { process.errorStream.bufferedReader().readText() }
            Triple(process.waitFor(), stdout.await(), stderr.await())
        }
        if (exit != 0) {
            val message = buildString {
                append(command.joinToString(" ")).append(" exited with ").append(exit)
                if (out.isNotEmpty()) append('\n').append(out)
                if (err.isNotEmpty()) append("\n=== stderr ===\n").append(err)
            }
            throw IOException(message)
        }
    }

    private fun applyNcmScript(): String {
        return """
            G=/config/usb_gadget/g1
            C=${DOLLAR}(find ${DOLLAR}G/configs -maxdepth 1 -type d | grep -E "/configs/[^/]+${DOLLAR}" | head -n1)
            UDC=${DOLLAR}(ls /sys/class/udc | head -n1)
            echo "G=${DOLLAR}G"
            echo "C=${DOLLAR}C"
            echo "UDC=${DOLLAR}UDC"
            mkdir -p "${DOLLAR}G/functions/ncm.usb0"
            mkdir -p "${DOLLAR}G/functions/ecm.usb0"
            echo "" > "${DOLLAR}G/UDC"
            find "${DOLLAR}C" -maxdepth 1 -type l -exec rm -f {} \;
            ln -s "${DOLLAR}G/functions/ffs.adb" "${DOLLAR}C/f1"
            ln -s "${DOLLAR}G/functions/ncm.usb0" "${DOLLAR}C/f2"
            echo "${DOLLAR}UDC" > "${DOLLAR}G/UDC"
        """.trimIndent()
    }
}
