import groovy.transform.Field

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** Android TV+ (App) **
 *
 * Discovers Android TV / Fire TV style ADB-capable devices and lets you choose which ones should exist as Hubitat devices.
 * Discovery is best-effort using mDNS service caches the hub already maintains; manual IP entry is always available.
 *
 * Selected rows are created as child devices under one parent device.
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

definition(
    name: 'Android TV+',
    namespace: 'jpage4500',
    author: 'Joe Page',
    description: 'Manage ADB devices (Fire TV / Android TV) from Hubitat',
    importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/android-tv-plus/android-tv-plus-app.groovy',
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

@Field static final String CHILD_DRIVER = 'Android TV+ Driver'
@Field static final String PARENT_DRIVER = 'Android TV+ Parent'
@Field static final Integer DEFAULT_ADB_PORT = 5555
// mDNS service types that identify an Android-TV-ish box. IMPORTANT: the port each of these advertises
// belongs to *that* service (Android TV remote = 6467, wireless debugging = a random TLS port) and is never
// the classic ADB-over-TCP port, so discovery always pins the ADB port to DEFAULT_ADB_PORT.
// Classic `adb connect <ip>:5555` advertises nothing at all, which is why SSDP is used as well.
@Field static final String CAST_SERVICE = '_googlecast._tcp'
// NB: the literal is repeated rather than referencing CAST_SERVICE -- one @Field static initialiser may
// not read another one in the Hubitat sandbox ("found in a static scope but doesn't refer to a local
// variable, static field or class"). Referencing it from inside a method is fine.
@Field static final List<String> MDNS_SERVICES = [
    '_googlecast._tcp',         // every Android TV / Google TV box has Cast built in -- see below
    '_androidtvremote2._tcp',   // Android TV / Google TV remote service
    '_androidtvremote._tcp',    // older Android TV remote service
    '_amzn-wplay._tcp',         // Fire TV (Amazon Whisperplay)
    '_adb-tls-connect._tcp'     // Android 11+ with "Wireless debugging" turned on
]

// Cast models that actually run Android TV, so ADB on 5555 is plausible. Plain Chromecast dongles,
// speakers and Nest displays answer Cast discovery too and are never ADB targets, so anything not
// matched here is listed but left unchecked rather than hidden.
@Field static final List<String> ANDROID_TV_HINTS = [
    'shield', 'google tv', 'android tv', 'bravia', 'aquos', 'tcl', 'hisense', 'philips',
    'xiaomi', 'mi tv', 'nokia', 'sharp', 'toshiba', 'onn', 'chromecast hd', 'chromecast 4k'
]

// Fire TV and Android TV both answer an SSDP M-SEARCH for the DIAL service. That is the only discovery
// path that reliably covers Fire TV (it never advertises the Android TV remote service). Plenty of
// non-ADB gear answers DIAL too, so SSDP-only rows are listed but not checked by default.
@Field static final List<String> SSDP_TERMS = [
    'urn:dial-multiscreen-org:service:dial:1'
]
@Field static final Long STALE_MS = 15 * 60 * 1000L

// Discovery is a setup-time activity, so background scanning stops on its own instead of multicasting an
// SSDP search at the whole LAN forever. Opening the app, saving it, or pressing Refresh Devices re-arms it.
@Field static final Integer DISCOVERY_WINDOW_MIN = 30

def installed() { updated() }

def updated() {
    unsubscribe()
    unschedule()
    subscribe(location, 'systemStart', 'bootHandler')
    // Subscribe broadly and filter in the handler: a per-term "ssdpTerm.<term>" subscription delivers
    // nothing at all if the term string doesn't match byte for byte, and gives no way to tell that apart
    // from "no device answered".
    subscribe(location, null, 'ssdpHandler', [filterEvents: false])
    registerMdns(true)
    createParentDevice()

    def parent = getParentDevice()
    if (parent) {
        parent.setPollInterval((settings.pollInterval ?: 30) as Integer)
        parent.setDebug(settings.debugOutput == true)
        parent.setCustomApps((settings.customApps ?: '') as String)
    }
    syncChildren()

    if (settings.debugOutput == true) {
        runIn(86400, 'debugOff')
        state.debugDisableMs = now() + 86400000L
    } else {
        state.remove('debugDisableMs')
    }

    // Polling is scheduled by each child driver now (see the driver's schedulePolling), so one
    // unresponsive device can't hold up anyone else's refresh.
    state.remove('discoveryUntilMs')      // unschedule() above wiped the tick; force a fresh window
    armDiscovery()
}

def uninstalled() {
    def parent = getParentDevice()
    if (parent) parent.removeAllChildren()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def bootHandler(evt) {
    registerMdns(true)
    state.remove('discoveryUntilMs')      // the hub's mDNS cache is empty after a reboot; scan again
    armDiscovery()
}

def debugOff() {
    logInfo('auto-disabling debug logging (24h elapsed)')
    app.updateSetting('debugOutput', [value: 'false', type: 'bool'])
    getParentDevice()?.setDebug(false)
    state.remove('debugDisableMs')
}

private void registerMdns(boolean force = false) {
    if (!force && state.lastRegisterMs && (now() - (state.lastRegisterMs as Long)) < 60000L) return
    List failed = []
    MDNS_SERVICES.each { svc ->
        try {
            registerMDNSListener(svc)
        } catch (Exception ex) {
            failed << svc
            logDebug("registerMDNSListener(${svc}) rejected: ${ex.message}")
        }
    }
    state.lastRegisterMs = now()
    state.mdnsAvailable = (failed.size() < MDNS_SERVICES.size())
    state.mdnsFailed = failed
    // worth saying out loud: a hub that won't take the listener will never cache that service type,
    // and the scan below then reports 0 records forever with nothing to explain why
    if (failed) logInfo("registerMdns: hub would not listen for ${failed.join(', ')}")
}

def mainPage() {
    registerMdns()
    armDiscovery()
    createParentDevice()
    Map candidates = discoverDevices()
    dynamicPage(name: 'mainPage', title: '', install: true, uninstall: true) {
        section(header('Android TV+')) {
            paragraph 'Manage ADB devices (Fire TV / Android TV) from Hubitat'
            paragraph 'Auto discovery will run for a short time after install. It may take a few seconds for results to appear - hit <b>Refresh Devices</b> to view latest results'
        }
        section(header('Devices')) {
            if (candidates.isEmpty()) {
                paragraph "No devices found yet. Click <b>Refresh Devices</b> or add one manually by IP."
            } else {
                Map childByDni = (getParentDevice()?.getChildDevices() ?: []).collectEntries { [(it.deviceNetworkId): it] }
                paragraph '<small>Checked devices are created in Hubitat. Uncheck to remove. Click Done to apply.</small>'
                candidates.sort { it.value.name ?: it.value.ip }.each { dni, d ->
                    def child = childByDni[dni]
                    input name: "sel_${cleanId(dni)}", type: 'bool', title: deviceRow(d, child),
                        defaultValue: (child != null || d.autoSelect != false), submitOnChange: true
                }
            }
            paragraph "<small>${discoveryStatus()}</small>"
            input name: 'rescan', type: 'button', title: 'Refresh Devices'
            int staleCount = candidates.findAll { dni, d -> isStale(d) }.size()
            if (staleCount > 0) {
                paragraph "<small>${staleCount} device(s) have not been seen in recent discovery scans.</small>"
                input name: 'forgetStale', type: 'button', title: "Forget ${staleCount} stale device(s)"
            }
        }
        section(header('Add a device by IP')) {
            paragraph 'Use this when auto-discovery does not find a device.'
            input name: 'manualIp', type: 'text', title: 'IP address', required: false, submitOnChange: true
            input name: 'manualPort', type: 'number', title: 'ADB port', defaultValue: DEFAULT_ADB_PORT, required: false, submitOnChange: true
            input name: 'manualName', type: 'text', title: 'Name (optional)', required: false, submitOnChange: true
            input name: 'addManual', type: 'button', title: 'Add device'
            if (state.manual) input name: 'clearManual', type: 'button', title: 'Clear manual entries'
        }
        section(header('Settings')) {
            input name: 'pollInterval', type: 'number', title: 'Refresh interval (seconds, 0 disables polling)', defaultValue: 30, range: '0..900', submitOnChange: true
            paragraph '<small>Each device refreshes on its own schedule, staggered so they do not all poll at once.</small>'
            input name: 'debugOutput', type: 'bool', title: 'Enable debug logging (auto-off after 24h)', defaultValue: false, submitOnChange: true
            if (settings.debugOutput == true && state.debugDisableMs) {
                paragraph "<span style='color:red'>Debug logging will be disabled at ${clockTime(new Date(state.debugDisableMs as Long))}</span>"
            }
        }
        section(header('Custom apps')) {
            input name: 'customApps', type: 'textarea', rows: 4, title: 'One per line: <b>Name = package.name</b>', required: false, submitOnChange: true
            paragraph '<small>Adds to the built-in list (Netflix, Prime Video, Disney+, YouTube, YouTube TV, Apple TV, HBO Max, Spotify, Plex, Twitch, Kodi) and applies to every device. The name is what <b>Current App Name</b> reports and what <b>appOpenByName</b> accepts.<br>' +
                'Example:<br><code>Kodi = org.xbmc.kodi<br>YouTube TV = com.google.android.youtube.tvunplugged</code><br>' +
                'If an app will not launch, append its start command: <code>Name = pkg | am start -n pkg/.MainActivity</code><br>' +
                'To find a package name, run <b>Send Shell Command</b> on a device with <code>pm list packages -3</code>.</small>'
        }
        section {
            paragraph "<small>Child devices are created under the '${CHILD_DRIVER}' parent. Click Done to apply changes.</small>"
        }
    }
}

def appButtonHandler(btn) {
    switch (btn) {
        case 'rescan':
            registerMdns(true)
            armDiscovery()
            scanMdns()
            ssdpDiscover()
            break
        case 'addManual':
            addManualDevice()
            break
        case 'clearManual':
            state.manual = []
            break
        case 'forgetStale':
            forgetStale()
            break
    }
}

private void addManualDevice() {
    if (isEmpty(settings.manualIp)) return
    String ip = settings.manualIp.trim()
    String name = settings.manualName?.trim()
    Integer port = ((settings.manualPort ?: DEFAULT_ADB_PORT) as Integer)
    if (port < 1 || port > 65535) port = DEFAULT_ADB_PORT

    List manual = state.manual ?: []
    if (!manual.any { it.ip == ip && (it.port as Integer) == port }) {
        manual << [ip: ip, port: port, name: name]
        state.manual = manual
    }

    createParentDevice()
    getParentDevice()?.createChild(manualDni(ip, port), name ?: ip, ip, "${port}", null, "manual")
    app.updateSetting('manualIp', [value: '', type: 'text'])
    app.updateSetting('manualPort', [value: "${DEFAULT_ADB_PORT}", type: 'number'])
    app.updateSetting('manualName', [value: '', type: 'text'])
}

// Extend the scanning window. A fresh window (or an expired one) also restarts the periodic tick and
// kicks off a scan right away; inside an active window this only pushes the deadline out.
private void armDiscovery() {
    boolean wasOff = ((state.discoveryUntilMs ?: 0L) as Long) < now()
    state.discoveryUntilMs = now() + (DISCOVERY_WINDOW_MIN * 60000L)
    if (!wasOff) return
    unschedule('discoveryTick')
    runEvery5Minutes('discoveryTick')
    runIn(4, 'ssdpDiscover')
    runIn(8, 'scanMdns')
    logInfo("discovery: scanning for new devices for the next ${DISCOVERY_WINDOW_MIN} minutes")
}

def discoveryTick() {
    if (now() > ((state.discoveryUntilMs ?: 0L) as Long)) {
        unschedule('discoveryTick')
        logInfo('discovery: background scanning stopped; open the app or press Refresh Devices to scan again')
        return
    }
    scanMdns()
    ssdpDiscover()
}

def scanMdns() {
    Map discovered = state.discovered ?: [:]
    int found = 0
    MDNS_SERVICES.each { svc ->
        Map entries = null
        try {
            entries = hubitat.helper.NetworkUtils.getRawMDNSEndpointsByMACForServiceType("${svc}.local.")
        } catch (Exception ex) {
            logDebug("scanMdns: ${svc} lookup unavailable: ${ex.message}")
            return
        }
        logDebug("scanMdns: ${svc} returned ${entries?.size() ?: 0} entry(ies)")
        entries?.each { mac, endpoint ->
            try {
                // The value is a typed bean, not a Map, so reading a property it does not declare throws --
                // and for non-Cast service types most of the interesting ones come back null. Read each one
                // through its own guarded accessor and never touch a name that might not exist.
                String ip = mdnsIp(endpoint)
                if (isEmpty(ip)) return
                Map txt = parseTxt(mdnsTxt(endpoint))
                String uuid = mdnsUuid(endpoint)
                String model = mdnsModel(endpoint) ?: txt.md ?: txt.model
                String name = mdnsName(endpoint) ?: txt.fn ?: txt.n ?: txt.name ?: model ?: ip

                // Cast is the discovery path that actually works here, but it answers for speakers,
                // displays and multiroom groups as well. Drop those outright; check only the models
                // known to run Android TV, and leave the rest listed for the user to try.
                boolean autoSelect = true
                if (svc == CAST_SERVICE) {
                    String castType = castDeviceType(txt.ca)
                    if (castType == 'audio' || castType == 'group') {
                        logDebug("scanMdns: ignoring cast ${castType} '${name}'")
                        return
                    }
                    autoSelect = looksLikeAndroidTv(model) || looksLikeAndroidTv(name)
                }
                found++
                String sourceId = uuid ?: (mac ? mac.toString() : ip)
                String dni = "AndroidTvPlus-${cleanId(sourceId)}"
                Map entry = (discovered[dni] instanceof Map) ? (discovered[dni] as Map) : [:]
                entry.ip = ip
                entry.port = DEFAULT_ADB_PORT      // never the advertised port -- see MDNS_SERVICES
                entry.name = name
                entry.uuid = uuid
                entry.mac = mac?.toString()
                if (model) entry.model = model
                entry.source = "mdns:${svc}"
                if (autoSelect) entry.autoSelect = true
                else if (entry.autoSelect == null) entry.autoSelect = false
                entry.lastSeenMs = now()
                discovered[dni] = entry
            } catch (Exception ex) {
                logWarn("scanMdns: skipping ${mac} on ${svc}: ${ex.message}")
            }
        }
    }
    state.lastScanFound = found
    if (found > 0) discovered.each { dni, d -> if (!d.lastSeenMs) d.lastSeenMs = now() }
    state.discovered = discovered
}

// Only these properties are known to exist on the mDNS endpoint bean; each is read in isolation so one
// missing property can't lose the whole entry.
private String mdnsIp(def ep)    { try { return ep.ip4Address?.toString() }    catch (Exception ignored) { return null } }
private String mdnsName(def ep)  { try { return ep.friendlyName?.toString() } catch (Exception ignored) { return null } }
private String mdnsModel(def ep) { try { return ep.model?.toString() }        catch (Exception ignored) { return null } }
private String mdnsUuid(def ep)  { try { return ep.deviceId?.toString() }     catch (Exception ignored) { return null } }
private def mdnsTxt(def ep)      { try { return ep.txtProperties }            catch (Exception ignored) { return null } }

// Cast 'ca' capabilities bitmask -> coarse device type (bits are community-documented, not official):
// bit0 = video out, bit2 = audio out, bit5 = multizone group.
private String castDeviceType(caValue) {
    if (caValue == null) return 'unknown'
    Integer ca
    try { ca = caValue.toString().trim() as Integer } catch (Exception ignored) { return 'unknown' }
    if (ca & 0x20) return 'group'
    if (ca & 0x01) return 'video'
    if (ca & 0x04) return 'audio'
    return 'unknown'
}

private boolean looksLikeAndroidTv(String text) {
    String t = (text ?: '').toLowerCase()
    if (!t) return false
    return ANDROID_TV_HINTS.any { t.contains(it) }
}

private Map parseTxt(raw) {
    if (raw == null) return [:]
    Map out = [:]
    try {
        if (raw instanceof Map) {
            raw.each { k, v -> if (k != null) out[k.toString()] = v?.toString() }
        } else {
            def items = (raw instanceof List || raw instanceof Object[]) ? raw.toList() : raw.toString().split('[\\r\\n ]+').toList()
            items.each { item ->
                String e = item?.toString()
                int i = e ? e.indexOf('=') : -1
                if (i > 0) out[e.substring(0, i)] = e.substring(i + 1)
            }
        }
    } catch (Exception ex) {
        logDebug("parseTxt: ${ex.message}")
        return [:]
    }
    return out
}

// ----------------------------------------------------------------------------
// SSDP (DIAL) discovery
// ----------------------------------------------------------------------------
def ssdpDiscover() {
    SSDP_TERMS.each { term ->
        try {
            sendHubCommand(new hubitat.device.HubAction("lan discovery ${term}", hubitat.device.Protocol.LAN))
            logDebug("ssdpDiscover: searching for ${term}")
        } catch (Exception ex) {
            logWarn("ssdpDiscover: ${term} search failed: ${ex.message}")
        }
    }
}

def ssdpHandler(evt) {
    String desc = evt?.description?.toString()
    if (isEmpty(desc) || !desc.contains('ssdpTerm')) return

    Map msg = null
    try {
        msg = parseLanMessage(desc)
    } catch (Exception ex) {
        logDebug("ssdpHandler: unparseable event: ${ex.message}")
        return
    }
    if (!msg) return

    // Record every service type that reaches us, without logging (SSDP chatter is constant on most
    // networks). The Devices section reports the tally, which is what tells you whether SSDP works here.
    String term = (msg.ssdpTerm ?: '').toString()
    List seen = (state.ssdpTermsSeen ?: []) as List
    if (term && !seen.contains(term)) {
        if (seen.size() < 25) seen << term
        state.ssdpTermsSeen = seen
    }
    if (!SSDP_TERMS.contains(term)) return

    String ip = hexToIp(msg.networkAddress as String)
    if (isEmpty(ip)) return
    String usn = (msg.ssdpUSN ?: '').toString()
    String uuid = usn ? usn.split('::')[0].replace('uuid:', '').trim() : null
    String dni = "AndroidTvPlus-${cleanId(uuid ?: ip)}"

    Map discovered = state.discovered ?: [:]
    Map entry = (discovered[dni] instanceof Map) ? (discovered[dni] as Map) : [:]
    boolean isNew = isEmpty(entry.ip as String)
    entry.ip = ip
    entry.port = DEFAULT_ADB_PORT
    if (uuid) entry.uuid = uuid
    if (msg.mac) entry.mac = msg.mac.toString()
    entry.source = 'ssdp:dial'
    // DIAL says "this is a TV-ish thing", not "ADB is reachable on 5555", so don't self-check the row
    if (entry.autoSelect == null) entry.autoSelect = false
    if (isEmpty(entry.name as String)) entry.name = ip
    entry.lastSeenMs = now()
    discovered[dni] = entry
    state.discovered = discovered
    if (((state.lastScanFound ?: 0) as Integer) < 1) state.lastScanFound = 1

    if (isNew) logInfo("ssdpHandler: found ${ip} via DIAL")
    if (isEmpty(entry.manufacturer as String)) {
        fetchSsdpDescription(dni, ip, hexToInt(msg.deviceAddress as String), msg.ssdpPath as String)
    }
}

// The SSDP reply only carries an address; the LOCATION document is what names the device.
private void fetchSsdpDescription(String dni, String ip, Integer httpPort, String path) {
    if (isEmpty(ip) || isEmpty(path)) return
    String url = "http://${ip}:${httpPort ?: 80}${path.startsWith('/') ? path : '/' + path}"
    try {
        asynchttpGet('ssdpDescriptionCallback', [uri: url, timeout: 10], [dni: dni])
    } catch (Exception ex) {
        logDebug("fetchSsdpDescription: ${url} failed: ${ex.message}")
    }
}

def ssdpDescriptionCallback(resp, data) {
    try {
        if (resp?.status != 200) return
        String body = resp.data?.toString()
        if (isEmpty(body)) return
        String dni = data?.dni
        Map discovered = state.discovered ?: [:]
        Map entry = (discovered[dni] instanceof Map) ? (discovered[dni] as Map) : null
        if (!entry) return

        String name = xmlTag(body, 'friendlyName')
        String maker = xmlTag(body, 'manufacturer')
        String model = xmlTag(body, 'modelName')
        if (name) entry.name = name
        if (maker) entry.manufacturer = maker
        if (model) entry.model = model
        discovered[dni] = entry
        state.discovered = discovered
        logDebug("ssdpDescriptionCallback: ${entry.ip} is '${entry.name}' (${maker} ${model})")
    } catch (Exception ex) {
        logDebug("ssdpDescriptionCallback: ${ex.message}")
    }
}

// one field out of a UPnP description -- not worth an XML parser
private String xmlTag(String body, String tag) {
    int a = body.indexOf("<${tag}>")
    if (a < 0) return null
    int b = body.indexOf("</${tag}>", a)
    if (b < 0) return null
    String v = body.substring(a + tag.length() + 2, b).trim()
    return isEmpty(v) ? null : v
}

private String hexToIp(String hex) {
    String h = hex?.trim()
    if (!h || h.length() < 8) return null
    try {
        return [h.substring(0, 2), h.substring(2, 4), h.substring(4, 6), h.substring(6, 8)]
            .collect { Integer.parseInt(it, 16).toString() }.join('.')
    } catch (Exception ignored) {
        return null
    }
}

private Integer hexToInt(String hex) {
    String h = hex?.trim()
    if (!h) return null
    try { return Integer.parseInt(h, 16) } catch (Exception ignored) { return null }
}

Map discoverDevices() {
    scanMdns()
    Map out = [:]
    (state.discovered ?: [:]).each { dni, d ->
        out[dni] = [
            ip: d.ip, port: DEFAULT_ADB_PORT, name: d.name, uuid: d.uuid, model: d.model,
            manufacturer: d.manufacturer, source: d.source, lastSeenMs: d.lastSeenMs,
            autoSelect: (d.autoSelect != false), type: 'discovered'
        ]
    }
    (state.manual ?: []).each { d ->
        out[manualDni(d.ip as String, (d.port ?: DEFAULT_ADB_PORT) as Integer)] = [
            ip: d.ip, port: (d.port ?: DEFAULT_ADB_PORT), name: (d.name ?: d.ip),
            uuid: null, model: null, source: 'manual', autoSelect: true, type: 'manual'
        ]
    }
    out = dedupeByIp(out)
    state.candidates = out
    return out
}

// One TV can answer on mDNS *and* SSDP, and may also have been added by hand -- each with a different id,
// so a different DNI. ADB only ever talks to <ip>:5555, so collapse rows that share an IP and keep the row
// the user is most invested in, folding in any details the dropped rows knew.
private Map dedupeByIp(Map candidates) {
    Set existing = ((getParentDevice()?.getChildDevices() ?: []).collect { it.deviceNetworkId.toString() }) as Set
    Map winner = [:]
    candidates.each { dni, d ->
        String ip = d?.ip?.toString()
        if (isEmpty(ip)) return
        String cur = winner[ip]
        if (cur == null || candidateRank(dni, d, existing) < candidateRank(cur, candidates[cur] as Map, existing)) {
            winner[ip] = dni
        }
    }
    Map out = [:]
    winner.each { ip, dni ->
        Map d = candidates[dni] as Map
        candidates.each { otherDni, od ->
            if (otherDni == dni || od?.ip?.toString() != ip) return
            if (isEmpty(d.model as String) && od.model) d.model = od.model
            if (isEmpty(d.manufacturer as String) && od.manufacturer) d.manufacturer = od.manufacturer
            if ((isEmpty(d.name as String) || d.name == ip) && od.name && od.name != ip) d.name = od.name
            if (od.autoSelect == true) d.autoSelect = true
        }
        out[dni] = d
    }
    return out
}

// lower wins
private int candidateRank(String dni, Map d, Set existing) {
    if (existing.contains(dni)) return 0                      // already a Hubitat device: never displace it
    if (d?.type == 'manual') return 1                         // the user typed this in on purpose
    if ((d?.source ?: '').toString().startsWith('mdns')) return 2
    return 3                                                  // ssdp / unknown
}

private void forgetStale() {
    Map cand = state.candidates ?: [:]
    List stale = cand.findAll { dni, d -> isStale(d) }.keySet().toList()
    if (stale.isEmpty()) return
    Map disc = state.discovered ?: [:]
    stale.each { dni ->
        disc.remove(dni)
        app.removeSetting("sel_${cleanId(dni)}")
    }
    state.discovered = disc
    state.candidates = cand.findAll { !stale.contains(it.key) }
    logInfo("forgetStale: forgot ${stale.size()} device(s)")
}

private boolean isStale(Map d) {
    if (d?.type == 'manual' || !d?.lastSeenMs) return false
    if (((state.lastScanFound ?: 0) as Integer) < 1) return false
    return (now() - (d.lastSeenMs as Long)) > STALE_MS
}

// Turns "nothing showed up" into something diagnosable at a glance.
private String discoveryStatus() {
    List parts = ["mDNS: ${state.lastScanFound ?: 0} record(s) on the last scan"]
    List failed = (state.mdnsFailed ?: []) as List
    if (failed) parts << "hub refused listeners for ${failed.join(', ')}"
    List seen = (state.ssdpTermsSeen ?: []) as List
    parts << (seen.isEmpty() ? 'SSDP: no replies seen yet' : "SSDP: ${seen.size()} service type(s) seen")
    Long until = (state.discoveryUntilMs ?: 0L) as Long
    parts << (until > now() ? "scanning until ${clockTime(new Date(until))}" : 'background scanning idle')
    return parts.join(' &#183; ')
}

// Jump straight to a created child's device page. It goes INSIDE the checkbox's title (which is already HTML),
// floated right, so it sits at the right edge of the same row rather than on a line of its own - a table can't
// do it, since the toggle's markup is generated by the hub and can't be wrapped in cells of ours.
// target=_blank keeps the app page (and its unsaved checkbox state) put. The hub renders the title inside a
// <label>, and a click on interactive content in a label is not forwarded to its control, so following the
// link does not toggle the checkbox. The glyph is a numeric entity so this file stays pure ASCII - a raw
// emoji character has to survive the editor/HPM/hub encoding path, and an entity is decoded by the browser.
private String deviceLink(dev, String label) {
    return "<a href='/device/edit/${dev.id}' target='_blank' style='float:right;margin-left:12px;font-weight:normal'>${label} &#10697;</a>"
}

private String deviceRow(Map d, child) {
    // floated first so it lands on the first line of the row instead of after the status line
    String top = child ? deviceLink(child, 'View') : ''
    top += "<b>${d.name ?: d.ip}</b> &#8212; ${d.ip}:${d.port}"
    String hardware = [d.manufacturer, d.model].findAll { it }.join(' ')
    if (hardware) top += " &#183; ${hardware}"
    if (d.type == 'manual') top += " &#183; <i>manual</i>"

    String extra = d.source ?: 'not added'
    if (child) {
        String conn = child.currentValue('connectionStatus') ?: 'idle'
        String appName = child.currentValue('currentAppName') ?: child.currentValue('currentApp')
        extra = conn
        if (appName && appName != 'unknown') extra += " &#183; ${appName}"
    }
    if (isStale(d)) extra = "not seen since ${clockTime(new Date(d.lastSeenMs as Long))}"
    return "${top}<br><span style='font-size:smaller;color:#666'>${extra}</span>"
}

private String parentDni() { "AndroidTvPlus-parent-${app.id}" }

private void createParentDevice() {
    def parent = getChildDevice(parentDni())
    if (parent) return
    try {
        parent = addChildDevice('jpage4500', PARENT_DRIVER, parentDni(), [label: 'Android TV+', isComponent: true, name: PARENT_DRIVER])
        parent.initialize()
        logInfo('createParentDevice: created parent device')
    } catch (Exception ex) {
        logError("createParentDevice: failed - install '${PARENT_DRIVER}' first: ${ex.message}")
    }
}

private getParentDevice() { getChildDevice(parentDni()) }

private void syncChildren() {
    def parent = getParentDevice()
    if (!parent) return
    Map cand = state.candidates ?: [:]
    // An empty candidate list is no evidence that the user's devices are gone (an mDNS read comes back
    // empty routinely, and SSDP replies land asynchronously). Never let it drive the delete pass below.
    if (cand.isEmpty()) {
        logDebug('syncChildren: no candidates known; leaving existing devices alone')
        return
    }

    Set wanted = [] as Set
    Set removedManual = [] as Set
    List manual = state.manual ?: []
    Set existing = (parent.getChildDevices() ?: []).collect { it.deviceNetworkId.toString() } as Set

    cand.each { dni, d ->
        if (isSelected(dni, d as Map, existing.contains(dni))) {
            parent.createChild(dni, d.name ?: d.ip, d.ip, "${d.port ?: DEFAULT_ADB_PORT}", d.uuid, d.type ?: 'discovered')
            wanted << dni
        } else if (d.type == 'manual') {
            manual = manual.findAll { manualDni(it.ip as String, (it.port ?: DEFAULT_ADB_PORT) as Integer) != dni }
            removedManual << dni
        }
    }
    state.manual = manual
    if (removedManual) {
        state.candidates = cand.findAll { !removedManual.contains(it.key) }
        removedManual.each { app.removeSetting("sel_${cleanId(it)}") }
    }
    parent.getChildDevices().each { child ->
        if (!wanted.contains(child.deviceNetworkId)) parent.deleteChild(child.deviceNetworkId)
    }
}

// An untouched checkbox has no setting yet, so the default depends on how the device turned up: mDNS rows
// (and anything that already has a device) default to selected, SSDP-only rows do not.
private boolean isSelected(String dni, Map d, boolean hasChild) {
    def v = settings["sel_${cleanId(dni)}"]
    if (v != null) return v != false
    return hasChild || (d?.autoSelect != false)
}

private String cleanId(String s) { (s ?: '').replaceAll('[^A-Za-z0-9]', '') }
private String manualDni(String ip, Integer port) { "AndroidTvPlus-${cleanId("${ip}:${port}")}" }
private boolean isEmpty(def v) { v == null || (v instanceof String && v.trim().isEmpty()) }
private String clockTime(Date d) { d ? d.format('M/d h:mm a') : '' }

private String header(String text) {
    return "<div style='color:#ffffff;font-weight:bold;background-color:#7a45f4;padding-top:10px;padding-bottom:10px;border:1px solid #000000;box-shadow:2px 3px #8B8F8F;border-radius:10px'><image style='padding:0px 10px 0px 10px;' src=https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/android-tv-plus/android-tv.png width='32'> ${text}</div>"
}

private void logDebug(msg) { if (settings.debugOutput) logAppAt('debug', msg) }
private void logInfo(msg)  { logAppAt('info', msg) }
private void logWarn(msg)  { logAppAt('warn', msg) }
private void logError(msg) { logAppAt('error', msg) }

private void logAppAt(String level, msg) {
    String out = "Android TV+ [App] ${msg}"
    switch (level) {
        case 'debug': log.debug(out); break
        case 'warn': log.warn(out); break
        case 'error': log.error(out); break
        default: log.info(out)
    }
}
