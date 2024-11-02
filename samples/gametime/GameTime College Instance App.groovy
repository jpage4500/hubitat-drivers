/**
 *  GameTime College Child
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
 *  Change History in Parent App
 */
import java.text.SimpleDateFormat
import groovy.transform.Field
import groovy.time.TimeCategory

definition(
    name: "GameTime College Instance",
    parent: "lnjustin:GameTime",
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
    page name: "mainPage", title: "", install: true, uninstall: true

}

@Field leagues = ["College Football", "Men's College Basketball", "Women's College Basketball"]
@Field api = ["College Football":"cfb", "Men's College Basketball":"cbb", "Women's College Basketball":"wcbb"]

mappings
{
    path("/gametime/:appId") { action: [ GET: "fetchSchedule"] }
}

def getScheduleEndpoint() {
    return getFullApiServerUrl() + "/gametime/${app.id}?access_token=${state.accessToken}"    
}

def getUpdateInterval() {
    return settings['updateInterval'] != null ? settings['updateInterval']*60 : 600
}

def instantiateToken() {
     if(!state.accessToken){	
         //enable OAuth in the app settings or this call will fail
         createAccessToken()	
     }   
}

def mainPage() {
    dynamicPage(name: "mainPage") {

            def key = getAPIKey()
            section {
                header()                
                paragraph getInterface("header", " GameTime College Instance")
                paragraph getInterface("note", "After selecting the league and your team, click DONE. This will create a device for the selected team, listed under the GameTime parent device.")
                input(name:"league", type: "enum", title: "College Sports League", options: leagues, required:true, submitOnChange:true)

                if(league) {
                    def availableLeagueKey = parent.getLeagueAPIKey(app.id, league)
                    if (availableLeagueKey && reuseLeagueKeySetting()) app.updateSetting("reuseLeagueKey",[value:"true",type:"bool"])
                    if ((availableLeagueKey && reuseLeagueKeySetting()) || (availableLeagueKey && !reuseLeagueKeySetting() && apiKey) || (!availableLeagueKey && apiKey)) {
                        if (!state.teams) setTeams()
                        input(name:"conference", type: "enum", title: "Conference", options: getConferenceOptions(), required:true, submitOnChange:true)
                        if (conference) input(name:"team", type: "enum", title: "Team", options: getTeamOptions(), required:true, submitOnChange:true)
                    }
                    else {
                        if (availableLeagueKey) input(name:"reuseLeagueKey", type: "bool", title: "Re-use API key for league from other GameTime app instance?", required:true, submitOnChange:true)
                        def link = getInterface("link", "SportsData.IO API Key", "https://sportsdata.io/")
                        def inputTitle = link + " for ${league}"
                        input(name:"apiKey", type: "text", title: inputTitle, required:true, submitOnChange:true)
                    }
                }  
            }
            if (team) {
                section (getInterface("header", " Tile Timing Settings")) {  
                        input name: "updateInterval", type: "number", title: "Update Interval While Game In Progress (mins)", defaultValue: 10
                        input name: "displayCompletedGameDays", type: "number", title: "Days for which to display a completed game", defaultValue: 1, width: 6
                        def defaultTime = timeToday("09:00", location.timeZone).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ", location.timeZone)
                        input name: "displayCompletedGameTime", type: "time", title: "Time on last day until which to display a completed game", defaultValue: defaultTime, width: 6
                        paragraph getInterface("note", "Example: Selecting '1' for Days and '9:00 AM' for Time displays a completed game until 9:00 AM the next day, at which point the next game will display.") 
                        input name: "lowPriority", title:"Low Priority Team?", type:"bool", required:false, submitOnChange:false
                        input name: "priorityHourThreshold", type: "number", title: "Low Priority Team Hour Threshold", defaultValue: 24
                        paragraph getInterface("note", "A low priority team will only display on the 'all teams' GameTime device if no higher priority team has a game within X hours. The Low Priority Team Hour Threshold specifies X. If you change the priority status of a team after install, you must go to the parent app and click DONE in order for the prioritzation change to have immediate effect.") 
                        input name: "numGamesForSchedule", type: "number", title: "Num Games For Schedule Tile", defaultValue: 3
                }


                section (getInterface("header", " Tile Content Settings")) {  
                    input name: "showScore", title:"Show Score?",  type:"bool", required:false, defaultValue: false, submitOnChange:true
                    paragraph getInterface("note", "Note: Score display is a best effort feature. The displayed score is often correct but is nonetheless subject to inaccuracy, especially lower scoring games, due to the nature of the API used.")
                    if (showScore) {
                        if (state.calibrationData != null && !recalibrateScoring) {
                            paragraph getInterface("note", "Scoring has been calibrated based on the game against ${state.calibrationData.game.opponent.displayName} that occurred ${getGameTimeStrFromUnix(state.calibrationData.game.gameTime)} ")
                            input("recalibrateScoring", "bool", title: "Re-Calibrate Scoring?", defaultValue: false, required: false, submitOnChange: true)
                        }
                        def calibrationGame = null
                        if (state.calibrationData == null || recalibrateScoring == true) {
                            // get last game data for calibration
                            calibrationGame = getLastGameData()
                        }
                        if (calibrationGame && ((state.calibrationData != null && recalibrateScoring == true) || state.calibrationData == null)) {
                            paragraph getInterface("note", "To calibrate scoring, enter the score of the team's last game against " + calibrationGame.opponent.displayName + " " + getGameTimeStrFromUnix(calibrationGame.gameTime) +  ". Calibration requires that neither team have a score of 0.") 
                            input name: "actualLastGameHomeScore", type: "number", title: "" + calibrationGame.homeTeam?.displayName + " Score"
                            input name: "actualLastGameAwayScore", type: "number", title: "" + calibrationGame.awayTeam?.displayName + " Score"
                        }
                        else if (calibrationGame == null && ((state.calibrationData != null && recalibrateScoring == true) || state.calibrationData == null)) {
                            paragraph getInterface("note", "Scoring calibration requires at least one past game that has completed. Return back to the app once a game has completed, in order to calibrate scoring.")
                        }
                    }
                    input name: "showGameResult", title:"Show Game Result?", type:"bool", required:false, defaultValue: false, submitOnChange:true
                    if (showGameResult && showScore) {
                        input(name:"showGameResultMethod", type: "enum", title: "Select How to Show Result", options: ["Text on Tile", "Color of Score"], required:true, submitOnChange:false)
                    }
                }
                section (getInterface("header", " Event Handling")) {  
                    paragraph getInterface("subHeader", " First Pre-Game Event")
                    paragraph getInterface("note", "Button 1 will be pushed upon the first pre-game event.") 
                    input name: "firstEventAdvance", type: "number", title: "First Pre-Game Event Occurs How Many Minutes Before GameTime?", defaultValue: 60
                    input name: "isFirstEventNotify", title:"Send Push Notification?", type:"bool", required:false, submitOnChange:false, defaultValue: false
                    paragraph getInterface("subHeader", " Second Pre-Game Event")
                    paragraph getInterface("note", "Button 2 will be pushed upon the second pre-game event.") 
                    input name: "secondEventAdvance", type: "number", title: "Second Pre-Game Event Occurs How Many Minutes Before GameTime?", defaultValue: 0
                    input name: "isSecondEventNotify", title:"Send Push Notification?", type:"bool", required:false, submitOnChange:false, defaultValue: false
                    paragraph getInterface("subHeader", " Win Event")
                    paragraph getInterface("note", "Button 3 will be pushed upon a win.") 
                    input name: "isWinEventNotify", title:"Send Push Notification?", type:"bool", required:false, submitOnChange:false, defaultValue: false
                    paragraph getInterface("subHeader", " Loss Event")
                    paragraph getInterface("note", "Button 4 will be pushed upon a loss.")
                    input name: "isLossEventNotify", title:"Send Push Notification?", type:"bool", required:false, submitOnChange:false, defaultValue: false
                    input name: "notificationDevices", type: "capability.notification", title: "Devices to Notify", required: false, multiple: true, submitOnChange: false
                }
            }
            section (getInterface("header", " Settings")) {
                if (team) label title: "GameTime Instance Name", defaultValue: team + " GameTime Instance", required:false, submitOnChange:true
                input("clearStateBetweenUpdate", "bool", title: "Clear teams data between updates?", defaultValue: true, required: false)
                paragraph getInterface("note", "Enabling Clear 'Teams Data Between Updates' reduces state size. Disabling conserves API calls.")
               
                if (league) {
                    def availableLeagueKey = parent.getLeagueAPIKey(app.id, league)
                    if ((availableLeagueKey && reuseLeagueKeySetting()) || (availableLeagueKey && !reuseLeagueKeySetting() && apiKey) || (!availableLeagueKey && apiKey)) {
                        if (availableLeagueKey) input(name:"reuseLeagueKey", type: "bool", title: "Re-use API key for league from other GameTime app instance?", required:true, submitOnChange:true)
                        if (availableLeagueKey && reuseLeagueKeySetting()) {
                            paragraph getInterface("note", "Re-Using API Key: ${availableLeagueKey}")
                        }
                        if (!availableLeagueKey || !reuseLeagueKeySetting()) {
                            def link = getInterface("link", "SportsData.IO API Key", "https://sportsdata.io/")
                            def inputTitle = link + " for ${league}"
                            input(name:"apiKey", type: "text", title: inputTitle, required:true, submitOnChange:true)
                        }
                    }
                }
			    input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false)
                input name: "disabled", title:"Manually Disable?", type:"bool", required:false, submitOnChange:false
		    }
            section("") {
                
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

def getLeagueAPIKey(forLeague) {
    def key = null
    if (league == forLeague) key = apiKey
    return key
}
                         
def getAPIKey() {
    def key = null
    def availableLeagueKey = parent.getLeagueAPIKey(app.id, league)
    if ((availableLeagueKey && !reuseLeagueKey && apiKey) || (!availableLeagueKey && apiKey)) key = apiKey
    else if (availableLeagueKey && reuseLeagueKey) key = availableLeagueKey
    return key
}

def reuseLeagueKeySetting() {
    def setting = reuseLeagueKey != null ? reuseLeagueKey : true
    log.debug "Setting: ${setting}"
    return setting
}

Integer getNumGamesForScheduleSetting() {
    return numGamesForSchedule != null ? (int) numGamesForSchedule : 3
}

Integer getFirstEventAdvanceSetting() {
    return firstEventAdvance != null ? (int) firstEventAdvance : 60
}

Integer getSecondEventAdvanceSetting() {
    return secondEventAdvance != null ? (int) secondEventAdvance : 0
}

def getLowPriorityThresholdSetting() {
    return priorityHourThreshold != null ? priorityHourThreshold : 24
}

def getLowPrioritySetting() {
    return lowPriority ? lowPriority : false
}


def getFontSizeSetting() {
    return parent.getFontSizeSetting()
}

def getShowGameResultSetting() {
    return showGameResult ? showGameResult : false
}

def getShowScoreSetting() {
    return showScore ? showScore : false
}

def getScheduleFontSizeSetting() {
    return parent.getScheduleTileFontSize()
}

def getScheduleTextColorSetting(oddOrEven) {
    return parent.getScheduleTileTextColor(oddOrEven)
}

def getScheduleBackgroundColorSetting(oddOrEven) {
    return parent.getScheduleTileBackgroundColor(oddOrEven)
}

def getTextColorSetting() {
    return parent.getTextColorSetting()
}

def getClearStateSetting() {
    return clearStateBetweenUpdate != null ? clearStateBetweenUpdate : true
}

def getClearTileRuleHoursSetting() {
    return parent.getClearTileRuleHoursSetting()
}

def getclearTileRuleSetting() {    
    return parent.getclearTileRuleSetting()
}

def getClearDeviceRuleHoursSetting() {
    return parent.getClearDeviceRuleHoursSetting()
}

def getclearDeviceRuleSetting() {    
    return parent.getclearDeviceRuleSetting()
}

def displayCompletedGameDaysSetting() {
    return displayCompletedGameDays ?: 1
}

def displayCompletedGameTimeSetting() {
    def defaultTime = timeToday("09:00", location.timeZone).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ", location.timeZone)
    return displayCompletedGameTime ?: defaultTime
}

def getTeamKey() {
    return state.team?.key  
}

def installed() {
	initialize()
}

def updated() {
    unschedule()
	unsubscribe()
    def storedRecord = state.lastRecord
    def storedCalibrationData = state.calibrationData
    def storedGameForCalibration = state.gameForCalibration
    state.clear()
    if (storedRecord != null) state.lastRecord = storedRecord
    state.calibrationData = storedCalibrationData
    state.gameForCalibration = storedGameForCalibration
	initialize()
}

def uninstalled() {
    deleteChild()
	logDebug "Uninstalled app"
}

def initialize() {
    instantiateToken()
    if (!settings["disabled"]) {
        def key = getAPIKey()
        if (league && team && key) {
            setTeams()
            setMyTeam()
            createChild()
            update(true, true)
            app.updateSetting("recalibrateScoring",[value:"false",type:"bool"])
            schedule("01 01 00 ? * *", scheduledUpdate)
        }
        else log.error "Missing input fields."
    }
}

def getLastGameData() {
    setMyTeam()
    def schedule = fetchTeamSchedule()
    if (schedule == "Error: first byte timeout") {
        log.warn "API call timeout."
        return
    }
    
    def now = new Date()
    def lastGame = null
    for (game in schedule) {
        def gameTime = getGameTime(game)
        def status = game.Status 
        if (gameTime != null && (gameTime.after(now) || gameTime.equals(now)  || status == "Scheduled" || status == "InProgress"  || status == "Delayed")) {
            // nothing to do
        }
        else if (gameTime != null) {
            // handle finished game
            if (lastGame == null) lastGame = game
            else {
                 def lastGameTime = getGameTime(lastGame)
                 if (getSecondsBetweenDates(gameTime, now) < getSecondsBetweenDates(lastGameTime, now)) {
                      lastGame = game
                 }
            }
        }
    }

    state.gameForCalibration = getGameData(lastGame)
    return state.gameForCalibration
}

def setScoreScalingFactor() {
    logDebug("Scoring Calibration: Setting Scaling Factor.")
    if (actualLastGameHomeScore && actualLastGameHomeScore > 0 && actualLastGameAwayScore && actualLastGameAwayScore > 0 && state.gameForCalibration != null) {
        if (state.calibrationData == null || (state.calibrationData != null && recalibrateScoring == true)) {
            state.calibrationData = [:]
            state.calibrationData.game = state.gameForCalibration
            if (state.gameForCalibration.scrambledHomeScore > 0 && state.gameForCalibration.scrambledAwayScore > 0) {
                def scaleFactor = actualLastGameHomeScore / state.gameForCalibration.scrambledHomeScore
                def calculatedAwayScore = Math.round(state.gameForCalibration.scrambledAwayScore * scaleFactor)
                state.calibrationData.scaleFactor = scaleFactor
                state.calibrationData.descrambledAwayScore = actualLastGameAwayScore
                state.calibrationData.descrambledHomeScore = actualLastGameHomeScore
                if (calculatedAwayScore == actualLastGameAwayScore) {
                    logDebug("Scoring Calibration Success! Scaling factor is ${scaleFactor}.")
                }
                else {
                    logDebug("Scoring Calibration Potentially Inaccurate: With a scaling factor of ${scaleFactor}, calculated away score of ${calculatedAwayScore} but actual away score was ${actualLastGameAwayScore}.")
                }
            }
            else logDebug("Warning: Calibration attempted with a game in which at least one team had a score of 0. No calibration occurred. Retry with a game in which both teams have a non-zero score.")
        }
        else logDebug("Settings updated with Re-calibrating scoring.")
    }
    else if ((actualLastGameHomeScore && actualLastGameHomeScore == 0) || (actualLastGameAwayScore && actualLastGameAwayScore == 0)) {
        logDebug("Warning: Calibration attempted with a game in which at least one team had a score of 0. No calibration occurred. Retry with a game in which both teams have a non-zero score.")
    }
    else logDebug("Warning: Scoring Calibration not completed.")
}

def scheduledUpdate()
{
    scheduleUpdateUponDSTChange()
    update(true)    
}

def scheduleUpdateUponDSTChange() {
    TimeZone timeZone = TimeZone.getDefault()
    def observesDST = timeZone.observesDaylightTime()
    if (observesDST) {
        // if DST observed, then check to see if DST change will occur today
        def now = new Date()
        def midnight =  now.copyWith(hourOfDay: 0, minute: 1, seconds: 0)
        def twoAM =  now.copyWith(hourOfDay: 2, minute: 1, seconds: 0)
        def inDSTMidnight = timeZone.inDaylightTime(midnight)
        def inDST2am = timeZone.inDaylightTime(twoAM)
        if (inDSTMidnight != inDST2am) {
            logDebug("DST Change Occurs Today")
            if (twoAM.after(now)) {
                logDebug("Scheduling update for 2:00 am today in order to update after DST Change")
                runOnce(twoAM, update)
            }
            else logDebug("But not scheduling update because DST change has already occurred")
        }
    }
}

def setTeams() {
   if (!state.teams) state.teams = [:]
   def fullTeams = fetchTeams()
   for (tm in fullTeams) {
       def displayName = tm.School + " " + tm.Name
       def teamMap = [id: tm.TeamID, key: tm.Key, school: tm.School, name: tm.Name, logo: tm.TeamLogoUrl, displayName: tm.ShortDisplayName, rank: tm.ApRank, wins: getIntValue(tm.Wins), losses: getIntValue(tm.Losses), conference: tm.Conference]
       state.teams[tm.Key] = teamMap
   }
}

def setStandings() {
    if (!state.teams) setTeams()
    else {
        def fullTeams = fetchTeams()
        for (tm in fullTeams) {
            state.teams[tm.Key]?.wins = getIntValue(tm.Wins)
            state.teams[tm.Key]?.losses = getIntValue(tm.Losses)
            state.teams[tm.Key]?.rank = tm.ApRank
            if (tm.Key == state.team.key) {
                state.team = state.teams[tm.Key]
            }
        }
    }
}

def getIntValue(standingComponent) {
    def value = 0   
    if (standingComponent != null && standingComponent != "null") {
        value = standingComponent as Integer
    }
    return value
}

Date getDateObj(dateStr) {
    // accepts input in format "yyyy-MM-dd'T'HH:mm:ss", without time zone information. Assumes eastern time zone already adjusted for DST
    def str = dateStr
    TimeZone tz = TimeZone.getTimeZone("America/New_York")
    Calendar cal = Calendar.getInstance()
    cal.setTimeZone(tz)
    def estDate = cal.time 
    def isDST = tz.inDaylightTime(estDate)
    if (isDST) str = str + "-04:00"
    else str = str + "-05:00"    
    def dateObj = toDateTime(str)
  //  logDebug("Converting gametime. EST Date is ${estDate}. isDST = ${isDST}. gametime is ${str}. dateObj is ${dateObj}")
    return dateObj
}

def isToday(Date date) {
    def isToday = false
    if (date != null) {
        def today = new Date().clearTime()
        def dateCopy = new Date(date.getTime())
        def dateObj = dateCopy.clearTime()
        if (dateObj.equals(today)) isToday = true
    }
    return isToday
}

def isYesterday(Date date) {
    def isYesterday = false
    def today = new Date().clearTime()
    def yesterday = today - 1
    def dateCopy = new Date(date.getTime())
    def dateObj = dateCopy.clearTime()    
    if (dateObj.equals(yesterday)) isYesterday = true
    return isYesterday
}

def update(onInitialize = false, setScaleFactor = false) {
    logDebug("Updating GameTime for ${state.team.displayName}")
    if (!state.teams) setTeams()
    updateState(onInitialize)
    if (setScaleFactor) {
        setScoreScalingFactor()
        updateState(onInitialize) // update state again after acquiring scaling factor
    }
    updateDisplayedGame()
    scheduleUpdate()
}

def getScheduleTile() {  
    logDebug("Getting schedule tile for ${state.team.displayName}")
    if (!state.refreshNum) state.refreshNum = 0
    state.refreshNum++
    def scheduleUrl = getScheduleEndpoint() + '&version=' + state.refreshNum   
        
    def scheduleTile =     "<div style='height:100%;width:100%'><iframe src='${scheduleUrl}' style='height:100%;width:100%;border:none'></iframe></div>"
    return scheduleTile
}

def fetchSchedule() {
    logDebug("Fetching Schedule")
    if(params.appId.toInteger() != app.id) {
        logDebug("Returning null since app ID received at endpoint is ${params.appId.toInteger()} whereas the app ID of this app is ${app.id}")
        return null    // request was not for this app/team, so return null
    }
    
    def scheduleTile = "<div style='height:100%'></div>"
    def oddTextColor = getScheduleTextColorSetting("odd")
    def oddBackgroundColor = getScheduleBackgroundColorSetting("odd")
    def evenTextColor = getScheduleTextColorSetting("even")
    def evenBackgroundColor = getScheduleBackgroundColorSetting("even")
    def fontSize = getScheduleFontSizeSetting()
    if (state.schedule != null) {
        scheduleTile = "<div style='height:100%;'><table width='100%' style='border-collapse: collapse; font-size:${fontSize}%;'>"
        def numRows = 0
        def numGames = state.schedule.size()
        for (game in state.schedule) {
            def backgroundColor = numRows % 2 == 0 ? evenBackgroundColor : oddBackgroundColor
            def textColor = numRows % 2 == 0 ? evenTextColor : oddTextColor
            scheduleTile += "<tr width='100%' height='${100/numGames}%' style='background-color:${backgroundColor}; color: ${textColor}'><td width='25%' style='margin:0; padding:4' align=left>${getGameDayOfWeek(game.gameTime)} <b>${getGameDate(game.gameTime)}</b></td>"
            scheduleTile += "<td width='100%' style='padding:4;display:flex; align-items:center; justify-content: center;' align=center>" + (game.homeOrAway == "home" ? "vs " : "@ ") + "<img src='${game.opponentLogo}' height='" + (25*fontSize).div(100) + "vh' style='padding:4'> ${game.opponent}</td>"
            scheduleTile += "<td width='25%' style='padding:4;margin:0' align=right>${getGameTimeOfDay(game.gameTime)}</td></tr>"
            numRows++
        }
        scheduleTile += "</table></div>" 
    }
    logDebug("Calling render on ${scheduleTile}")
    render contentType: "text/html", data: scheduleTile, status: 200
}


String getGameDate(gameTime) {
    Date gameTimeDateObj = new Date(gameTime)
    def dateFormat = new SimpleDateFormat("MMM d")
    dateFormat.setTimeZone(location.timeZone)        
    def gameDateStr = dateFormat.format(gameTimeDateObj)    
    return gameDateStr
}

String getGameDayOfWeek(gameTime) {
    Date gameTimeDateObj = new Date(gameTime)
    def dateFormat = new SimpleDateFormat("EEE")
    dateFormat.setTimeZone(location.timeZone)        
    def gameDayOfWeekStr = dateFormat.format(gameTimeDateObj)    
    return gameDayOfWeekStr    
}

String getGameTimeOfDay(gameTime) {
    Date gameTimeDateObj = new Date(gameTime)
    def dateFormat = new SimpleDateFormat("h:mm a")
    dateFormat.setTimeZone(location.timeZone)        
    def gameTimeOfDayStr = dateFormat.format(gameTimeDateObj)    
    return gameTimeOfDayStr    
}

def isLogoFound(logoUrl) {
    def isLogoFound = true
    try {
      
		httpGet(logoUrl) { resp ->
			if (resp.data.status == 404) {
                logDebug("Logo image not found at ${logoUrl}")
				isLogoFound = false
			}
		}
    } catch (UnknownHostException uhe) {
        // Handle exceptions as necessary
    } catch (FileNotFoundException fnfe) {
        logDebug("Logo image not found at ${logoUrl}")
        isLogoFound = false
    } catch (Exception e) {
        // Handle exceptions as necessary
    }   
    return isLogoFound
}

def getScheduleData(upcomingSchedule) {
    def scheduleData = []
    for (game in upcomingSchedule) {
        if (game != null) {
            def gameTime = getGameTime(game)              
            def homeTeam = state.teams[game.HomeTeam]
            def awayTeam = state.teams[game.AwayTeam]
            def opponent = null
            def opponentLogo = null
            def homeOrAway = null
            if (homeTeam.key == state.team.key) {
                opponent = awayTeam.name
                opponentLogo = awayTeam.logo
                if (!isLogoFound(opponentLogo)) {
                    logDebug("Refreshing logo state for game.AwayTeam")
                    setTeams() // refresh team state data for latest logo url
                    opponentLogo = state.teams[game.AwayTeam].logo
                    if (isLogoFound(opponentLogo)) logDebug("Fixed logo for ${game.AwayTeam}. New logo works at ${opponentLogo}")
                    else logDebug("Logo for ${game.AwayTeam} not fixed even after refresh. Logo at ${opponentLogo} broken too.")
                }
                homeOrAway = "home"
            }
            else if (awayTeam.key == state.team.key) {
                opponent = homeTeam.name
                opponentLogo = homeTeam.logo
                if (!isLogoFound(opponentLogo)) {
                    logDebug("Refreshing logo state for game.HomeTeam")
                    setTeams() // refresh team state data for latest logo url
                    opponentLogo = state.teams[game.HomeTeam].logo
                    if (isLogoFound(opponentLogo)) logDebug("Fixed logo for ${game.HomeTeam}. New logo works at ${opponentLogo}")
                    else logDebug("Logo for ${game.HomeTeam} not fixed even after refresh. Logo at ${opponentLogo} broken too.")
                }
                homeOrAway = "away"
            }

            def gameData = [gameTime: gameTime.getTime(), homeOrAway: homeOrAway, opponent: opponent, opponentLogo: opponentLogo]
            scheduleData.add(gameData)
        }        
    }

    scheduleData = scheduleData.sort {it.gameTime}
    def maxNumGames = getNumGamesForScheduleSetting()
    def subListIndex = maxNumGames < scheduleData.size() ? maxNumGames : scheduleData.size()
    scheduleData = scheduleData.subList(0, subListIndex)

    return scheduleData
}

Date getGameTime(game) {
    def dateTime = null
    Date gameTime = null
    if ((game.DateTime == null || game.DateTime == "null") && (game.Day != null && game.Day != "null")) dateTime = game.Day  // game on Day but time not yet set
    else if (game.DateTime != null && game.DateTime != "null") dateTime = game.DateTime
    if (dateTime) gameTime = getDateObj(dateTime)
    return gameTime
}

String getGameTimeStr(Date gameTime) {
    def now = new Date()
    def nextWeek = new Date().clearTime() + 7
    def lastWeek = new Date().clearTime() - 7
    def dateFormat = null
    def gameTimeStrPrefix = ""
    if (gameTime.after(nextWeek)) dateFormat = new SimpleDateFormat("EEE, MMM d h:mm a")
    else if (isToday(gameTime)) {
        gameTimeStrPrefix = "Today "
        dateFormat = new SimpleDateFormat("h:mm a")
    }
    else if (isYesterday(gameTime)) {
        gameTimeStrPrefix = "Yesterday "
        dateFormat = new SimpleDateFormat("h:mm a")
    }
    else if (gameTime.before(lastWeek)) dateFormat = new SimpleDateFormat("EEE, MMM d h:mm a")
    else if (gameTime.before(now)) {
        gameTimeStrPrefix = "This Past "
        dateFormat = new SimpleDateFormat("EEE h:mm a")
    }
    else dateFormat = new SimpleDateFormat("EEE h:mm a")
    dateFormat.setTimeZone(location.timeZone)        
    def gameTimeStr = gameTimeStrPrefix + dateFormat.format(gameTime)    
    return gameTimeStr
}

def getGameTimeStrFromUnix(unixGameTime) {
    def dateObj = new Date(unixGameTime)
    return getGameTimeStr(dateObj)
}

def updateState(onInitialize = false) {     
    updateAPICallInfo()
    def storedNextGame = state.nextGame
    def storedLastGame = state.lastGame
    def storedRecord = getRecord(state.team)
    
    def schedule = fetchTeamSchedule()
    if (schedule == "Error: first byte timeout") {
        log.warn "API call timeout. Not updating state. Will try again later."
        runIn(getUpdateInterval(), update)
        return
    }
    
    def now = new Date()
    def lastGame = null
    def nextGame = null
    def upcomingSchedule = []
    for (game in schedule) {
        def gameTime = getGameTime(game)
        def status = game.Status 
        if (gameTime != null  && status != "NotNecessary" && (gameTime.after(now) || gameTime.equals(now)  || status == "Scheduled" || status == "InProgress"  || status == "Delayed")) {
            // handle upcoming game
            if (nextGame == null) nextGame = game
            else {
                def nextGameTime = getGameTime(nextGame)
                if (status == "InProgress") {
                    if (nextGame.Status != "InProgress") nextGame = game
                    else if (nextGame.Status == "InProgress" && nextGameTime.after(gameTime)) {
                        nextGame = game    // display whichever game started earlier
                    }
                }
                else if (nextGame.Status != "InProgress" && getSecondsBetweenDates(now, gameTime) < getSecondsBetweenDates(now, nextGameTime)) {
                    nextGame = game
                }
            }
            if (gameTime.after(now) || status == "Scheduled") upcomingSchedule.add(game)
        }
        else if (gameTime != null && status != "NotNecessary") {
            // handle finished game
            if (lastGame == null) lastGame = game
            else {
                 def lastGameTime = getGameTime(lastGame)
                 if (getSecondsBetweenDates(gameTime, now) < getSecondsBetweenDates(lastGameTime, now)) {
                      lastGame = game
                 }
            }
        }
    }

    state.nextGame = getGameData(nextGame)
    state.lastGame = getGameData(lastGame)
    if (storedLastGame && storedLastGame.id == state.lastGame?.id) state.lastGame?.notifiedOfResult = storedLastGame?.notifiedOfResult
    state.schedule = getScheduleData(upcomingSchedule)
    setStandings()
    
    def hasRecordChanged = hasRecordChanged(storedRecord)
    if (hasRecordChanged) {
        logDebug("Record has changed. Setting last record to ${storedRecord} for determining the result of the last game.")
        state.lastRecord = [wins: storedRecord.wins, losses: storedRecord.losses, asOf: (new Date()).getTime()]  
    }
    else {
        logDebug("Team Record has not changed, eiher because just initialized or because API has not reported a change. No update to state.lastRecord made.")
        if (storedNextGame && state.lastGame?.id == storedNextGame.id) {
            if (state.lastGame.status == "Final" || state.lastGame.status == "F/OT") {
                logDebug("API reported game as over before record was updated.")
            }
            else {
                logDebug("Reason for record not being updated may be that the game was canceled or postponed.")
            }
        }
    }
    if (getShowGameResultSetting() && state.lastGame != null) {
        if (state.lastGame.status == "Final" || state.lastGame.status == "F/OT" || state.lastGame.status == "F/SO") {
            // Only report game result once API reports game as over
            setLastGameResult(hasRecordChanged, onInitialize)
        }
    }

    Date dateToUpdateDisplay = getDateToSwitchFromLastToNextGame()
    if (dateToUpdateDisplay != null && dateToUpdateDisplay.after(now)) runOnce(dateToUpdateDisplay, updateDisplayedGame)
}

def setLastGameResult(hasRecordChanged, onInitialize = false) {
    state.lastGame.resultFromScore = getLastGameResultFromScore()
    if (state.lastGame.resultFromScore == null && hasRecordChanged) state.lastGame.resultFromRecord = getLastGameResultFromRecord(onInitialize)
    if (state.lastGame.resultFromScore != null) state.lastGame.status = state.lastGame.resultFromScore
    else if (state.lastGame.resultFromRecord != null) state.lastGame.status = state.lastGame.resultFromRecord
    if (state.lastGame.resultFromScore != null && state.lastGame.resultFromRecord != null && state.lastGame.resultFromRecord != state.lastGame.resultFromScore) {
        def resultWarning = "Warning: Determined the result of the last game against ${state.lastGame.opponent.displayName} as ${state.lastGame.resultFromRecord} from the team record, but determined the result of the last game as ${state.lastGame.resultFromScore} from the scrambled score."
        logDebug(resultWarning)
        notificationDevices.deviceNotification(resultWarning) 
    }
    if (state.lastGame.notifiedOfResult == null || state.lastGame.notifiedOfResult == false) {
        if (state.lastGame.status == "Won") {
            if (!onInitialize) handleWinEvent(state.lastGame)
            state.lastGame.notifiedOfResult = true
        }
        else if (state.lastGame.status == "Lost") {
            if (!onInitialize) handleLossEvent(state.lastGame)
            state.lastGame.notifiedOfResult = true
        }
    }
}

def getLastGameResultFromScore() { 
    def result = null
    if (state.lastGame.homeOrAway == "Home") { 
        if (state.lastGame.scrambledHomeScore < state.lastGame.scrambledAwayScore) result = "Lost"
        else if (state.lastGame.scrambledHomeScore > state.lastGame.scrambledAwayScore) result = "Won"
        else result = null // Tied scrambled score unreliable, so fallback to record
    } else if (state.lastGame.homeOrAway == "Away") { 
        if (state.lastGame.scrambledHomeScore > state.lastGame.scrambledAwayScore) result = "Lost"
        else if (state.lastGame.scrambledHomeScore < state.lastGame.scrambledAwayScore) result = "Won"
        else result = null // Tied scrambled score unreliable, so fallback to record
    }
    return result
}

def hasRecordChanged(storedRecord) {
    def currentRecord = getRecord(state.team)
    def hasChanged = false
    if (currentRecord.wins != storedRecord.wins || currentRecord.losses != storedRecord.losses) hasChanged = true 
    return hasChanged
}

def getRecord(team) {
    return [wins: team.wins as Integer, losses: team.losses as Integer]
}

def updateRecord(onDemand = false) {
    def update = false
    if (onDemand == true) update = true
    else if (state.updateAttempts != null && state.updateAttempts > 12) {
        // abort update attempt
        state.updateAttempts = 0
    }
    else {
        update = true
        if (state.updateAttempts == null) state.updateAttempts = 1
        else state.updateAttempts++
    }
    if (update == true) {
        def storedRecord = getRecord(state.team)
        setStandings()
        def hasRecordChanged = hasRecordChanged(storedRecord)
        if (hasRecordChanged) state.lastRecord = [wins: storedRecord.wins, losses: storedRecord.losses, asOf: (new Date()).getTime()] 
        else logDebug("Team Record has not changed. No update to state.lastRecord made.")
        setLastGameResult(hasRecordChanged, onDemand)
        updateDisplayedGame()
    }
}

def getLastGameResultFromRecord(suppressRetry = false) {
    def result = null
    def recordNotUpdated = false
    def currentRecord = getRecord(state.team)
    if (state.lastRecord == null) {
        def warning = "Unable to determine result of last game for ${state.team.name}. Last team record not stored."
        if (suppressRetry == false) {
            runIn(getUpdateInterval(), updateRecord)
            warning += " Will keep checking."
        }
        logDebug(warning)
        return null
    }
    else if (state.lastGame != null && state.lastRecord.asOf <= state.lastGame.gameTime) {
        logDebug("Record not yet updated for last game.")
        if (suppressRetry == false) {
            runIn(getUpdateInterval(), updateRecord)
            logDebug(" Will keep checking.")
        }
        return null        
    }
    if (currentRecord.wins == state.lastRecord.wins && currentRecord.losses == state.lastRecord.losses + 1) result = "Lost"
    else if (currentRecord.wins == state.lastRecord.wins + 1 && currentRecord.losses == state.lastRecord.losses) result = "Won"
    else if (currentRecord.wins == state.lastRecord.wins && currentRecord.losses == state.lastRecord.losses) recordNotUpdated = true
    if (result == null) {
        def warning = "Warning: Unable to Determine Result of Last Game for ${state.team.name}."
        if (recordNotUpdated == true) {
            warning += " Record has not been updated yet."
            if (suppressRetry == false) {
                runIn(getUpdateInterval(), updateRecord)
                warning += " Will keep checking."
            }
        }
        warning += " Last Record is wins: ${state.lastRecord.wins} losses: ${state.lastRecord.losses}. Current Record is wins: ${currentRecord.wins} losses: ${currentRecord.losses}."
        logDebug(warning)
    }            
    else {
        logDebug("Determined last game result: ${result}")
    }
    return result
}

def handleWinEvent(lastGame) {
    def opponent = lastGame.opponent.displayName
    pushDeviceButton(3)
    if (isWinEventNotify == true && notificationDevices != null) {          
        def scoreText = ""
        if (lastGame.descrambledAwayScore && lastGame.descrambledHomeScore) scoreText = lastGame.descrambledHomeScore + " - " + lastGame.descrambledAwayScore
        notificationDevices.deviceNotification("Victory! ${state.team.displayName} wins over ${opponent}${scoreText ? ' ' + scoreText : ''}!") 
    }
}

def handleLossEvent(lastGame) {
    def opponent = lastGame.opponent.displayName
    pushDeviceButton(4)
    if (isLossEventNotify == true && notificationDevices != null) {          
        def scoreText = ""
        if (lastGame.descrambledAwayScore && lastGame.descrambledHomeScore) scoreText = lastGame.descrambledHomeScore + " - " + lastGame.descrambledAwayScore
        notificationDevices.deviceNotification("Defeat. ${state.team.displayName} loses to ${opponent}${scoreText ? ' ' + scoreText : ''}!") 
    }    
}

def updateAPICallInfo() {
    parent.updateAPICallInfo(league)
}

def getGameData(game) {
    def gameData = null
    if (game != null) {
        def gameID = game.GameID
        def gameTime = getGameTime(game)
        def gameTimeStr = getGameTimeStr(gameTime)        
        def status = game.Status
        def channel = game.Channel
        def progress = (status == "InProgress") ? getProgress(game) : null

        def homeTeam = state.teams[game.HomeTeam]
        if (!isLogoFound(homeTeam.logo)) {
            logDebug("Refreshing logo state for ${homeTeam.name}")
            setTeams() // refresh team state data for latest logo url
            homeTeam = state.teams[game.HomeTeam]
            if (isLogoFound(homeTeam.logo)) logDebug("Fixed logo for ${homeTeam.name}. New logo works at ${homeTeam.logo}")
            else logDebug("Logo for ${homeTeam.name} not fixed even after refresh. Logo at ${homeTeam.logo} broken too.")
        }
        
        def awayTeam = state.teams[game.AwayTeam]
        if (!isLogoFound(awayTeam.logo)) {
            logDebug("Refreshing logo state for ${awayTeam.name}")
            setTeams() // refresh team state data for latest logo url
            homeTeam = state.teams[game.AwayTeam]
            if (isLogoFound(awayTeam.logo)) logDebug("Fixed logo for ${awayTeam.name}. New logo works at ${awayTeam.logo}")
            else logDebug("Logo for ${awayTeam.name} not fixed even after refresh. Logo at ${awayTeam.logo} broken too.")
        } 
        def opponent = null
        def homeOrAway = null
        if (homeTeam.key == state.team.key) {
            opponent = awayTeam
            homeOrAway = "Home"
        }
        else if (awayTeam.key == state.team.key) {
            opponent = homeTeam
            homeOrAway = "Away"
        }
        else log.error "Team Not Playing in Game"   

        def scrambledHomeScore = game.HomeTeamScore
        def scrambledAwayScore = game.AwayTeamScore   

        def descrambledHomeScore = null
        def descrambledAwayScore = null
        if (state.calibrationData && state.calibrationData.scaleFactor != null && scrambledHomeScore != null && scrambledAwayScore != null) {
            descrambledHomeScore = Math.round(scrambledHomeScore * state.calibrationData.scaleFactor)
            descrambledAwayScore = Math.round(scrambledAwayScore * state.calibrationData.scaleFactor)
        }

        gameData = [id: gameID, gameTime: gameTime.getTime(), gameTimeStr: gameTimeStr, homeTeam: homeTeam, awayTeam: awayTeam, opponent: opponent, homeOrAway: homeOrAway, status: status, progress: progress, scrambledHomeScore: scrambledHomeScore, scrambledAwayScore: scrambledAwayScore, descrambledHomeScore: descrambledHomeScore, descrambledAwayScore: descrambledAwayScore, channel: channel]

    }
    return gameData
}

def sendPreGameNotification(myTeam, opponent, timeStr, minsLeft) {
    if (minsLeft == 0) {
        notificationDevices.deviceNotification("Gametime! ${myTeam} plays ${opponent}. ${timeStr}.")           
    }
    else {
        def hours = (minsLeft / 60).intValue()
        def hourStr = ""
        if (hours == 1) hourStr = hours + " hour"
        else if (hours > 1) hourStr = hours + " hours"
        def mins = (minsLeft % 60).intValue()
        def minStr = ""
        if (mins == 1) minStr = mins + " minute"
        else if (mins > 1) minStr = mins + " minutes"
        def timeLeft = (hourStr == "") ? minStr : hourStr + " " + minStr
        notificationDevices.deviceNotification("The ${myTeam} plays ${opponent} in ${timeLeft}. Game starts ${timeStr}.")
    }    
}

def handleFirstPreGameEvent(data) {
    pushDeviceButton(1)
    if (isFirstEventNotify == true && notificationDevices != null) {          
        sendPreGameNotification(state.team.displayName, data.opponent, data.gameTimeStr, getFirstEventAdvanceSetting())
    }
}

def handleSecondPreGameEvent(data) {
    pushDeviceButton(2)
    if (isSecondEventNotify == true && notificationDevices != null) {
        sendPreGameNotification(state.team.displayName, data.opponent, data.gameTimeStr, getSecondEventAdvanceSetting())
    }
}



def scheduleUpdate(Boolean updatingGameInProgress=false) {  
    
    // unschedule pregame events in case the next game has been cancelled since having scheduled those pregame events
    unschedule(handleFirstPreGameEvent)
    unschedule(handleSecondPreGameEvent)
    
    
    def shouldClearTeamState = true
    if (state.nextGame) {
        def nextGameTime = new Date(state.nextGame.gameTime)
        def now = new Date()
        
        if (state.nextGame.status == "Scheduled" && (nextGameTime.after(now) || nextGameTime.equals(now))) {
            if (isToday(nextGameTime)) {
                // if game starts later today, update shortly after gametime
               // only need to schedule update if game is today. If game is tomorrow, update will happen at midnight anyway
                def delayedGameTime = null
                // update game after the 10 minute delay from SportsData.IO
                use(TimeCategory ) {
                    delayedGameTime = nextGameTime + 11.minutes
                }
                runOnce(delayedGameTime, updateGameInProgress)
            }
            
            // Schedule First Pre-Game Event
            def firstEventSecsAdvance = getFirstEventAdvanceSetting()*60*-1
            Date firstEvent = adjustDateBySecs(nextGameTime, firstEventSecsAdvance)
            runOnce(firstEvent, handleFirstPreGameEvent, [data: [opponent: state.nextGame.opponent.displayName, gameTimeStr: state.nextGame.gameTimeStr]])
            
            // Schedule Second Pre-Game Event
            def secondEventSecsAdvance = getSecondEventAdvanceSetting()*60*-1
            Date secondEvent = adjustDateBySecs(nextGameTime, secondEventSecsAdvance)
            runOnce(secondEvent, handleSecondPreGameEvent, [data: [opponent: state.nextGame.opponent.displayName, gameTimeStr: state.nextGame.gameTimeStr]])

        }
        else if (state.nextGame.status == "InProgress") {
            // update in progress game no matter whether game started today or not, since late night game will progress into the next day
            shouldClearTeamState = false // don't clear team state when game is in progress
            runIn(getUpdateInterval(), updateGameInProgress) // while game is in progress, update every 10 minutes
        }
        else if (state.nextGame.status == "Delayed") {
            // update dalyed game no matter whether game started today or not, since late night game will delay into the next day
            shouldClearTeamState = false // don't clear team state when game is in progress
            runIn(1800, updateGameInProgress) // while game is delayed, update every 30 minutes
        }
        else if (state.nextGame.status == "Scheduled" && now.after(nextGameTime)) {
            // game should have already started by now, but sportsdata.io has not updated its API to reflect it yet (10 minute delay for free API). Update in 10 minutes
            logDebug("Game should have started by now, but status still indicates the game is scheduled, not in progress. This is not uncommon. Will check again in 10 minutes.")
            shouldClearTeamState = false // don't clear team state when game is in progress
            runIn(getUpdateInterval(), updateGameInProgress) // update every 10 minutes
        }  
        else if (updatingGameInProgress) {
            // game is over or cancelled. Update game state
            shouldClearTeamState = false // don't clear team state when game is in progress
            update()
        }
    }
    if (shouldClearTeamState && getClearStateSetting()) state.remove("teams") // clear teams state between updates since state is large
}

def doubleDigit(num) {
    def ret = null
    if (num != null) {
        def integer = num as Integer
        ret = String.format("%02d", integer)
    }
    return ret
}

def getProgress(game) {
    def progressStr = ""
    if (game.TimeRemainingMinutes != null && game.TimeRemainingSeconds != null && game.Period != null) {
        def timeRemaining = game.TimeRemainingMinutes + ":" + doubleDigit(game.TimeRemainingSeconds)
        if (league == "Men's College Basketball" || league == "Women's College Basketball") {
             if (game.Period == "1") progressStr = "1st " + timeRemaining
             else if (game.Period == "2") progressStr = "2nd " + timeRemaining
        }
        else if (league == "College Football") {
             if (game.Period == "1" || game.Period == "2" || game.Period == "3" || game.Period == "4") progressStr = game.Period + "Q " + timeRemaining
        
        }
        logDebug("Updating game progress. progressStr = ${progressStr}. game.Period = ${game.Period} with timeRemaining = ${timeRemaining}. Full Game is ${game}")
    }
    else {
        progressStr = "In Progress"
        logDebug("No data for game's progress. Full Game is ${game}")
    }
    return progressStr
}

def updateDisplayedGame() {
    def game = getGameToDisplay()
    def switchValue = getSwitchValue()  
    def tile = getGameTile(game)
    def scheduleTile = getScheduleTile()
    updateDevice([game: game, switchValue: switchValue, tile: tile, scheduleTile: scheduleTile])    
}

def getDateOfNextDayOfWeek(startDate, nextDayOfWeek) {
    Calendar cal = Calendar.getInstance()
    cal.setTimeZone(location.timeZone)
    cal.setTime(startDate)
    
    while (cal.get(Calendar.DAY_OF_WEEK) != nextDayOfWeek) {
        cal.add(Calendar.DAY_OF_WEEK, 1)
    }

    Date next = cal.time
    next.clearTime()
    return next
}


def getNumDaysLaterAtTime(startDate, numDaysLater, timeLater) {
    // atTimeHour Integer 0-23, atTimeMinutes Integer 0-59
    Date daysLater = new Date(startDate.getTime())
    daysLater += numDaysLater as Integer
    def timeMap = getTimeMapFromDateTime(timeLater)
    def atHour = timeMap.hour
    def atMinutes = timeMap.minutes
    def laterDate = daysLater.copyWith(hourOfDay: atHour, minute: atMinutes, seconds: 0)
    return laterDate
}

Date getDateToSwitchFromLastToNextGame() {
    if (!state.lastGame) return null
    def lastGameTime = new Date(state.lastGame.gameTime)
    def nextGameTime = null
    if (state.nextGame != null) nextGameTime = new Date(state.nextGame.gameTime)
    def now = new Date()
    Date date = null

    if (isToday(lastGameTime) && isToday(nextGameTime)) {
        // switch to next game today if next game is today too (double header)
        if (now.after(nextGameTime)) date = now // if double header is already scheduled to start, switch now
        else date = getHalfwayBetween(nextGameTime, lastGameTime)
    }
    else {
        def numDaysLater = displayCompletedGameDaysSetting()
        Date timeLater = toDateTime(displayCompletedGameTimeSetting())
        if (nextGameTime.after(timeLater)) {
            // if next game starts before it would be time to switch to display the next game
            if (now.after(nextGameTime)) date = now
            else date = getHalfwayBetween(nextGameTime, lastGameTime)
        }
        else date = getNumDaysLaterAtTime(lastGameTime, numDaysLater, timeLater)        
    }
    logDebug("Will switch to display next game at ${date}")
    return date
}

Date getHalfwayBetween(Date nextGameTime, Date lastGameTime) {
    def now = new Date()
    def switchTime = Math.round(getSecondsBetweenDates(now, nextGameTime) / 120) as Integer // switch halfway between now and the next game time
    Calendar cal = Calendar.getInstance()
    cal.setTimeZone(location.timeZone)
    cal.setTime(lastGameTime)
    cal.add(Calendar.MINUTE, switchTime)
    return cal.time    
}

def getGameToDisplay() {
    def game = null
    if (state.lastGame == null && state.nextGame != null) game = state.nextGame
    else if (state.nextGame == null && state.lastGame != null) game = state.lastGame
    else if (state.lastGame != null && state.nextGame != null) {
        if (state.nextGame.status == "InProgress" || state.nextGame.status == "Delayed") game = state.nextGame
        else {
            def now = new Date()        
            Date updateAtDate = getDateToSwitchFromLastToNextGame()
            if (updateAtDate != null && now.after(updateAtDate) || now.equals(updateAtDate)) game = state.nextGame
            else game = state.lastGame
        }    
    }
    def clearDeviceRuleSetting = getclearDeviceRuleSetting()
    def hourThreshold = getClearDeviceRuleHoursSetting()
    if ((clearDeviceRuleSetting == "inactive" && isInactive(hourThreshold)) || (clearDeviceRuleSetting == "seasonEnd" && isSeasonOver(hourThreshold))) {
        // clear device attributes at end of season after inactivity threshold
        game = null
    }
    return game
}

def getUpdatedGameData(gameToUpdate) {
    def schedule = fetchTeamSchedule()
    def updatedGameData = null
    for (game in schedule) {
        def gameID = game.GameID
        if (gameToUpdate.id == gameID) {
            updatedGameData = getGameData(game)
        }
    }
    return updatedGameData
}

def updateGameInProgress() {
    if (state.nextGame) {
        if (!state.teams) setTeams()
        def updatedGameData = getUpdatedGameData(state.nextGame)   
        if (updatedGameData != null) {
            logDebug("Updating game in progress. Progress is ${updatedGameData.progress}. Status is ${updatedGameData.status}")
            state.nextGame.progress = updatedGameData.progress
            state.nextGame.status = updatedGameData.status
            state.nextGame.scrambledAwayScore = updatedGameData.scrambledAwayScore
            state.nextGame.scrambledHomeScore = updatedGameData.scrambledHomeScore
            state.nextGame.descrambledAwayScore = updatedGameData.descrambledAwayScore
            state.nextGame.descrambledHomeScore = updatedGameData.descrambledHomeScore
        }
        updateDevice([game: state.nextGame, switchValue: getSwitchValue(), tile: getGameTile(state.nextGame), scheduleTile: getScheduleTile()])
        scheduleUpdate(true)
    }
}

def getSwitchValue() {
    def switchValue = "off"
    if (state.lastGame != null && isToday(new Date(state.lastGame.gameTime)) && state.lastGame.status != "Canceled" && state.lastGame.status != "Postponed") switchValue = "on"
    if (state.nextGame != null && isToday(new Date(state.nextGame.gameTime)) && state.nextGame.status != "Canceled" && state.nextGame.status != "Postponed") switchValue = "on"
    if (state.nextGame != null && isYesterday(new Date(state.nextGame.gameTime)) && state.nextGame.status == "InProgress") switchValue = "on" // late night game spilled into the next day
    return switchValue
}

// TO DO: show next game on tile whenever showing last game?
def getGameTile(game) {
    def gameTile = "<div style='overflow:auto;height:90%'></div>"
    def clearTileRuleSetting = getclearTileRuleSetting()
    def hourThreshold = getClearTileRuleHoursSetting()
    if (clearTileRuleSetting == null || clearTileRuleSetting == "never" || (clearTileRuleSetting == "inactive" && !isInactive(hourThreshold)) || (clearTileRuleSetting == "seasonEnd" && !isSeasonOver(hourThreshold))) {
        def textColor = getTextColorSetting()
        def fontSize = getFontSizeSetting()
        def colorStyle = ""
        if (textColor != "#000000") colorStyle = "color:" + textColor
        if (game != null) {
            def detailStr = null
            def gameFinished = (game.status == "Scheduled" || game.status == "InProgress" || game.status == "Canceled") ? false : true
            logDebug("Game Status for ${game} is ${game.status}")
            if (game.status == "InProgress") detailStr = game.progress
            else if (gameFinished) {
                if (getShowScoreSetting() && getShowGameResultSetting()) {
                    if (game.descrambledAwayScore != null && game.descrambledHomeScore != null && showGameResultMethod == "Color of Score") detailStr = null // will show game result with color of score instead of text
                    else if (game.descrambledAwayScore == null || game.descrambledHomeScore == null || showGameResultMethod == "Text on Tile") detailStr = game.status               
                }
                else detailStr = game.status
            }
            else if (game.status == "Scheduled") detailStr = game.gameTimeStr   
            else detailStr = game.status

            gameTile = "<div style='overflow:auto;height:90%;font-size:${fontSize}%;${colorStyle}'><table width='100%'>"
            gameTile += "<tr><td width='40%' align=center><img src='${game.awayTeam.logo}' width='100%'></td>"
            gameTile += "<td width='10%' align=center>at</td>"
            gameTile += "<td width='40%' align=center><img src='${game.homeTeam.logo}' width='100%'></td></tr>"
            if (parent.showTeamName) {
                gameTile += "<tr style='padding-bottom: 0em'><td width='40%' align=center>${parent.showTeamRecord && game.awayTeam.rank != null ? game.awayTeam.rank : ''} ${game.awayTeam.name}</td>"
                gameTile += "<td width='10%' align=center></td>"
                gameTile += "<td width='40%' align=center>${parent.showTeamRecord && game.homeTeam.rank != null ? game.homeTeam.rank : ''} ${game.homeTeam.name}</td></tr>" 
            }
            if (getShowScoreSetting() && game.descrambledAwayScore && game.descrambledHomeScore) {
                def awayScoreColor = null
                def homeScoreColor = null
                if (gameFinished && getShowScoreSetting() && getShowGameResultSetting() && showGameResultMethod == "Color of Score") {
                    if (game.homeOrAway == "Away") {
                        if (game.status == "Won" || (game.descrambledAwayScore > game.descrambledHomeScore)) awayScoreColor = "#059936" // green
                        else if (game.status == "Lost" || (game.descrambledAwayScore < game.descrambledHomeScore)) awayScoreColor = "#C33414" // red
                    }
                    else if (game.homeOrAway == "Home") {
                        if (game.status == "Lost" || (game.descrambledAwayScore > game.descrambledHomeScore)) homeScoreColor = "#C33414" // red
                        else if (game.status == "Won" || (game.descrambledAwayScore < game.descrambledHomeScore)) homeScoreColor = "#059936" // green
                    }
                }
                gameTile += "<tr style='padding-bottom: 0em'><td width='40%' align=center" + (awayScoreColor ? " bgcolor='${awayScoreColor}'" : "") +  ">${game.descrambledAwayScore}</td>"
                gameTile += "<td width='10%' align=center></td>"
                gameTile += "<td width='40%' align=center" + (homeScoreColor ? " bgcolor='${homeScoreColor}'" : "") +  ">${game.descrambledHomeScore}</td></tr>" 
            }
            if (parent.showTeamRecord && getShowGameResultSetting()) {
                gameTile += "<tr><td width='40%' align=center style='font-size:${fontSize*0.75}%;'>${'(' + game.awayTeam.wins + '-' + game.awayTeam.losses + ')'}</td>"
                gameTile += "<td width='10%' align=center></td>"
                gameTile += "<td width='40%' align=center style='font-size:${fontSize*0.75}%;'>${'(' + game.homeTeam.wins + '-' + game.homeTeam.losses + ')'}</td></tr>"  
            }
            if (detailStr) gameTile += "<tr style='padding-bottom: 0em'><td width='100%' align=center colspan=3>${detailStr}</td></tr>"
            if (parent.showChannel && game.channel != "null" && game.channel != null && !gameFinished) gameTile += "<tr><td width='100%' align=center colspan=3 style='font-size:${fontSize*0.75}%;'>${game.channel}</td></tr>"
            gameTile += "</table></div>"  
        }
    }
    return gameTile
}

Boolean isInactive(inactiveThreshold) {
    def isInactive = false
    Date now = new Date()
    Date inactiveDateTime = null
    Date activeDateTime = null
    if (state.lastGame != null && inactiveThreshold != null) {
        def lastGameTime = new Date(state.lastGame.gameTime)
        Calendar cal = Calendar.getInstance()
        cal.setTimeZone(location.timeZone)
        cal.setTime(lastGameTime)
        cal.add(Calendar.HOUR, inactiveThreshold as Integer)
        inactiveDateTime = cal.time
      //  logDebug("Inactivity Post-Game scheduled to start ${inactiveDateTime}")        
    }
    if (state.nextGame != null && inactiveThreshold != null) {
        def nextGameTime = new Date(state.nextGame.gameTime)
        Calendar cal = Calendar.getInstance()
        cal.setTimeZone(location.timeZone)
        cal.setTime(nextGameTime)
        cal.add(Calendar.HOUR, (inactiveThreshold * -1 as Integer))
        activeDateTime = cal.time
      //  logDebug("Inactivity Pre-Game scheduled to stop ${activeDateTime}")
        
    }   
    if (inactiveDateTime != null && activeDateTime != null) {
        if (now.after(inactiveDateTime) && now.before(activeDateTime)) isInactive = true
    }
    else if (inactiveDateTime == null && activeDateTime != null) {
        if (now.before(activeDateTime)) isInactive = true
    }
    else if (inactiveDateTime != null && activeDateTime == null) {
        if (now.after(inactiveDateTime)) isInactive = true
    }
    if (isInactive) logDebug("No game within the past ${inactiveThreshold} hour(s) and within the next ${inactiveThreshold} hour(s). ${getclearTileRuleSetting() == "inactive" ? "Hiding tile." : ""} ${getclearDeviceRuleSetting() == "inactive" ? "Hiding device attributes." : ""}")
   return isInactive
}

def isSeasonOver(hourThreshold) {
    def endOfSeason = false
    if (state.lastGame != null && state.nextGame == null) {
        // set endOfSeason = true if has been at least hourThreshold hours after the last game
        if (hourThreshold != null) {
            def lastGameTime = new Date(state.lastGame.gameTime)
            Calendar cal = Calendar.getInstance()
            cal.setTimeZone(location.timeZone)
            cal.setTime(lastGameTime)
            cal.add(Calendar.HOUR, hourThreshold as Integer)
            Date inactiveDateTime = cal.time    
            Date now = new Date()
            if (now.after(inactiveDateTime)) endOfSeason = true
        }
        else endOfSeason = true
    }
    return endOfSeason
}

def getTeam(teamKey) {
    def returnTeam = null
    state.teams.each { key, tm ->
        if(teamKey == key) {
            returnTeam = tm
        }
    }
    returnTeam
}

def updateDevice(data) {
    parent.updateChildDevice(app.id, data)
}

def pushDeviceButton(buttonNum) {
    parent.pushDeviceButton(app.id, buttonNum)
}

def getConferenceOptions() {
    def conferences = []
    state.teams.each { key, tm ->
        if (tm.conference != null) conferences.add(tm.conference)
    }
    def uniqueConferences = conferences.unique()
    return uniqueConferences
}

def getTeamOptions() {
    def conferenceTeams = []
    state.teams.each { key, tm ->
        if (settings["conference"] == tm.conference) {
            conferenceTeams.add(tm.school + " " + tm.name)
        }
    }
    return conferenceTeams
}

def setMyTeam() {
    state.teams.each { key, tm ->
        def name = tm.school + " " + tm.name
        if(settings["team"] == name) {
            state.team = tm
        }
    }
}

def getMyTeamName() {
    return state.team?.school + " " + state.team?.name
}

def fetchTeams() {
    return sendApiRequest("/scores/json/teams")
}

def fetchTeamSchedule() {
    def schedule = null
    def season = getCurrentSeason()
    if (state.team && season) {
        if (league == "Men's College Basketball" || league == "Women's College Basketball") {
             schedule = sendApiRequest("/scores/json/TeamSchedule/" + season + "/" + getTeamKey())
        }
        else if (league == "College Football") {
            def leagueSchedule = sendApiRequest("/scores/json/Games/" + season)                                                                                                                                                                                                                                                                            
            def teamSchedule = []
            if (leagueSchedule == "Error: first byte timeout") {
                return leagueSchedule
            }
            else if (leagueSchedule != null) {
                for (game in leagueSchedule) {
                    if (game.AwayTeam == state.team.key || game.HomeTeam == state.team.key) {
                        teamSchedule.add(game)
                    }
                }
            }
            schedule = teamSchedule
        }
    }
    return schedule
}

def getCurrentSeason() {
    def season = null
    def seasonDetails = sendApiRequest("/scores/json/CurrentSeason")   
    if (seasonDetails) {
        if (league == "Men's College Basketball" || league == "Women's College Basketball") season = seasonDetails.ApiSeason
        else season = seasonDetails
    }
    else logDebug("No season found.")
    return season
}

def createChild()
{
    def name = state.team?.school
    if (league == "Men's College Basketball") name += " Men's Basketball"
    else if (league == "Women's College Basketball") name += " Women's Basketball"
    else if (league == "College Football") name += " Football"
    
    def lowPriorityThreshold = getLowPriorityThresholdSetting()
    def isLowPriority = getLowPrioritySetting()
    
    parent.createChildDevice(app.id, name, isLowPriority, lowPriorityThreshold)
}

def deleteChild()
{
    parent.deleteChildDevice(app.id)
}

def sendApiRequest(path)
{
    def params = [
		uri: "https://api.sportsdata.io/",
        path: "v3/" + api[league] + path,
		contentType: "application/json",
		query: [
                key: getAPIKey(),
            ],
		timeout: 1000
	]

    if (body != null)
        params.body = body

    def result = null
    logDebug("Api Call: ${params}")
    parent.countAPICall(league)
    try
    {
        httpGet(params) { resp ->
        result = resp.data
        }                
    }
    catch (Exception e)
    {
        log.warn "sendApiRequest() failed: ${e.message}"
        return null
    }   
    return result
}
            
def getSecondsBetweenDates(Date startDate, Date endDate) {
    try {
        def difference = endDate.getTime() - startDate.getTime()
        return Math.round(difference/1000)
    } catch (ex) {
        log.error "getSecondsBetweenDates Exception: ${ex}"
        return 1000
    }
}

def adjustDateBySecs(Date date, Integer secs) {
    Calendar cal = Calendar.getInstance()
    cal.setTimeZone(location.timeZone)
    cal.setTime(date)
    cal.add(Calendar.SECOND, secs)
    Date newDate = cal.getTime()
    return newDate
}

def getTimeMapFromDateTime(dateTime) {
    Calendar cal = Calendar.getInstance()
    cal.setTimeZone(location.timeZone)
    cal.setTime(dateTime)
    def hour = cal.get(Calendar.HOUR_OF_DAY)
    def minutes = cal.get(Calendar.MINUTE)
    return [hour: hour, minutes: minutes]
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
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

