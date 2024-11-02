import groovy.json.JsonSlurper

/**
 *
 *  Copyright 2021 David Kilgore. All Rights Reserved
 *
 *  This software is free for Private Use. You may use and modify the software without distributing it.
 *  If you make a fork, and add new code, then you should create a pull request to add value, there is no
 *  guarantee that your pull request will be merged.
 *
 *  You may not grant a sublicense to modify and distribute this software to third parties without permission
 *  from the copyright holder
 *  Software is provided without warranty and your use of it is at your own risk.
 *
 *  version: 0.3.3
 */

definition(
        name: 'Google Photos App',
        namespace: 'dkilgore90',
        author: 'David Kilgore',
        description: 'Provides an interface for a google photos album slideshow in a Hubitat Dashboard',
        importUrl: 'https://raw.githubusercontent.com/dkilgore90/google-photos/master/photos-app.groovy',
        category: 'Photos',
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
        getAlbumsButton()

        section {
            input 'albumToUse', 'enum', title: 'Album to use', required: false, submitOnChange: true, options: state.albumNames
        }
        section {
            paragraph "Preferred image resolution (px):"
            input 'imgWidgh', 'text', title: 'width', defaultValue: '2048', submitOnChange: true
            input 'imgHeight', 'text', title: 'height', defaultValue: '1024', submitOnChange: true
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
            input 'refreshInterval', 'number', title: 'Refresh interval', defaultValue: 60, range: '2..60', required: true, submitOnChange: true
            input 'refreshUnits', 'enum', title: 'Refresh interval -- units', defaultValue: 'seconds', options: ['seconds', 'minutes'], required: true, submitOnChange: true
            input 'shuffle', 'bool', title: 'Shuffle images each time through album?', defaultValue: false, submitOnChange: true
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
                            '&response_type=code&scope=https://www.googleapis.com/auth/photoslibrary.readonly',
                    description: 'Click this link to authorize with your Google Photos library'
            )
        }
    } else {
        section {
            paragraph "Authorization link is hidden until the required credentials.json input is provided, and App installation is saved by clicking 'Done'"
        }
    }
}

def getAlbumsButton() {
    if (state?.googleAccessToken != null) {
        section {
            input 'getAlbums', 'button', title: 'Refresh Albums List', submitOnChange: true
        }
    } else {
        section {
            paragraph "Refresh Albums button is hidden until authorization is completed."
        }
    }
}

def getPhotosButton() {
    if (state?.googleAccessToken != null && albumToUse) {
        section {
            input 'loadPhotos', 'button', title: 'Load Photos from Album', submitOnChange: true
            input 'refreshPhotosNightly', 'bool', title: 'Refresh list of photos in album nightly?', defaultValue: false, submitOnChange: true
        }
    } else {
        section {
            paragraph "Load Photos button is hidden until authorization is completed and album is selected."
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
    state.albums = []
    state.albumNames = []
    def builder = new StringBuilder()
    builder << "<!DOCTYPE html><html><head><title>Hubitat Elevation - Google Photos</title></head>"
    builder << "<body><p>Congratulations! Google Photos has authenticated successfully</p>"
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
    log.info "Google Photos App (${albumToUse}) updating"
    app.updateLabel("Google Photos App (${albumToUse})")
    dev = getChildDevice(state.deviceId)
    dev.setLabel("Google Photos App (${albumToUse})")
    rescheduleLogin()
    unschedule(getNextPhoto)
    resume()
    if (refreshPhotosNightly) {
        schedule('0 0 23 ? * *', loadPhotos)
    }
}

def installed() {
    log.info "Google Photos App installed"
    createAccessToken()
    subscribe(location, 'systemStart', initialize)
    state.albumNames = []
    resume()
    state.deviceId = UUID.randomUUID().toString()
    addChildDevice('dkilgore90', "Google Photos Device", state.deviceId)
}

def uninstalled() {
    log.info "Google Photos App (${albumToUse}) uninstalling"
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
        case 'getAlbums':
            getAlbums()
            break
        case 'loadPhotos':
            loadPhotos()
            break
        case 'getToken':
            logToken()
            break
        case 'refreshToken':
            refreshLogin()
            break
    }
}

def pausePhotos() {
    logDebug('Pausing slideshow')
    unschedule(getNextPhoto)
}

def resume() {
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

private void getAlbums(reset=true, query=[:]) {
    if (query.pageToken) {
        log.info("Retrieving next page of albums from Google Photos")
    } else {
        log.info("Retrieving albums from Google Photos")
    }
    if (reset) {
        state.albumNames = []
        state.albums = [:]
    }
    def uri = 'https://photoslibrary.googleapis.com/v1/albums'
    def headers = [ Authorization: 'Bearer ' + state.googleAccessToken ]
    def contentType = 'application/json'
    def params = [ uri: uri, headers: headers, contentType: contentType, query: query ]
    asynchttpGet(handleAlbumsList, params, [params: params])
}

def handleAlbumsList(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorJson()
        } catch (Exception ignored) {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpGet(handleAlbumsList, data.params, data)
        } else {
            log.warn("Albums-list response code: ${respCode}, body: ${respError}")
        }
    } else {
        def respJson = resp.getJson()
        albumsList = state.albums
        albumNames = state.albumNames
        respJson.albums.each {
            albumNames.add(it.title)
            def album = [:]
            album.id = it.id
            album.name = it.title
            albumsList[it.title] = album
        }
        state.albums = albumsList
        state.albumNames = albumNames
        if (respJson.nextPageToken) {
            getAlbums(false, [pageToken: respJson.nextPageToken])
        }
    }
}

def loadPhotos(reset=true, pageToken=null) {
    if (pageToken) {
        log.info("Retrieving next page of photos from album: ${albumToUse}")
    } else {
        log.info("Retrieving photos from album: ${albumToUse}")
    }
    if (reset) {
        state.photos = []
    }
    def uri = 'https://photoslibrary.googleapis.com/v1/mediaItems:search'
    def headers = [ Authorization: 'Bearer ' + state.googleAccessToken ]
    def contentType = 'application/json'
    def body = [ albumId: state.albums[albumToUse]['id'] ]
    if (pageToken) {
        body.pageToken = pageToken
    }
    def params = [ uri: uri, headers: headers, contentType: contentType, body: body ]
    asynchttpPost(handlePhotosList, params, [params: params])
}

def handlePhotosList(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorJson()
        } catch (Exception ignored) {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpPost(handlePhotosList, data.params, data)
        } else {
            log.warn("Photos-list response code: ${respCode}, body: ${respError}")
        }
    } else {
        def respJson = resp.getJson()
        photosList = state.photos
        respJson.mediaItems.each {
            photosList.add(it.id)
        }
        state.photos = photosList
        state.index = 0
        if (respJson.nextPageToken) {
            loadPhotos(false, respJson.nextPageToken)
        } else if (shuffle) {
            logDebug('Shuffling photo order...')
            Collections.shuffle(state.photos)
        }
    }
}

def getNextPhoto() {
    logDebug('Loading next photo...')
    if (state.index == null) {
        log.warn('invalid array index: null')
        return
    }
    def index = state.index + 1
    if (index >= state.photos.size()) {
        index = 0
        if (shuffle) {
            logDebug('Shuffling photo order...')
            Collections.shuffle(state.photos)
        }
    }
    def id = state.photos[index]
    state.index = index
    getPhotoById(id)
}

def getPrevPhoto() {
    log.debug('Loading previous photo...')
    if (state.index == null) {
        log.warn('invalid array index: null')
        return
    }
    def index = state.index - 1
    if (index < 0) {
        index = state.photos.size() - 1
    }
    def id = state.photos[index]
    state.index = index
    getPhotoById(id)
}

def getPhotoById(id) {
    logDebug("Getting URL for image ID: ${id}")
    def uri = "https://photoslibrary.googleapis.com/v1/mediaItems/${id}"
    def headers = [ Authorization: 'Bearer ' + state.googleAccessToken ]
    def contentType = 'application/json'
    def params = [ uri: uri, headers: headers, contentType: contentType ]
    asynchttpGet(handlePhotoGet, params, [params: params])
}

def handlePhotoGet(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorJson()
        } catch (Exception ignored) {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpGet(handlePhotoGet, data.params, data)
        } else {
            log.warn("Photo-get response code: ${respCode}, body: ${respError}")
        }
    } else {
        def respJson = resp.getJson()
        device = getChildDevice(state.deviceId)
        if (respJson?.mediaMetadata?.containsKey('photo')) {
            def w = imgWidth ?: 2048
            def h = imgHeight ?: 1024
            sendEvent(device, [name: 'image', value: '<div style="box-sizing: content-box"><img style="height: 100%; width: 100%; object-fit: contain" src="' + "${respJson.baseUrl}=w${w}-h${h}" + '" /></div>'])
            //sendEvent(device, [name: 'image', value: '<div id="image" style="height:100%;width:100%;background-image:url(' + "${respJson.baseUrl}=w${w}-h${h}" + ');background-repeat:no-repeat;background-size:contain;background-position:center center;"></div>'])
            sendEvent(device, [name: 'mediaType', value: 'photo'])
        } else if (respJson?.mediaMetadata?.containsKey('video')) {
            sendEvent(device, [name: 'image', value: '<video autoplay loop><source src="' + "${respJson.baseUrl}=dv" + '" type="' + "${respJson.mimeType}" + '"></video>'])
            sendEvent(device, [name: 'mediaType', value: 'video'])
        }
    }
}

def logToken() {
    log.debug("Access Token: ${state.googleAccessToken}")
}
