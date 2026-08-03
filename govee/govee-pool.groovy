/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * DESCRIPTION:
 * Govee Pool Thermostat
 *
 * Reads temperature from a GoveeLife Smart Pool Thermometer using Govee's *undocumented* app API
 * (GET https://app2.govee.com/bff-app/v1/device/list). Govee's official developer API (Govee-API-Key)
 * does not expose this device, so the driver signs in to a Govee account to get an app API token.
 *
 * INSTALL:
 * - Add a new Virtual Device
 *   > Devices -> Add Device -> Virtual
 *   > Open the "Type" dropdown and search for "Govee Pool Thermostat" to find the new driver
 *   > Enter anything for the driver name and hit Save Device
 * CONFIGURE:
 *   > Enter the Govee account email and password and hit Save Preferences
 *   > The first login answers "a verification code has been emailed to you" -- that is expected, Govee
 *     verifies each new client once. Run "Submit Login Code" with the code from that email (~15 min)
 *   > Done. The driver keeps the token fresh on its own from then on
 *
 * TOKEN EXPIRY:
 *   The token is a JWT and expires. Govee has no refresh endpoint, so the driver logs in again -- before
 *   the token expires, and after any rejection. What it is doing is visible in the attributes:
 *     - tokenExpires / tokenDaysLeft (decoded from the token itself)
 *     - authStatus goes "ok" -> "expiring" -> "expired", or "codeRequired" / "loginFailed"
 *   Create a Rule Machine rule on authStatus = codeRequired or loginFailed for the rare case that does
 *   need a human.
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

// --- login: the driver fetches and re-fetches the API token itself ---
// protocol per wez/govee2mqtt PR #656. the v1 login endpoint is dead (Govee now answers 454 = "2FA
// verification required"); v2 accepts an emailed verification code in the body
@Field static final String LOGIN_URL = 'https://app2.govee.com/account/rest/account/v2/login'
@Field static final String VERIFICATION_URL = 'https://app2.govee.com/account/rest/account/v1/verification'
@Field static final String LOGIN_APP_VERSION = '7.4.10' // the v2 login endpoint needs a recent version
@Field static final Integer VERIFICATION_TYPE = 8       // "email me a login verification code"
@Field static final Long LOGIN_MIN_GAP_MS = 300000L     // never login more than once per 5 min
@Field static final List LOGIN_BACKOFF_SEC = [900, 3600, 14400, 43200]
@Field static final Long RELOGIN_BEFORE_MS = 86400000L  // re-login when the token has < 1 day left

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
        attribute "authStatus", "enum", ["ok", "missing", "invalid", "expired", "expiring", "error", "codeRequired", "loginFailed"]
        attribute "lastError", "string"
        attribute "tokenExpires", "string"
        attribute "tokenDaysLeft", "number"
        attribute "sourceDevice", "string"

        command "login"
        command "requestLoginCode"
        command "submitLoginCode", [[name: "code*", type: "STRING",
            description: "The verification code Govee emailed you (valid ~15 minutes)"]]
        command "clearAuthToken"
        command "listDevices"
    }
}

preferences {
    // the only way in: there is no refresh endpoint, so "keeping the token fresh" means logging in again
    input("goveeEmail", "string", title: "Govee account email", required: true)
    input("goveePassword", "password", title: "Govee account password", required: true,
        description: "the driver signs in with these to fetch the API token and re-fetches it before it " +
            "expires. Govee asks for a one-time emailed verification code per client - see the README")

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
        description: "overrides both endpoints - defaults are ${DEF_APP_VERSION} for polling and ${LOGIN_APP_VERSION} for login. " +
            "bump it if requests start failing with an \"app version is too low\" message",
        required: false)

    input name: "isLogging", type: "bool", title: "Enable debug logging", defaultValue: false, required: false
}

// ----------------------------------------------------------------------------
// lifecycle
// ----------------------------------------------------------------------------

def installed() {
    logInfo "installed"
    setAuthStatus('missing')
    setLastError('none')
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
    state.pollAttempt = 0
    state.remove('authRetryAtMs')           // an explicit Save means "try again now"
    state.remove('temperatureHistory')      // dead state from the removed history block
    state.remove('lastError')               // now an attribute
    // dead state from the removed hand-pasted-token path (NOT loginClientId - see loginClientId())
    state.remove('tokenSource')
    state.remove('legacyTokenFp')
    state.remove('useSniffedClientId')
    state.remove('tokenStoredMs')
    // an explicit Save also means "stop sulking about the last login failure and try again"
    state.loginFailCount = 0
    state.remove('loginBlockedReason')

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
    logInfo "updated: interval=${sec}s, account=${maskEmail(settings?.goveeEmail?.toString()?.trim())}, " +
        "token=${maskToken(token)}, expires=${fmt(state.tokenExpiresAt as Long)}"

    if (isEmpty(token)) {
        if (!loginConfigured()) {
            setAuthStatus('missing')
            setLastError('set the Govee account email and password, then run the "Login" command')
            logWarn 'not polling: set the Govee account email and password'
            return
        }
        doLogin(null, true)                  // the login callback polls on success
        return
    }
    refreshData()
}

// capability Refresh
def refresh() {
    logInfo "refresh"
    state.pollAttempt = 0
    state.remove('authRetryAtMs')           // a human asked - always try
    // nothing to poll with: log in now rather than waiting out doLogin()'s 5 min self-rate-limit
    if (isEmpty(activeToken()) && loginConfigured()) {
        doLogin(null, true)                 // the login callback polls on success
        return
    }
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
    unschedule('loginRetry')

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
// auth token - it only ever comes from a login, so state.authToken is the one source
// ----------------------------------------------------------------------------

// "forget the token and go and get a new one"
def clearAuthToken() {
    state.remove('authToken')
    state.remove('tokenExpiresAt')
    state.remove('tokenLifetimeMs')
    logInfo "clearAuthToken: cleared the stored token - logging in again"
    sendEventIfChanged('tokenExpires', 'unknown')
    sendEventIfChanged('tokenDaysLeft', -1)
    updated()
}

private String activeToken() {
    return (state.authToken ?: '').toString()
}

private void storeToken(String raw) {
    String t = raw?.trim()
    if (isEmpty(t)) return
    state.authToken = t
    state.remove('authRetryAtMs')
    parseTokenExpiry(t)
    if (!(t.startsWith('ey') && t.count('.') == 2)) {
        logWarn "the token Govee returned doesn't look like a JWT (${maskToken(t)})"
    }
}

private String maskToken(String t) {
    if (isEmpty(t)) return '(none)'
    if (t.length() < 16) return "***(${t.length()} chars)"
    return "${t.take(8)}...${t[-4..-1]} (${t.length()} chars)"
}

// ----------------------------------------------------------------------------
// login - the only way to get an API token
//
// protocol per wez/govee2mqtt PR #656:
//   POST /account/rest/account/v2/login  {email, password, client[, code]}
//     -> {status: 200, client: {token, tokenExpireCycle, refreshToken, topic, accountId, ...}}
//     -> body status 454 = a verification code is required (Govee emails it, valid ~15 min)
//     -> body status 455 = the code was wrong or expired
//   POST /account/rest/account/v1/verification  {type: 8, email}   -> sends the code
// there is NO refresh endpoint - "refreshing" means logging in again, which is why the client id has
// to stay stable (a new client id makes Govee ask for a new verification code)
// ----------------------------------------------------------------------------

// generated once and never regenerated - not by clearAuthToken, not by a Save (see above)
private String loginClientId() {
    if (isEmpty(state.loginClientId)) {
        state.loginClientId = UUID.randomUUID().toString()
        logDebug "generated a login client id (${state.loginClientId.take(8)}...)"
    }
    return state.loginClientId
}

private String loginAppVersion() {
    String v = settings?.goveeAppVersion?.toString()?.trim()
    return isEmpty(v) ? LOGIN_APP_VERSION : v
}

private Map loginHeaders() {
    String v = loginAppVersion()
    return [
        appVersion  : v,
        clientId    : loginClientId(),
        clientType  : '1',
        iotVersion  : '0',
        timestamp   : "${now()}".toString(),
        'User-Agent': "GoveeHome/${v} (com.ihoment.GoVeeSensor; build:8; iOS 18.4.0) Alamofire/5.10.2".toString(),
        Host        : API_HOST
    ]
}

// manual "log in now"
def login() {
    doLogin(null, true)
}

def submitLoginCode(String code) {
    String c = code?.toString()?.trim()
    if (isEmpty(c)) {
        logError "submitLoginCode: no code given"
        setLastError('submitLoginCode was called without a code')
        return
    }
    logInfo "submitLoginCode: retrying the login with the verification code"
    doLogin(c, true)
}

def requestLoginCode() {
    String email = settings?.goveeEmail?.toString()?.trim()
    if (isEmpty(email)) {
        logError "requestLoginCode: set the Govee account email first"
        setLastError('Govee account email not set')
        return
    }
    requestVerificationCode(email)
}

private boolean loginConfigured() {
    return !isEmpty(settings?.goveeEmail) && !isEmpty(settings?.goveePassword)
}

// returns false if the attempt was not even made
private boolean doLogin(String code, boolean manual = false) {
    String email = settings?.goveeEmail?.toString()?.trim()
    String pw = settings?.goveePassword
    if (isEmpty(email) || isEmpty(pw)) {
        logWarn "login: set the Govee account email and password first"
        setLastError('Govee account email/password not set')
        return false
    }
    if (!manual && !isEmpty(state.loginBlockedReason)) {
        logDebug "login skipped: ${state.loginBlockedReason}"
        return false
    }
    Long last = (state.lastLoginAttemptMs ?: 0) as Long
    if (!manual && (now() - last) < LOGIN_MIN_GAP_MS) {
        logDebug "login skipped: last attempt was ${((now() - last) / 1000L) as Integer}s ago"
        return false
    }

    Map body = [email: email, password: pw, client: loginClientId()]
    if (!isEmpty(code)) body.code = code

    state.lastLoginAttemptMs = now()
    logInfo "login: signing in as ${maskEmail(email)}${isEmpty(code) ? '' : ' with a verification code'}"
    // NOTE: never log `body` or `params` - they carry the account password in cleartext
    asynchttpPost('loginCallback', [
        uri               : LOGIN_URL,
        headers           : loginHeaders(),
        body              : body,
        requestContentType: 'application/json',
        contentType       : 'application/json',
        timeout           : HTTP_TIMEOUT
    ], [withCode: !isEmpty(code)])
    return true
}

def loginCallback(resp, data) {
    boolean withCode = (data?.withCode == true)
    Map json = responseJson(resp)
    Integer status = bodyStatus(json, resp)
    String message = json?.message?.toString()

    // instanceof guard: the response carries `client` as an object on success, but an error body may
    // omit it or hand back something else entirely (and .token on a String throws)
    Map client = (json?.client instanceof Map) ? (json.client as Map) : null
    String token = client?.token
    if (!isEmpty(token)) {
        state.loginFailCount = 0
        state.remove('loginBlockedReason')
        // set 'ok' BEFORE storing, so storeToken -> publishTokenStatus has the final word (a fresh token
        // can legitimately already be inside the expiry warning window)
        setAuthStatus('ok')
        setLastError('none')
        state.pollAttempt = 0
        storeToken(token)
        // the JWT's exp wins if present; tokenExpireCycle (seconds) is the fallback
        Long cycle = null
        try { cycle = (client.tokenExpireCycle == null) ? null : (client.tokenExpireCycle as Long) } catch (ignored) { }
        if (cycle && cycle > 0) {
            if (!state.tokenLifetimeMs) state.tokenLifetimeMs = cycle * 1000L
            if (!state.tokenExpiresAt) {
                state.tokenExpiresAt = now() + (cycle * 1000L)
                publishTokenStatus()
            }
        }
        if (client.accountId != null) state.accountId = client.accountId.toString()
        logInfo "login ok: token ${maskToken(token)}, expires ${fmt(state.tokenExpiresAt as Long)}" +
            (cycle ? " (Govee says the cycle is ${cycle}s)" : '')
        refreshData()
        return
    }

    if (status == 454) {
        // 2FA: Govee wants an emailed verification code before it will issue a token
        setAuthStatus('codeRequired')
        logWarn "login: Govee requires a verification code (454)"
        requestVerificationCode(settings?.goveeEmail?.toString()?.trim())
        return
    }
    if (status == 455) {
        setAuthStatus('codeRequired')
        setLastError('the verification code was wrong or expired - run "Request Login Code" for a new one')
        logError "login: the verification code was wrong or expired (455) - run \"Request Login Code\", " +
            "then \"Submit Login Code\" with the new code"
        return
    }
    loginFailed(status, message ?: describeResponse(resp), withCode)
}

private void requestVerificationCode(String email) {
    if (isEmpty(email)) {
        setLastError('Govee account email not set - cannot request a verification code')
        logError "requestVerificationCode: no email set"
        return
    }
    state.codeRequestedMs = now()
    logInfo "requesting a verification code for ${maskEmail(email)}"
    asynchttpPost('verificationCallback', [
        uri               : VERIFICATION_URL,
        headers           : loginHeaders(),
        body              : [type: VERIFICATION_TYPE, email: email],
        requestContentType: 'application/json',
        contentType       : 'application/json',
        timeout           : HTTP_TIMEOUT
    ])
}

def verificationCallback(resp, data) {
    Map json = responseJson(resp)
    Integer status = bodyStatus(json, resp)
    if (status == 200 || status == 0) {
        setAuthStatus('codeRequired')
        setLastError('verification code emailed - run "Submit Login Code" with it (valid ~15 minutes)')
        logWarn "a verification code has been emailed to ${maskEmail(settings?.goveeEmail?.toString()?.trim())} - " +
            "run the \"Submit Login Code\" command with it within ~15 minutes"
    } else {
        setAuthStatus('loginFailed')
        setLastError("could not request a verification code (${status}): ${json?.message ?: describeResponse(resp)}")
        logError "requestVerificationCode failed (${status}): ${json?.message ?: describeResponse(resp)}"
    }
}

private void loginFailed(Integer status, String msg, boolean withCode) {
    state.loginFailCount = ((state.loginFailCount ?: 0) as Integer) + 1
    String m = (msg ?: '').toString()

    // an app-version rejection can never succeed on retry - stop until the user changes something
    if (m.toLowerCase().contains('app version')) {
        state.loginBlockedReason = "Govee refused the login (${status}): ${m}"
        setAuthStatus('loginFailed')
        setLastError("${state.loginBlockedReason} - set the \"Govee app version header\" preference")
        logError "${state.loginBlockedReason}. automatic login stays off until you Save Preferences again. " +
            "try a newer value in the \"Govee app version header\" preference (login default ${LOGIN_APP_VERSION})"
        return
    }

    Integer idx = Math.min(((state.loginFailCount as Integer) - 1), LOGIN_BACKOFF_SEC.size() - 1)
    Integer delay = LOGIN_BACKOFF_SEC[idx]
    setAuthStatus('loginFailed')
    setLastError("login failed (${status})${isEmpty(m) ? '' : ": ${m}"}")
    logWarn "login failed (${status}${isEmpty(m) ? '' : ": ${m}"})${withCode ? ' [with a verification code]' : ''} - " +
        "attempt ${state.loginFailCount}, retrying in ${delay}s"
    runIn(delay, 'loginRetry', [overwrite: true])
}

def loginRetry() {
    // manual=true: the backoff ladder already rate-limits this, and the 5 min gap would skip it
    doLogin(null, true)
}

// pulls the JSON body out of an async response whether or not the HTTP status was an error
// (Govee answers the login endpoint with HTTP 454/455 *and* a status field in the body)
private Map responseJson(resp) {
    def json = null
    try {
        if (resp?.hasError()) {
            try { json = resp.getErrorJson() } catch (ignored) { }
            if (json == null) {
                String body = null
                try { body = resp.getErrorData()?.toString() } catch (ignored) { }
                if (body && body.trim().startsWith('{')) json = new JsonSlurper().parseText(body)
            }
        } else {
            try { json = resp.json } catch (ignored) { }
            if (json == null) {
                String body = null
                try { body = resp.data?.toString() } catch (ignored) { }
                if (body && body.trim().startsWith('{')) json = new JsonSlurper().parseText(body)
            }
        }
    } catch (e) {
        logDebug "responseJson: ${e.message}"
    }
    return (json instanceof Map) ? json : null
}

// prefers the body's status field, falls back to the HTTP status
private Integer bodyStatus(Map json, resp) {
    try {
        if (json?.status != null) return (json.status as BigDecimal).intValue()
    } catch (ignored) { }
    try {
        return (resp?.status ?: 0) as Integer
    } catch (ignored) {
        return 0
    }
}

private String describeResponse(resp) {
    try {
        if (resp?.hasError()) {
            return (resp.getErrorMessage() ?: resp.getErrorData()?.toString()?.take(200) ?: 'request failed')
        }
        return (resp?.data?.toString()?.take(200) ?: 'no response body')
    } catch (e) {
        return 'request failed'
    }
}

private String maskEmail(String e) {
    if (isEmpty(e)) return '(not set)'
    if (!e.contains('@')) return '***'
    def parts = e.split('@')
    return "${parts[0].take(1)}***@${parts[-1]}".toString()
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
        // the lifetime answers "how long do Govee tokens actually last?" and sets the re-login window
        if (iat && exp > iat) state.tokenLifetimeMs = exp - iat
        logDebug "parseTokenExpiry: issued ${fmt(iat)}, expires ${fmt(exp)}" +
            (iat ? " (lifetime ${round1((exp - iat) / 86400000.0d)} days)" : '')
    } else {
        state.remove('tokenExpiresAt')
        logDebug "parseTokenExpiry: no exp claim in the token - expiry is unknown"
    }
    publishTokenStatus()
}

// how early to re-login. a whole day for a long-lived token, but never more than a quarter of the
// token's actual lifetime - otherwise a short-lived token would sit permanently inside the window
private Long reloginThresholdMs() {
    Long threshold = RELOGIN_BEFORE_MS
    Long life = state.tokenLifetimeMs as Long
    if (life && (life.intdiv(4)) < threshold) threshold = life.intdiv(4)
    return Math.max(threshold, 300000L)
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

    // an expiring token is just a re-login - no human needed. doLogin() self-rate-limits to once per
    // 5 min, so this can't loop even though storing the new token calls back into publishTokenStatus()
    if (isEmpty(state.loginBlockedReason) && msLeft < reloginThresholdMs()) {
        logDebug "token expires ${fmt(exp)} (${days} day(s)) - trying to log in again"
        if (doLogin(null)) return            // the login callback republishes status and polls
    }

    // still here: the re-login was rate-limited, blocked or not configured, so this one does need saying
    Integer warn = (settings?.expiryWarnDays == null) ? 7 : (settings.expiryWarnDays as Integer)
    if (msLeft <= 0) {
        setAuthStatus('expired')
        setLastError("Authorization token expired ${fmt(exp)} - run the \"Login\" command")
        logError "auth token EXPIRED ${fmt(exp)} - run the \"Login\" command"
    } else if (warn > 0 && days <= warn) {
        setAuthStatus('expiring')
        setLastError("Authorization token expires ${fmt(exp)} (${days} day(s))")
        logWarn "auth token expires in ${days} day(s) (${fmt(exp)}) - a re-login is due"
    }
}

// ----------------------------------------------------------------------------
// http
// ----------------------------------------------------------------------------

private String appVersionHeader() {
    String v = settings?.goveeAppVersion?.toString()?.trim()
    return isEmpty(v) ? DEF_APP_VERSION : v
}

// the token is bound to the client id it was issued to, so poll with the same one we logged in with
private Map apiHeaders(String token) {
    return [
        clientId     : loginClientId(),
        clientType   : '0',                 // string: asynchttpGet is stricter about header value types
        appVersion   : appVersionHeader(),
        Host         : API_HOST,
        Authorization: "Bearer ${token}"
    ]
}

def refreshData() {
    String token = activeToken()
    if (isEmpty(token)) {
        // a token can only come from a login, so a scheduled poll is also the retry for a missing one.
        // doLogin() rate-limits itself to once per 5 min and won't run while a login is blocked
        if (!doLogin(null) && !loginConfigured()) {
            setAuthStatus('missing')
            setLastError('set the Govee account email and password, then run the "Login" command')
        }
        return
    }
    Long hold = state.authRetryAtMs as Long
    if (hold && now() < hold) {
        logDebug "skipping poll: the token is known-bad, next attempt ${fmt(hold)} " +
            "(Login, Refresh or Save Preferences retries immediately)"
        return
    }
    logDebug "refreshData: GET ${DEVICE_LIST_URL} (token ${maskToken(token)})"
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
    // Govee enforces a minimum app version; retrying with the same header can never work.
    // NOTE: keyed on the message, not on a status code - 454 means "verification code required" on the
    // login endpoint, which is a different thing entirely (see loginCallback)
    if (detail?.toLowerCase()?.contains('app version')) {
        setAuthStatus('error')
        setLastError("Govee rejected the request (${status}): app version too low - bump the \"Govee app version header\" preference")
        logError "Govee rejected the request (${status}: ${detail}) - set the \"Govee app version header\" preference " +
            "to match a current Govee app release (polling default ${DEF_APP_VERSION})"
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
    logError "auth failed (${status}): ${msg} | token=${maskToken(activeToken())} expires=${fmt(exp)}"

    // a rejected token is recoverable without a human - go and get a new one
    if (isEmpty(state.loginBlockedReason) && doLogin(null)) {
        setLastError("Authorization token rejected (${status}) - logging in again")
        return
    }

    setAuthStatus(expired ? 'expired' : 'invalid')
    setLastError("Authorization token rejected (${status})${expired ? " - expired ${fmt(exp)}" : ''} - " +
        'run the "Login" command')
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
