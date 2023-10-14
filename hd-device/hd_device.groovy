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
 *  Changes:
 *  1.0.3 - 10/14/23 - add more speak() methods
 *  1.0.2 - 10/13/23 - add FCM server key
 *  1.0.1 - 10/13/23 - support SpeechSynthesis for TTS
 *
 * ------------------------------------------------------------------------------------------------------------------------------
**/

import groovy.json.*
import groovy.transform.Field

@Field static String GURL = "https://fcm.googleapis.com/fcm/send"
@Field static String GKEY = "QUFBQUNpMndORVU6QVBBOTFiSGNNOEtBY05tektiYjN5RjkzUGhJRTloMXNFbk9mT3E5VDBrRm11SUFxWXhOMTA4MVVaOFNVMmRQRGFnbkdsZElhazAyLTBVSUpJZmZhbGVzSmo5SmotYVpjdC1PMnNFSm12YU5iSmVxSU1zdWFZSlE4aVNwSkpPTWE3d2ZEMEN6YndrRzg="

metadata {
    definition(name: "HD+ Device", namespace: "jpage4500", author: "Joe Page", importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-device/hd_device.groovy") {
        capability "Initialize"
        capability "Notification"
        capability "AudioNotification"
        capability "SpeechSynthesis"
        capability "MusicPlayer"
        capability "PresenceSensor"

        command('setClientKey', [[name: 'Set Device FCM key', type: 'STRING', description: 'HD+ will automatically set the device FCM key']])
        command('setServerKey', [[name: 'Set Server FCM key', type: 'STRING', description: 'NOTE: don\'t update this unless you want to use your own FCM server key']])
        
        //command('startMonitoring', [[name: 'Monitor Device Changes', type: 'STRING', description: 'HD+ will automatically call this']])
        //command('stopMonitoring', [[name: 'Stop Monitoring', type: 'STRING', description: 'HD+ will automatically call this']])

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

def initialize() {
    serverKey = device.currentValue('serverKey')
    if (isLogging) log.debug "initialize: ${serverKey}"
    setServerKey(serverKey)
}

def updated() {
    if (isLogging) log.debug "updated:"
    initialize()
}

def installed() {
    if (isLogging) log.debug "installed:"
    initialize()
}

def uninstalled() {
    if (isLogging) log.debug "uninstalled:"
}

def setClientKey (key) {
    if (isLogging) log.debug "setClientKey: ${key}"
    sendEvent( name: "clientKey", value: key )
}

def setServerKey (key) {
    if (isLogging) log.debug "setServerKey: ${key}"
    if (key == null || key.length() == 0) {
        // use default
        byte[] decoded = GKEY.decodeBase64()
        key = new String(decoded)
        if (isLogging) log.debug "setServerKey: USING DEFAULT KEY"
    }
    sendEvent( name: "serverKey", value: key )
}

/**
 * start monitoring multiple devices for state changes; when changes occur, update will be sent using FCM push
 */
def startMonitoring (devices) {
    if (isLogging) log.debug "startMonitoring: ${devices}"
    //def json = new JsonSlurper().parseText(devices)
}

def stopMonitoring () {
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
def playTrack(trackuri, volumelevel) {}
def playTrackAndRestore(trackuri, volumelevel) {}
def playTrackAndResume(trackuri, volumelevel) {}

// -- SpeechSynthesis commands --
def speak(text) {
    notifyVia("tts", text)
}
def speak(text, volume) {
    notifyVia("tts", text)
}
def speak(text, volume, voice) {
    notifyVia("tts", text)
}

// -- MusicPlayer commands --
def mute() {}
def nextTrack() {}
def pause() {}
def play() {}
def playTrack(trackuri) {}
def previousTrack() {}
def restoreTrack(trackuri) {}
def resumeTrack(trackuri) {}
def setLevel(volumelevel) {}
def setTrack(trackuri) {}
def stop() {}
def unmute() {}

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
        uri: GURL,
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