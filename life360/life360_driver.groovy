/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** LIFE360+ Hubitat App **
 * Life360 companion app to track members' location in your circle
 * - Community discussion: https://community.hubitat.com/t/release-life360/118544
 *
 * Changes:
 *  5.1.4  - 06/30/26 - add history preference
 *  5.0.15 - 12/31/24 - minor fixes
 *  5.0.12 - 12/24/24 - Dynamic Polling (mpalermo73)
 *  5.0.12 - 12/24/24 - Improve Randomness (mpalermo73 / @user3774)
 *  5.0.11 - 12/22/24 - bugfix when polling > 1 min
 *  5.0.10 - 12/21/24 - add some randomness
 *  5.0.9  - 12/19/24 - try a different API when hitting 403 error
 *  5.0.8  - 12/18/24 - added cookies found by @user3774
 *  5.0.7  - 12/11/24 - try to match Home Assistant
 *  5.0.6  - 12/05/24 - return to older API version (keeping eTag support)
 *  5.0.5  - 11/12/24 - support eTag for locations call
 *  5.0.4  - 11/09/24 - use newer API
 *  5.0.2  - 11/03/24 - restore webhook
 *  5.0.0  - 11/01/24 - fix Life360+ support (requires manual entry of access_token)
 *  4.0.0  - 02/08/24 - implement new Life360 API
 *
 * NOTE: This is a re-write of Life360+, which was just a continuation of "Life360 with States" -> https://community.hubitat.com/t/release-life360-with-states-track-all-attributes-with-app-and-driver-also-supports-rm4-and-dashboards/18274
 * - please see that thread for full history of this app/driver
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import java.text.SimpleDateFormat

metadata {
    definition(name: "Life360+ Driver", namespace: "jpage4500", author: "Joe Page", importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/life360/life360_driver.groovy") {
        capability "Actuator"
        capability "Presence Sensor"
        capability "Sensor"
        capability "Refresh"
        capability "Battery"
        capability "Power Source"
        capability "Switch"
        capability "Contact Sensor"
        capability "Acceleration Sensor"

        // location data
        attribute "latitude", "number"
        attribute "longitude", "number"
        attribute "accuracy", "number"
        attribute "lastUpdated", "date"

        // driving data
        attribute "inTransit", "enum", ["true", "false"]
        attribute "isDriving", "enum", ["true", "false"]
        attribute "speed", "number"
        attribute "distance", "number"
        attribute "since", "number"
        // NOTE: sent in V5 API (not implemented)
        attribute "userActivity", "string"

        // device data
        attribute "battery", "number"
        attribute "charge", "enum", ["true", "false"]
        attribute "status", "string"
        attribute "wifiState", "enum", ["true", "false"]
        attribute "shareLocation", "string"

        // user data
        attribute "avatar", "string"
        attribute "memberName", "string"
        attribute "phone", "string"
        attribute "email", "string"

        // place data
        attribute "address1", "string"
        attribute "address1prev", "string"
        // JSON formatted list of favorite places (circles)
        attribute "savedPlaces", "string"
        // Compact location history (only when saveHistory=true). Format: "1|s,lat,lng|ds,dlat,dlng|..."
        // - "1" is version; first entry is absolute (seconds-since-epoch + decimal lat/lng);
        //   subsequent entries are signed deltas (sec delta, int lat delta in 1e-5 deg, int lng delta in 1e-5 deg)
        attribute "history", "string"

        // hubitat device states
        attribute "contact", "string"
        attribute "acceleration", "string"
        attribute "switch", "string"

        // HTML attributes (optional)
        attribute "avatarHtml", "string"
        attribute "html", "string"

        command "refresh"
        // Trigger to manually force subscribe to / revalidate webhook to Life360 push notifications
        // command "refreshCirclePush"
        // -- presence sensor commands --
        command "arrived"
        command "departed"

        // called from Life360 Tracker app (https://community.hubitat.com/t/release-life360-tracker-works-with-the-life360-app/18276)
        command "sendHistory", ["string"]
        command "sendTheMap", ["string"]
        command "historyClearData"
    }
}

preferences {
    input "isMiles", "bool", title: "Units: Miles (false for Kilometer)", required: true, defaultValue: true
    input "generateHtml", "bool", title: "HTML Fields (tile, avatar)", required: true, defaultValue: false
    input "saveHistory", "bool", title: "Save Location History", description: "Save recent locations (time/lat/lng) to 'history' attribute", required: true, defaultValue: false

    input "transitThreshold", "number", title: "Minimum 'Transit' Speed", description: "Set minimum speed for inTransit to be true\n(leave as 0 to use Life360 data)", required: true, defaultValue: 0
    input "drivingThreshold", "number", title: "Minimum 'Driving' Speed", description: "Set minimum speed for isDriving to be true\n(leave as 0 to use Life360 data)", required: true, defaultValue: 0

    input "avatarFontSize", "number", title: "Avatar Font Size", required: true, defaultValue: 15
    input "avatarSize", "number", title: "Avatar Size by Percentage", required: true, defaultValue: 75

    input "logEnable", "bool", title: "Enable logging", required: true, defaultValue: false
}

// -- presence sensor commands --
def arrived() {
    sendEvent(name: "presence", value: "present")
}

def departed() {
    sendEvent(name: "presence", value: "not present")
}

// -- switch commands --
def off() {}

def on() {}

def refresh() {
    parent.refresh()
}

//def refreshCirclePush() {
//    // Manually ensure that Life360 notifications subscription is current / valid
//    log.info "refreshCirclePush: Attempting to resubscribe to circle notifications"
//    parent.createCircleSubscription()
//}

def installed() {
    log.info "installed: Location Tracker User Driver Installed"

    if (logEnable) log.info "installed: Setting attributes to initial values"

    sendEvent(name: "address1prev", value: "No Data")
}

def updated() {
    log.info "updated: Location Tracker User Driver has been Updated"
    refresh()
}

def strToDate(dateStr) {
    try {
        // "updated": "2024-11-07T21:42:09.900Z",
        return Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", dateStr)
    } catch (e) {
        log.error("dateToMs: bad date: ${dateStr}, ${e}")
    }
    return null
}

// called from Life360+ app
// @param member - Life360 member object (user details)
// @param thePlaces - all Life360 circles (locations)
// @param home - Life360 circle which user selected as 'home'
//
// @return true if member is in transit (inTransit=true OR speed > 1)
boolean generatePresenceEvent(member, thePlaces, home) {
    if (member == null) return false
    //log.trace("generatePresenceEvent: member:${member}")
    def location = member.location
    if (location == null) return false

    // NOTE: only interested in sending updates device when location or battery changes
    // -- location --
    Double latitude = toDouble(location.latitude)
    Double longitude = toDouble(location.longitude)
    Integer accuracy = toDouble(location.accuracy).round(0).toInteger()
    Integer battery = toDouble(location.battery).round(0).toInteger()
    Boolean wifiState = toBool(location.wifiState)
    Boolean charge = toBool(location.charge)
    Double speed = toDouble(location.speed)
    Boolean inTransit = toBool(location.inTransit)
    Boolean isDriving = toBool(location.isDriving)
    Long since = location.since.toLong()
    // NOTE: userActivity passed in v5 API (not implemented)
    // userActivity:[unknown|os_biking|os_running|vehicle]
    String userActivity = location.userActivity
    if (userActivity?.startsWith("os_")) {
        userActivity = userActivity.substring(3)
    }

    // -- name --
    String memberFirstName = (member.firstName) ? member.firstName : ""
    String memberLastName = (member.lastName) ? member.lastName : ""
    // -- home --
    Double homeLatitude = toDouble(home.latitude)
    Double homeLongitude = toDouble(home.longitude)
    Double homeRadius = toDouble(home.radius)

    String address1 = (location.name) ? location.name : location.address1
    String address2 = (location.address2) ? location.address2 : location.shortaddress

    // -- previous values (could be null) --
    Double prevLatitude = device.currentValue('latitude')
    Double prevLongitude = device.currentValue('longitude')
    Integer prevAccuracy = device.currentValue('accuracy')
    Integer prevBattery = device.currentValue('battery')
    String prevWifiState = device.currentValue('wifiState')

    // detect if location (lat/lng/accuracy) changed from previous update
    Boolean isLocationChanged = (prevLatitude == null || prevLatitude != latitude
        || (prevLongitude == null || prevLongitude != longitude)
        || (prevAccuracy == null || prevAccuracy != accuracy))

    // skip update if location, accuracy and battery have not changed
    if (!inTransit && speed <= 0 && !isLocationChanged
        && (prevBattery != null && prevBattery == battery)
        && (prevWifiState != null && prevWifiState.toBoolean() == wifiState)) {
        // NOTE: uncomment to see 'no change' updates every <30> seconds
        if (logEnable) log.trace "generatePresenceEvent: No change: $latitude/$longitude, acc:$accuracy, b:$battery%, wifi:$wifiState, speed:${speed.round(2)}, inTransit:$inTransit"
        return false
    }

    // -----------------------------------------------
    // ** location/accuracy/battery/battery changed **
    // -----------------------------------------------
    if (logEnable) log.info "generatePresenceEvent: <strong>change</strong>: $latitude/$longitude, acc:$accuracy, b:$battery%, wifi:$wifiState, speed:${speed.round(2)}, inTransit:$inTransit"
    Date lastUpdated = new Date()

    // *** Member Name ***
    String memberFullName = memberFirstName + " " + memberLastName
    sendEvent(name: "memberName", value: memberFullName)

    // *** Places List ***
    // format as JSON string for better parsing
    def savedPlacesJson = new groovy.json.JsonBuilder(thePlaces)
    sendEvent(name: "savedPlaces", value: savedPlacesJson.toString())

    // *** Avatar ***
    String avatar
    String avatarHtml
    if (member.avatar != null) {
        avatar = member.avatar
        avatarHtml = "<img src= \"${avatar}\">"
    } else {
        avatar = "not set"
        avatarHtml = "not set"
    }
    sendEvent(name: "avatar", value: avatar)

    // *** Location ***
    Double distanceAway = haversine(latitude, longitude, homeLatitude, homeLongitude) * 1000 // in meters
    // It is safe to assume that if we are within home radius then we are
    // both present and at home (to address any potential radius jitter)
    String memberPresence = (distanceAway <= homeRadius) ? "present" : "not present"
    //if (logEnable) log.info "generatePresenceEvent: present: $memberPresence, distance:$distanceAway, home:$homeLatitude/$homeLongitude/$homeRadius"

    // Where we think we are now is either at a named place or at address1
    // or perhaps we are on the free version of Life360 (address1  = null)
    if (address1 == null || address1 == "") address1 = "No Data"
    if (address2 == null || address2 == "") address2 = "No Data"

    // *** Address ***
    // If we are present then we are Home...
    address1 = (memberPresence == "present") ? "Home" : address1

    String prevAddress = device.currentValue('address1')
    if (address1 != prevAddress) {
        if (logEnable) log.info "generatePresenceEvent: address1:$address1, prevAddress = $prevAddress"
        // Update old and current address information and trigger events
        sendEvent(name: "address1prev", value: prevAddress)
        sendEvent(name: "address1", value: address1)
        sendEvent(name: "lastLocationUpdate", value: lastUpdated)
        sendEvent(name: "since", value: since)
    }

    // *** Presence ***
    String descriptionText = device.displayName + " has " + ((memberPresence == "present") ? "arrived" : "left")
    sendEvent(name: "presence", value: memberPresence, descriptionText: descriptionText)
    state.presence = memberPresence

    // *** Coordinates ***
    sendEvent(name: "longitude", value: longitude)
    sendEvent(name: "latitude", value: latitude)
    sendEvent(name: "accuracy", value: accuracy)

    // *** Location History (only when lat/lng actually changed) ***
    if (saveHistory && (prevLatitude == null || prevLatitude != latitude
        || prevLongitude == null || prevLongitude != longitude)) {
        saveLocationHistory(latitude, longitude, lastUpdated.getTime())
    }

    // Update status attribute with appropriate distance units
    // and update appropriate speed units
    // as chosen by users in device preferences
    Double speedUnits       // in user's preference of MPH or KPH
    Double distanceUnits    // in user's preference of miles or km
    // check for iPhone reporting speed of -1
    if (speed == -1) speed = 0.0
    speedUnits = (speed * (isMiles ? 2.23694 : 3.6)).round(2)
    distanceUnits = ((distanceAway / 1000) / ((isMiles ? 1.609344 : 1))).round(2)

    // if transit threshold specified in preferences then use it; else, use info provided by Life360
    if (transitThreshold.toDouble() > 0.0) {
        inTransit = (speedUnits >= transitThreshold.toDouble())
    }
    // if driving threshold specified in preferences then use it; else, use info provided by Life360
    if (drivingThreshold.toDouble() > 0.0) {
        isDriving = (speedUnits >= drivingThreshold.toDouble())
    }

    // *** Stale inTransit override (Life360 keeps inTransit=true for stationary members) ***
    // If Life360 says we're in transit but speed is ~0 AND we haven't moved more than GPS
    // accuracy since the last update, ignore the flag. This prevents the app's dynamic
    // polling from being held active indefinitely by a stuck Life360 flag.
    Double movedMeters = 0.0
    if (prevLatitude != null && prevLongitude != null) {
        movedMeters = haversine(prevLatitude, prevLongitude, latitude, longitude) * 1000.0
    }
    Double accuracyMeters = Math.max((prevAccuracy ?: 0) as double, (accuracy ?: 0) as double)
    if (inTransit && speedUnits < 0.5d && movedMeters <= accuracyMeters) {
        log.info("${memberFirstName}: ignoring stale Life360 inTransit flag (speed:${speedUnits}, moved:${movedMeters.round(0)}m within ${accuracyMeters.round(0)}m accuracy)")
        inTransit = false
    } else if (movedMeters > accuracyMeters && (inTransit || isDriving)) {
        // Real movement — surface it as info so users can correlate polls with motion.
        Double movedUnits = ((movedMeters / 1000.0) / (isMiles ? 1.609344 : 1.0)).round(2)
        String mapsUrl = "https://www.google.com/maps/search/?api=1&query=${latitude},${longitude}"
        log.info("${memberFirstName}: moved ${movedUnits} ${isMiles ? 'mi' : 'km'} @ ${speedUnits} ${isMiles ? 'mph' : 'kph'} — <a href='${mapsUrl}' target='_blank'>Google Maps link</a>")
    }

    String sStatus = (memberPresence == "present") ? "At Home" : sprintf("%.1f", distanceUnits) + ((isMiles) ? " miles from Home" : "km from Home")

    if (logEnable && (isDriving || inTransit)) {
        // *** On the move ***
        log.debug "generatePresenceEvent: $sStatus, speedUnits:$speedUnits, transitThreshold: $transitThreshold, inTransit: $inTransit, drivingThreshold: $drivingThreshold, isDriving: $isDriving"
    }

    sendEvent(name: "status", value: sStatus)
    state.status = sStatus

    sendEvent(name: "inTransit", value: inTransit)
    sendEvent(name: "isDriving", value: isDriving)
    sendEvent(name: "speed", value: speedUnits)
    sendEvent(name: "distance", value: distanceUnits)
    sendEvent(name: "userActivity", value: userActivity)

    // Set acceleration to active state if we are either moving or if we are anywhere outside home radius
    sendEvent(name: "acceleration", value: (inTransit || isDriving || memberPresence == "not present") ? "active" : "inactive")

    // *** Battery Level ***
    sendEvent(name: "battery", value: battery)

    // *** Charging State ***
    String powerSource = charge ? "DC" : "BTRY"
    sendEvent(name: "charge", value: charge)
    sendEvent(name: "powerSource", value: powerSource)
    sendEvent(name: "contact", value: charge ? "open" : "closed")

    // *** Wifi ***
    sendEvent(name: "wifiState", value: wifiState)
    sendEvent(name: "switch", value: wifiState ? "on" : "off")

    // ** Member Features **
    if (member.features != null) {
        sendEvent(name: "shareLocation", value: member.features.shareLocation)
    }

    // ** Member Communications **
    if (member.communications != null) {
        member.communications.each { comm ->
            String commType = comm.get('channel')
            if (commType != null && commType == "Voice") {
                sendEvent(name: "phone", value: comm.value)
            } else if (commType != null && commType == "Email") {
                sendEvent(name: "email", value: comm.value)
            }
        }
    }

    // *** Timestamp ***
    sendEvent(name: "lastUpdated", value: lastUpdated)
    state.update = true

    // ** HTML attributes (optional) **
    if (!generateHtml) {
        return inTransit
    }

    // send HTML avatar if generateHTML is enabled; otherwise clear it (only if previously set)
    sendEvent(name: "avatarHtml", value: avatarHtml)

    String binTransita
    if (isDriving) binTransita = "Driving"
    else if (inTransit) binTransita = "Moving"
    else binTransita = "Not Moving"

    int sEpoch = device.currentValue('since')
    if (sEpoch == null) {
        theDate = use(groovy.time.TimeCategory) {
            new Date(0)
        }
    } else {
        theDate = use(groovy.time.TimeCategory) {
            new Date(0) + sEpoch.seconds
        }
    }
    SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("E hh:mm a")
    String dateSince = DATE_FORMAT.format(theDate)

    String theMap = "https://www.google.com/maps/search/?api=1&query=" + latitude.toString() + "," + longitude.toString()

    tileMap = "<div style='overflow:auto;height:90%'><table width='100%'>"
    tileMap += "<tr><td width='25%' align=center><img src='${avatar}' height='${avatarSize}%'>"
    tileMap += "<td width='75%'><p style='font-size:${avatarFontSize}px'>"
    tileMap += "At: <a href='${theMap}' target='_blank'>${address1 == "No Data" ? "Between Places" : address1}</a><br>"
    tileMap += "Since: ${dateSince}<br>"
    tileMap += (sStatus == "At Home") ? "" : "${sStatus}<br>"
    tileMap += "${binTransita}"
    if (address1 == "No Data" ? "Between Places" : address1 != "Home" && inTransit) {
        tileMap += " @ ${sprintf("%.1f", speed)} "
        tileMap += isMiles ? "MPH" : "KPH"
    }
    tileMap += "<br>Phone Lvl: ${battery} - ${powerSource} - "
    tileMap += wifiState ? "WiFi" : "No WiFi"
    tileMap += "<br><p style='width:100%'>${lastUpdated}</p>"
    tileMap += "</table></div>"

    int tileDevice1Count = tileMap.length()
    if (tileDevice1Count > 1024) log.warn "generatePresenceEvent: Too many characters to display on Dashboard (${tileDevice1Count})"
    sendEvent(name: "html", value: tileMap, displayed: true)
    return inTransit
}

/**
 * Append a location to state.locationHistory as [timeMs, lat, lng], most recent first, capped at 100 entries.
 */
def saveLocationHistory(Double latitude, Double longitude, Long timeMs) {
    try {
        if (state.locationHistory == null) state.locationHistory = []

        // round lat/lng to 5 decimals (~1m precision) to keep entries compact
        Double latRounded = Math.round(latitude * 100000) / 100000.0
        Double lngRounded = Math.round(longitude * 100000) / 100000.0

        state.locationHistory.add(0, [timeMs, latRounded, lngRounded])

        // cap at 100 entries
        while (state.locationHistory.size() > 100) {
            state.locationHistory.remove(state.locationHistory.size() - 1)
        }

        // generate history attribute
        getHistory()
    } catch (e) {
        log.error "saveLocationHistory: ${e}"
    }
}

/**
 * Write up to 1024 chars of location history to the 'history' attribute.
 *
 * Format v1: "1|s,lat,lng|ds,dlat,dlng|ds,dlat,dlng|..."
 *   - leading "1" is the format version
 *   - first entry is absolute: s = seconds-since-epoch, lat/lng = decimal (5 decimals)
 *   - subsequent entries are signed deltas from the previous entry:
 *       ds   = seconds delta (negative since list is most-recent-first)
 *       dlat = integer delta in units of 0.00001 deg
 *       dlng = integer delta in units of 0.00001 deg
 *   - to decode entry i: s_i = s_{i-1} + ds_i, lat_i = lat_{i-1} + dlat_i/1e5
 *   - entries within 5m of the previous emitted entry are skipped (GPS jitter filter)
 */
def getHistory() {
    try {
        def entries = state.locationHistory ?: []
        StringBuilder sb = new StringBuilder("1")
        int count = 0
        Long prevSec = null
        Long prevLat100k = null
        Long prevLng100k = null
        for (entry in entries) {
            Double latDeg = entry[1] as Double
            Double lngDeg = entry[2] as Double
            Long sec = (entry[0] as Long).intdiv(1000)
            Long lat100k = Math.round(latDeg * 100000) as Long
            Long lng100k = Math.round(lngDeg * 100000) as Long

            // skip subsequent entries that haven't moved >5m from the previous emitted entry (GPS jitter)
            if (prevSec != null) {
                Double meters = haversine(prevLat100k / 100000.0, prevLng100k / 100000.0, latDeg, lngDeg) * 1000.0
                if (meters < 5.0) continue
            }

            String token
            if (prevSec == null) {
                token = "|${sec},${lat100k / 100000.0},${lng100k / 100000.0}"
            } else {
                token = "|${sec - prevSec},${lat100k - prevLat100k},${lng100k - prevLng100k}"
            }

            if (sb.length() + token.length() > 1024) break
            sb.append(token)
            prevSec = sec
            prevLat100k = lat100k
            prevLng100k = lng100k
            count++
        }

        String result = sb.toString()
        sendEvent(name: "history", value: result)
        return result
    } catch (e) {
        log.error "getHistory: ${e}"
        sendEvent(name: "history", value: "1")
        return "1"
    }
}

def haversine(lat1, lon1, lat2, lon2) {
    Double R = 6372.8
    // In kilometers
    Double dLat = Math.toRadians(lat2 - lat1)
    Double dLon = Math.toRadians(lon2 - lon1)
    lat1 = Math.toRadians(lat1)
    lat2 = Math.toRadians(lat2)

    Double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2)
    Double c = 2 * Math.asin(Math.sqrt(a))
    Double d = R * c
    return (d)
}

/**
 * for compatibility with Life360 Tracker app (https://community.hubitat.com/t/release-life360-tracker-works-with-the-life360-app/18276)
 */
def sendHistory(msgValue) {
    if (logEnable) log.trace "In sendHistory - nameValue: ${msgValue}"

    if (msgValue == null || msgValue.contains("No Data")) {
        if (logEnable) log.trace "In sendHistory - Nothing to report (No Data)"
    } else {
        try {
            if (state.list1 == null) state.list1 = []

            def date = new Date()
            newDate = date.format("E HH:mm")

            last = "${newDate} - ${msgValue}"
            state.list1.add(0, last)

            if (state.list1) {
                listSize1 = state.list1.size()
            } else {
                listSize1 = 0
            }

            int intNumOfLines = 10
            if (listSize1 > intNumOfLines) state.list1.removeAt(intNumOfLines)
            String result1 = state.list1.join(";")
            def lines1 = result1.split(";")

            theData1 = "<div style='overflow:auto;height:90%'><table style='text-align:left;font-size:${avatarFontSize}px'><tr><td>"

            for (i = 0; i < intNumOfLines && i < listSize1; i++) {
                combined = theData1.length() + lines1[i].length()
                if (combined < 1006) {
                    theData1 += "${lines1[i]}<br>"
                }
            }

            theData1 += "</table></div>"
            if (logEnable) log.debug "theData1 - ${theData1.replace("<", "!")}"

            dataCharCount1 = theData1.length()
            if (dataCharCount1 <= 1024) {
                if (logEnable) log.debug "What did I Say Attribute - theData1 - ${dataCharCount1} Characters"
            } else {
                theData1 = "Too many characters to display on Dashboard (${dataCharCount1})"
            }

            sendEvent(name: "bpt-history", value: theData1, displayed: true)
            sendEvent(name: "numOfCharacters", value: dataCharCount1, displayed: true)
            sendEvent(name: "lastLogMessage", value: msgValue, displayed: true)
        }
        catch (e1) {
            log.error "In sendHistory - Something went wrong<br>${e1}"
        }
    }
}

/**
 * for compatibility with Life360 Tracker app (https://community.hubitat.com/t/release-life360-tracker-works-with-the-life360-app/18276)
 */
def historyClearData() {
    if (logEnable) log.trace "historyClearData"
    // clear location history
    state.locationHistory = []
    state.list1 = []
    if (logEnable) log.info "Clearing the data"
    String historyLog = "Waiting for Data..."
    sendEvent(name: "bpt-history", value: historyLog, displayed: true)
    sendEvent(name: "numOfCharacters", value: 0, displayed: true)
    sendEvent(name: "lastLogMessage", value: "-", displayed: true)
}

/**
 * for compatibility with Life360 Tracker app (https://community.hubitat.com/t/release-life360-tracker-works-with-the-life360-app/18276)
 */
def sendTheMap(theMap) {
    lastMap = "${theMap}"
    sendEvent(name: "lastMap", value: lastMap, displayed: true)
}

/**
 * null-safe toDouble()
 */
static double toDouble(Object object) {
    if (object) return object.toDouble()
    else return 0
}

/**
 * null-safe toBool()
 */
static boolean toBool(Object object) {
    if (object) return object == "1"
    else return false
}
