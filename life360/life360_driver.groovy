// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1244
// hubitat end

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** LIFE360+ Hubitat Driver **
 * Life360 companion app to track members' location in your circle
 *
 * - see community discussion here: https://community.hubitat.com/t/release-life360/118544
 *
 *  Changes:
 *  5.0.8 - 12/18/24 - added cookies found by @user3774
 *  5.0.7 - 12/11/24 - try to match Home Assistant
 *  5.0.6 - 12/05/24 - return to older API version (keeping eTag support)
 *  5.0.4 - 11/09/24 - use newer API
 *  5.0.0 - 11/01/24 - fix Life360+ support (requires manual entry of access_token)
 *
 * NOTE: This is a re-write of Life360+, which was just a continuation of "Life360 with States" -> https://community.hubitat.com/t/release-life360-with-states-track-all-attributes-with-app-and-driver-also-supports-rm4-and-dashboards/18274
 * - please see that thread for full history of this app/driver
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 */

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
        attribute "savedPlaces", "string"

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

    address1prev = "No Data"
    sendEvent(name: address1prev, value: address1prev)
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
// @return true if member location changed from last update; false if no change
Boolean generatePresenceEvent(member, thePlaces, home) {
    //log.trace("generatePresenceEvent: member:${member}")
    if (member.location == null) return

    // NOTE: only interested in sending updates device when location or battery changes
    // -- location --
    Double latitude = toDouble(member.location.latitude)
    Double longitude = toDouble(member.location.longitude)
    Integer accuracy = toDouble(member.location.accuracy).round(0).toInteger()
    Integer battery = toDouble(member.location.battery).round(0).toInteger()
    Boolean wifiState = member.location.wifiState == 1
    Boolean charge = member.location.charge == 1
    Double speed = toDouble(member.location.speed)
    Boolean inTransit = member.location.inTransit == 1
    Boolean isDriving = member.location.isDriving == 1
    Long since = member.location.since.toLong()
    // userActivity:[unknown|os_biking|os_running|vehicle]
    String userActivity = member.location.userActivity
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

    String address1 = (member.location.name) ? member.location.name : member.location.address1
    String address2 = (member.location.address2) ? member.location.address2 : member.location.shortaddress

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
    if (!isLocationChanged
        && (prevBattery != null && prevBattery == battery)
        && (prevWifiState != null && prevWifiState.toBoolean() == wifiState)) {
        // NOTE: uncomment to see 'no change' updates every <30> seconds
        if (logEnable) log.trace "generatePresenceEvent: No change: $latitude/$longitude, acc:$accuracy, b:$battery%, wifi:$wifiState, speed:$speed"
        return false
    }

    // -----------------------------------------------
    // ** location/accuracy/battery/battery changed **
    // -----------------------------------------------
    if (logEnable) log.info "generatePresenceEvent: <strong>change</strong>: $latitude/$longitude, acc:$accuracy, b:$battery%, wifi:$wifiState, speed:${speed.round(2)}"
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
    String descriptionText = device.displayName + " has " + (memberPresence == "present") ? "arrived" : "left"
    sendEvent(name: "presence", value: memberPresence, descriptionText: descriptionText)
    state.presence = memberPresence

    // *** Coordinates ***
    sendEvent(name: "longitude", value: longitude)
    sendEvent(name: "latitude", value: latitude)
    sendEvent(name: "accuracy", value: accuracy)

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
        // clear out existing html values (only useful if you previously had HTML enabled..)
        sendEvent(name: "avatarHtml", value: null)
        sendEvent(name: "html", value: null)
        return isLocationChanged
    }

    // send HTML avatar if generateHTML is enabled; otherwise clear it (only if previously set)
    sendEvent(name: "avatarHtml", value: generateHtml)

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
    String lastUpdatedDesc = DATE_FORMAT.format(lastUpdated)

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
    return isLocationChanged
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

            theData1 = "<div style='overflow:auto;height:90%'><table style='text-align:left;font-size:${fontSize}px'><tr><td>"

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
    if (logEnable) log.trace "In historyClearData"
    msgValue = "-"
    logCharCount = "0"
    state.list1 = []
    if (logEnable) log.info "Clearing the data"
    historyLog = "Waiting for Data..."
    sendEvent(name: "bpt-history", value: historyLog, displayed: true)
    sendEvent(name: "numOfCharacters1", value: logCharCount1, displayed: true)
    sendEvent(name: "lastLogMessage1", value: msgValue, displayed: true)
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