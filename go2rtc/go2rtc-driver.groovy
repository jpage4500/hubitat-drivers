/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** go2rtc Camera **
 *
 * One child device per camera (which may include multiple go2rtc stream qualities), created by the go2rtc app.
 * Exposes:
 *   - ImageCapture: the "image" attribute points directly at the live still URL
 *     ({server}/api/frame.jpeg?src=NAME) with a cache-busting &refresh=<epochMs> that only changes when a
 *     refresh is actually requested (take() / refresh() / the app's poll timer). Nothing is downloaded to the
 *     hub - the dashboard / browser fetches the frame straight from go2rtc.
 *   - VideoCapture: Hubitat has no useful stream definition here, so RTSP URLs are published in custom
 *     attributes: video (active quality), videoMain, videoSub, videoExt, etc.
 *   - selectStream(role): switch the active "video" attribute between configured qualities.
 *
 * Server URL / host / RTSP port / credentials are pushed from the app via the parent's configure() call.
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import groovy.transform.Field

@Field static final Map ROLE_ATTRS = [
    main: 'videoMain', sub: 'videoSub', ext: 'videoExt',
    low: 'videoLow', high: 'videoHigh', sd: 'videoSd', hd: 'videoHd'
]
@Field static final List ROLE_ORDER = ['main', 'high', 'hd', 'sub', 'low', 'sd', 'ext']

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

        attribute 'video', 'string'           // active RTSP stream URL
        attribute 'videoMain', 'string'       // main-quality RTSP URL (when configured)
        attribute 'videoSub', 'string'        // sub-quality RTSP URL (when configured)
        attribute 'videoExt', 'string'        // extra-quality RTSP URL (when configured)
        attribute 'videoLow', 'string'
        attribute 'videoHigh', 'string'
        attribute 'videoSd', 'string'
        attribute 'videoHd', 'string'
        attribute 'snapshotUrl', 'string'     // live still-image URL, no cache-buster
        attribute 'streamName', 'string'      // primary (main) go2rtc stream name
        attribute 'streamRoles', 'string'     // JSON map of role -> stream name
        attribute 'selectedStream', 'string'  // active quality role (main, sub, ...)
        attribute 'source', 'string'          // producer source for primary stream (password masked)
        attribute 'status', 'string'          // online / offline / degraded
        attribute 'imageTimestamp', 'string'

        command 'selectStream', [[name: 'role', type: 'STRING', description: 'Quality role: main, sub, ext, low, high, sd, hd']]
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
    configure(base, host, rtspPort, username, password, source, streamName, null)
}

def configure(String base, String host, Integer rtspPort, String username, String password, String source, String streamName, Map streams) {
    if (streamName) updateDataValue('streamName', streamName)
    if (base) updateDataValue('serverBase', base)
    if (host) updateDataValue('host', host)
    updateDataValue('rtspPort', "${rtspPort ?: 8554}")
    if (source != null) updateDataValue('source', source)
    if (streams != null && !streams.isEmpty()) updateDataValue('streamsJson', groovy.json.JsonOutput.toJson(streams))
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

private Map streamRoles() {
    String json = getDataValue('streamsJson')
    if (json) {
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(json)
            if (parsed instanceof Map && !parsed.isEmpty()) {
                Map normalized = [:]
                parsed.each { k, v -> normalized[k.toString().toLowerCase()] = v.toString() }
                return normalized
            }
        } catch (ignored) { }
    }
    String primary = streamName()
    return primary ? [main: primary] : [:]
}

private String rtspUrl(String name) {
    if (isEmpty(name)) return null
    String creds = isEmpty(state.username) ? '' : "${state.username}:${state.password ?: ''}@"
    return "rtsp://${creds}${host()}:${rtspPort()}/${name}"
}

private String pickDefaultRole(Map roles) {
    for (String role in ROLE_ORDER) {
        if (roles[role]) return role
    }
    return roles.keySet() ? roles.keySet().iterator().next() : null
}

// (re)publish RTSP urls for every configured role; "video" follows selectedStream.
// Uses the CURRENT cache-buster (state.imageRefreshMs) - it does NOT bump it.
private void publishUrls() {
    Map roles = streamRoles()
    if (roles.isEmpty()) return

    ROLE_ATTRS.each { role, attr ->
        String name = roles[role]
        sendEventIfChanged(attr, name ? rtspUrl(name) : null)
    }

    sendEventIfChanged('streamRoles', groovy.json.JsonOutput.toJson(roles))

    String selected = (state.selectedStream ?: device.currentValue('selectedStream') ?: 'main').toString().toLowerCase()
    if (!roles[selected]) selected = pickDefaultRole(roles)
    state.selectedStream = selected
    sendEventIfChanged('selectedStream', selected)

    String primary = roles.main ?: roles[selected]
    String activeName = roles[selected] ?: primary
    if (primary) updateDataValue('streamName', primary)
    sendEventIfChanged('streamName', primary)
    sendEventIfChanged('video', rtspUrl(activeName))

    if (serverBase() && primary) {
        sendEventIfChanged('snapshotUrl', snapshotUrl(primary))
        sendEventIfChanged('image', imageUrl(primary))
    }
    String src = getDataValue('source')
    if (src) sendEventIfChanged('source', src)
}

// live snapshot url (no cache-buster): {server}/api/frame.jpeg?src=NAME (+ optional width)
private String snapshotUrl(String name) {
    String n = name ?: streamName()
    String u = "${serverBase()}/api/frame.jpeg?src=${urlEnc(n)}"
    if (settings.snapshotWidth) u += "&w=${settings.snapshotWidth as Integer}"
    return u
}

// the "image" attribute url: snapshot url + cache-buster on real refresh only
private String imageUrl(String name) {
    String u = snapshotUrl(name ?: streamName())
    Long ts = state.imageRefreshMs as Long
    if (ts) u += "&refresh=${ts}"
    return u
}

// ============================================================================
// ImageCapture - point "image" at a fresh snapshot url (no download)
// ============================================================================
def take() {
    if (isEmpty(serverBase()) || isEmpty(streamName())) { logWarn 'take: server/stream not configured'; return }
    state.imageRefreshMs = now()
    sendEvent(name: 'image', value: imageUrl(null))
    sendEvent(name: 'imageTimestamp', value: nowStr())
    logDebug("take: image -> ${imageUrl(null)}")
}

// ============================================================================
// VideoCapture (Hubitat's built-in def isn't useful here; RTSP lives in the "video" attribute)
// ============================================================================
def capture(start, end, camera) {
    logInfo "capture: server-side recording is not supported - use the RTSP URL in the 'video' attribute (${device.currentValue('video')})"
}

def selectStream(String role) {
    Map roles = streamRoles()
    String r = role?.toString()?.toLowerCase()
    if (!roles[r]) {
        logWarn "selectStream: unknown role '${role}' (available: ${roles.keySet().sort()})"
        return
    }
    state.selectedStream = r
    publishUrls()
    logInfo "selectStream: ${r} -> ${device.currentValue('video')}"
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

// status: online if primary exists; degraded if primary ok but a mapped substream is missing
private void refreshStatus() {
    String primary = streamName()
    if (isEmpty(serverBase()) || isEmpty(primary)) return
    Map roles = streamRoles()
    boolean primaryOk = false
    List missing = []
    try {
        roles.each { role, name ->
            if (isEmpty(name)) return
            boolean present = checkStreamPresent(name)
            if (role == 'main' || name == primary) primaryOk = present
            else if (!present) missing << "${role}(${name})"
        }
        if (!primaryOk) primaryOk = checkStreamPresent(primary)
        if (primaryOk && missing) {
            setStatus('degraded')
            logDebug "refreshStatus: missing substream(s): ${missing.join(', ')}"
        } else if (primaryOk) {
            setStatus('online')
            refreshPrimarySource(primary)
        } else {
            setStatus('offline')
        }
    } catch (e) {
        setStatus('offline')
        logDebug "refreshStatus: ${e.message}"
    }
}

private boolean checkStreamPresent(String name) {
    Map params = [uri: "${serverBase()}/api/streams", query: [src: name], timeout: 10]
    addAuth(params)
    boolean found = false
    httpGet(params) { resp ->
        found = resp?.data instanceof Map
    }
    return found
}

private void refreshPrimarySource(String primary) {
    try {
        Map params = [uri: "${serverBase()}/api/streams", query: [src: primary], timeout: 10]
        addAuth(params)
        httpGet(params) { resp ->
            def data = resp?.data
            if (data instanceof Map) {
                def prod = data.producers
                if (prod instanceof List) {
                    def first = prod.find { it instanceof Map && it.url }
                    if (first) updateDataValue('source', maskUrl(first.url.toString()))
                }
            }
        }
    } catch (ignored) { }
}

// ============================================================================
// helpers called by the parent
// ============================================================================
def setDebug(flag)              { state.debug = (flag as Boolean) }
def setRefreshInterval(seconds) { state.refreshInterval = (seconds ?: 0) as Integer }

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

private void sendEventIfChanged(String name, def value) {
    sendEventIfChanged(name, value, null)
}

private void sendEventIfChanged(String name, def value, String unit) {
    if (value == null) return
    if (device.currentValue(name)?.toString() != value.toString()) {
        if (unit) sendEvent(name: name, value: value, unit: unit)
        else sendEvent(name: name, value: value)
    }
}

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
