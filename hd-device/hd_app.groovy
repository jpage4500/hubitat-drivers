// hubitat start
// hub: 192.168.0.200
// type: app
// id: 777
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
}

mappings {
    path("/handleAuth") {
        action:
        [
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
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            href name: "myHref", url: "https://joe-page-software.gitbook.io/hubitat-dashboard/tiles/hd+-companion-app-driver", title: "Step-by-step instructions", style: "external"
        }
        section(header("Hubitat Setup")) {
            input 'clientId', 'text', title: 'Client ID', required: true, defaultValue: '', submitOnChange: true
            input 'clientSecret', 'text', title: 'Client Secret', required: true, defaultValue: '', submitOnChange: true
            showAuthorizeButton()
            showAuthorizedText()
        }
        section(header("HD+ Setup")) {
            input 'projectId', 'text', title: 'Project ID', required: true, defaultValue: '', submitOnChange: true
            input 'apiKey', 'text', title: 'API Key', required: true, defaultValue: '', submitOnChange: true
            input 'appId', 'text', title: 'App ID', required: true, defaultValue: '', submitOnChange: true
        }
        section(header("Devices")) {
            input "linkedDevices", "device.HDDevice", title: "Linked Devices", multiple: true, required: false
        }
        section(header("Other")) {
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
        }

    }
}

def showAuthorizeButton() {
    if (clientId == null || clientSecret == null) return;
    // create access token if one doesn't exist
    if (state?.accessToken == null) {
        createAccessToken()
        log.info("getAuthLink: accessToken:${state?.accessToken}")
    }
    href(
        name: 'authHref',
        title: 'Authorize App',
        url: 'https://accounts.google.com/o/oauth2/v2/auth?' +
            'redirect_uri=https://cloud.hubitat.com/oauth/stateredirect' +
            '&state=' + getHubUID() + '/apps/' + app.id + '/handleAuth?access_token=' + state.accessToken +
            '&access_type=offline&prompt=consent&client_id=' + clientId +
            '&response_type=code&scope=https://www.googleapis.com/auth/firebase.messaging',
        description: 'Click to authorize app with Google'
    )
}

def showAuthorizedText() {
    if (state?.googleAccessToken != null) {
        showMessage("Authorized!")
    } else {
        showMessage("Not Authorized - fill in required fields -> Save -> Authorize")
    }
}

// called by HD+ Device child
def getProjectId() {
    return projectId
}

// called by HD+ Device child
def getApiKey() {
    return apiKey
}

// called by HD+ Device child
def getAppId() {
    return appId
}

// called by HD+ Device child
// returns OAUTH access token
def getGoogleAccessToken() {
    return state?.googleAccessToken
}

// called by HD+ Device child
// returns error state
def getError() {
    return state?.error
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

def updated() {
    logDebug("updating")
    rescheduleLogin()

    updateLinkedDevices()
}

def installed() {
    log.info "installed"
    // start app on Hub reboot?
    subscribe(location, 'systemStart', initialize)
}

def uninstalled() {
    log.info "uninstalled"
    unschedule()
    unsubscribe()
    deleteChildDevice(state.deviceId)
}

def initialize(evt) {
    log.debug("initialize: ${evt.device} ${evt.value} ${evt.name}")
    rescheduleLogin()
}

def rescheduleLogin() {
    logDebug("rescheduleLogin")
    unschedule(refreshLogin)
    if (state?.googleRefreshToken) {
        refreshLogin()
        runEvery1Hour refreshLogin
    }
}

def login(String authCode) {
    if (clientId == null || clientSecret == null) {
        log.info('login: clientId/clientSecret not set!')
        return;
    }
    logDebug('login: getting access token..')
    def uri = 'https://www.googleapis.com/oauth2/v4/token'
    def query = [
        client_id    : clientId,
        client_secret: clientSecret,
        code         : authCode,
        grant_type   : 'authorization_code',
        redirect_uri : 'https://cloud.hubitat.com/oauth/stateredirect'
    ]
    def params = [uri: uri, query: query]
    try {
        httpPost(params) { response -> handleLoginResponse(response) }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("login: ${e.getLocalizedMessage()}: ${e.response.data}")
    } catch (e) {
        log.error("login:ERROR: ${e}")
    }
}

def setError(String error) {
    state.error = error
    updateLinkedDevices(true)
}

def refreshLogin() {
    if (clientId == null || clientSecret == null) {
        log.info('refreshLogin: clientId/clientSecret not set!')
        return;
    }
    logDebug('refreshLogin: refreshing access token..')
    def uri = 'https://www.googleapis.com/oauth2/v4/token'
    def query = [
        client_id    : clientId,
        client_secret: clientSecret,
        refresh_token: state.googleRefreshToken,
        grant_type   : 'refresh_token',
    ]
    def params = [uri: uri, query: query]
    try {
        httpPost(params) { response -> handleLoginResponse(response) }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("refreshLogin:HttpResponseException: ${e.getLocalizedMessage()}: ${e.response.data}")
        setError("Refresh Error: ${e.getLocalizedMessage()}: ${e.response.data}")
    } catch (e) {
        // java.net.UnknownHostException: www.googleapis.com: Temporary failure in name resolution on line 285 (method initialize)
        log.error("refreshLogin:ERROR: ${e}")
        setError("Refresh Error: ${e}")
    }
}

def handleLoginResponse(resp) {
    setError(null)
    def respCode = resp.getStatus()
    def respJson = resp.getData()
    if (respCode == 200) {
        state.lastSuccess = new Date()
        logDebug("handleLoginResponse: ${respCode}")
    } else {
        setError("Refresh Error: ${respCode}, ${respJson}")
        log.error("handleLoginResponse: ERROR: ${respCode}, ${respJson}")
    }
    // refresh token not always returned (no change)
    if (respJson.refresh_token) {
        state.googleRefreshToken = respJson.refresh_token
    }
    state.googleAccessToken = respJson.access_token
    
    updateLinkedDevices()
}

def updateLinkedDevices(boolean errorOnly=false) {
    linkedDevices?.forEach {d -> 
        if (!errorOnly) {
            d.setProjectID(getProjectId())
            d.setApiKey(getApiKey())
            d.setGoogleAccessToken(getGoogleAccessToken())
            d.setAppID(getAppId())
            d.initialize()
        }
        
        d.setError(getError())
    }
}

// ----------------------------------------------------------------------------
// Utility functions
// ----------------------------------------------------------------------------
static String header(text) {
    return "<div style='color:#ffffff;font-weight: bold;background-color:#244D76FF;padding-top: 10px;padding-bottom: 10px;border: 1px solid #000000;box-shadow: 2px 3px #8B8F8F;border-radius: 10px'><image style='padding: 0px 10px 0px 10px;' src=https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-device/images/logo.png width='50'> ${text}</div>"
}

static boolean isEmpty(text) {
    return text == null || text.isEmpty()
}

def showMessage(text) {
    if (!isEmpty(text)) {
        paragraph("<p style='color:red; font-weight: bold'>${text}</p>")
    }
}
