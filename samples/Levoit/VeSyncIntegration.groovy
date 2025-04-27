// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1671
// hubitat end

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
// 2025-04-27: v1.6 Levoit V201S-WUS support
// 2023-02-04: v1.5 Adding heartbeat event
// 2023-02-03: v1.4 Logging errors properly.
// 2022-08-05: v1.3 Fixed error caused by change in VeSync API for getPurifierStatus.
// 2022-07-18: v1.1 Support for Levoit Air Purifier Core 600S.
//                  Split into separate files for each device.
//                  Support for 'SwitchLevel' capability.
// 2021-10-22: v1.0 Support for Levoit Air Purifier Core 200S / 400S

import java.security.MessageDigest

metadata {
    definition(
        name: "VeSync Integration",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson and elfege (contributor)",
        description: "Integrates VeSync devices with Hubitat Elevation",
        category: "My Apps",
        iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
        iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        documentationLink: "https://github.com/dcmeglio/hubitat-bond/blob/master/README.md")
        {
            capability "Actuator"
            attribute "heartbeat", "string";
        }

    preferences {
        input(name: "email", type: "string", title: "<font style='font-size:12px; color:#1a77c9'>Email Address</font>", description: "<font style='font-size:12px; font-style: italic'>VeSync Account Email Address</font>", defaultValue: "", required: true);
        input(name: "password", type: "password", title: "<font style='font-size:12px; color:#1a77c9'>Password</font>", description: "<font style='font-size:12px; font-style: italic'>VeSync Account Password</font>", defaultValue: "");
		input("refreshInterval", "number", title: "<font style='font-size:12px; color:#1a77c9'>Refresh Interval</font>", description: "<font style='font-size:12px; font-style: italic'>Poll VeSync status every N seconds</font>", required: true, defaultValue: 30)
        input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: false, required: false)

        command "resyncEquipment"

    }
}

def installed() {
	logDebug "Installed with settings: ${settings}"
    updated()
}

def updated() {
	logDebug "Updated with settings: ${settings}"

	initialize()

    runIn(5 * (int)settings.refreshInterval, timeOutLevoit)

    updateDevices()

    // Turn off debug log in 30 minutes
    if (settings?.debugOutput) runIn(1800, logDebugOff);
}

def uninstalled() {

    unschedule()

	logDebug "Uninstalled app"

	for (device in getChildDevices())
	{
		deleteChildDevice(device.deviceNetworkId)
	}
}

def initialize() {
	logDebug "initializing"

    login()
}

private Boolean login()
{
    def logmd5 = MD5(password)

	def params = [
		uri: "https://smartapi.vesync.com/cloud/v1/user/login",
		contentType: "application/json",
        requestContentType: "application/json",
        body: [
            "timeZone": "America/Los_Angeles",
            "acceptLanguage": "en",
            "appVersion": "2.5.1",
            "phoneBrand": "SM N9005",
            "phoneOS": "Android",
            "traceId": "1634265366",
            "email": email,
            "password": logmd5,
            "devToken": "",
            "userType": "1",
            "method": "login"
        ],
		headers: [
            "Accept": "application/json",
            "Accept-Encoding": "gzip, deflate, br",
            "Connection": "keep-alive",
            "User-Agent": "Hubitat Elevation",
            "accept-language": "en",
            "appVersion": "2.5.1",
            "tz": "America/Los_Angeles"
        ]
	]

    logDebug "login: ${params.uri}"

	try
	{
		def result = false
		httpPost(params) { resp ->
			if (checkHttpResponse("login", resp))
			{
                state.token = resp.data.result.token
                state.accountID = resp.data.result.accountID
			}
		}
		return result
	}
	catch (e)
	{
        logError e.toString();
		checkHttpResponse("login", e.getResponse())
		return false
	}
}

def Boolean updateDevices()
{
    // Immediately schedule the next update -- this will keep the 
    // referesh interval as close to constant as possible.
    runIn((int)settings.refreshInterval, updateDevices)

    def command = [
            "method": "getPurifierStatus",
            "source": "APP",
            "data": [:]
        ]

    sendEvent(name: "heartbeat", value: "syncing", isStateChange: true, descriptionText: "Waiting on update from VeSync servers.")

    for (e in state.deviceList) {

        try {
            def dni = e.key
            def configModule = e.value

            if (dni.endsWith("-nl")) continue;

            logDebug "Updating ${dni}"

            def dev = getChildDevice(dni)

            sendBypassRequest(dev, command) { resp ->
                if (checkHttpResponse("update", resp))
                {
                    def status = resp.data.result
                    if (status == null)
                        logError "No status returned from getPurifierStatus: ${resp.msg}"
                    else
                        result = dev.update(status, getChildDevice(dni+"-nl"))
                }
            }
        }
        catch (exc)
        {
            logError exc.toString();
        }
    }

    sendEvent(name: "heartbeat", value: "synced", isStateChange: true, descriptionText: "Update received from VeSync servers.")

    // Schedule a call to the timeout method. This will cancel any outstanding
    // schedules.
    runIn(5 * (int)settings.refreshInterval, timeOutLevoit)
}

private deviceType(code) {
    switch(code)
    {
        case "Core200S":
        case "LAP-C201S-AUSR":
        case "LAP-C201S-WUSR":
        case "LAP-V201S-WUS":
            return "200S";
        case "Core300S":
        case "LAP-C301S-WJP":
            return "300S";
        case "Core400S":
        case "LAP-C401S-WJP":
        case "LAP-C401S-WUSR":
        case "LAP-C401S-WAAA":
            return "400S";
        case "Core600S":
        case "LAP-C601S-WUS":
        case "LAP-C601S-WUSR":
        case "LAP-C601S-WEU":
            return "600S";
    }

    logDebug "NOT_FOUND: ${code}"
    return null;
}

private Boolean getDevices() {

	def params = [
		uri: "https://smartapi.vesync.com/cloud/v1/deviceManaged/devices",
		contentType: "application/json",
        requestContentType: "application/json",
        body: [
            "timeZone": "America/Los_Angeles",
            "acceptLanguage": "en",
            "appVersion": "2.5.1",
            "phoneBrand": "SM N9005",
            "phoneOS": "Android",
            "traceId": "1634265366",
            "accountID": state.accountID,
            "token": state.token,
            "method": "devices",
            "pageNo": "1",
            "pageSize": "100"
        ],
		headers: [
            "tz": "America/Los_Angeles",
            "Accept": "application/json",
            "Accept-Encoding": "gzip, deflate, br",
            "Connection": "keep-alive",
            "User-Agent": "Hubitat Elevation",
            "accept-language": "en",
            "appVersion": "2.5.1",
            "accountID": state.accountID,
            "tk": state.token ]
	]
	try
	{
		def result = false
		httpPost(params) { resp ->
			if (checkHttpResponse("getDevices", resp))
			{
                def newList = [:]

				for (device in resp.data.result.list) {
                    logDebug "Device found: ${device.deviceType} / ${device.deviceName} / ${device.macID}"

                    def dtype = deviceType(device.deviceType);
                    if (dtype == null) continue

                    newList[device.cid] = device.configModule;
                }

                // Remove devices that are no longer present.

                List<com.hubitat.app.ChildDeviceWrapper> list = getChildDevices();
                if (list) list.each {
                    String dni = it.getDeviceNetworkId();
                    if (newList.containsKey(dni) == false) {
                        logDebug "Deleting ${dni}"
                        deleteChildDevice(dni);
                    }
                }

				for (device in resp.data.result.list) {
                    def dtype = deviceType(device.deviceType);
                    if (dtype == null) continue

                    com.hubitat.app.ChildDeviceWrapper equip1 = getChildDevice(device.cid)
                    if (equip1 == null) {
                        logDebug "Adding ${device.deviceName}"
                        equip1 = addChildDevice("Levoit Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                        equip1.updateDataValue("configModule", device.configModule);
                        equip1.updateDataValue("cid", device.cid);
                        equip1.updateDataValue("uuid", device.uuid);
                    }
                    else {
                        // In case the device name has changed.
                        logDebug "Updating ${device.deviceName} / " + dtype;
                        equip1.name = device.deviceName;
                        equip1.label = device.deviceName;
                    }
                }

                state.deviceList = newList

                runIn(5 * (int)settings.refreshInterval, timeOutLevoit)

                updateDevices()

				result = true
			}
		}
		return result
	}
	catch (e)
	{
        logError e.getMessage()
//		checkHttpResponse("getDevices", e.getMessage())
		return false
	}
}

def Boolean sendBypassRequest(equipment, payload, Closure closure)
{
    logDebug "sendBypassRequest(${payload})"

    def params = [
		uri: "https://smartapi.vesync.com/cloud/v2/deviceManaged/bypassV2",
		contentType: "application/json; charset=UTF-8",
        requestContentType: "application/json; charset=UTF-8",
        body: [
            "timeZone": "America/Los_Angeles",
            "acceptLanguage": "en",
            "appVersion": "2.5.1",
            "phoneBrand": "SM N9005",
            "phoneOS": "Android",
            "traceId": "1634265366",
            "cid": equipment.getDataValue("cid"),
            "configModule": equipment.getDataValue("configModule"),
            "payload": payload,
            "accountID": getAccountID(),
            "token": getAccountToken(),
            "method": "bypassV2",
            "debugMode": false,
            "deviceRegion": "US"
        ],
		headers: [
            "tz": "America/Los_Angeles",
            "Accept": "application/json",
            "Accept-Encoding": "gzip, deflate, br",
            "Connection": "keep-alive",
            "User-Agent": "Hubitat Elevation",
            "accept-language": "en",
            "appVersion": "2.5.1",
            "accountID": getAccountID(),
            "tk": getAccountToken() ]
	]

	try
	{
		httpPost(params, closure)
		return true
	}
	catch (e)
	{
        logError e.getMessage()
		return false
	}
}

// Commands -------------------------------------------------------------------------------------------------------------------

def resyncEquipment() {
  //
  // This will trigger a sensor remapping and cleanup
  //
  try {
    logDebug "resyncEquipment()"

    getDevices()
  }
  catch (Exception e) {
    logError("Exception in resyncEquipment(): ${e}");
  }
}

// Helpers -------------------------------------------------------------------------------------------------------------------

def getAccountToken() {
    return state.token
}

def getAccountID() {
    return state.accountID
}

def MD5(s) {
	def digest = MessageDigest.getInstance("MD5")
	new BigInteger(1,digest.digest(s.getBytes())).toString(16).padLeft(32,"0")
}

def parseJSON(data) {
    def json = data.getText()
    def slurper = new groovy.json.JsonSlurper()
    return slurper.parseText(json)
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}

def logError(msg) {
    log.error msg
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

def timeOutLevoit() {
    //If the timeout expires before being reset, mark this Parent Device as 'not present' to allow action to be taken
    sendEvent(name: "heartbeat", value: "not synced", isStateChange: true, descriptionText: "No update received from VeSync servers in a long time.")
}