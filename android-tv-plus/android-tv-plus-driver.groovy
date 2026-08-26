/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** Android TV+ Driver **
 *
 * Native Hubitat driver for ADB-over-TCP devices (Fire TV, Shield TV, Android TV variants).
 * Uses a persistent authenticated ADB socket and one-shot shell channels (`shell:<cmd>\0`) for command execution.
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import groovy.transform.Field
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

// ADB protocol
@Field static final int CMD_CNXN = 0x4e584e43
@Field static final int CMD_AUTH = 0x48545541
@Field static final int CMD_OPEN = 0x4e45504f
@Field static final int CMD_OKAY = 0x59414b4f
@Field static final int CMD_CLSE = 0x45534c43
@Field static final int CMD_WRTE = 0x45545257

@Field static final int AUTH_TOKEN = 1
@Field static final int AUTH_SIGNATURE = 2
@Field static final int AUTH_RSAPUBLICKEY = 3

@Field static final int ADB_VERSION = 0x01000000
@Field static final int MAX_PAYLOAD = 4096
@Field static final int LOCAL_ID = 1

// states
@Field static final String ST_IDLE = "IDLE"
@Field static final String ST_CONNECTING = "CONNECTING"
@Field static final String ST_AUTH_WAIT = "AUTH_WAIT"
@Field static final String ST_AUTH_SIGNATURE_WAIT = "AUTH_SIGNATURE_WAIT"
@Field static final String ST_AUTH_PUBKEY_WAIT = "AUTH_PUBKEY_WAIT"
@Field static final String ST_CONNECTED = "CONNECTED"
@Field static final String ST_CMD_OPENING = "CMD_OPENING"
@Field static final String ST_CMD_RUNNING = "CMD_RUNNING"

@Field static final Map KEY = [
    HOME:3, BACK:4, MENU:82,
    DPAD_UP:19, DPAD_DOWN:20, DPAD_LEFT:21, DPAD_RIGHT:22, DPAD_CENTER:23,
    VOLUME_UP:24, VOLUME_DOWN:25, VOLUME_MUTE:164,
    POWER:26, WAKEUP:224, SLEEP:223,
    PLAY_PAUSE:85, STOP:86, NEXT:87, PREV:88,
    REWIND:89, FF:90, PLAY:126, PAUSE:127,
    ENTER:66, ESCAPE:111
]

@Field static final Map APPS = [
    "com.netflix.ninja": [name: "Netflix", aliases: ["netflix"]],
    "com.amazon.firebat": [name: "Prime Video", aliases: ["primevideo", "prime video", "prime%20video"], launchCmd: "am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n com.amazon.firebat/com.amazon.firebatcore.deeplink.DeepLinkRoutingActivity"],
    "com.disney.disneyplus": [name: "Disney+", aliases: ["disney", "disneyplus", "disney+"]],
    "com.hbo.hbonow": [name: "HBO Max", aliases: ["hbomax", "hbo max", "hbo%20max"], launchCmd: "am start -n com.hbo.hbonow/com.wbd.beam.BeamActivity"],
    "com.amazon.firetv.youtube": [name: "YouTube", aliases: ["youtube"]],
    "com.apple.atve.amazon.appletv": [name: "Apple TV", aliases: ["appletv", "apple tv"], launchCmd: "am start -n com.apple.atve.amazon.appletv/.MainActivity"],
    "com.spotify.music": [name: "Spotify", aliases: ["spotify"]],
    "com.plexapp.android": [name: "Plex", aliases: ["plex"]],
    "tv.twitch.android.app": [name: "Twitch", aliases: ["twitch"]],
    "org.xbmc.kodi": [name: "Kodi", aliases: ["kodi", "xbmc"]],
    "com.google.android.youtube.tvunplugged": [name: "YouTube TV", aliases: ["youtubetv", "youtube tv", "youtube%20tv"]],
    "org.smarttube.beta": [name: "SmartTube beta", aliases: ["smarttube beta"]]
]

// runtime, cross-callback protocol state (must not live in `state`)
@Field static final Map rxBuf = new ConcurrentHashMap()           // device.id -> hex accumulator
@Field static final Map connState = new ConcurrentHashMap()       // device.id -> state string
@Field static final Map waitingForAuth = new ConcurrentHashMap()   // device.id -> bool
@Field static final Map remoteId = new ConcurrentHashMap()         // device.id -> int
@Field static final Map activeCmd = new ConcurrentHashMap()        // device.id -> command string
@Field static final Map activeOut = new ConcurrentHashMap()        // device.id -> command output text
@Field static final Map cmdQueues = new ConcurrentHashMap()        // device.id -> List<String>
@Field static final Map lastRxMs = new ConcurrentHashMap()         // device.id -> epoch millis
@Field static final Map failCount = new ConcurrentHashMap()        // device.id -> consecutive failures
@Field static final Map cmdTimeoutCount = new ConcurrentHashMap()  // device.id -> consecutive command timeouts
@Field static final Map manualDisconnect = new ConcurrentHashMap() // device.id -> bool
@Field static final Map nextConnectMs = new ConcurrentHashMap()    // device.id -> epoch millis of pending reconnect

@Field static final int MAX_QUEUED_COMMANDS = 20

// One round trip answers both "is the screen on" and "what app is in front". Devices differ on which
// line dumpsys power prints, so accept either.
@Field static final String STATUS_CMD = "dumpsys power 2>/dev/null | grep -m1 -E 'mWakefulness=|Display Power: state='; dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp|topResumedActivity'"
@Field static final String KEEPALIVE_CMD = "echo 1"

metadata {
    definition(
        name: "Android TV+ Driver",
        namespace: "jpage4500",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/android-tv-plus/android-tv-plus-driver.groovy"
    ) {
        capability "Switch"
        capability "Refresh"
        capability "Initialize"

        command "home"
        command "back"
        command "menu"
        command "wakeUp"
        command "sleepDevice"
        command "dpadUp"
        command "dpadDown"
        command "dpadLeft"
        command "dpadRight"
        command "select"
        command "enter"
        command "volumeUp"
        command "volumeDown"
        command "mute"
        command "play"
        command "pause"
        command "playPause"
        command "stop"
        command "fastForward"
        command "rewind"
        command "nextTrack"
        command "previousTrack"
        command "launchApp", [[name:"PackageName*", type:"STRING", description:"Package name, e.g. com.netflix.ninja"]]
        command "appOpenByName", [[name:"AppName*", type:"STRING", description:"Any name from the built-in list (Netflix, Prime Video, Disney+, YouTube, YouTube TV, Apple TV, HBO Max, Spotify, Plex, Twitch, Kodi) or from the app's Custom apps setting"]]
        command "sendKeyEvent", [[name:"KeyCode*", type:"NUMBER", description:"Android key code"]]
        command "sendShellCommand", [[name:"Command*", type:"STRING", description:"ADB shell command"]]
        command "getCurrentApp"
        command "generateNewKey"
        command "disconnect"
        command "reconnect"

        // enum rather than string so the values are validated on the way out and Rule Machine offers a
        // dropdown instead of a free-text box
        attribute "adbStatus", "enum", ["disconnected","connecting","connected","reconnecting","waiting_authorization","key_error","configuration_error"]
        attribute "connectionStatus", "enum", ["idle","online","offline","disconnected"]
        attribute "currentApp", "string"
        attribute "currentAppName", "string"
        // Android's mWakefulness, lowercased to match every other attribute value in this driver
        attribute "status", "enum", ["awake","asleep","dreaming","dozing","unknown"]
        attribute "refreshTime", "number"
    }

    preferences {
        input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

// ============================================================================
// lifecycle
// ============================================================================
def installed() {
    initialize()
}

def updated() {
    initialize()
}

def uninstalled() {
    cleanupRuntime()
    try { interfaces.rawSocket.close() } catch (ignored) {}
}

def initialize() {
    String key = devKey()
    unschedule("connectTask")
    unschedule("connectWatchdog")
    unschedule("authPubkeyTimeout")
    unschedule("commandWatchdog")
    unschedule("healthCheck")
    unschedule("pollTask")
    try { interfaces.rawSocket.close() } catch (ignored) {}

    connState[key] = ST_IDLE
    waitingForAuth[key] = false
    remoteId[key] = 0
    activeCmd.remove(key)
    activeOut.remove(key)
    rxBuf[key] = ""
    lastRxMs[key] = now()
    failCount[key] = 0
    cmdTimeoutCount[key] = 0
    manualDisconnect[key] = false
    nextConnectMs.remove(key)

    ensureKeyPair()
    sendEventIfChanged("adbStatus", "disconnected")
    sendEventIfChanged("connectionStatus", "idle")
    sendEventIfChanged("currentApp", "unknown")
    sendEventIfChanged("currentAppName", "unknown")
    sendEventIfChanged("status", "unknown")
    // a Switch capability with no value at all breaks dashboards and rules; an unreachable TV reads as off,
    // and the first status query after connecting corrects it
    if (device.currentValue("switch") == null) sendEvent(name: "switch", value: "off")
    setPollInterval((state.pollSec ?: 30) as Integer)
    runIn(2, "connectTask")
    runIn(60, "healthCheck")
}

// ============================================================================
// app / parent integration
// ============================================================================
def setPollInterval(seconds) {
    Integer sec = (seconds ?: 30) as Integer
    if (sec < 0) sec = 0
    if (sec > 900) sec = 900
    state.pollSec = sec
    sendEventIfChanged("refreshTime", sec)
    schedulePolling()
}

// Each device schedules its own refresh instead of the app walking every child on one thread, so a device
// that stops answering can only ever delay itself. The start offset is derived from the device id so a
// house full of TVs doesn't poll in lockstep.
private void schedulePolling() {
    unschedule("pollTask")
    Integer sec = (state.pollSec ?: 30) as Integer
    if (sec < 1) return
    int offset = Math.abs(devKey().hashCode() % 60)
    if (sec < 60) {
        schedule("${offset % sec}/${sec} * * * * ?", "pollTask")
    } else {
        schedule("${offset} 0/${(sec / 60) as Integer} * * * ?", "pollTask")
    }
}

def pollTask() { refresh() }

def setDebug(flag) {
    state.debugOutput = (flag as Boolean)
}

// Custom app catalog, pushed down from the app's Settings section (see parseCustomApps for the format).
// Kept per-child in state rather than merged into APPS, because APPS is @Field static and therefore shared
// by every child device on the hub.
def setCustomApps(String text) {
    Map parsed = parseCustomApps(text)
    if (parsed) {
        state.customApps = parsed
    } else {
        state.remove("customApps")
    }
    logDebug("custom apps: ${parsed.size()} (${parsed.collect { k, v -> v.name }.join(', ')})")
    // whatever is on screen was named against the old catalog; re-resolve now rather than at the next poll
    String pkg = device.currentValue("currentApp")
    if (!isEmpty(pkg) && pkg != "unknown") sendEventIfChanged("currentAppName", appNameForPackage(pkg))
}

// ============================================================================
// core commands
// ============================================================================
def refresh() {
    String key = devKey()
    if (manualDisconnect[key] == true) return
    ensureConnected(false)
    // Nothing to ask a device we aren't talking to. Queuing it anyway just parks a command for the length
    // of the reconnect backoff -- and CNXN enqueues a status query on connect regardless.
    if (stateOf(key) != ST_IDLE) enqueueStatusQuery()
}

def disconnect() {
    String key = devKey()
    manualDisconnect[key] = true
    closeSocket("disconnected", "disconnected")
}

def reconnect() {
    String key = devKey()
    manualDisconnect[key] = false
    waitingForAuth[key] = false
    failCount[key] = 0          // a hand-driven reconnect starts a fresh backoff ladder
    cmdTimeoutCount[key] = 0
    closeSocket("idle", "disconnected")
    runInMillis(150, "connectTask")
}

def sendShellCommand(String cmd) {
    sendShell(cmd)
}

def getCurrentApp() {
    enqueueStatusQuery()
}

def generateNewKey() {
    state.adbPublicKey = null
    state.adbKeyN = null
    state.adbKeyD = null
    ensureKeyPair()
    logWarn("Generated a new ADB key pair (${keyFingerprint()}); reconnecting so the TV prompts to authorize it.")
    // without this the new key sits unused until someone hits reconnect, so no dialog ever appears
    reconnect()
}

private void enqueueStatusQuery() {
    sendShell(STATUS_CMD, true)
}

// returns true when the command was accepted for delivery, so callers that mirror device state
// (on/off) don't claim a change that was never sent
private boolean sendShell(String shellCmd, boolean dedupe = false) {
    if (isEmpty(shellCmd)) return false
    String key = devKey()
    if (manualDisconnect[key] == true) {
        // silently queueing these made a dashboard button look broken; say why instead
        logWarn("Ignoring '${shellCmd.take(40)}': device is manually disconnected, run reconnect first")
        return false
    }
    if (dedupe && isQueuedOrActive(key, shellCmd)) return true
    enqueueCommand(key, shellCmd)
    ensureConnected(true)
    runNextCommand()
    return true
}

private void ensureConnected(boolean userInitiated) {
    String key = devKey()
    if (stateOf(key) != ST_IDLE) return
    if (waitingForAuth[key] == true && !userInitiated) return
    if (!userInitiated) {
        // don't stomp on an in-progress reconnect backoff with an immediate retry
        Long next = nextConnectMs[key] as Long
        if (next != null && now() < next) return
    }
    runInMillis(100, "connectTask")
}

// ============================================================================
// ADB connection and protocol
// ============================================================================
def connectTask() {
    String key = devKey()
    if (manualDisconnect[key] == true) return
    if (stateOf(key) != ST_IDLE) return

    String ip = getDataValue("ip")
    Integer port = asPort(getDataValue("port"))
    if (isEmpty(ip)) {
        // expected for a beat right after the parent creates the device; back off instead of giving up
        int n = ((failCount[key] ?: 0) as Integer) + 1
        failCount[key] = n
        int delay = Math.min(15 * n, 300)
        if (n == 1) logWarn("No device IP set yet; retrying in ${delay}s")
        else logDebug("Still no device IP; retrying in ${delay}s")
        sendEventIfChanged("adbStatus", "configuration_error")
        sendEventIfChanged("connectionStatus", "offline")
        nextConnectMs[key] = now() + (delay * 1000L)
        runIn(delay, "connectTask")
        return
    }

    if (!state.adbPublicKey || !state.adbKeyN || !state.adbKeyD) {
        ensureKeyPair()
        if (!state.adbPublicKey || !state.adbKeyN || !state.adbKeyD) {
            sendEventIfChanged("adbStatus", "key_error")
            return
        }
    }

    setState(key, ST_CONNECTING)
    remoteId[key] = 0
    rxBuf[key] = ""
    nextConnectMs.remove(key)
    sendEventIfChanged("adbStatus", "connecting")

    try {
        interfaces.rawSocket.connect(ip, port, byteInterface: true, timeout: 5000)
        setState(key, ST_AUTH_WAIT)
        sendAdbConnect()
        unschedule("connectWatchdog")
        runIn(20, "connectWatchdog")
    } catch (Exception ex) {
        onFailure("connect failed: ${ex.message}")
    }
}

def socketStatus(String message) {
    logDebug("socketStatus: ${message}")
    if (message?.toUpperCase()?.contains("CLOSED") || message?.toUpperCase()?.contains("ERROR")) {
        onFailure("socket closed: ${message}")
    }
}

def parse(String message) {
    String key = devKey()
    lastRxMs[key] = now()

    String chunk = message?.toUpperCase() ?: ""
    if (isEmpty(chunk)) return

    String buf = (rxBuf[key] ?: "") + chunk
    if (buf.length() > 262144) {
        logWarn("RX buffer overflow; resetting parser buffer")
        buf = ""
    }

    int guard = 0
    while (buf.length() >= 48 && guard < 400) {
        guard++
        byte[] hdr
        try {
            hdr = buf.substring(0, 48).decodeHex()
        } catch (Exception ex) {
            buf = buf.length() > 2 ? buf.substring(2) : ""
            continue
        }

        int cmd = (int) readInt32LE(hdr, 0)
        int arg0 = (int) readInt32LE(hdr, 4)
        int arg1 = (int) readInt32LE(hdr, 8)
        int dataLen = (int) readInt32LE(hdr, 12)
        long crc = readInt32LE(hdr, 16) & 0xFFFFFFFFL
        long magic = readInt32LE(hdr, 20) & 0xFFFFFFFFL
        long expectedMagic = (cmd ^ 0xFFFFFFFFL) & 0xFFFFFFFFL

        if (dataLen < 0 || dataLen > 131072 || magic != expectedMagic) {
            logWarn("Invalid ADB frame header; resyncing parser")
            buf = buf.length() > 2 ? buf.substring(2) : ""
            continue
        }

        int totalHex = 48 + (dataLen * 2)
        if (buf.length() < totalHex) break

        byte[] data = new byte[0]
        if (dataLen > 0) {
            try {
                data = buf.substring(48, totalHex).decodeHex()
            } catch (Exception ex) {
                logWarn("Invalid ADB payload encoding; dropping frame")
                buf = buf.substring(totalHex)
                continue
            }
        }
        buf = buf.substring(totalHex)

        // adbd only fills in data_check when the negotiated protocol is < 0x01000001, and it never
        // verifies the value it receives. Treat a mismatch as informational -- dropping the frame here
        // silently kills the AUTH handshake.
        long expectedSum = adbChecksum(data)
        if (crc != 0L && crc != expectedSum) {
            logDebug("checksum mismatch on ${cmdName(cmd)} frame (got ${crc}, computed ${expectedSum}); processing anyway")
        }
        handleAdbMessage(cmd, arg0, arg1, data)
    }
    rxBuf[key] = buf
}

private void handleAdbMessage(int cmd, int arg0, int arg1, byte[] data) {
    String key = devKey()
    String st = stateOf(key)

    switch (cmd) {
        case CMD_CNXN:
            unschedule("connectWatchdog")
            unschedule("authPubkeyTimeout")
            waitingForAuth[key] = false
            setState(key, ST_CONNECTED)
            failCount[key] = 0
            cmdTimeoutCount[key] = 0
            sendEventIfChanged("adbStatus", "connected")
            sendEventIfChanged("connectionStatus", "online")
            logInfo("ADB connected (${getDataValue('ip')}:${asPort(getDataValue('port'))}, key ${keyFingerprint()})")
            notifyParent()
            enqueueStatusQuery()
            runNextCommand()
            break

        case CMD_AUTH:
            if (arg0 != AUTH_TOKEN) break

            if (st == ST_AUTH_WAIT) {
                byte[] sig = signWithPrivateKey(data)
                if (sig) {
                    setState(key, ST_AUTH_SIGNATURE_WAIT)
                    sendAdbMsg(CMD_AUTH, AUTH_SIGNATURE, 0, sig)
                    logDebug("Sent AUTH signature")
                    unschedule("connectWatchdog")
                    runIn(20, "connectWatchdog")
                } else {
                    sendPublicKeyForAuth()
                }
            } else if (st == ST_AUTH_SIGNATURE_WAIT || st == ST_AUTH_PUBKEY_WAIT) {
                sendPublicKeyForAuth()
            }
            break

        case CMD_OKAY:
            if (st == ST_CMD_OPENING) {
                remoteId[key] = arg0
                setState(key, ST_CMD_RUNNING)
            }
            break

        case CMD_WRTE:
            sendAdbMsg(CMD_OKAY, LOCAL_ID, arg0, new byte[0])
            if (data && data.length > 0) {
                String txt = sanitizeText(data)
                if (!isEmpty(txt)) {
                    String prev = (activeOut[key] ?: "") as String
                    String next = (prev + "\n" + txt).trim()
                    if (next.length() > 12000) next = next.takeRight(12000)
                    activeOut[key] = next
                }
            }
            break

        case CMD_CLSE:
            // Closing a shell stream is a two-way CLSE exchange, so a trailing CLSE for a stream we've
            // already finished is routine. The protocol says to ignore an unknown stream, not answer it
            // -- answering just prompts another CLSE back.
            if (activeCmd[key] || st == ST_CMD_OPENING || st == ST_CMD_RUNNING) {
                if (arg0 > 0) {
                    try { sendAdbMsg(CMD_CLSE, LOCAL_ID, arg0, new byte[0]) } catch (ignored) {}
                }
                completeActiveCommand()
            }
            break

        default:
            logDebug("Unhandled ADB command ${cmdName(cmd)}")
            break
    }
}

private void sendPublicKeyForAuth() {
    String key = devKey()
    waitingForAuth[key] = true
    setState(key, ST_AUTH_PUBKEY_WAIT)
    sendAdbMsg(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, state.adbPublicKey.bytes)
    sendEventIfChanged("adbStatus", "waiting_authorization")
    sendEventIfChanged("connectionStatus", "offline")
    logWarn("TV requires authorization for key ${keyFingerprint()}. Check 'Always allow' on screen.")
    unschedule("authPubkeyTimeout")
    runIn(90, "authPubkeyTimeout")
    unschedule("connectWatchdog")
}

def connectWatchdog() {
    String key = devKey()
    String st = stateOf(key)
    if (st in [ST_CONNECTING, ST_AUTH_WAIT, ST_AUTH_SIGNATURE_WAIT]) {
        onFailure("connect/auth timed out")
    }
}

def authPubkeyTimeout() {
    String key = devKey()
    if (stateOf(key) == ST_AUTH_PUBKEY_WAIT) {
        onFailure("authorization timed out", 300)
    }
}

private void runNextCommand() {
    String key = devKey()
    if (stateOf(key) != ST_CONNECTED) return
    if (activeCmd[key]) return
    String nextCmd = dequeueCommand(key)
    if (!nextCmd) return

    activeCmd[key] = nextCmd
    activeOut[key] = ""
    remoteId[key] = 0
    setState(key, ST_CMD_OPENING)
    sendAdbMsg(CMD_OPEN, LOCAL_ID, 0, ("shell:${nextCmd}\0").bytes)
    unschedule("commandWatchdog")
    runIn(12, "commandWatchdog")
}

def commandWatchdog() {
    String key = devKey()
    String cmd = activeCmd[key] as String
    if (!cmd) return
    activeCmd.remove(key)
    activeOut.remove(key)
    remoteId[key] = 0
    int n = ((cmdTimeoutCount[key] ?: 0) as Integer) + 1
    cmdTimeoutCount[key] = n
    // Only retry twice. Re-queueing at the front unconditionally means a command that can never finish
    // (bad shell syntax, a stream the device never closes) blocks every command behind it forever.
    if (n < 3) {
        requeueFront(key, cmd)
    } else {
        logWarn("Dropping command after ${n} timeouts: ${cmd.take(60)}")
    }
    onFailure("command timed out", null, n >= 3)
}

private void completeActiveCommand() {
    String key = devKey()
    unschedule("commandWatchdog")

    String cmd = activeCmd[key] as String
    String out = (activeOut[key] ?: "") as String
    activeCmd.remove(key)
    activeOut.remove(key)
    remoteId[key] = 0
    setState(key, ST_CONNECTED)

    if (!isEmpty(cmd)) {
        cmdTimeoutCount[key] = 0
        if (cmd == STATUS_CMD) {
            parsePowerState(out)
            parseCurrentApp(out)
        } else {
            logDebug("shell output (${cmd.take(40)}): ${out.take(180)}")
        }
    }
    runNextCommand()
}

private void parsePowerState(String output) {
    if (isEmpty(output)) return
    String wake = null
    def m = (output =~ 'mWakefulness=([A-Za-z]+)')
    if (m) {
        wake = m[0][1].toLowerCase()
    } else {
        def d = (output =~ 'Display Power: state=([A-Za-z]+)')
        if (d) wake = (d[0][1].toUpperCase() == "ON") ? "awake" : "asleep"
    }
    if (!wake) return
    sendEventIfChanged("status", wake)
    // dreaming is the screensaver: the panel is lit, so it still counts as on
    boolean awake = (wake == "awake" || wake == "dreaming")
    sendEventIfChanged("switch", awake ? "on" : "off")
}

private void parseCurrentApp(String output) {
    if (isEmpty(output)) return
    def m = (output =~ '([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)')
    if (!m) return
    String pkg = m[0][1]
    String friendly = appNameForPackage(pkg)
    sendEventIfChanged("currentApp", pkg)
    sendEventIfChanged("currentAppName", friendly)
    notifyParent()
}

private void onFailure(String reason, Integer forcedDelaySec = null, boolean hardReset = false) {
    if (hardReset) {
        logWarn("Hard reconnect after repeated failures: ${reason}")
    } else {
        logWarn("ADB failure: ${reason}")
    }

    closeSocket("offline", "reconnecting")
    if (manualDisconnect[devKey()] == true) return
    scheduleReconnect(forcedDelaySec)
}

private void scheduleReconnect(Integer forcedDelaySec = null) {
    String key = devKey()
    int base = Math.max(5, (state.pollSec ?: 30) as Integer)
    int n = ((failCount[key] ?: 0) as Integer) + 1
    failCount[key] = n
    int delay = forcedDelaySec != null ? forcedDelaySec : Math.min(base * (1 << Math.min(n - 1, 5)), 900)
    nextConnectMs[key] = now() + (delay * 1000L)
    unschedule("connectTask")
    runIn(delay, "connectTask")
    logDebug("Reconnect attempt ${n} scheduled in ${delay}s")
    sendEventIfChanged("adbStatus", "reconnecting")
}

def healthCheck() {
    try {
        String key = devKey()
        if (manualDisconnect[key] == true) return

        String st = stateOf(key)
        Long last = (lastRxMs[key] ?: 0L) as Long
        if (st in [ST_CONNECTED, ST_CMD_OPENING, ST_CMD_RUNNING] && last > 0L && (now() - last) > 90000L) {
            onFailure("no ADB traffic for 90s", null, true)
        } else if (st == ST_CONNECTED && !activeCmd[key] && (now() - last) > 45000L) {
            // Only needed to keep lastRxMs fresh for the 90s check above. Any poll inside that window has
            // already done it, so at the default 30s interval this never fires.
            sendShell(KEEPALIVE_CMD, true)
        } else if (st == ST_IDLE && !isQueueEmpty(key)) {
            ensureConnected(false)
        }
    } catch (Exception ex) {
        logWarn("healthCheck failed: ${ex.message}")
    } finally {
        runIn(60, "healthCheck")
    }
}

private void closeSocket(String connectionStateValue, String adbStatusValue) {
    String key = devKey()
    unschedule("connectWatchdog")
    unschedule("authPubkeyTimeout")
    unschedule("commandWatchdog")
    try { interfaces.rawSocket.close() } catch (ignored) {}

    connState[key] = ST_IDLE
    remoteId[key] = 0
    activeCmd.remove(key)
    activeOut.remove(key)
    rxBuf[key] = ""
    nextConnectMs.remove(key)

    // whatever was on screen is no longer something we know; `switch` is left alone on purpose, since ADB
    // dropping out says nothing about the panel (a sleeping Fire TV still answers on 5555)
    sendEventIfChanged("currentApp", "unknown")
    sendEventIfChanged("currentAppName", "unknown")
    sendEventIfChanged("status", "unknown")
    sendEventIfChanged("connectionStatus", connectionStateValue)
    sendEventIfChanged("adbStatus", adbStatusValue)
    notifyParent()
}

private void sendAdbConnect() {
    sendAdbMsg(CMD_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::\0".bytes)
}

private void sendAdbMsg(int cmd, int arg0, int arg1, byte[] data) {
    int len = data?.length ?: 0
    long crc = adbChecksum(data ?: new byte[0])
    long magic = (cmd ^ 0xFFFFFFFFL) & 0xFFFFFFFFL

    byte[] header = concatBytes([int32LE(cmd), int32LE(arg0), int32LE(arg1), int32LE(len), int32LE(crc), int32LE(magic)])
    byte[] msg = len > 0 ? concatBytes([header, data]) : header
    sendRawHex(msg.encodeHex().toString().toUpperCase())
}

private void sendRawHex(String hexStr) {
    try {
        interfaces.rawSocket.sendMessage(hexStr)
    } catch (Exception ex) {
        onFailure("socket send failed: ${ex.message}")
    }
}

// ============================================================================
// key handling / high-level commands
// ============================================================================
// report optimistically so a dashboard reacts at once, then confirm against the device
def on() {
    if (!wakeUp()) return
    sendEventIfChanged("switch", "on")
    runIn(3, "getCurrentApp")
}

def off() {
    if (!sleepDevice()) return
    sendEventIfChanged("switch", "off")
    runIn(3, "getCurrentApp")
}

def home()          { keyEvent(KEY.HOME) }
def back()          { keyEvent(KEY.BACK) }
def menu()          { keyEvent(KEY.MENU) }
def wakeUp()        { keyEvent(KEY.WAKEUP) }
def sleepDevice()   { keyEvent(KEY.SLEEP) }
def select()        { keyEvent(KEY.DPAD_CENTER) }
def enter()         { select() }
def dpadUp()        { keyEvent(KEY.DPAD_UP) }
def dpadDown()      { keyEvent(KEY.DPAD_DOWN) }
def dpadLeft()      { keyEvent(KEY.DPAD_LEFT) }
def dpadRight()     { keyEvent(KEY.DPAD_RIGHT) }
def volumeUp()      { keyEvent(KEY.VOLUME_UP) }
def volumeDown()    { keyEvent(KEY.VOLUME_DOWN) }
def mute()          { keyEvent(KEY.VOLUME_MUTE) }
def play()          { keyEvent(KEY.PLAY) }
def pause()         { keyEvent(KEY.PAUSE) }
def playPause()     { keyEvent(KEY.PLAY_PAUSE) }
def stop()          { keyEvent(KEY.STOP) }
def fastForward()   { keyEvent(KEY.FF) }
def rewind()        { keyEvent(KEY.REWIND) }
def nextTrack()     { keyEvent(KEY.NEXT) }
def previousTrack() { keyEvent(KEY.PREV) }

def sendKeyEvent(code) { keyEvent((code ?: 0) as int) }
def keyEvent(int code) { return sendShell("input keyevent ${code}") }

def launchApp(String pkg) {
    if (isEmpty(pkg) || !isValidPackageName(pkg)) {
        logWarn("Invalid package name: ${pkg}")
        return
    }
    Map meta = appMeta(pkg)
    String cmd = (meta?.launchCmd as String) ?: buildResolvedLaunchCommand(pkg)
    sendShell(cmd)
    runIn(3, "getCurrentApp")
}

def appOpenByName(String appName) {
    String pkg = packageForAppName(appName)
    if (!pkg) {
        logWarn("Unknown app name: ${appName}")
        return
    }
    launchApp(pkg)
}

private String appNameForPackage(String pkg) {
    Map meta = appMeta(pkg)
    return (meta?.name as String) ?: pkg
}

// custom entries are checked first so a user can rename or re-point a built-in without editing the driver
private Map appMeta(String pkg) {
    Map custom = customApps()
    if (custom[pkg] instanceof Map) return custom[pkg] as Map
    return (APPS[pkg] instanceof Map) ? (APPS[pkg] as Map) : null
}

private Map customApps() {
    return (state.customApps instanceof Map) ? (state.customApps as Map) : [:]
}

private String packageForAppName(String appName) {
    String wanted = normalizeAppName(appName)
    if (isEmpty(wanted)) return null
    return matchAppName(customApps(), wanted) ?: matchAppName(APPS, wanted)
}

private String matchAppName(Map catalog, String wanted) {
    for (String pkg : catalog.keySet()) {
        Map meta = (catalog[pkg] instanceof Map) ? (catalog[pkg] as Map) : null
        if (!meta) continue
        if (normalizeAppName(meta.name as String) == wanted) return pkg
        List aliases = (meta.aliases instanceof List) ? (meta.aliases as List) : []
        for (def a : aliases) {
            if (normalizeAppName(a?.toString()) == wanted) return pkg
        }
    }
    return null
}

// One entry per line: "Kodi = org.xbmc.kodi". An optional "| <shell command>" tail overrides the launch
// command, for apps whose LEANBACK_LAUNCHER activity can't be resolved -- the same escape hatch the
// built-in APPS entries use. Blank lines and lines starting with # are ignored.
private Map parseCustomApps(String text) {
    Map out = [:]
    if (isEmpty(text)) return out
    for (String line : text.split('\n')) {
        String row = (line ?: "").trim()
        if (isEmpty(row) || row.startsWith("#")) continue
        int eq = row.indexOf("=")
        if (eq <= 0) {
            logWarn("Ignoring custom app '${row}': expected 'Name = package.name'")
            continue
        }
        String name = row.substring(0, eq).trim()
        String rest = row.substring(eq + 1).trim()
        String pkg = rest
        String launchCmd = null
        int bar = rest.indexOf("|")
        if (bar >= 0) {
            pkg = rest.substring(0, bar).trim()
            launchCmd = rest.substring(bar + 1).trim()
        }
        if (isEmpty(name) || !isValidPackageName(pkg)) {
            logWarn("Ignoring custom app '${row}': '${pkg}' is not a valid package name")
            continue
        }
        Map entry = [name: name]
        if (!isEmpty(launchCmd)) entry.launchCmd = launchCmd
        out[pkg] = entry
    }
    return out
}

private String normalizeAppName(String name) {
    return (name ?: "").trim().toLowerCase().replace("%20", " ").replaceAll('[^a-z0-9]', "")
}

private boolean isValidPackageName(String pkg) {
    return (pkg ?: "") ==~ '^[A-Za-z0-9_.]+$'
}

private String buildResolvedLaunchCommand(String pkg) {
    return "sh -c 'ACT=\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER ${pkg} 2>/dev/null | grep / | tail -n 1); if [ -n \"\$ACT\" ]; then am start -n \"\$ACT\"; else monkey -p ${pkg} -c android.intent.category.LAUNCHER 1; fi'"
}

// ============================================================================
// key generation and signing
// ============================================================================
private void ensureKeyPair() {
    if (state.adbPublicKey && state.adbKeyN && state.adbKeyD) return
    generateKeyPair()
}

private void generateKeyPair() {
    logInfo("Generating RSA-2048 ADB key")
    try {
        SecureRandom rng = new SecureRandom()
        BigInteger e = BigInteger.valueOf(65537L)
        BigInteger one = BigInteger.ONE
        BigInteger p
        BigInteger q
        BigInteger phi
        while (true) {
            p = new BigInteger(1024, 64, rng)
            q = new BigInteger(1024, 64, rng)
            if (q == p) continue
            phi = p.subtract(one).multiply(q.subtract(one))
            if (e.gcd(phi) == one) break
        }

        BigInteger n = p.multiply(q)
        BigInteger d = e.modInverse(phi)

        state.adbKeyN = n.toString(16)
        state.adbKeyD = d.toString(16)
        state.adbPublicKey = buildAdbPublicKey(n, 65537)
        logInfo("ADB key generated (${keyFingerprint()})")
    } catch (Exception ex) {
        logError("Failed to generate ADB key: ${ex.message}")
    }
}

private String keyFingerprint() {
    if (!state.adbPublicKey) return "no-key"
    byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(state.adbPublicKey.toString().bytes)
    return hash.encodeHex().toString().take(8)
}

private String buildAdbPublicKey(BigInteger n, int e) {
    int words = 64
    BigInteger b32 = BigInteger.ONE.shiftLeft(32)
    BigInteger two = BigInteger.valueOf(2L)
    BigInteger n0inv = n.mod(b32).modInverse(b32).negate().mod(b32)
    BigInteger rr = two.modPow(BigInteger.valueOf(4096L), n)

    byte[] buf = concatBytes([
        int32LE(words),
        int32LE(n0inv.longValue()),
        bigIntToLE(n, words * 4),
        bigIntToLE(rr, words * 4),
        int32LE(e)
    ])
    return "${buf.encodeBase64().toString().replaceAll('\\s', '')} hubitat_android_tv_plus\0"
}

private byte[] signWithPrivateKey(byte[] token) {
    try {
        if (!state.adbKeyD || !state.adbKeyN) return null
        BigInteger d = new BigInteger(state.adbKeyD as String, 16)
        BigInteger n = new BigInteger(state.adbKeyN as String, 16)

        byte[] sha1 = token
        byte[] digestInfo = [0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14] as byte[]
        int keySize = 256
        int psLen = keySize - sha1.length - digestInfo.length - 3
        if (psLen < 8) return null

        byte[] em = new byte[keySize]
        em[0] = 0x00
        em[1] = 0x01
        for (int i = 0; i < psLen; i++) em[2 + i] = (byte) 0xFF
        em[2 + psLen] = 0x00
        for (int i = 0; i < digestInfo.length; i++) em[3 + psLen + i] = digestInfo[i]
        for (int i = 0; i < sha1.length; i++) em[3 + psLen + digestInfo.length + i] = sha1[i]

        BigInteger m = new BigInteger(1, em)
        BigInteger sig = m.modPow(d, n)
        return bigIntToFixedBytes(sig, keySize)
    } catch (Exception ex) {
        logWarn("Could not sign AUTH token: ${ex.message}")
        return null
    }
}

// ============================================================================
// bytes / parser helpers
// ============================================================================
private byte[] int32LE(long val) {
    return [
        (byte) (val & 0xFF),
        (byte) ((val >> 8) & 0xFF),
        (byte) ((val >> 16) & 0xFF),
        (byte) ((val >> 24) & 0xFF)
    ] as byte[]
}

private long readInt32LE(byte[] buf, int offset) {
    return ((buf[offset] & 0xFFL)) |
        ((buf[offset + 1] & 0xFFL) << 8) |
        ((buf[offset + 2] & 0xFFL) << 16) |
        ((buf[offset + 3] & 0xFFL) << 24)
}

private byte[] concatBytes(List<byte[]> arrays) {
    int total = 0
    for (byte[] a : arrays) if (a) total += a.length
    byte[] out = new byte[total]
    int pos = 0
    for (byte[] a : arrays) {
        if (!a) continue
        for (int i = 0; i < a.length; i++) out[pos++] = a[i]
    }
    return out
}

private byte[] bigIntToLE(BigInteger val, int size) {
    byte[] be = val.toByteArray()
    if (be.length > size && be[0] == (byte) 0) be = be[1..-1] as byte[]
    byte[] out = new byte[size]
    int copy = Math.min(be.length, size)
    for (int i = 0; i < copy; i++) out[i] = be[be.length - 1 - i]
    return out
}

private byte[] bigIntToFixedBytes(BigInteger val, int size) {
    byte[] bytes = val.toByteArray()
    if (bytes.length > size && bytes[0] == (byte) 0) bytes = bytes[1..-1] as byte[]
    if (bytes.length == size) return bytes
    byte[] out = new byte[size]
    int offset = size - bytes.length
    for (int i = 0; i < bytes.length; i++) out[offset + i] = bytes[i]
    return out
}

// ADB's `data_check` header field is NOT a CRC-32 -- it is a plain 32-bit sum of the payload bytes
// (adb: calculate_apacket_checksum()). Using a real CRC here makes every payload-bearing frame look
// corrupt, which is what kept the AUTH token from ever being answered.
private long adbChecksum(byte[] data) {
    if (!data || data.length == 0) return 0L
    long sum = 0L
    for (byte b : data) sum += (b & 0xFFL)
    return sum & 0xFFFFFFFFL
}

private String cmdName(int cmd) {
    switch (cmd) {
        case CMD_CNXN: return "CNXN"
        case CMD_AUTH: return "AUTH"
        case CMD_OPEN: return "OPEN"
        case CMD_OKAY: return "OKAY"
        case CMD_CLSE: return "CLSE"
        case CMD_WRTE: return "WRTE"
        default: return "0x" + Integer.toHexString(cmd)
    }
}

private String sanitizeText(byte[] data) {
    return new String(data, "UTF-8").replaceAll(/[\x00-\x08\x0b-\x1f]/, "").trim()
}

// ============================================================================
// queue helpers
// ============================================================================
private List<String> queueFor(String key) {
    List<String> q = cmdQueues[key] as List<String>
    if (!q) {
        q = []
        cmdQueues[key] = q
    }
    return q
}

private void enqueueCommand(String key, String cmd) {
    List<String> q = queueFor(key)
    synchronized (q) {
        if (q.size() >= MAX_QUEUED_COMMANDS) {
            // make room by evicting a background query first -- a queued keypress is a user waiting on us
            int drop = -1
            for (int i = 0; i < q.size(); i++) {
                if (isBackgroundCommand(q[i] as String)) { drop = i; break }
            }
            if (drop < 0) drop = 0
            logWarn("Command queue full (${q.size()}); dropping '${(q[drop] as String).take(40)}'")
            q.remove(drop)
        }
        q << cmd
    }
}

private boolean isBackgroundCommand(String cmd) {
    return cmd == STATUS_CMD || cmd == KEEPALIVE_CMD
}

private String dequeueCommand(String key) {
    List<String> q = queueFor(key)
    synchronized (q) {
        if (q.isEmpty()) return null
        return q.remove(0)
    }
}

private void requeueFront(String key, String cmd) {
    if (isEmpty(cmd)) return
    List<String> q = queueFor(key)
    synchronized (q) { q.add(0, cmd) }
}

private boolean isQueuedOrActive(String key, String cmd) {
    if ((activeCmd[key] as String) == cmd) return true
    List<String> q = queueFor(key)
    synchronized (q) { return q.contains(cmd) }
}

private boolean isQueueEmpty(String key) {
    List<String> q = queueFor(key)
    synchronized (q) { return q.isEmpty() }
}

// ============================================================================
// state helpers
// ============================================================================
private String devKey() { device.id as String }
private String stateOf(String key) { (connState[key] ?: ST_IDLE) as String }
private void setState(String key, String value) { connState[key] = value }

private Integer asPort(def raw) {
    String v = raw?.toString()?.trim()
    if (!v) return 5555
    Integer p
    try {
        p = Integer.parseInt(v)
    } catch (Exception ignored) {
        logWarn("Unusable port '${v}'; falling back to 5555")
        return 5555
    }
    if (p < 1 || p > 65535) return 5555
    return p
}

private void sendEventIfChanged(String name, def value) {
    if (value == null) return
    if (device.currentValue(name)?.toString() != value.toString()) {
        sendEvent(name: name, value: value)
    }
}

private void notifyParent() {
    try {
        device.getParent()?.childStatusChanged()
    } catch (ignored) {}
}

private boolean isEmpty(def v) {
    return v == null || (v instanceof String && v.trim().isEmpty())
}

private void cleanupRuntime() {
    String key = devKey()
    rxBuf.remove(key)
    connState.remove(key)
    waitingForAuth.remove(key)
    remoteId.remove(key)
    activeCmd.remove(key)
    activeOut.remove(key)
    cmdQueues.remove(key)
    lastRxMs.remove(key)
    failCount.remove(key)
    cmdTimeoutCount.remove(key)
    manualDisconnect.remove(key)
    nextConnectMs.remove(key)
}

// ============================================================================
// logging
// ============================================================================
// debug logging is enabled either from the Android TV+ app (setDebug -> state) or on the device page itself
private boolean debugOn() { return state.debugOutput == true || settings.debugOutput == true }

private void logDebug(msg) { if (debugOn()) logAt("debug", msg) }
private void logInfo(msg)  { logAt("info", msg) }
private void logWarn(msg)  { logAt("warn", msg) }
private void logError(msg) { logAt("error", msg) }

private void logAt(String level, msg) {
    String out = "Android TV+ [${device.displayName}] ${msg}"
    switch (level) {
        case "debug": log.debug(out); break
        case "warn": log.warn(out); break
        case "error": log.error(out); break
        default: log.info(out)
    }
}
