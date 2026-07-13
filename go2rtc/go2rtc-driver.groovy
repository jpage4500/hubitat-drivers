/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** go2rtc Camera **
 *
 * One child device per go2rtc stream, created by the go2rtc app. Exposes:
 *   - ImageCapture: the "image" attribute points directly at the live still URL
 *     ({server}/api/frame.jpeg?src=NAME) with a cache-busting &refresh=<epochMs> that only changes when a
 *     refresh is actually requested (take() / refresh() / the app's poll timer). Nothing is downloaded to the
 *     hub - the dashboard / browser fetches the frame straight from go2rtc.
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

        attribute 'video', 'string'        // RTSP stream URL (rtsp://[user:pass@]host:port/NAME)
        attribute 'snapshotUrl', 'string'  // live still-image URL, no cache-buster ({server}/api/frame.jpeg?src=NAME)
        attribute 'streamName', 'string'   // go2rtc stream name this device maps to
        attribute 'source', 'string'       // producer source (from go2rtc, password masked)
        attribute 'status', 'string'       // online (stream present on server) / offline
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

// (re)publish the RTSP ("video"), snapshot, and image URLs from the stored server settings.
// Uses the CURRENT cache-buster (state.imageRefreshMs) - it does NOT bump it, so calling this on
// configure()/Done doesn't force dashboards to reload the image.
private void publishUrls() {
    String name = streamName()
    if (isEmpty(name)) return
    // RTSP url for the "video" attribute (VideoCapture has no usable stream attribute of its own)
    String creds = isEmpty(state.username) ? '' : "${state.username}:${state.password ?: ''}@"
    sendEventIfChanged('video', "rtsp://${creds}${host()}:${rtspPort()}/${name}")
    sendEventIfChanged('streamName', name)
    if (serverBase()) {
        sendEventIfChanged('snapshotUrl', snapshotUrl())
        sendEventIfChanged('image', imageUrl())
    }
    String src = getDataValue('source')
    if (src) sendEventIfChanged('source', src)
}

// live snapshot url (no cache-buster): {server}/api/frame.jpeg?src=NAME (+ optional width)
private String snapshotUrl() {
    String u = "${serverBase()}/api/frame.jpeg?src=${urlEnc(streamName())}"
    if (settings.snapshotWidth) u += "&w=${settings.snapshotWidth as Integer}"
    return u
}

// the "image" attribute url: snapshot url + a cache-buster that only changes on a real refresh, so the
// browser/dashboard re-fetches the frame exactly when we want it to (and caches it in between).
private String imageUrl() {
    String u = snapshotUrl()
    Long ts = state.imageRefreshMs as Long
    if (ts) u += "&refresh=${ts}"
    return u
}

// ============================================================================
// ImageCapture - point "image" at a fresh snapshot url (no download)
// ============================================================================
def take() {
    if (isEmpty(serverBase()) || isEmpty(streamName())) { logWarn 'take: server/stream not configured'; return }
    state.imageRefreshMs = now()                       // bump the cache-buster -> URL changes -> dashboards reload
    sendEvent(name: 'image', value: imageUrl())
    sendEvent(name: 'imageTimestamp', value: nowStr())
    logDebug("take: image -> ${imageUrl()}")
}

// ============================================================================
// VideoCapture (Hubitat's built-in def isn't useful here; RTSP lives in the "video" attribute)
// ============================================================================
def capture(start, end, camera) {
    logInfo "capture: server-side recording is not supported - use the RTSP URL in the 'video' attribute (${device.currentValue('video')})"
}

// ============================================================================
// Refresh - re-check status + bump the image cache-buster
// ============================================================================
def refresh() {
    publishUrls()
    refreshStatus()
    take()
    parent?.childStatusChanged()
}

// light-weight status check: GET {server}/api/streams?src=NAME (small JSON, not the image). If the stream is
// present the camera is "online"; also refresh the (masked) producer source. Any error -> "offline".
private void refreshStatus() {
    if (isEmpty(serverBase()) || isEmpty(streamName())) return
    try {
        Map params = [uri: "${serverBase()}/api/streams", query: [src: streamName()], timeout: 10]
        addAuth(params)
        httpGet(params) { resp ->
            def data = resp?.data
            if (data instanceof Map) {
                setStatus('online')
                def prod = data.producers
                if (prod instanceof List) {
                    def first = prod.find { it instanceof Map && it.url }
                    if (first) updateDataValue('source', maskUrl(first.url.toString()))
                }
            } else {
                setStatus('offline')
            }
        }
    } catch (e) {
        setStatus('offline')
        logDebug "refreshStatus: ${e.message}"
    }
}

// ============================================================================
// helpers called by the parent
// ============================================================================
def setDebug(flag)              { state.debug = (flag as Boolean) }
def setRefreshInterval(seconds) { state.refreshInterval = (seconds ?: 0) as Integer }   // informational; the app owns the timer

// ============================================================================
// util
// ============================================================================
private void addAuth(Map params) {
    if (!isEmpty(state.username)) {
        String creds = "${state.username}:${state.password ?: ''}"
        params.headers = (params.headers ?: [:]) + [Authorization: "Basic ${creds.bytes.encodeBase64().toString()}"]
    }
}

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

// hide any credentials in a source url before storing/showing it: rtsp://user:pass@host -> rtsp://***@host
private String maskUrl(String url) {
    if (!url) return url
    return url.replaceAll(/(?<=:\/\/)[^@\/]+@/, '***@')
}

private String urlEnc(String s) { return java.net.URLEncoder.encode(s ?: '', 'UTF-8') }
private String nowStr() { new Date().format('yyyy-MM-dd HH:mm:ss') }
private boolean isEmpty(def v) { return v == null || (v instanceof String && v.trim().isEmpty()) }

private void logDebug(msg) { if (state.debug == true) logAt('debug', msg) }
private void logInfo(msg)  { logAt('info',  msg) }
private void logWarn(msg)  { logAt('warn',  msg) }
private void logError(msg) { logAt('error', msg) }
private void logAt(String level, msg) { log."${level}"("go2rtc [${device.displayName}] ${msg}") }
