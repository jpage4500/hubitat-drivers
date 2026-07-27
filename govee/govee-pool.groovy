/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * DESCRIPTION:
 * Govee Pool Thermostat
 *
 * Reads temperature from a GoveeLife Smart Pool Thermometer using Govee's *undocumented* app API
 * (GET https://app2.govee.com/bff-app/v1/device/list). Govee's official developer API (Govee-API-Key)
 * does not expose this device, so an Authorization token captured from the phone app is required.
 *
 * INSTALL:
 * - Add a new Virtual Device
 *   > Devices -> Add Device -> Virtual
 *   > Open the "Type" dropdown and search for "Govee Pool Thermostat" to find the new driver
 *   > Enter anything for the driver name and hit Save Device
 * CONFIGURE:
 *   > Capture the "clientId" and "Authorization: Bearer <token>" headers the Govee app sends to
 *     app2.govee.com (see README.md - HTTP Toolkit on a rooted Android device)
 *   > Paste the Client ID into preferences and hit Save Preferences
 *   > Paste the WHOLE token using the "Set Auth Token" command (a leading "Bearer " is stripped)
 *
 * TOKEN EXPIRY:
 *   The token is a JWT and expires. Govee has no usable refresh mechanism, so re-capturing it is a
 *   manual chore -- but the driver now tells you when it is due:
 *     - tokenExpires / tokenDaysLeft attributes (decoded from the token itself)
 *     - authStatus goes to "expiring" then "expired"
 *   Create a Rule Machine rule on tokenDaysLeft <= 3 (or authStatus = expired) to get notified.
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field static final String DEVICE_LIST_URL = 'https://app2.govee.com/bff-app/v1/device/list'
@Field static final String API_HOST = 'app2.govee.com'
@Field static final String DEF_APP_VERSION = '7.0.30'   // Govee rejects versions it considers too old
@Field static final Integer HTTP_TIMEOUT = 20
@Field static final Long AUTH_RETRY_MS = 1800000L       // pause 30 min between polls with a known-bad token
@Field static final List POLL_BACKOFF_SEC = [60, 300, 900]

// refreshInterval (seconds) -> a VALID quartz cron.
// the old code built "0 0/${sec/60} * * * ?" which is out of range for anything >= 1 hour (minute step
// must be 0-59) -- schedule() threw and aborted updated() before it could poll
@Field static final Map REFRESH_CRON = [
    15   : '0/15 * * * * ?',
    30   : '0/30 * * * * ?',
    120  : '0 0/2 * * * ?',
    300  : '0 0/5 * * * ?',
    600  : '0 0/10 * * * ?',
    900  : '0 0/15 * * * ?',
    1800 : '0 0/30 * * * ?',
    3600 : '0 7 * * * ?',        // hourly at :07
    10800: '0 7 0/3 * * ?',      // every 3 hours
    18000: '0 7 0/6 * * ?',      // legacy "5 Hours" setting -> 6 hours (5 doesn't divide 24 evenly)
    21600: '0 7 0/6 * * ?'       // every 6 hours
]

metadata {
    definition (
        name: "Govee Pool Thermostat",
        namespace: "jpage4500",
        author: "Joe Page",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/govee/govee-pool.groovy"
    ) {
        capability "TemperatureMeasurement"
        capability "Refresh"
        capability "Initialize"

        attribute "lastUpdatedMs", "number"
        attribute "lastUpdated", "string"
        attribute "authStatus", "enum", ["ok", "missing", "invalid", "expired", "expiring", "error"]
        attribute "lastError", "string"
        attribute "tokenExpires", "string"
        attribute "tokenDaysLeft", "number"
        attribute "tokenSource", "enum", ["command", "preferences", "none"]
        attribute "sourceDevice", "string"

        command "setAuthToken", [[name: "token*", type: "STRING",
            description: "Paste the whole Authorization token from the Govee app (a leading \"Bearer \" is stripped)"]]
        command "clearAuthToken"
        command "listDevices"
    }
}

preferences {
    input("clientId", "string", title: "Client ID",
        description: "the clientId header the Govee app sends", required: true)

    input("goveeDeviceId", "string", title: "Govee device id (optional)",
        description: "only needed if the account has more than one temperature sensor - run the \"List Devices\" command to see the ids",
        required: false)

    input('refreshInterval', 'enum', title: 'Refresh Rate', required: true,
        defaultValue: '900',
        options: ["0": "Never", "15": "15 Seconds", "30": "30 Seconds", "120": "2 Minutes", "300": "5 Minutes", "600": "10 Minutes", "900": "15 Minutes", "1800": "30 Minutes", "3600": "1 Hour", "10800": "3 Hours", "21600": "6 Hours"])

    input("tempOffset", "decimal", title: "Temperature calibration offset", required: false, defaultValue: 0)

    input("expiryWarnDays", "number", title: "Warn this many days before the token expires",
        required: false, defaultValue: 7, range: "0..90")

    input("goveeAppVersion", "string", title: "Govee app version header",
        description: "default ${DEF_APP_VERSION} - bump it if requests start failing with an \"app version is too low\" message",
        required: false)

    input("authToken1", "string", title: "Authorization Token (legacy 1/2)",
        description: "prefer the \"Set Auth Token\" command - it takes the whole token in one field. these two are kept for existing installs",
        required: false)
    input("authToken2", "string", title: "Authorization Token (legacy 2/2)", description: "", required: false)

    input name: "isLogging", type: "bool", title: "Enable debug logging", defaultValue: false, required: false
}

// ----------------------------------------------------------------------------
// lifecycle
// ----------------------------------------------------------------------------

def installed() {
    logInfo "installed"
    setAuthStatus('missing')
    setLastError('none')
    sendEventIfChanged('tokenSource', 'none')
    updated()
}

def uninstalled() {
    unschedule()
    logInfo "uninstalled"
}

// capability Initialize - runs on hub start
def initialize() {
    logDebug "initialize"
    try {
        scheduleJobs()
    } catch (e) {
        logError "initialize: scheduling failed (${e.message})"
    }
    refreshData()
}

def updated() {
    unschedule()                            // bare unschedule ONLY here - scheduleJobs() re-arms everything
    reconcileTokenSources()
    state.pollAttempt = 0
    state.remove('authRetryAtMs')           // an explicit Save means "try again now"
    state.remove('temperatureHistory')      // dead state from the removed history block
    state.remove('lastError')               // now an attribute

    // guarded: a scheduling failure must NEVER abort updated() before the immediate poll below.
    // that is exactly what the old invalid-cron bug did - Save appeared to do nothing at all
    try {
        scheduleJobs()
    } catch (e) {
        logError "updated: scheduling failed (${e.message}) - use the Refresh command to poll"
    }

    String token = activeToken()
    Integer sec = intervalSecs()
    // un-gated so hitting Save always leaves visible evidence of what happened
    logInfo "updated: interval=${sec}s, clientId=${isEmpty(settings?.clientId) ? 'MISSING' : 'set'}, " +
        "token=${maskToken(token)} (source: ${state.tokenSource ?: 'none'}), expires=${fmt(state.tokenExpiresAt as Long)}"

    if (isEmpty(settings?.clientId) || isEmpty(token)) {
        setAuthStatus('missing')
        setLastError('Client ID and/or Authorization token not set')
        logWarn "not polling: set the Client ID, then paste a token with the \"Set Auth Token\" command"
        return
    }
    refreshData()
}

// capability Refresh
def refresh() {
    logInfo "refresh"
    state.pollAttempt = 0
    state.remove('authRetryAtMs')           // a human asked - always try
    refreshData()
}

// ----------------------------------------------------------------------------
// scheduling
// ----------------------------------------------------------------------------

private Integer intervalSecs() {
    def v = settings?.refreshInterval
    if (v == null) return 900
    // NOTE: don't use `?:` here - a numeric 0 ("Never") is falsy in Groovy and would become 15 minutes
    try {
        return v.toString().toInteger()
    } catch (e) {
        return 900
    }
}

private void scheduleJobs() {
    unschedule('refreshData')
    unschedule('checkTokenExpiry')
    unschedule('pollRetry')

    Integer sec = intervalSecs()
    if (sec > 0) {
        String cron = cronFor(sec)
        try {
            schedule(cron, 'refreshData')
            logDebug "scheduleJobs: polling with cron '${cron}'"
        } catch (e) {
            logError "scheduleJobs: hub rejected cron '${cron}' (${e.message}) - falling back to every 15 minutes"
            try {
                schedule(REFRESH_CRON[900], 'refreshData')
            } catch (e2) {
                logError "scheduleJobs: fallback cron also failed (${e2.message}) - use the Refresh command to poll"
            }
        }
    } else {
        logInfo "refresh rate is Never - only the Refresh command will poll"
    }

    // independent of polling: the token can expire while the poll interval is Never
    try {
        schedule('0 5 3 * * ?', 'checkTokenExpiry')
    } catch (e) {
        logWarn "scheduleJobs: could not schedule the daily token check (${e.message})"
    }
}

private String cronFor(Integer sec) {
    String cron = REFRESH_CRON[sec]
    if (cron) return cron
    // defensive: never emit an out-of-range step for an unknown/legacy value
    if (sec < 60) return "0/${Math.max(5, Math.min(59, sec))} * * * * ?"
    Integer mins = Math.max(1, sec.intdiv(60))
    if (mins <= 59) return "0 0/${mins} * * * ?"
    Integer hours = Math.max(1, Math.min(23, mins.intdiv(60)))
    return "0 7 0/${hours} * * ?"
}

// ----------------------------------------------------------------------------
// auth token
// ----------------------------------------------------------------------------

def setAuthToken(String token) {
    String t = normalizeToken(token)
    if (isEmpty(t)) {
        logError "setAuthToken: empty token"
        setLastError('setAuthToken was called with an empty value')
        return
    }
    // clear the old status BEFORE storing, so storeToken -> publishTokenStatus has the final word
    // (a freshly pasted token can legitimately already be inside the expiry warning window)
    setAuthStatus('ok')
    setLastError('none')
    storeToken(t, 'command')
    logInfo "setAuthToken: stored ${maskToken(t)}, expires ${fmt(state.tokenExpiresAt as Long)}"
    state.pollAttempt = 0
    // a fresh token restarts polling even if a 401 had throttled it (guarded - must still poll below)
    try {
        scheduleJobs()
    } catch (e) {
        logError "setAuthToken: scheduling failed (${e.message})"
    }
    refreshData()
}

def clearAuthToken() {
    state.remove('authToken')
    state.remove('tokenSource')
    state.remove('tokenExpiresAt')
    state.remove('legacyTokenFp')
    logInfo "clearAuthToken: cleared the stored token${isEmpty(legacyToken()) ? '' : ' (falling back to the legacy preference fields)'}"
    sendEventIfChanged('tokenExpires', 'unknown')
    sendEventIfChanged('tokenDaysLeft', -1)
    updated()
}

// a wrapped copy/paste leaves embedded newlines, and "Bearer " is easy to grab along with the value -
// both silently 401 if not cleaned up
private String normalizeToken(String raw) {
    if (raw == null) return ''
    String t = raw.trim()
    if (t.toLowerCase().startsWith('bearer ')) t = t.substring(7)
    // single-quoted patterns, not slashy strings: a slashy string is a GString, so a trailing `$`
    // before the closing `/` is a parse error
    t = t.replaceAll('^["\']+|["\']+$', '')
    return t.replaceAll('\\s', '')
}

private String legacyToken() {
    return normalizeToken("${settings?.authToken1 ?: ''}${settings?.authToken2 ?: ''}")
}

// cheap fingerprint used only to notice "the user edited the legacy fields"
private String fingerprint(String s) {
    if (isEmpty(s)) return ''
    return "${s.length()}:${s.length() >= 8 ? s[-8..-1] : s}"
}

// precedence: the most recently CHANGED source wins. the legacy pair is adopted into state on the first
// Save after upgrading (and any time it changes), so existing installs migrate with no user action
private void reconcileTokenSources() {
    String legacy = legacyToken()
    if (isEmpty(legacy)) return
    String fp = fingerprint(legacy)
    if (fp != state.legacyTokenFp) {
        state.legacyTokenFp = fp
        storeToken(legacy, 'preferences')
        logInfo "adopted the token from the legacy preference fields (${maskToken(legacy)})"
    }
}

private String activeToken() {
    String t = (state.authToken ?: '').toString()
    return isEmpty(t) ? legacyToken() : t
}

private void storeToken(String raw, String source) {
    String t = normalizeToken(raw)
    if (isEmpty(t)) return
    state.authToken = t
    state.tokenSource = source
    state.tokenStoredMs = now()
    sendEventIfChanged('tokenSource', source)
    state.remove('authRetryAtMs')
    parseTokenExpiry(t)
    if (!(t.startsWith('ey') && t.count('.') == 2)) {
        logWarn "the stored token doesn't look like a JWT (${maskToken(t)}) - double-check the paste"
    }
}

private String maskToken(String t) {
    if (isEmpty(t)) return '(none)'
    if (t.length() < 16) return "***(${t.length()} chars)"
    return "${t.take(8)}...${t[-4..-1]} (${t.length()} chars)"
}

// ----------------------------------------------------------------------------
// token expiry (the token is a JWT - read the exp claim instead of waiting for a silent outage)
// ----------------------------------------------------------------------------

private Map jwtClaims(String token) {
    try {
        if (isEmpty(token)) return null
        String[] parts = token.split('\\.')
        if (parts.length < 2) return null
        String p = parts[1].replace('-', '+').replace('_', '/')
        while (p.length() % 4 != 0) p = p + '='
        def claims = new JsonSlurper().parseText(new String(p.decodeBase64(), 'UTF-8'))
        return (claims instanceof Map) ? claims : null
    } catch (e) {
        logDebug "jwtClaims: could not decode the token payload (${e.message})"
        return null
    }
}

private Long epochMs(def v) {
    if (v == null) return null
    try {
        Long l = (v as BigDecimal).longValue()
        return l < 100000000000L ? l * 1000L : l     // seconds vs millis
    } catch (e) {
        return null
    }
}

private void parseTokenExpiry(String token) {
    Map claims = jwtClaims(token)
    Long exp = epochMs(claims?.exp)
    if (exp) {
        state.tokenExpiresAt = exp
        Long iat = epochMs(claims?.iat)
        // logging the lifetime finally answers "how long do Govee tokens actually last?"
        logDebug "parseTokenExpiry: issued ${fmt(iat)}, expires ${fmt(exp)}" +
            (iat ? " (lifetime ${round1((exp - iat) / 86400000.0d)} days)" : '')
    } else {
        state.remove('tokenExpiresAt')
        logDebug "parseTokenExpiry: no exp claim in the token - expiry is unknown"
    }
    publishTokenStatus()
}

// daily at 03:05 and after every successful poll
def checkTokenExpiry() {
    publishTokenStatus()
}

private void publishTokenStatus() {
    Long exp = state.tokenExpiresAt as Long
    if (!exp) {
        sendEventIfChanged('tokenExpires', 'unknown')
        sendEventIfChanged('tokenDaysLeft', -1)      // -1 = unknown, so a rule can still test it
        return
    }
    Long msLeft = exp - now()
    Integer days = (int) Math.floor(msLeft / 86400000.0d)
    sendEventIfChanged('tokenExpires', fmt(exp))
    sendEventIfChanged('tokenDaysLeft', days)

    Integer warn = (settings?.expiryWarnDays == null) ? 7 : (settings.expiryWarnDays as Integer)
    if (msLeft <= 0) {
        setAuthStatus('expired')
        setLastError("Authorization token expired ${fmt(exp)} - capture a new one and use \"Set Auth Token\"")
        logError "auth token EXPIRED ${fmt(exp)} - capture a new token from the Govee app"
    } else if (warn > 0 && days <= warn) {
        setAuthStatus('expiring')
        setLastError("Authorization token expires ${fmt(exp)} (${days} day(s))")
        logWarn "auth token expires in ${days} day(s) (${fmt(exp)}) - capture a new one soon"
    }
}

// ----------------------------------------------------------------------------
// http
// ----------------------------------------------------------------------------

private String appVersionHeader() {
    String v = settings?.goveeAppVersion?.toString()?.trim()
    return isEmpty(v) ? DEF_APP_VERSION : v
}

private Map apiHeaders(String token) {
    return [
        clientId     : settings?.clientId,
        clientType   : '0',                 // string: asynchttpGet is stricter about header value types
        appVersion   : appVersionHeader(),
        Host         : API_HOST,
        Authorization: "Bearer ${token}"
    ]
}

def refreshData() {
    String token = activeToken()
    if (isEmpty(settings?.clientId) || isEmpty(token)) {
        setAuthStatus('missing')
        setLastError('Client ID and/or Authorization token not set')
        return
    }
    Long hold = state.authRetryAtMs as Long
    if (hold && now() < hold) {
        logDebug "skipping poll: the token is known-bad, next attempt ${fmt(hold)} " +
            "(\"Set Auth Token\", Refresh or Save Preferences retries immediately)"
        return
    }
    logDebug "refreshData: GET ${DEVICE_LIST_URL} (token ${maskToken(token)}, source ${state.tokenSource ?: 'preferences'})"
    // async: a synchronous httpGet THROWS on 4xx, which is why the old 401 handling was unreachable
    asynchttpGet('deviceListCallback', [
        uri        : DEVICE_LIST_URL,
        headers    : apiHeaders(token),
        contentType: 'application/json',
        timeout    : HTTP_TIMEOUT
    ])
}

def deviceListCallback(resp, data) {
    Integer status = 0
    try { status = (resp?.status ?: 0) as Integer } catch (ignored) { }

    if (resp?.hasError()) {
        String body = null
        try { body = resp.getErrorData()?.toString()?.take(300) } catch (ignored) { }
        String msg = null
        try { msg = resp.getErrorMessage() } catch (ignored) { }
        httpFailed(status, msg, body)
        return
    }

    def json = null
    try {
        json = resp.json
    } catch (e) {
        try {
            json = new JsonSlurper().parseText(resp.data?.toString() ?: '')
        } catch (e2) {
            httpFailed(status, "non-JSON response: ${e2.message}", resp.data?.toString()?.take(300))
            return
        }
    }
    parseResponse(json)
}

private void httpFailed(Integer status, String msg, String body) {
    String detail = "${msg ?: 'request failed'}${body ? " body=${body}" : ''}"

    if (status == 401 || status == 403) {
        handleAuthFailure(status, detail)
        return
    }
    // Govee enforces a minimum app version; retrying with the same header can never work
    if (status == 454 || detail?.toLowerCase()?.contains('app version')) {
        setAuthStatus('error')
        setLastError("Govee rejected the request (${status}): app version too low - bump the \"Govee app version header\" preference")
        logError "Govee rejected the request (${status}: ${detail}) - set the \"Govee app version header\" preference " +
            "to match a current Govee app release (default ${DEF_APP_VERSION})"
        state.authRetryAtMs = now() + AUTH_RETRY_MS
        return
    }

    // transient (network error, 429, 5xx): short backoff, leave the schedule intact
    Integer attempt = ((state.pollAttempt ?: 0) as Integer) + 1
    state.pollAttempt = attempt
    setAuthStatus('error')
    setLastError("poll failed (${status}): ${msg ?: 'request failed'}")
    if (attempt <= POLL_BACKOFF_SEC.size()) {
        Integer delay = POLL_BACKOFF_SEC[attempt - 1]
        logWarn "poll failed (${status}: ${detail}) - retrying in ${delay}s (attempt ${attempt})"
        runIn(delay, 'pollRetry', [overwrite: true])
    } else {
        logWarn "poll failed (${status}: ${detail}) - giving up until the next scheduled poll"
    }
}

def pollRetry() {
    refreshData()
}

private void handleAuthFailure(Integer status, String msg) {
    Long exp = state.tokenExpiresAt as Long
    boolean expired = exp && now() > exp
    setAuthStatus(expired ? 'expired' : 'invalid')
    setLastError("Authorization token rejected (${status})${expired ? " - expired ${fmt(exp)}" : ''} - capture a new token")
    logError "auth failed (${status}): ${msg} | token=${maskToken(activeToken())} " +
        "source=${state.tokenSource ?: 'preferences'} expires=${fmt(exp)} " +
        "clientId=${isEmpty(settings?.clientId) ? 'MISSING' : 'set'}"
    // do NOT unschedule (that used to stop polling forever). keep the cron and just stop hammering a
    // token we know is dead - one error per 30 min instead of one per interval
    state.authRetryAtMs = now() + AUTH_RETRY_MS
}

// ----------------------------------------------------------------------------
// parsing
// ----------------------------------------------------------------------------

private void parseResponse(def json) {
    def devices = json?.data?.devices ?: json?.devices
    if (!(devices instanceof List)) {
        Integer bodyStatus = 0
        try { bodyStatus = (json?.status ?: 0) as Integer } catch (ignored) { }
        // Govee also answers 200 with [message:authorization token is invalid, status:401]
        if (bodyStatus == 401 || bodyStatus == 403) {
            handleAuthFailure(bodyStatus, json?.message?.toString())
            return
        }
        setAuthStatus('error')
        setLastError("unexpected response from Govee: ${json?.message ?: json}")
        logWarn "no devices in the response: ${json}"
        return
    }

    Map choices = [:]
    List candidates = []
    devices.each { dev ->
        Map info = deviceInfo(dev)
        if (!info) return
        // .toString() matters: a GString stored in state serializes badly
        choices[info.id] = "${info.name} (${info.sku})".toString()
        if (info.tempC != null) candidates << info
    }
    state.deviceChoices = choices
    logDebug "parseResponse: ${devices.size()} device(s), ${candidates.size()} reporting a temperature: ${choices}"

    if (candidates.isEmpty()) {
        setAuthStatus('error')
        setLastError('no Govee device reported a temperature')
        logWarn "account has ${devices.size()} device(s) but none reported 'tem': ${choices}"
        return
    }

    String want = settings?.goveeDeviceId?.toString()?.trim()
    Map chosen = isEmpty(want) ? null : candidates.find { it.id == want || it.mac == want || it.name == want }
    if (!isEmpty(want) && !chosen) {
        setAuthStatus('error')
        setLastError("configured device '${want}' not found")
        logWarn "device '${want}' not found or not reporting a temperature. available: ${choices}"
        return
    }
    if (!chosen) {
        chosen = candidates[0]
        if (candidates.size() > 1) {
            logWarn "${candidates.size()} devices report a temperature - set the \"Govee device id\" preference to pick one. " +
                "using '${chosen.name}' (${chosen.id}). available: ${choices}"
        }
    }
    publishReading(chosen)
}

// deviceExt.lastDeviceData / deviceSettings arrive as JSON *strings*
private Map deviceInfo(def dev) {
    try {
        def ext = dev?.deviceExt
        Map last = asMap(ext?.lastDeviceData)
        Map cfg = asMap(ext?.deviceSettings)
        String id = (dev?.deviceId ?: dev?.device ?: cfg?.device ?: '').toString()
        if (isEmpty(id)) return null
        def rawTem = last?.tem
        return [
            id     : id,
            mac    : (dev?.device ?: '').toString(),
            sku    : (dev?.sku ?: cfg?.sku ?: '?').toString(),
            name   : (dev?.deviceName ?: cfg?.deviceName ?: id).toString(),
            tempC  : (rawTem == null) ? null : ((rawTem as BigDecimal) / 100.0),
            online : last?.online,
            raw    : last
        ]
    } catch (e) {
        logDebug "deviceInfo: skipping a device (${e.message})"
        return null
    }
}

private Map asMap(def v) {
    if (v instanceof Map) return v
    if (v instanceof String && v.trim().startsWith('{')) {
        try { return new JsonSlurper().parseText(v) } catch (ignored) { }
    }
    return [:]
}

private void publishReading(Map info) {
    String scale = (location.temperatureScale ?: 'F')
    BigDecimal val = (scale == 'C') ? info.tempC : ((info.tempC * 9 / 5) + 32)
    val = val + ((settings?.tempOffset ?: 0) as BigDecimal)
    def temp = round1(val)
    String unit = '°' + scale

    sendEvent(name: 'temperature', value: temp, unit: unit,
        descriptionText: "${device.displayName} temperature is ${temp}${unit}")
    sendEvent(name: 'lastUpdatedMs', value: now())       // always sent - the freshness signal for tiles
    sendEventIfChanged('lastUpdated', nowStr())
    sendEventIfChanged('sourceDevice', "${info.name} (${info.id})".toString())

    if (info.online == false) {
        logWarn "Govee reports '${info.name}' as offline - the reading may be stale"
    }
    setAuthStatus('ok')
    setLastError('none')
    state.pollAttempt = 0
    state.remove('authRetryAtMs')
    // one look at everything the sensor reports (battery? humidity?) without spamming the log
    logDebug "publishReading: ${info.name} ${round1(info.tempC)}°C -> ${temp}${unit} | lastDeviceData: ${info.raw}"
    publishTokenStatus()
}

def listDevices() {
    Map choices = state.deviceChoices as Map
    if (!choices) {
        logInfo "listDevices: no data yet - polling now, run this again in a few seconds"
        refreshData()
        return
    }
    logInfo "listDevices: ${choices.size()} Govee device(s):"
    choices.each { id, name -> logInfo "  ${name} -> deviceId: ${id}" }
}

// ----------------------------------------------------------------------------
// helpers
// ----------------------------------------------------------------------------

private void sendEventIfChanged(String name, def value, String unit = null) {
    if (value == null) return
    if (device.currentValue(name)?.toString() != value.toString()) {
        if (unit) sendEvent(name: name, value: value, unit: unit)
        else sendEvent(name: name, value: value)
    }
}

private void setAuthStatus(String status) { sendEventIfChanged('authStatus', status) }

private void setLastError(String msg) { sendEventIfChanged('lastError', isEmpty(msg) ? 'none' : msg) }

private boolean isEmpty(def v) { return v == null || (v instanceof String && v.trim().isEmpty()) }

// avoids java.math.RoundingMode (not worth risking in the sandbox for one decimal place)
private def round1(def v) { return Math.round(((v ?: 0) as Double) * 10.0d) / 10.0d }

private String nowStr() { return new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone) }

private String fmt(Long ms) { return ms ? new Date(ms).format('yyyy-MM-dd HH:mm', location.timeZone) : 'unknown' }

private logDebug(msg) { if (settings?.isLogging) logAt('debug', msg) }
private logInfo(msg) { logAt('info', msg) }
private logWarn(msg) { logAt('warn', msg) }
private logError(msg) { logAt('error', msg) }
private logAt(String level, msg) { log."${level}"("Govee [${device.displayName}] ${msg}") }
