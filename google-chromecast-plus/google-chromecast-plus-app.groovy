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
        section(header('Discovered devices')) {
            Map discovered = candidates.findAll { k, v -> v.source == 'mdns' }
            if (discovered.isEmpty()) {
                paragraph "No devices discovered yet (found: ${(state.discovered ?: [:]).size()}). " +
                    "Discovery reads the hub's mDNS cache; click <b>Rescan</b> and reopen this page, " +
                    "or just add a device by IP below (works regardless)."
            } else {
                Map opts = candidates.collectEntries { dni, d -> [(dni): "${d.name} (${d.ip})${d.model ? ' - ' + d.model : ''}${d.source == 'manual' ? ' [manual]' : ''}"] }
                input name: 'selectedDevices', type: 'enum', title: 'Select discovered devices to add', options: opts, multiple: true, submitOnChange: true
            }
            input name: 'rescan', type: 'button', title: 'Rescan'
        }
        section(header('Add device by IP')) {
            paragraph 'Works without discovery. Enter the Chromecast IP and it is added (and connects) immediately.'
            input name: 'manualIp', type: 'text', title: 'IP address', required: false, submitOnChange: true
            input name: 'manualName', type: 'text', title: 'Name (optional)', required: false, submitOnChange: true
            input name: 'addManual', type: 'button', title: 'Add device'
        }
        section(header('Current devices')) {
            def parent = getParentDevice()
            def kids = (parent?.getChildDevices()) ?: []
            if (!kids) {
                paragraph 'No devices added yet.'
            } else {
                paragraph kids.collect { "&bull; ${it.getLabel()} - ${it.currentValue('connectionStatus') ?: 'idle'}" }.join('<br>')
            }
            if (state.manual) input name: 'clearManual', type: 'button', title: 'Clear manual list'
        }
        section(header('Settings')) {
            input name: 'refreshInterval', type: 'number', title: 'Status refresh interval (seconds)', defaultValue: 60, range: '10..3600', submitOnChange: true
            input name: 'debugOutput', type: 'bool', title: 'Enable debug logging', defaultValue: false, submitOnChange: true
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
                    String dni = "GoogleChromecastPlus-${cleanId((uuid ?: mac ?: ip).toString())}"
                    disc[dni] = [ip: ip, port: port, name: name, uuid: uuid, mac: mac?.toString(), model: model]
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
    (state.discovered ?: [:]).each { dni, d -> candidates[dni] = [ip: d.ip, port: d.port, name: d.name, uuid: d.uuid, model: d.model, source: 'mdns'] }
    (state.manual ?: []).each { m ->
        candidates[manualDni(m.ip)] = [ip: m.ip, port: (m.port ?: DEFAULT_PORT), name: (m.name ?: m.ip), uuid: null, source: 'manual']
    }
    state.candidates = candidates
    return candidates
}

private String cleanId(String s) { return (s ?: '').replaceAll('[^A-Za-z0-9]', '') }
private String manualDni(String ip) { return "GoogleChromecastPlus-${cleanId(ip)}" }

// ----------------------------------------------------------------------------
// child / parent devices
// ----------------------------------------------------------------------------
private String parentDni() { "GoogleChromecastPlus-parent-${app.id}" }

private void createParentDevice() {
    def parent = getChildDevice(parentDni())
    if (!parent) {
        try {
            parent = addChildDevice('jpage4500', DRIVER, parentDni(),
                [label: 'Google Chromecast+', isComponent: true, name: DRIVER])
            parent.updateDataValue('role', 'parent')
            parent.initialize()
            logInfo 'createParentDevice: created parent device'
        } catch (e) {
            logError "createParentDevice: failed - is the '${DRIVER}' driver installed? ${e.message}"
        }
    }
}

private getParentDevice() { getChildDevice(parentDni()) }

// reconcile wanted devices (selected discovered + all manual) against existing child devices
private void syncChildren() {
    def parent = getParentDevice()
    if (!parent) { logError 'syncChildren: no parent device'; return }
    Map cands = state.candidates ?: [:]
    Set wanted = new LinkedHashSet((settings.selectedDevices ?: []) as List)
    (state.manual ?: []).each { m -> wanted << manualDni(m.ip) }
    wanted.each { dni ->
        def d = cands[dni]
        if (d) parent.createChild(dni, d.name, d.ip, "${d.port}", d.uuid)
    }
    parent.getChildDevices().each { child ->
        if (!wanted.contains(child.deviceNetworkId)) parent.deleteChild(child.deviceNetworkId)
    }
}

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
