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
            input(name: "pollFreq", type: "enum", title: "Refresh Rate", required: true, defaultValue: "auto", options: ['auto': 'Auto Refresh (faster when devices are moving)', '30': '30 seconds', '60': '1 minute', '300': '5 minutes'])
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

    // -- new API --
    // https://api-cloudfront.life360.com/v4/circles/CIRCLE/members
    // -- old API --
    // https://api-cloudfront.life360.com/v3/circles/CIRCLE/members
    def params = [
        uri    : "https://api-cloudfront.life360.com",
        path   : "/v4/circles/${circle}/members",
        headers: getHttpHeaders()
    ]

    log.debug("fetchMembers:")

    try {
        httpGet(params) {
            response ->
                if (response.status == 200) {
                    state.members = response.data?.members
                    state.message = null
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
 * NOTE: this is calling the same API as fetchMembers but future API changes use a different call /items
 */
def fetchLocations() {
    if (isEmpty(circle)) {
        log.debug("fetchLocations: circle not set")
        return;
    }

    // prevent calling this API too frequently
    long lastAttempt = 0
    long currentTimeMs = new Date().getTime()
    if (state.lastUpdateMs != null) {
        lastAttempt = Math.round((long) (currentTimeMs - state.lastUpdateMs) / 1000L)
        if (lastAttempt < 5) {
            if (logEnable) log.trace "fetchLocations: TOO_FREQUENT: last:${lastAttempt}ms"
            state.message = "TOO_FREQUENT: please wait 5 secs between calls! last:${lastAttempt}ms"
            return
        }
    }
    state.lastUpdateMs = currentTimeMs

    // NOTE: current client uses /v5 API but also requires several new http headers (ce_*) and will often return http:403 response
    // -- NEW API --
    // https://api-cloudfront.life360.com/v5/circles/devices/locations
    // -- old API --
    // https://api-cloudfront.life360.com/v3/circles/CIRCLE/members
    def params = [
        uri    : "https://api-cloudfront.life360.com",
        // -- NEW API --
        //path   : "/v5/circles/devices/locations",
        path   : "/v3/circles/${circle}/members",
        headers: getHttpHeaders()
    ]

    // send l360-etag value
    if (!isEmpty(state.etag)) {
        params["headers"]["If-None-Match"] = state.etag
    }

    try {
        httpGet(params) {
            response ->
                if (response.status == 200) {
                    state.numSuccess = (state.numSuccess ?: 0) + 1
                    if (logEnable) log.trace("fetchLocations: SUCCESS: last: ${lastAttempt}s")
                    //if (logEnable) log.trace("fetchLocations: ${response.data}")
                    state.members = response.data?.members
                    // update child devices
                    notifyChildDevices()
                    state.message = null
                    state.lastSuccessMs = new Date().getTime()
                    // save l360-etag value for next request
                    def eTag = response.getFirstHeader("l360-etag")
                    if (eTag != null) {
                        state.etag = eTag.value;
                    }
                } else if (response.status == 304) {
                    state.numSuccess = (state.numSuccess ?: 0) + 1
                    state.message = null
                    state.lastSuccessMs = new Date().getTime()
                    if (logEnable) log.trace("fetchLocations: SUCCESS (304), last: ${lastAttempt}s")

                    // no changes - slow down timer (auto mode)
                    updateTimerFrequency(false)
                } else {
                    log.error("fetchLocations: bad response:${response.status}, ${response.data}")
                    state.message = "fetchLocations: bad response:${response.status}, ${response.data}"
                }
        }
    } catch (e) {
        state.numFailures = (state.numFailures ?: 0) + 1
        def numFailures = state.numFailures ?: 0
        def numSuccess = state.numSuccess ?: 0
        handleException("fetchLocations: errors:${numFailures} / ${numSuccess+numFailures}, last: ${lastAttempt}s", e)
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
    // NOTE: new /v5 API requires several new http headers (ce_*)
    def baseHeaders = [
        "Accept"         : "application/json",
        "cache-control"  : "no-cache",
        "User-Agent"     : "com.life360.android.safetymapd/KOKO/23.50.0 android/13",
//        "Accept-Encoding": "gzip",
//        "Accept-Language": "en_US",
//        "Content-Type"   : "application/json; charset=UTF-8",
//        "Connection"     : "Keep-Alive",
//        "Host"           : "api-cloudfront.life360.com",
//        "ce-id"          : UUID.randomUUID().toString(),
//        "ce-source"      : "/ANDROID/14/Google-Pixel-7/${state.deviceId}",
//        "ce-specversion" : "1.0",
//        "ce-time"        : new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"),
//        "ce-type"        : "com.life360.cloud.platform.devices.locations.v1"
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
        // adjust refresh rate based on if devices are moving
        Integer numLocationUpdates = state.numLocationUpdates != null ? state.numLocationUpdates : 0
        // TODO: experiment with these values.. make sure we're not calling the API too frequently but still get timely user updates
        if (numLocationUpdates == 0) {
            // update every 30 seconds
            refreshSecs = 30
        } else if (numLocationUpdates >= 1) {
            // update every 15 seconds
            refreshSecs = 15
        }
    } else {
        refreshSecs = pollFreq.toInteger()
    }
    if (logEnable) log.debug("scheduleUpdates: refreshSecs:$refreshSecs, pollFreq:$pollFreq")
    if (refreshSecs > 0 && refreshSecs < 60) {
        // seconds
        schedule("0/${refreshSecs} * * * * ? *", handleTimerFired)
    } else {
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

/**
 * update all Life360 devices with member location
 */
def notifyChildDevices() {
    if (isEmpty(settings.users)) return;
    if (isEmpty(settings.place)) return;
    // -- NEW API --
    //if (isEmpty(state.items)) return;
    if (isEmpty(state.places)) return;

    // Get a *** sorted *** list of places for easier navigation
    def thePlaces = state.places.sort { a, b -> a.name <=> b.name }
    def home = state.places.find { it.id == settings.place }

    placesMap = [:]
    for (rec in thePlaces) {
        placesMap.put(rec.name, "${rec.latitude};${rec.longitude};${rec.radius}")
    }

    // Iterate through each member and trigger an update from payload
    boolean isAnyChanged = false
    settings.users.each { memberId ->
        def externalId = "${app.id}.${memberId}"
        def member = state.members.find { it.id == memberId }
        if (member == null) {
            log.debug("notifyChildDevices: member not found; ${member}")
            return
        }
        try {
            // find the appropriate child device based on app id and the device network id
            def deviceWrapper = getChildDevice("${externalId}")
            if (deviceWrapper != null) {
                // send circle places and home to individual children
                //if (logEnable) log.trace("notifyChildDevices: updating: ${member.firstName}")
                boolean isChanged = deviceWrapper.generatePresenceEvent(member, placesMap, home)
                // if (logEnable) log.trace("notifyChildDevices: DONE updating: ${member.firstName}, isChanged:${isChanged}")
                if (isChanged) isAnyChanged = true
            } else {
                log.error("notifyChildDevices: device not found: ${externalId}")
            }
        } catch (e) {
            log.error "notifyChildDevices: Exception: member: ${member}"
            log.error e
        }
    }

    updateTimerFrequency(isAnyChanged)
}

def updateTimerFrequency(boolean isAnyChanged) {
    if (pollFreq == "auto") {
        // numLocationUpdates = how many consecutive location changes which can be used to speed up the next location check
        // - if user(s) are moving, update more frequently; if not, update less frequently
        Integer prevLocationUpdates = state.numLocationUpdates ?: 0
        Integer numLocationUpdates = prevLocationUpdates
        numLocationUpdates += isAnyChanged ? 1 : -1
        // max out at 3 to prevent a long drive from not slowing down API calls for a while after
        if (numLocationUpdates < 0) numLocationUpdates = 0
        else if (numLocationUpdates > 2) numLocationUpdates = 2
        state.numLocationUpdates = numLocationUpdates
        //if (logEnable) log.debug "cmdHandler: members:$settings.users.size, isAnyChanged:$isAnyChanged, numLocationUpdates:$numLocationUpdates"

        // calling schedule() can result in it firing right away so only do it if anything changes
        if (numLocationUpdates != prevLocationUpdates) {
            scheduleUpdates()
        }
    }
}

def findItem(memberId) {
    // TODO: there's probably a better way to write this such as:
    // def item = state.items.find { it.owners?.userId == memberId }
    for (def item : state.items) {
        for (def owner : item.owners) {
            if (owner.userId == memberId) {
                return item
            }
        }
    }
    return null
}

/**
 * subscribe to the life360 webhook to get push notifications on place events within this circle
 */
def createCircleSubscription() {
    if (isEmpty(circle)) {
        log.debug("createCircleSubscription: circle not set")
        return;
    }

    // https://api-cloudfront.life360.com/v3/circles/${circle}/webhook.json
    def params = [
        uri    : "https://api-cloudfront.life360.com",
        path   : "/v3/circles/${circle}/webhook.json",
        headers: getHttpHeaders()
    ]
    try {
        httpDelete(params) {
            response ->
                if (response.status == 200) {
                    if (logEnable) log.trace("createCircleSubscription: REMOVE_WEBHOOK: DONE")
                } else {
                    log.debug("createCircleSubscription: REMOVE_WEBHOOK: bad response:${response.status}, ${response.data}")
                }
        }
    }
    catch (e) {
        // ignore any errors - there many not be any existing webhooks
        log.debug("createCircleSubscription: REMOVE_WEBHOOK: ${e}")
    }

    // create our own OAUTH access token to use in webhook url
    createAccessToken()
    def hookUrl = "${getFullApiServerUrl()}/placecallback?access_token=${state.accessToken}"
    log.debug("createCircleSubscription: creating webhook url: ${hookUrl}")

    params["body"] = "url=${hookUrl}"

    try {
        httpPost(params) {
            response ->
                if (response.status == 200) {
                    log.info("createCircleSubscription: DONE: ${response.data}")
                } else {
                    log.error("createCircleSubscription: ERROR: bad response:${response.status}, ${response.data}")
                }
        }
    }
    catch (e) {
        // ignore any errors - there many not be any existing webhooks
        log.error("createCircleSubscription: EXCEPTION: ${e}")
    }
}

/**
 * called by Life360 webhook on member entering or exiting a defined place
 */
def placeEventHandler() {
    log.info("placeEventHandler: ${params}")

    // TODO: we get info about the member/place in the webhook - ideally only update that member's location
//    def circleId = params?.circleId
//    def placeId = params?.placeId
//    def memberId = params?.userId
//    def direction = params?.direction
//    def timestamp = params?.timestamp

    // update location of all members
    fetchLocations()
}

def getLocalUri(String user) {
    def url = getFullLocalApiServerUrl() + "/circle?access_token=${state.accessToken}"
    if (user != None) {
        url += "&user=${user}"
    }
    return url
}

def getCloudUri(String user) {
    def url = "${getApiServerUrl()}/${hubUID}/apps/${app.id}/circle?access_token=${state.accessToken}"
    if (user != None) {
        url += "&user=${user}"
    }
    return url
}

