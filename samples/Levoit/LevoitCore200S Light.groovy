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
// 2022-07-19: v1.2 Support for setting the auto-mode of the purifier.
// 2022-07-18: v1.1 Support for Levoit Air Purifier Core 600S.
//                  Split into separate files for each device.
//                  Support for 'SwitchLevel' capability.
// 2021-10-22: v1.0 Support for Levoit Air Purifier Core 200S / 400S


metadata {
    definition(
        name: "Levoit Core200S Air Purifier Light",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson",
        description: "Supports controlling the Levoit 200S / 300S air purifiers' night light capability",
        category: "My Apps",
        iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
        iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        documentationLink: "https://github.com/dcmeglio/hubitat-bond/blob/master/README.md")
        {
            capability "Switch"
            capability "Switch Level"

            command "setNightLight", [[name:"Night Light*", type: "ENUM", description: "Display", constraints: ["on", "off", "dim"] ] ]

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

    update()

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
	setNightLight("on")
}

def off() {
    logDebug "off()"
	setNightLight("off")
}

def setNightLight(mode)
{
    logDebug "setNightLight(${mode})"

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "night_light": mode ],
                "method": "setNightLight",
                "source": "APP" ]) { resp ->
			if (checkHttpResponse("setNightLight", resp))
			{
                sendLevelEvent(mode)
				result = true
			}
		}
    return result   
}

def sendLevelEvent(mode)
{
    def dimLevel = 0
    def swtch = "off"

    if (mode == "on") { dimLevel = 100; swtch = "on"; }
    else if (mode == "dim") { dimLevel = 50; swtch = "on"; } 

    device.sendEvent(name: "level", value: dimLevel)
    device.sendEvent(name: "switch", value: swtch)
}

def setLevel(level) {
    logDebug "setLevel(${level})"
    if (level < 10) { setNightLight("off") }
    else if (level > 75) { setNightLight("on") }
    else setNightLight("dim");
}

def update() {

    parent.updateDevices()

}

def update(status) {

    logDebug "update()"

    def mode = status.result.night_light
    state.mode = mode

    sendLevelEvent(mode)
    device.sendEvent(name: "mode", value: mode)

    return result
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
