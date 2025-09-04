// hubitat start
// hub: 192.168.0.200
// type: app
// id: 886
// hubitat end

/*
 * Hubitat App - Google Drive Image Slideshow
 * Refactored from original Google Photos version
 * Uses Google Drive API (drive)
 */

import groovy.json.JsonSlurper

definition(
    name: 'Google Drive Album App',
    namespace: 'jpage4500',
    author: 'Joe Page',
    description: 'Display a slideshow of images from a Google Drive folder on Hubitat Dashboard',
    category: 'Convenience',
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
        [GET: "handleAuthRedirect"]
    }
}

def updated() {
    if (state.currentPath) {
        log.info "Google Drive Album (${state.currentPath}) updating"
        app.updateLabel("Google Drive Album (${state.currentPath})")
        dev = getChildDevice(state.deviceId)
        if (dev) {
            dev.setLabel("Google Drive Album (${state.currentPath})")
        }
        rescheduleLogin()
        unschedule(getNextPhoto)
        resume()
        if (refreshPhotosNightly) {
            schedule('0 0 23 ? * *', loadPhotos)
        }
    }
}

def installed() {
    log.info "Google Drive Album installed"
    subscribe(location, 'systemStart', initialize)
    state.albumNames = []
    resume()
}

def uninstalled() {
    log.info "Google Drive Album App (${state.currentPath}) uninstalling"
    unschedule()
    unsubscribe()
    if (state.deviceId) {
        deleteChildDevice(state.deviceId)
    }
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
    logDebug("refreshLogin: ${params}")
    try {
        httpPost(params) { response ->
            logDebug("refreshLogin: code:${response.status}, json: ${response.data}")
            if (response.status == 200) {
                if (response.data.refresh_token) {
                    state.googleRefreshToken = response.data.refresh_token
                }
                state.googleAccessToken = response.data.access_token
                def expiresIn = response.data.expires_in
                state.expiresIn = expiresIn
                log.info("Token refreshed successfully. Expires in ${expiresIn} seconds.")
                unschedule(refreshLogin)
                runIn(expiresIn - 60, refreshLogin) // Refresh 1 minute before expiration
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        def errorCode = e.response?.status
        if (errorCode == 401) {
            log.error("refreshLogin: Refreshing access token failed. Please re-authenticate: ${e.response.data}")
            logout()
        } else {
            log.error("refreshLogin: ERROR: http:${errorCode}, ${e.getLocalizedMessage()}: ${e.response.data}")
        }
    }
}

def logout() {
    state.googleAccessToken = null
    state.googleRefreshToken = null
}

def pausePhotos() {
    logDebug('Pausing slideshow')
    unschedule(getNextPhoto)
}

def resume() {
    if (refreshInterval) {
        logDebug("Resuming slideshow with interval: ${refreshInterval} ${refreshUnits ?: 'seconds'}")
        if (refreshUnits == 'seconds' || refreshUnits == null) {
            if (refreshInterval < 60) {
                def sec = (new Date().getSeconds() % refreshInterval)
                schedule("${sec}/${refreshInterval} * * ? * *", getNextPhoto)
            } else {
                runEvery1Minute(getNextPhoto)
            }
        } else {
            if (refreshInterval < 60) {
                def ts = new Date()
                def sec = ts.getSeconds()
                def min = (ts.getMinutes() % refreshInterval)
                schedule("${sec} ${min}/${refreshInterval} * ? * *", getNextPhoto)
            } else {
                runEvery1Hour(getNextPhoto)
            }
        }
    }
}

def isBackSelected() {
    return folderToUse == '__BACK__' || folderToUse == '.. (Back)'
}

def mainPage() {
    //logDebug("mainPage: folderToUse=${folderToUse}, currentParentId=${state.currentParentId}, lastSelectedFolder=${state.lastSelectedFolder}")
    if (isBackSelected()) {
        logDebug("Going back to parent folder")
        goToParentFolder()
        return mainPage()
    } else if (state.folderNames?.containsKey(folderToUse)) {
        def folder = state.folderNames[folderToUse]
        if (folder?.id && state.lastSelectedFolder != folder.id) {
            state.lastSelectedFolder = folder.id
            getFolders(folder.id)
        }
    }

    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            href name: "myHref", url: "https://joe-page-software.gitbook.io/hubitat-dashboard/tiles/hd+-companion-app-driver", title: "Step-by-step instructions", style: "external"
        }
        section(header("Google Drive Login")) {
            input 'credentials', 'text', title: 'Google credentials.json', required: true, defaultValue: '', submitOnChange: true
            getAuthLink()
        }

        if (state.googleAccessToken) {
            section(header("Select Folder")) {
                input 'getFolders', 'button', title: 'Load Google Drive Folders'

                if (state.currentPath && (state.currentParentId || state.folderNames)) {
                    def sortedFolderNames = state.folderNames?.keySet()?.sort()
                    def sortedMap = sortedFolderNames?.collectEntries { [(it): it] }
                    if (state.currentParentId) {
                        sortedMap = ['.. (Back)': '__BACK__'] + sortedMap
                    }
                    input 'folderToUse', 'enum', title: 'Google Drive Folder', required: false, options: sortedMap, submitOnChange: true

                    if (state.lastSelectedFolder) {
                        paragraph "Current Path: ${state.currentPath ?: '/'}"
                        input 'getImages', 'button', title: 'Load Images From Folder'

                        if (state.photos) {
                            paragraph "Loaded: ${state.photos.size()} images"
                        }
                    }
                }
            }
        }

        section(header("Settings")) {
            if (state.photos) {
                input 'refreshInterval', 'number', title: 'Refresh interval', defaultValue: 60, range: '2..60', required: true, submitOnChange: true
                input 'refreshUnits', 'enum', title: 'Refresh interval -- units', defaultValue: 'seconds', options: ['seconds', 'minutes'], required: true, submitOnChange: true
                input 'shuffle', 'bool', title: 'Shuffle images?', defaultValue: false
            }
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: true, submitOnChange: true
        }
    }
}

def getAuthLink() {
    if (credentials) {
        def creds = getCredentials()
        // accessToken is required for OAuth2
        if (!state.accessToken) {
            createAccessToken()
            log.debug("Created access token: ${state.accessToken}")
        }
        href(
            name: 'authHref',
            title: 'Authorize Google Drive',
            url: 'https://accounts.google.com/o/oauth2/v2/auth?' +
                'redirect_uri=https://cloud.hubitat.com/oauth/stateredirect' +
                '&state=' + getHubUID() + '/apps/' + app.id + '/handleAuth?access_token=' + state.accessToken +
                '&access_type=offline&prompt=consent&client_id=' + creds?.client_id +
                '&response_type=code&scope=https://www.googleapis.com/auth/drive.readonly',
            description: 'Click this link to authorize with Google Drive',
            style: "external"
        )

        if (state.googleAccessToken) {
            paragraph "Status: Logged in"
        } else {
            paragraph "Status: Not Logged In"
        }
    }
}

def getCredentials() {
    try {
        return new JsonSlurper().parseText(credentials).web
    } catch (Throwable e) {
        //ignore -- this is thrown when the App first loads, before credentials can be entered
        return [:]
    }
}

def handleAuthRedirect() {
    // params: [access_token:TOKEN, code:CODE, state:HUB_ID/apps/APP_ID/handleAuth?access_token=TOKEN]
    def code = params.code
    // NOTE: not sure what params.access_token is used for
    def accessToken = params.access_token
    log.info "handleAuthRedirect: ${code}, params: ${params}"
    login(code)
    render contentType: 'text/html', data: "<html><body>Authorization complete. You may close this tab.</body></html>", status: 200
}

private logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug "$msg"
    }
}

def login(code) {
    def creds = getCredentials()
    def params = [
        uri : 'https://oauth2.googleapis.com/token',
        body: [
            code         : code,
            client_id    : creds.client_id,
            client_secret: creds.client_secret,
            redirect_uri : 'https://cloud.hubitat.com/oauth/stateredirect',
            grant_type   : 'authorization_code'
        ]
    ]
    logDebug("login: ${params}")
    try {
        httpPost(params) { response ->
            logDebug("login: response: ${response.status}, ${response.data}")
            if (response.status == 200) {
                state.googleAccessToken = response.data.access_token
                state.googleRefreshToken = response.data.refresh_token
                def refreshTokenExpiresIn = response.data.refresh_token_expires_in
                logDebug("login: Refresh token expires in ${refreshTokenExpiresIn} seconds")
                // TODO: get/use token expiration time
                state.expiresIn = response.data.expires_in ?: 3600 // Default to 1 hour if not provided
                if (state.expiresIn) {
                    // Refresh 1 minute before expiration
                    Integer runInSecs = state.expiresIn - 60;
                    log.info("login: Expires in ${state.expiresIn} secs")
                    if (runInSecs > 0) {
                        unschedule(refreshLogin)
                        runIn(runInSecs, refreshLogin)
                    }
                }
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("login: failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }

}

def appButtonHandler(btn) {
    if (btn == 'getFolders') getFolders()
    if (btn == 'getImages') loadImages()
}

def getFolders(parentId = 'root') {
    if (!state.pathStack) state.pathStack = []
    if (parentId == 'root') {
        state.pathStack = []
        state.currentPath = '/'
    } else {
        def folder = state.folderNames.find { it.value.id == parentId }
        if (folder) {
            state.pathStack << folder.key
            state.currentPath = '/' + state.pathStack.join('/')
        }
    }
    state.currentParentId = parentId == 'root' ? null : parentId
    def uri = 'https://www.googleapis.com/drive/v3/files'
    def headers = [Authorization: "Bearer ${state.googleAccessToken}"]
    def query = "'${parentId}' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed=false"
    def params = [
        uri        : uri,
        headers    : headers,
        contentType: 'application/json',
        query      : [
            q       : query,
            fields  : 'files(id,name,parents)',
            pageSize: 100
        ]
    ]

    logDebug("getFolders: ${params}")
    try {
        httpGet(params) { resp ->
            logDebug("getFolders: response: ${resp.status}, ${resp.data}")
            if (resp.status == 200) {
                def folders = resp.data.files
                logDebug("getFolders: GOT ${folders.size()} folders, parentId: ${parentId}")
                state.folderNames = folders.collectEntries { [(it.name): [id: it.id, parents: it.parents ?: []]] }
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("getFolders: failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }
}

def listPermissions(fileId) {
    def uri = "https://www.googleapis.com/drive/v3/files/${fileId}/permissions"
    def headers = [Authorization: "Bearer ${state.googleAccessToken}"]
    def params = [
        uri        : uri,
        headers    : headers,
        contentType: 'application/json',
        query      : [fields: 'permissions(id,role,type,emailAddress)']
    ]

    logDebug("listPermissions: ${params}")
    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                def permissions = resp.data.permissions
                logDebug("listPermissions: Found ${permissions.size()} permissions for file ${fileId}")
                permissions.each { perm ->
                    log.info("Permission: ID=${perm.id}, Role=${perm.role}, Type=${perm.type}, Email=${perm.emailAddress ?: 'N/A'}")
                }
                return permissions
            } else {
                log.error("listPermissions: Failed to list permissions. Status: ${resp.status}")
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("listPermissions: failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }
    return null
}

/**
 * update file permissions to make it public for 1 hour
 */
def updatePermissions(fileId) {
    def uri = "https://www.googleapis.com/drive/v3/files/${fileId}/permissions?supportsAllDrives=true"
    def headers = [Authorization: "Bearer ${state.googleAccessToken}"]
    def expirationTime = new Date(now() + 3600000).format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('UTC'))
    // 1 hour from now
    def body = [
        type                 : 'anyone',
        role                 : 'reader',
        expirationTime       : expirationTime,
        sendNotificationEmail: false,
        transferOwnership    : false
    ]
//    body       : new groovy.json.JsonBuilder(body).toString()

    def params = [
        uri        : uri,
        headers    : headers,
        contentType: 'application/json',
        body       : '{"type": "anyone", "role": "reader"}'
    ]

    logDebug("makeFilePublic: ${params}")
    try {
        httpPost(params) { resp ->
            if (resp.status == 200 || resp.status == 204) {
                logDebug("makeFilePublic: File ${fileId} is now public.")
            } else {
                log.error("makeFilePublic: Failed to make file public. Status: ${resp.status}")
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        def errorCode = e.response?.status
        def errorMessage = e.getLocalizedMessage()
        if (errorCode == 401) {
            log.warn("makeFilePublic: Unauthorized. Refreshing access token.")
            refreshLogin()
        } else {
            log.error("makeFilePublic: Error: http:${errorCode}: ${errorMessage}: ${e.response.data}")
        }
    }
}

def loadImages(pageToken = null) {
    if (!state.lastSelectedFolder) {
        log.error("loadImages: No last selected folder set.")
        return
    }

    def folderId = state.lastSelectedFolder
    if (!state.photos) state.photos = []

    def uri = 'https://www.googleapis.com/drive/v3/files'
    def headers = [Authorization: "Bearer ${state.googleAccessToken}"]
    def query = "'${folderId}' in parents and mimeType contains 'image/' and trashed=false"
    def params = [
        uri        : uri,
        headers    : headers,
        contentType: 'application/json',
        query      : [
            q        : query,
            fields   : 'files(id,name,webContentLink,webViewLink)',
            pageSize : 100,
            pageToken: pageToken
        ]
    ]

    logDebug("loadImages: ${params}")
    try {
        httpGet(params) { resp ->
            logDebug("loadImages: response: ${resp.status}, ${resp.data}")
            if (resp.status == 200) {
                def photos = resp.data.files
                if (photos.isEmpty()) {
                    log.warn("loadImages: No images found in the folder.")
                } else {
                    state.photos = photos
                    if (shuffle) Collections.shuffle(state.photos)
                    state.index = 0
                    getNextPhoto()
                }
            } else {
                log.error("loadImages: Failed to load images. Status: ${resp.status}")
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("loadImages: failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }
}

def goToParentFolder() {
    if (state.pathStack && state.pathStack.size() > 0) {
        state.pathStack.pop()
        state.currentPath = '/' + state.pathStack.join('/')
    } else {
        state.currentPath = '/'
    }
    folderToUse = null
    def parentId = state.pathStack.size() > 0 ? state.folderNames[state.pathStack[-1]]?.id : 'root'
    getFolders(parentId)
}

def getNextPhoto() {
    if (!state.photos || state.photos.size() == 0) {
        log.warn("getNextPhoto: No photos available in the album.")
        return
    }
    def index = (state.index ?: 0) + 1
    if (index >= state.photos.size()) index = 0
    state.index = index
    def photo = state.photos[index]
    logDebug("getNextPhoto: index=${index}, ${photo}")
    updateDevice(photo);
}

def getPrevPhoto() {
    if (!state.photos || state.photos.size() == 0) return
    def index = (state.index ?: 0) - 1
    if (index < 0) index = state.photos.size() - 1
    state.index = index
    def photo = state.photos[index]
    logDebug("getPrevPhoto: index=${index}, ${photo}")
    updateDevice(photo);
}

def updateDevice(photo) {
    //listPermissions(photo.id)
    //updatePermissions(photo.id)
    if (!state.deviceId) {
        state.deviceId = UUID.randomUUID().toString()
    }
    def dev = getChildDevice(state.deviceId)
    if (!dev) {
        log.info "Creating new Google Drive Album Device: ${state.deviceId}"
        dev = addChildDevice('jpage4500', "Google Drive Album Device", state.deviceId)
    }
    // https://drive.google.com/uc?id=${file.id}
    String url = "https://drive.google.com/uc?id=${photo.id}"
    dev?.sendEvent(name: 'image', value: photo.webContentLink)
    dev?.sendEvent(name: 'token', value: state.googleAccessToken)
    dev?.sendEvent(name: 'name', value: photo.name)
    dev?.sendEvent(name: 'mediaType', value: 'photo')
    dev?.sendEvent(name: 'html', value: '<div style="box-sizing: content-box"><img style="height: 100%; width: 100%; object-fit: contain" src="' + "${photo.webContentLink}" + '" /></div>')
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
