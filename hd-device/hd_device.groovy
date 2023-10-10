/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** HD+ Device **
 * 
 * Companion Hubitat driver for HD+ Android app: https://community.hubitat.com/t/release-hd-android-dashboard/41674/
 * 
 * - Use as presence for HD+ device (geofence)
 * - Send notifications (text or TTS) to any HD+ device
 * - TODO: instant cloud mode (remote) device status updates
 * 
 * ------------------------------------------------------------------------------------------------------------------------------
**/

import groovy.json.*

metadata {
    definition(name: "HD+ Device", namespace: "jpage4500", author: "Joe Page", importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-device/hd_device.groovy") {
        capability "Notification"
        capability "AudioNotification"
        capability "PresenceSensor"

        command('setClientKey', [[name: 'Set Device FCM key', type: 'STRING', description: 'HD+ will automatically set the device FCM key']])
        command('setServerKey', [[name: 'Set Server FCM key', type: 'STRING', description: 'HD+ will automatically set the server FCM key']])

        // -- presence sensor commands --
        command "arrived"
        command "departed"

        attribute "presence", "enum", ["present", "not present"]
    }

    preferences {
        input name: "isLogging", type: "bool", title: "Enable Logging", description: "", required: true
    }

    attribute "clientKey", "string"
    attribute "serverKey", "string"
    attribute "notificationText", "string"
}

def setClientKey (key) {
    if (isLogging) log.debug "setClientKey: ${key}"
    sendEvent( name: "clientKey", value: key )
}

def setServerKey (key) {
    if (isLogging) log.debug "setServerKey: ${key}"
    sendEvent( name: "serverKey", value: key )
}

// -- notification commands --
def deviceNotification (text) {
    notifyVia("notify", text)
}

// -- audio notification commands --
void playText(text) {
    notifyVia("tts", text)
}
void playText(text, volumelevel) {
    notifyVia("tts", text)
}
void playTextAndRestore(text, volumelevel) {
    notifyVia("tts", text)
}
void playTextAndResume(text, volumelevel) {
    notifyVia("tts", text)
}
def playTrack(trackuri, volumelevel) { log.error "playTrack not implemented!" }
def playTrackAndRestore(trackuri, volumelevel) { log.error "playTrackAndRestore not implemented!" }
def playTrackAndResume(trackuri, volumelevel) { log.error "playTrackAndResume not implemented!" }

// -- presence sensor commands --
def arrived () {
    sendEvent(name: "presence", value: "present")
}

def departed () {
    sendEvent(name: "presence", value: "not present")
}

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------

void notifyVia(msgType, text) {
    sendEvent( name: "notificationText", value: text )

    clientKey = device.currentValue('clientKey')
    serverKey = device.currentValue('serverKey')

    if (clientKey == null || clientKey.length() == 0) {
        log.error "notifyVia: clientKey not set, text: ${text}"
        return
    } else if (serverKey == null || serverKey.length() == 0) {
        log.error "notifyVia: serverKey not set, text: ${text}"
        return
    }

    def params = [
        uri: "https://fcm.googleapis.com/fcm/send",
        headers: [
            'Authorization': "key=${serverKey}"
        ],
        body: [
            to: "${clientKey}",
            data: [
                type: "${msgType}",
                deviceId: "${device.getId()}",
                msg: "${text}"
            ]
        ],
        contentType: "application/json"
    ]

    if (isLogging) log.debug "notifyVia: ${msgType}: text: \"${text}\""

    asynchttpPost(handlePostCommand, params, params.body)
}

def handlePostCommand(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        // request failed!
        log.error "handlePostCommand: ERROR: http:${respCode}, body:${resp.getErrorData()}"
    } else {
        // success
    }
}