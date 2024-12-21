// hubitat start
// hub: 192.168.0.200
// type: app
// id: 684
// hubitat end

import java.net.http.HttpTimeoutException

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** LIFE360+ Hubitat App **
 * Life360 companion app to track members' location in your circle
 * - see discussion: https://community.hubitat.com/t/release-life360/118544
 *
 * Changes:
 *  5.0.9 - 12/19/24 - try a different API when hitting 403 error
 *  5.0.8 - 12/18/24 - added cookies found by @user3774
 *  5.0.7 - 12/11/24 - try to match Home Assistant
 *  5.0.6 - 12/05/24 - return to older API version (keeping eTag support)
 *  5.0.5 - 11/12/24 - support eTag for locations call
 *  5.0.4 - 11/09/24 - use newer API
 *  5.0.2 - 11/03/24 - restore webhook
 *  5.0.0 - 11/01/24 - fix Life360+ support (requires manual entry of access_token)
 *  4.0.0 - 02/08/24 - implement new Life360 API
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
)

preferences {
    page(name: "mainPage")
}

mappings {
    // used for Life360 webhook
    path("/placecallback") {
        action:
        [
            POST: "placeEventHandler",
            GET : "placeEventHandler"
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
            input(name: "pollFreq", type: "enum", title: "Refresh Rate", required: true, defaultValue: "60", options: ['10': '10 seconds', '15': '15 seconds', '30': '30 seconds', '60': '1 minute', '300': '5 minutes', '0': 'Disabled'])
            input(name: "logEnable", type: "bool", defaultValue: "false", submitOnChange: "true", title: "Enable Debug Logging", description: "Enable extra logging for debugging.")
            input("fetchLocationsBtn", "button", title: "Fetch Locations")
        }
    }
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
        default:
            log.debug("appButtonHandler: unhandled:${button}")
    }
}

def showMessage(text) {
    if (!isEmpty(text)) {
        paragraph("<p style='color:red; font-weight: bold'>${text}</p>")
    }
}

def fetchCircles() {
    // https://api-cloudfront.life360.com/v3/circles.json
    def params = [
        uri    : "https://api-cloudfront.life360.com",
        path   : "/v3/circles.json",
        headers: getHttpHeaders()
    ]
    if (logEnable) log.debug "fetchCircles:"
    try {
        httpGet(params) {
            response ->
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

    // https://api-cloudfront.life360.com/v3/circles/${circle}/places.json
    def params = [
        uri    : "https://api-cloudfront.life360.com",
        path   : "/v3/circles/${circle}/places.json",
        headers: getHttpHeaders()
    ]
    log.debug("fetchPlaces:")
    try {
        httpGet(params) {
            response ->
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

    // https://api-cloudfront.life360.com/v3/circles/CIRCLE/members
    def params = [
        uri    : "https://api-cloudfront.life360.com",
        path   : "/v3/circles/${circle}/members",
        headers: getHttpHeaders()
    ]

    log.debug("fetchMembers:")

    try {
        httpGet(params) {
            response ->
                captureCookies(response)
                if (logEnable) log.debug("fetchMembers: ${response.data}")
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
def fetchLocations() {
    if (isEmpty(circle)) {
        log.debug("fetchLocations: circle not set")
        return
    } else if (isEmpty(settings.users)) {
        log.debug("fetchLocations: no users selected")
        return
    }

    // prevent calling this API too frequently (< 5 seconds)
    long currentTimeMs = new Date().getTime()
    if (state.lastUpdateMs != null) {
        long lastAttempt = Math.round((long) (currentTimeMs - state.lastUpdateMs) / 1000L)
        if (lastAttempt < 5) {
            if (logEnable) log.trace "fetchLocations: TOO_FREQUENT: last:${lastAttempt}ms"
            state.message = "TOO_FREQUENT: please wait 5 secs between calls! last:${lastAttempt}ms"
            return
        }
        if (logEnable) log.trace "fetchLocations: last:${lastAttempt}ms"
    }
    state.lastUpdateMs = currentTimeMs

    // iterate over every selected member
    settings.users.each { memberId ->
        fetchMemberLocation(memberId)
    }

    // re-schedule timer to add a little randomness
    scheduleUpdates()
}

def fetchMemberLocation(memberId) {
    // https://api-cloudfront.life360.com/v3/circles/CIRCLE/members/MEMBER
    def params = [
        uri    : "https://api-cloudfront.life360.com",
        path   : "/v3/circles/${circle}/members/${memberId}",
        headers: getHttpHeaders()
    ]

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
                captureCookies(response)
                if (response.status == 200) {
                    // if (logEnable) log.trace("fetchMemberLocation: SUCCESS: member:${memberId}: ${response.data}")
                    if (logEnable) log.trace("fetchMemberLocation: SUCCESS: member:${memberId}")

                    // update child devices
                    notifyChildDevice(memberId, response.data)

                    state.failCount = 0
                    state.message = null
                    state.lastSuccessMs = new Date().getTime()

                    // save l360-etag value for next request
                    def eTag = response.getFirstHeader("l360-etag")
                    if (eTag != null) state["etag-${memberId}"] = eTag.value
                } else if (response.status == 304) {
                    state.message = null
                    state.lastSuccessMs = new Date().getTime()
                    if (logEnable) log.trace("fetchMemberLocation: SUCCESS (304), member:${memberId}")
                } else {
                    log.error("fetchMemberLocation: bad response:${response.status}, ${response.data}")
                    state.message = "fetchMemberLocation: bad response:${response.status}, ${response.data}"
                }
        }
    } catch (e) {
        handleException("fetchMemberLocation: member:${memberId}", e)
        def status = e.response?.status
        if (status == 403) {
            // if this call fails with a 403 error, try a different API to capture updated cookies
            // alternately we could try clearing cookies and re-trying same call..
            state.failCount = (state.failCount ?: 0) + 1
            // NOTE: I'm setting a limit on how many times this will try fetchMembers()
            if (state.failCount <= 10) {
                log.debug("fetchMemberLocation: RETRY, fail:${state.failCount}")
                fetchMembers()
            }
        }
    }
}

def handleException(String tag, Exception e) {
    //log.error("handleException: ${e}")
    if (e instanceof HttpTimeoutException) {
        log.error("${tag}: EXCEPTION: ${e}")
        state.message = "TIMEOUT: ${tag}: ${e}"
        return
    }
    def status = e.response?.status
    if (status == 403) {
        log.error("handleException: ${tag}: ${status}")
        state.message = "ERROR: ${tag}: ${status}"
        return
    }
    def err = e.response?.data
    log.error("handleException: ${tag}: ${status}: ${err}")
    state.message = "ERROR: ${tag}: ${status}, ${err}"
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
        "User-Agent"   : "com.life360.android.safetymapd/KOKO/23.50.0 android/13",
    ]

    // TODO: not sure this is necessary for old API
    if (!isEmpty(circle)) {
        baseHeaders["circleid"] = circle
    }

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
    scheduleUpdates()
}

/**
 * called when user hits DONE on app (already installed)
 */
def updated() {
    log.debug("updated:")
    createChildDevices()
    scheduleUpdates()
}

def initialize(evt) {
    log.debug("initialize: ${evt.device} ${evt.value} ${evt.name}")
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
    unschedule()

    Integer refreshSecs = 30
    if (pollFreq == "auto") {
        // TODO: REMOVE
    } else {
        refreshSecs = pollFreq.toInteger()
    }
    // add some randomness to this value (between 0 and 5 seconds)
    Integer random = Math.abs(new Random().nextInt() % 5)
    refreshSecs += random

    if (logEnable) log.debug("scheduleUpdates: refreshSecs:$refreshSecs, pollFreq:$pollFreq, random:${random}")
    if (refreshSecs > 0 && refreshSecs < 60) {
        // seconds
        schedule("0/${refreshSecs} * * * * ? *", handleTimerFired)
    } else if (refreshSecs > 0) {
        // mins
        schedule("0 */${refreshSecs / 60} * * * ? *", handleTimerFired)
    }
}

/**
 * called by timer
 */
def handleTimerFired() {
    fetchLocations()
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
            // send circle places and home to individual children
            //if (logEnable) log.trace("notifyChildDevice: updating: ${memberObj.firstName}")
            boolean isChanged = deviceWrapper.generatePresenceEvent(memberObj, placesMap, home)
            // if (logEnable) log.trace("notifyChildDevice: DONE updating: ${memberObj.firstName}, isChanged:${isChanged}")
            if (isChanged) isAnyChanged = true
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
    // Extract just the "Set-Cookie" headers from the Response.
    response.getHeaders('Set-Cookie').each {
        def cookie = it.value.tokenize(';|,')[0]
        if (cookie) responseCookies << cookie
        if (logEnable) log.trace("captureCookies: ${it.value}")
    }
    if (responseCookies) {
        state["cookies"] = responseCookies.join(";")
    }
}

