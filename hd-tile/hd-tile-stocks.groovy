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

        command "setSymbol", ["string"]
        command "addSymbol", ["string"]
        command "removeSymbol", ["string"]
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
    unschedule()

    if (updateInterval > 0) {
        def minutes = updateInterval / 60
        schedule("0 0/${minutes} * * * ?", refreshData)
    }

    // Initialize state structures
    state.stockProfiles = state.stockProfiles ?: [:]
    state.stockQuotes = state.stockQuotes ?: [:]
    state.latestQuotes = state.latestQuotes ?: [:]

    // Fetch profiles immediately and then refresh quotes
    def symbolList = getSymbolList()
    fetchProfiles(symbolList)
    refreshData(symbolList)
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

def fetchProfiles(List<String> symbols) {
    if (!symbols || symbols.isEmpty()) {
        state.stockProfiles = [:]
        sendEvent(name: "stockProfiles", value: JsonOutput.toJson(profiles))
        return
    }

    def profiles = [:]

    symbols.each { sym ->
        def params = [
            uri    : "https://finnhub.io/api/v1/stock/profile2",
            headers: getAuthHeaders(),
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

/**
 * refresh stock data for given symbols
 * @param symbols - will be null when called from schedule
 */
def refreshData(List<String> symbols) {
    if (!symbols) {
        symbols = getSymbolList()
        if (!symbols) {
            log.error("refreshData: No symbols")
            return
        }
    }

    if (!isMarketOpen()) {
        logDebug("Market is closed. Skipping quote fetch.")
        return
    }

    def quoteMap = [:]

    def tz = TimeZone.getTimeZone('America/New_York')
    def today = new Date().format('yyyy-MM-dd', tz)

    symbols.each { sym ->
        def params = [
            uri    : "https://finnhub.io/api/v1/quote",
            headers: getAuthHeaders(),
            query  : [symbol: sym]
        ]

        try {
            httpGet(params) { resp ->
                if (resp.status == 200) {
                    def quote = resp.data ?: [:]
                    def price = (quote?.c != null) ? quote.c : null
                    Long timestamp = (quote?.t != null) ? quote.t : null as Long
                    if (price == null || timestamp == null) {
                        logDebug("refreshData: ${sym}: Incomplete quote data: ${quote}")
                        return
                    }
                    // Validate timestamp is from today
                    def quoteDate = new Date(timestamp * 1000).format('yyyy-MM-dd', tz)
                    if (quoteDate != today) {
                        logDebug("refreshData: ${sym}: Skipping quote with old date: date:${quoteDate}, t:${timestamp}, today:${today}, quote:${quote}")
                        return
                    }
                    logDebug("refreshData: ${sym}: val:${price}")

                    // Track last full quote response per symbol
                    quoteMap[sym] = quote

                    // Append last quote value and timestamp
                    def existing = (state.stockQuotes[sym] as List) ?: []
                    existing << [c: price, t: timestamp]
                    state.stockQuotes[sym] = existing

                    // Only keep entries from today
                    state.stockQuotes.each { sym2, quotes ->
                        state.stockQuotes[sym2] = (quotes as List).findAll { entry ->
                            Long compareTs = entry?.t as Long
                            if (compareTs) {
                                def entryDate = new Date(compareTs * 1000).format('yyyy-MM-dd', tz)
                                return entryDate == today
                            }
                            return false
                        }
                    }
                } else {
                    log.error "refreshData(${sym}) failed with status ${resp.status}"
                }
            }
        } catch (e) {
            log.error "refreshData(${sym}) exception: ${e.message}"
        }
    }

    // save quotes
    state.latestQuotes = quoteMap

    // Publish minimal quotes map: { SYMBOL: [c, c, ...] }
    def attrQuotes = [:]
    getSymbolList().each { sym ->
        def list = (state.stockQuotes[sym] as List) ?: []
        attrQuotes[sym] = list.collect { it?.c }
    }
    sendEvent(name: "stockQuotes", value: JsonOutput.toJson(attrQuotes))

    // Publish latest full quote per symbol
    sendEvent(name: "latestQuotes", value: JsonOutput.toJson(state.latestQuotes))
    sendEvent(name: "lastUpdatedMs", value: now())
}

/**
 * command: set stock symbol(s)
 */
def setSymbol(String symbol) {
    symbol = symbol?.trim()?.toUpperCase()
    if (!symbol) return

    def list = symbol.split(",")*.trim().findAll { it }
    List<String> symbolsList = list.collect { it.toUpperCase() } as List<String>

    device.updateSetting("symbols", [value: symbolsList.join(", "), type: "string"])
    logDebug("setSymbol: Set ${symbol}, symbols: ${symbolsList}")
    fetchProfiles(symbolsList)
    refreshData(symbolsList)
}

/**
 * command: add stock symbol to existing list
 */
def addSymbol(String symbol) {
    symbol = symbol?.trim()?.toUpperCase()
    if (!symbol) return
    def symbolsList = getSymbolList()
    if (!symbolsList.contains(symbol)) {
        symbolsList << symbol
        device.updateSetting("symbols", [value: symbolsList.join(", "), type: "string"])
        logDebug("addSymbol: Added ${symbol}, symbols: ${symbolsList}")
        fetchProfiles(symbolsList)
        refreshData(symbolsList)
    } else {
        logDebug("addSymbol: Symbol ${symbol} already exists in ${symbolsList}.")
    }
}

/**
 * command: remove existing stock symbol
 */
def removeSymbol(String symbol) {
    symbol = symbol?.trim()?.toUpperCase()
    if (!symbol) return
    def symbolsList = getSymbolList()
    if (symbolsList.contains(symbol)) {
        symbolsList.removeAll { it == symbol }
        device.updateSetting("symbols", [value: symbolsList.join(", "), type: "string"])
        logDebug("removeSymbol: Removed ${symbol}, symbols: ${symbolsList}")
        // remove state data
        state.stockProfiles.remove(symbol)
        state.stockQuotes.remove(symbol)
        state.latestQuotes.remove(symbol)
        fetchProfiles(symbolsList)
        refreshData(symbolsList)
    } else {
        logDebug("removeSymbol: Symbol ${symbol} not found in ${symbolsList}.")
    }
}
