/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** Google Chromecast+ Parent **
 *
 * Top-level parent device for the Google Chromecast+ app. This is a lean read-only status tile:
 * it aggregates its child Chromecast devices (deviceCount / playingCount / summary) and provides the
 * management hooks the app calls (create/delete children, broadcast the refresh interval + debug flag).
 * It intentionally has NO user-facing capabilities/commands - each Chromecast lives in its own child
 * device (driver "Google Chromecast+").
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import groovy.transform.Field

@Field static final String CHILD_DRIVER = "Google Chromecast+"

metadata {
    definition(
        name: "Google Chromecast+ Parent",
        namespace: "jpage4500",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/google-chromecast-plus/google-chromecast-plus-parent-driver.groovy"
    ) {
        // read-only aggregate of the child Chromecast devices - no user commands
        attribute "deviceCount", "number"
        attribute "playingCount", "number"
        attribute "summary", "string"
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
// child management (called by the app)
// ============================================================================
def createChild(String dni, String label, String ip, String port, String uuid = null, String deviceType = null) {
    if (isEmpty(dni)) { logError "createChild: missing dni"; return }
    def child = getChildDevice(dni)
    if (!child) {
        child = addChildDevice("jpage4500", CHILD_DRIVER, dni,
            [label: label ?: "Chromecast", isComponent: true, name: CHILD_DRIVER])
        logInfo "createChild: created '${label}' (${dni})"
    } else {
        child.setLabel(label ?: child.getLabel())
    }
    child.updateDataValue("ip", ip)
    child.updateDataValue("port", port ?: "8009")
    if (uuid) child.updateDataValue("uuid", uuid)
    if (deviceType) child.updateDataValue("deviceType", deviceType)
    child.setDebug(state.debug == true)
    child.setPreRoll(state.preRoll != false)
    child.setPreRollDelay(state.preRollDelay)
    child.initialize()
    runIn(3, "recomputeSummary")
    return child
}

def deleteChild(String dni) {
    if (getChildDevice(dni)) {
        deleteChildDevice(dni)
        logInfo "deleteChild: removed ${dni}"
    }
    runIn(2, "recomputeSummary")
}

def deleteChildren()    { getChildDevices().each { deleteChildDevice(it.deviceNetworkId) } }
def removeAllChildren() { deleteChildren() }

// relay the app-configured polling interval to every child
def setRefreshInterval(seconds) {
    Integer sec = (seconds ?: 60) as Integer
    getChildDevices().each { it.setRefreshInterval(sec) }
}

// single debug toggle: the app broadcasts here, we fan out to children (and gate our own logs)
def setDebug(flag) {
    state.debug = (flag as Boolean)
    getChildDevices().each { it.setDebug(flag) }
}

// pre-roll silence toggle: the app broadcasts here, we fan out to children
def setPreRoll(flag) {
    state.preRoll = (flag as Boolean)
    getChildDevices().each { it.setPreRoll(flag) }
}

// lead-in delay (seconds, or null = auto by device type): the app broadcasts here, we fan out to children
def setPreRollDelay(seconds) {
    state.preRollDelay = seconds
    getChildDevices().each { it.setPreRollDelay(seconds) }
}

// ============================================================================
// aggregate status
// ============================================================================
// children call this when their status changes
def childStatusChanged() { runIn(1, "recomputeSummary") }

def recomputeSummary() {
    def kids = getChildDevices()
    int playing = 0
    String summaryLine = ""
    kids.each { k ->
        if (k.currentValue("playbackStatus") == "PLAYING") {
            playing++
            if (!summaryLine) {
                String t = k.currentValue("mediaTitle") ?: k.currentValue("currentApp") ?: "playing"
                summaryLine = "${k.getLabel()}: ${t}"
            }
        }
    }
    sendEventIfChanged("deviceCount", kids.size())
    sendEventIfChanged("playingCount", playing)
    sendEventIfChanged("summary", playing == 0 ? "Nothing playing" : (playing == 1 ? summaryLine : "${playing} of ${kids.size()} playing"))
}

// ============================================================================
// util (same GC+ prefix as the child driver so the Logs filter shows both together)
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
private void logAt(String level, msg) { log."${level}"("GC+ [${device.displayName}] ${msg}") }
