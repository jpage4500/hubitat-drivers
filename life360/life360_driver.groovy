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

import java.text.SimpleDateFormat

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
        attribute "charge", "string"
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
  parent.refresh()
}

def refreshCirclePush() {
    // Manually ensure that Life360 notifications subscription is current / valid
    log.info "Life360+ Driver: Attempting to resubscribe to circle notifications"
    parent.createCircleSubscription()
}

def installed() {
    log.info "Life360+ Driver: Location Tracker User Driver Installed"

    if (logEnable) log.info "Life360+ Driver: Setting attributes to initial values"

    address1prev = "No Data"
    sendEvent ( name: address1prev, value: address1prev )
}

def updated() {
    log.info "Life360+ Driver: Location Tracker User Driver has been Updated"
    refresh()
}

def generatePresenceEvent(member, thePlaces, home) {
    if (member.location == null) {
        // log.info "Life360+ Driver: no location set for $member"
        return
    }

    // NOTE: only interested in sending updates device when location or battery changes
    // -- location --
    def Double latitude = member.location.latitude.toDouble()
    def Double longitude = member.location.longitude.toDouble()
    def Integer accuracy = member.location.accuracy.toDouble().round(0).toInteger()
    def Integer battery = member.location.battery.toDouble().round(0).toInteger()
    def Boolean wifiState = member.location.wifiState == "1"
    def String charge = member.location.charge
    def Double speed = member.location.speed.toDouble()
    def Boolean isDriving = member.location.isDriving == "1"
    def Boolean inTransit = member.location.inTransit == "1"
    def Integer since = member.location.since.toLong()
    // -- name --
    def String memberFirstName = (member.firstName) ? member.firstName : ""
    def String memberLastName = (member.lastName) ? member.lastName : ""
    def String address1 = (member.location.name) ? member.location.name : member.location.address1
    def String address2 = (member.location.address2) ? member.location.address2 : member.location.shortaddress
    // -- home --
    def Double homeLatitude = home.latitude.toDouble()
    def Double homeLongitude = home.longitude.toDouble()
    def Double homeRadius = home.radius.toDouble()

    // -- previous values (could be null) --
    def Double prevLatitude = device.currentValue('latitude')
    def Double prevLongitude = device.currentValue('longitude')
    def Integer prevAccuracy = device.currentValue('accuracy')
    def Integer prevBattery = device.currentValue('battery')
    def String prevWifiState = device.currentValue('wifiState')

    // skip update if location, accuracy or battery have not changed
    if ((prevLatitude != null && prevLatitude == latitude)
        && (prevLongitude != null && prevLongitude == longitude)
        && (prevAccuracy != null && prevAccuracy == accuracy)
        && (prevBattery != null && prevBattery == battery)
        && (prevWifiState != null && prevWifiState.toBoolean() == wifiState)) {
        // NOTE: uncomment to see 'no change' updates every <30> seconds
        //if (logEnable) log.trace "Life360+ Driver: No change: $latitude/$longitude, acc:$accuracy, b:$battery%, wifi:$wifiState, speed:$speed"
        return
    } else {
        if (logEnable) log.info "Life360+ Driver: <strong>change</strong>: $latitude/$longitude, acc:$accuracy, b:$battery%, wifi:$wifiState, speed:${speed.round(2)}"
    }

    // location changed, or Accuracy or Battery changed -- fetch any other useful values
    def Date lastUpdated = new Date()

    // *** Member Name ***
    def String memberFullName = memberFirstName + " " + memberLastName
    sendEvent( name: "memberName", value: memberFullName )

    // *** Places List ***
    // format as JSON string for better parsing
    def savedPlacesJson = new groovy.json.JsonBuilder(thePlaces)
    sendEvent( name: "savedPlaces", value: savedPlacesJson.toString() )

    // *** Avatar ***
    def String avatar
    def String avatarHtml
    if (member.avatar != null){
        avatar = member.avatar
        avatarHtml =  "<img src= \"${avatar}\">"
    } else {
        avatar = "not set"
        avatarHtml = "not set"
    }
    sendEvent( name: "avatar", value: avatar )
    // send HTML avatar if generateHTML is enabled; otherwise clear it (only if previously set)
    if (generateHtml) sendEvent( name: "avatarHtml", value: generateHtml)
    else if (device.currentValue('avatarHtml') != null) sendEvent( name: "avatarHtml", value: null)

    // *** Location ***
    def Double distanceAway = haversine(latitude, longitude, homeLatitude, homeLongitude) * 1000 // in meters
    // It is safe to assume that if we are within home radius then we are
    // both present and at home (to address any potential radius jitter)
    def String memberPresence = (distanceAway <= homeRadius) ? "present" : "not present"

    // Where we think we are now is either at a named place or at address1
    // or perhaps we are on the free version of Life360 (address1  = null)
    if (address1 == null || address1 == "") address1 = "No Data"
    if (address2 == null || address2 == "") address2 = "No Data"

    // *** Address ***
    // If we are present then we are Home...
    address1 = (memberPresence == "present") ? "Home" : address1

    def String prevAddress = device.currentValue('address1')
    if (address1 != prevAddress) {
        if (logEnable) log.info "Life360+ Driver: address1:$address1, prevAddress = $prevAddress"
        // Update old and current address information and trigger events
        sendEvent( name: "address1prev", value: prevAddress)
        sendEvent( name: "address1", value: address1 )
        sendEvent( name: "lastLocationUpdate", value: lastUpdated )
        sendEvent( name: "since", value: since )
    }

    // *** Presence ***
    def String descriptionText = device.displayName + " has " + ( memberPresence == "present" ) ? "arrived" : "left"
    sendEvent (name: "presence", value: memberPresence, descriptionText: descriptionText)
    state.presence = memberPresence

    // *** Coordinates ***
    sendEvent( name: "longitude", value: longitude )
    sendEvent( name: "latitude", value: latitude )
    sendEvent( name: "accuracy", value: accuracy )

    // Update status attribute with appropriate distance units
    // and update appropriate speed units
    // as chosen by users in device preferences
    def Double speedUnits       // in user's preference of MPH or KPH
    def Double distanceUnits    // in user's preference of miles or km
    // check for iPhone reporting speed of -1
    if (speed == -1) speed = 0.0
    speedUnits = (speed * (isMiles ? 2.23694 : 3.6)).round(2)
    distanceUnits = ((distanceAway / 1000) / ((isMiles ? 1.609344 : 1))).round(2)

    // if transit threshold specified in preferences then use it; else, use info provided by Life360
    if (transitThreshold.toDouble() > 0.0) { inTransit = (speedUnits >= transitThreshold.toDouble()) }
    // if driving threshold specified in preferences then use it; else, use info provided by Life360
    if (drivingThreshold.toDouble() > 0.0) { isDriving = (speedUnits >= drivingThreshold.toDouble()) }

    def String sStatus = (memberPresence == "present") ? "At Home" : sprintf("%.1f", distanceUnits) + ((isMiles) ? " miles from Home" : "km from Home")

    if (logEnable && (isDriving || inTransit)) {
        // *** On the move ***
        log.debug "Life360+ Driver: $sStatus, speedUnits:$speedUnits, transitThreshold: $transitThreshold, inTransit: $inTransit, drivingThreshold: $drivingThreshold, isDriving: $isDriving"
    }

    sendEvent( name: "status", value: sStatus )
    state.status = sStatus

    sendEvent( name: "inTransit", value: inTransit )
    sendEvent( name: "isDriving", value: isDriving )
    sendEvent( name: "speed", value: speedUnits )
    sendEvent( name: "distance", value: distanceUnits )

    // Set acceleration to active state if we are either moving or if we are anywhere outside home radius
    sendEvent( name: "acceleration", value: (inTransit || isDriving || memberPresence == "not present" ) ? "active" : "inactive" )

    // *** Battery Level ***
    sendEvent( name: "battery", value: battery )

    // *** Charging State ***
    sendEvent( name: "charge", value: charge )
    sendEvent( name: "powerSource", value: (charge == "1") ? "DC" : "BTRY")
    def String cContact = (charge == "1") ? "open" : "closed"
    sendEvent( name: "contact", value: cContact )

    // *** Wifi ***
    sendEvent( name: "wifiState", value: wifiState )
    sendEvent( name: "switch", value: wifiState ? "on" : "off" )

    // ** Member Features **
    if (member.features != null) {
        sendEvent ( name: "shareLocation", value: member.features.shareLocation )
    }

    // ** Member Communications **
    if (member.communications != null) {
        member.communications.each { comm ->
            def String commType = comm.get('channel')
            if (commType != null && commType == "Voice") {
                sendEvent ( name: "phone", value: comm.value )
            } else if (commType != null && commType == "Email") {
                sendEvent ( name: "email", value: comm.value )
            }
        }
    }

    // *** Timestamp ***
    sendEvent ( name: "lastUpdated", value: lastUpdated )
    state.update = true

    // Lastly update the status tile
    if (generateHtml) sendStatusTile1()
    else if (device.currentValue('html') != null) sendEvent( name: "html", value: null)
}

def sendStatusTile1() {
    def String binTransita
    if(device.currentValue('isDriving') == true) {
        binTransita = "Driving"
    } else if(device.currentValue('inTransit') == true) {
        binTransita = "Moving"
    } else {
        binTransita = "Not Moving"
    }

    int sEpoch = device.currentValue('since')
    if(sEpoch == null) {
        theDate = use( groovy.time.TimeCategory ) {
            new Date( 0 )
        }
    } else {
        theDate = use( groovy.time.TimeCategory ) {
            new Date( 0 ) + sEpoch.seconds
        }
    }
    SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("E hh:mm a")
    String dateSince = DATE_FORMAT.format(theDate)

    String theMap = "https://www.google.com/maps/search/?api=1&query=" + device.currentValue('latitude').toString() + "," + device.currentValue('longitude').toString()

    tileMap = "<div style='overflow:auto;height:90%'><table width='100%'>"
    tileMap += "<tr><td width='25%' align=center><img src='${device.currentValue("avatar")}' height='${avatarSize}%'>"
    tileMap += "<td width='75%'><p style='font-size:${avatarFontSize}px'>"
    tileMap += "At: <a href='${theMap}' target='_blank'>${device.currentValue('address1') == "No Data" ? "Between Places" : device.currentValue('address1')}</a><br>"
    tileMap += "Since: ${dateSince}<br>"
    tileMap += (device.currentValue('status') == "At Home") ? "" : "${device.currentValue('status')}<br>"
    tileMap += "${binTransita}"
    if(device.currentValue('address1') == "No Data" ? "Between Places" : device.currentValue('address1') != "Home" && device.currentValue('inTransit') == true) {
        tileMap += " @ ${sprintf("%.1f", device.currentValue('speed'))} "
        tileMap += (isMiles) ? "MPH":"KPH"
    }
    tileMap += "<br>Phone Lvl: ${device.currentValue('battery')} - ${device.currentValue('powerSource')} - "
    tileMap += (device.currentValue('wifiState') == true) ? "WiFi" : "No WiFi"
    tileMap += "<br><p style='width:100%'>${device.currentValue('lastUpdated')}</p>" //Avi - cleaned up formatting (cosmetic / personal preference only)
    tileMap += "</table></div>"

    int tileDevice1Count = tileMap.length()
    if (tileDevice1Count > 1024) log.warn "Life360+ Driver: In sendStatusTile1 - Too many characters to display on Dashboard (${tileDevice1Count})"
    sendEvent(name: "html", value: tileMap, displayed: true)
}

def haversine(lat1, lon1, lat2, lon2) {
    def Double R = 6372.8
    // In kilometers
    def Double dLat = Math.toRadians(lat2 - lat1)
    def Double dLon = Math.toRadians(lon2 - lon1)
    lat1 = Math.toRadians(lat1)
    lat2 = Math.toRadians(lat2)

    def Double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2)
    def Double c = 2 * Math.asin(Math.sqrt(a))
    def Double d = R * c
    return(d)
}
