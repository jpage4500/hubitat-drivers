/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * DESCRIPTION:
 * TEST 1
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

metadata {
    definition (
        name: "Test 1",
        namespace: "jpage4500",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-tile/hd-tile.groovy"
    ) {
        capability "TemperatureMeasurement"

        attribute "temperature", "number"
        attribute "lastUpdatedMs", "number"
    }
}

preferences {
    input("clientId", "string", title: "Client ID", description: "", required: true)

    input name: "isLogging", type: "bool", title: "Enable Logging", description: "", required: true
}

private logDebug(msg) {
    if (settings?.isLogging) {
        log.debug "$msg"
    }
}

def installed() {
    logDebug("installed")
}

def updated() {
    logDebug("updated")
}
