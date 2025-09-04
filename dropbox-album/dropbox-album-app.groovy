// hubitat start
// hub: 192.168.0.200
// type: app
// id: 884
// hubitat end

import groovy.json.JsonBuilder
import groovy.transform.Field

import java.security.MessageDigest

definition(
    name: "Dropbox Album",
    namespace: "jpage4500",
    author: "Joe Page",
    description: "Browse Dropbox folder, show random image in child driver",
    oauth: true,
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: ''
)

preferences {
    page(name: "mainPage")
    page(name: "browseFolders")
}

mappings {
    path("/handleAuth") {
        action:
        [GET: "oauthCallback"]
    }
}

@Field static String CLIENT_ID = "qb3joeqf66gltdt"

def mainPage(def params) {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        if (!isTokenValid()) {
            initialize()
            // not connected to dropbox
            section(header("Dropbox OAuth")) {
                if (state.dropboxAuthStatus) {
                    paragraph "Status: ${state.dropboxAuthStatus}"
                }
                paragraph "Click the button below to open the Dropbox authorization page in a new window. Copy the code provided by Dropbox and paste it into the input field below."
                href(url: oauthInitUrl(), title: "Authorize Dropbox", description: "Open Dropbox OAuth", style: "external")

                input name: "accessCode", type: "text", title: "Access Code", required: true, submitOnChange: true
                if (settings?.accessCode) {
                    String msg = processAccessCode(settings.accessCode)
                    if (msg) {
                        paragraph "${msg}"
                    }
                }
            }
        } else {
            // connected to dropbox
            section(header("Selected Folder")) {
                if (params?.selectedFolder) {
                    state.selectedFolder = params.selectedFolder
                }
                paragraph "Folder: ${state.selectedFolder ?: 'None selected'}"
                href(name: "browse", title: "Browse Dropbox Folders", page: "browseFolders")
            }
            section(header("Options")) {
                input name: "updateInterval", type: "number", title: "Update every X minutes", defaultValue: 15
                input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
            }
        }
    }
}

def processAccessCode(String code) {
    if (!code) {
        log.error "No access code provided."
        return null
    }

    def tokenParams = [
        uri : "https://api.dropboxapi.com/oauth2/token",
        body: [
            code         : code,
            grant_type   : "authorization_code",
            client_id    : CLIENT_ID,
            code_verifier: state.codeVerifier
        ]
    ]

    log.debug "processAccessCode: ${tokenParams}"

    try {
        httpPost(tokenParams) { resp ->
            if (resp.status == 200) {
                state.dropbox = [
                    accessToken : resp.data.access_token,
                    refreshToken: resp.data.refresh_token,
                    obtainedAt  : now(),
                    expiresIn   : resp.data.expires_in
                ]
                state.dropboxAuthStatus = "Connected"
                log.debug "Authorization successful!"
                return "Authorization successful!"
            } else {
                log.error "Token exchange failed: ${resp.status}"
                return "Token exchange failed: ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "Error during token exchange: ${e.message}"
        return "Error during token exchange: ${e.message}"
    }
}

def browseFolders(def params) {
    log.debug "Parameters: ${params}"
    def currentPath = params?.folderPath ?: state.selectedFolder ?: ""
    if (params?.up == "true") {
        currentPath = currentPath.tokenize("/").init().join("/")
        if (currentPath) currentPath = "/" + currentPath
    }

    log.debug "Using folder: ${currentPath}"

    dynamicPage(name: "browseFolders", title: "Select Dropbox Folder", nextPage: "mainPage", install: false, uninstall: false) {
        section("Current Folder: ${currentPath ?: 'Root'}") {
            if (currentPath && currentPath != "") {
                href(
                    name: "goUp",
                    title: "Go Up",
                    description: "Go to parent folder",
                    page: "browseFolders",
                    params: [folderPath: currentPath, up: "true"]
                )
            }

            def entries = getDropboxContents(currentPath)
            entries.findAll { it[".tag"] == "folder" }.each { folder ->
                log.debug "Found folder: ${folder.name} at path: ${folder.path_display}"
                href(
                    name: "openFolder_${folder.name}",
                    title: folder.name,
                    page: "browseFolders",
                    params: [folderPath: folder.path_display]
                )
            }
            log.debug "Found ${entries.size()} entries in folder: ${currentPath}"

            href(
                name: "selectThisFolder",
                title: "Use This Folder",
                description: currentPath ?: "Root",
                page: "mainPage",
                params: [selectedFolder: currentPath]
            )
        }
    }
}

def generateCodeVerifier() {
    return UUID.randomUUID().toString().replaceAll("-", "").take(32)
}

def generateCodeChallenge(String codeVerifier) {
    def digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.bytes)
    return digest.encodeBase64().toString().replaceAll("[+/=]", "")
}

def oauthInitUrl() {
    log.debug "Generated code verifier: ${state.codeVerifier}"
    def codeChallenge = generateCodeChallenge(state.codeVerifier)

// https://www.dropbox.com/oauth2/authorize
//      ?client_id=ABC
//      &redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcallback
//      &response_type=code
//      &code_challenge=XYZ
//      &code_challenge_method=S256
//      &token_access_type=offline

    def responseParams = getHubUID() + "/apps/" + app.id + "/handleAuth?access_token=" + state.accessToken

    def url = "https://www.dropbox.com/oauth2/authorize" +
        "?client_id=${CLIENT_ID}" +
        "&response_type=code" +
        "&code_challenge=${codeChallenge}" +
        "&code_challenge_method=S256" +
        "&token_access_type=offline"
//        "&redirect_uri=" + URLEncoder.encode("https://cloud.hubitat.com/oauth/stateredirect", "UTF-8") +
//        "&state=" + URLEncoder.encode(responseParams, "UTF-8")

    log.debug "Dropbox OAuth URL: ${url}"
    return url
}

def oauthCallback() {
    def code = params.code
    def stateParam = params.state

    if (!code) {
        log.error "missing authorization code: ${params}"
        return render([text: "Authorization failed or denied."])
    }
    log.debug "oauthCallback: params: ${params}"

// URL: https://api.dropboxapi.com/oauth2/token

//  code: XYZ
//  grant_type: authorization_code
//  client_id: ID
//  redirect_uri: http://localhost:8080/callback
//  code_verifier: ABC

    def tokenParams = [
        uri : "https://api.dropboxapi.com/oauth2/token",
        body: [
            code         : code,
            grant_type   : "authorization_code",
            client_id    : CLIENT_ID,
            code_verifier: state.codeVerifier
        ]
    ]

    log.debug "oauthCallback: TOKEN URL: ${tokenParams}"

    try {
        httpPost(tokenParams) { resp ->
            log.debug "oauthCallback: response: ${resp.status}, data: ${resp.data}"
            if (resp.status == 200) {
                state.dropbox = [
                    accessToken : resp.data.access_token,
                    refreshToken: resp.data.refresh_token,
                    obtainedAt  : now(),
                    expiresIn   : resp.data.expires_in
                ]
                state.dropboxAuthStatus = "Connected"
                initialize()
                render([text: "Authorization successful! You can now close this tab."])
            } else {
                log.error "Token exchange failed: ${resp.status}"
                render([text: "Failed to exchange code for token."])
            }
        }
    } catch (Exception e) {
        log.error "OAuth callback error: ${e.message}"
        render([text: "Error: ${e.message}"])
    }
}

def isTokenValid() {
    return (state.dropbox?.accessToken != null)
}

def installed() {
    log.debug "installed"
    initialize()
}

def updated() {
    log.debug "updated"
    initialize()
}

def initialize() {
    log.debug "initialize"
    unschedule()

    // 1 time only creation
    if (!state.accessToken) createAccessToken()
    if (!state.codeVerifier) state.codeVerifier = generateCodeVerifier()

    if (isTokenValid()) {
        def updateInterval = settings?.updateInterval ?: 15
        log.debug "update interval: ${updateInterval} minutes"
        schedule("0 0/${updateInterval} * * * ?", updateImage)
        //runEveryXMinutes(updateInterval, updateImage)
        if (!getChildDevice("dropboxImageDevice")) {
            addChildDevice("jpage4500", "Dropbox Album Device", "dropboxImageDevice", [name: "Dropbox Image"])
        }
        updateImage()
    }
}

def updateImage(Map options = [:]) {
    if (!isTokenValid()) {
        log.error "invalid token"
        return
    }
    def path = state.selectedFolder ?: ""
    def images = getDropboxContents(path)?.findAll { it.name.toLowerCase() =~ /\.(jpg|jpeg|png|gif)$/ }
    if (!images || images.isEmpty()) return

    if (options.next) {
        state.currentImageIndex = (state.currentImageIndex ?: 0) + 1
    } else if (options.prev) {
        state.currentImageIndex = (state.currentImageIndex ?: 0) - 1
    }

    state.currentImageIndex = (state.currentImageIndex ?: 0) % images.size()
    if (state.currentImageIndex < 0) state.currentImageIndex += images.size()

    def selected = images[state.currentImageIndex]
    log.debug "updateImage: selected: ${selected.name}, path: ${selected.path_display}, size: ${selected.size}"
    def body = [path: selected.path_display]
    try {
        httpPost([
            uri    : "https://api.dropboxapi.com/2/files/get_temporary_link",
            headers: [
                "Authorization": "Bearer ${state.dropbox.accessToken}",
                "Content-Type" : "application/json"
            ],
            body   : new JsonBuilder(body).toString()
        ]) { resp ->
            if (resp.status == 200) {
                log.debug "URL: ${resp.data.link}"
                def child = getChildDevice("dropboxImageDevice")
                child?.sendEvent(name: "image", value: resp.data.link, displayed: true)
                child?.sendEvent(name: "lastUpdatedMs", value: now())
            } else {
                log.error "get_temporary_link failed: ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "Image update error: ${e.message}"
    }
}

def getDropboxContents(String folderPath) {
    try {
        def body = [path: folderPath ?: ""]
        def result = null
        httpPost([
            uri    : "https://api.dropboxapi.com/2/files/list_folder",
            headers: [
                "Authorization": "Bearer ${state.dropbox.accessToken}",
                "Content-Type" : "application/json"
            ],
            body   : new JsonBuilder(body).toString()
        ]) { resp ->
            if (resp.status == 200) {
                result = resp.data.entries
            } else if (resp.status == 401) {
                log.warn "Access token expired. Attempting to refresh token."
                if (refreshAccessToken()) {
                    // Retry the request after refreshing the token
                    httpPost([
                        uri    : "https://api.dropboxapi.com/2/files/list_folder",
                        headers: [
                            "Authorization": "Bearer ${state.dropbox.accessToken}",
                            "Content-Type" : "application/json"
                        ],
                        body   : new JsonBuilder(body).toString()
                    ]) { retryResp ->
                        if (retryResp.status == 200) {
                            result = retryResp.data.entries
                        } else {
                            log.error "list_folder failed after token refresh: ${retryResp.status}"
                        }
                    }
                } else {
                    log.error "Token refresh failed. Unable to proceed."
                }
            } else {
                log.error "list_folder failed: ${resp.status}"
            }
        }
        return result
    } catch (Exception e) {
        log.error "list_folder exception: ${e.message}"
        // Handle 401 in exception message as well
        if (e.message?.contains("status code: 401")) {
            log.warn "Caught 401 Unauthorized in exception. Attempting to refresh token."
            if (refreshAccessToken()) {
                try {
                    def body = [path: folderPath ?: ""]
                    def result = null
                    httpPost([
                        uri    : "https://api.dropboxapi.com/2/files/list_folder",
                        headers: [
                            "Authorization": "Bearer ${state.dropbox.accessToken}",
                            "Content-Type" : "application/json"
                        ],
                        body   : new JsonBuilder(body).toString()
                    ]) { retryResp ->
                        if (retryResp.status == 200) {
                            result = retryResp.data.entries
                        } else {
                            log.error "list_folder failed after token refresh (catch): ${retryResp.status}"
                        }
                    }
                    return result
                } catch (Exception ex) {
                    log.error "Retry after token refresh failed: ${ex.message}"
                    return []
                }
            } else {
                log.error "Token refresh failed in catch block. Unable to proceed."
                return []
            }
        }
        return []
    }
}

def refreshAccessToken() {
    def refreshToken = state.dropbox?.refreshToken

    if (!refreshToken) {
        log.error "No refresh token available."
        state.dropboxAuthStatus = null
        return false
    }

    def tokenParams = [
        uri : "https://api.dropboxapi.com/oauth2/token",
        body: [
            grant_type   : "refresh_token",
            refresh_token: refreshToken,
            client_id    : CLIENT_ID
        ]
    ]

    try {
        httpPost(tokenParams) { resp ->
            if (resp.status == 200) {
                state.dropbox.accessToken = resp.data.access_token
                state.dropbox.obtainedAt = now()
                state.dropbox.expiresIn = resp.data.expires_in
                log.debug "Access token refreshed successfully."
                return true
            } else {
                log.error "Token refresh failed: ${resp.status}"
                return false
            }
        }
    } catch (Exception e) {
        log.error "Token refresh exception: ${e.message}"
        return false
    }
}

def showNextImage() {
    log.debug "Showing next image"
    // Add logic to determine and display the next image
    updateImage(next: true)
}

def showPreviousImage() {
    log.debug "Showing previous image"
    // Add logic to determine and display the previous image
    updateImage(prev: true)
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

private logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug "$msg"
    }
}