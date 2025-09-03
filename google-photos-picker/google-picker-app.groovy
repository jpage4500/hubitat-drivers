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
    page(name: "pgPicker")
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
            if (state.oauth?.access_token) {
                paragraph "✅ Connected to Google (token expires: ${state.oauth.expiryHuman ?: 'unknown'})"
                href url: getPickerUrl(), style: "external", required: false, title: "Open Google Picker", description: "Opens in new tab"
//                href name: "toPicker", title: "Open Google Picker", page: "pgPicker"
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
                "• Add this exact OAuth redirect URI to your Google OAuth Client settings."
        }
    }
}

def pgConnect() {
    ensureAccessToken()
    def authUrl = buildAuthUrl()
    dynamicPage(name: "pgConnect", title: "Connect to Google") {
        section("Authorize") {
            paragraph "Click the button below to start Google sign-in and grant read-only access to Drive and Photos."
            href url: authUrl, style: "external", required: false, title: "Authorize with Google", description: authUrl
        }
        section("Current Status") {
            if (state.oauth?.access_token) {
                paragraph "✅ Connected as: ${state.profileEmail ?: '(unknown)'}\nToken expires: ${state.oauth.expiryHuman ?: 'unknown'}"
            } else {
                paragraph "❌ Not connected."
            }
        }
    }
}

private void showPicker() {
    // Hubitat already validated the app endpoint access_token
    if (!state.oauth?.access_token) {
        log.debug "showPicker: no Google token"
        render contentType: "text/html",
            data: "<html><body>Not connected to Google. Return to the app and connect.</body></html>"
        return
    }
    // Refresh Google token if near expiry
    ensureGoogleToken()
    log.debug "showPicker: rendering picker page"
    try {
        render contentType: "text/html", data: buildPickerHtml(), status: 200
    } catch (e) {
        log.error "showPicker render error: ${e}"
        render contentType: "text/html",
            data: "<html><body>Error generating picker page. See logs.</body></html>",
            status: 500
    }
}

def pgPicker() {
    if (!state.oauth?.access_token) {
        // If not connected, show a standard Hubitat page with an error.
        // This part returns a dynamicPage object, which is correct for this case.
        return dynamicPage(name: "pgPicker", title: "Open Google Picker") {
            section { paragraph "You are not connected. Go back and connect first." }
        }
    } else {
        // If connected, render the custom HTML.
        // The 'render' method handles the response directly.
        // The page method should not return a value in this case.
        def html = buildPickerHtml()
        render contentType: "text/html", data: html
        // Return null or have no return statement to prevent issues.
        return null
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
    "https://cloud.hubitat.com/oauth/stateredirect" +
        '&state=' + getHubUID() + '/apps/' + app.id + '/handleAuth?access_token=' + state.accessToken
}

private String getImportUrl() { "${getBaseEndpoint()}${appApiPath()}/import?access_token=${state.accessToken}" }

private String getPickerUrl() { "${getLocalEndpoint()}${appApiPath()}/picker?access_token=${state.accessToken}" }

private String getPickerPostUrl() { getImportUrl() }

private String buildAuthUrl() {
    def scopes = URLEncoder.encode("${SCOPE_DRIVE} ${SCOPE_PHOTOS}", "UTF-8")
    return "https://accounts.google.com/o/oauth2/v2/auth?" +
        "response_type=code&access_type=offline&prompt=consent&" +
        "client_id=${URLEncoder.encode(settings.googleClientId ?: '', 'UTF-8')}" +
        "&redirect_uri=${getCallbackUrl()}" +
        "&scope=${scopes}"
}

private boolean tokenExpiredSoon() {
    if (!state.oauth?.expires_at) return true
    return (now() >= (state.oauth.expires_at as Long) - 60_000L) // refresh 60s early
}

private boolean ensureGoogleToken() {
    if (!state.oauth?.access_token) return false
    if (!tokenExpiredSoon()) return true
    return refreshToken()
}

private boolean refreshToken() {
    def rt = state.oauth?.refresh_token
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
    def expiresIn = (data.expires_in ?: 3600) as Long
    state.oauth = [
        access_token : data.access_token as String,
        token_type   : data.token_type ?: "Bearer",
        refresh_token: (data.refresh_token ?: state.oauth?.refresh_token),
        expires_at   : now() + (expiresIn * 1000L),
        expiryHuman  : new Date(now() + (expiresIn * 1000L)).toString()
    ]
    log.debug "OAUTH: ${state.oauth}, data: ${data}"
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
    def code = params.code
    if (!code) {
        render contentType: "text/html", data: "<html><body>OAuth failed: no code provided.</body></html>"
        return
    }
    log.debug "handleAuthRedirect: got code: ${code}"
    try {
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
            log.debug "Token response: ${response?.status} ${response?.data}"
            if (response.status == 200) {
                storeTokenResponse(response.data)
                render contentType: "text/html", data: "<html><body>✅ Google connected. You can close this tab and return to the app.</body></html>"
            } else {
                log.warn "Token exchange failed: ${response?.status} ${response?.data}"
                log.warn "access_token: ${response?.data?.access_token}"
                render contentType: "text/html", data: "<html><body>Token exchange failed. Check logs.</body></html>"
            }
        }
    } catch (e) {
        log.error "OAuth callback error: ${e}"
        render contentType: "text/html", data: "<html><body>OAuth error. See logs.</body></html>"
    }
}

/* =====================  Picker HTML  ===================== */

private String buildPickerHtml() {
    def accessToken = state.oauth?.access_token ?: ""
    def apiKey = settings.googleApiKey ?: ""
    def appToken = state.accessToken
    def importUrl = getPickerPostUrl()
    def saveFolder = (settings.saveFolder ?: "").replaceAll("[^A-Za-z0-9_\\-\\/]", "_")
    def title = "Google Picker – Select Images"

    return """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>${title}</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<script src="https://apis.google.com/js/api.js"></script>
<style>
body { font-family: system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif; margin: 16px; }
.btn { display:inline-block; padding:10px 14px; border-radius:10px; border:1px solid #ddd; text-decoration:none; }
#log { white-space: pre-wrap; font-size: 13px; margin-top: 12px; }
</style>
</head>
<body onload="onGapiLoad()">
    <h2>${title}</h2>
    <p>Signed in as: ${state.profileEmail ?: "unknown"}.</p>
    <button class="btn" onclick="openPicker()">Open Picker</button>
    <div id="log"></div>

<script>
let oauthToken = ${accessToken ? "'" + accessToken.replace("'", "\\'") + "'" : "null"};
let developerKey = ${apiKey ? "'" + apiKey.replace("'", "\\'") + "'" : "null"};
let importUrl = "${importUrl}";
let saveFolder = "${saveFolder}";

function log(msg) {
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
  if (!oauthToken) { log("No OAuth token. Close and reconnect."); return; }
  if (!developerKey) { log("No Developer Key. Close and configure in app."); return; }

  gapi.load('auth', {'callback': function() {
      gapi.load('picker', {'callback': function() {
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
        picker.setVisible(true);
      }});
  }});
}

async function pickerCallback(data) {
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
      log(text);
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
            headers: ["Authorization": "Bearer ${state.oauth.access_token}"]
        ]) { resp -> if (resp?.status == 200) meta = resp?.data }

        if (meta?.mimeType?.startsWith("image/")) {
            filename = sanitizeFilename(meta?.name ?: filename)
        }

        // Then, download the bytes
        def url = "https://www.googleapis.com/drive/v3/files/${URLEncoder.encode(fileId, 'UTF-8')}?alt=media"
        def bytes = null
        httpGet([
            uri            : url,
            headers        : ["Authorization": "Bearer ${state.oauth.access_token}"],
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
            headers: ["Authorization": "Bearer ${state.oauth.access_token}"]
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
            headers        : ["Authorization": "Bearer ${state.oauth.access_token}"],
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
