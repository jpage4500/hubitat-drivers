import java.net.http.HttpTimeoutException
import java.net.SocketTimeoutException
import groovy.transform.Field

@Field static final int    TOKEN_EXPIRED_POLL_SECS         = 300   // slow-poll rate when token is flagged expired
@Field static final int    AUTH_FAIL_THRESHOLD             = 3     // consecutive 401/403s before flagging token expired
@Field static final int    DEFAULT_POLL_SECS               = 30    // fallback when pollIntervalSecs is not yet set
@Field static final int    HTTP_TIMEOUT_SECS               = 30    // default HTTP request timeout
@Field static final int    SECS_PER_MIN                    = 60    // boundary between seconds and minutes cron expressions
@Field static final int    SECS_PER_HOUR                   = 3600  // seconds per hour
@Field static final int    RATE_LIMIT_BUFFER_SECS          = 10    // extra seconds added to Retry-After on 429
@Field static final int    RATE_LIMIT_FALLBACK_SECS        = 60    // fallback Retry-After when header is absent
@Field static final long   CIRCLES_POLL_INTERVAL_MS        = 60000L // circles membership check interval in ms
@Field static final int    FORCE_UPDATE_FETCH_DELAY_SECS   = 6     // seconds to wait after force-update before re-fetching (~5s for Life360 to push fresh GPS)
@Field static final int    CIRCLES_API_VERSION             = 4     // Life360 API version for the /circles endpoint
@Field static final int    MAX_BACKOFF_SHIFT               = 6     // max bit-shift for exponential backoff (caps at 2^6 = 64× base interval)

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** LIFE360+ Hubitat App **
 * Life360 companion app to track members' location in your circle
 * - Community discussion: https://community.hubitat.com/t/release-life360/118544
 *
 * Changelog: see CHANGELOG.md alongside this file
 *   https://github.com/jpage4500/hubitat-drivers/blob/master/life360/CHANGELOG.md
 *
 * NOTE: This is a re-write of Life360+, which was just a continuation of "Life360 with States" -> https://community.hubitat.com/t/release-life360-with-states-track-all-attributes-with-app-and-driver-also-supports-rm4-and-dashboards/18274
 * - please see that thread for full history of this app/driver
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

definition(
    name: "Life360+",
    namespace: "jpage4500",
    author: "Joe Page",
    description: "Life360 companion app to track members' location in your circle",
    category: "",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/life360/life360_app.groovy",
    oauth: [displayName: "Life360+", displayLink: ""]
)

preferences {
    page(name: "mainPage")
}

mappings {
    // map view showing all selected members
    path("/view") {
        action: [
            GET: "renderView"
        ]
    }
}

/**
 * starting page
 * - if we have an auth token, jump to circlesPage to select circle -> members
 * - otherwise, show phone number login page
 */
def mainPage() {
    // One-shot flags: button handlers set these so the result survives the one re-render
    // after the button press. On every other page open we clear stale state.
    if (state.tokenStatusPending) {
        state.tokenStatusPending = false
    } else {
        state.tokenStatus = null
    }
    if (state.forceUpdateStatusPending) {
        state.forceUpdateStatusPending = false
    } else {
        state.forceUpdateStatus = null
    }
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section() {
            href name: "myHref", url: "https://joe-page-software.gitbook.io/hubitat-dashboard/tiles/location-life360/life360+#configuration", title: "Step-by-step instructions", style: "external", width: 6
            showMessage(state.message)
        }
        section(header("STEP 1: Access Token")) {
            input 'access_token', 'text', title: 'Access Token', required: true, defaultValue: '', submitOnChange: true, width: 6
            if (!isEmpty(access_token)) {
                input("checkTokenBtn", "button", title: "Check Token")
                if (state.tokenStatus) paragraph state.tokenStatus
            }
        }

        if (!isEmpty(access_token)) {
            section(header("STEP 2: Life360 Circles")) {
                input("fetchCirclesBtn", "button", title: "Fetch Circles")

                if (!isEmpty(state.circles)) {
                    input "circle", "enum", multiple: false, required: true, title: "Life360 Circle", options: state.circles.collectEntries { [it.id, it.name] }, submitOnChange: true, width: 6
                }
            }

            section(header("STEP 3: Select HOME")) {
                input("fetchPlacesBtn", "button", title: "Fetch Places")

                if (!isEmpty(state.places)) {
                    paragraph "Please select the ONE Life360 Place that matches your Hubitat location: ${location.name}"
                    def thePlaces = state.places.collectEntries { [it.id, it.name] }
                    def sortedPlaces = thePlaces.sort { a, b -> a.value <=> b.value }
                    input "place", "enum", multiple: false, required: true, title: "Life360 Places: ", options: sortedPlaces, submitOnChange: true, width: 6
                }
            }

            section(header("STEP 4: Select Members to track")) {
                input("fetchMembersBtn", "button", title: "Fetch Members")

                if (!isEmpty(state.members)) {
                    def theMembers = state.members.collectEntries { [it.id, it.firstName + " " + it.lastName] }
                    def sortedMembers = theMembers.sort { a, b -> a.value <=> b.value }
                    input "users", "enum", multiple: true, required: false, title: "Life360 Members: ", options: sortedMembers, submitOnChange: true, width: 6
                }
            }

            section(header("STEP 5: Verify Connectivity")) {
                paragraph "<small style='color:#666'>Pulls current data for every selected member. Use this to confirm setup before saving.  If it fails, check the access token (STEP 1) and try again.</small>"
                input("fetchLocationsBtn", "button", title: "Fetch Locations")
                if (state.lastSuccessMs) {
                    long ageSec = (long)((new Date().getTime() - state.lastSuccessMs) / 1000L)
                    String ageStr = (ageSec < SECS_PER_MIN) ? "${ageSec}s ago"
                        : (ageSec < SECS_PER_HOUR) ? "${(long)(ageSec / SECS_PER_MIN)} min ago"
                        : "${(long)(ageSec / SECS_PER_HOUR)} hr ago"
                    paragraph "<small style='color:#080'>\u2713 Last successful fetch: ${ageStr}</small>"
                }
            }
        }

        section(header("Polling")) {
            input(name: "pollFreq", type: "enum", title: "Default Refresh Rate", required: true, defaultValue: "60", width: 6, options: ['10': '10 seconds', '15': '15 seconds', '30': '30 seconds', '60': '1 minute', '180': '3 minutes', '300': '5 minutes', '0': 'Disabled'])
            input(name: "dynamicPolling", type: "bool", defaultValue: false, submitOnChange: true, title: "Enable Dynamic Polling", description: "Increase polling frequency to 'Dynamic Refresh Rate' when a member is in motion. Polling returns to 'Default Refresh Rate' once they've stopped. Only engages when the Dynamic Refresh Rate is faster than the Default Refresh Rate.")
            input(name: "dynamicPollFreq", type: "enum", title: "Dynamic Refresh Rate", required: false, defaultValue: "20", width: 6, options: ['5': '5 seconds', '10': '10 seconds', '20': '20 seconds', '30': '30 seconds'])
        }

        section(header("Notifications")) {
            input(name: "notifyTokenExpiry", type: "bool", defaultValue: true, submitOnChange: true, title: "Enable Token Expiry Notifications", description: "Master switch for token-expiry alerts. Turn off to silence all alerts without removing your notification devices.")
            if (settings.notifyTokenExpiry != false) {
                input(name: "notifyDevices", type: "capability.notification", title: "Notify Device on Token Failure", multiple: true, required: false, width: 6, description: "Devices to notify when the Life360 access token appears expired/revoked. Useful so you know to re-paste a fresh token without watching the logs.", submitOnChange: true)
                if (!isEmpty(settings.notifyDevices)) {
                    paragraph ""   // full-width spacer forces the dropdown onto its own row, below the device selector
                    input(name: "notifyRepeatHours", type: "enum", title: "Repeat Reminder Every", defaultValue: "never", width: 6, options: ['never': 'Never (notify once)', '2': '2 hours', '6': '6 hours', '12': '12 hours', '24': '24 hours', '48': '48 hours'])
                }
            }
        }

        section(header("Logging")) {
            paragraph "<small style='color:#666'>Controls what appears in Hubitat's app/device logs. Turn the privacy switches OFF when sharing logs publicly.</small>"
            input(name: "logEnable", type: "bool", defaultValue: false, submitOnChange: true, title: "Enable Debug Logging", description: "Enable extra logging for debugging.")
            input(name: "logRawPayload", type: "bool", defaultValue: false, submitOnChange: true, title: "Log Raw API Diagnostics", description: "Verbose. Logs sensitive data (GPS, partial token, cookies, payloads). Debug only — don't share resulting logs.")
            input(name: "logShowNames", type: "bool", defaultValue: true, submitOnChange: true, title: "Include Names and Places in Logs", description: "When on, logs show member/place/circle names. Turn off for privacy (UUIDs only) — useful when sharing logs for debugging.")
            input(name: "logShowMapsLink", type: "bool", defaultValue: true, submitOnChange: true, title: "Include Google Maps Link in Logs", description: "When a member moves, include a clickable Google Maps link to their coordinates in the info log. Turn off to keep coordinates out of logs.")
        }

        section(header("Map View")) {
            input(name: "googleMapsApiKey", type: "text", title: "Google Maps API Key (optional)", required: false, defaultValue: "", submitOnChange: true, width: 6)
            paragraph "<small style='color:#666'>If a Google Maps API Key is set, the map view uses Google Maps. Otherwise OpenStreetMap is used (no key required).<br><b style='color:#a00'>Security:</b> the key is embedded in the page HTML and visible to anyone who can load the map. Restrict it in Google Cloud Console (APIs &amp; Services --> Credentials) with HTTP-referrer + API restrictions so a leaked key can't be abused.</small>"
            String viewUrl = getViewUrl()
            if (viewUrl) {
                paragraph "<a href='${viewUrl}' target='_blank'>Open Map View</a><br><small style='color:#888'>${viewUrl}</small>"
                input("revokeViewLinkBtn", "button", title: "Revoke Map Link")
            } else {
                input("generateViewLinkBtn", "button", title: "Generate Map Link")
                paragraph "<small style='color:#888'>In Apps Code, open the ⋮ menu (top-right of the editor) and enable OAuth, then click 'Generate Map Link'.</small>"
            }
        }

        if (!isEmpty(access_token) && !isEmpty(settings.users) && !isEmpty(state.members)) {
            section(header("Force Update")) {
                paragraph "<small style='color:#666'>Push a location-update request to a member's phone. Life360 will ask the device for a fresh GPS fix (~5 seconds).</small>"
                def forceMembers = state.members
                    .findAll { settings.users.contains(it.id) }
                    .collectEntries { [it.id, "${it.firstName} ${it.lastName}".trim()] }
                    .sort { a, b -> a.value <=> b.value }
                input "forceUpdateMember", "enum", multiple: false, required: false, title: "Member", options: forceMembers, submitOnChange: true, width: 6
                input("forceUpdateBtn", "button", title: "Force Update")
                if (state.forceUpdateStatus) paragraph state.forceUpdateStatus
            }
        }

    }
}

private String getViewUrl() {
    if (isEmpty(state.accessToken)) return null
    return "${getFullLocalApiServerUrl()}/view?access_token=${state.accessToken}"
}

/**
 * automatically called when an input:button is pressed
 * @param button name
 */
def appButtonHandler(String button) {
    switch (button) {
        case "fetchCirclesBtn":
            fetchCircles()
            break
        case "fetchPlacesBtn":
            fetchPlaces()
            break
        case "fetchMembersBtn":
            fetchMembers()
            break
        case "fetchLocationsBtn":
            fetchLocations()
            break
        case "generateViewLinkBtn":
            generateViewLink()
            break
        case "revokeViewLinkBtn":
            revokeViewLink()
            break
        case "checkTokenBtn":
            checkToken()
            break
        case "forceUpdateBtn":
            forceMemberUpdate(settings.forceUpdateMember)
            app.removeSetting("forceUpdateMember")   // reset the dropdown to blank; it's a fire-once action, not a persisted preference
            break
        default:
            if (logEnable) log.debug("appButtonHandler: unhandled:${button}")
    }
}

private void generateViewLink() {
    try {
        if (isEmpty(state.accessToken)) {
            createAccessToken()
        }
    } catch (e) {
        log.error("generateViewLink: failed to create access token — in Apps Code, open the ⋮ menu (top-right) and enable OAuth first: ${e}")
        state.message = "In Apps Code, open the ⋮ menu (top-right of the editor) and enable OAuth, then try again."
    }
}

private void revokeViewLink() {
    state.accessToken = null
}

def showMessage(text) {
    if (!isEmpty(text)) {
        paragraph("<p style='color:red; font-weight: bold'>${text}</p>")
    }
}

// ---- Token check ------------------------------------------------------------

private void checkToken() {
    state.tokenStatusPending = true   // tell mainPage() to display the result on re-render
    def params = life360Params("/users/me")
    def cookies = state["cookies"]
    if (cookies) params["headers"]["Cookie"] = cookies
    try {
        httpGet(params) { response ->
            captureCookies(response)
            Integer status = response.status
            if (status == 200) {
                def user = response.data
                String name = user?.firstName ?: "unknown"
                log.info("checkToken: valid — id:${user?.id} name:${user?.firstName} ${user?.lastName} email:${user?.loginEmail}")
                captureUnitOfMeasure(user)
                state.tokenStatus = "<span style='color:#080'>&#10003; Hi ${name}! Your token is valid.</span>"
            } else if (status == 401 || status == 403) {
                log.error("checkToken: AUTH FAILURE (${status}) — token is likely expired or revoked")
                state.tokenStatus = "<span style='color:#a00'>&#10007; Auth failed (${status}) — token appears expired or revoked.</span>"
            } else {
                log.error("checkToken: unexpected status:${status}")
                state.tokenStatus = "<span style='color:#a00'>&#10007; Unexpected response (${status}) checking token.</span>"
            }
        }
    } catch (e) {
        log.error("checkToken: error: ${e.message}")
        state.tokenStatus = "<span style='color:#a00'>&#10007; Network error checking token — ${e.message}</span>"
    }
}

/**
 * Capture the user's units preference from a /users/me (or token) user object.
 * Life360's settings.unitOfMeasure: "i" = imperial (miles/mph), "m" = metric (km/kph).
 * Stored so the driver follows the user's Life360 app setting instead of a local toggle.
 */
private void captureUnitOfMeasure(user) {
    def unit = user?.settings?.unitOfMeasure
    if (unit && unit != state.unitOfMeasure) {
        state.unitOfMeasure = unit
        if (logEnable) log.debug("unitOfMeasure from Life360: ${unit == 'm' ? 'metric (km/kph)' : unit == 'i' ? 'imperial (miles/mph)' : unit}")
    }
}

/**
 * Async refresh of the user's Life360 units preference. Cheap GET /users/me, fire-and-forget;
 * called on install and on Done so units track the account setting without user intervention.
 */
private void refreshUserSettings() {
    if (isEmpty(access_token)) return
    def params = life360Params("/users/me")
    def cookies = state["cookies"]
    if (cookies) params["headers"]["Cookie"] = cookies
    asynchttpGet("handleUserSettingsResponse", params)
}

def handleUserSettingsResponse(response, data) {
    if (!response.status) return
    captureCookiesAsync(response)
    if (response.status == 200) {
        try {
            captureUnitOfMeasure(response.json)
        } catch (e) {
            log.error("handleUserSettingsResponse: JSON parse error: ${e.message}")
        }
    }
}

/**
 * Units preference sourced from the user's Life360 account (settings.unitOfMeasure).
 * @return true = miles/mph, false = km/kph, or null if not yet known (driver falls back
 *         to its local isMiles toggle).
 */
Boolean getUnitIsMiles() {
    if (state.unitOfMeasure == 'i') return true
    if (state.unitOfMeasure == 'm') return false
    return null
}

// ---- end Token check --------------------------------------------------------

// ---- Force Update -----------------------------------------------------------

private void forceMemberUpdate(String memberId) {
    if (isEmpty(memberId)) {
        log.warn("forceMemberUpdate: no member selected")
        state.forceUpdateStatus = "<span style='color:#a00'>&#10007; Select a member first.</span>"
        return
    }
    if (isEmpty(circle)) {
        log.warn("forceMemberUpdate: no circle selected")
        state.forceUpdateStatus = "<span style='color:#a00'>&#10007; No circle selected.</span>"
        return
    }
    String memberName = memberDisplayName(memberId)
    String body = groovy.json.JsonOutput.toJson([type: "location"])
    String cookies = state["cookies"]

    // Diagnostic chatter — gated behind logRawPayload. Includes URL (with circle/member UUIDs),
    // partial Bearer token, partial Cloudflare cookie head, User-Agent.
    if (getLogRawPayload()) {
        log.trace("forceMemberUpdate: POST ${life360BaseUrl()}/circles/${circle}/members/${memberId}/request")
        log.trace("forceMemberUpdate: body: ${body}")
        log.trace("forceMemberUpdate: Authorization: Bearer ${access_token ? access_token.take(8) + '…' : 'null'}")
        log.trace("forceMemberUpdate: Cookie header: ${cookies ? cookies.take(40) + '…' : 'none'}")
        log.trace("forceMemberUpdate: User-Agent: ${getHttpHeaders()['User-Agent']}")
    }

    def params = life360Params("/circles/${circle}/members/${memberId}/request")
    params.contentType = "application/json"
    params.body = body
    if (cookies) params["headers"]["Cookie"] = cookies

    if (getLogRawPayload()) log.trace("forceMemberUpdate: full params: ${params}")

    state.forceUpdateStatusPending = true
    state.forceUpdateStatus = "<span style='color:#888'>Sending…</span>"
    asynchttpPost("handleForceUpdateResponse", params, [memberId: memberId])
    log.info("forceMemberUpdate: sent for member:${memberName}")
}

def handleForceUpdateResponse(response, Map data) {
    String memberId = data.memberId
    Integer status = response.status
    String memberName = memberDisplayName(memberId)

    if (!status) {
        String errMsg = response.getErrorMessage() ?: "(no details)"
        log.error("forceMemberUpdate: network failure for ${memberName} — ${errMsg}")
        log.error("forceMemberUpdate: hasError:${response.hasError()}")
        state.forceUpdateStatus = "<span style='color:#a00'>&#10007; Network error: ${errMsg}</span>"
        return
    }

    if (getLogRawPayload()) {
        try {
            log.trace("forceMemberUpdate: response headers: ${response.headers}")
        } catch (e) {
            log.debug("forceMemberUpdate: could not read response headers: ${e.message}")
        }
    }

    captureCookiesAsync(response)

    if (status == 200) {
        def result
        try {
            result = response.json
        } catch (e) {
            log.error("forceMemberUpdate: failed to parse response JSON: ${e.message}")
            state.forceUpdateStatus = "<span style='color:#a00'>&#10007; Parse error in response</span>"
            return
        }
        String requestId = result?.requestId
        String isPollable = result?.isPollable
        if (getLogRawPayload()) log.trace("forceMemberUpdate: 200 OK — raw json: ${result}")
        log.info("forceMemberUpdate: SUCCESS member:${memberName} requestId:${requestId} isPollable:${isPollable}")
        state.forceUpdateStatus = "<span style='color:#080'>&#10003; Sent to ${memberName} — fresh location in ~5s</span>"
        runIn(FORCE_UPDATE_FETCH_DELAY_SECS, "fetchLocations")
    } else {
        String errMsg = response.getErrorMessage() ?: "(no details)"
        String errBody = null
        try { errBody = response.data?.toString() } catch (ignored) {}
        log.error("forceMemberUpdate: FAILED status:${status} member:${memberName} — ${errMsg}")
        log.error("forceMemberUpdate: response body: ${errBody ?: '(empty)'}")
        state.forceUpdateStatus = "<span style='color:#a00'>&#10007; Failed (${status}) — check logs for details</span>"
    }
}

// ---- end Force Update -------------------------------------------------------

def fetchCircles() {
    def params = life360Params("/circles", CIRCLES_API_VERSION)
    try {
        httpGet(params) {
            response ->
                captureCookies(response)
                if (response.status == 200) {
                    state.circles = response.data.circles
                    if (logEnable) log.debug("fetchCircles: ${state.circles?.size() ?: 0} circles: ${state.circles?.collect { showNamesInLogs() ? it.name : it.id }}")
                    state.message = null
                } else {
                    log.error("fetchCircles: bad response:${response.status}, ${response.data}")
                    state.message = "fetchCircles: bad response:${response.status}, ${response.data}"
                }
        }
    } catch (e) {
        handleException("fetch circles", e)
    }
}

def fetchPlaces() {
    if (isEmpty(circle)) {
        if (logEnable) log.debug("fetchPlaces: circle not set")
        return;
    }

    def params = life360Params("/circles/${circle}/places")
    try {
        httpGet(params) {
            response ->
                captureCookies(response)
                if (response.status == 200) {
                    state.places = response.data.places
                    if (logEnable) log.debug("fetchPlaces: ${state.places?.size() ?: 0} places: ${state.places?.collect { showNamesInLogs() ? it.name : it.id }}")
                    state.message = null
                } else {
                    log.error("fetchPlaces: bad response:${response.status}, ${response.data}")
                    state.message = "fetchPlaces: bad response:${response.status}, ${response.data}"
                }
        }
    } catch (e) {
        handleException("fetch places", e)
    }
}

def fetchMembers() {
    if (isEmpty(circle)) {
        if (logEnable) log.debug("fetchMembers: circle not set")
        return;
    }
    def params = life360Params("/circles/${circle}/members")
    asynchttpGet("handleMembersResponse", params)
}

def handleMembersResponse(response, data) {
    Integer status = response.status
    if (!status) {
        String errMsg = response.getErrorMessage() ?: "(no details)"
        log.error("fetchMembers: network error: ${errMsg}")
        state.message = "fetchMembers: network error: ${errMsg}"
        return
    }

    captureCookiesAsync(response)

    if (status == 200) {
        try {
            state.members = response.json?.members
        } catch (e) {
            log.error("fetchMembers: failed to parse response JSON: ${e.message}")
            state.message = "fetchMembers: invalid response body (parse error)"
            return
        }
        if (logEnable) log.debug("fetchMembers: ${state.members?.size() ?: 0} members: ${state.members?.collect { showNamesInLogs() ? it.firstName : it.id }}")
        state.message = null

        // sync child devices: create for newly selected members, remove departed ones
        createChildDevices()

        // push refreshed name/avatar/location to all selected members that have a device
        settings.users?.each { memberId ->
            def member = state.members?.find { it.id == memberId }
            // /circles/<id>/members doesn't always include location; the driver
            // early-returns on a null location anyway, but guard explicitly so the
            // intent is visible here and a future API change can't surprise us.
            if (member?.location) notifyChildDevice(memberId, member)
        }
    } else {
        log.error("fetchMembers: bad response:${status}")
        state.message = "fetchMembers: bad response:${status}"
    }
}

/**
 * fetch location for every member
 */
boolean fetchLocations() {
    if (isEmpty(circle)) {
        if (logEnable) log.debug("fetchLocations: circle not set")
        return false
    } else if (isEmpty(settings.users)) {
        if (logEnable) log.debug("fetchLocations: no users selected")
        return false
    }

    long currentTimeMs = new Date().getTime()

    // honor a server-driven rate-limit window from a prior 429
    if (state.rateLimitedUntilMs && currentTimeMs < state.rateLimitedUntilMs) {
        if (logEnable) log.debug("fetchLocations: rate-limited for ${(state.rateLimitedUntilMs - currentTimeMs)/1000}s, skipping")
        return false
    }
    // if the token is known-bad, stop hammering until the user pastes a new one
    if (state.tokenLikelyExpired) {
        if (logEnable) log.debug("fetchLocations: token flagged expired; skipping until refreshed")
        return false
    }

    if (logEnable) log.debug("fetchLocations: polling mode:${state.dynamicPollingActive ? 'DYNAMIC' : 'STANDARD'} interval:${state.pollIntervalSecs}s")

    // build places context once per poll cycle — reused for every member (§4.2)
    def ctx = buildPlacesContext()

    // iterate over every selected member
    settings.users.each { memberId ->
        fetchMemberLocation(memberId, ctx)
    }

    return true
}

def fetchMemberLocation(memberId, Map ctx = null) {
    // in-flight guard (§7.1): skip if a prior request for this member is still pending
    long startMs = new Date().getTime()
    long inflightMs = (state["inflight-${memberId}"] ?: 0L) as long
    Integer httpTimeout = clamp((state.pollIntervalSecs ?: DEFAULT_POLL_SECS) as int, 5, DEFAULT_POLL_SECS)
    if (inflightMs > 0 && (startMs - inflightMs) < ((httpTimeout + 2) * 1000L)) {
        if (logEnable) log.debug("fetchMemberLocation: member:${memberDisplayName(memberId)}: prior request pending, skipping")
        return
    }
    // transient-error backoff (§5.3): skip if still in backoff window after a prior 5xx
    long transientUntilMs = (state["transientUntilMs-${memberId}"] ?: 0L) as long
    if (transientUntilMs > 0 && startMs < transientUntilMs) {
        if (logEnable) {
            long remainSecs = (long)((transientUntilMs - startMs) / 1000L)
            log.debug("fetchMemberLocation: member:${memberDisplayName(memberId)}: transient backoff ${remainSecs}s remaining, skipping")
        }
        return
    }

    state["inflight-${memberId}"] = startMs

    def params = life360Params("/circles/${circle}/members/${memberId}")
    params.timeout = httpTimeout

    def cookies = state["cookies"]
    if (cookies) params["headers"]["Cookie"] = cookies

    def tag = state["etag-${memberId}"]
    if (tag) params["headers"]["If-None-Match"] = tag

    asynchttpGet("handleMemberLocationResponse", params, [memberId: memberId, ctx: ctx])
}

def handleMemberLocationResponse(response, Map data) {
    String memberId = data.memberId
    Map ctx = data.ctx
    state.remove("inflight-${memberId}")  // clear in-flight marker (§7.1)

    String memberName = memberDisplayName(memberId)

    // AsyncResponse.hasError() returns true for ANY non-2xx, including 304.
    // Use response.status as the primary gate; null/zero means a network-level failure.
    Integer status = response.status
    if (!status) {
        long delaySecs = applyTransientBackoff(memberId)
        int count = state["transientCount-${memberId}"] as int
        log.warn("fetchMemberLocation: NETWORK ERROR x${count} for member:${memberName}: ${response.getErrorMessage() ?: "(no details)"}; backing off ${delaySecs}s")
        return
    }

    captureCookiesAsync(response)

    if (status == 200) {
        def memberObj
        try {
            memberObj = response.json
        } catch (e) {
            log.error("fetchMemberLocation: JSON parse error for member:${memberName} (HTML body on 200?): ${e.message}")
            return
        }
        if (logEnable) log.debug("fetchMemberLocation: SUCCESS (200), locationUpdate:true, member:${memberName}")
        notifyChildDevice(memberId, memberObj, ctx)
        if (state.watchdogWarned) log.info("WATCHDOG: cleared — Life360 fetch succeeded again")
        markFetchSuccess(memberId)
        // AsyncResponse headers are a plain Map — keys are case-sensitive, but HTTP headers
        // are case-insensitive by spec; search case-insensitively so a server casing change
        // can't silently disable the 304 optimization.
        def eTag = response.headers?.find { it.key?.equalsIgnoreCase("l360-etag") }?.value
        if (eTag) state["etag-${memberId}"] = eTag

    } else if (status == 304) {
        if (state.watchdogWarned) log.info("WATCHDOG: cleared — Life360 fetch succeeded again (304)")
        markFetchSuccess(memberId)
        if (logEnable) log.debug("fetchMemberLocation: SUCCESS (304), prevInTransit:${isMemberInTransit(memberId)}, member:${memberName}")

    } else if (status == 401 || status == 403) {
        String jarBefore = cookieJarSummary()
        clearSessionCache()
        state.failCount = (state.failCount ?: 0) + 1
        log.error("fetchMemberLocation: AUTH (${status}); jar-at-failure [${jarBefore}]; cleared session; failCount=${state.failCount}")
        if (state.failCount >= AUTH_FAIL_THRESHOLD) {
            boolean wasExpired = state.tokenLikelyExpired ?: false
            state.tokenLikelyExpired = true
            state.message = "⚠ Access token appears expired/revoked (HTTP ${status} x${state.failCount}). Re-paste a fresh token from life360.com → DevTools → Network → token packet."
            if (!wasExpired) { scheduleUpdates(); notifyTokenExpired() }
        } else {
            state.message = "AUTH ERROR (${status}) on fetchMemberLocation member:${memberName}; cleared session, will retry"
        }

    } else if (status == 429) {
        Integer retryAfter = null
        try { retryAfter = response.headers?.find { it.key?.equalsIgnoreCase("Retry-After") }?.value?.toInteger() } catch (ignored) {}
        Integer delaySecs = (retryAfter ?: RATE_LIMIT_FALLBACK_SECS) + RATE_LIMIT_BUFFER_SECS
        state.rateLimitedUntilMs = new Date().getTime() + (delaySecs * 1000L)
        log.warn("fetchMemberLocation: RATE_LIMIT (429); backing off ${delaySecs}s")
        state.message = "Rate limited (429) — backing off ${delaySecs}s, will retry automatically"

    } else if (status in [502, 503, 504, 520, 522, 525]) {
        long delaySecs = applyTransientBackoff(memberId)
        int count = state["transientCount-${memberId}"] as int
        log.warn("fetchMemberLocation: TRANSIENT (${status}) x${count} for member:${memberName}; backing off ${delaySecs}s")
        state.message = "Transient error (${status}) x${count} for member:${memberName} — backing off ${delaySecs}s, will retry automatically"

    } else {
        log.error("fetchMemberLocation: unexpected response:${status} for member:${memberName}")
        state.message = "ERROR: fetchMemberLocation: ${status} for member:${memberName}"
    }
}

private static int clamp(int val, int lo, int hi) {
    return Math.min(Math.max(val, lo), hi)
}

private long applyTransientBackoff(String memberId) {
    int count = ((state["transientCount-${memberId}"] ?: 0) as int) + 1
    state["transientCount-${memberId}"] = count
    int pollSecs = (state.pollIntervalSecs ?: DEFAULT_POLL_SECS) as int
    int shift = Math.min(count - 1, MAX_BACKOFF_SHIFT)
    long delaySecs = Math.min((long)(pollSecs * (1L << shift)), (long)TOKEN_EXPIRED_POLL_SECS)
    state["transientUntilMs-${memberId}"] = new Date().getTime() + (delaySecs * 1000L)
    return delaySecs
}

private void markFetchSuccess(String memberId) {
    state.failCount = 0
    state.tokenLikelyExpired = false
    state.rateLimitedUntilMs = null
    state.message = null
    state.lastSuccessMs = new Date().getTime()
    state["transientCount-${memberId}"] = 0
    state.remove("transientUntilMs-${memberId}")
    if (state.watchdogWarned) {
        state.watchdogWarned = false
    }
}

/**
 * Classify an exception thrown by httpGet/httpPost into one of:
 *   "TIMEOUT" | "AUTH" | "RATE_LIMIT" | "TRANSIENT" | "OTHER"
 * Updates state (failCount, tokenLikelyExpired, rateLimitedUntilMs, cookies) so the
 * next scheduled poll can react. Returns the classification string.
 */
String handleException(String tag, Exception e) {
    Integer status = null
    try { status = e.response?.status } catch (ignored) { /* not all exceptions carry .response */ }

    if (e instanceof HttpTimeoutException || e instanceof SocketTimeoutException) {
        log.error("${tag}: TIMEOUT: ${e}")
        state.message = "TIMEOUT: ${tag}"
        return "TIMEOUT"
    }
    if (status == 401 || status == 403) {
        // ha-life360 treats 403 the same as 401: clear session, back off, surface to user.
        String jarBefore = cookieJarSummary()
        clearSessionCache()
        state.failCount = (state.failCount ?: 0) + 1
        log.error("${tag}: AUTH (${status}); jar-at-failure [${jarBefore}]; cleared session; failCount=${state.failCount}")
        if (state.failCount >= AUTH_FAIL_THRESHOLD) {
            boolean wasExpired = state.tokenLikelyExpired ?: false
            state.tokenLikelyExpired = true
            state.message = "⚠ Access token appears expired/revoked (HTTP ${status} x${state.failCount}). Re-paste a fresh token from life360.com → DevTools → Network → token packet."
            if (!wasExpired) {
                // scheduleUpdates() must come before notifyTokenExpired() — notifyTokenExpired()
                // calls scheduleTokenExpiryReminder() which registers a job; the unschedule()
                // inside scheduleUpdates() would cancel it if the order were reversed.
                scheduleUpdates()
                notifyTokenExpired()
            }
        } else {
            state.message = "AUTH ERROR (${status}) on ${tag}; cleared session, will retry"
        }
        return "AUTH"
    }
    if (status == 429) {
        Integer retryAfter = readRetryAfterSecs(e)
        Integer delaySecs = (retryAfter ?: RATE_LIMIT_FALLBACK_SECS) + RATE_LIMIT_BUFFER_SECS
        state.rateLimitedUntilMs = new Date().getTime() + (delaySecs * 1000L)
        log.warn("${tag}: RATE_LIMIT (429); backing off ${delaySecs}s")
        state.message = "Rate limited (429) — backing off ${delaySecs}s, will retry automatically"
        return "RATE_LIMIT"
    }
    if (status != null && (status == 502 || status == 503 || status == 504 || status == 520)) {
        log.warn("${tag}: TRANSIENT (${status}); will retry next tick")
        state.message = "Transient error (${status}) — will retry automatically"
        return "TRANSIENT"
    }
    def err = null
    try { err = e.response?.data } catch (ignored) {}
    log.error("handleException: ${tag}: ${status}: ${err}: ${e}")
    state.message = "ERROR: ${tag}: ${status}, ${err}"
    return "OTHER"
}

private void notifyTokenExpired() {
    if (isEmpty(settings.notifyDevices)) return
    if (settings.notifyTokenExpiry == false) return
    String msg = "Life360 token expired — re-paste a fresh access token"
    settings.notifyDevices.each { dev ->
        try {
            dev.deviceNotification(msg)
        } catch (e) {
            log.error("notifyTokenExpired: ${dev?.displayName}: ${e}")
        }
    }
    scheduleTokenExpiryReminder()
}

private void scheduleTokenExpiryReminder() {
    unschedule("sendTokenExpiryReminder")
    String freq = settings.notifyRepeatHours ?: "never"
    if (freq == "never") return
    int delaySecs = freq.toInteger() * SECS_PER_HOUR
    runIn(delaySecs, "sendTokenExpiryReminder")
    if (logEnable) log.debug("scheduleTokenExpiryReminder: next reminder in ${freq}h")
}

def sendTokenExpiryReminder() {
    if (!state.tokenLikelyExpired) return          // token refreshed, stop chain
    if (settings.notifyTokenExpiry == false) return // master toggle off
    if (isEmpty(settings.notifyDevices)) return
    String msg = "Life360 token still expired — re-paste a fresh access token"
    settings.notifyDevices.each { dev ->
        try {
            dev.deviceNotification(msg)
        } catch (e) {
            log.error("sendTokenExpiryReminder: ${dev?.displayName}: ${e}")
        }
    }
    log.warn("sendTokenExpiryReminder: token still expired, reminder sent")
    scheduleTokenExpiryReminder()  // schedule the next repeat
}

private Integer readRetryAfterSecs(Exception e) {
    try {
        def h = e.response?.getFirstHeader('Retry-After')
        if (h?.value != null) {
            return Integer.parseInt(h.value.toString().trim())
        }
    } catch (ignored) {}
    return null
}

private String life360BaseUrl(int version = 3) {
    // alternate base URL to try if cloudfront stops working:
    // return "https://api.life360.com/v${version}"
    return "https://api-cloudfront.life360.com/v${version}"
}

private Map life360Params(String path, int version = 3) {
    return [
        uri    : "${life360BaseUrl(version)}${path}",
        headers: getHttpHeaders(),
        timeout: HTTP_TIMEOUT_SECS
    ]
}

Map getHttpHeaders() {
    // try to match these headers:
    // - https://github.com/pnbruckner/life360/blob/master/life360/const.py#L7
    // - https://github.com/pnbruckner/life360/blob/master/life360/api.py#L46
    def baseHeaders = [
        "Accept"       : "application/json",
        "cache-control": "no-cache",
        // alternate Life360 mobile-app fingerprint to try (also requires X-Application header below):
        // "User-Agent"   : "com.life360.android.safetymapd/KNSTNB/24.50.0 android/14",
        // "X-Application": "com.life360.android.safetymapd",
        "User-Agent"   : "com.life360.android.safetymapd/KOKO/23.50.0 android/13",
    ]

    // part of newer API
    //    if (!isEmpty(circle)) {
    //        baseHeaders["circleid"] = circle
    //    }

    if (!isEmpty(access_token)) {
        baseHeaders["Authorization"] = "Bearer " + access_token
    }
    return baseHeaders
}

// ----------------------------------------------------------------------------
// Utility functions
// ----------------------------------------------------------------------------
static String header(text) {
    return "<div style='color:#ffffff;font-weight: bold;background-color:#8652ff;padding-top: 10px;padding-bottom: 10px;border: 1px solid #000000;box-shadow: 2px 3px #8B8F8F;border-radius: 10px'><image style='padding: 0px 10px 0px 10px;' src=https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/life360/images/logo-white.png width='50'> ${text}</div>"
}

static boolean isEmpty(text) {
    return text == null || text.isEmpty()
}

/**
 * Both privacy toggles default ON ("include in logs"). On a fresh install
 * settings.* is null until the user opens the app and hits Done — treat null
 * as the documented default so behavior matches the UI.
 */
boolean showNamesInLogs() {
    return settings.logShowNames != false
}

/**
 * called by child driver to honor the same toggle in its own logs
 */
boolean getShowNamesInLogs() {
    return showNamesInLogs()
}

/**
 * called by child driver to decide whether to include a Google Maps link in
 * its "moved" info log
 */
boolean getShowMapsLink() {
    return settings.logShowMapsLink != false
}

boolean getLogRawPayload() {
    return settings.logRawPayload == true
}

private String memberDisplayName(String memberId) {
    if (!showNamesInLogs()) return memberId
    return state.members?.find { it.id == memberId }?.firstName ?: memberId
}

// -------------------------------------------------------------------

/**
 * called when user hits DONE on app for the first time
 */
def installed() {
    log.info("installed")
    createChildDevices()
    subscribe(location, 'systemStart', initialize)
    refreshUserSettings()           // learn the account's units preference (settings.unitOfMeasure)
    state.scheduledBaseSecs = null  // force scheduleUpdates() to (re)arm
    scheduleUpdates()
}

/**
 * called when user hits DONE on app (already installed)
 */
def updated() {
    log.info("updated: pollFreq:${settings.pollFreq}, dynamicPolling:${settings.dynamicPolling}, dynamicPollFreq:${settings.dynamicPollFreq}, logEnable:${logEnable}, logRawPayload:${settings.logRawPayload}, notifyTokenExpiry:${settings.notifyTokenExpiry}, notifyRepeatHours:${settings.notifyRepeatHours}")
    // user clicked Done — assume any pasted token is fresh; let polling resume
    state.tokenLikelyExpired = false
    state.failCount = 0
    state.rateLimitedUntilMs = null
    state.message = null
    unschedule("sendTokenExpiryReminder")  // clear any pending repeat reminder (§5.2)
    state.tokenStatus = null        // clear so a freshly-pasted token doesn't show stale status
    state.memberCount = null        // re-baseline after any circle/membership config change
    state.lastCirclesFetchMs = null // fire circles check on next poll tick
    createChildDevices()
    refreshUserSettings()           // refresh the account's units preference (settings.unitOfMeasure)
    scheduleUpdates()
}

def initialize(evt) {
    log.info("initialize: ${evt.device} ${evt.value} ${evt.name}")
    clearSessionCache()             // clear any in-flight keys left over from before the hub restarted
    state.scheduledBaseSecs = null  // force scheduleUpdates() to (re)arm
    scheduleUpdates()
}


def uninstalled() {
    log.info("uninstalled")
    removeChildDevices(getChildDevices())
}

/**
 * can be called by child device to force update location
 */
def refresh() {
    fetchLocations()
    scheduleUpdates()
}

def scheduleUpdates() {
    // pick the desired base rate (no jitter), so we can detect no-op rebuilds
    Integer baseSecs
    int pollFreq = (settings.pollFreq ?: "60").toInteger()
    int dynamicPollFreq = (settings.dynamicPollFreq ?: "20").toInteger()
    boolean wantDynamic = (settings.dynamicPolling && state.memberInTransit
        && (pollFreq > dynamicPollFreq))
    if (wantDynamic) {
        baseSecs = dynamicPollFreq
    } else {
        baseSecs = pollFreq
    }

    // when token is flagged expired, slow polling to 5 minutes — fetchLocations
    // would early-return anyway, but the timer was still firing at the user's poll
    // rate (10s on aggressive installs). 5 min is fast enough to pick up a fresh
    // token quickly without spamming the scheduler.
    if (state.tokenLikelyExpired) {
        baseSecs = TOKEN_EXPIRED_POLL_SECS
        wantDynamic = false
    }

    boolean prevActive = (state.dynamicPollingActive == true)
    boolean modeFlipped = (prevActive != wantDynamic)

    // skip churn: if the base rate hasn't changed AND we've scheduled at least once,
    // don't tear down + re-arm the job. Stops scheduleUpdates() from re-firing the
    // same cron registration when called repeatedly from handleTimerFired/fetchLocations.
    if (!modeFlipped && state.scheduledBaseSecs != null && state.scheduledBaseSecs == baseSecs) {
        log.info("scheduleUpdates: no-op (baseSecs:${baseSecs} unchanged)")
        return
    }

    unschedule()
    // Re-arm the token-expiry reminder if the token is currently flagged expired —
    // unschedule() (no-arg) cancels all jobs, which would otherwise silently kill it.
    if (state.tokenLikelyExpired) notifyTokenExpired()
    state.dynamicPollingActive = wantDynamic
    state.scheduledBaseSecs = baseSecs
    state.pollIntervalSecs = baseSecs

    // pollFreq=0 means Disabled
    if (baseSecs <= 0) {
        log.info("scheduleUpdates: polling DISABLED (pollFreq=0)")
        return
    }

    // log.info — visibility into polling-mode flips and (re)schedules in production
    if (modeFlipped) {
        log.info("scheduleUpdates: polling mode -> ${wantDynamic ? 'DYNAMIC' : 'STANDARD'}, baseSecs:${baseSecs}")
    } else {
        log.info("scheduleUpdates: baseSecs:${baseSecs}, pollFreq:${settings.pollFreq}, dynamicPollFreq:${settings.dynamicPollFreq}")
    }

    if (baseSecs < SECS_PER_MIN) {
        schedule("0/${baseSecs} * * * * ? *", handleTimerFired)
    } else {
        schedule("0 */${(baseSecs / SECS_PER_MIN).toInteger()} * * * ? *", handleTimerFired)
    }
}


/**
 * called by timer
 */
def handleTimerFired() {
    // watchdog: if we haven't had a successful fetch in a long time, surface it loudly.
    // Threshold = max(pollFreq * 10, 10 minutes). state.message is rendered on mainPage,
    // and a log.warn shows up in Hubitat's main log so the user can see something is wrong
    // without needing to rely on the auto_reboot script.
    Long now = new Date().getTime()
    if (state.lastSuccessMs != null) {
        long ageMs = now - state.lastSuccessMs
        Integer pollFreqSec = (settings.pollFreq?.toString() ?: "60").toInteger()
        long thresholdMs = Math.max((long)(pollFreqSec * 10L * 1000L), (long)(10L * 60L * 1000L))
        if (ageMs > thresholdMs && !state.tokenLikelyExpired) {
            // only log on the rising edge — otherwise this would spam every poll tick
            if (!state.watchdogWarned) {
                long ageMin = (long)(ageMs / 60000L)
                log.warn("WATCHDOG: no successful Life360 update in ${ageMin} min")
                state.message = "WATCHDOG: no successful Life360 update in ${ageMin} min — token may be expired or Life360/Cloudflare is blocking; re-paste access token if needed."
                state.watchdogWarned = true
            }
        }
    }

    // circles check — at most once per minute; detects membership changes regardless of location fetch state
    long lastCirclesFetchMs = (state.lastCirclesFetchMs ?: 0L) as long
    if (now - lastCirclesFetchMs >= CIRCLES_POLL_INTERVAL_MS) {
        state.lastCirclesFetchMs = now
        def circlesParams = life360Params("/circles")
        def cookies = state["cookies"]
        if (cookies) circlesParams["headers"]["Cookie"] = cookies
        asynchttpGet("handleCirclesPollResponse", circlesParams)
    }

    // when the token is flagged expired, probe /users/me instead of fetching locations —
    // auto-recovers if the service healed (Cloudflare blip, transient outage, etc.)
    if (state.tokenLikelyExpired) {
        probeTokenAfterExpiry()
        return
    }

    if (!fetchLocations()) return
}

/**
 * Async /users/me probe fired by handleTimerFired when tokenLikelyExpired is set.
 * On 200: service is back — clear the flag and resume normal polling automatically.
 * On 401/403: token still dead — stay in degraded mode, reminders continue.
 * On network error or other status: stay quiet and try again next tick.
 */
private void probeTokenAfterExpiry() {
    if (isEmpty(access_token)) return
    def params = life360Params("/users/me")
    def cookies = state["cookies"]
    if (cookies) params["headers"]["Cookie"] = cookies
    asynchttpGet("handleTokenProbeResponse", params)
}

def handleTokenProbeResponse(response, data) {
    Integer status = response.status
    if (!status) {
        // network-level failure — stay quiet, try again next tick
        if (logEnable) log.debug("tokenProbe: network error — ${response.getErrorMessage() ?: "(no details)"}")
        return
    }
    captureCookiesAsync(response)
    if (status == 200) {
        // service is back — auto-recover; clear state before the JSON parse so a garbled
        // 200 body doesn't leave the app stuck in slow-poll (a valid 200 means auth is OK)
        log.info("tokenProbe: 200 OK — token is valid; auto-recovering from expired state")
        state.tokenLikelyExpired = false
        state.failCount = 0
        state.rateLimitedUntilMs = null
        state.message = null
        unschedule("sendTokenExpiryReminder")
        scheduleUpdates()   // restore normal polling rate
        try {
            captureUnitOfMeasure(response.json)
        } catch (e) {
            log.warn("tokenProbe: could not parse units from response body: ${e.message}")
        }
    } else if (status == 401 || status == 403) {
        if (logEnable) log.debug("tokenProbe: ${status} — token still expired; waiting for user to re-paste")
    } else {
        if (logEnable) log.debug("tokenProbe: unexpected status ${status} — will retry next tick")
    }
}

def handleCirclesPollResponse(response, data) {
    Integer status = response.status
    if (!status) {
        log.error("fetchCircles: poll: network error: ${response.getErrorMessage() ?: "(no details)"}")
        return
    }
    captureCookiesAsync(response)
    if (status != 200) {
        log.warn("fetchCircles: poll: unexpected status:${status}")
        return
    }

    def circle = response.json?.circles?.find { it.id == settings.circle }
    if (!circle) return

    String circleName = showNamesInLogs() ? (circle.name ?: circle.id) : circle.id
    int newCount = circle.memberCount?.toInteger() ?: 0

    if (state.memberCount == null) {
        state.memberCount = newCount
        if (isEmpty(state.members)) {
            log.info("fetchCircles: ${circleName}: memberCount:${newCount} — triggering initial member fetch")
            fetchMembers()
        } else {
            if (logEnable) log.debug("fetchCircles: ${circleName}: memberCount:${newCount} (initialized)")
        }
        return
    }

    int prevCount = state.memberCount as int
    if (newCount == prevCount) {
        if (logEnable) log.debug("fetchCircles: ${circleName}: memberCount:${newCount} (no change)")
    } else {
        log.info("fetchCircles: ${circleName}: ${prevCount} → ${newCount}")
        state.memberCount = newCount
        fetchMembers()
    }
}

def createChildDevices() {
    if (isEmpty(settings.users)) return;
    if (isEmpty(state.members)) return;

    // hoist getChildDevices() once — avoids O(N²) hub device-list walks (§4.4)
    Map childMap = getChildDevices().collectEntries { [it.deviceNetworkId, it] }

    settings.users.each { memberId ->
        String externalId = "${app.id}.${memberId}"
        if (!childMap.containsKey(externalId)) {
            def member = state.members.find { it.id == memberId }
            if (member == null) {
                log.warn("createChildDevices: member ${memberId} not found in state.members — skipping")
                return
            }
            def memberName = member.firstName
            log.info "createChildDevices: Creating Life360 Device: ${showNamesInLogs() ? memberName : memberId}"
            try {
                addChildDevice("jpage4500", "Life360+ Driver", externalId, null, ["name": "Life360 - ${memberName}", isComponent: false])
                log.info "createChildDevices: Child Device Successfully Created"
            }
            catch (e) {
                log.error "createChildDevices: Child device creation failed with error = ${e}"
            }
        }
    }

    // remove child devices whose member is no longer selected (orphan cleanup)
    Set<String> wantedDnis = settings.users.collect { "${app.id}.${it}".toString() } as Set
    childMap.each { dni, child ->
        if (!wantedDnis.contains(dni)) {
            log.info "createChildDevices: removing orphan child: ${child.displayName} (${dni})"
            try {
                deleteChildDevice(dni)
                // dni = "<appId>.<memberId>" — strip the app prefix to get the memberId
                String memberId = dni.contains('.') ? dni.substring(dni.indexOf('.') + 1) : dni
                state.remove("inTransit-${memberId}")
            } catch (e) {
                log.error "createChildDevices: failed to remove ${child.displayName}: ${e}"
            }
        }
    }

    // not enabling webhook as it doesn't appear to work anymore
    // createCircleSubscription()
}

private removeChildDevices(delete) {
    delete.each { deleteChildDevice(it.deviceNetworkId) }
}

def notifyChildDevice(memberId, memberObj, Map ctx = null) {
    if (isEmpty(settings.users)) return;
    if (isEmpty(settings.place)) return;
    if (isEmpty(state.places)) return;

    // use pre-built context from fetchLocations() poll cycle, or build on the spot (§4.2)
    String placesJson = ctx?.placesJson
    def home = ctx?.home
    if (placesJson == null) {
        def built = buildPlacesContext()
        placesJson = built.placesJson
        home = built.home
    }

    def externalId = "${app.id}.${memberId}"
    try {
        // find the appropriate child device based on app id and the device network id
        def deviceWrapper = getChildDevice("${externalId}")
        if (deviceWrapper != null) {
            // send location, places and home to device driver
            boolean inTransit = deviceWrapper.generatePresenceEvent(memberObj, placesJson, home)
            boolean prevInTransit = isMemberInTransit(memberId)
            boolean transitFlipped = (prevInTransit != inTransit)
            if (transitFlipped && logEnable) log.debug("notifyChildDevice: ${showNamesInLogs() ? memberObj.firstName : memberId}: state changed: inTransit:${inTransit}")
            // save inTransit state per member
            state["inTransit-${memberId}"] = inTransit
            // Re-evaluate the polling rate the instant a member's transit state flips,
            // using this fresh flag. The old synchronous dynamicPolling() call in
            // fetchLocations() ran before the async responses landed, so it always read
            // the previous tick's flags and lagged rate changes by a full poll cycle.
            if (transitFlipped && settings.dynamicPolling) dynamicPolling()
        } else {
            log.error("notifyChildDevice: device not found: ${externalId}")
        }
    } catch (e) {
        log.error "notifyChildDevice: Exception: member: ${memberObj}"
        log.error e
    }
}

private Map buildPlacesContext() {
    def thePlaces = state.places?.sort { a, b -> a.name <=> b.name } ?: []
    def home = state.places?.find { it.id == settings.place }
    def placesMap = [:]
    for (rec in thePlaces) {
        placesMap.put(rec.name, "${rec.latitude};${rec.longitude};${rec.radius}")
    }
    // serialize the places JSON once per poll — it's identical for every member,
    // so the driver shouldn't re-serialize it N times (§4.5)
    String placesJson = new groovy.json.JsonBuilder(placesMap).toString()
    return [placesJson: placesJson, home: home]
}

void captureCookies(response) {
    try {
        response.getHeaders('Set-Cookie')?.each {
            def cookieVal = it.value?.tokenize(';')?.getAt(0)
            if (getLogRawPayload()) log.trace("captureCookies: raw Set-Cookie: ${it.value}")
            if (cookieVal && cookieVal.contains("=")) {
                mergeCookie(cookieVal)
            }
        }
    } catch (e) {
        log.error("captureCookies: ${e.message}")
    }
    if (getLogRawPayload()) log.trace("captureCookies(sync): jar now [${cookieJarSummary()}]")
}

/**
 * Summarize the current cookie jar for logs. Always safe to log: shows cookie NAMES
 * (e.g. "__cf_bm, _cfuvid") so you can confirm the Cloudflare cookies are present without
 * leaking their values. When logRawPayload is on, also appends a short value head per cookie
 * for deeper debugging. Used to watch jar health before/after a Cloudflare 403.
 */
private String cookieJarSummary() {
    String jar = state["cookies"] ?: ""
    if (!jar) return "empty"
    boolean raw = getLogRawPayload()
    return jar.tokenize(";").collect { entry ->
        int eq = entry.indexOf("=")
        if (eq <= 0) return entry
        String name = entry.substring(0, eq)
        if (!raw) return name
        String val = entry.substring(eq + 1)
        return "${name}=${val.take(8)}…"
    }.join(", ")
}

void captureCookiesAsync(response) {
    // AsyncResponse headers are a plain Map — one value per name, no getHeaders() list.
    // The __cf_bm Cloudflare cookie arrives here; missing it causes 403 within minutes.
    try {
        def cookie = response.headers?.get("Set-Cookie")
        if (cookie) {
            def cookieVal = cookie.tokenize(';')?.getAt(0)
            // need a well-formed name=value to upsert; otherwise ignore this header
            if (cookieVal && cookieVal.contains("=")) {
                String name = cookieVal.substring(0, cookieVal.indexOf("="))
                boolean isNew = mergeCookie(cookieVal)
                if (logEnable) {
                    log.debug("captureCookiesAsync: ${isNew ? 'added' : 'updated'} '${name}'; jar now [${cookieJarSummary()}]")
                }
                if (getLogRawPayload()) log.trace("captureCookiesAsync: raw Set-Cookie head: ${cookieVal.take(60)}…")
            } else if (logEnable) {
                log.debug("captureCookiesAsync: ignored malformed Set-Cookie (no name=value)")
            }
        }
    } catch (e) {
        log.error("captureCookiesAsync: ${e.message}")
    }
}

/**
 * Upsert a single "name=value" cookie into the jar, preserving every other cookie.
 * Parse existing jar -> upsert this name -> re-serialize. This is the U1 fix: a rotating
 * __cf_bm replaces only its own entry and never drops _cfuvid.
 * Returns true if the cookie name was newly added, false if it updated an existing one.
 */
private boolean mergeCookie(String cookieVal) {
    int eq = cookieVal.indexOf("=")
    String name = cookieVal.substring(0, eq)
    String value = cookieVal.substring(eq + 1)
    Map<String, String> jar = [:]
    (state["cookies"] ?: "").tokenize(";").each { entry ->
        int e2 = entry.indexOf("=")
        if (e2 > 0) jar[entry.substring(0, e2)] = entry.substring(e2 + 1)
    }
    boolean isNew = !jar.containsKey(name)
    jar[name] = value
    state["cookies"] = jar.collect { k, v -> "${k}=${v}" }.join(";")
    return isNew
}

/**
 * Drop the session-bound cache (cookies + per-member etags). Mirrors what
 * ha-life360's coordinator does when it sees a LoginError before re-auth.
 */
void clearSessionCache() {
    if (logEnable) log.debug("clearSessionCache: dropping cookie jar [${cookieJarSummary()}] + etags/inflight/backoff (likely after auth failure)")
    state.remove("cookies")
    def toRemove = []
    state.each { k, v ->
        if (k?.toString()?.startsWith("etag-") || k?.toString()?.startsWith("inflight-") ||
            k?.toString()?.startsWith("transientCount-") || k?.toString()?.startsWith("transientUntilMs-")) toRemove << k
    }
    toRemove.each { state.remove(it) }
}

void dynamicPolling() {
    state.memberInTransit = false

    // iterate over every selected member
    settings.users.each { memberId ->
        boolean inTransit = isMemberInTransit(memberId)
        if (inTransit) {
            state.memberInTransit = true
        }
    }

    if (state.memberInTransit && ! state.dynamicPollingActive) {
        scheduleUpdates()
        if (logEnable) log.debug("dynamicPolling: switched STANDARD -> DYNAMIC (memberInTransit: ${state.memberInTransit}, dynamicPollingActive: ${state.dynamicPollingActive})")

    } else if (state.memberInTransit && state.dynamicPollingActive) {
        // already in dynamic mode — no-op

    } else if (! state.memberInTransit && state.dynamicPollingActive) {
        // §8.4: state.memberInTransit was already set to false at the top of this method
        scheduleUpdates()
        if (logEnable) log.debug("dynamicPolling: switched DYNAMIC -> STANDARD (memberInTransit: ${state.memberInTransit}, dynamicPollingActive: ${state.dynamicPollingActive})")
    } else {
        // if (logEnable) log.trace("dynamicPolling - DO NOTHING")
    }
}

boolean isMemberInTransit(memberId) {
    def inTransit = state["inTransit-${memberId}"]
    return inTransit != null && inTransit
}

// ----------------------------------------------------------------------------
// Map View — renders all selected members on a single OpenStreetMap (Leaflet)
// page. No API key required. Reached via the /view mapping.
// ----------------------------------------------------------------------------
def renderView() {
    def members = []
    getChildDevices().each { dev ->
        def lat = dev.currentValue("latitude")
        def lng = dev.currentValue("longitude")
        if (lat == null || lng == null) return
        try {
            double dlat = (lat as Number).doubleValue()
            double dlng = (lng as Number).doubleValue()
            if (dlat == 0.0d && dlng == 0.0d) return
            members << [
                name      : (dev.currentValue("memberName") ?: dev.displayName ?: "?").toString(),
                lat       : dlat,
                lng       : dlng,
                avatar    : (dev.currentValue("avatar") ?: "").toString(),
                address   : (dev.currentValue("address1") ?: "").toString(),
                battery   : (dev.currentValue("battery") ?: "").toString(),
                presence  : (dev.currentValue("presence") ?: "").toString(),
                updated   : (dev.currentValue("lastLocationUpdate") ?: "").toString(),
                inTransit : (dev.currentValue("inTransit") ?: "false").toString()
            ]
        } catch (e) {
            if (logEnable) log.debug("renderView: skipping ${dev.displayName}: ${e}")
        }
    }

    String membersJson = new groovy.json.JsonBuilder(members).toString()
    String apiKey = settings.googleMapsApiKey?.toString()?.trim()
    String html = isEmpty(apiKey) ?
        buildOsmMapHtml(membersJson, members.size()) :
        buildGoogleMapHtml(membersJson, members.size(), apiKey)
    render contentType: "text/html; charset=utf-8", data: html, status: 200
}

private String commonMapStyles() {
    return """
  html, body { height: 100%; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
  #map { position: absolute; top: 0; bottom: 0; left: 0; right: 0; }
  .l360-avatar { border-radius: 50%; border: 3px solid #8652ff; box-shadow: 0 2px 6px rgba(0,0,0,0.4); background: #fff; object-fit: cover; }
  .l360-pin { width: 36px; height: 36px; border-radius: 50%; background: #8652ff; color: #fff; display: flex; align-items: center; justify-content: center; font-weight: bold; border: 3px solid #fff; box-shadow: 0 2px 6px rgba(0,0,0,0.4); font-size: 14px; }
  .l360-popup { font-size: 13px; min-width: 180px; }
  .l360-popup b { color: #8652ff; }
  .l360-popup .row { margin-top: 4px; color: #444; }
  .l360-empty { padding: 40px; text-align: center; color: #666; font-size: 16px; }
  .l360-status { position: absolute; top: 10px; right: 10px; z-index: 1000; background: rgba(255,255,255,0.9); padding: 6px 10px; border-radius: 6px; font-size: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.2); }
"""
}

private String popupBuilderJs() {
    return """
  function buildPopup(m) {
    var popup = '<div class="l360-popup"><b>' + escapeHtml(m.name) + '</b>';
    if (m.address)  popup += '<div class="row">' + escapeHtml(m.address) + '</div>';
    if (m.presence) popup += '<div class="row">Presence: ' + escapeHtml(m.presence) + '</div>';
    if (m.inTransit === 'true') popup += '<div class="row">In transit</div>';
    if (m.battery)  popup += '<div class="row">Battery: ' + escapeHtml(m.battery) + '%</div>';
    if (m.updated)  popup += '<div class="row">Updated: ' + escapeHtml(m.updated) + '</div>';
    popup += '</div>';
    return popup;
  }
  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function(c) {
      return { '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c];
    });
  }
"""
}

private String buildOsmMapHtml(String membersJson, int count) {
    return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Life360 Map</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
      integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=" crossorigin="">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
        integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=" crossorigin=""></script>
<style>${commonMapStyles()}</style>
</head>
<body>
${count == 0 ? '<div class="l360-empty">No members with location data yet.<br>Wait for the next poll, then refresh.</div>' : '<div id="map"></div><div class="l360-status" id="status">Loading…</div>'}
<script>
  var members = ${membersJson};
  ${popupBuilderJs()}
  if (members.length > 0) {
    var map = L.map('map');
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
    }).addTo(map);

    var bounds = [];
    members.forEach(function(m) {
      var icon;
      if (m.avatar) {
        icon = L.divIcon({
          className: '',
          html: '<img class="l360-avatar" src="' + m.avatar + '" width="44" height="44">',
          iconSize: [44, 44],
          iconAnchor: [22, 22],
          popupAnchor: [0, -22]
        });
      } else {
        var initial = (m.name || '?').charAt(0).toUpperCase();
        icon = L.divIcon({
          className: '',
          html: '<div class="l360-pin">' + initial + '</div>',
          iconSize: [36, 36],
          iconAnchor: [18, 18],
          popupAnchor: [0, -18]
        });
      }
      L.marker([m.lat, m.lng], { icon: icon }).addTo(map).bindPopup(buildPopup(m));
      bounds.push([m.lat, m.lng]);
    });

    if (bounds.length === 1) {
      map.setView(bounds[0], 15);
    } else {
      map.fitBounds(bounds, { padding: [40, 40] });
    }

    document.getElementById('status').textContent = members.length + ' member' + (members.length === 1 ? '' : 's') + ' · OSM · auto-refresh 60s';
    setTimeout(function() { window.location.reload(); }, 60000);
  }
</script>
</body>
</html>
"""
}

private String buildGoogleMapHtml(String membersJson, int count, String apiKeyRaw) {
    String apiKey = apiKeyRaw
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Life360 Map</title>
<style>${commonMapStyles()}</style>
</head>
<body>
${count == 0 ? '<div class="l360-empty">No members with location data yet.<br>Wait for the next poll, then refresh.</div>' : '<div id="map"></div><div class="l360-status" id="status">Loading…</div>'}
<script>
  var members = ${membersJson};
  ${popupBuilderJs()}
  async function initMap() {
    if (members.length === 0) return;
    var { Map, InfoWindow } = await google.maps.importLibrary('maps');
    var { AdvancedMarkerElement } = await google.maps.importLibrary('marker');

    var map = new Map(document.getElementById('map'), {
      mapTypeControl: true,
      streetViewControl: false,
      fullscreenControl: true,
      mapId: 'DEMO_MAP_ID'
    });
    var bounds = new google.maps.LatLngBounds();
    var infoWindow = new InfoWindow();

    members.forEach(function(m) {
      var pos = { lat: m.lat, lng: m.lng };
      var content;
      if (m.avatar) {
        content = document.createElement('img');
        content.className = 'l360-avatar';
        content.src = m.avatar;
        content.width = 44;
        content.height = 44;
      } else {
        content = document.createElement('div');
        content.className = 'l360-pin';
        content.textContent = (m.name || '?').charAt(0).toUpperCase();
      }
      var marker = new AdvancedMarkerElement({
        position: pos,
        map: map,
        title: m.name,
        content: content
      });
      marker.addListener('gmp-click', function() {
        infoWindow.setContent(buildPopup(m));
        infoWindow.open(map, marker);
      });
      bounds.extend(pos);
    });

    if (members.length === 1) {
      map.setCenter({ lat: members[0].lat, lng: members[0].lng });
      map.setZoom(15);
    } else {
      map.fitBounds(bounds, 60);
    }

    document.getElementById('status').textContent = members.length + ' member' + (members.length === 1 ? '' : 's') + ' · Google · auto-refresh 60s';
    setTimeout(function() { window.location.reload(); }, 60000);
  }
</script>
<script async defer src="https://maps.googleapis.com/maps/api/js?key=${apiKey}&libraries=marker&callback=initMap&v=weekly"></script>
</body>
</html>
"""
}
