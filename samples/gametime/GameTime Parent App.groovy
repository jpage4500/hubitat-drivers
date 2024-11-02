/**
 *  GameTime
 *
 *  Copyright 2021 Justin Leonard
 *
 *  Licensed Virtual the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change History:
 *  v1.2.0 - Full Feature Beta
 *  v1.2.1 - Bug fixes
 *  v1.2.2 - Update scheduling if late night game; Time Formatting improvements
 *  v1.2.3 - Bug fixes
 *  v1.2.4 - Added option to hide game result spoilers
 *  v1.2.5 - Bug fixes
 *  v1.2.6 - Bug fixes
 *  v1.2.7 - Hide record when hide game spoilers
 *  v1.3.0 - Added option to designate team as Low Priority
 *  v1.3.1 - Fixed issue with NFL bye weeks
 *  v1.4.0 - Added schedule attribute
 *  v1.4.1 - Fixed issue with schedule attribute displaying on native hubitat dashboards
 *  v1.4.2 - Bug fix with college schedule tile
 *  v1.4.3 - Improved schedule tile display
 *  v1.5.0 - Improved api key input, added event notifications
 *  v1.5.1 - Fixes issue with pregame event notifications when next game cancelled
 *  v1.5.2 - Fixes issue with updating tile after the last game of the season
 *  v1.5.3 - Fixes issue with tile font size configurability
 *  v1.5.4 - Added Uninstall Confirmation; Added Update Interval Configurability
 *  v1.5.5 - Added ability to configure tile text color from parent GameTime device
 *  v1.5.6 - Fixed issue with NFL post season
 *  v1.5.7 - Gracefully handle unauthorized API access
 *  v1.5.8 - Fixed update interval bug
 *  v1.5.9 - Added disable option
 *  v1.5.10 - FIxed stale logo url issue; fixed time zone bug
 *  v1.5.10 - Fixed stale logo url issue; Fixed time zone / daylight savings time issue
 *  v1.5.12 - Fixed device attributes to honor inactive status
 *  v1.5.13 - Fix bug with API Key input field; Configurable when to clear tile and/or clear device attributes; UI Formatting
 *  v1.5.14 - Fix tile if no clearTile settings 
 *  v1.5.15 - Customizable timeframe for which to display completed game; Added homeOrAway device attribute 
 *  v1.5.16 - Handle daylight savings time change
 *  v1.5.17 - Safeguard added in SetStandings() against team state data having been cleared
 *  v1.6.0  - Determine win-loss from scrambled score; Add option to descramble scoring and display on tile
 *  v1.6.1  - Suppress win-loss notification on app initialization
 *  v1.6.2  - Add option to show game result with color of score
 *  v1.6.3  - Bug fix
 *  v1.6.4  - Bug fix
 *  v1.6.5  - Bug fix to handle 'Canceled' status for a game
 *  v1.6.6  - Bug fix to handle when next game starts before would have switched to display next game
  * v1.6.7  - Bug fix for status on tile
 * v1.6.8   - Bug fix for scores
  * v1.6.9   - Bug fix for scores
* v1.6.10 - Bug fix
* v1.6.11 - Added logo
* v1.6.12 - Bug fix
* v1.6.13 - Exclude playoff games that aren't necessary (e.g., game 7 when series already won)
* v1.6.14 - Bug fix in tile
 */
import java.text.SimpleDateFormat
import groovy.transform.Field

definition(
    name: "GameTime",
    namespace: "lnjustin",
    author: "Justin Leonard",
    description: "GameTime Tracker for College and Professional Sports",
    category: "My Apps",
    oauth: [displayName: "GameTime", displayLink: ""],
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png"
)

@Field String checkMark = "https://raw.githubusercontent.com/lnjustin/App-Images/master/checkMark.svg"
@Field String xMark = "https://raw.githubusercontent.com/lnjustin/App-Images/master/xMark.svg"

preferences {
    page name: "mainPage", title: "", install: true, uninstall: false
    page name: "removePage", title: "", install: false, uninstall: true
}

def mainPage() {
    dynamicPage(name: "mainPage") {
    	    installCheck()
		    if(state.appInstalled == 'COMPLETE'){   
                section() {
                    header()
                }    
                section(getInterface("header", " College Sports")) {
                    app(name: "anyOpenApp", appName: "GameTime College Instance", namespace: "lnjustin", title: "<b>Add a new GameTime instance for college sports</b>", multiple: true)
                }
                section(getInterface("header", " Professional Sports")) {
                    app(name: "anyOpenApp", appName: "GameTime Professional Instance", namespace: "lnjustin", title: "<b>Add a new GameTime instance for professional sports</b>", multiple: true)
                } 
                section("") { 
                    paragraph getInterface("note", txt="After installing or updating your team(s) above, be sure to click the DONE button below.")
                }
                section (getInterface("header", " Game Tile Settings")) {
                    input("showTeamName", "bool", title: "Show Team Name on Tile?", defaultValue: false, displayDuringSetup: false, required: false, width: 4)
                    input("showTeamRecord", "bool", title: "Show Team Record on Tile?", defaultValue: false, displayDuringSetup: false, required: false, width: 4)
                    input("showChannel", "bool", title: "Show TV Channel on Tile?", defaultValue: false, displayDuringSetup: false, required: false, width: 4)
                    input(name:"fontSize", type: "number", title: "Game Tile Font Size (%)", required:true, defaultValue:100, width: 6)
                    input("textColor", "text", title: "Game Tile Text Color (Hex format with leading #)", defaultValue: '#000000', displayDuringSetup: false, required: false, width: 6)
                    input name: "clearTileRule", type: "enum", title: "Select When to Clear Tile", options: ["never":"Never", "inactive":"When No Game +/- X Hours", "seasonEnd" : "X Hours After Season Ends"], submitOnChange:true, width: 7
                    if (clearTileRule == "inactive" || clearTileRule == "seasonEnd") input name: "clearTileRuleHours", type: "number", title: "Hours", defaultValue: 24, width: 5
                    input name: "clearDeviceRule", type: "enum", title: "Select When to Clear Non-Tile Device Attributes", options: ["never":"Never", "inactive":"When No Game +/- X Hours", "seasonEnd" : "X Hours After Season Ends"], submitOnChange:true, width: 7
                    if (clearDeviceRule == "inactive" || clearDeviceRule == "seasonEnd") input name: "clearDeviceRuleHours", type: "number", title: "Hours", defaultValue: 24, width: 5
                }
                section (getInterface("header", " Schedule Tile Settings")) {
                    input(name:"scheduleFontSize", type: "number", title: "Schedule Tile Font Size (%)", required:true, defaultValue:100, width: 12)
                    input(name:"oddRowBackgroundColor", type: "text", title: "Background Color for Odd Rows of Schedule Tile (#Hex)", required:true, defaultValue: '#FFFFFF', width: 4)
                    input(name:"oddRowOpacity", type: "decimal", title: "Opacity (0.0 to 1.0) for Odd Rows of Schedule Tile", required:true, defaultValue: 0, width: 4)
                    input("oddRowTextColor", "text", title: "Text Color for Odd Rows of Schedule Tile (#Hex)", defaultValue: '#000000', displayDuringSetup: false, required: false, width: 4)   
                    input(name:"evenRowBackgroundColor", type: "text", title: "Background Color for Even Rows of Schedule Tile (#Hex)", required:true, defaultValue: '#9E9E9E', width: 4)
                    input(name:"evenRowOpacity", type: "decimal", title: "Opacity (0.0 to 1.0) for Even Rows of Schedule Tile", required:true, defaultValue: 1, width: 4)
                    input("evenRowTextColor", "text", title: "Text Color for Even Rows of Schedule Tile (#Hex)", defaultValue: '#FFFFFF', displayDuringSetup: false, required: false, width: 4)                      
                }
			    section (getInterface("header", " General Settings")) {
                    input("refreshUponDST", "bool", title: "Refresh Upon Daylight Savings Time?", defaultValue: true, required: false)
                    input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false)
		        }
            }
            section("") {
                href(name: "removePage", title: getInterface("boldText", "Remove GameTime"), description: "", required: false, page: "removePage")
                
                footer()
            }
    }
}

String logo(String width='75') {
    return '<img width="' + width + 'px" style="display: block;margin-left: auto;margin-right: auto;margin-top:0px;" border="0" src="' + getLogoPath() + '">'
}

def header() {
    paragraph logo('90')
}

def getLogoPath() {
    return "https://github.com/lnjustin/App-Images/blob/master/GameTime/GameTime.png?raw=true"
}

def footer() {
    paragraph getInterface("line", "") + '<div style="display: block;margin-left: auto;margin-right: auto;text-align:center"><img width="25px" border="0" src="' + getLogoPath() + '"> &copy; 2024 lnjustin.<br>'
}

def removePage() {
	dynamicPage(name: "removePage", title: "Remove GameTime", install: false, uninstall: true) {
		section ("WARNING!\n\nRemoving GameTime will remove all Team Devices\n") {
            header() 
		}
	}
}

 public static getRGB(String hex)
{
    def rgb = []
    for (int i = 0; i < 3; i++)
    {
        rgb[i] = (Integer.parseInt(hex.substring(i * 2 + 1, i * 2 + 3), 16)).toString()
    }
    return rgb
}

def getScheduleTileTextColor(oddOrEven) {
    def color = null
    if (oddOrEven == "odd") {
         color = (oddRowTextColor) ? oddRowTextColor : '#000000'
    }
    else if (oddOrEven == "even") {
        color = (evenRowTextColor) ? evenRowTextColor : '#FFFFFF'
    }
    return color
}

def getScheduleTileFontSize() {
    return scheduleFontSize != null ? scheduleFontSize : 100
}

def getScheduleTileBackgroundColor(oddOrEven) {
    def color = null
    def hex = null
    def op = null
    if (oddOrEven == "odd") {
         hex = (oddRowBackgroundColor) ? oddRowBackgroundColor : '#FFFFFF'
         op = oddRowOpacity ? oddRowOpacity : 0
    }
    else if (oddOrEven == "even") {
        hex = (evenRowBackgroundColor) ? evenRowBackgroundColor : '#9E9E9E'
         op = evenRowOpacity ? evenRowOpacity : 1 
    }
    def rgb = getRGB(hex)
    def opacity = op.toString()
    return "rgba(" + rgb[0] + "," + rgb[1] + "," + rgb[2] + "," + opacity + ")"    
}

def settingUpdate(settingName, value, type) {
    app.updateSetting(settingName, value)
}

def getTextColorSetting() {
    return (textColor) ? textColor : "#000000"
}

def getFontSizeSetting() {
    return fontSize != null ? fontSize : 100
}

def getClearTileRuleHoursSetting() {
    return clearTileRuleHours != null ? clearTileRuleHours : 45
}

def getclearTileRuleSetting() {    
    return clearTileRule != null ? clearTileRule : "never"
}

def getClearDeviceRuleHoursSetting() {
    return clearDeviceRuleHours != null ? clearDeviceRuleHours : 45
}

def getclearDeviceRuleSetting() {    
    return clearDeviceRule != null ? clearDeviceRule : "never"
}  
    
def installed() {
	initialize()
}

def updated() {
    unschedule()
	unsubscribe()
    def storedAPICalls = state.apiCallsThisMonth
    def numMonths = state.numMonthsInstalled
    state.clear()
    if (storedAPICalls != null) state.apiCallsThisMonth = storedAPICalls
    if (numMonths != null) state.numMonthsInstalled = numMonths
	initialize()
}

def uninstalled() {
    deleteDevices()
	logDebug "Uninstalled app"
}

def initialize() {
    createParentDevice()
    childApps.each { child ->
        child.updated()                
    }
}

def refreshChildApps() {
    childApps.each { child ->
        child.update(false)                
    }
}

def getLeagueAPIKey(forAppID, forLeague) {
    def leagueKey = null
    if (forLeague != null) {
        childApps.each { child ->
            if (child.id != forAppID) {
                def childKey = child.getLeagueAPIKey(forLeague)               
                if (childKey != null) leagueKey = childKey
            }
        }    
    }
    return leagueKey
}

def updateLastGameResult(appID) {
    childApps.each { child ->
        if (child.id == appID) {
            child.updateRecord(true)                
        }
    }
}

def fullUpdate(appID) {
    childApps.each { child ->
        if (child.id == appID) {
            child.update()                
        }
    }
}

def installCheck(){
	state.appInstalled = app.getInstallationState() 
	if(state.appInstalled != 'COMPLETE'){
		section{paragraph "Please hit 'Done' to install '${app.label}' parent app "}
  	}
  	else{
    	log.info "Parent Installed"
  	}
}

def createParentDevice()
{
    def parent = getChildDevice("GameTimeParentDevice${app.id}")
    if (!parent) {
        String parentNetworkID = "GameTimeParentDevice${app.id}"
        parent = addChildDevice("lnjustin", "GameTime", parentNetworkID, [label:"GameTime", isComponent:true, name:"GameTime"])
        if (parent) {
            parent.updateSetting("parentID", app.id)
            logDebug("Created GameTime Parent Device")
        }
        else log.error "Error Creating GameTime Parent Device"
    }
}

def deleteDevices() 
{
    deleteChildrenDevices()
    deleteChildDevice("GameTimeParentDevice${app.id}")
}

def deleteChildrenDevices() 
{
    def parent = getChildDevice("GameTimeParentDevice${app.id}")
    if (parent) {
        parent.deleteChildren()
    }
    else log.error "No Parent Device Found. No child devices deleted."    
}

def deleteChildDevice(appID) {
    def parent = getChildDevice("GameTimeParentDevice${app.id}")
    if (parent) {
        parent.deleteChild(appID)
    }
    else log.error "No Parent Device Found."
}

def createChildDevice(appID, name, isLowPriority, lowPriorityThreshold) {
    def parent = getChildDevice("GameTimeParentDevice${app.id}")
    if (parent) {
        parent.createChild(appID, name, isLowPriority, lowPriorityThreshold)
    }
    else log.error "No Parent Device Found."
}

def updateChildDevice(appID, data) {
    def parent = getChildDevice("GameTimeParentDevice${app.id}")
    if (parent) {
        parent.updateChildDevice(appID, data)
    }
    else log.error "No Parent Device Found."
}

def pushDeviceButton(appID, buttonNum) {
    def parent = getChildDevice("GameTimeParentDevice${app.id}")
    if (parent) {
        parent.pushChildDeviceButton(appID, buttonNum)
    }
    else log.error "No Parent Device Found."    
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}

def countAPICall(league) {
    if (state.apiCallsThisMonth == null) state.apiCallsThisMonth = [:]
    if (state.apiCallsThisMonth[league] != null)  state.apiCallsThisMonth[league]++
    else if (state.apiCallsThisMonth[league] == null) state.apiCallsThisMonth[league] = 1
    if (state.apiCallsThisMonth[league] > 1000) log.warn "API Call Limit of 1000 per month exceeded for ${league}. Uncheck 'Clear Teams Data Between Updates' in the app to reduce the number of API calls."
}
    
def updateAPICallInfo(league) {
    Calendar cal = Calendar.getInstance()
    cal.setTimeZone(location.timeZone)
    cal.setTime(new Date())
    def dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
    def isMonthStart = dayOfMonth == 1 ? true : false    
    if (isMonthStart) {
        if (state.numMonthsInstalled == null) state.numMonthsInstalled = [:]
        if (state.numMonthsInstalled[league] == null) {
            state.numMonthsInstalled[league] = 0 // don't start average yet since only installed part of the month
            if (state.apiCallsThisMonth == null) state.apiCallsThisMonth = [:]
            state.apiCallsThisMonth[league] = 0
        }
        else {
            state.numMonthsInstalled[league]++
            if (state.avgAPICallsPerMonth == null) state.avgAPICallsPerMonth = [:]
            if (state.avgAPICallsPerMonth[league] != null) {
                state.avgAPICallsPerMonth[league] = state.avgAPICallsPerMonth[league] + ((state.apiCallsThisMonth[league] - state.avgAPICallsPerMonth[league]) / state.numMonthsInstalled[league])
            }
            else {
                state.avgAPICallsPerMonth[league] = state.apiCallsThisMonth[league]
            }           
            state.apiCallsThisMonth[league] = 0
        }
    }
}

def getInterface(type, txt="", link="") {
    switch(type) {
        case "line": 
            return "<hr style='background-color:#555555; height: 1px; border: 0;'></hr>"
            break
        case "header": 
            return "<div style='color:#ffffff;font-weight: bold;background-color:#555555;border: 1px solid;box-shadow: 2px 3px #A9A9A9'> ${txt}</div>"
            break
        case "error": 
            return "<div style='color:#ff0000;font-weight: bold;'>${txt}</div>"
            break
        case "note": 
            return "<div style='color:#333333;font-size: small;'>${txt}</div>"
            break
        case "subField":
            return "<div style='color:#000000;background-color:#ededed;'>${txt}</div>"
            break     
        case "subHeader": 
            return "<div style='color:#000000;font-weight: bold;background-color:#ededed;border: 1px solid;box-shadow: 2px 3px #A9A9A9'> ${txt}</div>"
            break
        case "subSection1Start": 
            return "<div style='color:#000000;background-color:#d4d4d4;border: 0px solid'>"
            break
        case "subSection2Start": 
            return "<div style='color:#000000;background-color:#e0e0e0;border: 0px solid'>"
            break
        case "subSectionEnd":
            return "</div>"
            break
        case "boldText":
            return "<b>${txt}</b>"
            break
        case "link":
            return '<a href="' + link + '" target="_blank" style="color:#51ade5">' + txt + '</a>'
            break
    }
} 

