// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1271
// hubitat end

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * DESCRIPTION:
 * Adds a new tile to HD+ app (https://community.hubitat.com/t/release-hd-android-dashboard/41674/)
 *
 * These tiles can all be added directly in HD+ -- this is just an easier way to make them available for multiple devices and
 * it's easier to enter the URL here instead of a device
 *
 * INSTALL:
 * - Add a new Virtual Device
 *   > Devices -> Add Device -> Virtual
 *   > Open the "Type" dropdown and search for "HD+ Tile" to find the new driver
 *   > Enter anything for the driver name and hit Save Device
 * CONFIGURE:
 *   > Select Device Type that you want to use (ie: Calendar)
 *   > Enter URL for the device type (zip code for pollen count)
 *   > Select Refresh Rate for how often the dashboard will refresh this data/URL (in seconds)
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

metadata {
    definition (
        name: "HD+ Tile",
        namespace: "jpage4500",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-tile/hd-tile.groovy"
    ) {
        // NOTE: capability is needed for driver to show up in MakerAPI list
        capability "Sensor"

        attribute "deviceType", "string"
        attribute "url", "string"
        attribute "url2", "string"
        attribute "url3", "string"
        attribute "refreshInterval", "number"
        attribute "lastUpdatedMs", "number"

        attribute "status1", "string"
        attribute "status2", "string"

        command('setStatus1', [[name: 'Status field 1', type: 'STRING', description: 'HD+ will set this field']])
        command('setStatus2', [[name: 'Status field 1', type: 'STRING', description: 'HD+ will set this field']])
    }
}

preferences {
    input("deviceType", "enum", title: "Device Type", required: true,
        options: [
            "imageUrl": "Image URL",
            "videoUrl" : "Video URL",
            "webUrl" : "Web URL",
            "calendar" : "Calendar (ical URL)",
            "pollen" : "Pollen Count (Zip Code)",
            "radar" : "Radar (LAT,LNG)",
            "stock" : "Stock (Symbol)",
            "dadjokes" : "Dad Jokes",
            "activity" : "Device Activity"
        ])

    input("url", "string", title: "URL | Zip Code | Stock Symbol", description: "", required: false)

    input("url2", "string", title: "URL #2 (used for tiles that support multiple URL's)", description: "", required: false)

    input("url3", "string", title: "URL #3 (used for tiles that support multiple URL's)", description: "", required: false)

    input('refreshInterval', 'enum', title: 'Refresh Rate', required: false,
        defaultValue: '15 Minutes',
        options: ["0": "Never", "15": "15 Seconds", "30": "30 Seconds", "120": "2 Minutes", "300": "5 Minutes", "600": "10 Minutes", "900": "15 Minutes", "1800": "30 Minutes", "3600": "1 Hour", "10800": "3 Hours", "18000": "5 Hours"])

    input name: "isLogging", type: "bool", title: "Enable Logging", description: "", required: true
}

def installed() {
}

def updated() {
    if (isLogging) log.debug "updated: ${deviceType}, ${url}, ${refreshInterval}"
    sendEvent(name:"deviceType", value:deviceType)
    sendEvent(name:"url", value:url)
    sendEvent(name:"url2", value:url2)
    sendEvent(name:"url3", value:url3)
    sendEvent(name:"refreshInterval", value:refreshInterval)
    sendEvent(name:"lastUpdatedMs", value:new Date().getTime())
}

def initialize(){
}

def setStatus1 (value) {
    if (isLogging) log.debug "setStatus1: ${value}"
    sendEvent( name: "status1", value: value )
}

def setStatus2 (value) {
    if (isLogging) log.debug "setStatus2: ${value}"
    sendEvent( name: "status2", value: value )
}
