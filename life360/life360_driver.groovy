/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** LIFE360+ Hubitat Driver **
 *
 * - see community discussion here: https://community.hubitat.com/t/release-life360/118544
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *  ****************  Location Tracker User Driver  ****************
 *
 *  Design Usage:
 *  This driver stores the user data to be used with Location Tracker.
 *
 *  Copyright 2020-2022 Bryan Turcotte (@bptworld)
 *
 *  This App is free.  If you like and use this app, please be sure to mention it on the Hubitat forums!  Thanks.
 *
 *  Remember...I am not a professional programmer, everything I do takes a lot of time and research (then MORE research)!
 *  Donations are never necessary but always appreciated.  Donations to support development efforts are accepted via:
 *
 *  Paypal at: https://paypal.me/bptworld
 *
 *  Unless noted in the code, ALL code contained within this app is mine. You are free to change, ripout, copy, modify or
 *  otherwise use the code in anyway you want. This is a hobby, I'm more than happy to share what I have learned and help
 *  the community grow. Have FUN with it!
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  If modifying this project, please keep the above header intact and add your comments/credits below - Thank you! -  @BPTWorld
 *
 *  App and Driver updates can be found at https://github.com/bptworld/Hubitat
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  Special thanks to namespace: "tmleafs", author: "tmleafs" for his work on the Life360 ST driver
 *
 *  Changes:
 *  3.0.17 - 08/06/23 - merge PR by imnotbob - https://github.com/jpage4500/hubitat-drivers/pull/16
 *  3.0.14 - 05/31/23 - add setting to auto-adjust refresh rate when users are moving
 *  3.0.13 - 05/30/23 - minor changes
 *  3.0.12 - 05/28/23 - change isDriving, isTransit, wifiState from string to boolean (enum)
 *  3.0.8 - 05/18/23 - more changes/cleanup from @Scottma61
 *  3.0.5 - 05/15/23 - several changes including PR from @Scottma61
 *  3.0.3 - 05/11/23 - fix status attribute
 *  3.0.2 - 05/11/23 - set presence attribute
 *  3.0.0 - 05/05/23 - refactor driver
 *                       - Only notify on location or battery change
 *                       - add accuracy - useful to know how accurate a given location is
 *                       - don't set fields for both miles and km - just the one the user selects
 *                       - last updated time
 *                       - add phone and email fields
 *  1.6.1 - 03/22/22 - Adustment to stop and error when someone pauses themselves in the Life360 phone app. Thanks @jpage4500!
 *  1.6.0 - 01/07/21 - Interim release
 *  1.5.5 - 12/20/20 - Reliability Improvements + Cleaned up Logging
 *  1.5.2 - 12/17/20 - Added initialization code for additional attributes / preferences
                     - Fixed Switch capability errors
 *  1.5.1 - 12/17/20 - Adjustments to work with Life360 Tracker
 *  1.5.0 - 12/06/20 - Moved all location functionality to child driver from parent app -and-
                       Added:
                         - Minimum Transit Speed Preference - use to set a custom speed threshold
                           for inTransit to become true (follows Km or Miles unit preference)
                         - Minimum Driving Speed Prederence - use to set a custom speed threshold
                           for isDriving to become true (follows Km or Miles unit preference)
                         - memberName attribute - First Name + Last Name from Life360 member info
                         - memberFriendlyName driver preference and attribute
 *  1.3.0 - 12/04/20 - Fixed condition to trigger presence & address changes
 *  ---
 *  1.0.0 - 01/18/20 - Initial release
 */

//file:noinspection SpellCheckingInspection


import groovy.json.JsonBuilder
import groovy.time.TimeCategory
import groovy.transform.CompileStatic
import groovy.transform.Field

import java.text.SimpleDateFormat

@Field static final String appVersion = '3.0.18'  // public version

metadata {
  definition (name: "Life360+ Driver", namespace: "jpage4500", author: "Joe Page", importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/life360/life360_driver.groovy") {
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
        attribute "inTransit", "enum", ["true","false"]
        attribute "isDriving", "enum", ["true","false"]
        attribute "speed", "number"
        attribute "distance", "number"
        attribute "since", "number"

        // device data
        attribute "battery", "number"
        attribute "charge", "enum", ["true","false"]
        attribute "status", "string"
        attribute "wifiState", "enum", ["true","false"]
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
        command "refreshCirclePush"
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

def off() {
  // empty stub needed for switch capability not to throw errors
}

def on() {
  // empty stub needed for switch capability not to throw errors
}

def refresh() {
    state.force = true
    parent.refresh()
}

def refreshCirclePush() {
    // Manually ensure that Life360 notifications subscription is current / valid
    logInfo "Attempting to resubscribe to circle notifications"
    parent.createCircleSubscription()
}

def installed() {
    logInfo "Location Tracker User Driver Installed"

    if (getSettingB('logEnable')) logInfo "Setting attributes to initial values"

    String address1prev = "No Data"
    sendEvent ( name: 'address1prev', value: address1prev )
    state.force = true
}

def updated() {
    logInfo "Location Tracker User Driver has been Updated"
    refresh()
    if (getSettingB('logEnable')) {
        runIn(1800L, 'logsOff')
    }
}

void logsOff() {
    if (getSettingB('logEnable')) {
        // Log this information regardless of user setting.
        logInfo 'debug logging disabled...'
        device.updateSetting ('logEnable', [value: 'false', type: 'bool'])
    }
}

private Boolean changedLatLongitude(Double v, Double v1){
    Double rounded = Math.round(v*1000.0D)/1000.0D
    Double rounded1 = Math.round(v1*1000.0D)/1000.0D
    Double diff = (rounded - rounded1) * 10000.0D
    if (getSettingB('logEnable')) logInfo "diff: $diff diffabs: ${Math.abs(diff)}"
    return Math.abs(diff) > 10.0D
}

Boolean generatePresenceEvent(Map member, Map thePlaces, Map home) {
    if (member.location == null) {
        // logInfo "no location set for $member"
        return false
    }

    Map mloc = (Map)member.location
    // NOTE: only interested in sending updates device when location or battery changes
    // -- location --
    Double latitude = mloc.latitude.toDouble()
    Double longitude = mloc.longitude.toDouble()
    Integer accuracy = mloc.accuracy.toDouble().round(0).toInteger()
    Integer battery = mloc.battery.toDouble().round(0).toInteger()
    Boolean wifiState = mloc.wifiState == "1"
    Boolean charge = mloc.charge == "1"

    Double speed; speed = mloc.speed.toDouble()
    // check for iPhone reporting speed of -1
    if (speed == -1) speed = 0.0D

    Boolean isDriving; isDriving = mloc.isDriving == "1"
    Boolean inTransit; inTransit = mloc.inTransit == "1"
    Long since = mloc.since.toLong()
    // -- name --
    String memberFirstName = (member.firstName) ? member.firstName : sBLK
    String memberLastName = (member.lastName) ? member.lastName : sBLK
    String address1; address1 = (mloc.name) ? mloc.name : mloc.address1
//    String address2; address2 = (mloc.address2) ? mloc.address2 : mloc.shortaddress
    // -- home --
    Double homeLatitude = home.latitude.toDouble()
    Double homeLongitude = home.longitude.toDouble()
    Double homeRadius = home.radius.toDouble()

    // detect if location (lat/lng/accuracy) changed from previous update
    // -- previous values (could be null) --
    Double prevLatitude = device.currentValue('latitude')
    Double prevLongitude = device.currentValue('longitude')
    Integer prevAccuracy = device.currentValue('accuracy')

    Boolean isLocationChanged
    isLocationChanged = ( (Boolean)state.force ||
        (prevLatitude == null || changedLatLongitude(prevLatitude, latitude) )
        || (prevLongitude == null || changedLatLongitude(prevLongitude, longitude))
        || (prevAccuracy == null || Math.abs(prevAccuracy - accuracy) > 100))
    if(!isLocationChanged){
        // NOTE: uncomment to see 'no change' updates every <n> seconds
        //if (getSettingB('logEnable')) logTrace "No change: $latitude/$longitude, acc:$accuracy"
    } else {
        if (getSettingB('logEnable')) logInfo "<strong>change</strong>: $latitude/$longitude, acc:$accuracy"
    }

    state.remove('force')

    // skip update if location, accuracy and battery have not changed
    Integer prevBattery = device.currentValue('battery')
    String prevWifiState = device.currentValue('wifiState')
    Boolean isBatteryWifiChanged =  (
        (prevBattery == null || prevBattery != battery)
        || (prevWifiState == null || prevWifiState.toBoolean() != wifiState))
    if(!isBatteryWifiChanged){
        // NOTE: uncomment to see 'no change' updates every <n> seconds
        //if (getSettingB('logEnable')) logTrace "No change: b:$battery%, wifi:$wifiState, speed:$speed"
    } else {
        if (getSettingB('logEnable')) logInfo "<strong>change</strong>: b:$battery%, wifi:$wifiState"
    }

    Date lastUpdated = new Date()

    // location changed, or Accuracy or Battery changed -- fetch any other useful values
    if(isLocationChanged || isBatteryWifiChanged){

        // *** Member Name ***
        String memberFullName = memberFirstName + " " + memberLastName
        sendEvent( name: "memberName", value: memberFullName )

        // *** Places List ***
        // format as JSON string for better parsing
        String savedPlacesJson = new JsonBuilder(thePlaces)
        sendEvent( name: "savedPlaces", value: savedPlacesJson.toString() )

        // *** Avatar ***
        String avatar
        String avatarHtml
        if (member.avatar != null){
            avatar = member.avatar
            sendEvent( name: "avatar", value: avatar )
            avatarHtml =  "<img src= \"${avatar}\">"
        } else {
            avatar = "not set"
            avatarHtml = "not set"
            device.deleteCurrentState("avatar")
        }

        // *** Location ***
        Double distanceAway = haversine(latitude, longitude, homeLatitude, homeLongitude) * 1000.0D // in meters
        // It is safe to assume that if we are within home radius then we are
        // both present and at home (to address any potential radius jitter)
        String memberPresence = (distanceAway <= homeRadius) ? "present" : "not present"

        // Where we think we are now is either at a named place or at address1
        // or perhaps we are on the free version of Life360 (address1  = null)
        if (address1 == null || address1 == sBLK) address1 = "No Data"
//      if (address2 == null || address2 == sBLK) address2 = "No Data"

        // *** Address ***
        // If we are present then we are Home...
        address1 = (memberPresence == "present") ? "Home" : address1

        String prevAddress = device.currentValue('address1')
        if (address1 != prevAddress) {
            if (getSettingB('logEnable')) logInfo "address1:$address1, prevAddress = $prevAddress"
            // Update old and current address information and trigger events
            sendEvent( name: "address1prev", value: prevAddress)
            sendEvent( name: "address1", value: address1 )
            //sendEvent( name: "lastLocationUpdate", value: lastUpdated )
            sendEvent( name: "since", value: since )
        }

        // *** Presence ***
        String descriptionText = device.displayName + " has " + ( memberPresence == "present" ? "arrived" : "left" )
        sendEvent (name: "presence", value: memberPresence, descriptionText: descriptionText)
        state.presence = memberPresence

        // *** Coordinates ***
        sendEvent( name: "longitude", value: longitude )
        sendEvent( name: "latitude", value: latitude )
        sendEvent( name: "accuracy", value: accuracy )

        Boolean isMiles = getSettingB('isMiles')

        // Update status attribute with appropriate distance units
        // and update appropriate speed units
        // as chosen by users in device preferences
        Double speedUnits       // in user's preference of MPH or KPH
        Double distanceUnits    // in user's preference of miles or km
        speedUnits = (speed * (isMiles ? 2.23694D : 3.6D)).round(2)
        distanceUnits = ((distanceAway / 1000.0D) / ((isMiles ? 1.609344D : 1.0D))).round(2)

        Double transitThreshold = ((BigDecimal)settings.transitThreshold).toDouble()
        Double drivingThreshold = ((BigDecimal)settings.drivingThreshold).toDouble()
        // if transit threshold specified in preferences then use it; else, use info provided by Life360
        if (transitThreshold > 0.0D) { inTransit = (speedUnits >= transitThreshold) }
        // if driving threshold specified in preferences then use it; else, use info provided by Life360
        if (drivingThreshold > 0.0D) { isDriving = (speedUnits >= drivingThreshold) }

        String sStatus = (memberPresence == "present") ? "At Home" : sprintf("%.1f", distanceUnits) + ((isMiles) ? " miles from Home" : "km from Home")

        if (getSettingB('logEnable') && (isDriving || inTransit)) {
            // *** On the move ***
            logDebug "$sStatus, speedUnits:$speedUnits, transitThreshold: $transitThreshold, inTransit: $inTransit, drivingThreshold: $drivingThreshold, isDriving: $isDriving"
        }

        sendEvent( name: "status", value: sStatus )
        state.status = sStatus

        sendEvent( name: "inTransit", value: inTransit )
        sendEvent( name: "isDriving", value: isDriving )
        sendEvent( name: "speed", value: speedUnits, unit: isMiles ? "mph" : "km/h")
        sendEvent( name: "distance", value: distanceUnits, unit: isMiles ? "mi" : "km")

        // Set acceleration to active state if we are either moving or if we are anywhere outside home radius
        sendEvent( name: "acceleration", value: (inTransit || isDriving || memberPresence == "not present" ) ? "active" : "inactive" )

        // *** Battery Level ***
        sendEvent( name: "battery", value: battery, unit: "%" )

        // *** Charging State ***
        String powerSource = charge ? "dc" : "battery" // match hubitat powersource enum
        sendEvent( name: "charge", value: charge )
        sendEvent( name: "powerSource", value: powerSource)
        sendEvent( name: "contact", value: charge ? "open" : "closed" )

        // *** Wifi ***
        sendEvent( name: "wifiState", value: wifiState )
        sendEvent( name: "switch", value: wifiState ? "on" : "off" )

        // ** Member Features **
        if (member.features != null) {
            sendEvent ( name: "shareLocation", value: member.features.shareLocation )
        }

        // ** Member Communications **
        if (member.communications != null) {
            ((List<Map>)member.communications).each { comm ->
                String commType = comm.get('channel')
                if (commType != null && commType == "Voice") {
                    sendEvent ( name: "phone", value: comm.value )
                } else if (commType != null && commType == "Email") {
                    sendEvent ( name: "email", value: comm.value )
                }
            }
        }

        // ** HTML attributes (optional) **
        if (getSetB('generateHtml')) {
            // send HTML avatar if generateHTML is enabled
            sendEvent( name: "avatarHtml", value: avatarHtml)

            String transitDesc
            if (isDriving) transitDesc = 'Driving'
            else if (inTransit) transitDesc = 'Moving'
            else transitDesc = 'Not Moving'

            Integer sEpoch = device.currentValue('since')
            Date theDate
            if(sEpoch == null) {
                theDate = use( TimeCategory ) {
                    new Date(0)
                }
            } else {
                theDate = use( TimeCategory ) {
                    new Date( 0 ) + sEpoch.seconds
                }
            }
            SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("E hh:mm a")
            String dateSince = DATE_FORMAT.format(theDate)

            String theMap = "https://www.google.com/maps/search/?api=1&query=" + latitude.toString() + "," + longitude.toString()

            String tileMap
            tileMap = "<div style='overflow:auto;height:90%'><table width='100%'>"
            tileMap += "<tr><td width='25%' align=center><img src='${avatar}' height='${avatarSize}%'>"
            tileMap += "<td width='75%'><p style='font-size:${avatarFontSize}px'>"
            tileMap += "At: <a href='${theMap}' target='_blank'>${address1 == "No Data" ? "Between Places" :address1}</a><br>"
            tileMap += "Since: ${dateSince}<br>"
            tileMap += (sStatus == "At Home") ? sBLK : sStatus+"<br>"
            tileMap += transitDesc
            if(address1 == "No Data" ? "Between Places" : address1 != "Home" && inTransit) {
                tileMap += " @ ${sprintf("%.1f", speed)} "
                tileMap += isMiles ? "MPH":"KPH"
            }
            tileMap += "<br>Phone Lvl: ${battery} - ${powerSource} - "
            tileMap += wifiState ? "WiFi" : "No WiFi"
            tileMap += "<br><p style='width:100%'>${lastUpdated}</p>"
            tileMap += "</table></div>"

            int tileDevice1Count = tileMap.length()
            if (tileDevice1Count > 1024) logWarn "Too many characters to display on Dashboard tile (${tileDevice1Count})"
            sendEvent(name: "html", value: tileMap, displayed: true)

        } else {
            // clear out existing html values
            device.deleteCurrentState("avatarHtml")
            device.deleteCurrentState("html")
        }
    }

    // *** Timestamp ***
    sendEvent ( name: "lastUpdated", value: lastUpdated )
    state.update = true

    return isLocationChanged
}

/**
 *  compute distance apart in KM
 */
static Double haversine(Double ilat1, Double lon1, Double ilat2, Double lon2) {
    Double R = 6372.8D
    // In kilometers
    Double dLat = Math.toRadians(ilat2 - ilat1)
    Double dLon = Math.toRadians(lon2 - lon1)
    Double lat1 = Math.toRadians(ilat1)
    Double lat2 = Math.toRadians(ilat2)

    Double d2 = 2.0D
    Double a = Math.sin(dLat / d2) * Math.sin(dLat / d2) + Math.sin(dLon / d2) * Math.sin(dLon / d2) * Math.cos(lat1) * Math.cos(lat2)
    Double c = d2 * Math.asin(Math.sqrt(a))
    Double d = R * c
    return(d)
}



private Boolean getSettingB(String nm) { return (Boolean) settings[nm] }


/*------------------ Logging helpers ------------------*/

@Field static final String PURPLE = 'purple'
@Field static final String BLUE = '#0299b1'
@Field static final String GRAY = 'gray'
@Field static final String ORANGE = 'orange'
@Field static final String RED = 'red'

@Field static final String sLTH = '<'
@Field static final String sGTH = '>'

@Field static final String sNL = (String)null
@Field static final String sBLK = ''

@CompileStatic
private static String logPrefix(String msg, String color = null) {
    String myMsg = msg.replaceAll(sLTH, '&lt;').replaceAll(sGTH, '&gt;')
    StringBuilder sb = new StringBuilder('<span ')
            .append("style='color:").append(GRAY).append(";'>")
            .append('[Life360+ driver v').append(appVersion).append('] ')
            .append('</span>')
            .append("<span style='color:").append(color).append(";'>")
            .append(myMsg)
            .append('</span>')
    return sb.toString()
}

private void logTrace(String msg) {
    log.trace logPrefix(msg, GRAY)
}

void debug (String msg) { logDebug (msg) }
private void logDebug(String msg) {
    log.debug logPrefix(msg, PURPLE)
}

private void logInfo(String msg) {
    log.info logPrefix(msg, BLUE)
}

private void logWarn(String msg) {
    log.warn logPrefix(msg, ORANGE)
}

private void logError(String msg, Exception ex = null) {
    log.error logPrefix(msg, RED)
    String a,b; a = sNL; b = sNL
    try {
        if (ex) {
            a = getExceptionMessageWithLine(ex)
            if (devdbg()) b = getStackTrace(ex)
        }
    } catch (ignored) {}
    if (a || b) {
        log.error logPrefix(a+' \n'+b, RED)
    }
}
