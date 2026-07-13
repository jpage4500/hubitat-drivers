/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** go2rtc Parent **
 *
 * Top-level parent device for the go2rtc app. A lean read-only status tile: it aggregates its child camera
 * devices (cameraCount / onlineCount / summary) and provides the management hooks the app calls
 * (create/delete children, broadcast the server settings + refresh interval + debug flag).
 *
 * It has NO user-facing camera commands - each camera lives in its own child device (driver "go2rtc Camera").
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import groovy.transform.Field

@Field static final String CHILD_DRIVER = 'go2rtc Camera'

metadata {
    definition(
        name: 'go2rtc Parent',
        namespace: 'jpage4500',
        author: 'Joe Page',
        importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/go2rtc/go2rtc-parent-driver.groovy'
    ) {
        // read-only aggregate of the child camera devices - no user commands
        attribute 'cameraCount', 'number'
        attribute 'onlineCount', 'number'
        attribute 'summary', 'string'
    }
}

// ============================================================================
// lifecycle
// ============================================================================
def installed()   { initialize() }
def updated()     { recomputeSummary() }
def uninstalled() { deleteChildren() }
def initialize()  { recomputeSummary() }

// ============================================================================
// server settings (pushed from the app, cached so children can be (re)built)
// ============================================================================
def setServer(String base, String host, Integer rtspPort, String username, String password) {
    state.serverBase = base
    state.serverHost = host
    state.rtspPort = rtspPort
    state.username = username
    state.password = password
    // re-push to existing children in case the server url / port / creds changed
    getChildDevices().each { it.configure(base, host, rtspPort, username, password, it.getDataValue('source'), it.getDataValue('streamName')) }
}

// ============================================================================
// child management (called by the app)
// ============================================================================
def createChild(String dni, String streamName, String base, String host, Integer rtspPort, String username, String password, String source) {
    if (isEmpty(dni)) { logError 'createChild: missing dni'; return }
    def child = getChildDevice(dni)
    boolean isNew = false
    if (!child) {
        child = addChildDevice('jpage4500', CHILD_DRIVER, dni,
            [label: streamName ?: 'Camera', isComponent: true, name: CHILD_DRIVER])
        isNew = true
        logInfo "createChild: created '${streamName}' (${dni})"
    }
    child.setDebug(state.debug == true)
    child.configure(base, host, rtspPort, username, password, source, streamName)
    // only capture a still on first create (configure() updates urls/status every time without re-fetching bytes)
    if (isNew) child.initialize()
    runIn(3, 'recomputeSummary')
    return child
}

def deleteChild(String dni) {
    if (getChildDevice(dni)) {
        deleteChildDevice(dni)
        logInfo "deleteChild: removed ${dni}"
    }
    runIn(2, 'recomputeSummary')
}

def deleteChildren()    { getChildDevices().each { deleteChildDevice(it.deviceNetworkId) } }
def removeAllChildren() { deleteChildren() }

// relay the app-configured snapshot poll interval to every child (informational; the app owns the timer)
def setRefreshInterval(seconds) {
    Integer sec = (seconds ?: 0) as Integer
    getChildDevices().each { it.setRefreshInterval(sec) }
}

// single debug toggle: the app broadcasts here, we fan out to children (and gate our own logs)
def setDebug(flag) {
    state.debug = (flag as Boolean)
    getChildDevices().each { it.setDebug(flag) }
}

// ============================================================================
// aggregate status
// ============================================================================
// children call this when their status changes
def childStatusChanged() { runIn(1, 'recomputeSummary') }

def recomputeSummary() {
    def kids = getChildDevices()
    int online = 0
    kids.each { k -> if (k.currentValue('status') == 'online') online++ }
    sendEventIfChanged('cameraCount', kids.size())
    sendEventIfChanged('onlineCount', online)
    String summary
    if (kids.isEmpty()) summary = 'No cameras'
    else summary = "${online} of ${kids.size()} online"
    sendEventIfChanged('summary', summary)
}

// ============================================================================
// util (same go2rtc prefix as the child driver so the Logs filter shows both together)
// ============================================================================
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
private void logAt(String level, msg) { log."${level}"("go2rtc [${device.displayName}] ${msg}") }
