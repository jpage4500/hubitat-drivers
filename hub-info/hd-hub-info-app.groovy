// hubitat start
// hub: 192.168.0.200
// type: app
// id: 818
// hubitat end

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * DESCRIPTION:
 *
 *
 * INSTALL:
 * - Add a new Virtual Device
 *   > Devices -> Add Device -> Virtual
 *   > Open the "Type" dropdown and search for "HD+ Tile" to find the new driver
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

definition(
    name: 'HD+ Hub Info App',
    namespace: 'jpage4500',
    author: 'Joe Page',
    description: 'View Hubitat Variables in HD+',
    importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-device/hd_app.groovy',
    category: '',
    oauth: false,
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: ''
)

preferences {
    page(name: 'mainPage')
}

def mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section(header("Variables")) {
            input name: "useAllVariables", type: "bool", title: "Allow All Variables?", defaultValue: true, submitOnChange: true

            if (!settings.useAllVariables) {
                List varKeyList = []
                Map varList = getAllGlobalVars()
                varList.each {
                    def key = it.getKey()
                    varKeyList.add(key)
                }
                varKeyList.sort()
                // let user select which variable(s) they want to expose
                input "selectedVars", "enum", multiple: true, required: false, title: "Select Variables: ", options: varKeyList, submitOnChange: true
            }

            input(name: "updateStateBtn", type: "button", title: "Update Variables")
            showVariables()
        }
        section(header("Other")) {
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
        }
    }
}

def uninstalled() {
    log.info "uninstalled"
    unschedule()
    unsubscribe()
    deleteChildDevice(state.deviceId)
}

def updated() {
    logDebug("updating")
    initialize()
    notifyChildDevice()
}

def installed() {
    log.info "installed"
    initialize()
    // start app on Hub reboot?
    //subscribe(location, 'systemStart', initialize)
}

def initialize() {
    logDebug("initialize")
    if (isEmpty(state.deviceId)) {
        state.deviceId = UUID.randomUUID().toString()
    }
    def childDevice = getChildDevice(state.deviceId)
    if (childDevice == null) {
        addChildDevice('jpage4500', "HD+ Hub Info", state.deviceId)
    }

    unsubscribe()
    removeAllInUseGlobalVar()

    Map varList = getAllGlobalVars()
    varList.each {
        def key = it.getKey()
        //addInUseGlobalVar(key)
        subscribe(location, "variable:${key}", onVariableUpdated)
    }

    subscribe(location, "sunrise", onVariableUpdated)
    subscribe(location, "sunset", onVariableUpdated)
    subscribe(location, "mode", onVariableUpdated)
}

def showVariables() {
    Map varList = getAllGlobalVars()
    varList.each {
        def key = it.getKey()
        def value = it.getValue()
        paragraph("key:${key} - value:${value}")
    }
}

// automatically called when a variable is renamed
void renameVariable(String oldName, String newName) {
    logDebug("renameVariables: ${oldName} -> ${newName}")
    notifyChildDevice()
}

def notifyChildDevice() {
    def childDevice = getChildDevice(state.deviceId)
    if (childDevice == null) return

    String variableStr = ""
    getAllGlobalVars().each {
        // key:Test HTML, value:[type:string, value:TESTd, deviceId:1645, attribute:Variable, source:, sourceIp:]
        // key:two, value:[type:integer, value:12333, deviceId:null, attribute:null, source:, sourceIp:]
        String key = it.getKey()
        def variable = it.getValue()

        String type = variable.type
        String value = variable.value
        String deviceId = variable.deviceId

        if (variableStr.length() == 0) variableStr += "["
        else variableStr += ","

        variableStr += encode(key, value, type, deviceId)

        // TODO: if size > 2048 split into multiple variables
    }

    variableStr += ","
    variableStr += encode("sunrise", location.sunrise, "time", null) + ","
    variableStr += encode("sunset", location.sunset, "time", null) + ","
    variableStr += encode("tempScale", location.temperatureScale, "string", null) + ","
    variableStr += encode("uptime", location.uptime, "integer", null)

    variableStr += "]";

    logDebug("updateChildren: ${variableStr}")

    childDevice.sendEvent(name: "variables", value: variableStr)
}

static String encode(String key, Object value, String type, String id) {
    return "{" +
        escape("k", key) + "," +
        escape("v", value) + "," +
        escape("t", type) + "," +
        escape("i", id) +
        "}"
}

static String escape(String key, def val) {
    if (val != null) return "\"" + key + "\":\"" + val + "\"";
    else return ""
}

// called by subscribe() when variable changes
def onVariableUpdated(evt) {
    logDebug("onVariableUpdated: name:${evt.name}, value:${evt.value}")
    notifyChildDevice()
}

def appButtonHandler(btn) {
    logDebug("appButtonHandler: ${btn}")
    switch (btn) {
        case "updateStateBtn":
            notifyChildDevice()
            break;
    }
}

// ----------------------------------------------------------------------------
// Utility functions
// ----------------------------------------------------------------------------
static String header(text) {
    return "<div style='color:#ffffff;font-weight: bold;background-color:#244D76FF;padding-top: 10px;padding-bottom: 10px;border: 1px solid #000000;box-shadow: 2px 3px #8B8F8F;border-radius: 10px'><image style='padding: 0px 10px 0px 10px;' src=https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-device/images/logo.png width='50'> ${text}</div>"
}

static boolean isEmpty(text) {
    return text == null || text.isEmpty()
}

def showMessage(text) {
    if (!isEmpty(text)) {
        paragraph("<p style='color:red; font-weight: bold'>${text}</p>")
    }
}

private logDebug(msg) {
    if (settings.debugOutput) {
        log.debug "$msg"
    }
}

