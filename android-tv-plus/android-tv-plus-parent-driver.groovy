/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** Android TV+ Parent **
 *
 * Parent device created by the Android TV+ app. It manages child creation/deletion and holds aggregate status.
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import groovy.transform.Field

@Field static final String CHILD_DRIVER = "Android TV+ Driver"

metadata {
    definition(
        name: "Android TV+ Parent",
        namespace: "jpage4500",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/android-tv-plus/android-tv-plus-parent-driver.groovy"
    ) {
        attribute "deviceCount", "number"
        attribute "onlineCount", "number"
        attribute "summary", "string"
    }
}

def installed()   { initialize() }
def updated()     { recomputeSummary() }
def uninstalled() { removeAllChildren() }
def initialize()  { recomputeSummary() }

def createChild(String dni, String label, String ip, String port, String uuid = null, String sourceType = null) {
    if (isEmpty(dni)) { logError("createChild: missing dni"); return }
    def child = getChildDevice(dni)
    boolean isNew = false
    if (!child) {
        String initialName = label ?: "ADB Device"
        child = addChildDevice("jpage4500", CHILD_DRIVER, dni, [
            label: initialName,
            isComponent: true,
            name: CHILD_DRIVER,
            data: [ip: ip ?: "", port: port ?: "5555", discoveredName: initialName]
        ])
        isNew = true
        logInfo("createChild: created '${initialName}' (${dni})")
    } else if (label) {
        // Push the discovered name onto the label only when that name itself changed - otherwise a device
        // the user renamed in the hub UI would get renamed back on every sync. A child created before this
        // data value existed has no record of its discovered name, so seed it and leave the label alone.
        String lastName = child.getDataValue("discoveredName")
        if (lastName == null) {
            child.updateDataValue("discoveredName", label)
        } else if (lastName != label) {
            logInfo("createChild: discovered name changed '${lastName}' -> '${label}', renaming (${dni})")
            child.setLabel(label)
            child.updateDataValue("discoveredName", label)
        }
    }

    String oldIp = child.getDataValue("ip")
    String oldPort = child.getDataValue("port")
    child.updateDataValue("ip", ip ?: "")
    child.updateDataValue("port", port ?: "5555")
    if (uuid) child.updateDataValue("uuid", uuid)
    if (sourceType) child.updateDataValue("sourceType", sourceType)

    child.setDebug(state.debug == true)
    child.setPollInterval((state.pollSec ?: 30) as Integer)
    child.setCustomApps((state.customApps ?: "") as String)
    if (isNew || oldIp != ip || oldPort != (port ?: "5555")) child.initialize()
    runIn(2, "recomputeSummary")
    return child
}

def deleteChild(String dni) {
    if (getChildDevice(dni)) {
        deleteChildDevice(dni)
        logInfo("deleteChild: removed ${dni}")
    }
    runIn(1, "recomputeSummary")
}

def removeAllChildren() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
    runIn(1, "recomputeSummary")
}

def setPollInterval(seconds) {
    Integer sec = (seconds ?: 30) as Integer
    if (sec < 0) sec = 0
    if (sec > 900) sec = 900
    state.pollSec = sec
    getChildDevices().each { it.setPollInterval(sec) }
}

def setDebug(flag) {
    state.debug = (flag as Boolean)
    getChildDevices().each { it.setDebug(flag) }
}

// raw text from the app's Custom apps setting; each child parses it (see the driver's parseCustomApps)
def setCustomApps(String text) {
    state.customApps = text ?: ""
    getChildDevices().each { it.setCustomApps(state.customApps as String) }
}

def childStatusChanged() { runIn(1, "recomputeSummary") }

def recomputeSummary() {
    def kids = getChildDevices()
    int online = 0
    String first = null
    kids.each { k ->
        if ((k.currentValue("connectionStatus") ?: "") == "online") {
            online++
            if (!first) {
                String app = k.currentValue("currentAppName") ?: k.currentValue("currentApp") ?: "connected"
                first = "${k.displayName}: ${app}"
            }
        }
    }
    sendEventIfChanged("deviceCount", kids.size())
    sendEventIfChanged("onlineCount", online)
    String summary = online == 0 ? "No devices online" : (online == 1 ? first : "${online} of ${kids.size()} online")
    sendEventIfChanged("summary", summary)
}

private void sendEventIfChanged(String name, def value) {
    if (value == null) return
    if (device.currentValue(name)?.toString() != value.toString()) {
        sendEvent(name: name, value: value)
    }
}

private boolean isEmpty(def v) { return v == null || (v instanceof String && v.trim().isEmpty()) }

private void logDebug(msg) { if (state.debug == true) logAt("debug", msg) }
private void logInfo(msg)  { logAt("info", msg) }
private void logWarn(msg)  { logAt("warn", msg) }
private void logError(msg) { logAt("error", msg) }

private void logAt(String level, msg) {
    String out = "Android TV+ [${device.displayName}] ${msg}"
    switch (level) {
        case "debug": log.debug(out); break
        case "warn":  log.warn(out); break
        case "error": log.error(out); break
        default:      log.info(out)
    }
}
