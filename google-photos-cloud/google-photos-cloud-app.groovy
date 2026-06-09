import groovy.transform.Field

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** Google Photos Cloud App **
 *
 * Slideshow of Google Photos for a Hubitat Dashboard that works AFTER Google's 2025 API changes.
 *
 * Background:
 *   Google removed the photoslibrary.readonly / .sharing / photoslibrary scopes on Mar 31, 2025, so an app can no
 *   longer read a user's existing library or albums. The only architecture that still works:
 *     1. PICK    - user selects photos with the new Photos Picker REST API (no JS widget, no Developer Key)
 *     2. UPLOAD  - app re-uploads the picked bytes into an APP-CREATED album (photoslibrary.appendonly)
 *     3. ROTATE  - app reads that album forever via photoslibrary.readonly.appcreateddata
 *   Because the app uploads the photos itself they become "app-created data" the app keeps access to, and the
 *   resulting Library baseUrl is browser-fetchable (no auth header) so it drops straight into a dashboard <img>.
 *
 * Multiple albums:
 *   Each child device maps to one Google album. The child's label drives the album name, e.g.
 *   "Kids" -> "HUBITAT-KIDS".
 *
 *  Changes:
 *  1.0.0 - 06/08/26 - initial version
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

definition(
    name: 'Google Photos Cloud',
    namespace: 'jpage4500',
    author: 'Joe Page',
    description: 'Google Photos slideshow for Hubitat dashboards (Picker + app-created albums)',
    importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/google-photos-cloud/google-photos-cloud-app.groovy',
    category: 'Utility',
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
        action: [ GET: "handleAuthRedirect" ]
    }
}

@Field static final String CHILD_DRIVER = 'Google Photos Cloud Device'
@Field static final String TOKEN_URL = 'https://www.googleapis.com/oauth2/v4/token'
@Field static final String PICKER_BASE = 'https://photospicker.googleapis.com/v1'
@Field static final String LIBRARY_BASE = 'https://photoslibrary.googleapis.com/v1'
@Field static final List SCOPES = [
    'https://www.googleapis.com/auth/photospicker.mediaitems.readonly',
    'https://www.googleapis.com/auth/photoslibrary.appendonly',
    'https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata'
]

private logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug "$msg"
    }
}

// ----------------------------------------------------------------------------
// UI
// ----------------------------------------------------------------------------
def mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            href name: "instructionsHref",
                url: "https://joe-page-software.gitbook.io/hubitat-dashboard/tiles/google-photos",
                title: "Step-by-step instructions",
                style: "external"
        }
        section(header("Google Setup")) {
            input 'clientId', 'text', title: 'Client ID', required: true, defaultValue: '', submitOnChange: true
            input 'clientSecret', 'text', title: 'Client Secret', required: true, defaultValue: '', submitOnChange: true
            showAuthorizeButton()
            showAuthorizedText()
        }
        section(header("Albums / Devices")) {
            showChildren()
        }
        section(header("Slideshow Settings")) {
            input 'maxSize', 'number', title: 'Max image size (px, longest edge)', defaultValue: 2048, submitOnChange: true
            input 'refreshInterval', 'number', title: 'Refresh interval', defaultValue: 60, range: '2..3600', required: true, submitOnChange: true
            input 'refreshUnits', 'enum', title: 'Refresh interval -- units', defaultValue: 'seconds', options: ['seconds', 'minutes'], required: true, submitOnChange: true
            input 'shuffle', 'bool', title: 'Shuffle photo order?', defaultValue: false, submitOnChange: true
            input 'refreshPhotosNightly', 'bool', title: 'Re-sync album contents nightly?', defaultValue: false, submitOnChange: true
        }
        section(header("Other")) {
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
        }
    }
}

def showAuthorizeButton() {
    if (isEmpty(clientId) || isEmpty(clientSecret)) return
    // createAccessToken() throws "OAuth is not enabled for this App" if OAuth wasn't enabled in the
    // Apps Code editor. An app can't enable its own OAuth (Hubitat security gate), so catch the failure
    // and show instructions instead of letting the whole page error out.
    if (state?.accessToken == null) {
        try {
            createAccessToken()
            logDebug("showAuthorizeButton: created endpoint accessToken")
        } catch (e) {
            log.error("showAuthorizeButton: could not create access token (OAuth likely not enabled): ${e}")
        }
    }
    if (state?.accessToken == null) {
        showMessage("OAuth is not enabled for this app. In Hubitat go to <b>Apps Code</b> &rarr; open " +
            "<b>Google Photos Cloud</b> &rarr; click <b>OAuth</b> (top-right) &rarr; <b>Enable OAuth in App</b> " +
            "&rarr; <b>Update</b>. Then reopen this app and Authorize.", true)
        return
    }
    def scopeStr = URLEncoder.encode(SCOPES.join(' '), 'UTF-8')
    href(
        name: 'authHref',
        title: 'Authorize App',
        url: 'https://accounts.google.com/o/oauth2/v2/auth?' +
            'redirect_uri=https://cloud.hubitat.com/oauth/stateredirect' +
            '&state=' + getHubUID() + '/apps/' + app.id + '/handleAuth?access_token=' + state.accessToken +
            '&access_type=offline&prompt=consent&client_id=' + clientId +
            '&response_type=code&scope=' + scopeStr,
        description: 'Click to authorize app with Google Photos'
    )
}

def showAuthorizedText() {
    if (state?.googleAccessToken != null) {
        showMessage("Authorized!", false)
    } else {
        showMessage("Not Authorized - fill in required fields -> Save -> Authorize", true)
    }
    if (!isEmpty(state?.error)) {
        showMessage(state.error, true)
    }
}

def showChildren() {
    if (!isEmpty(state?.albumNote)) {
        showMessage(state.albumNote, false)
        state.albumNote = null
    }
    def children = getChildDevices()
    // auto-refresh the page while EITHER a picker session is open (waiting for the user to pick) OR an upload
    // is running - so we catch the upload starting (it begins only after the user picks) and show its progress.
    if (children) {
        children.each { child ->
            def dni = child.deviceNetworkId
            def info = childState(dni)
            def count = (info?.photos ?: []).size()
            paragraph "<b>${child.label}</b> &rarr; album <code>${info?.title ?: '(not created)'}</code> &mdash; ${count} photo(s)"
            if (info?.uploading) {
                paragraph "<b>&#8635; Uploading ${info.uploadDone ?: 0}/${info.uploadTotal ?: 0}&hellip;</b>"
            }
            // Add / Refresh / Delete on one row (Hubitat 12-col grid: 4 + 4 + 4).
            // Refresh re-syncs the photo list from the Google album so photos removed/deleted there drop out.
            input "pick_${dni}", 'button', title: "Add Photos to '${child.label}'", width: 4, submitOnChange: true
            input "refresh_${dni}", 'button', title: "Refresh '${child.label}'", width: 4, submitOnChange: true
            input "del_${dni}", 'button', title: "Delete '${child.label}'", width: 4, submitOnChange: true
            def sess = state.pickerSessions?.get(dni)
            if (sess?.pickerUri && now() < (sess.deadline ?: 0)) {
                def winName = ("gp_" + dni).replaceAll(/[^A-Za-z0-9_]/, "_")
                // open the popup once (right after Add Photos), then clear the flag so a later page render
                // doesn't reopen it - but keep the fallback link visible the whole time the session is open
                def openScript = ""
                if (sess.autoOpen) {
                    sess.autoOpen = false
                    def sessions = state.pickerSessions ?: [:]
                    sessions[dni] = sess
                    state.pickerSessions = sessions
                    openScript = "<script>window.open('${sess.pickerUri}','${winName}','width=960,height=760,menubar=no,toolbar=no,location=no,resizable=yes,scrollbars=yes');</script>"
                }
                def minsLeft = Math.max(0, Math.round((sess.deadline - now()) / 60000d) as Integer)
                def expTime = new Date(sess.deadline as Long).format('h:mm a', location.timeZone)
                // no auto-refresh - the upload runs in background; user clicks Refresh to see progress
                paragraph """${openScript}<small>&#128247; Picker is open &mdash; select photos and click Done. If the popup was blocked, <a href='${sess.pickerUri}' target='${winName}'>click here</a>.<br>Times out in ${minsLeft} min (${expTime}).<br>Upload will happen automatically when done. Click <a href="#" onclick="location.reload();return false;">Refresh</a> to view progress.</small>"""
            }
        }
    } else {
        paragraph "No albums yet. Create one below."
    }

    if (state?.googleAccessToken == null) {
        paragraph "<i>Authorize with Google first to create albums.</i>"
        return
    }
    input 'newAlbumName', 'text', title: 'New album name (e.g. "Kids")', required: false, submitOnChange: true
    input 'addChildBtn', 'button', title: 'Create Album & Device', submitOnChange: true
}

// ----------------------------------------------------------------------------
// Lifecycle
// ----------------------------------------------------------------------------
def installed() {
    log.info "Google Photos Cloud installed"
    if (state.children == null) state.children = [:]
    if (state.pickerSessions == null) state.pickerSessions = [:]
    subscribe(location, 'systemStart', initialize)
}

def updated() {
    logDebug("updated")
    if (state.children == null) state.children = [:]
    if (state.pickerSessions == null) state.pickerSessions = [:]
    rescheduleLogin()
    resume()
    unschedule(syncAllAlbums)
    if (refreshPhotosNightly) {
        schedule('0 0 23 ? * *', syncAllAlbums)
    }
}

def uninstalled() {
    log.info "Google Photos Cloud uninstalling"
    unschedule()
    unsubscribe()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize(evt) {
    logDebug("initialize: ${evt?.name} ${evt?.value}")
    rescheduleLogin()
    resume()
}

// ----------------------------------------------------------------------------
// OAuth (pattern from hd_app.groovy)
// ----------------------------------------------------------------------------
def handleAuthRedirect() {
    def authCode = params.code
    // OAuth authorization codes are single-use. A refresh/back-nav re-hits this URL with an already-used
    // code -> invalid_grant. If we're already authorized, treat that as a harmless duplicate, not a failure.
    if (isEmpty(authCode)) {
        log.warn("handleAuthRedirect: no authorization code in redirect (already authorized=${state?.googleRefreshToken != null})")
        renderAuthResult(state?.googleRefreshToken != null)
        return
    }
    log.info('handleAuthRedirect: redirect from google, exchanging code')
    unschedule(refreshLogin)
    login(authCode)
    if (state?.googleRefreshToken) {
        runEvery1Hour refreshLogin
    }
    renderAuthResult(state?.googleAccessToken != null)
}

private void renderAuthResult(boolean ok) {
    def msg = ok ?
        "Google Photos has authenticated successfully." :
        "Authorization did not complete. If you were already connected this is just a duplicate redirect (you can ignore it). Otherwise return to the app and click Authorize again."
    def builder = new StringBuilder()
    builder << "<!DOCTYPE html><html><head><title>Hubitat Elevation - Google Photos Cloud</title></head>"
    builder << "<body><p>${msg}</p>"
    builder << "<p><a href=https://${location.hub.localIP}/installedapp/configure/${app.id}/mainPage>Click here</a> to return to the App main page.</p></body></html>"
    render contentType: "text/html", data: builder.toString(), status: 200
}

def rescheduleLogin() {
    unschedule(refreshLogin)
    if (state?.googleRefreshToken) {
        refreshLogin()
        runEvery1Hour refreshLogin
    }
}

def login(String authCode) {
    if (isEmpty(clientId) || isEmpty(clientSecret)) {
        log.warn('login: clientId/clientSecret not set!')
        return
    }
    log.info('login: getting access_token from Google')
    def params = [uri: TOKEN_URL, query: [
        client_id    : clientId,
        client_secret: clientSecret,
        code         : authCode,
        grant_type   : 'authorization_code',
        redirect_uri : 'https://cloud.hubitat.com/oauth/stateredirect'
    ]]
    try {
        httpPost(params) { response -> handleLoginResponse(response) }
    } catch (groovyx.net.http.HttpResponseException e) {
        def body = "${e.response?.data}"
        // invalid_grant on a code exchange = code already used/expired. If we already have a refresh token
        // this is just a duplicate redirect (page refresh / back nav) - log it but don't flag it as an error.
        if (body.contains('invalid_grant') && state?.googleRefreshToken) {
            log.info("login: ignoring invalid_grant on duplicate redirect (already authorized)")
        } else {
            log.error("login: ${e.getLocalizedMessage()}: ${e.response?.data}")
            state.error = "Login error: ${e.getLocalizedMessage()}"
        }
    } catch (e) {
        log.error("login: ERROR: ${e}")
        state.error = "Login error: ${e}"
    }
}

def refreshLogin() {
    if (isEmpty(clientId) || isEmpty(clientSecret) || isEmpty(state?.googleRefreshToken)) {
        return
    }
    logDebug('refreshLogin: refreshing access_token')
    def params = [uri: TOKEN_URL, query: [
        client_id    : clientId,
        client_secret: clientSecret,
        refresh_token: state.googleRefreshToken,
        grant_type   : 'refresh_token'
    ]]
    try {
        httpPost(params) { response -> handleLoginResponse(response) }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("refreshLogin: ${e.getLocalizedMessage()}: ${e.response?.data}")
        state.error = "Refresh error: ${e.getLocalizedMessage()}"
    } catch (e) {
        log.error("refreshLogin: ERROR: ${e}")
        state.error = "Refresh error: ${e}"
    }
}

def handleLoginResponse(resp) {
    def respCode = resp.getStatus()
    def respJson = resp.getData()
    if (respCode == 200) {
        state.error = null
        state.lastSuccess = new Date().toString()
        logDebug("handleLoginResponse: authorized scopes: ${respJson.scope}")
    } else {
        state.error = "Auth error: ${respCode}, ${respJson}"
        log.error("handleLoginResponse: ${respCode}, ${respJson}")
    }
    if (respJson.refresh_token) {
        state.googleRefreshToken = respJson.refresh_token
    }
    if (respJson.access_token) {
        state.googleAccessToken = respJson.access_token
    }
}

private Map authHeader() {
    return [Authorization: 'Bearer ' + state.googleAccessToken]
}

// ----------------------------------------------------------------------------
// Buttons
// ----------------------------------------------------------------------------
def appButtonHandler(btn) {
    logDebug("appButtonHandler: ${btn}")
    if (btn == 'addChildBtn') {
        createAlbumAndChild(settings.newAlbumName)
    } else if (btn?.startsWith('pick_')) {
        def dni = btn.substring('pick_'.length())
        startPickerSession(dni)
    } else if (btn?.startsWith('refresh_')) {
        def dni = btn.substring('refresh_'.length())
        refreshAlbum(dni)
    } else if (btn?.startsWith('del_')) {
        def dni = btn.substring('del_'.length())
        deleteAlbumAndChild(dni)
    }
}

// re-sync the photo list from the Google album (reconciles photos the user removed/deleted there)
def refreshAlbum(String dni) {
    def before = (childState(dni)?.photos ?: []).size()
    syncAlbum(dni, null, null)
    def after = (childState(dni)?.photos ?: []).size()
    def label = getChildDevice(dni)?.label ?: 'album'
    log.info("refreshAlbum: '${label}' ${before} -> ${after} photo(s)")
    if (after == 0) {
        // album is now empty - clear the displayed image so it stops showing a removed photo
        def child = getChildDevice(dni)
        child?.sendEvent(name: 'image', value: '<img src="" />')
        child?.sendEvent(name: 'total', value: 0)
        child?.sendEvent(name: 'index', value: 0)
    }
    state.albumNote = "Refreshed '${label}': ${after} photo(s) available" + (after != before ? " (was ${before})" : "") + "."
}

// ----------------------------------------------------------------------------
// Album + child creation
// ----------------------------------------------------------------------------
def createAlbumAndChild(String name) {
    if (isEmpty(name)) {
        showMessage("Enter an album name first", true)
        return
    }
    if (state.googleAccessToken == null) {
        showMessage("Authorize with Google first", true)
        return
    }
    def title = albumTitleFor(name)
    def albumId = createAlbum(title)
    if (isEmpty(albumId)) {
        showMessage("Failed to create album '${title}' - check logs", true)
        return
    }
    def dni = UUID.randomUUID().toString()
    addChildDevice('jpage4500', CHILD_DRIVER, dni, [label: name, name: CHILD_DRIVER])
    def children = state.children ?: [:]
    children[dni] = [albumId: albumId, title: title, photos: [], index: 0, paused: false]
    state.children = children
    log.info("createAlbumAndChild: created album '${title}' (${albumId}) + child '${name}'")
    app.updateSetting('newAlbumName', [type: 'text', value: ''])
}

def deleteAlbumAndChild(String dni) {
    def info = childState(dni)
    def title = info?.title ?: '(unknown)'
    def label = getChildDevice(dni)?.label ?: title
    log.info("deleteAlbumAndChild: deleting device + state for '${label}' (album ${title})")
    // remove the child device (its uninstalled() also calls childRemoved(), which is idempotent)
    try {
        deleteChildDevice(dni)
    } catch (e) {
        log.warn("deleteAlbumAndChild: deleteChildDevice failed: ${e}")
    }
    // clean up all app state tied to this album
    def children = state.children ?: [:]; children.remove(dni); state.children = children
    state.pickerSessions?.remove(dni)
    state.uploadQueue?.remove(dni)
    // The Google Photos Library API has no albums.delete - the album shell can't be removed via API.
    state.albumNote = "Deleted device '${label}'. Note: Google's API can't delete the album itself - " +
        "remove '${title}' manually in the Google Photos app if you want it gone."
}

private String createAlbum(String title) {
    logDebug("createAlbum: ${title}")
    def albumId = null
    try {
        httpPostJson([uri: "${LIBRARY_BASE}/albums", headers: authHeader(), body: [album: [title: title]]]) { resp ->
            if (resp.status == 200) {
                albumId = resp.data?.id
            } else {
                log.error("createAlbum: ${resp.status} ${resp.data}")
            }
        }
    } catch (e) {
        log.error("createAlbum: ERROR: ${e}")
    }
    return albumId
}

// ----------------------------------------------------------------------------
// Picker session: create -> show link -> poll -> import
// ----------------------------------------------------------------------------
def startPickerSession(String dni) {
    if (state.googleAccessToken == null) {
        showMessage("Authorize with Google first", true)
        return
    }
    logDebug("startPickerSession: ${dni}")
    try {
        httpPostJson([uri: "${PICKER_BASE}/sessions", headers: authHeader(), body: [:]]) { resp ->
            if (resp.status == 200) {
                def sessions = state.pickerSessions ?: [:]
                sessions[dni] = [
                    sessionId : resp.data.id,
                    pickerUri : resp.data.pickerUri,
                    deadline  : now() + (parseDurationSec(resp.data.pollingConfig?.timeoutIn, 600) * 1000L),
                    autoOpen  : true
                ]
                state.pickerSessions = sessions
                def poll = parseDurationSec(resp.data.pollingConfig?.pollInterval, 5)
                logDebug("startPickerSession: response = ${resp.data}")
                log.info("startPickerSession: open ${resp.data.pickerUri}")
                log.info("startPickerSession: polling session ${resp.data.id} every ${poll}s")
                runIn(poll, 'pollPickerSession', [data: [dni: dni, attempt: 0]])
            } else {
                log.error("startPickerSession: ${resp.status} ${resp.data}")
                showMessage("Picker session failed: ${resp.status}", true)
            }
        }
    } catch (e) {
        log.error("startPickerSession: ERROR: ${e}")
        showMessage("Picker session error: ${e}", true)
    }
}

def pollPickerSession(data) {
    def dni = data.dni
    int attempt = (data.attempt ?: 0) + 1
    def sess = state.pickerSessions?.get(dni)
    if (!sess) {
        log.warn("pollPickerSession: no active session for ${dni}")
        return
    }
    if (now() > (sess.deadline ?: 0)) {
        log.warn("pollPickerSession: session timed out for ${dni} after ${attempt} polls")
        endPickerSession(dni)
        return
    }
    try {
        httpGet([uri: "${PICKER_BASE}/sessions/${sess.sessionId}", headers: authHeader(), contentType: 'application/json']) { resp ->
            def isSet = resp.data?.mediaItemsSet
            log.info("pollPickerSession: poll #${attempt} status=${resp.status} mediaItemsSet=${isSet}")
            if (resp.status == 200 && isSet) {
                log.info("pollPickerSession: media selected for ${dni}, importing")
                importPickedItems(dni, sess.sessionId, null)
            } else {
                def poll = parseDurationSec(resp.data?.pollingConfig?.pollInterval, 5)
                runIn(poll, 'pollPickerSession', [data: [dni: dni, attempt: attempt]])
            }
        }
    } catch (e) {
        log.error("pollPickerSession: poll #${attempt} ERROR: ${e}")
        runIn(10, 'pollPickerSession', [data: [dni: dni, attempt: attempt]])
    }
}

def importPickedItems(String dni, String sessionId, String pageToken) {
    def query = [sessionId: sessionId, pageSize: 100]
    if (pageToken) query.pageToken = pageToken
    try {
        httpGet([uri: "${PICKER_BASE}/mediaItems", headers: authHeader(), contentType: 'application/json', query: query]) { resp ->
            if (resp.status != 200) {
                log.error("importPickedItems: ${resp.status} ${resp.data}")
                return
            }
            def queue = state.uploadQueue ?: [:]
            def list = queue[dni] ?: []
            resp.data?.mediaItems?.each { item ->
                // only photos (skip video re-upload for now)
                if (item?.type == 'PHOTO' || item?.mediaFile?.mimeType?.startsWith('image/')) {
                    // skip the same photo picked more than once in this session (dedupe by picker item id)
                    if (list.any { it.pickerId == item.id }) {
                        logDebug("importPickedItems: skipping duplicate pick '${item.mediaFile?.filename}'")
                    } else {
                        list << [
                            pickerId: item.id,
                            baseUrl : item.mediaFile.baseUrl,
                            mimeType: item.mediaFile.mimeType ?: 'image/jpeg',
                            filename: item.mediaFile.filename ?: "${item.id}.jpg"
                        ]
                    }
                }
            }
            queue[dni] = list
            state.uploadQueue = queue
            if (resp.data?.nextPageToken) {
                importPickedItems(dni, sessionId, resp.data.nextPageToken)
            } else {
                log.info("importPickedItems: ${list.size()} photo(s) queued for ${dni}")
                // seed progress counters so the UI can show "Uploading X/N"
                def info = childState(dni)
                if (info != null) {
                    info.uploadTotal = list.size()
                    info.uploadDone = 0
                    info.uploading = list.size() > 0
                    putChildState(dni, info)
                }
                runInMillis(200, 'processUploadQueue', [data: [dni: dni, sessionId: sessionId]])
            }
        }
    } catch (e) {
        log.error("importPickedItems: ERROR: ${e}")
    }
}

// process one upload at a time to stay within hub execution limits
def processUploadQueue(data) {
    def dni = data.dni
    def queue = state.uploadQueue ?: [:]
    def list = queue[dni] ?: []
    if (list.isEmpty()) {
        log.info("processUploadQueue: done for ${dni}")
        def doneInfo = childState(dni)
        if (doneInfo != null) { doneInfo.uploading = false; putChildState(dni, doneInfo) }
        endPickerSession(dni)
        getNextPhoto(dni)
        return
    }
    def item = list.remove(0)
    queue[dni] = list
    state.uploadQueue = queue

    boolean ok = uploadOnePhoto(dni, item)
    def info = childState(dni)
    if (info != null) {
        info.uploadDone = (info.uploadDone ?: 0) + 1
        putChildState(dni, info)
    }
    logDebug("processUploadQueue: ${item.filename} -> ${ok ? 'ok' : 'FAIL'} (${list.size()} left)")
    runInMillis(500, 'processUploadQueue', [data: [dni: dni, sessionId: data.sessionId]])
}

private boolean uploadOnePhoto(String dni, Map item) {
    try {
        // 1) download bytes from the picked baseUrl (REQUIRES auth header).
        // Use a RESIZED version (=w-h), NOT the original (=d): picker photos already exist in the user's
        // library, and re-uploading byte-identical originals makes Google de-dupe them onto the existing
        // (non-app-created) library item, which then 404s under readonly.appcreateddata. A resized copy has
        // different bytes, so Google stores it as a genuine app-created item the app can read back.
        // Size to the configured max (longest edge), so we store at the resolution we actually display.
        def sz = maxSize ?: 2048
        byte[] bytes = downloadBytes("${item.baseUrl}=w${sz}-h${sz}")
        if (bytes == null || bytes.length == 0) {
            log.warn("uploadOnePhoto: no bytes for ${item.filename}")
            return false
        }
        // 2) upload bytes -> returns an upload token (plain text)
        String uploadToken = uploadBytes(bytes, item.mimeType, item.filename)
        if (isEmpty(uploadToken)) {
            log.warn("uploadOnePhoto: no upload token for ${item.filename}")
            return false
        }
        // 3) create the media item in this child's album
        def info = childState(dni)
        String mediaId = batchCreate(info.albumId, uploadToken, item.filename)
        if (isEmpty(mediaId)) return false
        addPhotoId(dni, mediaId)
        return true
    } catch (e) {
        log.error("uploadOnePhoto: ERROR (${item.filename}): ${e}")
        return false
    }
}

private byte[] downloadBytes(String url) {
    byte[] result = null
    httpGet([uri: url, headers: authHeader(), contentType: 'application/octet-stream', ignoreSSLIssues: true]) { resp ->
        if (resp.status == 200) {
            result = readResponseBytes(resp)
        } else {
            log.error("downloadBytes: ${resp.status}")
        }
    }
    return result
}

private String uploadBytes(byte[] bytes, String mimeType, String filename) {
    String token = null
    def headers = [
        Authorization              : 'Bearer ' + state.googleAccessToken,
        'X-Goog-Upload-Protocol'    : 'raw',
        'X-Goog-Upload-Content-Type': mimeType,
        'X-Goog-Upload-File-Name'   : filename
    ]
    httpPost([uri: "${LIBRARY_BASE}/uploads", headers: headers, contentType: 'application/octet-stream', requestContentType: 'application/octet-stream', body: bytes]) { resp ->
        if (resp.status == 200) {
            token = readResponseString(resp)?.trim()
        } else {
            log.error("uploadBytes: ${resp.status} ${resp.data}")
        }
    }
    return token
}

private String batchCreate(String albumId, String uploadToken, String filename) {
    String mediaId = null
    def body = [
        albumId      : albumId,
        newMediaItems: [[ description: filename, simpleMediaItem: [uploadToken: uploadToken] ]]
    ]
    httpPostJson([uri: "${LIBRARY_BASE}/mediaItems:batchCreate", headers: authHeader(), body: body]) { resp ->
        if (resp.status == 200) {
            def r = resp.data?.newMediaItemResults?.getAt(0)
            mediaId = r?.mediaItem?.id
            if (mediaId == null) log.error("batchCreate: no id, status=${r?.status}")
        } else {
            log.error("batchCreate: ${resp.status} ${resp.data}")
        }
    }
    return mediaId
}

def endPickerSession(String dni) {
    def sess = state.pickerSessions?.get(dni)
    if (sess?.sessionId) {
        try {
            httpDelete([uri: "${PICKER_BASE}/sessions/${sess.sessionId}", headers: authHeader()]) { resp ->
                logDebug("endPickerSession: deleted ${sess.sessionId} -> ${resp.status}")
            }
        } catch (e) {
            logDebug("endPickerSession: ${e}")
        }
    }
    def sessions = state.pickerSessions ?: [:]
    sessions.remove(dni)
    state.pickerSessions = sessions
}

// ----------------------------------------------------------------------------
// Album re-sync (optional nightly) - rebuild photo id list from the album
// ----------------------------------------------------------------------------
def syncAllAlbums() {
    getChildDevices().each { syncAlbum(it.deviceNetworkId, null, null) }
}

// Rebuild the photo id list from the Google album via mediaItems:search. Accumulates across pages into
// 'acc' and only commits at the very end - and only if non-empty - so a restricted/empty/failed search
// never wipes a working list.
def syncAlbum(String dni, String pageToken, List acc) {
    def info = childState(dni)
    if (!info?.albumId) return
    if (acc == null) acc = []
    def body = [albumId: info.albumId, pageSize: 100]
    if (pageToken) body.pageToken = pageToken
    try {
        httpPostJson([uri: "${LIBRARY_BASE}/mediaItems:search", headers: authHeader(), body: body]) { resp ->
            if (resp.status != 200) {
                log.warn("syncAlbum: ${resp.status} ${resp.data} (album search may be restricted under appcreateddata)")
                return
            }
            resp.data?.mediaItems?.each { acc << it.id }
            if (resp.data?.nextPageToken) {
                syncAlbum(dni, resp.data.nextPageToken, acc)
            } else {
                // reaching here means we got HTTP 200 (errors throw and are caught below), so an empty
                // result is a real "album is empty" - commit it. Restricted/failed searches never get here.
                info.photos = acc
                if ((info.index ?: 0) >= acc.size()) info.index = 0
                putChildState(dni, info)
                logDebug("syncAlbum: ${dni} now has ${acc.size()} photo(s)")
            }
        }
    } catch (e) {
        log.error("syncAlbum: ERROR: ${e}")
    }
}

// ----------------------------------------------------------------------------
// Slideshow (per child)
// ----------------------------------------------------------------------------
def resume() {
    unschedule(tick)
    def interval = (refreshInterval ?: 60) as Integer
    def units = refreshUnits ?: 'seconds'
    if (units == 'seconds') {
        if (interval < 60) {
            def sec = (new Date().getSeconds() % interval)
            schedule("${sec}/${interval} * * ? * *", tick)
        } else {
            runEvery1Minute(tick)
        }
    } else {
        if (interval < 60) {
            schedule("0 0/${interval} * ? * *", tick)
        } else {
            runEvery1Hour(tick)
        }
    }
    // media baseUrls expire after <=60 min and advancing (tick) refreshes them - so a separate refresh is
    // only needed when a single photo stays on screen long enough to risk expiry. For typical short intervals
    // the advance already keeps the URL fresh, so skip the extra job entirely.
    unschedule(refreshCurrentPhotos)
    def intervalSeconds = (units == 'minutes') ? interval * 60 : interval
    if (intervalSeconds >= 2700) {
        runEvery30Minutes(refreshCurrentPhotos)
        logDebug("resume: tick every ${interval} ${units} (+ 30-min url refresh - long interval)")
    } else {
        logDebug("resume: tick every ${interval} ${units}")
    }
    // refresh now so a currently-stale image is fixed immediately on save/reboot
    refreshCurrentPhotos()
}

def tick() {
    logDebug("tick: advancing slideshow(s)")
    getChildDevices().each { child ->
        def dni = child.deviceNetworkId
        if (!childState(dni)?.paused) {
            getNextPhoto(dni)
        }
    }
}

// re-fetch a fresh baseUrl for each child's CURRENT photo without advancing (keeps the dashboard image
// from expiring when the advance interval is long or the slideshow is paused/idle)
def refreshCurrentPhotos() {
    getChildDevices().each { child ->
        def dni = child.deviceNetworkId
        def info = childState(dni)
        def photos = info?.photos ?: []
        if (!photos.isEmpty()) {
            def idx = (info.index ?: 0)
            if (idx < 0 || idx >= photos.size()) idx = 0
            logDebug("refreshCurrentPhotos: ${dni} refreshing url for index=${idx}")
            getPhotoById(dni, photos[idx])
        }
    }
}

def pausePhotos(String dni) {
    def info = childState(dni)
    if (info) { info.paused = true; putChildState(dni, info) }
}

def resumePhotos(String dni) {
    def info = childState(dni)
    if (info) { info.paused = false; putChildState(dni, info) }
    getNextPhoto(dni)
}

def getNextPhoto(String dni) {
    def info = childState(dni)
    def photos = info?.photos ?: []
    if (photos.isEmpty()) { logDebug("getNextPhoto: no photos for ${dni}"); return }
    def index = (info.index ?: 0) + 1
    if (index >= photos.size()) {
        index = 0
        if (shuffle) Collections.shuffle(photos)
    }
    info.index = index
    info.photos = photos
    putChildState(dni, info)
    logDebug("getNextPhoto: ${dni} index=${index}/${photos.size() - 1} id=${photos[index]}")
    sendPosition(dni, index, photos.size())
    getPhotoById(dni, photos[index])
}

def getPrevPhoto(String dni) {
    def info = childState(dni)
    def photos = info?.photos ?: []
    if (photos.isEmpty()) { logDebug("getPrevPhoto: no photos for ${dni}"); return }
    def index = (info.index ?: 0) - 1
    if (index < 0) index = photos.size() - 1
    info.index = index
    putChildState(dni, info)
    logDebug("getPrevPhoto: ${dni} index=${index}/${photos.size() - 1} id=${photos[index]}")
    sendPosition(dni, index, photos.size())
    getPhotoById(dni, photos[index])
}

// push 1-based position + total to the child device for display
private void sendPosition(String dni, int index, int total) {
    def child = getChildDevice(dni)
    if (child == null) return
    child.sendEvent(name: 'index', value: index + 1)
    child.sendEvent(name: 'total', value: total)
}

private void getPhotoById(String dni, String id) {
    // re-fetch each tick: Library baseUrls expire ~60 min, so never cache the URL - only the id
    def params = [uri: "${LIBRARY_BASE}/mediaItems/${id}", headers: authHeader(), contentType: 'application/json']
    logDebug("getPhotoById: ${dni} GET mediaItems/${id}")
    asynchttpGet('handlePhotoGet', params, [dni: dni, id: id, params: params])
}

def handlePhotoGet(resp, data) {
    if (resp.hasError()) {
        if (resp.status == 401 && !data.isRetry) {
            log.warn('handlePhotoGet: token expired, refreshing & retrying')
            refreshLogin()
            data.isRetry = true
            data.params.headers = authHeader()
            asynchttpGet('handlePhotoGet', data.params, data)
        } else if (resp.status == 404) {
            // id no longer resolves (deleted in Google Photos, or a bad id captured at upload time).
            // Drop it from the album list so it stops breaking the rotation.
            log.warn("handlePhotoGet: 404 Not Found for id=${data.id} (${data.dni}) - removing from album list")
            removePhotoId(data.dni, data.id)
        } else {
            log.warn("handlePhotoGet: ${resp.status} ${resp.getErrorMessage()} (id=${data.id}, ${data.dni})")
        }
        return
    }
    logDebug("handlePhotoGet: ok id=${data.id} (${data.dni})")
    def json = resp.json
    def child = getChildDevice(data.dni)
    if (child == null) return
    def sz = maxSize ?: 2048
    if (json?.mediaMetadata?.containsKey('photo') || json?.mediaMetadata?.photo != null) {
        def html = '<div style="box-sizing: content-box"><img style="height: 100%; width: 100%; object-fit: contain" src="' + "${json.baseUrl}=w${sz}-h${sz}" + '" /></div>'
        child.sendEvent(name: 'image', value: html)
        child.sendEvent(name: 'mediaType', value: 'photo')
    } else if (json?.mediaMetadata?.video != null) {
        def html = '<video autoplay loop><source src="' + "${json.baseUrl}=dv" + '" type="' + "${json.mimeType}" + '"></video>'
        child.sendEvent(name: 'image', value: html)
        child.sendEvent(name: 'mediaType', value: 'video')
    }
}

// ----------------------------------------------------------------------------
// Per-child state helpers
// ----------------------------------------------------------------------------
private Map childState(String dni) {
    return (state.children ?: [:])[dni]
}

private void putChildState(String dni, Map info) {
    def children = state.children ?: [:]
    children[dni] = info
    state.children = children
}

private void addPhotoId(String dni, String mediaId) {
    def info = childState(dni)
    if (info == null) return
    def photos = info.photos ?: []
    if (!photos.contains(mediaId)) photos << mediaId
    info.photos = photos
    putChildState(dni, info)
}

private void removePhotoId(String dni, String mediaId) {
    def info = childState(dni)
    if (info == null) return
    def photos = info.photos ?: []
    if (photos.remove(mediaId)) {
        // keep index in range after removal
        if ((info.index ?: 0) >= photos.size()) info.index = 0
        info.photos = photos
        putChildState(dni, info)
        logDebug("removePhotoId: removed ${mediaId} from ${dni}, ${photos.size()} photo(s) remain")
    }
}

// called by child when it is being removed
def childRemoved(String dni) {
    def children = state.children ?: [:]
    children.remove(dni)
    state.children = children
}

// ----------------------------------------------------------------------------
// Utilities
// ----------------------------------------------------------------------------
static String albumTitleFor(String label) {
    def slug = label.toUpperCase().replaceAll(/[^A-Z0-9]+/, "_").replaceAll(/^_+|_+$/, "")
    return "HUBITAT-" + slug
}

// google.protobuf.Duration serializes like "5s" / "300s"
private long parseDurationSec(String dur, long fallback) {
    if (isEmpty(dur)) return fallback
    def m = (dur =~ /([0-9.]+)/)
    return m.find() ? Math.max(1, (m.group(1) as BigDecimal).intValue()) : fallback
}

// NOTE: Hubitat's sandbox blocks ClassExpressions like java.io.InputStream and calls like getClass().
// Use duck typing only: Strings have .getBytes; stream-like data supports .read(buf); byte[] coerces via "as byte[]".
private byte[] readResponseBytes(resp) {
    def d = resp?.data
    if (d == null) return null
    if (d instanceof String) return d.getBytes("ISO-8859-1")
    try {
        ByteArrayOutputStream bos = new ByteArrayOutputStream()
        byte[] buf = new byte[8192]
        int n = d.read(buf)
        while (n != -1) {
            bos.write(buf, 0, n)
            n = d.read(buf)
        }
        return bos.toByteArray()
    } catch (e) {
        // not stream-like - last resort: assume it is already a byte array
        try {
            return d as byte[]
        } catch (ignored) {
            log.error("readResponseBytes: cannot read response data: ${e}")
            return null
        }
    }
}

private String readResponseString(resp) {
    def d = resp?.data
    if (d == null) return null
    if (d instanceof String) return d
    try {
        return d.getText("UTF-8")
    } catch (e) {
        return d.toString()
    }
}

static String header(text) {
    return "<div style='color:#ffffff;font-weight: bold;background-color:#244D76FF;padding-top: 10px;padding-bottom: 10px;border: 1px solid #000000;box-shadow: 2px 3px #8B8F8F;border-radius: 10px'><image style='padding: 0px 10px 0px 10px;' src=https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/google-photos-cloud/logo.png width='40'> ${text}</div>"
}

static boolean isEmpty(text) {
    return text == null || text.toString().isEmpty()
}

def showMessage(text, boolean isError) {
    if (!isEmpty(text)) {
        def color = isError ? 'red' : 'green'
        paragraph("<p style='color:${color}; font-weight: bold'>${text}</p>")
    }
}
