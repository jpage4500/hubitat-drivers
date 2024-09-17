// hubitat start
// hub: 192.168.0.200
// type: app
// id: 776
// hubitat end

import groovy.json.JsonSlurper

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
 *  1.0.7 - 09/17/24 - added Hubitat app and OAUTH support (old version stopped working)
 *  1.0.6 - 11/16/23 - use a more direct method for displaying notifications
 *  1.0.5 - 10/29/23 - reduce logging
 *  1.0.4 - 10/26/23 - add support for PushableButton which can be defined to run custom commands in HD+
 *  1.0.3 - 10/14/23 - add more speak() methods
 *  1.0.2 - 10/13/23 - add FCM server key
 *  1.0.1 - 10/13/23 - support SpeechSynthesis for TTS
 *
 * NOTE: used Google Photos App as reference to write this https://github.com/dkilgore90/google-photos
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

definition(
        name: 'HD+ Companion App',
        namespace: 'jpage4500',
        author: 'Joe Page',
        description: 'Companion app for HD+ (Android)',
        importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-device/hd_app.groovy',
        category: '',
        oauth: true,
        iconUrl: '',
        iconX2Url: '',
        iconX3Url: ''
)

preferences {
    page(name: 'mainPage')
    page(name: 'debugPage')
}

mappings {
    path("/handleAuth") {
        action: [
                GET: "handleAuthRedirect"
        ]
    }
}

private logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug "$msg"
    }
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Setup", install: true, uninstall: true) {
        section {
            input 'credentials', 'text', title: 'Google credentials.json', required: true, defaultValue: '', submitOnChange: false
        }
        getAuthLink()

        section {
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
        }

        getPhotosButton()
        getDebugLink()
    }
}

def debugPage() {
    dynamicPage(name:"debugPage", title: "Debug", install: false, uninstall: false) {
        section {
            paragraph "Debug buttons"
        }
        section {
            input 'getToken', 'button', title: 'Log Access Token', submitOnChange: true
        }
        section {
            input 'refreshToken', 'button', title: 'Force Token Refresh', submitOnChange: true
        }
        mainPageLink()
    }
}

def getAuthLink() {
    if (credentials && state?.accessToken) {
        def creds = getCredentials()
        section {
            href(
                    name       : 'authHref',
                    title      : 'Auth Link',
                    url        : 'https://accounts.google.com/o/oauth2/v2/auth?' +
                            'redirect_uri=https://cloud.hubitat.com/oauth/stateredirect' +
                            '&state=' + getHubUID() + '/apps/' + app.id + '/handleAuth?access_token=' + state.accessToken +
                            '&access_type=offline&prompt=consent&client_id=' + creds?.client_id +
                            '&response_type=code&scope=https://www.googleapis.com/auth/firebase.messaging',
                    description: 'Click this link to authorize'
            )
        }
    } else {
        section {
            paragraph "Authorization link is hidden until the required credentials.json input is provided, and App installation is saved by clicking 'Done'"
        }
    }
}

def getPhotosButton() {
    if (state?.googleAccessToken != null) {
        section {
            paragraph "Authorized!"
        }
    } else {
        section {
            paragraph "Not authorized - see https://github.com/dkilgore90/google-photos?tab=readme-ov-file"
        }
    }
}

def getDebugLink() {
    section{
        href(
                name       : 'debugHref',
                title      : 'Debug buttons',
                page       : 'debugPage',
                description: 'Access debug buttons (log current googleAccessToken, force googleAccessToken refresh)'
        )
    }
}

def getCredentials() {
    try {
        def creds = new JsonSlurper().parseText(credentials)
        return creds.web
    } catch (Throwable e) {
        //ignore -- this is thrown when the App first loads, before credentials can be entered
    }
}

def handleAuthRedirect() {
    log.info('successful redirect from google')
    unschedule(refreshLogin)
    def authCode = params.code
    login(authCode)
    runEvery1Hour refreshLogin
    def builder = new StringBuilder()
    builder << "<!DOCTYPE html><html><head><title>Hubitat Elevation - HD+</title></head>"
    builder << "<body><p>Congratulations! HD+ has authenticated successfully</p>"
    builder << "<p><a href=https://${location.hub.localIP}/installedapp/configure/${app.id}/mainPage>Click here</a> to return to the App main page.</p></body></html>"

    def html = builder.toString()

    render contentType: "text/html", data: html, status: 200
}

def mainPageLink() {
    section {
        href(
                name       : 'Main page',
                page       : 'mainPage',
                description: 'Back to main page'
        )
    }
}

def updated() {
    log.info "updating"
    rescheduleLogin()
    resume()
}

def installed() {
    log.info "installed"
    createAccessToken()
    subscribe(location, 'systemStart', initialize)
    resume()
    state.deviceId = UUID.randomUUID().toString()
    addChildDevice('jpage4500', "HD+ Device", state.deviceId)
}

def uninstalled() {
    log.info "uninstalled"
    unschedule()
    unsubscribe()
    deleteChildDevice(state.deviceId)
}

def initialize(evt) {
    log.debug(evt)
    recover()
}

def recover() {
    rescheduleLogin()
}

def rescheduleLogin() {
    unschedule(refreshLogin)
    if (state?.googleRefreshToken) {
        refreshLogin()
        runEvery1Hour refreshLogin
    }
}

def login(String authCode) {
    log.info('Getting access_token from Google')
    def creds = getCredentials()
    def uri = 'https://www.googleapis.com/oauth2/v4/token'
    def query = [
            client_id    : creds.client_id,
            client_secret: creds.client_secret,
            code         : authCode,
            grant_type   : 'authorization_code',
            redirect_uri : 'https://cloud.hubitat.com/oauth/stateredirect'
    ]
    def params = [uri: uri, query: query]
    try {
        httpPost(params) { response -> handleLoginResponse(response) }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("Login failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }
}

def refreshLogin() {
    log.info('Refreshing access_token from Google')
    def creds = getCredentials()
    def uri = 'https://www.googleapis.com/oauth2/v4/token'
    def query = [
            client_id    : creds.client_id,
            client_secret: creds.client_secret,
            refresh_token: state.googleRefreshToken,
            grant_type   : 'refresh_token',
    ]
    def params = [uri: uri, query: query]
    try {
        httpPost(params) { response -> handleLoginResponse(response) }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("Login refresh failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }
}

def handleLoginResponse(resp) {
    def respCode = resp.getStatus()
    def respJson = resp.getData()
    logDebug("Authorized scopes: ${respJson.scope}")
    if (respJson.refresh_token) {
        state.googleRefreshToken = respJson.refresh_token
    }
    state.googleAccessToken = respJson.access_token
}

def appButtonHandler(btn) {
    switch (btn) {
        case 'getToken':
            logToken()
            break
        case 'refreshToken':
            refreshLogin()
            break
    }
}

def resume() {
    logDebug("resume")
}

def logToken() {
    log.debug("Access Token: ${state.googleAccessToken}")
}
