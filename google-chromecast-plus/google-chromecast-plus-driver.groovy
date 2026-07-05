/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** Google Chromecast+ **
 *
 * Native Hubitat driver for Google Cast (Chromecast) devices. Speaks the CASTV2 protocol directly over a
 * TLS raw socket (port 8009) - no external bridge, no protobuf library (the single CastMessage envelope is
 * hand-encoded; all real payloads are JSON).
 *
 * ONE driver, TWO roles (selected at runtime by the "role" data value):
 *   - PARENT (role=parent, created by the app): manages child devices + group actions + aggregate status.
 *   - CHILD  (role=child, one per Chromecast):  owns a socket, full protocol, TTS/media/transport/status.
 *
 * Fixes the built-in integration's headline bugs (2-second TTS cutoff / first-word clip) by answering
 * heartbeat PINGs synchronously and gating announcements on real MEDIA_STATUS transitions.
 *
 *  Changes:
 *  1.0.0 - initial version
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.helper.HexUtils
import java.util.concurrent.ConcurrentHashMap

// -- CASTV2 constants --
@Field static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
@Field static final String NS_HEARTBEAT  = "urn:x-cast:com.google.cast.tp.heartbeat"
@Field static final String NS_RECEIVER   = "urn:x-cast:com.google.cast.receiver"
@Field static final String NS_MEDIA      = "urn:x-cast:com.google.cast.media"
@Field static final String SRC     = "sender-0"
@Field static final String RECV    = "receiver-0"
@Field static final String APP_DMR = "CC1AD845"          // Default Media Receiver
@Field static final Integer CAST_PORT = 8009

// per-device, cross-callback state (parse/socketStatus/scheduled jobs can run on different threads)
@Field static final Map rxBuf   = new ConcurrentHashMap()   // device.id -> hex String (rx accumulator)
@Field static final Map pending = new ConcurrentHashMap()   // "devId:reqId" -> [type, ts] (coalesce/diagnostics)

metadata {
    definition(
        name: "Google Chromecast+",
        namespace: "jpage4500",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/google-chromecast-plus/google-chromecast-plus-driver.groovy"
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "SpeechSynthesis"
        capability "AudioNotification"
        capability "MusicPlayer"
        capability "AudioVolume"

        // child-mode manual controls
        command "reconnect"
        command "disconnect"
        command "launchApp", [[name: "App ID*", type: "STRING", description: "Cast app id, e.g. CC1AD845"]]
        command "stopApp"

        // -- now-playing / status (child mode) --
        attribute "connectionStatus", "string"   // idle / connecting / connected / disconnected
        attribute "currentApp", "string"          // e.g. "Netflix", "Default Media Receiver"
        attribute "appStatusText", "string"        // free-text now-playing hint from the app
        attribute "playbackStatus", "string"       // raw Cast playerState: PLAYING/PAUSED/BUFFERING/IDLE
        attribute "mediaTitle", "string"
        attribute "mediaArtist", "string"
        attribute "mediaAlbum", "string"
        attribute "albumArtUrl", "string"
        attribute "mediaSeries", "string"
        attribute "mediaEpisode", "string"
        attribute "mediaContentId", "string"
        attribute "mediaDuration", "number"
        attribute "mediaPosition", "number"
        attribute "refreshTime", "number"          // polling interval (secs) pushed from the app

        // -- aggregate (parent mode) --
        attribute "deviceCount", "number"
        attribute "playingCount", "number"
        attribute "summary", "string"
    }

    preferences {
        input name: "keepAlive", type: "bool", title: "Real-time updates (keep a persistent connection). Turn OFF to poll on-demand (quieter, higher latency).", defaultValue: true
        input name: "ttsVolume", type: "number", title: "Announcement volume (0-100, blank = leave current)", required: false, range: "0..100"
        // debug logging is a single toggle in the app (broadcast here as state.debug); no per-device switch
    }
}

// ============================================================================
// role
// ============================================================================
private String getIp()     { return getDataValue("ip") }
private Integer getPort()  { return (getDataValue("port") ?: "${CAST_PORT}") as Integer }
// null (never saved) counts as ON, so app-created children get real-time until explicitly turned off
private boolean isKeepAlive() { return settings.keepAlive != false }

// ============================================================================
// lifecycle
// ============================================================================
def installed() {
    logDebug("installed")
    initialize()
}

def updated() {
    logDebug("updated")
    unschedule()
    // reconnect cleanly if settings/IP changed
    closeSocket()
    initialize()
}

def uninstalled() {
    unschedule()
    closeSocket()
    rxBuf.remove(device.id as String)
}

def initialize() {
    state.conn = "IDLE"
    state.retryCount = 0
    state.pendingActions = []
    rxBuf[device.id as String] = ""
    sendEventIfChanged("connectionStatus", "idle")
    if (isKeepAlive() && !isEmpty(getIp())) runIn(2, "connect")
}

def refresh() {
    if (isConnectedState()) {
        sendReceiverGetStatus()
        if (state.transportId) sendMediaGetStatus()
    } else if (device.currentValue("connectionStatus") != "offline") {
        connect()                            // (re)connect + GET_STATUS; on-demand devices idle-disconnect after
    }
    // known-offline devices are skipped here; markOffline schedules a slow retry
}

// ============================================================================
// config received from the parent (this driver is child-only)
// ============================================================================

// polling interval relayed from the app via the parent; published for reference (the app drives the poll)
def setRefreshInterval(seconds) {
    state.refreshSec = (seconds ?: 60) as Integer
    sendEvent(name: "refreshTime", value: state.refreshSec)
}

// single debug toggle: the parent broadcasts the flag here; it gates this device's debug logs
def setDebug(flag) { state.debug = (flag as Boolean) }

// ============================================================================
// PUBLIC COMMANDS (per-device Chromecast control)
// ============================================================================

// -- SpeechSynthesis --
def speak(text)                { announce(text, ttsVolume, "restore") }
def speak(text, volume)        { announce(text, volume, "restore") }
def speak(text, volume, voice) { announce(text, volume, "restore", voice) }

// -- AudioNotification / Notification --
def deviceNotification(text)                { speak(text) }
def playText(text)                          { announce(text, ttsVolume, "none") }
def playText(text, volume)                  { announce(text, volume, "none") }
def playTextAndRestore(text, volume = null) { announce(text, volume, "restore") }
def playTextAndResume(text, volume = null)  { announce(text, volume, "resume") }

def playTrack(uri)                          { playMedia(uri, null, null, null, "none") }
def playTrack(uri, volume)                  { playMedia(uri, null, null, volume, "none") }
def playTrackAndRestore(uri, volume = null) { playMedia(uri, null, null, volume, "restore") }
def playTrackAndResume(uri, volume = null)  { playMedia(uri, null, null, volume, "resume") }

// -- MusicPlayer transport --
def play()          { sendMediaCommand("PLAY") }
def pause()         { sendMediaCommand("PAUSE") }
def stop()          { sendMediaCommand("STOP") }
def nextTrack()     { sendMediaCommand("QUEUE_NEXT") }
def previousTrack() { sendMediaCommand("QUEUE_PREV") }
def setTrack(uri)     { /* stage a track without playing - not supported by DMR; treat as playTrack */ playTrack(uri) }
def restoreTrack(uri) { playTrack(uri) }
def resumeTrack(uri)  { playTrack(uri) }

// seek (seconds) - custom convenience
def seek(pos) { sendMediaCommand("SEEK", [currentTime: (pos ?: 0) as BigDecimal]) }

// -- AudioVolume / level --
def setVolume(vol) { setDeviceVolume(vol) }
def setLevel(vol)  { setVolume(vol) }
def volumeUp()     { setDeviceVolume(((device.currentValue("volume") ?: 0) as Integer) + 5) }
def volumeDown()   { setDeviceVolume(((device.currentValue("volume") ?: 0) as Integer) - 5) }
def mute()         { setMute(true) }
def unmute()       { setMute(false) }

// -- app control --
def launchApp(appId) { enqueue([type: "launch", stage: "receiver", appId: appId]) }
def stopApp()        { if (state.sessionId) sendReceiverStop(state.sessionId) }
def reconnect()      { closeSocket(); runIn(1, "connect") }
def disconnect()     { state.pendingActions = []; closeSocket(); sendEventIfChanged("connectionStatus", "disconnected") }

// ============================================================================
// CHILD mode: high-level actions
// ============================================================================

private void announce(String text, volume, String mode, String voice = null) {
    if (isEmpty(text)) return
    Map tts = voice ? textToSpeech(text, voice) : textToSpeech(text)
    if (!tts?.uri) { logError "announce: textToSpeech returned no uri for '${text}'"; return }
    enqueue([type: "announce", stage: "app", url: tts.uri, dur: (tts.duration ?: 0),
             volume: (volume != null ? volume : ttsVolume), mode: mode, title: "Announcement"])
}

private void playMedia(String url, String title, String subtitle, volume, String mode) {
    if (isEmpty(url)) return
    enqueue([type: "media", stage: "app", url: url, title: title, subtitle: subtitle,
             volume: volume, mode: mode ?: "none"])
}

private void setDeviceVolume(vol) {
    Integer v = Math.max(0, Math.min(100, (vol ?: 0) as Integer))
    enqueue([type: "setVolume", stage: "receiver", level: (v / 100.0d)])
    // reflect optimistically; confirmed by RECEIVER_STATUS
    sendEventIfChanged("volume", v)
}

private void setMute(boolean muted) {
    enqueue([type: "setMute", stage: "receiver", muted: muted])
    sendEventIfChanged("mute", muted ? "muted" : "unmuted")
}

// ============================================================================
// action queue + connection pump
// ============================================================================
private void enqueue(Map action) {
    state.pendingActions = (state.pendingActions ?: []) + [action]
    pump()
}

// advance the connection toward the state the queued actions need, running them when ready
private void pump() {
    def acts = state.pendingActions ?: []
    switch (state.conn ?: "IDLE") {
        case "IDLE":
            if (acts) connect()
            break
        case "READY":
            def remaining = []
            acts.each { a -> if (a.stage == "receiver") runAction(a) else remaining << a }
            state.pendingActions = remaining
            if (remaining.any { it.stage == "app" }) ensureApp()
            break
        case "APP_CONNECTED":
            acts.each { runAction(it) }
            state.pendingActions = []
            break
        // CONNECTING / TP_CONNECTING / APP_LAUNCHING / CLOSING -> wait; pump() re-runs on transition
    }
}

private void runAction(Map a) {
    switch (a.type) {
        case "getStatus":
            sendReceiverGetStatus()
            if (state.transportId) sendMediaGetStatus()
            break
        case "setVolume": sendSetVolume([level: a.level]); break
        case "setMute":   sendSetVolume([muted: a.muted]); break
        case "launch":    sendLaunch(a.appId); break
        case "setVolumePre": sendSetVolume([level: a.level]); break
        case "announce":  startAnnounce(a); break
        case "media":     startMedia(a); break
    }
}

// ============================================================================
// TTS-with-restore + media LOAD
// ============================================================================
private void startAnnounce(Map a) {
    if (a.mode in ["restore", "resume"]) snapshotForRestore()
    state.ttsActive = true
    state.ttsStarted = false
    state.ttsMode = a.mode
    if (a.volume != null) sendSetVolume([level: (Math.max(0, Math.min(100, (a.volume as Integer))) / 100.0d)])
    sendMediaLoad(a.url, a.title ?: "Announcement", null, null)
    if ((a.dur ?: 0) > 0) runIn((Math.ceil((a.dur as BigDecimal).doubleValue()) as Integer) + 3, "ttsTimeoutRestore")
}

private void startMedia(Map a) {
    state.ttsActive = false
    state.ttsMode = "none"
    if (a.volume != null) sendSetVolume([level: (Math.max(0, Math.min(100, (a.volume as Integer))) / 100.0d)])
    sendMediaLoad(a.url, a.title, a.subtitle, null)
}

private void snapshotForRestore() {
    state.ttsRestore = [
        volume       : device.currentValue("volume"),
        muted        : (device.currentValue("mute") == "muted"),
        appId        : state.appId,
        transportId  : state.transportId,
        sessionId    : state.sessionId,
        mediaSessionId: state.mediaSessionId,
        contentId    : device.currentValue("mediaContentId"),
        position     : device.currentValue("mediaPosition"),
        wasPlaying   : (device.currentValue("playbackStatus") == "PLAYING")
    ]
}

def ttsTimeoutRestore() { finishTts() }

private void finishTts() {
    if (!state.ttsActive) return
    state.ttsActive = false
    unschedule("ttsTimeoutRestore")
    maybeIdleDisconnect()
    String mode = state.ttsMode ?: "none"
    Map snap = state.ttsRestore ?: [:]
    state.ttsMode = "none"
    state.ttsRestore = [:]
    if (mode == "none") return

    // restore volume/mute first so restored content isn't loud
    if (snap.volume != null) sendSetVolume([level: ((snap.volume as Integer) / 100.0d)])
    if (snap.muted != null)  sendSetVolume([muted: snap.muted])

    if (snap.appId == APP_DMR && snap.contentId && snap.wasPlaying) {
        // we cast this content ourselves -> we can truly resume it
        sendMediaLoad(snap.contentId, null, null, null)
        if (mode == "resume" && (snap.position ?: 0) > 0) {
            state.resumeSeekTo = snap.position
            runIn(3, "resumeSeek")
        }
    } else if (snap.appId && snap.appId != APP_DMR) {
        // third-party app (Spotify/YouTube/etc.): a sender cannot resume its exact content
        logInfo "finishTts: prior app '${snap.appId}' was third-party; exact resume not supported (relaunching)"
        sendLaunch(snap.appId)
    }
}

def resumeSeek() {
    if (state.resumeSeekTo != null) { sendMediaCommand("SEEK", [currentTime: (state.resumeSeekTo as BigDecimal)]); state.remove("resumeSeekTo") }
}

private void sendMediaLoad(String url, String title, String subtitle, String art) {
    if (!state.transportId) { logWarn "sendMediaLoad: no transportId"; return }
    Map media = [
        contentId  : url,
        streamType : "BUFFERED",
        contentType: inferContentType(url),
        metadata   : [metadataType: 0, title: (title ?: ""), subtitle: (subtitle ?: "")]
    ]
    if (art) media.metadata.images = [[url: art]]
    Map load = [type: "LOAD", requestId: nextRequestId(), autoplay: true, currentTime: 0, media: media]
    sendCastMessage(SRC, state.transportId, NS_MEDIA, JsonOutput.toJson(load))
}

private void sendMediaCommand(String type, Map extra = [:]) {
    if (!state.transportId || !state.mediaSessionId) {
        logWarn "sendMediaCommand ${type}: no active media session"
        return
    }
    Map m = [type: type, requestId: nextRequestId(), mediaSessionId: state.mediaSessionId] + extra
    sendCastMessage(SRC, state.transportId, NS_MEDIA, JsonOutput.toJson(m))
}

private String inferContentType(String url) {
    String u = (url ?: "").toLowerCase().split("\\?")[0]
    if (u.endsWith(".mp3"))  return "audio/mpeg"
    if (u.endsWith(".m4a") || u.endsWith(".aac")) return "audio/aac"
    if (u.endsWith(".ogg") || u.endsWith(".oga")) return "audio/ogg"
    if (u.endsWith(".flac")) return "audio/flac"
    if (u.endsWith(".wav"))  return "audio/wav"
    if (u.endsWith(".mp4") || u.endsWith(".m4v")) return "video/mp4"
    if (u.endsWith(".m3u8")) return "application/x-mpegurl"
    if (u.endsWith(".mpd"))  return "application/dash+xml"
    return "audio/mpeg"
}

// ============================================================================
// connection lifecycle
// ============================================================================
def connect() {
    if (isConnectedState() || state.conn == "CONNECTING") return
    if (isEmpty(getIp())) { logError "connect: no IP configured"; return }
    rxBuf[device.id as String] = ""
    state.conn = "CONNECTING"
    state.transportId = null; state.sessionId = null; state.mediaSessionId = null
    sendEventIfChanged("connectionStatus", "connecting")
    try {
        interfaces.rawSocket.connect(getIp(), getPort(), byteInterface: true, secureSocket: true, ignoreSSLIssues: true)
        state.lastRxTs = now()
        state.lastPongTs = now()
        sendConnectionConnect(RECV)   // virtual-connect to the platform
        startHeartbeat()
        sendReceiverGetStatus()
        state.conn = "TP_CONNECTING"
    } catch (e) {
        String msg = (e.message ?: "").toString()
        state.conn = "IDLE"
        // an unreachable / powered-off device is expected, not an error -> mark offline, don't tight-loop
        if (msg.toLowerCase() =~ /route|unreachable|timed out|timeout|refused/) {
            markOffline(msg)
        } else {
            logWarn "connect: failed to ${getIp()}:${getPort()} - ${msg}"
            if (isKeepAlive()) scheduleReconnect() else state.pendingActions = []
        }
    }
}

private void closeSocket() {
    unschedule("heartbeatTick")
    unschedule("reconnectAttempt")
    try { interfaces.rawSocket.close() } catch (e) { /* ignore */ }
    rxBuf[device.id as String] = ""
    state.conn = "IDLE"
    state.transportId = null; state.sessionId = null; state.mediaSessionId = null
}

def socketStatus(String message) {
    logDebug("socketStatus: ${message}")
    String m = message?.toLowerCase() ?: ""
    if (m.contains("error") || m.contains("closed") || m.contains("failure")) {
        handleSocketDown("socketStatus:${message}")
    }
}

private void handleSocketDown(String why) {
    logWarn "socket down (${why})"
    unschedule("heartbeatTick")
    try { interfaces.rawSocket.close() } catch (e) { /* ignore */ }
    rxBuf[device.id as String] = ""
    state.conn = "IDLE"
    state.transportId = null; state.sessionId = null; state.mediaSessionId = null
    sendEventIfChanged("playbackStatus", "IDLE")
    if (isKeepAlive() || state.ttsActive) {
        scheduleReconnect()
    } else {
        state.pendingActions = []            // on-demand: don't reconnect, the next poll re-establishes
    }
}

private void scheduleReconnect() {
    int rc = (state.retryCount ?: 0)
    int delay = Math.min((int) Math.pow(2, Math.min(rc, 6)), 60) + (int) (now() % 3)   // capped + jitter
    state.retryCount = rc + 1
    logDebug("scheduleReconnect: in ${delay}s (retry ${state.retryCount})")
    runIn(delay, "reconnectAttempt")
}

def reconnectAttempt() { if (state.conn == "IDLE") connect() }

// device unreachable (powered off / off network): mark offline quietly and stop looping
private void markOffline(String why) {
    logDebug("offline: ${getIp()} (${why})")
    state.conn = "IDLE"
    state.transportId = null; state.sessionId = null; state.mediaSessionId = null
    state.pendingActions = []                // drop queued work; the next scheduled poll retries
    unschedule("heartbeatTick")
    sendEventIfChanged("connectionStatus", "offline")
    sendEventIfChanged("playbackStatus", "OFFLINE")
    sendEventIfChanged("status", "stopped")
    parent?.childStatusChanged()
    runIn(300, "connect")                    // retry slowly (both modes) so recovery is noticed; never a tight loop
}

// ============================================================================
// heartbeat (fixes the 2s-cutoff bug)
// ============================================================================
private void startHeartbeat() {
    unschedule("heartbeatTick")
    runIn(5, "heartbeatTick")
}

def heartbeatTick() {
    if (!isConnectedState()) return
    // dead-connection detection: >2 missed ping cycles
    if (state.lastRxTs && (now() - (state.lastRxTs as Long)) > 35000) {
        logWarn "heartbeat: no data for >35s - reconnecting"
        handleSocketDown("heartbeat-timeout")
        return
    }
    sendHeartbeatPing()
    runIn(5, "heartbeatTick")
}

// ============================================================================
// protocol send helpers
// ============================================================================
private int nextRequestId() { int n = ((state.requestId ?: 0) as Integer) + 1; state.requestId = n; return n }

private void sendConnectionConnect(String dst) { sendCastMessage(SRC, dst, NS_CONNECTION, '{"type":"CONNECT"}') }
private void sendConnectionClose(String dst)   { sendCastMessage(SRC, dst, NS_CONNECTION, '{"type":"CLOSE"}') }
private void sendHeartbeatPing()               { sendCastMessage(SRC, RECV, NS_HEARTBEAT, '{"type":"PING"}') }
private void sendHeartbeatPong(String dst)     { sendCastMessage(SRC, dst ?: RECV, NS_HEARTBEAT, '{"type":"PONG"}') }

private void sendReceiverGetStatus() {
    String pk = "${device.id}:getStatus"
    if (pending[pk] && (now() - (pending[pk].ts as Long)) < 5000) return   // coalesce
    int rid = nextRequestId()
    pending[pk] = [type: "GET_STATUS", ts: now(), rid: rid]
    sendCastMessage(SRC, RECV, NS_RECEIVER, JsonOutput.toJson([type: "GET_STATUS", requestId: rid]))
}

private void sendLaunch(String appId) {
    sendCastMessage(SRC, RECV, NS_RECEIVER, JsonOutput.toJson([type: "LAUNCH", appId: appId, requestId: nextRequestId()]))
}

private void sendReceiverStop(String sessionId) {
    sendCastMessage(SRC, RECV, NS_RECEIVER, JsonOutput.toJson([type: "STOP", sessionId: sessionId, requestId: nextRequestId()]))
}

private void sendSetVolume(Map volMap) {
    // volMap = [level: 0.0-1.0] or [muted: true/false]
    sendCastMessage(SRC, RECV, NS_RECEIVER, JsonOutput.toJson([type: "SET_VOLUME", volume: volMap, requestId: nextRequestId()]))
}

private void sendMediaGetStatus() {
    if (!state.transportId) return
    sendCastMessage(SRC, state.transportId, NS_MEDIA, JsonOutput.toJson([type: "GET_STATUS", requestId: nextRequestId()]))
}

private void sendCastMessage(String srcId, String dstId, String ns, String json) {
    if (isEmpty(dstId)) { logWarn "sendCastMessage: null destination for ns=${ns}"; return }
    try {
        byte[] body = encodeCastMessage(srcId, dstId, ns, json)
        byte[] frame = concatBytes([int32BE((long) body.length), body])
        String hex = HexUtils.byteArrayToHexString(frame)
        if (ns != NS_HEARTBEAT) logDebug("TX ${ns} -> ${dstId}: ${json}")
        interfaces.rawSocket.sendMessage(hex)
    } catch (e) {
        logError "sendCastMessage failed (${ns}): ${e.message}"
        handleSocketDown("send-exception")
    }
}

private void ensureApp() {
    if (state.appId == APP_DMR && state.transportId) {
        sendConnectionConnect(state.transportId)
        state.conn = "APP_CONNECTED"
        pump()
    } else {
        sendLaunch(APP_DMR)
        state.conn = "APP_LAUNCHING"
    }
}

// ============================================================================
// receive: parse -> reassemble frames -> route
// ============================================================================
def parse(String message) {
    String key = device.id as String
    String buf = (rxBuf[key] ?: "") + (message ?: "").toUpperCase()
    state.lastRxTs = now()

    if (buf.length() > 262144) {                 // 128 KB body -> desync; reset
        logWarn "parse: rx overflow (${buf.length()} hex chars) - resetting"
        rxBuf[key] = ""
        return
    }

    while (true) {
        if (buf.length() < 8) break              // need 4 bytes (8 hex) for length prefix
        long bodyLen = readInt32BE(buf.substring(0, 8).decodeHex(), 0)
        if (bodyLen < 0 || bodyLen > 131072) {
            logWarn "parse: bogus frame length ${bodyLen} - resetting"
            rxBuf[key] = ""
            return
        }
        int totalHex = 8 + (int) (bodyLen * 2)
        if (buf.length() < totalHex) break        // whole frame not here yet
        byte[] body = buf.substring(8, totalHex).decodeHex()
        buf = buf.substring(totalHex)
        try { routeIncoming(decodeCastMessage(body)) } catch (e) { logError "route error: ${e.message}" }
    }
    rxBuf[key] = buf
}

private void routeIncoming(Map msg) {
    if (isEmpty(msg?.payload_utf8)) return
    Map p
    try { p = (Map) new JsonSlurper().parseText(msg.payload_utf8) } catch (e) { return }
    switch (msg.namespace) {
        case NS_HEARTBEAT:
            if (p.type == "PING") sendHeartbeatPong(msg.source_id)
            else if (p.type == "PONG") state.lastPongTs = now()
            break
        case NS_RECEIVER:
            if (p.type == "RECEIVER_STATUS") handleReceiverStatus(p)
            break
        case NS_MEDIA:
            if (p.type == "MEDIA_STATUS") handleMediaStatus(p)
            else if (p.type in ["LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST"]) handleMediaError(p)
            break
        case NS_CONNECTION:
            if (p.type == "CLOSE") handlePeerClose(msg.source_id)
            break
    }
}

private void handleReceiverStatus(Map p) {
    pending.remove("${device.id}:getStatus")
    def st = p.status ?: [:]
    // volume / mute
    if (st.volume != null) {
        if (st.volume.level != null) sendEventIfChanged("volume", Math.round((st.volume.level as BigDecimal).doubleValue() * 100) as Integer)
        if (st.volume.muted != null) sendEventIfChanged("mute", st.volume.muted ? "muted" : "unmuted")
    }
    def apps = st.applications ?: []
    if (apps.isEmpty()) {
        sendEventIfChanged("currentApp", "none")
        sendEventIfChanged("appStatusText", "")
        sendEventIfChanged("playbackStatus", "IDLE")
        sendEventIfChanged("status", "stopped")
        clearNowPlaying()
        state.appId = null; state.transportId = null; state.sessionId = null; state.mediaSessionId = null
    } else {
        def app0 = apps[0]
        sendEventIfChanged("currentApp", app0.displayName ?: app0.appId)
        sendEventIfChanged("appStatusText", app0.statusText ?: "")
        state.appId = app0.appId
        String newTransport = app0.transportId
        if (newTransport && newTransport != state.transportId) {
            state.transportId = newTransport
            state.sessionId = app0.sessionId
            // if the running app speaks media, virtual-connect + read now-playing (monitoring)
            def ns = (app0.namespaces ?: []).collect { it?.name }
            if (ns.contains(NS_MEDIA)) {
                sendConnectionConnect(newTransport)
                sendMediaGetStatus()
            }
        }
    }
    // state transitions
    if (state.conn == "TP_CONNECTING") {
        state.conn = "READY"
        state.retryCount = 0
        sendEventIfChanged("connectionStatus", "online")
        pump()
        maybeIdleDisconnect()
    } else if (state.conn == "APP_LAUNCHING") {
        def ourApp = apps.find { it.appId == APP_DMR }
        if (ourApp?.transportId) {
            state.transportId = ourApp.transportId
            state.sessionId = ourApp.sessionId
            state.appId = ourApp.appId
            sendConnectionConnect(ourApp.transportId)
            state.conn = "APP_CONNECTED"
            pump()
        }
    }
    parent?.childStatusChanged()
}

private void handleMediaStatus(Map p) {
    def arr = p.status ?: []
    if (arr.isEmpty()) return
    def s = arr[0]
    if (s.mediaSessionId != null) state.mediaSessionId = s.mediaSessionId

    String ps = s.playerState ?: "UNKNOWN"
    sendEventIfChanged("playbackStatus", ps)
    sendEventIfChanged("status", mapPlayerState(ps))
    if (s.currentTime != null) sendEventIfChanged("mediaPosition", (s.currentTime as BigDecimal).intValue())

    def media = s.media
    if (media != null) {
        if (media.duration != null) sendEventIfChanged("mediaDuration", (media.duration as BigDecimal).intValue())
        if (media.contentId != null) sendEventIfChanged("mediaContentId", media.contentId)
        def md = media.metadata ?: [:]
        String title = md.title
        String artist = md.artist ?: md.subtitle
        sendEventIfChanged("mediaTitle", title ?: "")
        sendEventIfChanged("mediaArtist", artist ?: "")
        sendEventIfChanged("mediaAlbum", md.albumName ?: "")
        sendEventIfChanged("mediaSeries", md.seriesTitle ?: "")
        sendEventIfChanged("mediaEpisode", md.episode != null ? "${md.episode}" : "")
        String art = md.images ? md.images[0]?.url : null
        if (art) sendEventIfChanged("albumArtUrl", art)
        sendEventIfChanged("trackDescription", [title, artist].findAll { it }.join(" - "))
        sendEvent(name: "trackData", value: JsonOutput.toJson([title: title, artist: artist, album: md.albumName, image: art]))
    }

    // TTS completion: only after the announcement has actually started playing, so a transient
    // IDLE/INTERRUPTED before playback can't trigger a premature restore (the first-word/2s bug).
    if (state.ttsActive) {
        if (ps == "PLAYING") state.ttsStarted = true
        else if (state.ttsStarted && ps == "IDLE" && s.idleReason != "INTERRUPTED") finishTts()
    }

    parent?.childStatusChanged()
    maybeIdleDisconnect()
}

private void handleMediaError(Map p) {
    logWarn "media error: ${p.type} ${p}"
    if (state.ttsActive) finishTts()
}

private void handlePeerClose(String src) {
    logDebug("peer CLOSE from ${src}")
    if (src == state.transportId) {
        // app-level virtual connection dropped
        state.transportId = null; state.mediaSessionId = null
        if (state.conn == "APP_CONNECTED") state.conn = "READY"
        sendReceiverGetStatus()
    } else if (src == RECV) {
        handleSocketDown("peer-close")
    }
}

private void clearNowPlaying() {
    ["mediaTitle", "mediaArtist", "mediaAlbum", "mediaSeries", "mediaEpisode", "albumArtUrl", "mediaContentId", "trackDescription"].each {
        sendEventIfChanged(it, "")
    }
}

// on-demand mode: if not keeping alive and nothing pending, drop the socket shortly after a read
private void maybeIdleDisconnect() {
    if (isKeepAlive()) return
    if (state.ttsActive) return
    if (state.pendingActions && !state.pendingActions.isEmpty()) return
    runIn(12, "idleDisconnect")
}

def idleDisconnect() {
    if (isKeepAlive() || state.ttsActive) return
    if (state.pendingActions && !state.pendingActions.isEmpty()) return
    logDebug("idleDisconnect")
    closeSocket()
}

// ============================================================================
// CASTV2 codec (hand-rolled; only CastMessage exists on the wire)
// ============================================================================
private byte[] encodeVarint(long v) {
    List<Integer> out = []
    long n = v
    while (true) {
        int b = (int) (n & 0x7F)
        n = n >>> 7
        if (n != 0) { out << (b | 0x80) } else { out << b; break }
    }
    byte[] r = new byte[out.size()]
    for (int i = 0; i < out.size(); i++) r[i] = (byte) out[i]
    return r
}

// returns [value(long), nextPos(int)] or null if the buffer is truncated
private List decodeVarint(byte[] buf, int pos) {
    long result = 0; int shift = 0; int p = pos
    while (true) {
        if (p >= buf.length) return null
        int b = buf[p] & 0xFF; p++
        result |= ((long) (b & 0x7F)) << shift
        if ((b & 0x80) == 0) break
        shift += 7
        if (shift > 63) return null
    }
    return [result, p]
}

private byte[] encodeVarintField(int fieldNo, long v) {
    byte tag = (byte) ((fieldNo << 3) | 0)
    return concatBytes([[tag] as byte[], encodeVarint(v)])
}

private byte[] encodeLenDelim(int fieldNo, String s) {
    byte[] utf8 = (s ?: "").getBytes("UTF-8")     // UTF-8 byte length is load-bearing (never str.length())
    byte tag = (byte) ((fieldNo << 3) | 2)
    return concatBytes([[tag] as byte[], encodeVarint((long) utf8.length), utf8])
}

private byte[] encodeCastMessage(String srcId, String dstId, String namespace, String jsonPayload) {
    return concatBytes([
        encodeVarintField(1, 0L),          // protocol_version = CASTV2_1_0
        encodeLenDelim(2, srcId),
        encodeLenDelim(3, dstId),
        encodeLenDelim(4, namespace),
        encodeVarintField(5, 0L),          // payload_type = STRING
        encodeLenDelim(6, jsonPayload)
    ])
}

private Map decodeCastMessage(byte[] body) {
    Map m = [source_id: null, dest_id: null, namespace: null, payload_utf8: null]
    int p = 0
    while (p < body.length) {
        List tagRes = decodeVarint(body, p); if (tagRes == null) break
        long tag = (long) tagRes[0]; p = (int) tagRes[1]
        int field = (int) (tag >>> 3)
        int wtype = (int) (tag & 0x7)
        switch (wtype) {
            case 0:  // varint
                List v = decodeVarint(body, p); if (v == null) return m; p = (int) v[1]
                break
            case 2:  // length-delimited
                List lenRes = decodeVarint(body, p); if (lenRes == null) return m
                int len = (int) lenRes[0]; int start = (int) lenRes[1]
                if (len < 0 || start + len > body.length) return m
                if (field in [2, 3, 4, 6]) {
                    String s = new String(body, start, len, "UTF-8")
                    if (field == 2) m.source_id = s
                    else if (field == 3) m.dest_id = s
                    else if (field == 4) m.namespace = s
                    else if (field == 6) m.payload_utf8 = s
                }
                p = start + len
                break
            case 5: p += 4; break     // fixed32 (unused) - skip
            case 1: p += 8; break     // fixed64 (unused) - skip
            default: return m
        }
    }
    return m
}

private byte[] int32BE(long v) {
    return [(byte) ((v >> 24) & 0xFF), (byte) ((v >> 16) & 0xFF), (byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)] as byte[]
}

private long readInt32BE(byte[] buf, int off) {
    return ((buf[off] & 0xFFL) << 24) | ((buf[off + 1] & 0xFFL) << 16) | ((buf[off + 2] & 0xFFL) << 8) | (buf[off + 3] & 0xFFL)
}

// the + operator on byte[] is unreliable in the Hubitat sandbox (see FireTV driver)
private byte[] concatBytes(List<byte[]> arrays) {
    int total = 0
    for (byte[] a : arrays) { if (a) total += a.length }
    byte[] result = new byte[total]
    int pos = 0
    for (byte[] a : arrays) {
        if (a) { for (int i = 0; i < a.length; i++) { result[pos++] = a[i] } }
    }
    return result
}

// ============================================================================
// util
// ============================================================================
private boolean isConnectedState() { return state.conn in ["TP_CONNECTING", "READY", "APP_LAUNCHING", "APP_CONNECTED"] }

private String mapPlayerState(String ps) {
    switch (ps) {
        case "PLAYING":   return "playing"
        case "PAUSED":    return "paused"
        case "BUFFERING": return "buffering"
        default:          return "stopped"
    }
}

private void sendEventIfChanged(String name, def value, String unit = null) {
    if (value == null) return
    if (device.currentValue(name)?.toString() != value.toString()) {
        if (unit) sendEvent(name: name, value: value, unit: unit)
        else sendEvent(name: name, value: value)
    }
}

private boolean isEmpty(def v) { return v == null || (v instanceof String && v.trim().isEmpty()) }

private void logDebug(msg) { if (state.debug == true) logAt('debug', msg) }
private void logInfo(msg)  { logAt('info',  msg) }
private void logWarn(msg)  { logAt('warn',  msg) }
private void logError(msg) { logAt('error', msg) }
// every line is prefixed "GC+ [<device name>] " so the Hubitat Logs text-filter "GC+" shows the whole
// integration (app + all children) in one view, each line self-identifying its device.
private void logAt(String level, msg) { log."${level}"("GC+ [${device.displayName}] ${msg}") }
