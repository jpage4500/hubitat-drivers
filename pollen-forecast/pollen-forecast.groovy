/**
 *
 *  Fetch 30-day pollen history for a given zip code
 *
 *  Changes:
 *
 * v1.0.0 - 04/20/2021 - Use 30-day history endpoint; for use with Hubitat Dashboard Android app
 * 
 * - used https://github.com/bptworld/Hubitat/tree/master/Drivers/Pollen%20Forecaster as a starting point to write this driver
 * 
 */


metadata {
    definition (name: "Pollen History", namespace: "jpage4500", author: "Joe Page", importUrl: "") {
        capability "Actuator"
        capability "Sensor"
        capability "Polling"

        attribute "indexYesterday", "number"
        attribute "categoryYesterday", "string"
        attribute "triggersYesterday", "string"

        attribute "indexToday", "number"
        attribute "categoryToday", "string"
        attribute "triggersToday", "string"

        attribute "indexTomorrow", "number"
        attribute "categoryTomorrow", "string"
        attribute "triggersTomorrow", "string"

        attribute "location", "string"
    }

    preferences {
        input name: "about", type: "paragraph", title: "Pollen Forecaster", description: "Retrieve data from Pollen.com. For use with Hubitat dashboards."
        input name: "zipCode", type: "text", title: "Zip Code", required: true, defaultValue: "${location.zipCode}"
        input name: "pollRate", type: "text", title: "Poll Interval (hours)", required: true, defaultValue: "5"
        input "logEnable", "bool", title: "Enable logging", required: true, defaultValue: false
    }
}

def installed() {
    runEvery1Hour(poll)
    poll()
}

def updated() {
    poll()
}

def uninstalled() {
    unschedule()
}

def poll() {
    if(logEnable) log.debug "In poll..."
    def pollenZip = null

    // Use hub zipcode if user has not defined their own
    if(zipCode) {
        pollenZip = zipCode
    } else {
        pollenZip = location.zipCode
    }
    
    if (logEnable) log.debug "Getting pollen data for ZIP: ${pollenZip}"

    // for 30-day history
    // uri: 'https://www.pollen.com/api/forecast/historic/pollen/' + ${pollenZip} + '/30',

    def params = [
        uri: 'https://www.pollen.com/api/forecast/current/pollen/' + ${pollenZip},
        headers: [Referer:'https://www.pollen.com']
    ]

    try {
        httpGet(params) {resp ->
            if (logEnable) log.debug ${resp}

            state.location = resp.data.Location.DisplayLocation
            sendEvent(name: "location", value: state.location, displayed: true)

            resp.data.Location.periods.each {period ->
                    def catName = ""
                    def indexNum = period.Index.toFloat()
                    
                    // Set the category according to index thresholds
                    if (indexNum < 2.5) {catName = "Low"}
                    else if (indexNum < 4.9) {catName = "Low-Medium"}
                    else if (indexNum < 7.3) {catName = "Medium"}
                    else if (indexNum < 9.7) {catName = "Medium-High"}
                    else if (indexNum < 12) {catName = "High"}
                    else {catName = "Unknown"}

                    // Build the list of allergen triggers
                    def triggersList = period.Triggers.inject([]) { result, entry ->
                        result << "${entry.Name}"
                    }.join(", ")

                if (period.Type == 'Yesterday') {
                    state.indexYesterday = period.Index
                    state.categoryYesterday = catName
                    state.triggersYesterday = triggersList
                    
                    sendEvent(name: "indexYesterday", value: state.indexYesterday, displayed: true)
                    sendEvent(name: "categoryYesterday", value: state.categoryYesterday, displayed: true)
                    sendEvent(name: "triggersYesterday", value: state.triggersYesterday, displayed: true)
                } else if (period.Type == 'Today') {
                    state.indexToday = period.Index
                    state.categoryToday = catName
                    state.triggersToday = triggersList
                    
                    sendEvent(name: "indexToday", value: state.indexToday, displayed: true)
                    sendEvent(name: "categoryToday", value: state.categoryToday, displayed: true)
                    sendEvent(name: "triggersToday", value: state.triggersToday, displayed: true)
                } else if(period.Type == 'Tomorrow') {
                    state.indexTomorrow = period.Index
                    state.categoryTomorrow = catName
                    state.triggersTomorrow = triggersList
                    
                    sendEvent(name: "indexTomorrow", value: state.indexTomorrow, displayed: true)
                    sendEvent(name: "categoryTomorrow", value: state.categoryTomorrow, displayed: true)
                    sendEvent(name: "triggersTomorrow", value: state.triggersTomorrow, displayed: true)
                }
            }
        }
    }
    catch (SocketTimeoutException e) {
        if(logEnable) log.debug "Connection to Pollen.com API timed out."
        sendEvent(name: "location", value: "Connection timed out while retrieving data from API", displayed: true)
    }
    catch (e) {
        if(logEnable) log.debug "Could not retrieve pollen data: $e"
        sendEvent(name: "location", value: "Could not retrieve data from API", displayed: true)
    }
}

def configure() {
    poll()
}
