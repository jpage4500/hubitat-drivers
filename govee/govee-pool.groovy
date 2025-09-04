// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1782
// hubitat end

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * DESCRIPTION:
 * Govee Pool Thermostat
 *
 * INSTALL:
 * - Add a new Virtual Device
 *   > Devices -> Add Device -> Virtual
 *   > Open the "Type" dropdown and search for "Govee Pool Thermostat" to find the new driver
 *   > Enter anything for the driver name and hit Save Device
 * CONFIGURE:
 *   >
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

metadata {
    definition (
        name: "Govee Pool Thermostat",
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

    input("authToken1", "string", title: "Authorization Token (1/2)", description: "variables can only hold 255 characters so split between 2 fields", required: true)
    input("authToken2", "string", title: "Authorization Token (2/2)", description: "", required: false)

    input('refreshInterval', 'enum', title: 'Refresh Rate', required: true,
        defaultValue: '900',
        options: ["0": "Never", "15": "15 Seconds", "30": "30 Seconds", "120": "2 Minutes", "300": "5 Minutes", "600": "10 Minutes", "900": "15 Minutes", "1800": "30 Minutes", "3600": "1 Hour", "10800": "3 Hours", "18000": "5 Hours"])

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
    if (!settings?.clientId || !settings?.authToken1) return;

    def updateInterval = (settings?.refreshInterval ?: "900").toInteger()
    logDebug("updated: interval: ${updateInterval} seconds")
    schedule("0/${updateInterval} * * * * ?", refreshData)

    refreshData()
}

def initialize(){
    logDebug("initialize")
}

def getAuthToken() {
    return "${settings?.authToken1 ?: ''}${settings?.authToken2 ?: ''}"
}

def refreshData() {
    def authToken = getAuthToken()
    //logDebug("refreshData: clientId: ${settings?.clientId}, authToken: ${authToken}")

    def headers = [
        clientId: settings?.clientId,
        clientType: 0,
        appVersion: "7.0.30",
        Host: "app2.govee.com",
        Authorization: "Bearer ${authToken}"
    ]

    def params = [
        uri: "https://app2.govee.com/bff-app/v1/device/list",
        headers: headers
    ]

    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                parseResponse(resp.data)
            } else {
                log.error "refreshData failed with status ${resp.status}"
            }
        }
    } catch (e) {
        log.error "refreshData exception: ${e.message}"
    }
}

def parseResponse(data) {
    //logDebug("parseResponse: data: ${data}")
    def devices = data?.data?.devices
    if (devices) {
        devices.each { device ->
            def deviceExt = device?.deviceExt
            if (deviceExt) {
                def lastDeviceData = new groovy.json.JsonSlurper().parseText(deviceExt?.lastDeviceData)
                def rawTemperature = lastDeviceData?.tem
                if (rawTemperature != null) {
                    def celsius = rawTemperature / 100.0
                    def fahrenheit = (celsius * 9 / 5) + 32
                    def timestamp = now()
                    logDebug("Device ID: ${device?.deviceId}, Temperature: ${celsius}°C / ${fahrenheit}°F")
                    sendEvent(name: "temperature", value: fahrenheit)
                    sendEvent(name: "lastUpdatedMs", value: timestamp)

                    // save last 100 temperature values
                    //state.temperatureHistory = (state.temperatureHistory ?: []) + [[temperature: fahrenheit, timestamp: timestamp]]
                    //if (state.temperatureHistory.size() > 100) {
                    //    state.temperatureHistory = state.temperatureHistory[-100..-1]
                    //}
                }
            }
        }
    } else {
        log.error "No devices found in the response."
    }
}