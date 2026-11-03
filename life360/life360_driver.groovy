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
@Field static final Map LAST_SENT = new java.util.concurrent.ConcurrentHashMap()  // device.id -> [attr: last value sent]; memory-only, so first poll after reload resends everything
@Field static final double EARTH_RADIUS_KM         = 6372.8   // mean Earth radius in km (used in haversine)
@Field static final long   STATIONARY_MIN_SECS     = 120      // a stop shorter than this (a red light) never ends "in transit" polling
@Field static final double STATIONARY_MIN_RADIUS_M = 25       // "same spot" is never tighter than this, whatever accuracy the phone claims (GPS wander)
@Field static final double STATIONARY_MAX_RADIUS_M = 150      // ...and never looser than this, so a coarse cell fix cannot make a moving car look parked
@Field static final Map    STILL_ANCHOR            = new java.util.concurrent.ConcurrentHashMap()  // device.id -> [lat, lng, epoch ms] where a flagged member was first seen; memory-only

metadata {
    definition(
        name: "Life360+ Driver", 
        namespace: "jpage4500", 
        author: "Joe Page", 
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/life360/life360_driver.groovy"
    )
    {
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
        attribute "isMoving", "enum", ["true", "false"]   // inTransit AND the phone has actually changed position recently; goes false long before Life360 clears inTransit
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
    input "unitOverride", "enum", title: "Units (this device only)",
        description: "Per-device fallback. The app-level 'Units' setting overrides this when set to anything other than 'Follow Life360 app'.",
        options: ["life360": "Follow Life360 app (recommended)", "hubitat": "Follow Hubitat system (°F → miles, °C → km)", "imperial": "Miles / mph", "metric": "Kilometers / kph"],
        required: true, defaultValue: "life360"
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
    LAST_SENT[device.id as String]?.remove("presence")  // manual override: let the next poll resend
    sendEvent(name: "presence", value: "present")
}

def departed() {
    LAST_SENT[device.id as String]?.remove("presence")
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

def uninstalled() {
    LAST_SENT.remove(device.id as String)
    STILL_ANCHOR.remove(device.id as String)
}

def updated() {
    log.info "updated: Location Tracker User Driver has been Updated"
    LAST_SENT.remove(device.id as String)  // unit/threshold prefs may change derived values
    // one-time migration: isMiles bool → unitOverride enum (isMiles removed in 5.2.0)
    if (settings.unitOverride == null || settings.unitOverride == "life360") {
        def oldIsMiles = settings.isMiles
        if (oldIsMiles == false) {
            device.updateSetting("unitOverride", [value: "metric", type: "enum"])
            log.info "updated: migrated isMiles=false → unitOverride=metric"
        }
    }
    refresh()
}

// called from Life360+ app
// @param member - Life360 member object (user details)
// @param thePlaces - unused; the app passes null. Kept only so the app<->driver signature stays stable.
// @param home - Life360 circle which user selected as 'home'
// @param ctx - optional poll-cycle context map (showNames, showMapsLink, useMiles) hoisted by the app once per tick
//
// @return true if the app should treat the member as in transit for polling (Life360 inTransit flag, unless the member has not moved for too long)
boolean generatePresenceEvent(member, thePlaces, home, Map ctx = null) {
    if (member == null) return false
    // Capture hub temperatureScale before `location` is shadowed by member.location below
    String hubTempScale = location.temperatureScale
    def location = member.location
    if (location == null) return false

    // prefer values hoisted by the app (one IPC call per tick); fall back to per-call IPC for
    // backward compat (e.g. direct driver calls from tools or older app versions)
    boolean showNames = (ctx?.showNames != null) ? ctx.showNames : { try { parent?.getShowNamesInLogs() != false } catch (ignored) { true } }()
    boolean showMapsLink = (ctx?.showMapsLink != null) ? ctx.showMapsLink : { try { parent?.getShowMapsLink() != false } catch (ignored) { true } }()
    boolean logRawPayload = (ctx?.logRawPayload != null) ? ctx.logRawPayload : { try { parent?.getLogRawPayload() == true } catch (ignored) { false } }()
    boolean logEachPoll = (ctx?.logDrivingTransitions != null) ? ctx.logDrivingTransitions : { try { parent?.getLogDrivingTransitions() != false } catch (ignored) { true } }()

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
    if (!userActivity) userActivity = "unknown"   // Life360's own "no activity" value; always a real string, never a null event

    // -- name --
    String memberFirstName = (member.firstName) ? member.firstName : ""
    String memberLastName = (member.lastName) ? member.lastName : ""
    // -- home -- (null if the selected place was removed from Life360; treat as absent)
    if (home == null) {
        log.error("generatePresenceEvent: home place not found — presence update skipped; re-open Life360+ and verify the selected HOME place still exists in Life360")
        return false
    }
    Double homeLatitude = toDouble(home.latitude)
    Double homeLongitude = toDouble(home.longitude)
    Double homeRadius = toDouble(home.radius)

    String address1 = (location.name) ? location.name : location.address1

    // -- previous values (used for address history and location history) --
    Double prevLatitude = device.currentValue('latitude')
    Double prevLongitude = device.currentValue('longitude')
    Boolean prevInTransit = device.currentValue('inTransit') == 'true'

    if (logRawPayload) log.trace("generatePresenceEvent: location payload: ${location}")

    // -----------------------------------------------
    // ** location/accuracy/battery/battery changed **
    // -----------------------------------------------
    if (logEnable) log.debug "generatePresenceEvent: ${displayMember(memberFirstName, showNames)}: ${showNames ? "lat:$latitude lng:$longitude" : "location hidden (names off)"} acc:${accuracy}m b:${battery}% wifi:$wifiState speed:${speed.round(2)}m/s inTransit:$inTransit isDriving:$isDriving"
    Date lastUpdated = new Date()

    // *** Member Name ***
    String memberFullName = memberFirstName + " " + memberLastName
    if (memberFullName != device.currentValue("memberName")) sendEvent(name: "memberName", value: memberFullName)

    // *** Avatar ***
    String avatar = member.avatar ?: ""
    String avatarHtml = (avatar.startsWith("http")) ? "<img src=\"${avatar}\">" : ""
    if (avatar != device.currentValue("avatar")) sendEvent(name: "avatar", value: avatar)

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
        if (logEnable) log.debug "generatePresenceEvent: ${displayMember(memberFirstName, showNames)}: address1:${showNames ? address1 : "(hidden)"}, prevAddress = ${showNames ? prevAddress : "(hidden)"}"
        // Update old and current address information and trigger events
        sendEvent(name: "address1prev", value: prevAddress)
        sendEvent(name: "address1", value: address1)
        sendEvent(name: "lastLocationUpdate", value: lastUpdated)
        sendEvent(name: "since", value: since)
    }

    // *** Presence ***
    String descriptionText = device.displayName + " has " + ((memberPresence == "present") ? "arrived" : "left")
    sendChanged("presence", memberPresence, descriptionText)

    // *** Coordinates ***
    sendChanged("longitude", longitude)
    sendChanged("latitude", latitude)
    sendChanged("accuracy", accuracy)

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
    if (speed < 0) speed = 0.0   // any negative speed means "no reading" (Apple reports -1), never a real speed
    // Hard overrides (imperial/metric/hubitat) ignore the Life360 API entirely.
    // "life360" (default): API setting wins, fall back to Hubitat system if not yet known.
    Boolean useMiles
    if (unitOverride == "imperial") {
        useMiles = true
    } else if (unitOverride == "metric") {
        useMiles = false
    } else if (unitOverride == "hubitat") {
        useMiles = (hubTempScale != "C")
    } else {
        // "life360" — prefer API, fall back to Hubitat system setting
        Boolean ctxMiles = ctx?.useMiles
        if (ctxMiles != null) {
            useMiles = ctxMiles
        } else {
            try { Boolean apiMiles = parent?.getUnitIsMiles(); if (apiMiles != null) useMiles = apiMiles } catch (ignored) {}
        }
        if (useMiles == null) useMiles = (hubTempScale != "C")
    }
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
    // Life360's inTransit/isDriving are reported exactly as received. But Life360 can keep them (and even a stale speed)
    // set after the phone has stopped, and the value we return to the app drives the fast poll rate. That is OUR polling
    // decision, not Life360's data: if a member Life360 calls in transit has not left the spot where we first saw them
    // (beyond the phone's own reported accuracy) for longer than two normal refresh cycles, tell the app to stop fast
    // polling. Position is used rather than speed because the stale value is often the speed itself. Skipped when the
    // user set a speed threshold.
    boolean stillTooLong = false
    if (transitThresholdD <= 0.0) {   // a Minimum Transit Speed already decides inTransit from speed
        String stillKey = device.id as String
        long nowMs = new Date().getTime()
        if (!inTransit) {   // the app polls fast only for inTransit, so only that flag matters here
            STILL_ANCHOR.remove(stillKey)
        } else {
            List anchor = STILL_ANCHOR[stillKey]
            double slackMeters = Math.min(Math.max(accuracy as double, STATIONARY_MIN_RADIUS_M), STATIONARY_MAX_RADIUS_M)
            long stillMs = Math.max(((ctx?.pollFreqSecs ?: 0) as long) * 2000L, STATIONARY_MIN_SECS * 1000L)
            // memory was lost (hub reboot / code save) but we had already decided "stopped" at this spot: keep it
            if (anchor == null && device.currentValue('isMoving') == 'false' && prevLatitude != null && prevLongitude != null
                    && haversine(prevLatitude, prevLongitude, latitude, longitude) * 1000.0 <= slackMeters) {
                anchor = [latitude, longitude, nowMs - stillMs]
                STILL_ANCHOR[stillKey] = anchor
            }
            if (anchor == null || haversine(anchor[0], anchor[1], latitude, longitude) * 1000.0 > slackMeters) {
                STILL_ANCHOR[stillKey] = [latitude, longitude, nowMs]   // moved (or first sighting): restart the clock here
            } else {
                if (nowMs - (anchor[2] as long) >= stillMs) {
                    if (logEnable) log.debug "generatePresenceEvent: ${displayMember(memberFirstName, showNames)}: no movement for ${(long)((nowMs - (anchor[2] as long)) / 1000L)}s (within ${slackMeters.round(0)}m) with Life360 inTransit:true; not fast polling"
                    stillTooLong = true
                }
            }
        }
    }

    String suffix = showMapsLink ? " — <a href='https://www.google.com/maps/search/?api=1&query=${latitude},${longitude}' target='_blank'>Google Maps link</a>" : ""
    boolean moving = inTransit || isDriving
    // isMoving = Life360 says in transit AND the position has not been still for too long (the same value the app uses
    // to decide on fast polling). The first poll after an upgrade has no stored isMoving, so fall back to inTransit.
    boolean isMovingNow = inTransit && !stillTooLong
    boolean prevIsMoving = (device.currentValue('isMoving') != null) ? (device.currentValue('isMoving') == 'true') : prevInTransit
    if (logEachPoll) {
        // default on: log every poll when moving — but not while stationary (Life360 keeps
        // inTransit/isDriving set at a light, in traffic, or for a while after arriving)
        if (moving && !stillTooLong && speedUnits >= 1.0) {
            log.info("${displayMember(memberFirstName, showNames)}: moving @ ${speedUnits} ${useMiles ? 'mph' : 'kph'}${suffix}")
        }
    } else {
        // quiet mode: only log start/stop of movement (isMoving: in transit and actually changing position)
        if (isMovingNow && !prevIsMoving) {
            log.info("${displayMember(memberFirstName, showNames)}: started moving${suffix}")
        } else if (!isMovingNow && prevIsMoving) {
            log.info("${displayMember(memberFirstName, showNames)}: stopped moving${suffix}")
        }
    }
    // extra debug detail only when debug logging is on
    if (thresholdActive && logEnable) log.debug("MOTION ${displayMember(memberFirstName, showNames)}: " +
        "speedUnits:${speedUnits} inTransit:${inTransit} isDriving:${isDriving} (threshold override)")

    String sStatus = (memberPresence == "present") ? "At Home" : sprintf("%.1f", distanceUnits) + ((useMiles) ? " miles from Home" : " km from Home")

    if (logEnable && (isDriving || inTransit)) {
        log.debug "generatePresenceEvent: ${displayMember(memberFirstName, showNames)}: $sStatus, speedUnits:$speedUnits, transitThreshold:$transitThreshold, inTransit:$inTransit, drivingThreshold:$drivingThreshold, isDriving:$isDriving"
    }

    sendChanged("status", sStatus)

    sendChanged("inTransit", inTransit.toString())
    sendChanged("isDriving", isDriving.toString())
    sendChanged("isMoving", isMovingNow.toString())
    sendChanged("speed", speedUnits)
    sendChanged("distance", distanceUnits)
    sendChanged("userActivity", userActivity)

    // Set acceleration to active state if we are either moving or if we are anywhere outside home radius
    // REPURPOSED CAPABILITY (§5.4): 'acceleration' active/inactive = in transit OR driving OR away from home (NOT physical acceleration)
    sendChanged("acceleration", (inTransit || isDriving || memberPresence == "not present") ? "active" : "inactive")

    // *** Battery Level ***
    sendChanged("battery", battery)

    // *** Charging State ***
    String powerSource = charge ? "dc" : "battery"   // canonical Power Source capability enum values
    String powerSourceDisplay = charge ? "DC" : "BTRY"  // abbreviated form used in tile display only
    sendChanged("charge", charge.toString())
    sendChanged("powerSource", powerSource)
    // REPURPOSED CAPABILITY (§5.4): 'contact' open/closed = charging / on battery (NOT a contact sensor)
    sendChanged("contact", charge ? "open" : "closed")

    // *** Wifi ***
    sendChanged("wifiState", wifiState.toString())
    // REPURPOSED CAPABILITY (§5.4): 'switch' on/off = WiFi connected / not (NOT a controllable switch)
    sendChanged("switch", wifiState ? "on" : "off")

    // ** Member Features **
    if (member.features != null) {
        sendChanged("shareLocation", toBool(member.features.shareLocation).toString())
    }

    // ** Member Communications **
    if (member.communications != null) {
        // the last entry per channel wins, as the old loop did (it overwrote the field with each entry in turn), but
        // decided once so several numbers/addresses can't overwrite each other and re-send on every poll
        def voices = member.communications.findAll { it.get('channel') == "Voice" }
        def emails = member.communications.findAll { it.get('channel') == "Email" }
        def voice = voices ? voices[-1] : null
        def email = emails ? emails[-1] : null
        if (voice != null) sendChanged("phone", voice.value?.toString())
        if (email != null) sendChanged("email", email.value?.toString())
    }

    // *** Timestamp ***
    sendEvent(name: "lastUpdated", value: lastUpdated)

    // ** HTML attributes (optional) **
    if (!generateHtml) {
        return isMovingNow
    }

    if (avatarHtml != device.currentValue("avatarHtml")) sendEvent(name: "avatarHtml", value: avatarHtml)

    String motionLabel
    if (isDriving && isMovingNow) motionLabel = "Driving"
    else if (isMovingNow) motionLabel = "Moving"
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
    if (address1 != "Home" && isMovingNow) {
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
    return isMovingNow
}

/**
 * sendEvent only when the value differs from the last one sent for this device.
 */
private void sendChanged(String name, value, String descriptionText = null) {
    Map last = LAST_SENT[device.id as String]
    if (last == null) { last = new java.util.concurrent.ConcurrentHashMap(); LAST_SENT[device.id as String] = last }
    if (value == null) return   // nothing to report (callers pass real values; phone/email may be absent)
    if (last[name] == value) return
    if (descriptionText) sendEvent(name: name, value: value, descriptionText: descriptionText)
    else sendEvent(name: name, value: value)
    last[name] = value   // remembered only once the send went through
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
