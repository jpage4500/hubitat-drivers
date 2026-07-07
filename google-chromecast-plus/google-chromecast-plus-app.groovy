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
 *
 *  Changes:
 *  1.0.0 - initial version
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

definition(

    name: 'Google Chromecast+',
    namespace: 'jpage4500',
    author: 'Joe Page',
    description: 'Discover and control Google Cast / Chromecast devices (status, media, TTS)',
    importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/google-chromecast-plus/google-chromecast-plus-app.groovy',
    category: 'Convenience',
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
    if (settings.debugOutput == true) runIn(86400, 'debugOff')
    schedulePolling()
    // give the hub a few seconds to populate its mDNS cache, then keep it fresh
    runIn(6, 'scanMdns')
    runEvery5Minutes('scanMdns')
}

def uninstalled() {
    def parent = getParentDevice()
    if (parent) parent.removeAllChildren()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// mDNS listener is cleared on reboot -> re-register on system start
def bootHandler(evt) { registerMdns(true); runIn(10, 'scanMdns') }

// scheduled by updated() 24h after debug is enabled: clear the app toggle + broadcast off to parent/children
def debugOff() {
    logInfo('auto-disabling debug logging (24h elapsed)')
    app.updateSetting('debugOutput', [value: 'false', type: 'bool'])
    getParentDevice()?.setDebug(false)
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
        }
        section {
            paragraph "<small>Selected/added devices become child devices under the '${DRIVER}' parent device. Click <b>Done</b> to apply.</small>"
        }
    }
}

def appButtonHandler(btn) {
    switch (btn) {
        case 'rescan':
            registerMdns(true)
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
    }
}

// ----------------------------------------------------------------------------
// discovery
// ----------------------------------------------------------------------------
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
        entries?.each { mac, v ->
            try {
                String ip = v.ip4Address
                if (ip) {
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
                                 deviceType: deviceType, statusText: txt.rs, castVersion: txt.ve]
                }
            } catch (ex) {
                logWarn "scanMdns: skipping ${mac}: ${ex.message}"
            }
        }
        state.discovered = disc
    } catch (e) {
        logWarn "scanMdns: NetworkUtils mDNS lookup failed (${e.message})"
    }
}

// Build a dni -> [ip, port, name, uuid, source] map from accumulated mDNS results + manual entries.
Map discoverDevices() {
    scanMdns()
    Map candidates = [:]
    (state.discovered ?: [:]).each { dni, d -> candidates[dni] = [ip: d.ip, port: d.port, name: d.name, uuid: d.uuid, model: d.model, deviceType: d.deviceType, statusText: d.statusText, source: 'mdns'] }
    (state.manual ?: []).each { m ->
        candidates[manualDni(m.ip)] = [ip: m.ip, port: (m.port ?: DEFAULT_PORT), name: (m.name ?: m.ip), uuid: null, source: 'manual']
    }
    state.candidates = candidates
    return candidates
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

// one selectable row: name/ip/model + a live-status line pulled from the created child (if any)
private String deviceRow(Map d, child) {
    String s = "<b>${d.name}</b> &mdash; ${d.ip}"
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
        }
    }
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
    (state.candidates ?: [:]).each { dni, d ->
        if (isSelected(dni)) { parent.createChild(dni, d.name, d.ip, "${d.port}", d.uuid, d.deviceType); wanted << dni }
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

// same "GC+ [App] " prefix as the driver, so the Logs filter "GC+" shows app + all devices together
private void logDebug(msg) { if (settings.debugOutput) logAppAt('debug', msg) }
private void logInfo(msg)  { logAppAt('info',  msg) }
private void logWarn(msg)  { logAppAt('warn',  msg) }
private void logError(msg) { logAppAt('error', msg) }
private void logAppAt(String level, msg) { log."${level}"("GC+ [App] ${msg}") }
