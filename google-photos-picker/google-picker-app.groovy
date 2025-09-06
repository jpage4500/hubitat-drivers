// hubitat start
// hub: 192.168.0.200
// type: app
// id: 954
// hubitat end

import groovy.transform.Field

/**
 *  Google Picker Image Importer (Complete)
 *  Description: Select multiple images via Google Picker (Drive & Photos) and save to Hubitat File Manager.
 *
 *  Permissions:
 *   - OAuth must be enabled for this app in Hubitat (Apps Code > this app > OAuth > Enable OAuth).
 *   - In Google Cloud Console, enable: Drive API, Photos Library API.
 *
 *  Notes:
 *   - This app serves a Picker page, receives selected items, downloads with the proper API, and
 *     stores them to the hub's File Manager.
 *   - Tested patterns for Drive images (alt=media) and Photos Library items (baseUrl + "=d").
 */
definition(
    name: "Google Photos Picker Album",
    namespace: 'jpage4500',
    author: 'Joe Page',
    description: "Select multiple images via Google Picker and save to Hubitat File Manager",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true,
    oauth: true
)

preferences {
    page(name: "pgMain")
    page(name: "pgConnect")
}

@Field static String SCOPE_DRIVE = "https://www.googleapis.com/auth/drive.readonly"
@Field static String SCOPE_PHOTOS = "https://www.googleapis.com/auth/photoslibrary.readonly"
@Field static String TOKEN_URL = "https://oauth2.googleapis.com/token"

def pgMain() {
    dynamicPage(name: "pgMain", title: "Google Picker Image Importer", install: true, uninstall: true) {
        section("Google API Settings") {
            input "googleApiKey", "text", title: "Google API Key (Developer Key for Picker)", required: true
            input "googleClientId", "text", title: "Google OAuth Client ID", required: true
            input "googleClientSecret", "password", title: "Google OAuth Client Secret", required: true
            input "saveFolder", "text", title: "File Manager folder (optional, e.g. 'picker')", required: false
        }
        section("Connection") {
            def oauth = getOAuthState()
            log.info "Main page: OAuth state = ${oauth}"
            log.info "Main page: access_token exists = ${!!oauth?.access_token}"

            if (oauth?.access_token) {
                paragraph "✅ Connected to Google (token expires: ${oauth.expiryHuman ?: 'unknown'})"
                href url: getPickerUrl(), style: "external", required: false, title: "Open Google Picker", description: "Opens picker in new tab"
                paragraph "<a target=_blank href=${getPickerUrl()}>Open in new tab</a>"
                href name: "toConnect", title: "Reconnect / Change Google Account", page: "pgConnect"
            } else {
                paragraph "❌ Not connected to Google yet."
                href name: "toConnect", title: "Connect to Google", page: "pgConnect"
            }
        }
        section("Tools") {
            href url: getCallbackUrl(), style: "external", required: false, title: "View OAuth Redirect URI", description: getCallbackUrl()
            paragraph "Files will be saved to File Manager" + (settings.saveFolder ? " in folder '/${settings.saveFolder}'" : "") + "."
        }
        section("About & Tips") {
            paragraph "• Make sure Drive API and Photos Library API are enabled in your Google project.\n" +
                "• Add this exact OAuth redirect URI to your Google OAuth Client settings:\n" +
                "<b>https://cloud.hubitat.com/oauth/stateredirect</b>"
        }
    }
}

def pgConnect() {
    ensureAccessToken()
    def authUrl = buildAuthUrl()
    dynamicPage(name: "pgConnect", title: "Connect to Google") {
        section("Authorize") {
            paragraph "Click the button below to start Google sign-in and grant read-only access to Drive and Photos."
            paragraph """
                <button onclick="openOAuthPopup()" style="padding: 10px 20px; font-size: 16px; background-color: #4285f4; color: white; border: none; border-radius: 5px; cursor: pointer;">
                    Authorize with Google
                </button>
                <script>
                    function openOAuthPopup() {
                        var popup = window.open('${authUrl}', 'oauth', 'width=600,height=600,scrollbars=yes,resizable=yes');
                        // Check if popup is closed
                        var checkClosed = setInterval(function() {
                            if (popup.closed) {
                                clearInterval(checkClosed);
                                // Refresh the page to update the status
                                window.location.reload();
                            }
                        }, 1000);
                    }
                </script>
            """
        }
        section("Current Status") {
            def oauth = getOAuthState()
            log.info "Connect page: OAuth state = ${oauth}"
            log.info "Connect page: access_token exists = ${!!oauth?.access_token}"

            if (oauth?.access_token) {
                paragraph "✅ Connected as: ${state.profileEmail ?: '(unknown)'}\nToken expires: ${oauth.expiryHuman ?: 'unknown'}"
            } else {
                paragraph "❌ Not connected."
            }
        }
    }
}

private def showPicker() {
    log.info "showPicker: called"
    // Hubitat already validated the app endpoint access_token
    def oauth = getOAuthState()
    log.info "showPicker: OAuth state = ${oauth}"
    if (!oauth?.access_token) {
        log.warn "showPicker: no Google token available"
        render contentType: "text/html",
            data: "<html><body><h2>Not connected to Google</h2><p>Return to the app and connect first.</p></body></html>"
        return
    }
    // Refresh Google token if near expiry
    log.info "showPicker: ensuring Google token is valid"
    //ensureGoogleToken()
    log.info "showPicker: rendering picker page"
    try {
//        byte[] bytes = downloadHubFile("google-photos-picker.html")
//        render contentType: "text/html", data: bytes
        def htmlContent = buildPickerHtml()
        log.info "showPicker: HTML content generated (${htmlContent.length()} characters)"
        render contentType: "text/html", data: htmlContent.getBytes("UTF-8"), status: 200
    } catch (e) {
        log.error "showPicker render error: ${e}"
        log.error "showPicker render error stack: ${e.stackTrace}"
        render contentType: "text/html",
            data: "<html><body><h2>Error generating picker page</h2><p>Check app logs for details.</p><p>Error: ${e.message}</p></body></html>",
            status: 500
    }
}


/* =====================  OAuth Flow  ===================== */

def installed() {
    log.info "Installed"
    initialize()
}

def updated() {
    log.info "Updated"
    initialize()
}

def initialize() {
    ensureAccessToken()
    // Reset OAuth state if it's corrupted
    if (state.oauth && !(state.oauth instanceof Map)) {
        log.warn "initialize: OAuth state was corrupted, resetting"
        state.oauth = [:]
    }
}

// Safe method to get OAuth state
private Map getOAuthState() {
    try {
        if (!state.oauth) {
            return [:]
        }
        if (state.oauth instanceof Map) {
            return state.oauth
        } else {
            log.warn "getOAuthState: OAuth state was corrupted (${state.oauth}), resetting"
            state.oauth = [:]
            return [:]
        }
    } catch (Exception e) {
        log.error "getOAuthState: Error accessing OAuth state: ${e.message}"
        state.oauth = [:]
        return [:]
    }
}

// Safe method to set OAuth state
private void setOAuthState(Map oauthData) {
    try {
        state.oauth = oauthData
        log.info "setOAuthState: OAuth state updated successfully"
    } catch (Exception e) {
        log.error "setOAuthState: Error setting OAuth state: ${e.message}"
        // Try to reset state
        state.oauth = [:]
    }
}

private ensureAccessToken() {
    if (!state.accessToken) {
        createAccessToken()
        log.debug "Generated app access token for endpoints."
    }
}

private String getBaseEndpoint() {
    // Use the cloud endpoint if available, otherwise fallback to local IP
    if (app && app.getApiServerUrl()) {
        return app.getApiServerUrl()
    }
    return "http://${location.hubs[0]?.localIP ?: 'hubitat.local'}"
}

private String getLocalEndpoint() {
    return "http://${location.hubs[0]?.localIP ?: 'hubitat.local'}"
}

private String appApiPath() { "/apps/api/${app.id}" }

private String getCallbackUrl() {
    "https://cloud.hubitat.com/oauth/stateredirect"
}

private String getImportUrl() { "${getBaseEndpoint()}${appApiPath()}/import?access_token=${state.accessToken}" }

private String getPickerUrl() { "${getLocalEndpoint()}${appApiPath()}/picker?access_token=${state.accessToken}" }

private String getPickerPostUrl() { getImportUrl() }

private String buildAuthUrl() {
    def scopes = URLEncoder.encode("${SCOPE_DRIVE} ${SCOPE_PHOTOS}", "UTF-8")
    return "https://accounts.google.com/o/oauth2/v2/auth?" +
        "response_type=code&access_type=offline&prompt=consent&" +
        "client_id=${URLEncoder.encode(settings.googleClientId ?: '', 'UTF-8')}" +
        "&redirect_uri=${URLEncoder.encode(getCallbackUrl(), 'UTF-8')}" +
        "&scope=${scopes}" +
        "&state=${getHubUID()}/apps/${app.id}/handleAuth?access_token=${state.accessToken}"
}

private boolean tokenExpiredSoon() {
    def oauth = getOAuthState()
    if (!oauth?.expires_at) return true
    return (now() >= (oauth.expires_at as Long) - 60_000L) // refresh 60s early
}

private boolean ensureGoogleToken() {
    def oauth = getOAuthState()
    if (!oauth?.access_token) return false
    if (!tokenExpiredSoon()) return true
    return refreshToken()
}

private boolean refreshToken() {
    def oauth = getOAuthState()
    def rt = oauth?.refresh_token
    if (!rt) return false
    try {
        log.debug "Refreshing Google token..."
        httpPost([
            uri        : TOKEN_URL,
            contentType: "application/x-www-form-urlencoded",
            body       : [
                client_id    : settings.googleClientId,
                client_secret: settings.googleClientSecret,
                grant_type   : "refresh_token",
                refresh_token: rt
            ]
        ]) { resp ->
            if (resp?.status == 200 && resp?.data?.access_token) {
                storeTokenResponse(resp.data)
                log.info "Refreshed Google access token."
                return true
            } else {
                log.warn "Failed to refresh token: ${resp?.status} ${resp?.data}"
            }
        }
    } catch (e) {
        log.error "Refresh token error: ${e}"
    }
    return false
}

private void storeTokenResponse(data) {
    log.info "storeTokenResponse: storing token data: ${data}"

    def currentOAuth = getOAuthState()
    log.info "storeTokenResponse: current OAuth state: ${currentOAuth}"

    def expiresIn = (data.expires_in ?: 3600) as Long
    def existingRefreshToken = currentOAuth?.refresh_token

    def newOAuthState = [
        access_token : data.access_token as String,
        token_type   : data.token_type ?: "Bearer",
        refresh_token: (data.refresh_token ?: existingRefreshToken),
        expires_at   : now() + (expiresIn * 1000L),
        expiryHuman  : new Date(now() + (expiresIn * 1000L)).toString()
    ]

    setOAuthState(newOAuthState)
    log.info "storeTokenResponse: OAuth state updated successfully"
}

/* =====================  Endpoints  ===================== */

mappings {
    path("/handleAuth") {
        action:
        [GET: "handleAuthRedirect"]
    }
    // show google picker
    path("/picker") {
        action:
        [GET: "showPicker"]
    }
    // Receives selected files from the picker (POST JSON)
    path("/import") {
        action:
        [POST: "importSelected"]
    }
    // Optional: health ping
    path("/health") {
        action:
        [GET: "health"]
    }
}

def health() {
    render contentType: "application/json", data: [ok: true, time: new Date().time] as groovy.json.JsonOutput
}

def handleAuthRedirect() {
    // params: [access_token:TOKEN, code:CODE, state:HUB_ID/apps/APP_ID/handleAuth?access_token=TOKEN]
    log.info "=== OAuth Callback Started ==="
    log.info "handleAuthRedirect: params = ${params}"
    log.info "handleAuthRedirect: request headers = ${request.headers}"
    log.info "handleAuthRedirect: request URI = ${request.uri}"

    def code = params.code
    def state = params.state

    if (!code) {
        log.error "OAuth failed: no code provided. Params: ${params}"
        render contentType: "text/html", data: """
<html><body>
    <h2>❌ OAuth Failed</h2>
    <p>No authorization code provided.</p>
    <p>Params: ${params}</p>
    <p>Check app logs for more details.</p>
</body></html>
"""
        return
    }

    // Verify state parameter contains our app path
    if (!state || !state.contains("/apps/${app.id}/handleAuth")) {
        log.error "OAuth failed: invalid state parameter. Expected to contain /apps/${app.id}/handleAuth, Got: ${state}"
        render contentType: "text/html", data: """
<html><body>
    <h2>❌ OAuth Failed</h2>
    <p>Invalid state parameter.</p>
    <p>Expected to contain: /apps/${app.id}/handleAuth</p>
    <p>Got: ${state}</p>
    <p>Check app logs for more details.</p>
</body></html>
"""
        return
    }

    log.info "handleAuthRedirect: got code: ${code}, state: ${state}"
    log.info "handleAuthRedirect: about to exchange code for token"
    try {
        log.info "handleAuthRedirect: making token exchange request to ${TOKEN_URL}"
        log.info "handleAuthRedirect: client_id = ${settings.googleClientId}"
        log.info "handleAuthRedirect: redirect_uri = https://cloud.hubitat.com/oauth/stateredirect"

        httpPost([
            uri : TOKEN_URL,
            body: [
                code         : code,
                client_id    : settings.googleClientId,
                client_secret: settings.googleClientSecret,
                redirect_uri : 'https://cloud.hubitat.com/oauth/stateredirect',
                grant_type   : "authorization_code"
            ]
        ]) { response ->
            log.info "Token exchange response: ${response?.status}"
            log.info "Token exchange data: ${response?.data}"

            if (response.status == 200) {
                log.info "Token exchange successful, storing response"
                storeTokenResponse(response.data)
                log.info "OAuth successful! Google connected. State after: ${state.oauth}"

                render contentType: "text/html", data: """
<html>
<head>
    <title>OAuth Success</title>
    <script>
        console.log('OAuth success page loaded');
        console.log('window.opener exists:', !!window.opener);
        
        // Try to close the popup window and refresh the parent
        if (window.opener) {
            console.log('This is a popup window, refreshing parent and closing');
            // This is a popup window
            window.opener.location.reload();
            window.close();
        } else {
            console.log('This is not a popup, showing success message');
            // This is not a popup, just show success message
            document.body.innerHTML = '<h2>✅ Google connected successfully!</h2><p>You can close this tab and return to the app.</p>';
        }
    </script>
</head>
<body>
    <h2>✅ Google connected successfully!</h2>
    <p>This window should close automatically. If it doesn't, you can close it manually and return to the app.</p>
    <p>Check browser console for debug info.</p>
</body>
</html>
"""
            } else {
                log.error "Token exchange failed: ${response?.status} ${response?.data}"
                render contentType: "text/html", data: """
<html><body>
    <h2>❌ Token Exchange Failed</h2>
    <p>Status: ${response?.status}</p>
    <p>Response: ${response?.data}</p>
    <p>Check app logs for more details.</p>
</body></html>
"""
            }
        }
    } catch (e) {
        log.error "OAuth callback error: ${e}"
        log.error "OAuth callback error stack: ${e.stackTrace}"
        render contentType: "text/html", data: """
<html><body>
    <h2>❌ OAuth Error</h2>
    <p>Error: ${e}</p>
    <p>Check app logs for more details.</p>
</body></html>
"""
    }
}

/* =====================  Picker HTML  ===================== */

private String buildPickerHtml() {
    def oauth = getOAuthState()
    def accessToken = oauth?.access_token ?: ""
    def apiKey = settings.googleApiKey ?: ""
    def importUrl = getPickerPostUrl()
    def saveFolder = (settings.saveFolder ?: "").replaceAll("[^A-Za-z0-9_\\-\\/]", "_")
    def userEmail = state.profileEmail ?: "unknown"

    // Load HTML from file manager and inject configuration
    def filename = "google-photos-picker.html"
    def htmlContent = ""

    try {
        log.info "buildPickerHtml: attempting to load ${filename} from file manager"
        byte[] htmlBytes = downloadHubFile(filename)
        if (!htmlBytes) {
            log.warn "buildPickerHtml: file ${filename} not found in file manager, using fallback"
            htmlContent = getFallbackHtml()
        } else {
            log.info "buildPickerHtml: loaded ${filename} from file manager (${htmlBytes.length} bytes)"
            htmlContent = new String(htmlBytes, "UTF-8")
        }
    } catch (Exception e) {
        log.error "buildPickerHtml: error loading ${filename}: ${e.message}, using fallback"
        htmlContent = getFallbackHtml()
    }

    // Replace placeholder values with actual configuration
    log.info "buildPickerHtml: injecting configuration - accessToken: ${accessToken ? 'present' : 'missing'}, apiKey: ${apiKey ? 'present' : 'missing'}"
    htmlContent = htmlContent.replace('let oauthToken = null;', "let oauthToken = ${accessToken ? "'" + accessToken.replace("'", "\\'") + "'" : "null"};")
    htmlContent = htmlContent.replace('let developerKey = null;', "let developerKey = ${apiKey ? "'" + apiKey.replace("'", "\\'") + "'" : "null"};")
    htmlContent = htmlContent.replace('let importUrl = "";', "let importUrl = \"${importUrl}\";")
    htmlContent = htmlContent.replace('let saveFolder = "";', "let saveFolder = \"${saveFolder}\";")
    htmlContent = htmlContent.replace('<span id="userEmail">unknown</span>', "<span id=\"userEmail\">${userEmail}</span>")

    log.info "buildPickerHtml: HTML content prepared (${htmlContent.length()} characters)"
    return htmlContent
}

private String getFallbackHtml() {
    log.info "buildPickerHtml: using fallback HTML"
    return """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>Google Picker – Select Images</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<script src="https://apis.google.com/js/api.js"></script>
<style>
body { font-family: system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif; margin: 16px; }
.btn { display:inline-block; padding:10px 14px; border-radius:10px; border:1px solid #ddd; text-decoration:none; }
#log { white-space: pre-wrap; font-size: 13px; margin-top: 12px; }
</style>
</head>
<body onload="onGapiLoad()">
    <h2>Google Picker – Select Images</h2>
    <p>Signed in as: <span id="userEmail">unknown</span>.</p>
    <button class="btn" onclick="openPicker()">Open Picker</button>
    <div id="log"></div>

<script>
let oauthToken = null;
let developerKey = null;
let importUrl = "";
let saveFolder = "";

function log(msg) {
  console.log('Picker Debug:', msg);
  document.getElementById('log').textContent += (msg + "\\n");
}

function onGapiLoad() {
  log("Google API script loaded. Preparing picker...");
  gapi.load('auth', {'callback': onAuthLoad});
  gapi.load('picker');
}

function onAuthLoad() {
  log("Authentication module loaded.");
  openPicker();
}

function openPicker() {
  log("openPicker called");
  log("oauthToken: " + (oauthToken ? "present" : "missing"));
  log("developerKey: " + (developerKey ? "present" : "missing"));
  log("importUrl: " + importUrl);
  log("saveFolder: " + saveFolder);
  
  if (!oauthToken) { log("No OAuth token. Close and reconnect."); return; }
  if (!developerKey) { log("No Developer Key. Close and configure in app."); return; }

  gapi.load('auth', {'callback': function() {
      log("Auth loaded, loading picker...");
      gapi.load('picker', {'callback': function() {
        log("Picker loaded, creating picker...");
        const viewImages = new google.picker.View(google.picker.ViewId.DOCS_IMAGES);
        const viewPhotos = new google.picker.PhotosView()
                              .setType(google.picker.PhotosViewType.PHOTOS);
    
        const picker = new google.picker.PickerBuilder()
          .setAppId("") // optional
          .setOAuthToken(oauthToken)
          .setDeveloperKey(developerKey)
          .addView(viewImages)
          .addView(viewPhotos)
          .enableFeature(google.picker.Feature.MULTISELECT_ENABLED)
          .setTitle("Select images to import")
          .setCallback(pickerCallback)
          .build();
        log("Picker created, making visible...");
        picker.setVisible(true);
        log("Picker should now be visible");
      }});
  }});
}

async function pickerCallback(data) {
  log("Picker callback triggered: " + JSON.stringify(data));
  if (data.action === google.picker.Action.PICKED) {
    const docs = (data.docs || []).map(d => ({
      id: d.id,
      name: d.name || d.description || (d.id + ".jpg"),
      mimeType: d.mimeType || "",
      serviceId: d.serviceId || "", // 'photos' for Google Photos
      url: d.url || "",
      sizeBytes: d.sizeBytes || null
    }));

    log("Selected " + docs.length + " item(s). Uploading list to hub...");
    try {
      const res = await fetch(importUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ items: docs, saveFolder })
      });
      const text = await res.text();
      log("Upload response: " + text);
    } catch (e) {
      log("POST failed: " + e);
    }
  }
}
</script>
</body>
</html>
"""
}

/* =====================  Import Handler  ===================== */

def importSelected() {
    // Hubitat automatically checks access_token on app endpoints
    try {
        def body = request.JSON
        List items = (body?.items ?: []) as List
        String folder = (body?.saveFolder ?: settings.saveFolder ?: "").toString().trim()
        if (folder && !folder.startsWith("/")) folder = "/${folder}"

        if (!ensureGoogleToken()) {
            render contentType: "text/plain", data: "No/expired Google token. Reconnect.", status: 401
            return
        }

        int ok = 0; int fail = 0
        items.each { it ->
            def id = it.id as String
            def mime = (it.mimeType ?: "").toString()
            def svc = (it.serviceId ?: "").toString().toLowerCase()
            def name = sanitizeFilename(it.name ?: (id + guessExt(mime)))

            boolean saved = false
            if (svc == "photos") {
                saved = importFromPhotos(id, name, folder)
            } else {
                // default to Drive
                saved = importFromDrive(id, name, folder)
            }
            if (saved) ok++ else fail++
        }

        render contentType: "text/plain", data: "Imported ${ok} item(s), ${fail} failed."
    } catch (e) {
        log.error "importSelected error: ${e}"
        render contentType: "text/plain", data: "Error: ${e}", status: 500
    }
}

private boolean importFromDrive(String fileId, String filename, String folder) {
    try {
        // First, get file metadata to refine filename and ensure it's an image.
        def meta = null
        httpGet([
            uri    : "https://www.googleapis.com/drive/v3/files/${URLEncoder.encode(fileId, 'UTF-8')}",
            query  : [fields: "id,name,mimeType,size"],
            headers: ["Authorization": "Bearer ${getOAuthState().access_token}"]
        ]) { resp -> if (resp?.status == 200) meta = resp?.data }

        if (meta?.mimeType?.startsWith("image/")) {
            filename = sanitizeFilename(meta?.name ?: filename)
        }

        // Then, download the bytes
        def url = "https://www.googleapis.com/drive/v3/files/${URLEncoder.encode(fileId, 'UTF-8')}?alt=media"
        def bytes = null
        httpGet([
            uri            : url,
            headers        : ["Authorization": "Bearer ${getOAuthState().access_token}"],
            contentType    : 'application/octet-stream',
            ignoreSSLIssues: true
        ]) { resp -> bytes = resp?.data?.bytes }

        if (!bytes) {
            log.warn "Drive download returned no data for ${fileId}"
            return false
        }
        return saveToFileManager(folder, filename, bytes)
    } catch (e) {
        log.error "importFromDrive error (${fileId}): ${e}"
        // Try refresh once on 401
        if (e.toString()?.contains("401")) {
            if (refreshToken()) {
                return importFromDrive(fileId, filename, folder)
            }
        }
        return false
    }
}

private boolean importFromPhotos(String mediaItemId, String filename, String folder) {
    try {
        // Get media item to obtain baseUrl (requires Photos scope)
        def item = null
        httpGet([
            uri    : "https://photoslibrary.googleapis.com/v1/mediaItems/${URLEncoder.encode(mediaItemId, 'UTF-8')}",
            headers: ["Authorization": "Bearer ${getOAuthState().access_token}"]
        ]) { resp -> if (resp?.status == 200) item = resp?.data }

        if (!item?.baseUrl) {
            log.warn "Photos item missing baseUrl for ${mediaItemId}"
            return false
        }
        def mimeType = item?.mimeType ?: "image/jpeg"
        def ext = guessExt(mimeType)
        if (!filename?.toLowerCase()?.endsWith(ext)) {
            filename = sanitizeFilename(filename + ext)
        }

        // Download original quality: baseUrl + "=d" (original), or use parameters like "=w2048-h2048"
        def downloadUrl = "${item.baseUrl}=d"
        def bytes = null
        httpGet([
            uri            : downloadUrl,
            headers        : ["Authorization": "Bearer ${getOAuthState().access_token}"],
            contentType    : 'application/octet-stream',
            ignoreSSLIssues: true
        ]) { resp -> bytes = resp?.data?.bytes }

        if (!bytes) {
            log.warn "Photos download returned no data for ${mediaItemId}"
            return false
        }
        return saveToFileManager(folder, filename, bytes)
    } catch (e) {
        log.error "importFromPhotos error (${mediaItemId}): ${e}"
        if (e.toString()?.contains("401")) {
            if (refreshToken()) {
                return importFromPhotos(mediaItemId, filename, folder)
            }
        }
        return false
    }
}

/* =====================  File Manager Helpers  ===================== */

private boolean saveToFileManager(String folder, String filename, byte[] bytes) {
    try {
        String path = (folder ? "${folder}/${filename}" : "/${filename}")
        // Ensure slashes normalized
        path = path.replaceAll("/+", "/")
        hubitat.helper.FileManager.saveFile(path, bytes)
        log.info "Saved ${path} (${bytes?.length ?: 0} bytes)"
        return true
    } catch (e) {
        log.error "File save failed for ${filename}: ${e}"
        return false
    }
}

private String sanitizeFilename(String name) {
    if (!name) return "image_${now()}.jpg"
    // Remove path separators and control chars
    def n = name.replaceAll("[\\\\/\\t\\n\\r]", "_")
    // Keep simple ASCII if hub has issues with unicode
    n = n.replaceAll("[^A-Za-z0-9._\\- ]", "_")
    return n
}

private String guessExt(String mime) {
    if (!mime) return ".jpg"
    mime = mime.toLowerCase()
    if (mime.contains("png")) return ".png"
    if (mime.contains("gif")) return ".gif"
    if (mime.contains("webp")) return ".webp"
    if (mime.contains("bmp")) return ".bmp"
    if (mime.contains("tiff")) return ".tiff"
    return ".jpg"
}
