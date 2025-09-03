// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1603
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

metadata {
    definition(
        name: "HD+ Hub Info",
        namespace: "jpage4500",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hub-info/hub-info.groovy"
    ) {
        // NOTE: capability is needed for driver to show up in MakerAPI list
        capability "Sensor"

        attribute "variables", "string"

        // manually refresh variables
        command('updateVariables', [[name: 'Update Variables', description: 'Update all variables']])

        command "setVariable", [[name: 'variableName', type: 'STRING', description: 'Variable Name'],[name: "variableValue", type: "STRING", description: "Variable Value"]]
    }
}

preferences {
    input name: "isLogging", type: "bool", title: "Enable Logging", description: "", required: true
}

def installed() {
    if (isLogging) log.debug "installed: "
}

def updated() {
    if (isLogging) log.debug "updated: "
}

def initialize() {
    if (isLogging) log.debug "initialize: "
}

def setVariable(String key, String value) {
    if (isLogging) log.debug "updateVariables:${key}, val:${value}"
    parent.notifyChildDevice()
}


