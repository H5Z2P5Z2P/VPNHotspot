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
 * Root NCM mode switches the live configfs gadget to `ffs.adb + ncm.<usbN>`, then lets Ethernet
 * tethering configure that interface because some devices do not expose NCM as a USB tethering type.
 */
object UsbTethering {
    const val KEY_MODE = "service.usbTetheringMode"
    const val KEY_NCM_INTERFACE = "service.usbTethering.ncmInterface"
    const val DEFAULT_NCM_INTERFACE = "usb0"
    private const val DOLLAR = '$'
    private val ncmInterfaceRegex = Regex("usb\\d+")

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

    fun isValidNcmInterface(iface: String) = ncmInterfaceRegex.matches(iface)

    fun ncmInterface() = app.pref.getString(KEY_NCM_INTERFACE, null)?.takeIf(::isValidNcmInterface)
        ?: DEFAULT_NCM_INTERFACE

    fun isActiveNcmInterface(iface: String?) = Build.VERSION.SDK_INT >= 30 && Mode.current() == Mode.Ncm &&
            iface == ncmInterface()

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
                val iface = ncmInterface()
                Timber.i("Applying NCM gadget function for $iface")
                executeScript(applyNcmScript(iface))
                TetheringManagerCompat.startTethering(TetheringManagerCompat.TETHERING_ETHERNET, true,
                        object : TetheringManagerCompat.StartTetheringCallback {
                    override fun onTetheringStarted() = callback.onTetheringStarted()
                    override fun onTetheringFailed(error: Int?) {
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                executeScript(cleanupNcmScript())
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to clean up NCM gadget after tethering failure")
                            }
                            callback.onTetheringFailed(error)
                        }
                    }
                    override fun onException(e: Exception) {
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                executeScript(cleanupNcmScript())
                            } catch (eCleanup: Exception) {
                                e.addSuppressed(eCleanup)
                            }
                            callback.onException(e)
                        }
                    }
                })
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply NCM gadget function")
                try {
                    executeScript(cleanupNcmScript())
                } catch (eCleanup: Exception) {
                    e.addSuppressed(eCleanup)
                }
                callback.onException(e)
            }
        }
    }

    fun stop(usingNcm: Boolean, callback: TetheringManagerCompat.StopTetheringCallback) {
        if (Build.VERSION.SDK_INT < 30 || !usingNcm && Mode.current() != Mode.Ncm) {
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
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        executeScript(cleanupNcmScript())
                    } catch (e: Exception) {
                        firstException?.addSuppressed(e) ?: run {
                            callback.onException(e)
                            return@launch
                        }
                    }
                    firstException?.let {
                        callback.onException(it)
                        return@launch
                    }
                    firstError?.let {
                        callback.onStopTetheringFailed(it)
                        return@launch
                    }
                    callback.onStopTetheringSucceeded()
                }
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

    private fun applyNcmScript(iface: String): String {
        return """
            set -e
            IFACE=$iface
            case "${DOLLAR}IFACE" in
                usb*) ;;
                *) echo "Invalid NCM interface: ${DOLLAR}IFACE" >&2; exit 64 ;;
            esac
            case "${DOLLAR}{IFACE#usb}" in
                ''|*[!0-9]*) echo "Invalid NCM interface: ${DOLLAR}IFACE" >&2; exit 64 ;;
            esac
            G=/config/usb_gadget/g1
            C=
            for d in "${DOLLAR}G"/configs/*; do
                [ -d "${DOLLAR}d" ] || continue
                C=${DOLLAR}d
                break
            done
            UDC=
            for u in /sys/class/udc/*; do
                [ -e "${DOLLAR}u" ] || continue
                UDC=${DOLLAR}{u##*/}
                break
            done
            FUNC=ncm.${DOLLAR}IFACE
            [ -n "${DOLLAR}C" ] || { echo "USB gadget config not found" >&2; exit 1; }
            [ -n "${DOLLAR}UDC" ] || { echo "USB device controller not found" >&2; exit 1; }
            echo "G=${DOLLAR}G"
            echo "C=${DOLLAR}C"
            echo "UDC=${DOLLAR}UDC"
            echo "IFACE=${DOLLAR}IFACE"
            echo "" > "${DOLLAR}G/UDC"
            for f in "${DOLLAR}C"/*; do
                [ -L "${DOLLAR}f" ] || continue
                rm -f "${DOLLAR}f"
            done
            for f in "${DOLLAR}G"/functions/ncm.usb[0-9]* "${DOLLAR}G"/functions/ecm.usb[0-9]*; do
                [ -e "${DOLLAR}f" ] || continue
                rmdir "${DOLLAR}f" 2>/dev/null || true
            done
            mkdir -p "${DOLLAR}G/functions/${DOLLAR}FUNC"
            ln -s "${DOLLAR}G/functions/ffs.adb" "${DOLLAR}C/f1"
            ln -s "${DOLLAR}G/functions/${DOLLAR}FUNC" "${DOLLAR}C/f2"
            echo "${DOLLAR}UDC" > "${DOLLAR}G/UDC"
            i=0
            while [ "${DOLLAR}i" -lt 50 ]; do
                ACTUAL=${DOLLAR}(cat "${DOLLAR}G/functions/${DOLLAR}FUNC/ifname" 2>/dev/null || true)
                [ -n "${DOLLAR}ACTUAL" ] && [ "${DOLLAR}ACTUAL" != "(unnamed net_device)" ] && break
                i=${DOLLAR}((i + 1))
                sleep 0.1
            done
            [ -n "${DOLLAR}ACTUAL" ] && [ "${DOLLAR}ACTUAL" != "(unnamed net_device)" ] || {
                echo "NCM network interface was not created" >&2
                exit 1
            }
            if [ "${DOLLAR}ACTUAL" != "${DOLLAR}IFACE" ]; then
                /system/bin/ip link set dev "${DOLLAR}ACTUAL" down
                /system/bin/ip link set dev "${DOLLAR}ACTUAL" name "${DOLLAR}IFACE"
            fi
            /system/bin/ip link set dev "${DOLLAR}IFACE" up
        """.trimIndent()
    }

    private fun cleanupNcmScript(): String {
        return """
            set -e
            G=/config/usb_gadget/g1
            C=
            for d in "${DOLLAR}G"/configs/*; do
                [ -d "${DOLLAR}d" ] || continue
                C=${DOLLAR}d
                break
            done
            UDC=
            for u in /sys/class/udc/*; do
                [ -e "${DOLLAR}u" ] || continue
                UDC=${DOLLAR}{u##*/}
                break
            done
            [ -n "${DOLLAR}C" ] || { echo "USB gadget config not found" >&2; exit 1; }
            [ -n "${DOLLAR}UDC" ] || { echo "USB device controller not found" >&2; exit 1; }
            echo "" > "${DOLLAR}G/UDC"
            for f in "${DOLLAR}C"/*; do
                [ -L "${DOLLAR}f" ] || continue
                rm -f "${DOLLAR}f"
            done
            ln -s "${DOLLAR}G/functions/ffs.adb" "${DOLLAR}C/f1"
            for f in "${DOLLAR}G"/functions/ncm.usb[0-9]* "${DOLLAR}G"/functions/ecm.usb[0-9]*; do
                [ -e "${DOLLAR}f" ] || continue
                rmdir "${DOLLAR}f" 2>/dev/null || true
            done
            echo "${DOLLAR}UDC" > "${DOLLAR}G/UDC"
        """.trimIndent()
    }
}
