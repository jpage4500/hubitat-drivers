// hubitat start
// hub: 192.168.0.200
// type: app
// id: 957
// hubitat end

import groovy.json.JsonSlurper
import groovy.json.JsonBuilder

definition(
    name: "Robinhood Portfolio",
    namespace: "jpage4500",
    author: "Joe Page",
    description: "Track Robinhood owned stocks",
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: ''
)

preferences {
    page(name: "mainPage")
}

def installed() {
    logDebug "Installed"
    initialize()
}

def updated() {
    logDebug "Updated"
    initialize()
}

def initialize() {
    logDebug "Initializing"
    fetchPortfolio()
}

def fetchPortfolio() {
    if (!settings.rh_username || !settings.rh_password) {
        log.error "Robinhood credentials not set."
        return
    }
    def token = robinhoodLogin(settings.rh_username, settings.rh_password, settings.rh_mfa_code)
    if (!token) {
        log.error "Failed to login to Robinhood."
        state.rhStatus = "Login failed"
        return
    }
    state.rhStatus = "Login successful"
    def positions = getRobinhoodPositions(token)
    if (positions) {
        state.rhPositions = positions
    } else {
        state.rhPositions = []
    }
}

def robinhoodLogin(username, password, mfa_code = null) {
    // Generate or reuse a device_token (UUID)
    if (!state.device_token) {
        state.device_token = UUID.randomUUID().toString()
    }
    def params = [
        uri        : "https://api.robinhood.com/oauth2/token/",
        contentType: "application/x-www-form-urlencoded",
        headers    : [
            'User-Agent'             : 'Robinhood/10.68.0 (Android; 11.0)',
            'Accept'                 : 'application/json',
            'X-Robinhood-API-Version': '1.0.0'
        ],
        body       : [
            grant_type    : "password",
            username      : username,
            password      : password,
            client_id     : "c82SH0WZOsabOXGP2sxqcj34FxkvfnWRZBKlBjFS",
            expires_in    : 86400,
            device_token  : state.device_token,
            challenge_type: "sms",
            scope         : "internal"
        ]
    ]
    if (mfa_code) {
        params.body.mfa_code = mfa_code
    }
    try {
        def token = null
        httpPost(params) { resp ->
            if (resp.status == 200 && resp.data.access_token) {
                token = resp.data.access_token
            } else {
                log.error "Robinhood login failed: ${resp.status} ${resp.data}"
            }
        }
        return token
    } catch (Exception e) {
        log.error "Robinhood login exception: ${e.message}"
        return null
    }
}

def getRobinhoodPositions(token) {
    def positions = []
    try {
        def accountsUrl = "https://api.robinhood.com/accounts/"
        def accountUrl = null
        httpGet([
            uri    : accountsUrl,
            headers: ["Authorization": "Bearer ${token}"]
        ]) { resp ->
            if (resp.status == 200 && resp.data.results && resp.data.results.size() > 0) {
                accountUrl = resp.data.results[0].url
            }
        }
        if (!accountUrl) return []
        def positionsUrl = "https://api.robinhood.com/positions/?account=${accountUrl}"
        httpGet([
            uri    : positionsUrl,
            headers: ["Authorization": "Bearer ${token}"]
        ]) { resp ->
            if (resp.status == 200 && resp.data.results) {
                positions = resp.data.results.findAll { it.quantity && it.quantity.toBigDecimal() > 0 }
            }
        }
    } catch (Exception e) {
        log.error "Error fetching Robinhood positions: ${e.message}"
    }
    return positions
}

def mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section("Robinhood Login") {
            input name: "rh_username", type: "text", title: "Robinhood Username", required: true
            input name: "rh_password", type: "password", title: "Robinhood Password", required: true
            input name: "rh_mfa_code", type: "text", title: "2FA Code (if required)", required: false
        }

        section("Robinhood Status") {
            paragraph "Status: ${state.rhStatus ?: 'Not logged in'}"
        }

        if (state.rhPositions && state.rhPositions.size() > 0) {
            section("Owned Stocks") {
                state.rhPositions.each { pos ->
                    paragraph "Instrument: ${pos.instrument}\nQuantity: ${pos.quantity}"
                }
            }
        }

        section("Options") {
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: true, submitOnChange: true
        }
    }
}

def logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug "$msg"
    }
}
