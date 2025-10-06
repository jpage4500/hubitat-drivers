// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1796
// hubitat end

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * DESCRIPTION:
 * HD+ Stock Ticker
 *
 * INSTALL:
 * - Add a new Virtual Device
 *   > Devices -> Add Device -> Virtual
 *   > Open the "Type" dropdown and search for "HD+ Stock Ticker" to find the new driver
 *   > Enter anything for the device name and hit Save Device
 *
 * CONFIGURE:
 * - Provide your Finnhub API token
 * - Provide one or more stock symbols separated by commas (e.g., AAPL, MSFT, GOOGL)
 * - Choose a refresh interval (default 15 minutes)
 * - Enable debug logging if desired
 *
 * BEHAVIOR:
 * - On update, fetches stock profiles for all symbols (/stock/profile2)
 * - On each refresh interval, fetches quotes for all symbols (/quote)
 * - Stores minimal data to fit Hubitat attribute limits:
 *   - stockProfiles: { SYMBOL: { name, logo } }
 *   - stockQuotes: { SYMBOL: [c, c, ...] } for approximately last 24 hours
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

import groovy.json.JsonOutput

metadata {
    definition(
            name: "HD+ Stock Ticker",
            namespace: "jpage4500",
            author: "Joe Page"
    ) {
        // NOTE: capability is needed for driver to show up in MakerAPI list
        capability "Sensor"

        attribute "stockProfiles", "string"
        attribute "stockQuotes", "string"
        attribute "latestQuotes", "string"
        attribute "lastUpdatedMs", "number"
    }
}

preferences {
    input("apiToken", "string", title: "Finnhub API Token", description: "Required", required: true)
    input("symbols", "string", title: "Stock Symbols (comma-separated)", description: "e.g., AAPL, MSFT", required: true)

    input('refreshInterval', 'enum', title: 'Refresh Rate', required: true,
            defaultValue: '900',
            options: [
                    "60"  : "1 Minute",
                    "300" : "5 Minutes",
                    "900" : "15 Minutes",
                    "1800": "30 Minutes",
                    "3600": "1 Hour"
            ])

    input name: "isLogging", type: "bool", title: "Enable Logging", description: "", required: true
}

private logDebug(msg) {
    if (settings?.isLogging) {
        log.debug "$msg"
    }
}

def installed() {
    logDebug("installed")
}

def updated() {
    if (!settings?.apiToken || !settings?.symbols) return

    def updateInterval = (settings?.refreshInterval ?: "900").toInteger()
    logDebug("updated: interval: ${updateInterval} seconds, symbols: ${settings?.symbols}")

    // Unschedule existing jobs before re-scheduling
    try {
        unschedule()
    } catch (ignored) {
    }

    if (updateInterval > 0) {
        def minutes = updateInterval / 60
        schedule("0 0/${minutes} * * * ?", refreshData)
    }

    // Initialize state structures
    state.stockProfiles = state.stockProfiles ?: [:]
    state.stockQuotes = state.stockQuotes ?: [:]
    state.latestQuotes = state.latestQuotes ?: [:]

    // Fetch profiles immediately and then refresh quotes
    fetchProfiles()
    refreshData()
}

def initialize() {
    logDebug("initialize")
}

private List<String> getSymbolList() {
    def raw = (settings?.symbols ?: "")
    def list = raw.split(",")*.trim().findAll { it }
    return list.collect { it.toUpperCase() } as List<String>
}

private Map getAuthHeaders() {
    return [
            'X-Finnhub-Token': settings?.apiToken
    ]
}

private String todayString() {
    try {
        return new Date().format('yyyy-MM-dd', location?.timeZone)
    } catch (e) {
        return new Date().format('yyyy-MM-dd')
    }
}

def fetchProfiles() {
    def symbols = getSymbolList()
    if (!symbols) return

    def headers = getAuthHeaders()
    def profiles = [:]

    symbols.each { sym ->
        def params = [
                uri    : "https://finnhub.io/api/v1/stock/profile2",
                headers: headers,
                query  : [symbol: sym]
        ]
        try {
            httpGet(params) { resp ->
                if (resp.status == 200) {
                    def data = resp.data ?: [:]
                    profiles[sym] = [
                            name: data?.name,
                            logo: data?.logo
                    ]
                    logDebug("fetchProfiles: ${sym}: ${profiles[sym]}")
                } else {
                    log.error "fetchProfiles(${sym}) failed with status ${resp.status}"
                }
            }
        } catch (e) {
            log.error "fetchProfiles(${sym}) exception: ${e.message}"
        }
    }

    state.stockProfiles = profiles
    sendEvent(name: "stockProfiles", value: JsonOutput.toJson(profiles))
}

private boolean isMarketOpen() {
    try {
        // New York time zone (handles EST/EDT automatically)
        def tz = TimeZone.getTimeZone('America/New_York')
        def cal = Calendar.getInstance(tz)
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sunday, 2=Monday, ..., 7=Saturday
        int hour = cal.get(Calendar.HOUR_OF_DAY)
        int minute = cal.get(Calendar.MINUTE)
        // Market opens at 9:30am (9*60+30=570), closes at 4:00pm (16*60=960)
        int nowMins = hour * 60 + minute
        boolean weekday = (dayOfWeek >= Calendar.MONDAY && dayOfWeek <= Calendar.FRIDAY)
        return (weekday && nowMins >= 570 && nowMins < 960)
    } catch (e) {
        log.error "isMarketOpen() exception: ${e.message}"
        return true // fallback: allow fetch
    }
}

def refreshData() {
    if (!isMarketOpen()) {
        logDebug("Market is closed. Skipping quote fetch.")
        return
    }
    def symbols = getSymbolList()
    if (!symbols) return

    def headers = getAuthHeaders()
    def nowMs = now()
    def today = todayString()

    symbols.each { sym ->
        def params = [
                uri    : "https://finnhub.io/api/v1/quote",
                headers: headers,
                query  : [symbol: sym]
        ]

        try {
            httpGet(params) { resp ->
                if (resp.status == 200) {
                    def quote = resp.data ?: [:]
                    def price = (quote?.c != null) ? quote.c : null
                    def timestamp = (quote?.t != null) ? quote.t : null
                    // Validate timestamp is from today
                    if (timestamp != null) {
                        def quoteDate = new Date(timestamp * 1000).format('yyyy-MM-dd', location?.timeZone)
                        if (quoteDate != today) {
                            logDebug("refreshData: ${sym}: Skipping quote with old date: ${quoteDate}")
                            return
                        }
                    }
                    if (price != null) {
                        logDebug("refreshData: ${sym}: val:${price}")
                        // Ensure structure exists
                        if (!state.stockQuotes) state.stockQuotes = [:]
                        if (!state.stockQuotes[sym]) state.stockQuotes[sym] = []

                        // Append new entry
                        def existing = (state.stockQuotes[sym] as List) ?: []
                        existing << [c: price, t: timestamp]
                        // Only keep entries from today
                        existing = existing.findAll { entry ->
                            def entryDate = new Date(entry.t * 1000).format('yyyy-MM-dd', location?.timeZone)
                            entryDate == today
                        }
                        state.stockQuotes[sym] = existing
                        // Track last full quote response per symbol
                        if (!state.latestQuotes) state.latestQuotes = [:]
                        state.latestQuotes[sym] = quote
                    }
                } else {
                    log.error "refreshData(${sym}) failed with status ${resp.status}"
                }
            }
        } catch (e) {
            log.error "refreshData(${sym}) exception: ${e.message}"
        }
    }

    // Publish minimal quotes map: { SYMBOL: [c, c, ...] }
    def attrQuotes = [:]
    try {
        getSymbolList().each { sym ->
            def list = (state.stockQuotes[sym] as List) ?: []
            attrQuotes[sym] = list.collect { it?.c }
        }
        sendEvent(name: "stockQuotes", value: JsonOutput.toJson(attrQuotes))
    } catch (e) {
        sendEvent(name: "stockQuotes", value: "${attrQuotes}")
    }

    // Publish latest full quote per symbol
    try {
        sendEvent(name: "latestQuotes", value: JsonOutput.toJson(state.latestQuotes ?: [:]))
    } catch (e) {
        sendEvent(name: "latestQuotes", value: "${state.latestQuotes ?: [:]}")
    }
    sendEvent(name: "lastUpdatedMs", value: now())
}
