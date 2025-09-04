// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1783
// hubitat end

import groovy.json.JsonOutput
import java.text.SimpleDateFormat

/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * DESCRIPTION:
 * Accuweather Health Device
 *
 * INSTALL:
 * - Add a new Virtual Device
 *   > Devices -> Add Device -> Virtual
 *   > Open the "Type" dropdown and search for "Accuweather Health Device" to find the new driver
 *   > Enter anything for the driver name and hit Save Device
 * CONFIGURE:
 *   > visit accuweather.com and find the URL for the health activity you want to use
 *   > examples:
 *   > https://www.accuweather.com/en/us/charlotte/28202/allergies-weather/349818?name=tree-pollen
 *   > https://www.accuweather.com/en/us/charlotte/28202/allergies-weather/349818?name=grass-pollen
 *   > https://www.accuweather.com/en/us/charlotte/28202/sinus-weather/349818
 *   > Enter the URL in the "Accuweather URL" field
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

metadata {
    definition(
        name: "Accuweather Health Device",
        namespace: "jpage4500",
        author: "Joe Page",
        importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/hd-tile/hd-tile.groovy"
    ) {
        capability "Variable"

        attribute "url", "string"
        // JSON array of days; each element
        attribute "values", "string"
        attribute "lastUpdatedMs", "number"
    }
}

preferences {
    input("url", "string", title: "Accuweather URL", description: "example: https://www.accuweather.com/en/us/charlotte/28202/fishing-weather/349818", required: true)

    input('refreshInterval', 'enum', title: 'Refresh Rate', required: true,
        defaultValue: '3600', /* 1 hour */
        description: "How often to refresh data from Accuweather",
        options: ["0": "Never", "15": "15 Seconds", "30": "30 Seconds", "120": "2 Minutes", "300": "5 Minutes", "600": "10 Minutes", "900": "15 Minutes", "1800": "30 Minutes", "3600": "1 Hour", "10800": "3 Hours", "18000": "5 Hours"])

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
    if (!settings?.url || !settings?.refreshInterval) return;

    sendEvent(name: "url", value: settings.url)

    def updateInterval = (settings.refreshInterval).toInteger()
    logDebug("updated: url:${url}, interval: ${updateInterval} seconds")
    if (updateInterval <= 0) {
        // do nothing
        unschedule()
    } else if (updateInterval < 60) {
        // seconds
        schedule("0/${updateInterval} * * * * ?", refreshData)
    } else if (updateInterval < 10800) {
        // minutes
        schedule("0 0/${updateInterval / 60} * * * ?", refreshData)
    } else {
        // hours
        schedule("0 0 0/${updateInterval / 3600} * * ?", refreshData)
    }

    refreshData()
}

def refreshData() {
    def url = settings?.url;
    if (!url) return;
    logDebug("refreshData: url: ${url}")

    def headers = [
        "Accept-Encoding": "gzip, deflate, br",
        "Connection"     : "keep-alive",
        "User-Agent"     : "Mozilla/5.0 (Windows NT 10.0)",
        "Host"           : "www.accuweather.com",
        "Referer"        : "https://www.accuweather.com/",
        "Accept"         : "application/json, text/plain, */*",
        "Content-Type"   : "application/json;charset=UTF-8"
    ]

    def params = [
        uri       : url,
        headers   : headers,
        textParser: true
    ]

    try {
        httpGet(params) { resp ->
            logDebug("refreshData: response: ${resp.status}")
            if (resp.status == 200) {
                parseResponse(resp.data)
            } else {
                log.error "refreshData failed with status ${resp.status}"
            }
        }
    } catch (e) {
        log.error "refreshData exception: ${e.message}"
    }
}

void parseResponse(StringReader data) {
    def formatter = new SimpleDateFormat("EEE, M/d")
    def date = new Date()
    def formattedDate = formatter.format(date)
    def html = null
    // all the data we're looking for is on a single line so first find the line
    data.eachLine { line ->
        def startPos = line.indexOf(formattedDate)
        if (startPos > 0) {
            html = line
            return
        }
    }
    if (html == null) {
        log.error("parseResponse: No data found for date: ${formattedDate}")
        return
    }

    def dataMap = [:]

    // find value for next 7 days
    for (int i = 0; i < 7; i++) {
        date = new Date() + i
        formattedDate = formatter.format(date)

        def startPos = html.indexOf(formattedDate)

        def category = parseValue(html, startPos, "category")
        def factorText = parseValue(html, startPos, "factorText")
        def factorValue = parseValue(html, startPos, "factorValue")
        logDebug("date: ${formattedDate}, category: ${category}, factorText: ${factorText}, factorValue: ${factorValue}")
        dataMap[formattedDate] = category + "\n" + factorText + "\n" + factorValue
    }
    sendEvent(name: "values", value: JsonOutput.toJson(dataMap))
    sendEvent(name: "lastUpdatedMs", value: timestamp)
}

String parseValue(String html, int startPos, String key) {
    if (startPos < 0) return null
    // "category\&quot;:\&quot;"
    // "factorText\&quot;:\&quot;"
    // "factorValue\&quot;:\&quot;"
    key += "\\&quot;:\\&quot;"
    def valuePos = html.indexOf(key, startPos)
    if (valuePos > 0) {
        valuePos += key.length()
        def endPos = html.indexOf("\\&quot;", valuePos)
        if (endPos > 0) {
            value = html.substring(valuePos, endPos)
            return value
        }
    }
    return null
}