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
        attribute "deviceType", "string"
        attribute "url", "string"
        attribute "refreshInterval", "number"
    }
}

preferences {
    input("deviceType", "enum", title: "Device Type", required: true,
        options: ["imageUrl": "Image URL", "videoUrl" : "Video URL", "webUrl" : "Web URL", "calendar" : "Calendar (ical URL)", "pollen" : "Pollen Count"])

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

    sendEvent(name:"deviceType", value:deviceType)
    sendEvent(name:"url", value:url)
    sendEvent(name:"refreshInterval", value:refreshInterval)
}
