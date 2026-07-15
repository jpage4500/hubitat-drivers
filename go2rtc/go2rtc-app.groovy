import groovy.transform.Field

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** go2rtc (App) **
 *
 * Connects Hubitat to a running go2rtc server (https://github.com/AlexxIT/go2rtc) and turns each configured
 * camera into a Hubitat device. Multiple go2rtc streams (main, sub, ext, ...) for the same camera are grouped
 * into one device when they share a base name (e.g. front_door + front_door_sub) or when mapped in the UI.
 *
 * Discovery is done over go2rtc's REST API: GET {server}/api/streams returns a JSON object keyed by stream
 * name. Each selected camera group becomes a child device under a single top-level "go2rtc" parent device.
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
@Field static final List ROLE_SUFFIXES = ['main', 'sub', 'ext', 'low', 'high', 'sd', 'hd']

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
    clearRedundantMapSettings()
    syncChildren()
    def parent = getParentDevice()
    if (parent) {
        parent.setServer(serverBase(), serverHost(), rtspPort(), settings.username, settings.password)
        parent.setRefreshInterval((settings.refreshInterval ?: 0) as Integer)
        parent.setDebug(settings.debugOutput == true)
    }
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

def debugOff() {
    logInfo('auto-disabling debug logging (24h elapsed)')
    app.updateSetting('debugOutput', [value: 'false', type: 'bool'])
    getParentDevice()?.setDebug(false)
    state.remove('debugDisableMs')
}

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
    Map groups = buildCameraGroups(streams)
    dynamicPage(name: 'mainPage', title: '', install: true, uninstall: true) {
        section(header('go2rtc')) {
            paragraph 'Connect to your <a href="https://github.com/AlexxIT/go2rtc" target="_blank">go2rtc</a> server and create a Hubitat device for each camera. ' +
                'Main and sub streams for the same camera are grouped into one device. Each device exposes still images and RTSP URLs for every configured quality.'
        }

        section(header('Server')) {
            input name: 'serverUrl', type: 'text', title: 'go2rtc server URL', description: "e.g. http://192.168.0.160:${DEFAULT_API_PORT}/", required: true, submitOnChange: true
            if (haveServer) {
                if (state.lastFetchOk) {
                    paragraph "<span style='color:green'>&#10003; Connected</span> &mdash; found ${streams.size()} stream${streams.size() == 1 ? '' : 's'} " +
                        "in ${groups.size()} camera group${groups.size() == 1 ? '' : 's'} at ${serverBase()}"
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
                Map childByBase = (getParentDevice()?.getChildDevices() ?: []).collectEntries {
                    [(it.getDataValue('cameraBase') ?: it.getDataValue('streamName')): it]
                }
                if (groups.isEmpty()) {
                    paragraph 'No streams configured on the server yet. Add cameras to go2rtc, then click <b>Refresh</b>.'
                } else {
                    paragraph "<small>Checked cameras are created in Hubitat. Streams named <code>camera_sub</code>, <code>camera_main</code>, etc. are auto-grouped. " +
                        'Expand a camera below to override stream mapping. Hit <b>Done</b> to apply.</small>'
                    sortedGroupKeys(groups).each { base ->
                        String cid = cleanId(base)
                        Map roleMap = groups[base]
                        input name: "sel_${cid}", type: 'bool', title: groupRow(base, roleMap, childByBase[base]), defaultValue: true, submitOnChange: true
                    }
                }
                def orphans = findOrphanChildren(streams, groups)
                if (orphans) {
                    paragraph "<hr><b>Devices to remove</b><br><small>These devices no longer match a selected camera group (for example, old per-stream devices after grouping). They will be removed when you hit <b>Done</b>.</small>"
                    orphans.sort { it.getLabel() }.each { child ->
                        paragraph orphanRow(child)
                    }
                }
            }

            // per-camera stream mapping overrides (hideable sections)
            sortedGroupKeys(groups).each { base ->
                String cid = cleanId(base)
                if (settings["sel_${cid}"] != false) {
                    Map roleMap = groups[base]
                    List streamOptions = ['none'] + streams.keySet().sort()
                    section(hideable: true, hidden: true, header("Stream mapping: ${base}")) {
                        paragraph "<small>Override auto-grouping when stream names don't follow the <code>${base}_sub</code> convention.</small>"
                        ROLE_SUFFIXES.each { role ->
                            String key = "map_${cid}_${role}"
                            clearImplicitMapSetting(key, role, roleMap)
                            String displayVal = mapInputValue(key, role, roleMap, streamOptions)
                            input name: key, type: 'enum', title: "${role} stream",
                                options: streamOptions, defaultValue: displayVal, submitOnChange: true
                        }
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
            paragraph '<small>Selected cameras become child devices under the \'go2rtc\' parent device. Click <b>Done</b> to apply.</small>'
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
// camera grouping
// ----------------------------------------------------------------------------
// parse stream name into {base, role}; bare name or _main suffix -> role main
private Map parseStreamName(String name) {
    String lower = name.toLowerCase()
    for (String role in ROLE_SUFFIXES) {
        String suffix = "_${role}"
        if (lower.endsWith(suffix) && name.length() > suffix.length()) {
            return [base: name.substring(0, name.length() - suffix.length()), role: role]
        }
    }
    return [base: name, role: 'main']
}

// group discovered streams by camera base name
private Map buildCameraGroups(Map streams) {
    Map groups = [:]
    streams.keySet().each { name ->
        def parsed = parseStreamName(name)
        String base = parsed.base
        String role = parsed.role
        if (!groups[base]) groups[base] = [:]
        if (role == 'main' && groups[base].main) {
            // prefer bare name over explicit _main suffix
            if (name == base) groups[base].main = name
        } else {
            groups[base][role] = name
        }
    }
    return groups
}

private List sortedGroupKeys(Map groups) {
    return groups.keySet().sort { a, b -> a.toLowerCase() <=> b.toLowerCase() }
}

private String firstMappedStream(Map roleMap) {
    for (String role in ROLE_SUFFIXES) {
        if (roleMap[role]) return roleMap[role]
    }
    return roleMap.values() ? roleMap.values().iterator().next() : null
}

// ----------------------------------------------------------------------------
// stream mapping overrides — only persist real manual changes
//
// Hubitat enum inputs save a value on first Done even when it matches auto-grouping.
// That freezes grouping when go2rtc stream names change. We clear redundant map_*
// settings and only honor saved values that differ from the auto-detected role.
// ----------------------------------------------------------------------------
private String mapInputValue(String key, String role, Map autoGroup, List streamOptions) {
    def saved = settings[key]
    String autoVal = autoGroup[role]
    if (!isEmpty(saved) && saved != 'none' && streamOptions.contains(saved.toString())) {
        if (!autoVal || saved.toString() != autoVal) return saved.toString()
    }
    if (autoVal && streamOptions.contains(autoVal)) return autoVal
    return 'none'
}

// drop unset/redundant map settings so auto-grouping can evolve and defaultValue shows auto in the UI
private void clearImplicitMapSetting(String key, String role, Map autoGroup) {
    def saved = settings[key]
    String autoVal = autoGroup[role]
    if (!autoVal) return
    if (isEmpty(saved) || saved == 'none' || saved.toString() == autoVal) {
        try {
            app.removeSetting(key)
        } catch (ignored) { }
    }
}

private void clearRedundantMapSettings() {
    Map streams = state.streams ?: [:]
    buildCameraGroups(streams).each { base, autoGroup ->
        String cid = cleanId(base)
        ROLE_SUFFIXES.each { role ->
            clearImplicitMapSetting("map_${cid}_${role}", role, autoGroup)
        }
    }
}

// merge auto-grouping with per-camera UI overrides
private Map resolveRoleMap(String base, Map autoGroup, Map streams) {
    String cid = cleanId(base)
    Map result = [:]
    ROLE_SUFFIXES.each { role ->
        String key = "map_${cid}_${role}"
        def setting = settings[key]
        if (setting && setting != 'none' && streams.containsKey(setting.toString())) {
            String chosen = setting.toString()
            // only honor a saved value when it is a real manual override
            if (!autoGroup[role] || chosen != autoGroup[role]) result[role] = chosen
        }
        if (!result[role] && autoGroup[role] && streams.containsKey(autoGroup[role])) {
            result[role] = autoGroup[role]
        }
    }
    if (result.isEmpty()) {
        autoGroup.each { role, name ->
            if (streams.containsKey(name)) result[role] = name
        }
    }
    return result
}

// ----------------------------------------------------------------------------
// UI helpers
// ----------------------------------------------------------------------------
private String groupRow(String base, Map roleMap, child) {
    String primary = roleMap.main ?: firstMappedStream(roleMap)
    String baseUrl = serverBase()
    String snap = primary ? "${baseUrl}/api/frame.jpeg?src=${urlEnc(primary)}&w=320" : ''
    String roles = roleMap.keySet().sort().collect { role -> "${role}: <code>${roleMap[role]}</code>" }.join(' &middot; ')
    String s = "<b>${base}</b>"
    String extra = roles ?: 'no streams'
    if (child) extra += " &middot; <i>added</i>"
    String thumb = snap ? "<div><img src='${snap}' style='max-height:90px;max-width:100%;border-radius:6px;margin-top:6px' onerror=\"this.style.display='none'\"></div>" : ''
    return s + "<br><span style='font-size:smaller;color:#666'>${extra}</span>${thumb}"
}

private String orphanRow(child) {
    String sn = child.getDataValue('streamName') ?: child.getLabel()
    String base = child.getDataValue('cameraBase')
    String detail = base ? "group '${base}', stream '${sn}'" : "stream '${sn}'"
    return "<div style='opacity:0.5'>&#128465; <b>${child.getLabel()}</b> <span style='color:#b00'>(will be removed)</span>" +
        "<br><span style='font-size:smaller'>${detail}</span></div>"
}

private Set buildWantedDnis(Map streams, Map groups) {
    Set wanted = [] as Set
    groups.each { base, autoGroup ->
        String cid = cleanId(base)
        if (isGroupSelected(cid)) {
            Map roleMap = resolveRoleMap(base, autoGroup, streams)
            String primary = roleMap.main ?: firstMappedStream(roleMap)
            if (primary) wanted << childDni(base)
        }
    }
    return wanted
}

private List findOrphanChildren(Map streams, Map groups) {
    Set wanted = buildWantedDnis(streams, groups)
    return (getParentDevice()?.getChildDevices() ?: []).findAll { child ->
        !wanted.contains(child.deviceNetworkId)
    }
}

// ----------------------------------------------------------------------------
// child / parent devices
// ----------------------------------------------------------------------------
private String parentDni() { "go2rtc-parent-${app.id}" }
String childDni(String base) { "go2rtc-${cleanId(base)}" }

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

private void syncChildren() {
    def parent = getParentDevice()
    if (!parent) { logError 'syncChildren: no parent device'; return }
    Map streams = state.streams ?: [:]
    Map groups = buildCameraGroups(streams)
    Set wanted = buildWantedDnis(streams, groups)
    groups.each { base, autoGroup ->
        String cid = cleanId(base)
        if (isGroupSelected(cid)) {
            Map roleMap = resolveRoleMap(base, autoGroup, streams)
            String primary = roleMap.main ?: firstMappedStream(roleMap)
            if (primary) {
                String dni = childDni(base)
                def streamData = streams[primary]
                parent.createChild(dni, base, primary, roleMap, serverBase(), serverHost(), rtspPort(),
                    settings.username, settings.password, streamData?.source)
            }
        }
    }
    parent.reconcileChildren(wanted as List)
}

private boolean isGroupSelected(String cid) {
    return settings["sel_${cid}"] != false
}

// ----------------------------------------------------------------------------
// server url helpers
// ----------------------------------------------------------------------------
String serverBase() {
    String u = settings.serverUrl?.trim()
    if (isEmpty(u)) return null
    if (!(u ==~ /(?i)^https?:\/\/.*/)) u = "http://${u}"
    return u.replaceAll(/\/+$/, '')
}

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

private String maskUrl(String url) {
    if (!url) return url
    return url.replaceAll(/(?<=:\/\/)[^\/]+@/, '***@')
}

private boolean isEmpty(def v) { return v == null || (v instanceof String && v.trim().isEmpty()) }
private String clockTime(Date d) { d ? d.format('M/d h:mm a') : '' }

private void logDebug(msg) { if (settings.debugOutput) logAppAt('debug', msg) }
private void logInfo(msg)  { logAppAt('info',  msg) }
private void logWarn(msg)  { logAppAt('warn',  msg) }
private void logError(msg) { logAppAt('error', msg) }
private void logAppAt(String level, msg) { log."${level}"("go2rtc [App] ${msg}") }
