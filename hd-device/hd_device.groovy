// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1270
// hubitat end

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** HD+ Device **
 *
 * Companion Hubitat app/driver for HD+ Android app: https://community.hubitat.com/t/release-hd-android-dashboard/41674/
 *
 * - Use as presence for HD+ device (geofence)
 * - Send notifications (text or TTS) to any HD+ device
 * - TODO: instant cloud mode (remote) device status updates
 *
 *  Changes:
 *  1.0.11 - 11/02/24 - allow adding child devices
 *  1.0.10 - 10/28/24 - added error handling and a status variable
 *  1.0.8 - 10/07/24 - set FCM messages to high priority (faster delivery)
 *  1.0.7 - 09/17/24 - added Hubitat app and OAUTH support (old version stopped working)
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

@Field static String TYPE_TTS = "tts"
@Field static String TYPE_NOTIFY = "notify"
@Field static String TYPE_BTN = "btn"

metadata {
    definition(
        name: "HD Device",
        namespace: "jpage4500",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-device/hd_device.groovy"
    ) {

        capability "Initialize"
        capability "Notification"
        capability "AudioNotification"
        capability "SpeechSynthesis"
        capability "MusicPlayer"
        capability "PresenceSensor"
        capability "PushableButton"

        command('setProjectID', [[name: 'Set Project ID', type: 'STRING', description: 'HD+ will automatically set the project ID']])
        command('setApiKey', [[name: 'Set API key', type: 'STRING', description: 'HD+ will automatically set the API key']])
        command('setAppID', [[name: 'Set app ID', type: 'STRING', description: 'HD+ will automatically set the app ID']])
        command('setGoogleAccessToken', [[name: 'Set Project ID', type: 'STRING', description: 'HD+ will automatically set the access token']])
        command('setError', [[name: 'Set Error', type: 'STRING', description: 'HD+ will automatically set the error']])
        command('setClientKey', [[name: 'Set Device FCM key', type: 'STRING', description: 'HD+ will automatically set the device FCM key']])

        //command('startMonitoring', [[name: 'Monitor Device Changes', type: 'STRING', description: 'HD+ will automatically call this']])
        //command "stopMonitoring"

        // -- presence sensor commands --
        command "arrived"
        command "departed"

        // debugging
        command('logTokens')

        attribute "presence", "enum", ["present", "not present"]
        attribute "numberOfButtons", "number"
    }

    preferences {
        input name: "isLogging", type: "bool", title: "Enable Logging", description: "", required: true
    }

    // needed by Android client to generate a clientKey
    attribute "projectId", "string"
    attribute "apiKey", "string"
    attribute "appId", "string"
    // set by Android client (FCM)
    attribute "clientKey", "string"
    // driver status: not configured, ready, error
    attribute "status", "string"

    attribute "notificationText", "string"
}

def logTokens() {
    updateTokens()
}

def setProjectID(String projectId) {
    state.projectId = projectId
}

def setApiKey(String key) {
    state.apiKey = key
}

def setGoogleAccessToken(String token) {
    state.googleAccessToken = token
}

def setError(String error) {
    state.error = error
}

def setAppID(String id) {
    state.appID = id
}

String getGoogleAccessToken() {
    if (parent) return parent.getGoogleAccessToken()
    return state.googleAccessToken
}

String getProjectID() {
    if (parent) return parent.getProjectId()
    return state.projectId
}

String getApiKey() {
    if (parent) return parent.getApiKey()
    return state.apiKey
}

String getError() {
    if (parent) return parent.getError()
    return state.error
}

String getAppID() {
    if (parent) return parent.getAppId()
    return state.appID
}

def updateTokens() {
    // client side values required by HD+ to create a FCM token
    def projectId = getProjectID()
    if (projectId == null) {
        if (isLogging) log.debug "logTokens: no parent device!"
    }
    else {
        sendEvent(name: "projectId", value: projectId)
        def apiKey = getApiKey()
        sendEvent(name: "apiKey", value: apiKey)
        def appId = getAppID()
        sendEvent(name: "appId", value: appId)
        if (isLogging) log.debug "updateTokens: proj:$projectId, token:$accessToken, appId:$appId"
    }
    
    updateStatus()
}

def initialize() {
    updateTokens()
    sendEvent(name: "numberOfButtons", value: 10)
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

/**
 * NOTE: called by HD+ client to set it's client key -- needed to reach device via FCM
 */
def setClientKey(key) {
    if (isLogging) log.debug "setClientKey: ${key}"
    sendEvent(name: "clientKey", value: key)
    updateStatus()
}

/**
 * check if any config values are missing and set 'status' value
 */
def updateStatus() {
    def projectId = getProjectID()
    def oauthToken = getGoogleAccessToken()
    
    if (!parent && !projectId) {
        sendEvent(name: "status", value: "ERROR: parent app not found")
        return
    }

    if (isEmpty(projectId)) {
        sendEvent(name: "status", value: "ERROR: missing projectId")
        return
    } else if (isEmpty(oauthToken)) {
        sendEvent(name: "status", value: "ERROR: missing oauthToken")
        return
    }

    // clientKey is set by device -- this is the LAST piece needed
    def clientKey = device.currentValue('clientKey')
    if (isEmpty(clientKey)) {
        sendEvent(name: "status", value: "ERROR: client not configured")
        return
    }

    def error = getError()
    if (isEmpty(error)) {
        sendEvent(name: "status", value: (parent != null || projectId != null) ? "READY" : "UNKNOWN")
    } else {
        sendEvent(name: "status", value: "ERROR: ${error}")
    }

}

/**
 * start monitoring multiple devices for state changes; when changes occur, update will be sent using FCM push
 */
def startMonitoring(devices) {
    if (isLogging) log.debug "startMonitoring:"
    interfaces.webSocket.connect("ws://localhost:8080/eventsocket")
}

def stopMonitoring() {
    if (isLogging) log.debug "stopMonitoring:"
    interfaces.webSocket.close()
}

// -- notification commands --
def deviceNotification(text) {
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
def arrived() {
    sendEvent(name: "presence", value: "present")
}

def departed() {
    sendEvent(name: "presence", value: "not present")
}

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------

void notifyVia(msgType, text) {
    if (msgType == TYPE_NOTIFY || msgType == TYPE_TTS) {
        sendEvent(name: "notificationText", value: text)
    }

    def projectId = getProjectID()
    def oauthToken = getGoogleAccessToken()
    def clientKey = device.currentValue('clientKey')
    updateStatus()

    if (isEmpty(clientKey)) {
        log.error "notifyVia: clientKey not set, text: ${text}"
        return
    } else if (isEmpty(projectId)) {
        log.error "notifyVia: projectId not set, text: ${text}"
        return
    } else if (isEmpty(oauthToken)) {
        log.error "notifyVia: oauthToken not set, text: ${text}"
        return
    }

    def url = "https://fcm.googleapis.com/v1/projects/${projectId}/messages:send"

    def headers = [
        "Authorization": "Bearer ${oauthToken}",
        "Content-Type" : "application/json; UTF-8"
    ]

    def body = [
        "message": [
            "token": "${clientKey}",
            "android": [
                "priority": "high"
            ]
        ]
    ]

    if (msgType == TYPE_NOTIFY) {
        // use notification payload to display system notification (no need to start HD+ to display message)
        body["message"]["notification"] = [
            "body": "${text}",
        ]
    } else {
        // use data payload to allow HD+ to intercept message (don't show notification)
        body["message"]["data"] = [
            "type"    : "${msgType}",
            "deviceId": "${device.getId()}",
            "msg"     : "${text}"
        ]
    }

    def params = [
        uri        : url,
        headers    : headers,
        body       : body,
        contentType: "application/json"
    ]

    if (isLogging) log.debug "notifyVia: ${msgType}: text: \"${text}\""
    //if (isLogging) log.debug "notifyVia: ${JsonOutput.toJson(body)}"

    asynchttpPost(handlePostCommand, params, body)
}

def handlePostCommand(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        // request failed!
        log.error "handlePostCommand: ERROR: http:${respCode}, err:${resp.getErrorData()}, body:${data}"
    }
}

static def isEmpty(str) {
    return (str == null || str.length() == 0)
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
