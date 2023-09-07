/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** LIFE360+ Hubitat App **
 *
 * - see community discussion here: https://community.hubitat.com/t/release-life360/118544
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *  ---- Original Header ----
 *
 *  Life360 with States - Hubitat Port
 *
 *  BTRIAL DISTANCE AND SLEEP PATCH 29-12-2017
 *  Updated Code to handle distance from, and sleep functionality
 *
 *  TMLEAFS REFRESH PATCH 06-12-2016 V1.1
 *  Updated Code to match Smartthings updates 12-05-2017 V1.2
 *  Added updateMember function that pulls all usefull information Life360 provides for webCoRE use V2.0
 *
 *  Copyright 2014 Jeff's Account
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * ---- End of original header ----
 *
 * ---- New Header ----
 *
 *  ****************  L360 with States App  ****************
 *
 *  Design Usage:
 *  Life360 with all States included
 *
 *  Copyright 2019-2020 Bryan Turcotte (@bptworld)
 *
 *  This App is free.  If you like and use this app, please be sure to mention it on the Hubitat forums!  Thanks.
 *
 *  Remember...I am not a programmer, everything I do takes a lot of time and research!
 *  Donations are never necessary but always appreciated.  Donations to support development efforts are accepted via:
 *
 *  Paypal at: https://paypal.me/bptworld
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  Special thanks goes out to @cwwilson08 for working on and figuring out the oauth stuff!
 *  This would not be possible without his work.
 *
 *  Changes:
 *  3.0.17 - 08/06/23 - merge PR by imnotbob - https://github.com/jpage4500/hubitat-drivers/pull/16
 *  3.0.16 - 08/25/23 - handle http:401 response from life360 and clear token; allowing re-login
 *  3.0.15 - 08/22/23 - merge PR from @jbaruch - Fixed url and auth token after Life360 migration to cloudfront
 *  3.0.14 - 05/31/23 - add setting to auto-adjust refresh rate when users are moving
 *  3.0.13 - 05/30/23 - minor changes
 *  3.0.12 - 05/28/23 - change isDriving, isTransit, wifiState from string to boolean (enum)
 *  3.0.8 - 05/18/23 - more changes/cleanup from @Scottma61
 *  3.0.5 - 05/15/23 - several changes including PR from @Scottma61
 *  3.0.3 - 05/11/23 - fix status attribute
 *  3.0.2 - 05/11/23 - set presence attribute
 *  3.0.0 - 05/05/23 - refactor driver
 *                       - Only notify on location or battery change
 *                       - add accuracy - useful to know how accurate a given location is
 *                       - don't set fields for both miles and km - just the one the user selects
 *                       - last updated time
 *                       - add phone and email fields
 *  2.6.4 - 09/06/22 - Added option to export all life360 Places to file
 *  2.6.3 - 08/15/22 - Bundle Manager changes
 *  2.6.2 - 05/06/22 - Bundle Manager
 *  2.6.1 - 02/26/21 - Fixed a login bug where authentication would fail when certain special characters were included in
 *                     the login information.
 *  2.6.0 - 01/07/21 - Interim release reintegrating push notifications with a new POST request
                       to force location name update from real-time servers
 *  2.5.5 - 12/20/20 - Reliability Improvements:
                     - Added a 1 sec delay after push event to let data packet catch-up
                     - Cleaned up Scheduling
                     - Cleaned up Logging
 *  2.5.3 - 12/17/20 - Fixed a logging issue
 *  2.5.2 - 12/17/20 - 30 second refresh intervals
                       To-Do: Provide a user preference field in app for refresh intervals
 *  2.5.1 - 12/11/20 - Resubscribe to notifications on Update() event
 *  2.5.0 - 12/06/20 - Moved all member location functionality to Location Tracker child Driver
                       Keeping only Circle level functionality at parent app level
 *  ---
 *  v1.0.0 - 06/30/19 - Initial port of ST app (cwwilson08) (bptworld)
 */

//file:noinspection SpellCheckingInspection
//file:noinspection unused
//file:noinspection GroovySillyAssignment
//file:noinspection GrPackage

import groovy.transform.CompileStatic
import groovy.transform.Field
import java.text.SimpleDateFormat
import com.hubitat.app.ChildDeviceWrapper

@Field static final String appVersion = '3.0.18'  // public version
@Field static final String sNL = (String)null
@Field static final String sBLK = ''
@Field static final String sCACHE = ' CACHE'

@Field static final Integer iZ = 0
@Field static final Integer i1 = 1

@CompileStatic
static Boolean devdbg() { return false }
static Boolean devdbg1() { return false }

definition(
    name: "Life360+",
    namespace: "jpage4500",
    author: "Joe Page",
    description: "Life360 app that exposes more details than the built-in driver",
    category: sBLK,
    iconUrl: sBLK,
    iconX2Url: sBLK,
    oauth: [displayName: "Life360", displayLink: "Life360"],
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/life360/life360_app.groovy",
) {
  appSetting "clientId"
  appSetting "clientSecret"
}

preferences {
    page(name: "Credentials", title: "Enter Life360 Credentials", content: "getCredentialsPage", nextPage: "testLife360Connection", install: false)
    page(name: "listCirclesPage", title: "Select Life360 Circle", content: "listCircles", install: false)
    page(name: "myPlaces", title: "My Places", content: "myPlaces", install: true)
}

mappings {
  path("/placecallback") {
    action: [
              POST: "placeEventHandler",
              GET: "placeEventHandler"
    ]
  }

    path("/receiveToken") {
    action: [
            POST: "receiveToken",
            GET: "receiveToken"
    ]
  }
}

def getCredentialsPage() {
    if (getSettingB('logEnable')) logTrace "getCredentialsPage"
    if(state.life360AccessToken) {
        listCircles()
    } else {
        passwordPage(true)
    }
}

def getCredentialsErrorPage(String message, Boolean uninstallOption) {
    if (getSettingB('logEnable')) debug "getCredentialsErrorPage:"
    passwordPage(uninstallOption, message)
}

def passwordPage(Boolean uninstallOpt, String msg = sNL){
    dynamicPage(name: "Credentials", title: "Enter Life360 Credentials", nextPage: "listCirclesPage", uninstall: uninstallOpt, install:false){
        section(getFormat("header-green", getImage("Blank")+" Life360 Credentials")) {
            input "username", "text", title: "Life360 Username?", multiple: false, required: true
            input "password", "password", title: "Life360 Password?", multiple: false, required: true, autoCorrect: false
            if(msg) paragraph msg
        }
    }
}

Boolean testLife360Connection() {
    if(state.life360AccessToken) {
        return true
    } else {
        return initializeLife360Connection()
    }
}

private Map getLifeToken(){
    if (getSettingB('logEnable')) debug "getLifeToken"

    String username = URLEncoder.encode(getSettingStr('username'),"UTF-8")
    String password = URLEncoder.encode(getSettingStr('password'), "UTF-8")

    if (!username || !password){
        return [result: false, msg: "No Credentials"]
    }

    //def url = "https://api.life360.com/v3/oauth2/token.json"
    String url = "https://api-cloudfront.life360.com:443/v3/oauth2/token.json"

    String postBody =  "grant_type=password&" +
            "username=${username}&"+
            "password=${password}"

    def result; result = null

    try {
        //httpPost(uri: url, body: postBody, headers: ["Authorization": "Basic cFJFcXVnYWJSZXRyZTRFc3RldGhlcnVmcmVQdW1hbUV4dWNyRUh1YzptM2ZydXBSZXRSZXN3ZXJFQ2hBUHJFOTZxYWtFZHI0Vg==" ]) {response ->
        Integer status; status = null
        addHttpR(url)
        httpPost(uri: url, body: postBody, headers: ["Authorization": "Basic Y2F0aGFwYWNyQVBoZUtVc3RlOGV2ZXZldnVjSGFmZVRydVl1ZnJhYzpkOEM5ZVlVdkE2dUZ1YnJ1SmVnZXRyZVZ1dFJlQ1JVWQ==" ]) { response ->
            result = response
            status = response.getStatus()
        }
        if (result.data.access_token) {
            state.life360AccessToken = result.data.access_token
            state.connectionInfo = result.data
            return [result: true]
        }
        return [result: false, msg: "Data Error: $status"]
    }
    catch (e) {
        def status = e.getResponse().status
        logError "getLifeToken error: ",e
        return [result: false, msg: "getToken Connection Error: $status"]
    }
}

Boolean initializeLife360Connection() {
    if (getSettingB('logEnable')) debug "initializeLife360Connection"

    Map res = getLifeToken()

    if ((Boolean)res.result) return true
    else {
        getCredentialsErrorPage((String)res.msg, false)
        return false
    }
}

/**
 * display ListCirclesPage
 */
def listCircles() {
    Boolean le = getSettingB('logEnable')
    if (le) logTrace "listCircles"
    Boolean uninstallOption
    uninstallOption = (app.installationState == "COMPLETE")
    dynamicPage(name: "listCirclesPage", title: sBLK, install: true, uninstall: true) {
        displayHeader()

        if (!testLife360Connection()) {
            return
        }

        String urlCircles = "https://api.life360.com/v3/circles.json"
        def resultCircles; resultCircles = null
        Integer status; status = null

        try {
            addHttpR(urlCircles)
            httpGet(uri: urlCircles, headers: ["Authorization": "Bearer ${state.life360AccessToken}", timeout: 30 ]) {response ->
                resultCircles = response
                status = response.getStatus()
                // on 401 Unauthorized response, clear access token
                if (status == 401) {
                    state.life360AccessToken = null
                }
            }
        } catch (e) {
           status = e.getResponse().status
           logError "listCircles, error: http:$status ", e
           // on 401 Unauthorized response, clear access token
           if (status == 401) {
               state.life360AccessToken = null
           }
           getCredentialsErrorPage("Error logging into Life360!", uninstallOption)
           displayFooter()
           return
        }

        List<Map> circles = resultCircles.data.circles

        section(getFormat("header-green", getImage("Blank")+" Select Life360 Circle")) {
          input 'circle', "enum", multiple: false, required:true, title:"Life360 Circle", options: circles.collectEntries{[it.id, it.name]}, submitOnChange: true
        }

        if(circles) {
            state.circle = getSettingStr('circle')
        } else {
            getCredentialsErrorPage("Invalid Usernaname or password.", uninstallOption)
        }

        if(getSettingStr('circle')) {
            if (le) logTrace "listPlaces"
            uninstallOption = (app.installationState == "COMPLETE")

            if (checkApi()){
            if (le) logTrace "listUsers"

                String url = "https://api.life360.com/v3/circles/${state.circle}/places.json"
                def result; result = null
                status = null

                try {
                    addHttpR(url)
                    httpGet(uri: url, headers: ["Authorization": "Bearer ${state.life360AccessToken}", timeout: 30 ]) { response ->
                        result = response
                        status = response.getStatus()
                    }
                } catch (e) {
                    status = e.getResponse().status
                    logError "listCircles, error: http:$status ", e
                    getCredentialsErrorPage("Error logging into Life360!", uninstallOption)
                    return
                }
                List<Map> places = result.data.places
                state.places = places

                section(getFormat("header-green", getImage("Blank")+" Select Life360 Place to Match Current Location")) {
                    paragraph "Please select the ONE Life360 Place that matches your Hubitat location: ${location.name}"
                    Map<String,Object> thePlaces = places.collectEntries{[it.id, it.name]}
                    Map sortedPlaces = thePlaces.sort { a, b -> a.value <=> b.value }
                    input 'place', "enum", multiple: false, required:true, title:"Life360 Places: ", options: sortedPlaces, submitOnChange: true
                    paragraph "<hr>"
                    input "exportPlaces", "bool", title: "Export all Life360 Places to file<br><small>* Switch will turn off when finished</small>", defaultValue:false, submitOnChange:true
                    if(exportPlaces) {
                        Map life360PlacesExportMap = [:]
                        for(Map rec in places) {
                            life360PlacesExportMap.put((String)rec.name, "${rec.latitude};${rec.longitude};${rec.radius}".toString())
                        }
                        Boolean finished = saveLif360PlacesHandler(life360PlacesExportMap)
                        app.updateSetting("exportPlaces",[value:"false",type:"bool"])
                        if(finished) { paragraph "Places have been saved as 'life360Places.txt'" }
                    }
                }
            }
        }

        if(getSettingStr('place') && getSettingStr('circle')) {
            if (checkApi()){

                String url = "https://api.life360.com/v3/circles/${state.circle}/members.json"
                def result; result = null

                addHttpR(url + ' ui')
                Integer rc
                httpGet(uri: url, headers: ["Authorization": "Bearer ${state.life360AccessToken}", timeout: 30 ]) { response ->
                    result = response
                    rc = response.getStatus()
                }

                List<Map> members = result.data.members
                state.members = members

                section(getFormat("header-green", getImage("Blank")+" Select Life360 Members to Import into Hubitat")) {
                    Map theMembers = members.collectEntries{[it.id, it.firstName+sSPACE+it.lastName]}
                    Map sortedMembers = theMembers.sort { a, b -> a.value <=> b.value }
                    input "users", "enum", multiple: true, required:false, title:"Life360 Members: ", options: sortedMembers, submitOnChange: true
                }

                section(getFormat("header-green", getImage("Blank")+" Other Options")) {
                    input (name: 'pollFreq', type: "enum", title: "Refresh Rate", required: true, defaultValue: "auto", options: ['auto':'Auto Refresh (faster when devices are moving)','30':'30 seconds','60':'1 minute', '600':'10 minutes', '1800':'30 minutes'])

                    input(name: 'logEnable', type: "bool", defaultValue: "false", submitOnChange: "true", title: "Enable Debug Logging", description: "Enable extra logging for debugging.")
                }
            }
        }

        if(devdbg()){
            section('Dump of http request counts') {
                paragraph getMapDescStr(httpCntsMapFLD)
            }
        }

        displayFooter()
    }
}

def installed() {
    if (getSettingB('logEnable')) logTrace "installed"
    unschedule()
    unsubscribe()
    initialize()
}

def updated() {
    if (getSettingB('logEnable')) logTrace "updated"
    state.remove('lastUpdateMs')
    unschedule()
    unsubscribe()
    initialize()
    if (getSettingB('logEnable') && !devdbg()){
       runIn(1800L, 'logsOff')
    }
}

void logsOff() {
    if (getSettingB('logEnable')) {
        // Log this information regardless of user setting.
        logInfo 'debug logging disabled...'
        app.updateSetting ('logEnable', [value: 'false', type: 'bool'])
    }
}

void initialize(){
    if (!state.circle) state.circle = getSettingStr('circle')

    initializeSub()
    Boolean le = getSettingB('logEnable')

    ((List<String>)settings.users).each {String memberId->
        String externalId = "${app.id}.${memberId}".toString()
        ChildDeviceWrapper deviceWrapper = getChildDevice(externalId)

        if (!deviceWrapper) { // device isn't there - so we need to create
            Map member = ((List<Map>)state.members).find{it.id==memberId}
            // Modified from @Stephack
            List<ChildDeviceWrapper> childDevices = childList()
            if(childDevices.find{it.data.vcId == "${member}"}){
                if (le) logInfo "${member.firstName} already exists...skipping"
            } else {
                if (le) logInfo "Creating Life360 Device: " + member
                try{
                    addChildDevice("jpage4500", "Life360+ Driver", externalId, ["name": "Life360 - ${member.firstName}", isComponent: false])
                    if (le) logInfo "Child Device Successfully Created"
                } catch (e) {
                    logError "Child device creation failed with error ", e
                }
            }
        }
    }

    List<ChildDeviceWrapper> childDevices = childList()
    if (le) debug "Child Devices: ${childDevices}"
    childDevices.each {childDevice->
        String[] cN = ((String)childDevice.deviceNetworkId).split("\\.")
        String childMemberId = cN[1]
        //def (childAppName, childMemberId) = childDevice.deviceNetworkId.split("\\.")
        if (!((List<String>)settings.users).find{it==childMemberId}) {
            deleteChildDevice((String)childDevice.deviceNetworkId)
            Map member = ((List<Map>)state.members).find {it.id==childMemberId}
            if (member) ((List<Map>)state.members).remove(member)
        }
    }
    createCircleSubscription(true)
}

/**
 *  setup webHook for circle changes;  calls updateMembers and scheduleUpdates
 */
void createCircleSubscription(Boolean reset = false) {
    Boolean le = getSettingB('logEnable')
    if (le) logTrace "createCircleSubscription:"

    if (checkApi()){

        Integer lastUpd = getLastTsValSecs('lastCircleSubUpdDt')
        if (!reset && lastUpd < 20) {
            if (le) logInfo "Skipping circle sub update, last done $lastUpd"
            return
        }
        updTsVal('lastCircleSubUpdDt')

        if (le) logInfo "Remove any existing Life360 Webhooks for this Circle."
        String deleteUrl = "https://api.life360.com/v3/circles/${state.circle}/webhook.json"
        def result; result = null
        Integer status; status = null
        try {
            // ignore any errors - there many not be any existing webhooks
            addHttpR(deleteUrl+' delete')
            httpDelete (uri: deleteUrl, headers: ["Authorization": "Bearer ${state.life360AccessToken}" ]) {response ->
                result = response
                status = response.getStatus()
                // on 401 Unauthorized response, clear access token
                if (status == 401) {
                    state.life360AccessToken = null
                }
            }
        } catch (e) {
            status = e.getResponse().status
            debug "deleteURL error $e"
            // on 401 Unauthorized response, clear access token
            if (status == 401) {
                state.life360AccessToken = null
            }
        }

        // subscribe to the life360 webhook to get push notifications on place events within this circle

        if (le) logInfo "Create a new Life360 Webhooks for this Circle."
        String accessToken; accessToken=(String)state.accessToken
        if(!accessToken) {
            try {
                accessToken = createAccessToken() // create our own OAUTH access token to use in webhook url
            } catch (e) {
                logError "Create access token failed ", e
                accessToken = (String)null
            }
        }
        if(accessToken){
            String hookUrl = (String)getApiServerUrl()+"/${hubUID}/apps/${app.id}/placecallback?access_token=${accessToken}".toString()
            String url = "https://api.life360.com/v3/circles/${state.circle}/webhook.json".toString()
            String postBody =  "url=${hookUrl}".toString()
            result = null
            status = null
            try {
                addHttpR(url)
                httpPost(uri: url, body: postBody, headers: ["Authorization": "Bearer ${state.life360AccessToken}" ]) {response ->
                    result = response
                    status = response.getStatus()
                    // on 401 Unauthorized response, clear access token
                    if (status == 401) {
                        state.life360AccessToken = null
                    }
                }
            } catch (e) {
                status = e.getResponse().status
                debug "webhook setup failed $e"
                // on 401 Unauthorized response, clear access token
                if (status == 401) {
                    state.life360AccessToken = null
                }
            }

            if (result?.data?.hookUrl) {
                logInfo "Successfully subscribed to Life360 circle push events"
                if (le) debug "Confirmation: ${result.data?.hookUrl}"
            }
        } else {
            logWarn "FAILED to subscribe to Life360 circle push events"
        }
        updateMembers(false)
        scheduleUpdates(true)
    }
}

void initializeSub() {
    subscribe(location, "getVersionsInfo", syncVersion)
    // TODO: subscribe to attributes, devices, locations, etc.
}

def syncVersion(evt){
}

/**
 * webHook call back
 * @return
 */
def placeEventHandler() {
    Boolean le = getSettingB('logEnable')
    if (le) debug "placeEventHandler: Received Life360 Push Event - Updating Members Location Status... $params"
    String circleId = params?.circleId
    String memberId = params?.userId
    String direction = params?.direction
    if (le) debug "PlaceHandler direction = $direction"
    //def placeId = params?.placeId
    //def timestamp = params?.timestamp

    String externalId = "${app.id}.${memberId}".toString()
    ChildDeviceWrapper deviceWrapper = getChildDevice(externalId)
    if(!deviceWrapper){
        if (le) logTrace "placeEventHandler: child device not found..."
        return
    }

    if (checkApi()){

        // once a push event is received, we can force a real-time update of location data via issuing the following
        // post request against the member for which the push event was received

        if (le) logTrace "placeEventHandler: about to post request update..."
        String postUrl = "https://api.life360.com/v3/circles/${circleId}/members/${memberId}/request.json".toString()
        try {

            addHttpR(postUrl + ' async')
            Map requestParams = [
                    uri: postUrl,
                    requestContentType: 'application/json',
                    headers: ["Authorization": "Bearer ${state.life360AccessToken}"],
                    body: ["type": "location"]
            ]
            asynchttpPost("ackHandler", requestParams, [:])

        } catch (e) {
            logError "request post / get, error: ", e
        }
    }
}

void ackHandler(resp, data) {
	Integer rc = resp.getStatus()
	if(rc == 200 || rc == 207) {
		def result = resp.getJson()
		def requestId = result?.requestId
		Boolean isPollable = result?.isPollable
		if (getSettingB('logEnable')) {
			debug "PlaceHandler Post response = ${result}"
			debug "PlaceHandler Post requestId = ${requestId} isPollable = $isPollable"
		}
	} else {
        // on 401 Unauthorized response, clear access token
        if (rc == 401) {
            state.life360AccessToken = null
        }
	   logWarn "response failed rc: $rc"
	}

	// we got a PUSH EVENT from Life360 - better update everything by pulling a fresh data packet
	// But first, wait a second to let packet catch-up with push event
	remTsVal('lastMembersDataUpdDt')
	runIn(2, 'schedUpdateMembers')
}

def refresh() {
	if (getSettingB('logEnable')) debug("refresh:")

	Integer lastUpd = getLastTsValSecs('lastRefreshUpdDt')
	if (lastUpd < 20) {
		if (getSettingB('logEnable')) logInfo "Skipping circle sub update, last done $lastUpd"
		return
	}
	updTsVal('lastRefreshUpdDt')

	listCircles()
	createCircleSubscription()
}

void scheduleUpdates(Boolean reset = false) {
    Boolean le = getSettingB('logEnable')

	Integer lastRefreshSecs = state.lastRefreshSecs
	Integer refreshSecs; refreshSecs = null
	if (getSettingStr('pollFreq') == "auto") {
		// adjust refresh rate based on if devices are moving
		Integer numLocationUpdates = state.numLocationUpdates != null ? (Integer)state.numLocationUpdates : 0
		// TODO: experiment with these values.. make sure we're not calling the API too frequently but still get timely user updates
		if (numLocationUpdates == 0) {
			// update every 300 (was 30,15,10) seconds
			refreshSecs = 300
		} else if (numLocationUpdates == 1) {
			// update every 120 seconds
			refreshSecs = 120
		} else if (numLocationUpdates >= 2) {
			// update every 60 seconds
			refreshSecs = 60
		}

        Integer lastAttempt = getLastTsValSecs('lastScheduleUpdDt')
        if (devdbg() && le) {
            logTrace("scheduleUpdates: reset: $reset lastAttempt: $lastAttempt")
            logTrace("scheduleUpdates: refreshSecs:$refreshSecs, lastRefreshSecs: ${lastRefreshSecs}")
        }

        // slow down polling updates to longer periods
        if(!reset && lastAttempt < 240 && lastRefreshSecs <= refreshSecs) {
            if(refreshSecs == lastRefreshSecs) updTsVal('lastScheduleUpdDt')
            if (le) debug "scheduleUpdates SKIPPING CHANGE"
            return
        }
        updTsVal('lastScheduleUpdDt')
	} else {
		refreshSecs = getSettingStr('pollFreq')?.toInteger()
        remTsVal('lastScheduleUpdDt')
	}
	if(!refreshSecs) refreshSecs = 600

	if(reset || lastRefreshSecs != refreshSecs){
        if (le) debug("scheduleUpdates: CHANGE refreshSecs:$refreshSecs, pollFreq:${getSettingStr('pollFreq')}")
		state.lastRefreshSecs = refreshSecs
		unschedule(updateMembers)
		Random rand = new Random(wnow())
		if (refreshSecs > 5 && refreshSecs < 60) {
			// seconds
			Integer ssseconds = rand.nextInt( (refreshSecs/2).toInteger() - 2)
			schedule("${ssseconds}/${refreshSecs} * * * * ? *", updateMembers)
		} else {
			// mins
			Integer ssseconds = rand.nextInt(28)
			schedule("${ssseconds} */${refreshSecs/60} * * * ? *", updateMembers)
        }
    } else {
        if (le) debug "scheduleUpdates SKIPPING CHANGE"
    }
}

Boolean checkApi(){
    if (!state.life360AccessToken) {
        Map a = getLifeToken()
    }
    if (!state.circle) state.circle = getSettingStr('circle')
    return (state.life360AccessToken && state.circle)
}

@Field volatile static Map<String, List<Map>> membersMapFLD = [:]

void schedUpdateMembers(){ updateMembers(false) }

void updateMembers(Boolean lazy = true){
    if (checkApi()){
        Integer lastAttempt = getLastTsValSecs('lastMembersAttemptUpdDt')
        Integer lastUpd = getLastTsValSecs('lastMembersDataUpdDt')
        String myId = gtAid()

        String url = "https://api.life360.com/v3/circles/${state.circle}/members.json"
        if (membersMapFLD[myId] && ((!lazy && lastUpd < 10) || (lazy && lastUpd <= 30) || lastAttempt < 10)) {
            addHttpR(url + sCACHE)
            if(getSettingB('logEnable')) debug "SKIPPING update Members lastUpd: $lastUpd, lastAttempt: $lastAttempt ${ devdbg1() ? membersMapFLD[myId] : sBLK}"
            if(getSettingStr('pollFreq') != 'auto' && lastAttempt < 10) runIn(30, 'schedUpdateMembers')
            return //membersMapFLD[myId]
        }
        debug 'Getting Members'
        Map requestParams = [ uri: url, headers: ["Authorization": "Bearer ${state.life360AccessToken}"], timeout: 20 ]
        updTsVal('lastMembersAttemptUpdDt')
        asynchttpGet("cmdHandler", requestParams)
        addHttpR(url + ' async')
    } else{
        logError("no access token")
    }
}

void cmdHandler(resp, data) {
    Integer rc = resp.getStatus()
    if(rc == 200 || rc == 207) {
        def result = resp.getJson()
        List<Map> members = result.members
        state.members = members
        String myId = gtAid()
        membersMapFLD[myId]=members
        updTsVal('lastMembersDataUpdDt')

        Boolean le = getSettingB('logEnable')
        if (devdbg1() && le) debug("Response data from Life360: $result")

        // Get a *** sorted *** list of places for easier navigation
        List<Map> thePlaces = ((List<Map>)state.places).sort { a, b -> a.name <=> b.name }
        Map home = ((List<Map>)state.places).find{it.id==getSettingStr('place')}

        Map placesMap = [:]
        for (Map rec in thePlaces) {
            placesMap.put(rec.name, "${rec.latitude};${rec.longitude};${rec.radius}".toString())
        }

        // Iterate through each member and trigger an update from payload
        Boolean isAnyChanged; isAnyChanged = false
        ((List<String>)settings.users).each {String memberId ->
            String externalId = "${app.id}.${memberId}".toString()
            Map member = ((List<Map>)state.members).find{ it.id==memberId }
            try {
                // find the appropriate child device based on app id and the device network id
                ChildDeviceWrapper deviceWrapper = getChildDevice(externalId)

                // send circle places and home to individual children
                // driver lets us know if location changed enough that we should auto adjust polling
                Boolean isChanged = deviceWrapper.generatePresenceEvent(member, placesMap, home)
                if (isChanged) {
                    isAnyChanged = true
                    if (devdbg() && le) logDebug "cmdHandler: member: ${member} isChanged:$isChanged"
                }
            } catch(e) {
                logError "cmdHandler: Exception: member: ${member}", e
            }
        }

        if (getSettingStr('pollFreq') == "auto") {
            // numLocationUpdates = how many consecutive location changes which can be used to speed up the next location check
            // - if user(s) are moving, update more frequently; if not, update less frequently
            Integer prevLocationUpdates = state.numLocationUpdates != null ? (Integer)state.numLocationUpdates : 0
            Integer numLocationUpdates; numLocationUpdates = prevLocationUpdates
            numLocationUpdates += isAnyChanged ? 1 : -1
            // max out at 3 to prevent a long drive from not slowing down API calls for a while after
            if (numLocationUpdates < 0) numLocationUpdates = 0
            else if (numLocationUpdates > 2) numLocationUpdates = 2
            state.numLocationUpdates = numLocationUpdates

            if (devdbg() && le) logDebug "cmdHandler: members: ${((List)settings.users).size()}, isAnyChanged:$isAnyChanged, numLocationUpdates:$numLocationUpdates"

            scheduleUpdates()
        }
    } else {
        // connection error
        logError("cmdHandler: rc: $rc resp:$resp")
        // on 401 Unauthorized response, clear access token
        if (rc == 401) {
            state.life360AccessToken = null
        }
    }
}

List<ChildDeviceWrapper> childList() {
    List<ChildDeviceWrapper> children = getChildDevices()
    //if (getSettingB('logEnable')) logDebug "childList: children: ${children}"
    return children
}

Boolean saveLif360PlacesHandler(data) {
    Boolean finished
    if(data) {
        if (getSettingB('logEnable')) logDebug "saveLif360PlacesHandler: writting to file: life360Places.txt"
        writeFile("life360Places.txt", data)
        finished = true
    } else {
        logInfo "There is no data to save."
        finished = false
    }
    return finished
}

Boolean writeFile(fName, fData) {
    //if (getSettingB('logEnable')) logDebug "Writing to file - ${fName} - ${fData}"
    login()
    try {
        def params = [
            uri: "http://127.0.0.1:8080",
            path: "/hub/fileManager/upload",
            query: [
                "folder": "/"
            ],
            headers: [
                "Cookie": state.cookie,
                "Content-Type": "multipart/form-data; boundary=----WebKitFormBoundaryDtoO2QfPwfhTjOuS"
            ],
            body: """------WebKitFormBoundaryDtoO2QfPwfhTjOuS
Content-Disposition: form-data; name="uploadFile"; filename="${fName}"
Content-Type: text/plain

${fData}

------WebKitFormBoundaryDtoO2QfPwfhTjOuS
Content-Disposition: form-data; name="folder"


------WebKitFormBoundaryDtoO2QfPwfhTjOuS--""",
            timeout: 300,
            ignoreSSLIssues: true
        ]
        httpPost(params) { resp ->
        }
    } catch (e) {
        logError "Error writing file $fName: ", e
        return false
    }
    return true
}

def login() {        // Modified from code by @dman2306
    if (getSettingB('logEnable')) logDebug "login: Checking Hub Security"
    state.cookie = sBLK
    if(hubSecurity) {
        try{
            httpPost(
                [
                    uri: "http://127.0.0.1:8080",
                    path: "/login",
                    query:
                    [
                        loginRedirect: "/"
                    ],
                    body:
                    [
                        username: hubUsername,
                        password: hubPassword,
                        submit: "Login"
                    ],
                    textParser: true,
                    ignoreSSLIssues: true
                ]
            )
            { resp ->
                if (resp.data?.text?.contains("The login information you supplied was incorrect.")) {
                    logWarn "Quick Chart Data Collector - username/password is incorrect."
                } else {
                    String[] t = ((String)resp?.headers?.'Set-Cookie')?.split(';')
                    state.cookie = t[0]
                }
            }
        } catch (e) {
            logError "Hub login ", e
        }
    }
}

def uninstalled() {
    removeChildDevices(getChildDevices())
}

private void removeChildDevices(delete) {
    delete.each {deleteChildDevice(it.deviceNetworkId)}
}

static String getImage(String type) {                    // Modified from @Stephack Code
    String loc = "<img src=https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/life360/images/"
    if(type == "Blank") return "${loc}blank.png height=40 width=5}>".toString()
    if(type == "optionsRed") return "${loc}options-red.png height=30 width=30>".toString()
    return sBLK
}

static String getFormat(String type, String myText=null, String page=null) {            // Modified code from @Stephack
    if(type == "header-green") return "<div style='color:#ffffff;font-weight: bold;background-color:#81BC00;border: 1px solid #000000;box-shadow: 2px 3px #8B8F8F;border-radius: 10px'>${myText}</div>".toString()
    if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;' />".toString()
    if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>".toString()
    if(type == "button-blue") return "<a style='color:white;text-align:center;font-size:20px;font-weight:bold;background-color:#03FDE5;border:1px solid #000000;box-shadow:3px 4px #8B8F8F;border-radius:10px' href='${page}'>${myText}</a>".toString()
    return sBLK
}

def displayHeader() {
    String theName; theName = (String)null
    if(app.label) {
        if(app.label.contains("(Paused)")) {
            theName = app.label - " <span style='color:red'>(Paused)</span>".toString()
        } else {
            theName = app.label
        }
    }
    //if(!theName) theName = "New Child App"
    String headerName = theName ?: 'Life360+'
    section(headerName) {
    }
}

def displayFooter() {
    section() {
        if(state.appType == "parent") { href "removePage", title: getImage("optionsRed")+" <b>Remove App and all child apps</b>", description:sBLK }
        paragraph getFormat("line")
        String bMes = "<div style='color:#1A77C9;text-align:center;font-size:20px;font-weight:bold'>Life360+</div>".toString()
        paragraph bMes
    }
}


@Field volatile static Map<String, Map> httpCntsMapFLD = [:]

private void addHttpR(String path) {
    String myId = gtAid()
    Map<String,Integer> cnts = httpCntsMapFLD[myId] ?: [:]
    cnts[path] = (cnts[path] ? cnts[path] : iZ) + i1
    httpCntsMapFLD[myId] = cnts
    httpCntsMapFLD = httpCntsMapFLD
}



/*------------------ Logging helpers ------------------*/

@Field static final String PURPLE = 'purple'
@Field static final String BLUE = '#0299b1'
@Field static final String GRAY = 'gray'
@Field static final String ORANGE = 'orange'
@Field static final String RED = 'red'

@Field static final String sLTH = '<'
@Field static final String sGTH = '>'

@CompileStatic
private static String logPrefix(String msg, String color = null) {
    String myMsg = msg.replaceAll(sLTH, '&lt;').replaceAll(sGTH, '&gt;')
    StringBuilder sb = new StringBuilder('<span ')
            .append("style='color:").append(GRAY).append(";'>")
            .append('[Life360+ v').append(appVersion).append('] ')
            .append('</span>')
            .append("<span style='color:").append(color).append(";'>")
            .append(myMsg)
            .append('</span>')
    return sb.toString()
}

private void logTrace(String msg) {
    log.trace logPrefix(msg, GRAY)
}

void debug (String msg) { logDebug (msg) }
private void logDebug(String msg) {
    log.debug logPrefix(msg, PURPLE)
}

private void logInfo(String msg) {
    log.info logPrefix(msg, BLUE)
}

private void logWarn(String msg) {
    log.warn logPrefix(msg, ORANGE)
}

private void logError(String msg, Exception ex = null) {
    log.error logPrefix(msg, RED)
    String a,b; a = sNL; b = sNL
    try {
        if (ex) {
            a = getExceptionMessageWithLine(ex)
            if (devdbg()) b = getStackTrace(ex)
        }
    } catch (ignored) {}
    if (a || b) {
        log.error logPrefix(a+' \n'+b, RED)
    }
}

Long wnow(){ return (Long)now() }
String gtAid() { return app.getId() }
private String getSettingStr(String nm) { return (String) settings[nm] }
private Boolean getSettingB(String nm) { return (Boolean) settings[nm] }


/*------------------ In-memory timers ------------------*/
@Field volatile static Map<String, Map> tsDtMapFLD = [:]

@CompileStatic
private void updTsVal(String key, String dt = sNL) {
    String val = dt ?: getDtNow()
//  if (key in svdTSValsFLD) { updServerItem(key, val); return }

    String appId = gtAid()
    Map data = tsDtMapFLD[appId] ?: [:]
    if (key) data[key]=val
    tsDtMapFLD[appId] = data
    tsDtMapFLD = tsDtMapFLD
}


@CompileStatic
private void remTsVal(key) {
    String appId = gtAid()
    Map data = tsDtMapFLD[appId] ?: [:]
    if (key) {
        if (key instanceof List) {
            List<String> aa = (List<String>) key
            for (String k in aa) {
                if (data.containsKey(k)) { data.remove(k) }
                //if (k in svdTSValsFLD) { remServerItem(k) }
            }
        } else {
            String sKey = (String) key
            if (data.containsKey(sKey)) { data.remove(sKey) }
            //if (sKey in svdTSValsFLD) { remServerItem(sKey) }
        }
        tsDtMapFLD[appId] = data
        tsDtMapFLD = tsDtMapFLD
    }
}

@CompileStatic
private String getTsVal(String key) {
/*  if (key in svdTSValsFLD) {
    return (String)getServerItem(key)
  }*/
    String appId = gtAid()
    Map tsMap = tsDtMapFLD[appId]
    if (key && tsMap && tsMap[key]) { return (String) tsMap[key] }
    return sNL
}

@CompileStatic
Integer getLastTsValSecs(String val, Integer nullVal = 1000000) {
    String ts = val ? getTsVal(val) : sNL
    return ts ? GetTimeDiffSeconds(ts).toInteger() : nullVal
}

@CompileStatic
Long GetTimeDiffSeconds(String lastDate, String sender = sNL) {
    try {
        if (lastDate?.contains('dtNow')) { return 10000 }
        Date lastDt = Date.parse('E MMM dd HH:mm:ss z yyyy', lastDate)
        Long start = lastDt.getTime()
        Long stop = wnow()
        Long diff = (Long)((stop - start) / 1000L)
        return diff.abs()
    } catch (ex) {
        logError("GetTimeDiffSeconds Exception: (${sender ? "$sender | " : sBLK}lastDate: $lastDate): ${ex}", ex)
        return 10000L
    }
}

@CompileStatic
static String getDtNow() {
    Date now = new Date()
    return formatDt(now)
}

private static TimeZone mTZ() { return TimeZone.getDefault() } // (TimeZone)location.timeZone

@CompileStatic
static String formatDt(Date dt, Boolean tzChg = true) {
    SimpleDateFormat tf = new SimpleDateFormat('E MMM dd HH:mm:ss z yyyy')
    if (tzChg) { if (mTZ()) { tf.setTimeZone(mTZ()) } }
    return (String) tf.format(dt)
}



@Field static final String sSPACE = ' '
@Field static final String sCLRORG = 'orange'
@Field static final String sLINEBR = '<br>'

@CompileStatic
static String span(String str, String clr = sNL, String sz = sNL, Boolean bld = false, Boolean br = false) {
    return str ? "<span ${(clr || sz || bld) ? "style='${clr ? "color: ${clr};" : sBLK}${sz ? "font-size: ${sz};" : sBLK}${bld ? "font-weight: bold;" : sBLK}'" : sBLK}>${str}</span>${br ? sLINEBR : sBLK}" : sBLK
}

@Field static final String sSPCSB7 = '      │'
@Field static final String sSPCSB6 = '     │'
@Field static final String sSPCS6 = '      '
@Field static final String sSPCS5 = '     '
@Field static final String sSPCST = '┌─ '
@Field static final String sSPCSM = '├─ '
@Field static final String sSPCSE = '└─ '
@Field static final String sNWL = '\n'
@Field static final String sDBNL = '\n\n • '

@CompileStatic
static String spanStr(Boolean html, String s) { return html ? span(s) : s }

@CompileStatic
static String doLineStrt(Integer level, List<Boolean>newLevel) {
    String lineStrt; lineStrt = sNWL
    Boolean dB; dB = false
    Integer i
    for (i = iZ;  i < level; i++) {
        if (i + i1 < level) {
            if (!newLevel[i]) {
                if (!dB) { lineStrt+=sSPCSB7; dB = true }
                else lineStrt += sSPCSB6
            } else lineStrt += !dB ? sSPCS6 : sSPCS5
        } else lineStrt += !dB ? sSPCS6 : sSPCS5
    }
    return lineStrt
}

@CompileStatic
static String dumpListDesc(List data,Integer level, List<Boolean> lastLevel, String listLabel, Boolean html = false,
                           Boolean reorder = true) {
    String str; str = sBLK
    Integer cnt; cnt = i1
    List<Boolean> newLevel = lastLevel

    List list1 = data?.collect{it}
    Integer sz = list1.size()
    for (Object par in list1) {
        String lbl = listLabel + "[${cnt-i1}]".toString()
        if (par instanceof Map) {
            Map newmap = [:]
            newmap[lbl] = (Map) par
            Boolean t1 = cnt == sz
            newLevel[level] = t1
            str += dumpMapDesc(newmap, level, newLevel, cnt, sz, !t1, html, reorder)
        } else if (par instanceof List || par instanceof ArrayList) {
            Map newmap = [:]
            newmap[lbl] = par
            Boolean t1 = cnt == sz
            newLevel[level] = t1
            str += dumpMapDesc(newmap, level, newLevel, cnt, sz, !t1, html, reorder)
        } else {
            String lineStrt
            lineStrt = doLineStrt(level,lastLevel)
            lineStrt += cnt == i1 && sz > i1 ? sSPCST : (cnt < sz ? sSPCSM:sSPCSE)
            str+=spanStr(html, lineStrt + lbl + ": ${par} (${objType(par)})".toString() )
        }
        cnt += i1
    }
    return str
}

@CompileStatic
static String dumpMapDesc(Map data, Integer level, List<Boolean> lastLevel, Integer listCnt = null,
                          Integer listSz = null, Boolean listCall = false, Boolean html = false,
                          Boolean reorder = true) {
    String str; str = sBLK
    Integer cnt; cnt = i1
    Integer sz = data?.size()
    Map svMap, svLMap, newMap; svMap = [:]; svLMap = [:]; newMap = [:]
    for (par in data) {
        String k = (String) par.key
        def v = par.value
        if (reorder && v instanceof Map) {
            svMap += [(k): v]
        } else if (reorder && (v instanceof List || v instanceof ArrayList)) {
            svLMap += [(k): v]
        } else newMap += [(k):v]
    }
    newMap += svMap + svLMap
    Integer lvlpls = level + i1
    for (par in newMap) {
        String lineStrt
        List<Boolean> newLevel = lastLevel
        Boolean thisIsLast = cnt == sz && !listCall
        if (level>iZ) newLevel[(level-i1)] = thisIsLast
        Boolean theLast
        theLast = thisIsLast
        if (level == iZ) lineStrt = sDBNL
        else {
            theLast = theLast && thisIsLast
            lineStrt = doLineStrt(level, newLevel)
            if (listSz && listCnt && listCall) lineStrt += listCnt == i1 && listSz > i1 ? sSPCST : (listCnt<listSz ? sSPCSM : sSPCSE)
            else lineStrt += ((cnt<sz || listCall) && !thisIsLast) ? sSPCSM : sSPCSE
        }
        String k = (String) par.key
        def v = par.value
        String objType = objType(v)
        if (v instanceof Map) {
            str += spanStr(html, lineStrt + "${k}: (${objType})".toString() )
            newLevel[lvlpls] = theLast
            str += dumpMapDesc((Map) v, lvlpls, newLevel, null, null, false, html, reorder)
        }
        else if (v instanceof List || v instanceof ArrayList) {
            str += spanStr(html, lineStrt + "${k}: [${objType}]".toString() )
            newLevel[lvlpls] = theLast
            str += dumpListDesc((List) v, lvlpls, newLevel, sBLK, html, reorder)
        }
        else{
            str += spanStr(html, lineStrt + "${k}: (${v}) (${objType})".toString() )
        }
        cnt += i1
    }
    return str
}

@CompileStatic
static String objType(obj) { return span(myObj(obj), sCLRORG) }

@CompileStatic
static String getMapDescStr(Map data, Boolean reorder = true) {
    List<Boolean> lastLevel = [true]
    String str = dumpMapDesc(data, iZ, lastLevel, null, null, false, true, reorder)
    return str != sBLK ? str : 'No Data was returned'
}

static String myObj(obj) {
    if (obj instanceof String) return 'String'
    else if (obj instanceof Map) return 'Map'
    else if (obj instanceof List) return 'List'
    else if (obj instanceof ArrayList) return 'ArrayList'
    else if (obj instanceof BigInteger) return 'BigInt'
    else if (obj instanceof Long) return 'Long'
    else if (obj instanceof Integer) return 'Int'
    else if (obj instanceof Boolean) return 'Bool'
    else if (obj instanceof BigDecimal) return 'BigDec'
    else if (obj instanceof Double) return 'Double'
    else if (obj instanceof Float) return 'Float'
    else if (obj instanceof Byte) return 'Byte'
    else if (obj instanceof com.hubitat.app.DeviceWrapper) return 'Device'
    else return 'unknown'
}
