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
 *  1.0.6 - 11/16/23 - use a more direct method for displaying notifications
 *  1.0.5 - 10/29/23 - reduce logging
 *  1.0.4 - 10/26/23 - add support for PushableButton which can be defined to run custom commands in HD+
 *  1.0.3 - 10/14/23 - add more speak() methods
 *  1.0.2 - 10/13/23 - add FCM server key
 *  1.0.1 - 10/13/23 - support SpeechSynthesis for TTS
 * ------------------------------------------------------------------------------------------------------------------------------
**/

import groovy.json.*
import groovy.transform.Field
import hubitat.helper.InterfaceUtils

@Field static String GURL = "https://fcm.googleapis.com/fcm/send"
@Field static String GKEY = "QUFBQUNpMndORVU6QVBBOTFiSGNNOEtBY05tektiYjN5RjkzUGhJRTloMXNFbk9mT3E5VDBrRm11SUFxWXhOMTA4MVVaOFNVMmRQRGFnbkdsZElhazAyLTBVSUpJZmZhbGVzSmo5SmotYVpjdC1PMnNFSm12YU5iSmVxSU1zdWFZSlE4aVNwSkpPTWE3d2ZEMEN6YndrRzg="

@Field static String TYPE_TTS = "tts"
@Field static String TYPE_NOTIFY = "notify"
@Field static String TYPE_BTN = "btn"

metadata {
    definition(name: "HD+ Device", namespace: "jpage4500", author: "Joe Page", importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-device/hd_device.groovy") {
        capability "Initialize"
        capability "Notification"
        capability "AudioNotification"
        capability "SpeechSynthesis"
        capability "MusicPlayer"
        capability "PresenceSensor"
        capability "PushableButton"

        command('setClientKey', [[name: 'Set Device FCM key', type: 'STRING', description: 'HD+ will automatically set the device FCM key']])
        command('setServerKey', [[name: 'Set Server FCM key', type: 'STRING', description: 'NOTE: don\'t update this unless you want to use your own FCM server key']])
        
        //command('startMonitoring', [[name: 'Monitor Device Changes', type: 'STRING', description: 'HD+ will automatically call this']])
        //command "stopMonitoring"

        // -- presence sensor commands --
        command "arrived"
        command "departed"

        attribute "presence", "enum", ["present", "not present"]
        attribute "numberOfButtons", "number"
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
    setServerKey(serverKey)
    sendEvent(name: "numberOfButtons", value: 10)
}

def updated() {
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
    if (isLogging) log.debug "startMonitoring:"
    interfaces.webSocket.connect("ws://localhost:8080/eventsocket")
}

def stopMonitoring () {
    if (isLogging) log.debug "stopMonitoring:"
    interfaces.webSocket.close()
}

// -- notification commands --
def deviceNotification (text) {
    notifyVia(TYPE_NOTIFY, text)
}

// -- audio notification commands --
void playText(text) {
    notifyVia(TYPE_TTS, text)
}
void playText(text, volumelevel) {
    notifyVia(TYPE_TTS, text)
}
void playTextAndRestore(text, volumelevel) {
    notifyVia(TYPE_TTS, text)
}
void playTextAndResume(text, volumelevel) {
    notifyVia(TYPE_TTS, text)
}
def playTrack(trackuri, volumelevel) {}
def playTrackAndRestore(trackuri, volumelevel) {}
def playTrackAndResume(trackuri, volumelevel) {}

// -- SpeechSynthesis commands --
def speak(text) {
    notifyVia(TYPE_TTS, text)
}
def speak(text, volume) {
    notifyVia(TYPE_TTS, text)
}
def speak(text, volume, voice) {
    notifyVia(TYPE_TTS, text)
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

// -- PushableButton commands --
def push(buttonNumber) {
    notifyVia(TYPE_BTN, buttonNumber)
}

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
    if (msgType == TYPE_NOTIFY || msgType == TYPE_TTS) {
        sendEvent( name: "notificationText", value: text )
    }

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
            to: "${clientKey}"
        ],
        contentType: "application/json"
    ]

    if (msgType == TYPE_NOTIFY) {
        // use notification payload to display system notification (bypass HD+)
        params["body"]["notification"] = [
            "body" : "${text}"
        ]
    } else {
        // use data payload to allow HD+ to handle message first
        params["body"]["data"] = [
            "type" : "${msgType}",
            "deviceId" : "${device.getId()}",
            "msg" : "${text}"
        ]
    }

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
        if (isLogging) log.debug "success:"
    }
}

// ----------------------------------------------------------------------------
// eventstream handler
// ----------------------------------------------------------------------------

def webSocketStatus(String socketStatus) {
   if (socketStatus.startsWith("status: open")) {
      if (isLogging) log.debug "webSocketStatus: Connected"
   } else if (socketStatus.startsWith("status: closing")) {
      if (isLogging) log.debug "webSocketStatus: Closing"
   } else if (socketStatus.startsWith("failure:")) {
      log.error "webSocketStatus: failed: ${socketStatus}"
   } else {
      log.error "webSocketStatus: unknown error: ${socketStatus}"
   }
}

def parse(String description) {
   // { "source":"DEVICE","name":"commsError","displayName" : "Garage Lights", "value" : "false", "type" : "null", "unit":"null","deviceId":1041,"hubId":0,"installedAppId":0,"descriptionText" : "null"}
   def json = new JsonSlurper().parseText(description)
   if (isLogging) log.debug "GOT: device:${json.displayName}, ${json.name} = ${json.value}"
}
