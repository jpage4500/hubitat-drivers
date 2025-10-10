import groovy.json.JsonSlurper
import groovy.json.JsonBuilder

definition(
        name: "Test 2",
        namespace: "jpage4500",
        author: "Joe Page",
        iconUrl: '',
        description: "Test 2",
        iconX2Url: '',
        iconX3Url: ''
)

preferences {
    page(name: "mainPage")
}

def installed() {
}

def updated() {
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
