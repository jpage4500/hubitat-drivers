/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** LIFE360+ Hubitat Driver **
 * Per-member child device for the Life360+ companion app
 * - Community discussion: https://community.hubitat.com/t/release-life360/118544
 *
 * Changelog: see CHANGELOG.md alongside this file
 *   https://github.com/jpage4500/hubitat-drivers/blob/master/life360/CHANGELOG.md
 *
 * NOTE: This is a re-write of Life360+, which was just a continuation of "Life360 with States" -> https://community.hubitat.com/t/release-life360-with-states-track-all-attributes-with-app-and-driver-also-supports-rm4-and-dashboards/18274
 * - please see that thread for full history of this app/driver
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import java.text.SimpleDateFormat
import groovy.transform.Field

@Field static final int    HUBITAT_TILE_MAX_CHARS  = 1024    // Hubitat dashboard tile character limit
@Field static final int    MAX_HISTORY_ENTRIES     = 100     // max location history entries kept in state
@Field static final int    MAX_HISTORY_LOG_LINES   = 10      // max lines shown in the bpt-history tile
@Field static final int    HISTORY_TILE_FOOTER_LEN = 18      // byte length of "</table></div>" closing tag
@Field static final long   LAT_LNG_PRECISION       = 100000L // 1e5 — 5 decimal places (~1m GPS precision)
@Field static final double GPS_JITTER_METERS       = 5.0      // minimum movement to record a new history entry
@Field static final double MS_TO_MPH               = 2.23694  // m/s → miles per hour
@Field static final double MS_TO_KPH               = 3.6      // m/s → kilometres per hour
@Field static final double KM_PER_MI               = 1.609344 // kilometres per mile (divisor for km→mi)
@Field static final double EARTH_RADIUS_KM         = 6372.8   // mean Earth radius in km (used in haversine)

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
        attribute "lastLocationUpdate", "date"

        // driving data
        attribute "inTransit", "enum", ["true", "false"]
        attribute "isDriving", "enum", ["true", "false"]
        attribute "speed", "number"
        attribute "distance", "number"
        attribute "since", "number"
        // NOTE: sent in V5 API (not implemented)
        attribute "userActivity", "string"

        // device data
        // NOTE: 'battery' attribute is provided by capability "Battery"; don't redeclare (§8.11)
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
        attribute "contact", "enum", ["open", "closed"]
        attribute "acceleration", "enum", ["active", "inactive"]
        attribute "switch", "enum", ["on", "off"]

        // HTML attributes (optional)
        attribute "avatarHtml", "string"
        attribute "html", "string"

        // legacy Life360 Tracker app compatibility attributes
        attribute "bpt-history", "string"
        attribute "numOfCharacters", "number"
        attribute "lastLogMessage", "string"
        attribute "lastMap", "string"

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
    input "isMiles", "bool", title: "Units: Miles (fallback only — your Life360 account's units setting is used when available)", required: true, defaultValue: true
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
}

def updated() {
    log.info "updated: Location Tracker User Driver has been Updated"
    refresh()
}

// called from Life360+ app
// @param member - Life360 member object (user details)
// @param thePlaces - pre-serialized JSON of all Life360 places (string); a raw Map is still accepted for backward compat
// @param home - Life360 circle which user selected as 'home'
//
// @return true if member is in transit (per Life360 inTransit flag)
boolean generatePresenceEvent(member, thePlaces, home) {
    if (member == null) return false
    //log.trace("generatePresenceEvent: member:${member}")
    def location = member.location
    if (location == null) return false

    // hoist parent IPC calls once per event — avoids repeated cross-device calls in the hot path
    boolean showNames = true
    try { showNames = (parent?.getShowNamesInLogs() != false) } catch (ignored) {}
    boolean showMapsLink = true
    try { showMapsLink = (parent?.getShowMapsLink() != false) } catch (ignored) {}

    // -- location --
    // round to 5 decimal places (~1m precision) — matches history storage and avoids spurious sub-meter jitter
    // cast denominator to double to avoid Groovy long/long integer division truncating the result
    Double latitude = Math.round(toDouble(location.latitude) * LAT_LNG_PRECISION) / (LAT_LNG_PRECISION as double)
    Double longitude = Math.round(toDouble(location.longitude) * LAT_LNG_PRECISION) / (LAT_LNG_PRECISION as double)
    Integer accuracy = toDouble(location.accuracy).round(0).toInteger()
    Integer battery = toDouble(location.battery).round(0).toInteger()
    Boolean wifiState = toBool(location.wifiState)
    Boolean charge = toBool(location.charge)
    Double speed = toDouble(location.speed)
    Boolean inTransit = toBool(location.inTransit)
    Boolean isDriving = toBool(location.isDriving)
    Long since = (location.since != null) ? location.since.toLong() : 0L
    // NOTE: userActivity passed in v5 API (not implemented)
    // userActivity:[unknown|os_biking|os_running|vehicle]
    String userActivity = location.userActivity
    if (userActivity?.startsWith("os_")) {
        userActivity = userActivity.substring(3)
    }

    // -- name --
    String memberFirstName = (member.firstName) ? member.firstName : ""
    String memberLastName = (member.lastName) ? member.lastName : ""
    // -- home -- (null if the selected place was removed from Life360; treat as absent)
    if (home == null) {
        log.warn("generatePresenceEvent: home place not found — check that the selected HOME place still exists in Life360")
        return false
    }
    Double homeLatitude = toDouble(home.latitude)
    Double homeLongitude = toDouble(home.longitude)
    Double homeRadius = toDouble(home.radius)

    String address1 = (location.name) ? location.name : location.address1

    // -- previous values (used for address history and location history) --
    Double prevLatitude = device.currentValue('latitude')
    Double prevLongitude = device.currentValue('longitude')

    if (parent?.getLogRawPayload()) log.trace("RAW L360 ${displayMember(memberFirstName, showNames)}: ${location}")

    // -----------------------------------------------
    // ** location/accuracy/battery/battery changed **
    // -----------------------------------------------
    if (logEnable) log.debug "generatePresenceEvent: changed: lat:$latitude lng:$longitude acc:${accuracy}m b:${battery}% wifi:$wifiState speed:${speed.round(2)}m/s inTransit:$inTransit isDriving:$isDriving"
    Date lastUpdated = new Date()

    // *** Member Name ***
    String memberFullName = memberFirstName + " " + memberLastName
    sendEvent(name: "memberName", value: memberFullName)

    // *** Places List ***
    // app pre-serializes once per poll (§4.5); tolerate a raw Map from an older app
    String savedPlacesValue = (thePlaces instanceof CharSequence) ? thePlaces.toString() : new groovy.json.JsonBuilder(thePlaces).toString()
    sendEvent(name: "savedPlaces", value: savedPlacesValue)

    // *** Avatar ***
    String avatar
    String avatarHtml
    if (member.avatar != null) {
        avatar = member.avatar
        avatarHtml = avatar.startsWith("http") ? "<img src=\"${avatar}\">" : ""
    } else {
        avatar = ""
        avatarHtml = ""
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

    // *** Address ***
    // If we are present then we are Home...
    address1 = (memberPresence == "present") ? "Home" : address1

    String prevAddress = device.currentValue('address1')
    if (address1 != prevAddress) {
        if (logEnable) log.debug "generatePresenceEvent: address1:$address1, prevAddress = $prevAddress"
        // Update old and current address information and trigger events
        sendEvent(name: "address1prev", value: prevAddress)
        sendEvent(name: "address1", value: address1)
        sendEvent(name: "lastLocationUpdate", value: lastUpdated)
        sendEvent(name: "since", value: since)
    }

    // *** Presence ***
    String descriptionText = device.displayName + " has " + ((memberPresence == "present") ? "arrived" : "left")
    sendEvent(name: "presence", value: memberPresence, descriptionText: descriptionText)

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
    // Units follow the user's Life360 account setting (settings.unitOfMeasure) when the app
    // has learned it; the local isMiles toggle is only a fallback until then.
    Boolean useMiles = isMiles
    try {
        Boolean apiMiles = parent?.getUnitIsMiles()
        if (apiMiles != null) useMiles = apiMiles
    } catch (ignored) {}
    speedUnits = (speed * (useMiles ? MS_TO_MPH : MS_TO_KPH)).round(2)
    distanceUnits = ((distanceAway / 1000) / (useMiles ? KM_PER_MI : 1)).round(2)

    // ── Motion state ─────────────────────────────────────────────────────────────
    // Trust Life360's inTransit/isDriving flags directly.
    // Manual thresholds (transitThreshold/drivingThreshold) let the user override
    // with speed-based logic if they choose — otherwise Life360's values stand.
    double transitThresholdD = (transitThreshold ?: 0).toDouble()
    double drivingThresholdD = (drivingThreshold ?: 0).toDouble()
    boolean thresholdActive = (transitThresholdD > 0.0 || drivingThresholdD > 0.0)
    if (transitThresholdD > 0.0) {
        inTransit = (speedUnits >= transitThresholdD)
    }
    if (drivingThresholdD > 0.0) {
        isDriving = (speedUnits >= drivingThresholdD)
    }

    if (inTransit || isDriving) {
        String suffix = showMapsLink ? " — <a href='https://www.google.com/maps/search/?api=1&query=${latitude},${longitude}' target='_blank'>Google Maps link</a>" : ""
        log.info("${displayMember(memberFirstName, showNames)}: moving @ ${speedUnits} ${useMiles ? 'mph' : 'kph'}${suffix}")
    }
    // Only worth logging when a threshold override is in play — otherwise this just restates
    // the RAW L360 flags (and the "moving @" line already shows the speed). §5.4 trust-the-payload.
    if (thresholdActive && logEnable) log.debug("MOTION ${displayMember(memberFirstName, showNames)}: " +
        "speedUnits:${speedUnits} inTransit:${inTransit} isDriving:${isDriving} (threshold override)")

    String sStatus = (memberPresence == "present") ? "At Home" : sprintf("%.1f", distanceUnits) + ((useMiles) ? " miles from Home" : " km from Home")

    if (logEnable && (isDriving || inTransit)) {
        log.debug "generatePresenceEvent: $sStatus, speedUnits:$speedUnits, transitThreshold:$transitThreshold, inTransit:$inTransit, drivingThreshold:$drivingThreshold, isDriving:$isDriving"
    }

    sendEvent(name: "status", value: sStatus)

    sendEvent(name: "inTransit", value: inTransit.toString())
    sendEvent(name: "isDriving", value: isDriving.toString())
    sendEvent(name: "speed", value: speedUnits)
    sendEvent(name: "distance", value: distanceUnits)
    sendEvent(name: "userActivity", value: userActivity)

    // Set acceleration to active state if we are either moving or if we are anywhere outside home radius
    // REPURPOSED CAPABILITY (§5.4): 'acceleration' active/inactive = in transit OR driving OR away from home (NOT physical acceleration)
    sendEvent(name: "acceleration", value: (inTransit || isDriving || memberPresence == "not present") ? "active" : "inactive")

    // *** Battery Level ***
    sendEvent(name: "battery", value: battery)

    // *** Charging State ***
    String powerSource = charge ? "dc" : "battery"   // canonical Power Source capability enum values
    String powerSourceDisplay = charge ? "DC" : "BTRY"  // abbreviated form used in tile display only
    sendEvent(name: "charge", value: charge.toString())
    sendEvent(name: "powerSource", value: powerSource)
    // REPURPOSED CAPABILITY (§5.4): 'contact' open/closed = charging / on battery (NOT a contact sensor)
    sendEvent(name: "contact", value: charge ? "open" : "closed")

    // *** Wifi ***
    sendEvent(name: "wifiState", value: wifiState.toString())
    // REPURPOSED CAPABILITY (§5.4): 'switch' on/off = WiFi connected / not (NOT a controllable switch)
    sendEvent(name: "switch", value: wifiState ? "on" : "off")

    // ** Member Features **
    if (member.features != null) {
        sendEvent(name: "shareLocation", value: toBool(member.features.shareLocation).toString())
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

    // ** HTML attributes (optional) **
    if (!generateHtml) {
        return inTransit
    }

    // send HTML avatar if generateHTML is enabled; otherwise clear it (only if previously set)
    sendEvent(name: "avatarHtml", value: avatarHtml)

    String motionLabel
    if (isDriving) motionLabel = "Driving"
    else if (inTransit) motionLabel = "Moving"
    else motionLabel = "Not Moving"

    long sEpoch = since ?: 0L
    Date theDate = (sEpoch == 0L) ? new Date(0) : new Date(sEpoch * 1000L)
    SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("E hh:mm a")
    String dateSince = DATE_FORMAT.format(theDate)

    String theMap = "https://www.google.com/maps/search/?api=1&query=" + latitude.toString() + "," + longitude.toString()

    String tileMap = "<div style='overflow:auto;height:90%'><table width='100%'>"
    tileMap += "<tr><td width='25%' align=center>${(avatar && avatar.startsWith("http")) ? "<img src='${avatar}' height='${avatarSize}%'>" : ""}"
    tileMap += "<td width='75%'><p style='font-size:${avatarFontSize}px'>"
    tileMap += "At: <a href='${theMap}' target='_blank'>${address1 == "No Data" ? "Between Places" : escapeHtml(address1)}</a><br>"
    tileMap += "Since: ${dateSince}<br>"
    tileMap += (sStatus == "At Home") ? "" : "${sStatus}<br>"
    tileMap += "${motionLabel}"
    if (address1 != "Home" && inTransit) {
        tileMap += " @ ${sprintf("%.1f", speedUnits)} "
        tileMap += useMiles ? "MPH" : "KPH"
    }
    tileMap += "<br>Phone Lvl: ${battery} - ${powerSourceDisplay} - "
    tileMap += wifiState ? "WiFi" : "No WiFi"
    tileMap += "<br><p style='width:100%'>${lastUpdated}</p>"
    tileMap += "</table></div>"

    int tileDevice1Count = tileMap.length()
    if (tileDevice1Count > HUBITAT_TILE_MAX_CHARS) log.warn "generatePresenceEvent: Too many characters to display on Dashboard (${tileDevice1Count})"
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
        Double latRounded = Math.round(latitude * LAT_LNG_PRECISION) / (LAT_LNG_PRECISION as double)
        Double lngRounded = Math.round(longitude * LAT_LNG_PRECISION) / (LAT_LNG_PRECISION as double)

        state.locationHistory.add(0, [timeMs, latRounded, lngRounded])

        // cap at 100 entries
        while (state.locationHistory.size() > MAX_HISTORY_ENTRIES) {
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
        Long prevSec = null
        Long prevLat100k = null
        Long prevLng100k = null
        for (entry in entries) {
            Double latDeg = entry[1] as Double
            Double lngDeg = entry[2] as Double
            Long sec = (entry[0] as Long).intdiv(1000)
            Long lat100k = Math.round(latDeg * LAT_LNG_PRECISION) as Long
            Long lng100k = Math.round(lngDeg * LAT_LNG_PRECISION) as Long

            // skip subsequent entries that haven't moved >GPS_JITTER_METERS from the previous emitted entry
            if (prevSec != null) {
                Double meters = haversine(prevLat100k / LAT_LNG_PRECISION, prevLng100k / LAT_LNG_PRECISION, latDeg, lngDeg) * 1000.0
                if (meters < GPS_JITTER_METERS) continue
            }

            String token
            if (prevSec == null) {
                token = "|${sec},${lat100k / (LAT_LNG_PRECISION as double)},${lng100k / (LAT_LNG_PRECISION as double)}"
            } else {
                token = "|${sec - prevSec},${lat100k - prevLat100k},${lng100k - prevLng100k}"
            }

            if (sb.length() + token.length() > HUBITAT_TILE_MAX_CHARS) break
            sb.append(token)
            prevSec = sec
            prevLat100k = lat100k
            prevLng100k = lng100k
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

// §8.12: pure math, no instance state — make static
static def haversine(lat1, lon1, lat2, lon2) {
    Double R = EARTH_RADIUS_KM
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
    if (logEnable) log.debug "In sendHistory - nameValue: ${msgValue}"

    if (msgValue == null || msgValue.contains("No Data")) {
        if (logEnable) log.debug "In sendHistory - Nothing to report (No Data)"
    } else {
        try {
            if (state.list1 == null) state.list1 = []

            def date = new Date()
            String newDate = date.format("E HH:mm")

            String last = "${newDate} - ${msgValue}"
            state.list1.add(0, last)

            int listSize1 = state.list1.size()

            if (listSize1 > MAX_HISTORY_LOG_LINES) state.list1.removeAt(MAX_HISTORY_LOG_LINES)
            def lines1 = state.list1

            String theData1 = "<div style='overflow:auto;height:90%'><table style='text-align:left;font-size:${avatarFontSize}px'><tr><td>"

            for (int i = 0; i < MAX_HISTORY_LOG_LINES && i < listSize1; i++) {
                int combined = theData1.length() + lines1[i].length()
                if (combined < HUBITAT_TILE_MAX_CHARS - HISTORY_TILE_FOOTER_LEN) {
                    theData1 += "${lines1[i]}<br>"
                }
            }

            theData1 += "</table></div>"
            if (logEnable) log.debug "theData1 - ${theData1.replace("<", "!")}"

            int dataCharCount1 = theData1.length()
            if (dataCharCount1 <= HUBITAT_TILE_MAX_CHARS) {
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
    if (logEnable) log.debug "historyClearData"
    // clear location history
    state.locationHistory = []
    state.list1 = []
    if (logEnable) log.debug "Clearing the data"
    String historyLog = "Waiting for Data..."
    sendEvent(name: "bpt-history", value: historyLog, displayed: true)
    sendEvent(name: "numOfCharacters", value: 0, displayed: true)
    sendEvent(name: "lastLogMessage", value: "-", displayed: true)
}

/**
 * for compatibility with Life360 Tracker app (https://community.hubitat.com/t/release-life360-tracker-works-with-the-life360-app/18276)
 */
def sendTheMap(theMap) {
    sendEvent(name: "lastMap", value: theMap.toString(), displayed: true)
}

/**
 * Minimal HTML entity escaper for Hubitat's sandboxed Groovy.
 * Uses String.replace() chains — no regex — to stay sandbox-safe.
 * '&' must be replaced first to prevent double-escaping.
 */
static String escapeHtml(String s) {
    if (s == null) return ""
    return s.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace("\"", "&quot;")
             .replace("'", "&#39;")
}

/**
 * null-safe toDouble()
 */
static double toDouble(Object object) {
    if (object != null) return object.toDouble()
    else return 0
}

/**
 * null-safe toBool() — handles Life360 "1"/"0" strings and native JSON booleans
 */
static boolean toBool(Object object) {
    if (object == null) return false
    if (object instanceof Boolean) return object
    return object == "1"
}

/**
 * If the parent app has "Include Names and Places in Logs" enabled (default),
 * return the supplied firstName; otherwise return the memberId parsed from
 * device.deviceNetworkId = "${app.id}.${memberId}".
 * showNames must be pre-resolved by the caller (hoisted from the hot path).
 */
private String displayMember(String firstName, boolean showNames) {
    if (showNames) return firstName
    String dni = device.deviceNetworkId ?: ""
    int dot = dni.indexOf('.')
    return (dot > 0 && dot < dni.length() - 1) ? dni.substring(dot + 1) : (firstName ?: "?")
}
