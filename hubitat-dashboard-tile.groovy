/**
 * DESCRIPTION:
 * Adds a new tile to Hubitat Dashboard app
 *
 * INSTALL:
 * - Add this code to the Drivers Code section of the Hubitat Hub's web console
 *   > Drivers Code -> New Driver -> paste this code -> Save
 *
 * CONFIGURE:
 * - Add a new Virtual Device
 *   > Devices -> Add Device -> Virtual
 *   > Open the "Type" dropdown and search for "Hubitat Dashboard Tile" to find the new driver
 *   > Enter anything for the driver name and hit Save Device
 * - Configure Device
 *   > Select Device Type that you want to use
 *   > Enter URL for the device type
 *   > Select Refresh Rate for how often the dashboard will refresh this data/URL (in seconds)
 *
 **/

metadata {
    definition (
        name: "Hubitat Dashboard Tile",
        namespace: "hubitat-dashboard-tile",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hubitat-dashboard-tile.groovy"
    ) {
        capability "Sensor"

        attribute "deviceType", "string"
        attribute "url", "string"
        attribute "refreshInterval", "number"
    }
}

preferences {
    input("deviceType", "enum", title: "Device Type", required: true,
        options: ["imageUrl": "Image URL", "videoUrl" : "Video URL", "webUrl" : "Web URL", "calendar" : "Calendar (ical URL)", "pollen" : "Pollen Count", "radar" : "Radar"])

    input("url", "string", title: "URL", required: true)

    input('refreshInterval', 'enum', title: 'Refresh Rate', required: true,
        defaultValue: '15 Minutes',
        options: ["0": "Never", "15": "15 Seconds", "30": "30 Seconds", "120": "2 Minutes", "300": "5 Minutes", "600": "10 Minutes", "900": "15 Minutes", "1800": "30 Minutes", "3600": "1 Hour", "10800": "3 Hours", "18000": "5 Hours"])
}

def installed() {
    initialize()
}

def updated(){
    initialize()
}

def initialize(){
    log.info "${device}: initialize: deviceType:${deviceType}, url:${url}, refreshInterval:${refreshInterval}"

    sendEvent(name:"deviceType",value:deviceType)
    sendEvent(name:"url",value:url)
    sendEvent(name:"refreshInterval",value:refreshInterval)
}
