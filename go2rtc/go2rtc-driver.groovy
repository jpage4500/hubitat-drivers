/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** go2rtc Camera **
 *
 * One child device per go2rtc stream, created by the go2rtc app. Exposes:
 *   - ImageCapture: take() pulls a still from {server}/api/frame.jpeg?src=NAME, stores it in Hubitat's File
 *     Manager, and points the "image" attribute at it (file:<dni>) so dashboards render the snapshot.
 *   - VideoCapture: Hubitat has no useful stream definition here, so the camera's RTSP URL
 *     (rtsp://[user:pass@]host:rtspPort/NAME) is published in the custom "video" attribute.
 *
 * Server URL / host / RTSP port / credentials are pushed from the app via the parent's configure() call.
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

metadata {
    definition(
        name: 'go2rtc Camera',
        namespace: 'jpage4500',
        author: 'Joe Page',
        importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/go2rtc/go2rtc-driver.groovy'
    ) {
        capability 'ImageCapture'      // command take(); attribute image
        capability 'VideoCapture'      // command capture(); attribute clip (unused - see "video" below)
        capability 'Refresh'
        capability 'Initialize'

        command 'clearImage'

        attribute 'video', 'string'        // RTSP stream URL (rtsp://[user:pass@]host:port/NAME)
        attribute 'snapshotUrl', 'string'  // live still-image URL ({server}/api/frame.jpeg?src=NAME)
        attribute 'streamName', 'string'   // go2rtc stream name this device maps to
        attribute 'source', 'string'       // producer source (from go2rtc, password masked)
        attribute 'status', 'string'       // online (snapshot reachable) / offline
        attribute 'imageTimestamp', 'string'
    }

    preferences {
        input name: 'snapshotWidth', type: 'number', title: 'Snapshot width (px, blank = native)', required: false, range: '16..3840'
        // debug logging is a single toggle in the app (broadcast as state.debug); no per-device switch
    }
}

// ============================================================================
// lifecycle
// ============================================================================
def installed()   { logDebug('installed'); initialize() }
def updated()     { logDebug('updated'); refresh() }
def uninstalled() { deleteImageFile() }

def initialize() {
    logDebug('initialize')
    refresh()
}

// pushed from the app (via the parent) on create + whenever server settings change
def configure(String base, String host, Integer rtspPort, String username, String password, String source, String streamName) {
    if (streamName) updateDataValue('streamName', streamName)
    if (base) updateDataValue('serverBase', base)
    if (host) updateDataValue('host', host)
    updateDataValue('rtspPort', "${rtspPort ?: 8554}")
    if (source != null) updateDataValue('source', source)
    // creds live in state (not shown as prominent device data values); embedded into the RTSP url below
    if (username != null) state.username = username
    if (password != null) state.password = password
    publishUrls()
}

// ============================================================================
// derived URLs / attributes
// ============================================================================
private String streamName() { getDataValue('streamName') }
private String serverBase() { getDataValue('serverBase') }
private String host()       { getDataValue('host') }
private Integer rtspPort()  { (getDataValue('rtspPort') ?: '8554') as Integer }

// build + publish the RTSP ("video") and snapshot URLs from the stored server settings
private void publishUrls() {
    String name = streamName()
    if (isEmpty(name)) return
    // RTSP url for the "video" attribute (VideoCapture has no usable stream attribute of its own)
    String creds = isEmpty(state.username) ? '' : "${state.username}:${state.password ?: ''}@"
    String rtsp = "rtsp://${creds}${host()}:${rtspPort()}/${name}"
    sendEventIfChanged('video', rtsp)
    sendEventIfChanged('streamName', name)
    // live snapshot url (handy for HD+ image tiles / direct browser use; no creds so it stays shareable)
    if (serverBase()) sendEventIfChanged('snapshotUrl', snapshotUrl())
    String src = getDataValue('source')
    if (src) sendEventIfChanged('source', src)
}

// {server}/api/frame.jpeg?src=NAME (+ optional width)
private String snapshotUrl() {
    String u = "${serverBase()}/api/frame.jpeg?src=${urlEnc(streamName())}"
    if (settings.snapshotWidth) u += "&w=${settings.snapshotWidth as Integer}"
    return u
}

// ============================================================================
// ImageCapture
// ============================================================================
// pull a still from go2rtc, store it in the File Manager, and point the "image" attribute at it.
def take() {
    publishUrls()
    String name = streamName()
    if (isEmpty(serverBase()) || isEmpty(name)) { logWarn 'take: server/stream not configured'; return }
    try {
        byte[] img = fetchBytes(snapshotUrl())
        if (img && img.length > 0) {
            writeImageToFile(img)
            sendEvent(name: 'image', value: "file:${fileName()}", isStateChange: true)
            sendEvent(name: 'imageTimestamp', value: nowStr())
            setStatus('online')
            logDebug("take: captured ${img.length} bytes -> file:${fileName()}")
        } else {
            setStatus('offline')
            logWarn 'take: no image data returned'
        }
    } catch (groovy.lang.MissingMethodException e) {
        // uploadHubFile was added in 2.3.4.132
        logError(e.message?.contains('uploadHubFile')
            ? 'take failed: update Hubitat to at least 2.3.4.132 (File Manager API required)'
            : "take failed: ${e.message}")
    } catch (e) {
        setStatus('offline')
        logWarn "take failed: ${e.message}"
    }
}

def clearImage() {
    deleteImageFile()
    sendEvent(name: 'image', value: 'n/a')
    sendEvent(name: 'imageTimestamp', value: 'n/a')
}

// ============================================================================
// VideoCapture (Hubitat's built-in def isn't useful here; RTSP lives in the "video" attribute)
// ============================================================================
def capture(start, end, camera) {
    logInfo "capture: server-side recording is not supported - use the RTSP URL in the 'video' attribute (${device.currentValue('video')})"
}

// ============================================================================
// Refresh
// ============================================================================
def refresh() {
    publishUrls()
    take()
    parent?.childStatusChanged()
}

// ============================================================================
// helpers called by the parent
// ============================================================================
def setDebug(flag)             { state.debug = (flag as Boolean) }
def setRefreshInterval(seconds) { state.refreshInterval = (seconds ?: 0) as Integer }   // informational; the app owns the timer

// ============================================================================
// http / file
// ============================================================================
// fetch raw bytes (JPEG) - handle whatever shape httpGet hands back (stream / byte[] / string)
private byte[] fetchBytes(String url) {
    byte[] out = null
    Map params = [uri: url, timeout: 20]
    addAuth(params)
    httpGet(params) { resp ->
        def data = resp?.data
        if (data == null) return
        if (data instanceof byte[]) {
            out = data
        } else if (data instanceof String) {
            out = data.getBytes('ISO-8859-1')
        } else {
            // InputStream
            ByteArrayOutputStream bos = new ByteArrayOutputStream()
            byte[] buf = new byte[8192]
            int n
            while ((n = data.read(buf)) > 0) bos.write(buf, 0, n)
            out = bos.toByteArray()
        }
    }
    return out
}

private void addAuth(Map params) {
    if (!isEmpty(state.username)) {
        String creds = "${state.username}:${state.password ?: ''}"
        params.headers = (params.headers ?: [:]) + [Authorization: "Basic ${creds.bytes.encodeBase64().toString()}"]
    }
}

private String fileName() { "${device.getDeviceNetworkId()}.jpg" }
private void writeImageToFile(byte[] image) { if (image != null) uploadHubFile(fileName(), image) }
private void deleteImageFile() { try { deleteHubFile(fileName()) } catch (ignored) { } }

// ============================================================================
// util
// ============================================================================
private void setStatus(String s) {
    if (device.currentValue('status') != s) {
        sendEvent(name: 'status', value: s)
        parent?.childStatusChanged()
    }
}

private void sendEventIfChanged(String name, def value, String unit = null) {
    if (value == null) return
    if (device.currentValue(name)?.toString() != value.toString()) {
        if (unit) sendEvent(name: name, value: value, unit: unit)
        else sendEvent(name: name, value: value)
    }
}

private String urlEnc(String s) { return java.net.URLEncoder.encode(s ?: '', 'UTF-8') }
private String nowStr() { new Date().format('yyyy-MM-dd HH:mm:ss') }
private boolean isEmpty(def v) { return v == null || (v instanceof String && v.trim().isEmpty()) }

private void logDebug(msg) { if (state.debug == true) logAt('debug', msg) }
private void logInfo(msg)  { logAt('info',  msg) }
private void logWarn(msg)  { logAt('warn',  msg) }
private void logError(msg) { logAt('error', msg) }
private void logAt(String level, msg) { log."${level}"("go2rtc [${device.displayName}] ${msg}") }
