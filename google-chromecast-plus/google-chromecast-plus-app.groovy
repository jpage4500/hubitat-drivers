import groovy.transform.Field

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** Google Chromecast+ (App) **
 *
 * Discovers Google Cast / Chromecast devices on the LAN and lets you pick which ones to control from Hubitat.
 *
 * Discovery uses Hubitat's apps-only mDNS listener API (registerMDNSListener / getMDNSEntries, firmware
 * 2.4.1.151+) for the "_googlecast._tcp" service, with manual IP entry as a fallback. Selected devices are
 * created as child devices under a single top-level "Google Chromecast+" parent device (the same driver
 * runs in parent mode there and child mode on each Chromecast).
 *
 * Note: the hub fills its mDNS cache in the background AFTER the listener is registered, so discovery can
 * take 15-60s on first install. Results are accumulated into state so devices stay listed once seen.
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

definition(

    name: 'Google Chromecast+',
    namespace: 'jpage4500',
    author: 'Joe Page',
    description: 'Discover and control Google Cast / Chromecast devices (status, media, TTS)',
    importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/google-chromecast-plus/google-chromecast-plus-app.groovy',
    category: 'Convenience',
    menu: "Integrations",
    oauth: false,
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: ''
)

preferences {
    page(name: 'mainPage')
}

@Field static final String DRIVER = 'Google Chromecast+'
@Field static final String PARENT_DRIVER = 'Google Chromecast+ Parent'
@Field static final String MDNS_SERVICE = '_googlecast._tcp'
@Field static final Integer DEFAULT_PORT = 8009
// how long a device can be missing from the hub's mDNS cache before its row is flagged as stale
// (~3 missed scans while background discovery is running; once it stops, the stamp is still refreshed by the
// synchronous scanMdns() on every page render, so a device that is actually present is never flagged)
@Field static final Long STALE_MS = 15 * 60 * 1000

// Discovery is a setup-time activity, so background scanning stops on its own instead of re-reading the hub's
// mDNS cache forever. Opening the app, hitting Done, a hub reboot, or Refresh Devices re-arms it.
@Field static final Integer DISCOVERY_WINDOW_MIN = 30

// ----------------------------------------------------------------------------
// lifecycle
// ----------------------------------------------------------------------------
def installed() { updated() }

def updated() {
    logDebug('updated')
    unsubscribe()
    unschedule()
    subscribe(location, 'systemStart', 'bootHandler')
    registerMdns(true)
    createParentDevice()
    syncChildren()
    def parent = getParentDevice()
    if (parent) {
        parent.setRefreshInterval((settings.refreshInterval ?: 60) as Integer)
        parent.setDebug(settings.debugOutput == true)   // single toggle -> broadcast to parent + all children
    }
    // debug logging auto-disables 24h after being enabled so verbose logs are never left on
    if (settings.debugOutput == true) {
        runIn(86400, 'debugOff')
        state.debugDisableMs = now() + 86400000     // when auto-off fires; surfaced on the main page
    } else {
        state.remove('debugDisableMs')
    }
    schedulePolling()
    state.remove('discoveryUntilMs')    // unschedule() above wiped the tick; force a fresh window
    armDiscovery()
}

def uninstalled() {
    def parent = getParentDevice()
    if (parent) parent.removeAllChildren()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// mDNS listener is cleared on reboot -> re-register on system start
def bootHandler(evt) {
    registerMdns(true)
    state.remove('discoveryUntilMs')    // the hub's mDNS cache is empty after a reboot; scan again
    armDiscovery()
}

// scheduled by updated() 24h after debug is enabled: clear the app toggle + broadcast off to parent/children
def debugOff() {
    logInfo('auto-disabling debug logging (24h elapsed)')
    app.updateSetting('debugOutput', [value: 'false', type: 'bool'])
    getParentDevice()?.setDebug(false)
    state.remove('debugDisableMs')
}

// throttled so rapid page re-renders don't spam the hub; force=true always re-registers
private void registerMdns(boolean force = false) {
    if (!force && state.lastRegisterMs && (now() - (state.lastRegisterMs as Long)) < 60000) return
    try {
        registerMDNSListener(MDNS_SERVICE)
        state.lastRegisterMs = now()
        state.mdnsAvailable = true
        logDebug("registered mDNS listener for ${MDNS_SERVICE}")
    } catch (e) {
        logWarn "registerMDNSListener unavailable (needs firmware 2.4.1.151+): ${e.message}"
        state.mdnsAvailable = false
    }
}

// central polling: ONE timer in the app refreshes every child (each child does a short on-demand
// connect -> GET_STATUS -> disconnect). Keeps all cadence + a single summary log in one place.
private void schedulePolling() {
    unschedule('pollDevices')
    Integer sec = (settings.refreshInterval ?: 60) as Integer
    if (sec < 60) schedule("0/${sec} * * * * ?", 'pollDevices')
    else schedule("0 0/${(sec / 60) as Integer} * * * ?", 'pollDevices')
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
    registerMdns()          // ensure the listener is active as soon as the page is opened
    armDiscovery()
    createParentDevice()
    Map candidates = discoverDevices()
    dynamicPage(name: 'mainPage', title: '', install: true, uninstall: true) {
        section(header('Google Chromecast+')) {
            paragraph 'Discover Chromecast / Google Cast devices and create a Hubitat device for each one you want to monitor and control.'
        }
        section(header('Devices')) {
            if (candidates.isEmpty()) {
                paragraph "No devices found yet. Discovery reads the hub's mDNS cache &mdash; click <b>Rescan</b> and reopen, or add one by IP below."
            } else {
                Map childByDni = (getParentDevice()?.getChildDevices() ?: []).collectEntries { [(it.deviceNetworkId): it] }
                paragraph "<small>Checked devices are created in Hubitat. Uncheck to remove. Hit DONE to apply changes</small>"
                candidates.sort { it.value.name }.each { dni, d ->
                    input name: "sel_${cleanId(dni)}", type: 'bool', title: deviceRow(d, childByDni[dni]), defaultValue: true, submitOnChange: true
                }
            }
            input name: 'rescan', type: 'button', title: 'Refresh Devices'
            paragraph "<small>${discoveryStatus()}</small>"
            int staleCount = candidates.findAll { dni, d -> isStale(d) }.size()
            if (staleCount > 0) {
                paragraph "<small>${staleCount} device(s) above haven't answered mDNS for a while. <b>Forget</b> drops them from this list, and their Hubitat devices are removed when you click Done. A device that's only powered off comes back on its own &mdash; leave it alone unless it's really gone.</small>"
                input name: 'forgetStale', type: 'button', title: "Forget ${staleCount} device(s) not seen in mDNS"
            }
        }
        section(hideable: true, hidden: true, 'Add a device by IP') {
            paragraph 'For a device not auto-discovered (or on another subnet). It is added and connects immediately.'
            input name: 'manualIp', type: 'text', title: 'IP address', required: false, submitOnChange: true
            input name: 'manualName', type: 'text', title: 'Name (optional)', required: false, submitOnChange: true
            input name: 'addManual', type: 'button', title: 'Add device'
            if (state.manual) input name: 'clearManual', type: 'button', title: 'Clear manual entries'
        }
        section(header('Settings')) {
            input name: 'refreshInterval', type: 'number', title: 'Status refresh interval (seconds)', defaultValue: 60, range: '10..3600', submitOnChange: true
            input name: 'debugOutput', type: 'bool', title: 'Enable debug logging (auto-off after 24h)', defaultValue: false, submitOnChange: true
            paragraph "<small>Announcement volume and lead-in delay are set per device &mdash; open a Chromecast device to change them.</small>"
            paragraph '<small>Rule Machine strips &lt; and &gt; out of its text fields, so SSML typed in a rule never reaches the device. Write it with braces instead &mdash; <b>Hello {break time="2s"/} World</b> &mdash; and the driver converts it back.</small>'
        }
        section {
            if (settings.debugOutput == true && state.debugDisableMs) {
                paragraph "<span style='color:red'>Debug logging will be disabled at ${clockTime(new Date(state.debugDisableMs as Long))}</span>"
            }
            paragraph "<small>Selected/added devices become child devices under the '${DRIVER}' parent device. Click <b>Done</b> to apply.</small>"
        }
    }
}

def appButtonHandler(btn) {
    switch (btn) {
        case 'rescan':
            registerMdns(true)
            armDiscovery()
            scanMdns()
            break
        case 'addManual':
            if (!isEmpty(settings.manualIp)) {
                String ip = settings.manualIp.trim()
                String nm = settings.manualName?.trim()
                def list = state.manual ?: []
                if (!list.any { it.ip == ip }) {
                    list << [ip: ip, name: nm, port: DEFAULT_PORT]
                    state.manual = list
                }
                // create the child right away (also exercises the TLS connect)
                createParentDevice()
                def p = getParentDevice()
                if (p) p.createChild(manualDni(ip), nm, ip, "${DEFAULT_PORT}", null)
                logDebug("added manual device ${ip}")
                app.updateSetting('manualIp', [value: '', type: 'text'])
                app.updateSetting('manualName', [value: '', type: 'text'])
            }
            break
        case 'clearManual':
            state.manual = []
            break
        case 'forgetStale':
            forgetStale()
            break
    }
}

// ----------------------------------------------------------------------------
// discovery
// ----------------------------------------------------------------------------
// Extend the scanning window. A fresh window (or an expired one) also restarts the periodic tick and kicks
// off a scan right away; inside an active window this only pushes the deadline out.
private void armDiscovery() {
    boolean wasOff = ((state.discoveryUntilMs ?: 0L) as Long) < now()
    state.discoveryUntilMs = now() + (DISCOVERY_WINDOW_MIN * 60000L)
    if (!wasOff) return
    unschedule('discoveryTick')
    runEvery5Minutes('discoveryTick')
    runIn(6, 'scanMdns')    // give the hub a few seconds to populate its mDNS cache before the first read
    logInfo("discovery: scanning for new devices for the next ${DISCOVERY_WINDOW_MIN} minutes")
}

def discoveryTick() {
    if (now() > ((state.discoveryUntilMs ?: 0L) as Long)) {
        unschedule('discoveryTick')
        logInfo('discovery: background scanning stopped; open the app or press Refresh Devices to scan again')
        return
    }
    scanMdns()
}

// Background scanning stops on its own, so show whether it is still running - otherwise "my new device never
// showed up" has no visible explanation.
private String discoveryStatus() {
    Long until = (state.discoveryUntilMs ?: 0L) as Long
    String scan = 'background scanning idle &mdash; press <b>Refresh Devices</b> to scan again'
    if (until > now()) scan = "scanning for new devices until ${clockTime(new Date(until))}"
    return "mDNS: ${state.lastScanFound ?: 0} record(s) on the last scan &middot; ${scan}"
}

// Read the hub's current mDNS cache and merge into state.discovered (accumulates across renders/reboots-of-page).
def scanMdns() {
    try {
        // Reads the hub's already-populated mDNS cache synchronously (the hub keeps _googlecast._tcp warm).
        // Returns a Map<MAC, ChromeCastEndpoint bean>; bean properties: ip4Address, port, friendlyName,
        // deviceId (uuid), model, macAddress, txtProperties. NOTE: it's a bean, not a Map -> accessing a
        // property that doesn't exist throws, so only touch the real property names below.
        Map entries = hubitat.helper.NetworkUtils.getRawMDNSEndpointsByMACForServiceType('_googlecast._tcp.local.')
        logDebug("scanMdns: ${entries?.size() ?: 0} entries")
        def disc = state.discovered ?: [:]
        int found = 0
        entries?.each { mac, v ->
            try {
                String ip = v.ip4Address
                if (ip) {
                    found++
                    String uuid = v.deviceId
                    String name = v.friendlyName ?: v.name ?: ip
                    Integer port = (v.port ?: DEFAULT_PORT) as Integer
                    String model = v.model
                    // the bean also carries the mDNS TXT records the code otherwise ignores; parse them for
                    // a device-type hint (audio/video/group) and the current receiver-status text (rs).
                    Map txt = parseTxt(v.txtProperties)
                    String deviceType = castDeviceType(txt.ca)
                    String dni = "GoogleChromecastPlus-${cleanId((uuid ?: mac ?: ip).toString())}"
                    disc[dni] = [ip: ip, port: port, name: name, uuid: uuid, mac: mac?.toString(), model: model,
                                 deviceType: deviceType, statusText: txt.rs, castVersion: txt.ve,
                                 lastSeenMs: now()]     // drives isStale(); entries are only pruned on request
                }
            } catch (ex) {
                logWarn "scanMdns: skipping ${mac}: ${ex.message}"
            }
        }
        // "not seen in mDNS" only means something if this read actually returned devices. An empty result is
        // routine (listener not registered yet, cache not filled, firmware without the API, a hub that just
        // rebooted) and must NOT make every known device look stale - that wiped a user's whole device list.
        state.lastScanFound = found
        // entries saved before lastSeenMs existed start their clock at the first scan that worked, so they get
        // the full STALE_MS grace period instead of being flagged the moment the app is upgraded
        if (found > 0) disc.each { dni, d -> if (!d.lastSeenMs) d.lastSeenMs = now() }
        state.discovered = disc
    } catch (e) {
        logWarn "scanMdns: NetworkUtils mDNS lookup failed (${e.message})"
        state.lastScanFound = 0      // no evidence -> nothing is stale (see isStale)
    }
}

// Build a dni -> [ip, port, name, uuid, source] map from accumulated mDNS results + manual entries.
Map discoverDevices() {
    scanMdns()
    Map candidates = [:]
    (state.discovered ?: [:]).each { dni, d -> candidates[dni] = [ip: d.ip, port: d.port, name: d.name, uuid: d.uuid, model: d.model, deviceType: d.deviceType, statusText: d.statusText, lastSeenMs: d.lastSeenMs, source: 'mdns'] }
    (state.manual ?: []).each { m ->
        candidates[manualDni(m.ip)] = [ip: m.ip, port: (m.port ?: DEFAULT_PORT), name: (m.name ?: m.ip), uuid: null, source: 'manual']
    }
    state.candidates = candidates
    return candidates
}

// Explicit prune (the 'Forget' button): drop every candidate currently flagged stale, plus its checkbox
// setting. Deliberately NOT something Done does on its own - discovery is the only record of these devices,
// and an empty or partial mDNS read would otherwise silently wipe the list. Their child devices go on the
// next Done, when syncChildren finds them missing from the candidates.
private void forgetStale() {
    Map cand = state.candidates ?: [:]
    List gone = cand.findAll { dni, d -> isStale(d) }.keySet().toList()
    if (gone.isEmpty()) { logInfo 'forgetStale: nothing flagged as stale'; return }
    Map disc = state.discovered ?: [:]
    gone.each { dni -> disc.remove(dni); app.removeSetting("sel_${cleanId(dni)}") }
    state.discovered = disc
    state.candidates = cand.findAll { !gone.contains(it.key) }
    logInfo "forgetStale: forgot ${gone.size()} device(s) not seen in mDNS: ${gone.join(', ')}"
}

private String cleanId(String s) { return (s ?: '').replaceAll('[^A-Za-z0-9]', '') }
private String manualDni(String ip) { return "GoogleChromecastPlus-${cleanId(ip)}" }

// The mDNS bean's txtProperties shape isn't documented for Hubitat's NetworkUtils, so handle whatever it is:
// a Map<String,String>, a List/array of "key=value" strings, or a single space/newline-joined string.
// Logs the raw form once at debug so the real shape can be confirmed on a live hub. Returns [:] on anything odd.
private Map parseTxt(raw) {
    if (raw == null) return [:]
    // getClass() is blocked in the Hubitat sandbox, so log the raw value + instanceof flags instead (enough to ID the shape)
    if (!state.loggedTxtShape) { logDebug("parseTxt: raw txtProperties=${raw} (map=${raw instanceof Map}, list=${raw instanceof List})"); state.loggedTxtShape = true }
    Map out = [:]
    try {
        if (raw instanceof Map) {
            raw.each { k, val -> if (k != null) out[k.toString()] = val?.toString() }
        } else {
            def items = (raw instanceof List || raw instanceof Object[]) ? raw.toList() : raw.toString().split(/[\r\n ]+/).toList()
            items.each { entry ->
                String e = entry?.toString()
                int i = e ? e.indexOf('=') : -1
                if (i > 0) out[e.substring(0, i)] = e.substring(i + 1)
            }
        }
    } catch (ex) {
        logWarn "parseTxt: could not parse txtProperties (${ex.message})"
        return [:]
    }
    return out
}

// Map the Cast 'ca' capabilities bitmask to a coarse device type. Best-effort (bits are community-documented,
// not official): bit0=video-out, bit2=audio-out, bit5=multizone group. Returns 'unknown' when absent/unparseable.
private String castDeviceType(caValue) {
    if (caValue == null) return 'unknown'
    Integer ca
    try { ca = caValue.toString().trim() as Integer } catch (ignored) { return 'unknown' }
    if (ca & 0x20) return 'group'          // multizone -> speaker/display group
    if (ca & 0x01) return 'video'          // has video output -> TV / dongle / display
    if (ca & 0x04) return 'audio'          // audio out only -> speaker
    return 'unknown'
}

// A candidate that has dropped out of the hub's mDNS cache. Discovery only ever merges into
// state.discovered, so a device that was factory-reset or renamed (new deviceId -> new DNI), or a
// speaker/display group edited or deleted in Google Home (groups get a fresh deviceId), leaves its old
// entry behind forever - and since the checkbox defaults to checked, syncChildren keeps re-creating the
// child. Flagging those is what this is for; removing them is the 'Forget' button, never automatic.
//
// Staleness needs POSITIVE evidence, both parts required:
//   - the entry has a real lastSeenMs (a missing stamp means "unknown", never "gone"), and
//   - the last scan actually returned devices (an empty read says nothing about any single device).
// Without those two guards, a hub whose mDNS cache came back empty flagged every device as stale.
private boolean isStale(Map d) {
    if (d?.source != 'mdns' || !d.lastSeenMs) return false
    if (((state.lastScanFound ?: 0) as Integer) < 1) return false
    return (now() - (d.lastSeenMs as Long)) > STALE_MS
}

// Jump straight to a created child's device page. It goes INSIDE the checkbox's title (which is already HTML),
// floated right, so it sits at the right edge of the same row rather than on a line of its own - a table can't
// do it, since the toggle's markup is generated by the hub and can't be wrapped in cells of ours.
// target=_blank keeps the app page (and its unsaved checkbox state) put. The hub renders the title inside a
// <label>, and a click on interactive content in a label is not forwarded to its control, so following the
// link does not toggle the checkbox.
private String deviceLink(dev, String label) {
    return "<a href='/device/edit/${dev.id}' target='_blank' style='float:right;margin-left:12px;font-weight:normal'>${label} &#10697;</a>"
}

// one selectable row: name/ip/model + a live-status line pulled from the created child (if any)
private String deviceRow(Map d, child) {
    // floated first so it lands on the first line of the row instead of after the status line
    String s = child ? deviceLink(child, 'View') : ''
    s += "<b>${d.name}</b> &mdash; ${d.ip}"
    if (d.model) s += " &middot; ${d.model}"
    if (d.deviceType && d.deviceType != 'unknown') s += " &middot; ${d.deviceType}"
    if (d.source == 'manual') s += " &middot; <i>manual</i>"
    // before the device is added we have no child to query; fall back to the mDNS receiver-status text (rs)
    String extra = d.statusText ?: 'not added yet'
    if (child) {
        String cs = child.currentValue('connectionStatus') ?: 'idle'
        String ps = child.currentValue('playbackStatus')
        if (ps && !(ps in ['IDLE', 'OFFLINE', 'UNKNOWN'])) {
            extra = ps.toLowerCase()
            String t = child.currentValue('mediaTitle')
            String a = child.currentValue('currentApp')
            if (t) extra += " &middot; ${t}" else if (a && a != 'none') extra += " &middot; ${a}"
        } else {
            extra = cs
            // "since" only reads well for the sticky states, so append it just for online/offline. the driver
            // keeps connectionStatus on those two outcomes (no transient "connecting"), so the timestamp of the
            // current event is a stable "online since 12:42 AM" that doesn't reset on every poll/reconnect
            if (cs in ['online', 'offline']) {
                Date since = child.currentState('connectionStatus')?.date
                if (since) extra += " since ${clockTime(since)}"
            }
        }
    }
    // flag a row the hub has not seen for a while; isStale guarantees lastSeenMs is set
    if (isStale(d)) extra = "not seen in mDNS since ${clockTime(new Date(d.lastSeenMs as Long))}"
    return s + "<br><span style='font-size:smaller;color:#666'>${extra}</span>"
}

// ----------------------------------------------------------------------------
// child / parent devices
// ----------------------------------------------------------------------------
private String parentDni() { "GoogleChromecastPlus-parent-${app.id}" }

private void createParentDevice() {
    def parent = getChildDevice(parentDni())
    if (!parent) {
        try {
            parent = addChildDevice('jpage4500', PARENT_DRIVER, parentDni(),
                [label: 'Google Chromecast+', isComponent: true, name: PARENT_DRIVER])
            parent.initialize()
            logInfo 'createParentDevice: created parent device'
        } catch (e) {
            logError "createParentDevice: failed - is the '${PARENT_DRIVER}' driver installed? ${e.message}"
        }
    }
}

private getParentDevice() { getChildDevice(parentDni()) }

// reconcile wanted devices (a checkbox per candidate, checked by default) against existing child devices
private void syncChildren() {
    def parent = getParentDevice()
    if (!parent) { logError 'syncChildren: no parent device'; return }
    Set wanted = [] as Set
    Set gone = [] as Set        // unchecked manual entry -> forget the entry, not just the child
    List manual = state.manual ?: []
    (state.candidates ?: [:]).each { dni, d ->
        if (isSelected(dni)) {
            parent.createChild(dni, d.name, d.ip, "${d.port}", d.uuid, d.deviceType); wanted << dni
        } else if (d.source == 'manual') {
            // nothing re-discovers a manual entry, so unchecking has to drop the entry itself - otherwise
            // it stays a candidate and (with the checkbox defaulting to checked) comes back on the next Done
            manual = manual.findAll { manualDni(it.ip) != dni }
            gone << dni
        }
        // a discovered (mDNS) entry is NEVER dropped here, checked or not: unchecking already deletes the
        // child device, and the row stays listed so a powered-off device can be re-checked later. Pruning
        // the list is the explicit 'Forget devices not seen in mDNS' button (forgetStale).
    }
    state.manual = manual
    if (gone) {
        // prune the render-time candidate cache too, and don't leave orphan checkbox settings behind
        state.candidates = (state.candidates ?: [:]).findAll { !gone.contains(it.key) }
        gone.each { app.removeSetting("sel_${cleanId(it)}") }
        logInfo "syncChildren: forgot ${gone.size()} device(s): ${gone.join(', ')}"
    }
    parent.getChildDevices().each { child ->
        if (!wanted.contains(child.deviceNetworkId)) parent.deleteChild(child.deviceNetworkId)
    }
}
private boolean isSelected(String dni) { return settings["sel_${cleanId(dni)}"] != false }  // checked by default

// ----------------------------------------------------------------------------
// util
// ----------------------------------------------------------------------------
private String header(String text) {
    return "<div style='color:#ffffff;font-weight: bold;background-color:#8652ff;padding-top: 10px;padding-bottom: 10px;border: 1px solid #000000;box-shadow: 2px 3px #8B8F8F;border-radius: 10px'><image style='padding: 0px 10px 0px 10px;' src=https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/google-chromecast-plus/chromecast.png width='50'> ${text}</div>"
}

private boolean isEmpty(def v) { return v == null || (v instanceof String && v.trim().isEmpty()) }

// short local clock time, e.g. "12:42 AM" (hub-local via the JVM default timezone, as in the other drivers)
private String clockTime(Date d) { d ? d.format('M/d h:mm a') : '' }

// same "GC+ [App] " prefix as the driver, so the Logs filter "GC+" shows app + all devices together
private void logDebug(msg) { if (settings.debugOutput) logAppAt('debug', msg) }
private void logInfo(msg)  { logAppAt('info',  msg) }
private void logWarn(msg)  { logAppAt('warn',  msg) }
private void logError(msg) { logAppAt('error', msg) }
// The Hubitat sandbox rejects a computed method name: log."${level}"(...) fails to compile with
// "Expression [MethodCallExpression] is not allowed" (SecurityException) on current firmware, which takes
// the whole app down at install. Dispatch by hand instead.
private void logAppAt(String level, msg) {
    String out = "GC+ [App] ${msg}"
    switch (level) {
        case 'debug': log.debug(out); break
        case 'warn':  log.warn(out);  break
        case 'error': log.error(out); break
        default:      log.info(out)
    }
}
