/* 

MIT License

Copyright (c) Niklas Gustafsson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

// History:
// 
// 2023-02-05: v1.6 Fixed the heartbeat logic.
// 2023-02-04: v1.5 Adding heartbeat event
// 2023-02-03: v1.4 Logging errors properly.
// 2022-08-05: v1.3 Fixed error caused by change in VeSync API for getPurifierStatus.
// 2022-08-05: v1.3 Fixed error caused by change in VeSync API for getPurifierStatus.
// 2022-07-18: v1.1 Support for Levoit Air Purifier Core 600S.
//                  Split into separate files for each device.
//                  Support for 'SwitchLevel' capability.
// 2021-10-22: v1.0 Support for Levoit Air Purifier Core 200S / 400S


metadata {
    definition(
        name: "Levoit Core200S Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson",
        description: "Supports controlling the Levoit 200S / 300S air purifiers",
        category: "My Apps",
        iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
        iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        documentationLink: "https://github.com/dcmeglio/hubitat-bond/blob/master/README.md")
        {
            capability "Switch"
            capability "SwitchLevel"
            capability "FanControl"
            capability "Actuator"

            attribute "filter", "number";                              // Filter status (0-100%)
            attribute "mode", "string";                                // Purifier mode 
            
            attribute "info", "string";                               // HTML

            command "setDisplay", [[name:"Display*", type: "ENUM", description: "Display", constraints: ["on", "off"] ] ]
            command "setSpeed", [[name:"Speed*", type: "ENUM", description: "Speed", constraints: ["off", "low", "medium", "high"] ] ]
            command "setMode",  [[name:"Mode*", type: "ENUM", description: "Mode", constraints: ["manual", "sleep"] ] ]
            command "toggle"
        }

    preferences {
        input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: false, required: false)
    }
}

def installed() {
	logDebug "Installed with settings: ${settings}"
    updated();
}

def updated() {
	logDebug "Updated with settings: ${settings}"

    state.clear()
    unschedule()
	initialize()

    runIn(3, update)

    // Turn off debug log in 30 minutes
    if (settings?.debugOutput) runIn(1800, logDebugOff);
}

def uninstalled() {
	logDebug "Uninstalled app"
}

def initialize() {
	logDebug "initializing"
}

def on() {
    logDebug "on()"
	handlePower(true)
    device.sendEvent(name: "switch", value: "on")

	if (state.speed != null) {
        setSpeed(state.speed)
	}
    else {
        setSpeed("low")
    }

    if (state.mode != null) {
        setMode(state.mode)
    }
    else {
        update(null)
    }
}

def off() {
    logDebug "off()"
	handlePower(false)
    device.sendEvent(name: "switch", value: "off")
}

def toggle() {
    logDebug "toggle()"
	if (device.currentValue("switch") == "on")
		off()
	else
		on()
}

def cycleSpeed() {
    logDebug "cycleSpeed()"

    def speed = (state.speed == "low") ? "medium" : ( (state.speed == "medium") ? "high" : "low")
    
    if (state.switch == "off")
    {
        on()
    }
    setSpeed(speed)
}

def setLevel(value)
{
    logDebug "setLevel $value"
    def speed = 0
    setMode("manual") // always manual if setLevel() cmd was called

    if(value < 33) speed = 1
    if(value >= 33 && value < 66) speed = 2
    if(value >= 66) speed = 3
    
    sendEvent(name: "level", value: value)
    setSpeed(speed)
}

def setSpeed(speed) {
    logDebug "setSpeed(${speed})"
    if (speed == "off") {
        off()
    }
    else if (speed == "sleep") {
        setMode(speed)
        device.sendEvent(name: "speed", value: "on")
    }
    else if (state.mode == "manual") {
        handleSpeed(speed)
        state.speed = speed
        device.sendEvent(name: "speed", value: speed)
    }
    else if (state.mode == "sleep") {
        setMode("manual")
        handleSpeed(speed)
        state.speed = speed
        device.sendEvent(name: "speed", value: speed)
    }
}

def setMode(mode) {
    logDebug "setMode(${mode})"
    handleMode(mode)
    state.mode = mode
	device.sendEvent(name: "mode", value: mode)
    switch(mode)
    {
        case "manual":
            device.sendEvent(name: "speed", value: state.speed)
            break;
        case "sleep":
            device.sendEvent(name: "speed", value: "on")
            break;
    }
}

def setDisplay(displayOn) {
    logDebug "setDisplay(${displayOn})"
    handleDisplayOn(displayOn)
}

def mapSpeedToInteger(speed) {
    return (speed == "low") ? 1 : ( (speed == "medium") ? 2 : 3)
}

def mapIntegerStringToSpeed(speed) {
    return (speed == "1") ? "low" : ( (speed == "2") ? "medium" : "high")
}

def mapIntegerToSpeed(speed) {
    return (speed == 1) ? "low" : ( (speed == 2) ? "medium" : "high")
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}

void logDebugOff() {
  //
  // runIn() callback to disable "Debug" logging after 30 minutes
  // Cannot be private
  //
  if (settings?.debugOutput) device.updateSetting("debugOutput", [type: "bool", value: false]);
}

def handlePower(on) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ enabled: on, id: 0 ],
                "method": "setSwitch",
                "source": "APP" ]) { resp ->
			if (checkHttpResponse("handleOn", resp))
			{
                def operation = on ? "ON" : "OFF"
                logDebug "turned ${operation}()"
				result = true
			}
		}
    return result
}

def handleSpeed(speed) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ level: mapSpeedToInteger(speed), id: 0, type: "wind" ],
                "method": "setLevel",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleSpeed", resp))
			{
                logDebug "Set speed"
				result = true
			}
		}
    return result
}

def handleMode(mode) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "mode": mode ],
                "method": "setPurifierMode",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleMode", resp))
			{
                logDebug "Set mode"
				result = true
			}
		}
    return result
}

def update() {

    logDebug "update()"

    def result = null

    def nightLight = parent.getChildDevice(device.cid+"-nl")

    parent.sendBypassRequest(device,  [
                "method": "getPurifierStatus",
                "source": "APP",
                "data": [:]
            ]) { resp ->
			if (checkHttpResponse("update", resp))
			{
                def status = resp.data.result
                if (status == null)
                    logError "No status returned from getPurifierStatus: ${resp.msg}"
                else
                    result = update(status, nightLight)                
			}
		}
    return result
}

def update(status, nightLight)
{
    logDebug "update(status, nightLight)"

    logDebug status

    state.speed = mapIntegerToSpeed(status.result.level)
    state.mode = status.result.mode

    device.sendEvent(name: "switch", value: status.result.enabled ? "on" : "off")
    device.sendEvent(name: "mode", value: status.result.mode)
    device.sendEvent(name: "filter", value: status.result.filter_life)

    switch(state.mode)
    {
        case "manual":
            device.sendEvent(name: "speed", value: mapIntegerToSpeed(status.result.level))
            break;
        case "sleep":
            device.sendEvent(name: "speed", value: "on")
            break;
    }

    def html = "Filter: ${status.result.filter_life}%"
    device.sendEvent(name: "info", value: html)

    if (nightLight != null) {
        nightLight.update(status)
    }

    return status
}

def handleDisplayOn(displayOn) 
{
    logDebug "handleDisplayOn()"

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "state": (displayOn == "on")],
                "method": "setDisplay",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleDisplayOn", resp))
			{
                logDebug "Set display"
				result = true
			}
		}
    return result
}


def checkHttpResponse(action, resp) {
	if (resp.status == 200 || resp.status == 201 || resp.status == 204)
		return true
	else if (resp.status == 400 || resp.status == 401 || resp.status == 404 || resp.status == 409 || resp.status == 500)
	{
		log.error "${action}: ${resp.status} - ${resp.getData()}"
		return false
	}
	else
	{
		log.error "${action}: unexpected HTTP response: ${resp.status}"
		return false
	}
}
