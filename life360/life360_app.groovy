import java.net.http.HttpTimeoutException
import java.net.SocketTimeoutException

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** LIFE360+ Hubitat App **
 * Life360 companion app to track members' location in your circle
 * - Community discussion: https://community.hubitat.com/t/release-life360/118544
 *
 * Changes:
 *  5.1.3  - 05/09/26 - add /view endpoint to view members on a map
 *  5.1.2  - 05/01/26 - merge in changes by iEnam: API change (api-cloudfront.life360.com); better cookie handling
 *  5.1.1 -  05/01/26 - add device notification when token expires
 *  5.1.0  - 05/01/26 - hardening: HTTP timeouts; classify 401/403/429/5xx in handleException;
 *           clear cookies+etags on auth error; backoff on rate-limit; watchdog warns
 *           when no successful update in N minutes; loud banner when token expired
 *  5.0.15 - 12/31/24 - minor fixes
 *  5.0.14 - 12/24/24 - Dynamic Polling
 *  5.0.13 - 12/24/24 - restore original scheduling routine
 *  5.0.10 - 12/21/24 - add some randomness
 *  5.0.9  - 12/19/24 - try a different API when hitting 403 error
 *  5.0.8  - 12/18/24 - added cookies found by @user3774
 *  5.0.7  - 12/11/24 - try to match Home Assistant
 *  5.0.6  - 12/05/24 - return to older API version (keeping eTag support)
 *  5.0.5  - 11/12/24 - support eTag for locations call
 *  5.0.4  - 11/09/24 - use newer API
 *  5.0.2  - 11/03/24 - restore webhook
 *  5.0.0  - 11/01/24 - fix Life360+ support (requires manual entry of access_token)
 *  4.0.0  -  02/08/24 - implement new Life360 API
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
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section() {
            href name: "myHref", url: "https://joe-page-software.gitbook.io/hubitat-dashboard/tiles/location-life360/life360+#configuration", title: "Step-by-step instructions", style: "external"
            showMessage(state.message)
        }
        section(header("STEP 1: Access Token")) {
            input 'access_token', 'text', title: 'Access Token', required: true, defaultValue: '', submitOnChange: true
        }

        if (!isEmpty(access_token)) {
            section(header("STEP 2: Life360 Circles")) {
                input("fetchCirclesBtn", "button", title: "Fetch Circles")

                if (!isEmpty(state.circles)) {
                    input "circle", "enum", multiple: false, required: true, title: "Life360 Circle", options: state.circles.collectEntries { [it.id, it.name] }, submitOnChange: true
                }
            }

            section(header("STEP 3: Select HOME")) {
                input("fetchPlacesBtn", "button", title: "Fetch Places")

                if (!isEmpty(state.places)) {
                    paragraph "Please select the ONE Life360 Place that matches your Hubitat location: ${location.name}"
                    thePlaces = state.places.collectEntries { [it.id, it.name] }
                    sortedPlaces = thePlaces.sort { a, b -> a.value <=> b.value }
                    input "place", "enum", multiple: false, required: true, title: "Life360 Places: ", options: sortedPlaces, submitOnChange: true
                }
            }

            section(header("STEP 4: Select Members to track")) {
                input("fetchMembersBtn", "button", title: "Fetch Members")

                if (!isEmpty(state.members)) {
                    theMembers = state.members.collectEntries { [it.id, it.firstName + " " + it.lastName] }
                    sortedMembers = theMembers.sort { a, b -> a.value <=> b.value }
                    input "users", "enum", multiple: true, required: false, title: "Life360 Members: ", options: sortedMembers, submitOnChange: true
                }
            }
        }

        section(header("Other Options")) {
            input(name: "pollFreq", type: "enum", title: "Default Refresh Rate", required: true, defaultValue: "60", options: ['10': '10 seconds', '15': '15 seconds', '30': '30 seconds', '60': '1 minute', '180': '3 minutes', '300': '5 minutes', '0': 'Disabled'])
            input(name: "dynamicPolling", type: "bool", defaultValue: "false", submitOnChange: "true", title: "Enable Dynamic Polling - Increase polling frequency to 'Dynamic Refesh Rate' when a member is in motion.  Polling returns to 'Default Refresh Rate' once they've stopped.", description: "Increase polling frequency when a member is in motion.  Polling returns to 'Refresh Rate' once still.")
            input(name: "dynamicPollFreq", type: "enum", title: "Dynamic Refesh Rate", required: false, defaultValue: "20", options: ['5': '5 seconds', '10': '10 seconds', '20': '20 seconds', '30': '30 seconds'])
            input(name: "notifyDevices", type: "capability.notification", title: "Notify Device on Token Failure (for debugging)", multiple: true, required: false, description: "Devices to notify when the Life360 access token appears expired/revoked.")
            input(name: "logEnable", type: "bool", defaultValue: "false", submitOnChange: "true", title: "Enable Debug Logging", description: "Enable extra logging for debugging.")
            input("fetchLocationsBtn", "button", title: "Fetch Locations")
        }

        section(header("Map View")) {
            input(name: "googleMapsApiKey", type: "text", title: "Google Maps API Key (optional)", required: false, defaultValue: "", submitOnChange: true, description: "If set, the map view uses Google Maps. Otherwise OpenStreetMap is used (no key required).")
            String viewUrl = getViewUrl()
            if (viewUrl) {
                paragraph "<a href='${viewUrl}' target='_blank'>Open Map View</a><br><small style='color:#888'>${viewUrl}</small>"
            } else {
                input("generateViewLinkBtn", "button", title: "Generate Map Link")
                paragraph "<small style='color:#888'>Enable OAuth on this app (top-right of the app source code editor), then click 'Generate Map Link'.</small>"
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
        default:
            log.debug("appButtonHandler: unhandled:${button}")
    }
}

private void generateViewLink() {
    try {
        if (isEmpty(state.accessToken)) {
            createAccessToken()
        }
    } catch (e) {
        log.error("generateViewLink: failed to create access token — enable OAuth in the app source code editor first: ${e}")
        state.message = "Enable OAuth on the app (top-right of the app source code editor), then try again."
    }
}

def showMessage(text) {
    if (!isEmpty(text)) {
        paragraph("<p style='color:red; font-weight: bold'>${text}</p>")
    }
}

def fetchCircles() {
    def params = life360Params("/circles")
    if (logEnable) log.debug "fetchCircles:"
    try {
        httpGet(params) {
            response ->
                captureCookies(response)
                if (response.status == 200) {
                    state.circles = response.data.circles
                    if (logEnable) log.debug("fetchCircles: DONE")
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
        log.debug("fetchPlaces: circle not set")
        return;
    }

    def params = life360Params("/circles/${circle}/places.json")
    log.debug("fetchPlaces:")
    try {
        httpGet(params) {
            response ->
                captureCookies(response)
                if (response.status == 200) {
                    state.places = response.data.places
                    if (logEnable) log.debug("fetchPlaces: DONE")
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
        log.debug("fetchMembers: circle not set")
        return;
    }

    def params = life360Params("/circles/${circle}/members")

    if (logEnable) log.debug("fetchMembers:")

    try {
        httpGet(params) {
            response ->
                captureCookies(response)
                //if (logEnable) log.debug("fetchMembers: ${response.data}")
                if (response.status == 200) {
                    state.members = response.data?.members
                    state.message = null

                    // update child devices
                    settings.users.each { memberId ->
                        def externalId = "${app.id}.${memberId}"
                        def deviceWrapper = getChildDevice("${externalId}")
                        if (!deviceWrapper) {
                            def member = state.members.find { it.id == memberId }
                            notifyChildDevice(memberId, member)
                        }
                    }
                } else {
                    log.error("fetchMembers: bad response:${response.status}, ${response.data}")
                    state.message = "fetchMembers: bad response:${response.status}, ${response.data}"
                }
        }
    } catch (e) {
        handleException("fetch members", e)
    }
}

/**
 * fetch location for every member
 */
boolean fetchLocations() {
    if (isEmpty(circle)) {
        log.debug("fetchLocations: circle not set")
        return false
    } else if (isEmpty(settings.users)) {
        log.debug("fetchLocations: no users selected")
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

    // prevent calling this API too frequently (< 5 seconds)
    if (state.lastUpdateMs != null) {
        long lastAttempt = Math.round((long) (currentTimeMs - state.lastUpdateMs) / 1000L)
        if (lastAttempt < 5) {
            //if (logEnable) log.trace "fetchLocations: TOO_FREQUENT: last:${lastAttempt}ms"
            state.message = "TOO_FREQUENT: please wait 5 secs between calls! last:${lastAttempt}ms"
            return false
        }
    }
    state.lastUpdateMs = currentTimeMs

    // iterate over every selected member
    settings.users.each { memberId ->
        fetchMemberLocation(memberId)
    }

    if (settings.dynamicPolling) {
        dynamicPolling()
    }

    return true
}

def fetchMemberLocation(memberId) {
    def params = life360Params("/circles/${circle}/members/${memberId}")

    // add cookies to header
    def cookies = state["cookies"]
    if (cookies) {
        params["headers"]["Cookie"] = cookies
        //if (logEnable) log.debug("fetchMemberLocation: cookie: ${cookies}")
    }

    // set l360-etag value
    def tag = state["etag-${memberId}"]
    if (tag) {
        params["headers"]["If-None-Match"] = tag
        //if (logEnable) log.debug("eTag header:  ${tag}")
    }

    //if (logEnable) log.trace("fetchMemberLocation: member:${memberId}, tag:${tag}")

    try {
        httpGet(params) {
            response ->

/*
                if (response.data) {
                    log.trace("fetchMemberLocation (${response.status}) - ${response.data["firstName"]}: inTransit ${response.data["location"].inTransit} || speed ${response.data["location"].speed.toInteger()} || memberInTransit ${state.memberInTransit}")
                }
*/
                captureCookies(response)

                if (response.status == 200) {
                    // if (logEnable) log.trace("fetchMemberLocation: SUCCESS: member:${memberId}: ${response.data}")
                    if (logEnable) log.trace("fetchMemberLocation: SUCCESS (200): member:${memberId}")

                    // update child devices
                    notifyChildDevice(memberId, response.data)

                    state.failCount = 0
                    state.tokenLikelyExpired = false
                    state.rateLimitedUntilMs = null
                    state.message = null
                    state.lastSuccessMs = new Date().getTime()

                    // save l360-etag value for next request
                    def eTag = response.getFirstHeader("l360-etag")
                    if (eTag != null) state["etag-${memberId}"] = eTag.value
                } else if (response.status == 304) {
                    state.message = null
                    state.lastSuccessMs = new Date().getTime()
                    // NOTE: if user WAS inTransit before, do we keep them in that state?
                    boolean prevInTransit = isMemberInTransit(memberId)
                    if (logEnable) log.trace("fetchMemberLocation: SUCCESS (304), member:${memberId}, prevInTransit:${prevInTransit}")
                } else {
                    log.error("fetchMemberLocation: bad response:${response.status}, ${response.data}")
                    state.message = "fetchMemberLocation: bad response:${response.status}, ${response.data}"
                }
        }
    } catch (e) {
        // handleException classifies and updates state.failCount / state.tokenLikelyExpired /
        // state.rateLimitedUntilMs / state.cookies as appropriate. Do NOT synchronously retry —
        // let the next scheduled tick try again (fetchLocations short-circuits on AUTH/RATE_LIMIT).
        handleException("fetchMemberLocation: member:${memberId}", e)
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
        log.warn("${tag}: TIMEOUT: ${e}")
        state.message = "TIMEOUT: ${tag}"
        return "TIMEOUT"
    }
    if (status == 401 || status == 403) {
        // ha-life360 treats 403 the same as 401: clear session, back off, surface to user.
        clearSessionCache()
        state.failCount = (state.failCount ?: 0) + 1
        log.warn("${tag}: AUTH (${status}); cleared session; failCount=${state.failCount}")
        if (state.failCount >= 3) {
            boolean wasExpired = state.tokenLikelyExpired ?: false
            state.tokenLikelyExpired = true
            state.message = "⚠ Access token appears expired/revoked (HTTP ${status} x${state.failCount}). Re-paste a fresh token from life360.com → DevTools → Network → token packet."
            if (!wasExpired) notifyTokenExpired()
        } else {
            state.message = "AUTH ERROR (${status}) on ${tag}; cleared session, will retry"
        }
        return "AUTH"
    }
    if (status == 429) {
        Integer retryAfter = readRetryAfterSecs(e)
        Integer delaySecs = (retryAfter ?: 60) + 10
        state.rateLimitedUntilMs = new Date().getTime() + (delaySecs * 1000L)
        log.warn("${tag}: RATE_LIMIT (429); backing off ${delaySecs}s")
        state.message = "RATE LIMITED (429); backing off ${delaySecs}s"
        return "RATE_LIMIT"
    }
    if (status != null && (status == 502 || status == 503 || status == 504 || status == 520)) {
        log.warn("${tag}: TRANSIENT (${status}); will retry next tick")
        state.message = "TRANSIENT ${status} on ${tag}"
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
    String msg = "Life360 token expired"
    settings.notifyDevices.each { dev ->
        try {
            dev.deviceNotification(msg)
        } catch (e) {
            log.error("notifyTokenExpired: ${dev?.displayName}: ${e}")
        }
    }
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

private String life360BaseUrl() {
    // alternate base URL to try if cloudfront stops working:
    // return "https://api.life360.com/v3"
    return "https://api-cloudfront.life360.com/v3"
}

private Map life360Params(String path) {
    return [
        uri    : "${life360BaseUrl()}${path}",
        headers: getHttpHeaders(),
        timeout: 30
    ]
}

Map getHttpHeaders() {
    if (isEmpty(state.deviceId)) {
        state.deviceId = UUID.randomUUID().toString()
    }

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

// -------------------------------------------------------------------

/**
 * called when user hits DONE on app for the first time
 */
def installed() {
    log.debug("installed")
    createChildDevices()
    // re-schedule updates on reboot; TODO: is this needed?
    subscribe(location, 'systemStart', initialize)
    state.scheduledBaseSecs = null  // force scheduleUpdates() to (re)arm
    scheduleUpdates()
}

/**
 * called when user hits DONE on app (already installed)
 */
def updated() {
    log.debug("updated:")
    // user clicked Done — assume any pasted token is fresh; let polling resume
    state.tokenLikelyExpired = false
    state.failCount = 0
    state.rateLimitedUntilMs = null
    createChildDevices()
    state.scheduledBaseSecs = null  // force scheduleUpdates() to (re)arm
    scheduleUpdates()
}

def initialize(evt) {
    log.debug("initialize: ${evt.device} ${evt.value} ${evt.name}")
    state.scheduledBaseSecs = null  // force scheduleUpdates() to (re)arm
    scheduleUpdates()
}

def initialize() {
    log.debug("initialize")
    state.message = null
}

def uninstalled() {
    log.debug("uninstalled")
    removeChildDevices(getChildDevices())
}

/**
 * can be called by child device to force update location
 */
def refresh() {
    if (logEnable) log.debug("refresh:")
    fetchLocations()
    scheduleUpdates()
}

def scheduleUpdates() {
    // pick the desired base rate (no jitter), so we can detect no-op rebuilds
    Integer baseSecs
    boolean wantDynamic = (settings.dynamicPolling && state.memberInTransit
        && (settings.pollFreq.toInteger() > settings.dynamicPollFreq.toInteger()))
    if (wantDynamic) {
        baseSecs = settings.dynamicPollFreq.toInteger()
    } else {
        baseSecs = settings.pollFreq.toInteger()
    }

    boolean prevActive = (state.dynamicPollingActive == true)
    boolean modeFlipped = (prevActive != wantDynamic)

    // skip churn: if the base rate hasn't changed AND we've scheduled at least once,
    // don't tear down + re-arm the job. Stops scheduleUpdates() from re-firing the
    // same cron registration when called repeatedly from handleTimerFired/fetchLocations.
    if (!modeFlipped && state.scheduledBaseSecs != null && state.scheduledBaseSecs == baseSecs) {
        if (logEnable) log.debug("scheduleUpdates: no-op (baseSecs:${baseSecs} unchanged)")
        return
    }

    unschedule()
    state.dynamicPollingActive = wantDynamic
    state.scheduledBaseSecs = baseSecs

    // add some randomness to this value (between 0 and 5 seconds)
    Integer random = Math.abs(new Random().nextInt() % 5)
    Integer refreshSecs = baseSecs + random

    // log.info — visibility into polling-mode flips and (re)schedules in production
    if (modeFlipped) {
        log.info("scheduleUpdates: polling mode -> ${wantDynamic ? 'DYNAMIC' : 'STANDARD'}, baseSecs:${baseSecs}")
    } else if (logEnable) {
        log.debug("scheduleUpdates: refreshSecs:${refreshSecs}, pollFreq:${settings.pollFreq}, random:${random}, dynamicPollFreq: ${settings.dynamicPollFreq}")
    }

    if (refreshSecs > 0 && refreshSecs < 60) {
        // seconds
        schedule("0/${refreshSecs} * * * * ? *", handleTimerFired)
    } else if (refreshSecs > 0) {
        // mins
        schedule("0 */${(refreshSecs / 60).toInteger()} * * * ? *", handleTimerFired)
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
        Integer pollFreqSec = ((settings.pollFreq ?: "60").toString()).toInteger()
        long thresholdMs = Math.max((long)(pollFreqSec * 10L * 1000L), (long)(10L * 60L * 1000L))
        if (ageMs > thresholdMs && !state.tokenLikelyExpired) {
            long ageMin = (long)(ageMs / 60000L)
            log.warn("WATCHDOG: no successful Life360 update in ${ageMin} min")
            state.message = "WATCHDOG: no successful Life360 update in ${ageMin} min — token may be expired or Life360/Cloudflare is blocking; re-paste access token if needed."
        }
    }

    if (!fetchLocations()) return

    // change things up every 10 minutes or so
    Long currentTimeMs = new Date().getTime()
    Long updateTimeMs = state.updateTimeMs ?: 0
    if (currentTimeMs > updateTimeMs) {
        // update again in 5-10 minutes
        Integer random = Math.abs(new Random().nextInt() % 300000) + 300000
        state.updateTimeMs = currentTimeMs + random
        if (logEnable) log.info "handleTimerFired: changing things up; ${random}ms"

        // re-schedule timer to add a little randomness
        scheduleUpdates()

        // this call doesn't use cookies
        fetchMembers()
    }
}

def createChildDevices() {
    if (isEmpty(settings.users)) return;
    if (isEmpty(state.members)) return;

    settings.users.each { memberId ->
        def externalId = "${app.id}.${memberId}"
        def deviceWrapper = getChildDevice("${externalId}")
        if (!deviceWrapper) {
            def member = state.members.find { it.id == memberId }
            def memberName = member.firstName
            def childList = getChildDevices()
            if (childList.find { it.data.vcId == "${member}" }) {
                if (logEnable) log.info "createChildDevices: ${memberName} already exists...skipping"
            } else {
                log.info "createChildDevices: Creating Life360 Device: ${memberName}"
                try {
                    addChildDevice("jpage4500", "Life360+ Driver", externalId, 1234, ["name": "Life360 - ${memberName}", isComponent: false])
                    log.info "createChildDevices: Child Device Successfully Created"
                }
                catch (e) {
                    log.error "createChildDevices: Child device creation failed with error = ${e}"
                }
            }
        }
    }

    // not enabling webhook as it doesn't appear to work anymore
    // createCircleSubscription()
}

private removeChildDevices(delete) {
    delete.each { deleteChildDevice(it.deviceNetworkId) }
}

def notifyChildDevice(memberId, memberObj) {
    if (isEmpty(settings.users)) return;
    if (isEmpty(settings.place)) return;
    if (isEmpty(state.places)) return;

    // Get a *** sorted *** list of places for easier navigation
    def thePlaces = state.places.sort { a, b -> a.name <=> b.name }
    def home = state.places.find { it.id == settings.place }

    placesMap = [:]
    for (rec in thePlaces) {
        placesMap.put(rec.name, "${rec.latitude};${rec.longitude};${rec.radius}")
    }

    def externalId = "${app.id}.${memberId}"
    try {
        // find the appropriate child device based on app id and the device network id
        def deviceWrapper = getChildDevice("${externalId}")
        if (deviceWrapper != null) {
            // send location, places and home to device driver
            boolean inTransit = deviceWrapper.generatePresenceEvent(memberObj, placesMap, home)
            boolean prevInTransit = isMemberInTransit(memberId)
            if (prevInTransit != inTransit && logEnable) log.trace("notifyChildDevice: ${memberObj.firstName}: state changed: inTransit:${inTransit}")
            // save inTransit state per member
            state["inTransit-${memberId}"] = inTransit
        } else {
            log.error("notifyChildDevice: device not found: ${externalId}")
        }
    } catch (e) {
        log.error "notifyChildDevice: Exception: member: ${memberObj}"
        log.error e
    }
}

void captureCookies(response) {
    def responseCookies = []
    try {
        // Extract just the "Set-Cookie" headers from the Response.
        response.getHeaders('Set-Cookie')?.each {
            def cookie = it.value?.tokenize(';|,')?.getAt(0)
            if (cookie) responseCookies << cookie
            if (logEnable) log.trace("captureCookies: ${it.value}")
        }
    } catch (e) {
        if (logEnable) log.trace("captureCookies: ${e.message}")
    }
    if (responseCookies) {
        state["cookies"] = responseCookies.join(";")
    }
}

/**
 * Drop the session-bound cache (cookies + per-member etags). Mirrors what
 * ha-life360's coordinator does when it sees a LoginError before re-auth.
 */
void clearSessionCache() {
    state.remove("cookies")
    def toRemove = []
    state.each { k, v ->
        if (k?.toString()?.startsWith("etag-")) toRemove << k
    }
    toRemove.each { state.remove(it) }
}

void dynamicPolling() {

    // if (logEnable) log.trace("dynamicPolling - INFO: dynamicPolling:${dynamicPolling} || memberInTransit:${state.memberInTransit} || dynamicPollingActive:${state.dynamicPollingActive}")

    state.memberInTransit = false

    // iterate over every selected member
    settings.users.each { memberId ->
        boolean inTransit = isMemberInTransit(memberId)
        if (inTransit) {
            state.memberInTransit = true
        }
    }

    if (state.memberInTransit && ! state.dynamicPollingActive) {
        if (logEnable) log.debug("dynamicPolling - SET DYNAMIC POLLING - memberInTransit: ${state.memberInTransit} || dynamicPollingActive: ${state.dynamicPollingActive}")
        scheduleUpdates()

    } else if (state.memberInTransit && state.dynamicPollingActive) {
        //if (logEnable) log.debug("dynamicPolling - ALREADY ACTIVE - memberInTransit: ${state.memberInTransit} || dynamicPollingActive: ${state.dynamicPollingActive}")

    } else if (! state.memberInTransit && state.dynamicPollingActive) {
        if (logEnable) log.debug("dynamicPolling - SET STANDARD POLLING - memberInTransit: ${state.memberInTransit} || dynamicPollingActive: ${state.dynamicPollingActive}")
        state.memberInTransit = false
        scheduleUpdates()
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
    render contentType: "text/html", data: html, status: 200
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

private String buildGoogleMapHtml(String membersJson, int count, String apiKey) {
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