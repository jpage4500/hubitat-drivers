/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** Google Chromecast+ Parent **
 *
 * Top-level parent device for the Google Chromecast+ app. This is a lean status tile:
 * it aggregates its child Chromecast devices (deviceCount / playingCount / summary) and provides the
 * management hooks the app calls (create/delete children, broadcast the refresh interval + debug flag).
 * The one user-facing feature is broadcast/speak: a single announcement fanned out to every child
 * Chromecast at once. Individual Chromecast control still lives in each child device (driver
 * "Google Chromecast+").
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
        // "speak to all": lets the parent be picked as a single Speak/Notification target that
        // fans the announcement out to every child Chromecast at once
        capability "SpeechSynthesis"

        // read-only aggregate of the child Chromecast devices
        attribute "deviceCount", "number"
        attribute "playingCount", "number"
        attribute "summary", "string"

        // broadcast a single announcement to every child at once (explicit volume vs. the
        // capability's bare speak(text)); volume blank = each device keeps its own setting
        command "broadcast", [
            [name: "Message*", type: "STRING", description: "text to speak on every Chromecast"],
            [name: "Volume", type: "NUMBER", description: "0-100, blank = leave each device's current volume"]
        ]
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
    boolean isNew = false
    if (!child) {
        child = addChildDevice("jpage4500", CHILD_DRIVER, dni,
            [label: label ?: "Chromecast", isComponent: true, name: CHILD_DRIVER])
        isNew = true
        logInfo "createChild: created '${label}' (${dni})"
    } else {
        child.setLabel(label ?: child.getLabel())
    }
    String oldIp = child.getDataValue("ip")
    child.updateDataValue("ip", ip)
    child.updateDataValue("port", port ?: "8009")
    if (uuid) child.updateDataValue("uuid", uuid)
    if (deviceType) child.updateDataValue("deviceType", deviceType)
    child.setDebug(state.debug == true)
    // only (re)initialize on first create or when the target IP changed. re-initializing a healthy child tears
    // down its live connection and resets "online since"; the app calls createChild for every device on each
    // Done, so the old unconditional init was a major source of connection churn. (reboots re-init via the
    // Initialize capability, so existing children still recover without this.)
    if (isNew || (ip && ip != oldIp)) child.initialize()
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


// ============================================================================
// broadcast (fan one announcement out to every child at once)
// ============================================================================
// SpeechSynthesis capability - each child's own speak() handles TTS, per-device volume & lead-in,
// and restores whatever it was playing afterward. speak() returns quickly (the child defers the
// blocking TLS connect), so looping over children here doesn't stall.
def speak(text)                { broadcast(text, null) }
def speak(text, volume)        { broadcast(text, volume) }
def speak(text, volume, voice) { broadcast(text, volume, voice) }

// custom command: play <text> on every child Chromecast at once
def broadcast(text, volume = null, voice = null) {
    if (isEmpty(text)) { logWarn "broadcast: empty message, ignoring"; return }
    def kids = getChildDevices()
    logInfo "broadcast: '${text}'${volume != null ? " @${volume}" : ""} -> ${kids.size()} device(s)"
    kids.each { child ->
        if (voice != null)       child.speak(text, volume, voice)
        else if (volume != null) child.speak(text, volume)
        else                     child.speak(text)
    }
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
