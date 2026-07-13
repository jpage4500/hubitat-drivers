import groovy.transform.Field

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** go2rtc (App) **
 *
 * Connects Hubitat to a running go2rtc server (https://github.com/AlexxIT/go2rtc) and turns each configured
 * camera / stream into a Hubitat device.
 *
 * Discovery is done over go2rtc's REST API: GET {server}/api/streams returns a JSON object keyed by stream
 * name. Each selected stream becomes a child device under a single top-level "go2rtc" parent device. A child
 * device exposes a still image (ImageCapture, pulled from {server}/api/frame.jpeg?src=NAME) and the camera's
 * RTSP URL (rtsp://{host}:{rtspPort}/NAME, in the "video" attribute).
 *
 * Because go2rtc stream names are user-editable, a child whose stream no longer exists on the server is
 * detected on each page render and listed (disabled) at the bottom - it is removed when you hit Done.
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

definition(

    name: 'go2rtc',
    namespace: 'jpage4500',
    author: 'Joe Page',
    description: 'Turn go2rtc camera streams into Hubitat devices (still image + RTSP)',
    importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/go2rtc/go2rtc-app.groovy',
    category: 'Integrations',
    menu: 'Integrations',
    oauth: false,
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: ''
)

preferences {
    page(name: 'mainPage')
}

@Field static final String PARENT_DRIVER = 'go2rtc Parent'
@Field static final Integer DEFAULT_API_PORT = 1984
@Field static final Integer DEFAULT_RTSP_PORT = 8554

// ----------------------------------------------------------------------------
// lifecycle
// ----------------------------------------------------------------------------
def installed() { updated() }

def updated() {
    logDebug('updated')
    unsubscribe()
    unschedule()
    createParentDevice()
    fetchStreams()
    syncChildren()
    def parent = getParentDevice()
    if (parent) {
        parent.setServer(serverBase(), serverHost(), rtspPort(), settings.username, settings.password)
        parent.setRefreshInterval((settings.refreshInterval ?: 0) as Integer)
        parent.setDebug(settings.debugOutput == true)   // single toggle -> broadcast to parent + all children
    }
    // debug logging auto-disables 24h after being enabled so verbose logs are never left on
    if (settings.debugOutput == true) {
        runIn(86400, 'debugOff')
        state.debugDisableMs = now() + 86400000
    } else {
        state.remove('debugDisableMs')
    }
    schedulePolling()
}

def uninstalled() {
    def parent = getParentDevice()
    if (parent) parent.removeAllChildren()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// scheduled by updated() 24h after debug is enabled: clear the app toggle + broadcast off to parent/children
def debugOff() {
    logInfo('auto-disabling debug logging (24h elapsed)')
    app.updateSetting('debugOutput', [value: 'false', type: 'bool'])
    getParentDevice()?.setDebug(false)
    state.remove('debugDisableMs')
}

// central polling: ONE timer in the app refreshes every child (each child re-pulls its snapshot + status).
// 0 = off (a still is still captured once when the child is created / on Refresh).
private void schedulePolling() {
    unschedule('pollDevices')
    Integer sec = (settings.refreshInterval ?: 0) as Integer
    if (sec <= 0) return
    if (sec < 60) schedule("0/${sec} * * * * ?", 'pollDevices')
    else schedule("0 0/${Math.max(1, (sec / 60) as Integer)} * * * ?", 'pollDevices')
}

def pollDevices() {
    def parent = getParentDevice()
    def kids = (parent?.getChildDevices()) ?: []
    logDebug("pollDevices: refreshing ${kids.size()} device(s)")
    kids.each { try { it.refresh() } catch (e) { logWarn "pollDevices: ${it} - ${e.message}" } }
}

// ----------------------------------------------------------------------------
// UI
// ----------------------------------------------------------------------------
def mainPage() {
    createParentDevice()
    boolean haveServer = !isEmpty(settings.serverUrl)
    Map streams = haveServer ? fetchStreams() : [:]
    dynamicPage(name: 'mainPage', title: '', install: true, uninstall: true) {
        section(header('go2rtc')) {
            paragraph 'Connect to your <a href="https://github.com/AlexxIT/go2rtc" target="_blank">go2rtc</a> server and create a Hubitat device for each camera. ' +
                'Each device exposes a still image (Image Capture) and the camera\'s RTSP stream URL, so it works on dashboards and in the HD+ app.'
        }

        section(header('Server')) {
            input name: 'serverUrl', type: 'text', title: 'go2rtc server URL', description: "e.g. http://192.168.0.160:${DEFAULT_API_PORT}/", required: true, submitOnChange: true
            if (haveServer) {
                if (state.lastFetchOk) {
                    paragraph "<span style='color:green'>&#10003; Connected</span> &mdash; found ${streams.size()} stream${streams.size() == 1 ? '' : 's'} at ${serverBase()}"
                } else {
                    paragraph "<span style='color:red'>&#10007; Could not reach ${serverBase()}</span>${state.lastError ? " &mdash; ${state.lastError}" : ''}<br>" +
                        '<small>Check the URL (include http:// and the port, default 1984) and that go2rtc is running.</small>'
                }
            }
            input name: 'refreshServer', type: 'button', title: 'Refresh'
        }

        section(hideable: true, hidden: true, header('Advanced')) {
            input name: 'rtspPort', type: 'number', title: 'RTSP port', description: "go2rtc RTSP server port (default ${DEFAULT_RTSP_PORT})", defaultValue: DEFAULT_RTSP_PORT, range: '1..65535', submitOnChange: true
            input name: 'username', type: 'text', title: 'API username (optional)', required: false, submitOnChange: true
            input name: 'password', type: 'password', title: 'API password (optional)', required: false, submitOnChange: true
            paragraph '<small>Only needed if you have enabled authentication on go2rtc. Credentials are used for the API and embedded in the RTSP URL.</small>'
        }

        if (haveServer && state.lastFetchOk) {
            section(header('Cameras')) {
                Map childByName = (getParentDevice()?.getChildDevices() ?: []).collectEntries { [(it.getDataValue('streamName')): it] }
                if (streams.isEmpty()) {
                    paragraph 'No streams configured on the server yet. Add cameras to go2rtc, then click <b>Refresh</b>.'
                } else {
                    paragraph "<small>Checked cameras are created in Hubitat. Uncheck to remove. Hit <b>Done</b> to apply changes.</small>"
                    streams.sort { it.key.toLowerCase() }.each { name, d ->
                        input name: "sel_${cleanId(name)}", type: 'bool', title: deviceRow(name, d, childByName[name]), defaultValue: true, submitOnChange: true
                    }
                }
                // children whose stream no longer exists on the server -> will be removed on Done
                def orphans = (getParentDevice()?.getChildDevices() ?: []).findAll { !streams.containsKey(it.getDataValue('streamName')) }
                if (orphans) {
                    paragraph "<hr><b>No longer on the server</b><br><small>These cameras were removed or renamed on go2rtc and will be deleted when you hit Done.</small>"
                    orphans.sort { it.getLabel() }.each { child ->
                        paragraph orphanRow(child)
                    }
                }
            }
        }

        section(header('Settings')) {
            input name: 'refreshInterval', type: 'number', title: 'Snapshot refresh interval (seconds, 0 = off)', defaultValue: 0, range: '0..86400', submitOnChange: true
            paragraph '<small>How often each camera re-captures its still image and re-checks its status. Leave at 0 to only capture on demand (Take / Refresh).</small>'
            input name: 'debugOutput', type: 'bool', title: 'Enable debug logging (auto-off after 24h)', defaultValue: false, submitOnChange: true
        }
        section {
            if (settings.debugOutput == true && state.debugDisableMs) {
                paragraph "<span style='color:red'>Debug logging will be disabled at ${clockTime(new Date(state.debugDisableMs as Long))}</span>"
            }
            paragraph "<small>Selected cameras become child devices under the 'go2rtc' parent device. Click <b>Done</b> to apply.</small>"
        }
    }
}

def appButtonHandler(btn) {
    switch (btn) {
        case 'refreshServer':
            fetchStreams()
            break
    }
}

// ----------------------------------------------------------------------------
// discovery (go2rtc REST API)
// ----------------------------------------------------------------------------
// GET {server}/api/streams -> JSON object keyed by stream name; each value has producers[]/consumers[].
// We keep a light summary (source url masked, producer/consumer counts) per stream in state.streams.
Map fetchStreams() {
    String base = serverBase()
    Map result = [:]
    if (!base) { state.streams = result; return result }
    try {
        Map params = [uri: "${base}/api/streams", timeout: 10]
        addAuth(params)
        httpGet(params) { resp ->
            def data = resp?.data
            if (data instanceof Map) {
                data.each { name, info ->
                    if (name == null) return
                    result[name.toString()] = summarizeStream(info)
                }
            }
        }
        state.lastFetchOk = true
        state.lastError = null
        logDebug("fetchStreams: ${result.size()} stream(s)")
    } catch (e) {
        state.lastFetchOk = false
        state.lastError = e.message
        logWarn "fetchStreams failed: ${e.message}"
    }
    state.streams = result
    return result
}

// pull a small display summary out of one stream's {producers:[...], consumers:[...]} object
private Map summarizeStream(info) {
    String source = null
    int producers = 0, consumers = 0
    try {
        if (info instanceof Map) {
            def prod = info.producers
            def cons = info.consumers
            if (prod instanceof List) {
                producers = prod.size()
                def first = prod.find { it instanceof Map && it.url }
                if (first) source = maskUrl(first.url.toString())
            }
            if (cons instanceof List) consumers = cons.size()
        }
    } catch (ignored) { }
    return [source: source, producers: producers, consumers: consumers]
}

// ----------------------------------------------------------------------------
// UI helpers
// ----------------------------------------------------------------------------
// one selectable camera row: name + a live snapshot thumbnail + source / status line
private String deviceRow(String name, Map d, child) {
    String base = serverBase()
    String snap = "${base}/api/frame.jpeg?src=${urlEnc(name)}&w=320"
    String s = "<b>${name}</b>"
    String extra = d.source ? "source: ${d.source}" : 'no source configured'
    if (d.consumers) extra += " &middot; ${d.consumers} viewer${d.consumers == 1 ? '' : 's'}"
    if (child) extra += " &middot; <i>added</i>"
    String thumb = "<div><img src='${snap}' style='max-height:90px;max-width:100%;border-radius:6px;margin-top:6px' onerror=\"this.style.display='none'\"></div>"
    return s + "<br><span style='font-size:smaller;color:#666'>${extra}</span>${thumb}"
}

// a disabled row for a child whose stream is gone from the server (removed on Done)
private String orphanRow(child) {
    String sn = child.getDataValue('streamName') ?: child.getLabel()
    return "<div style='opacity:0.5'>&#128465; <b>${child.getLabel()}</b> <span style='color:#b00'>(will be removed)</span>" +
        "<br><span style='font-size:smaller'>stream '${sn}' no longer exists on the server</span></div>"
}

// ----------------------------------------------------------------------------
// child / parent devices
// ----------------------------------------------------------------------------
private String parentDni() { "go2rtc-parent-${app.id}" }
String childDni(String name) { "go2rtc-${cleanId(name)}" }

private void createParentDevice() {
    def parent = getChildDevice(parentDni())
    if (!parent) {
        try {
            parent = addChildDevice('jpage4500', PARENT_DRIVER, parentDni(),
                [label: 'go2rtc', isComponent: true, name: PARENT_DRIVER])
            parent.initialize()
            logInfo 'createParentDevice: created parent device'
        } catch (e) {
            logError "createParentDevice: failed - is the '${PARENT_DRIVER}' driver installed? ${e.message}"
        }
    }
}

private getParentDevice() { getChildDevice(parentDni()) }

// reconcile wanted cameras (a checkbox per stream, checked by default) against existing child devices.
// streams that vanished from the server aren't candidates -> not wanted -> deleted here.
private void syncChildren() {
    def parent = getParentDevice()
    if (!parent) { logError 'syncChildren: no parent device'; return }
    Set wanted = [] as Set
    (state.streams ?: [:]).each { name, d ->
        String dni = childDni(name)
        if (isSelected(dni)) {
            parent.createChild(dni, name, serverBase(), serverHost(), rtspPort(), settings.username, settings.password, d.source)
            wanted << dni
        }
    }
    parent.getChildDevices().each { child ->
        if (!wanted.contains(child.deviceNetworkId)) parent.deleteChild(child.deviceNetworkId)
    }
}
private boolean isSelected(String dni) {
    // dni is "go2rtc-<cleanId>"; the toggle setting is "sel_<cleanId>". checked (true) by default.
    return settings["sel_${dni.replaceFirst('go2rtc-', '')}"] != false
}

// ----------------------------------------------------------------------------
// server url helpers
// ----------------------------------------------------------------------------
// normalized base url (adds http://, strips trailing slashes); null if unset
String serverBase() {
    String u = settings.serverUrl?.trim()
    if (isEmpty(u)) return null
    if (!(u ==~ /(?i)^https?:\/\/.*/)) u = "http://${u}"
    return u.replaceAll(/\/+$/, '')
}

// host portion of the server url (for building the RTSP url)
String serverHost() {
    String u = serverBase()
    if (!u) return null
    def m = (u =~ /(?i)^https?:\/\/([^:\/]+)/)
    return m ? m[0][1] : null
}

Integer rtspPort() { return (settings.rtspPort ?: DEFAULT_RTSP_PORT) as Integer }

private void addAuth(Map params) {
    if (!isEmpty(settings.username)) {
        String creds = "${settings.username}:${settings.password ?: ''}"
        params.headers = (params.headers ?: [:]) + [Authorization: "Basic ${creds.bytes.encodeBase64().toString()}"]
    }
}

// ----------------------------------------------------------------------------
// util
// ----------------------------------------------------------------------------
private String header(String text) {
    return "<div style='color:#ffffff;font-weight: bold;background-color:#8652ff;padding-top: 10px;padding-bottom: 10px;border: 1px solid #000000;box-shadow: 2px 3px #8B8F8F;border-radius: 10px'><image style='padding: 0px 10px 0px 10px;' src=https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/go2rtc/go2rtc.png width='40'> ${text}</div>"
}

private String cleanId(String s) { return (s ?: '').replaceAll('[^A-Za-z0-9]', '') }
private String urlEnc(String s) { return java.net.URLEncoder.encode(s ?: '', 'UTF-8') }

// hide any credentials in a source url before showing it in the UI: rtsp://user:pass@host -> rtsp://***@host
private String maskUrl(String url) {
    if (!url) return url
    return url.replaceAll(/(?<=:\/\/)[^@\/]+@/, '***@')
}

private boolean isEmpty(def v) { return v == null || (v instanceof String && v.trim().isEmpty()) }
private String clockTime(Date d) { d ? d.format('M/d h:mm a') : '' }

private void logDebug(msg) { if (settings.debugOutput) logAppAt('debug', msg) }
private void logInfo(msg)  { logAppAt('info',  msg) }
private void logWarn(msg)  { logAppAt('warn',  msg) }
private void logError(msg) { logAppAt('error', msg) }
private void logAppAt(String level, msg) { log."${level}"("go2rtc [App] ${msg}") }
