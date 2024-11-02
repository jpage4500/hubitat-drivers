/**
 *  GameTime
 *
 *  Copyright\u00A9 2021 Justin Leonard
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
 *  Change History in Parent App
**/

metadata
{
    definition(name: "GameTime", namespace: "lnjustin", author: "Justin Leonard", importUrl: "")
    {
        capability "Actuator"
        capability "Switch"
        
        attribute "tile", "string" 
        attribute "schedule", "string" 
        
        attribute "gameTime", "string"
        attribute "gameTimeStr", "string"
        attribute "status", "string"                    
        attribute "opponent", "string"  
        attribute "homeOrAway", "string"
        
        command(
             "setTileTextColor", 
             [
                [
                     "name":"Set Tile Text Color",
                     "description":"Set the color of the text on your tile(s). Hex format with leading #).",
                     "type":"text"
                ]
             ]
        )
    }
}

preferences
{
    section
    {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def logDebug(msg) 
{
    if (logEnable)
    {
        log.debug(msg)
    }
}    

def updated()
{
    configure()
}

def uninstalled()
{
    logDebug("GameTime Parent Device: uninstalled()")
    deleteChildren()
}

def parse(String description)
{
    logDebug(description)
}

def configure()
{    
    refresh()
}

def setTileTextColor(color) {
    parent?.settingUpdate("textColor", color, "string") 
}

def on() {
    sendEvent(name: "switch", value: "on")
}

def off() {
    sendEvent(name: "switch", value: "off")
}

def updateChildDevice(appID, data)
{
    def child = getChildDevice("GameTimeChildDevice${appID}")
    if (child) {
        child.updateDevice(appID, data)
        runIn(5, "updateParentDevice")
    }
    else log.error "No Child Device for app ${appID} found"
}

def pushChildDeviceButton(appID, buttonNum) {
    def child = getChildDevice("GameTimeChildDevice${appID}")
    if (child) {
        child.pushButton(buttonNum)
    }
    else log.error "No Child Device for app ${appID} found"    
}

def anyHighPriorityGameNear(lowPriorityChild, children) {
    def anyNear = false
    def thresholdInSecs = lowPriorityChild.getThreshold() * 3600
    for (child in children) {
        if (child != lowPriorityChild && !child.isLowPriority()) {
            def childGameTime = child.currentValue("gameTime")
            def lowPriorityGameTime = lowPriorityChild.currentValue("gameTime")
            if (childGameTime != "No Game Data" && childGameTime != "No Game Scheduled" && lowPriorityGameTime != "No Game Data" && lowPriorityGameTime != "No Game Scheduled") {
                Date lowPriorityGameTimeDate = new Date(Long.valueOf(lowPriorityGameTime))
                Date childGameTimeDate = new Date(Long.valueOf(childGameTime))   
                def secsDiff = getSecondsBetweenDates(lowPriorityGameTimeDate, childGameTimeDate)
                if (secsDiff == 0) anyNear = true
                else if (secsDiff > 0 && secsDiff < thresholdInSecs) anyNear = true
                else if (secsDiff < 0 && secsDiff > -1*thresholdInSecs) anyNear = true
            }
        }
    }
    return anyNear
}

def updateParentDevice() {
    def nextGameChild = null
    def lastGameChild = null
    Date now = new Date()
    def children = getChildDevices()
    def filteredChildren = []
    for(child in children)
    {
        if (child.isLowPriority() && !anyHighPriorityGameNear(child, children)) filteredChildren.add(child)  // only add if no high priority game near in time to this low priority child
        else if (!child.isLowPriority()) filteredChildren.add(child)
    }
    for(child in filteredChildren)
    {    
        def childGameTime = child.currentValue("gameTime")
        if (childGameTime != null && childGameTime != "No Game Scheduled" && childGameTime != "No Game Data") {
            def gameTimeObj = new Date(Long.valueOf(childGameTime))
            def childStatus = child.currentValue("status")
            if (gameTimeObj.after(now) || gameTimeObj.equals(now)  || childStatus == "Scheduled" || childStatus == "InProgress"  || childStatus == "Delayed") {         
                if (nextGameChild == null) {
                    nextGameChild = child
                }
                else {                
                    def nextGameTimeObj = new Date(Long.valueOf(nextGameChild.currentValue("gameTime")))                
                    def nextGameChildStatus = nextGameChild.currentValue("status")
                    if (childStatus == "InProgress") {
                        if (nextGameChildStatus != "InProgress") nextGameChild = child
                         else if (nextGameChildStatus == "InProgress" && nextGameTimeObj.after(gameTimeObj)) nextGameChild = child    // display whichever game started earlier
                     }
                     else {
                         if (nextGameChildStatus != "InProgress" && getSecondsBetweenDates(now, gameTimeObj) < getSecondsBetweenDates(now, nextGameTimeObj)) {
                               nextGameChild = child
                         }    
                     }
                }
            }
            else {
                // handle finished game
                if (lastGameChild == null) lastGameChild = child
                else {
                     def lastChildGameTime = new Date(Long.valueOf(lastGameChild.currentValue("gameTime"))) 
                     if (getSecondsBetweenDates(gameTimeObj, now) < getSecondsBetweenDates(lastChildGameTime, now)) {
                          lastGameChild = child
                     }
                }            
            }
        }
    }
    
    def childToDisplay = null
    if (lastGameChild == null && nextGameChild != null) childToDisplay = nextGameChild
    else if (nextGameChild == null && lastGameChild != null) childToDisplay = lastGameChild
    else if (lastGameChild != null && nextGameChild != null) {
        def lastChildGameTime = new Date(Long.valueOf(lastGameChild.currentValue("gameTime"))) 
        def nextChildGameTime = new Date(Long.valueOf(nextGameChild.currentValue("gameTime")))  
        
        def switchTime = Math.round(getSecondsBetweenDates(lastChildGameTime, nextChildGameTime) / 120) as Integer // switch halfway between
        Calendar cal = Calendar.getInstance()
        cal.setTimeZone(location.timeZone)
        cal.setTime(lastChildGameTime)
        cal.add(Calendar.MINUTE, switchTime)
        def switchDate = cal.time
        if (nextGameChild.currentValue("status") == "InProgress" || nextGameChild.currentValue("status") == "Delayed") childToDisplay = nextGameChild
        else if (now.after(switchDate) || now.equals(switchDate)) childToDisplay = nextGameChild
        else childToDisplay = lastGameChild
    }
    if (childToDisplay) copyChild(childToDisplay)
    else clearParent()
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

def clearParent() {
    sendEvent(name: "gameTime", value: "No Game Scheduled")
    sendEvent(name: "gameTimeStr", value: "No Game Scheduled")
    sendEvent(name: "tile", value: "<div style='overflow:auto;height:90%'></div>")
    sendEvent(name: "schedule", value: "<div style='overflow:auto;height:90%'></div>")
    sendEvent(name: "status", value: "No Game Scheduled")
    sendEvent(name: "opponent", value: "No Game Scheduled")
    sendEvent(name: "homeOrAway", value: "No Game Scheduled")
    sendEvent(name: "switch", value: "off")    
}

def copyChild(child) {
    sendEvent(name: "gameTime", value: child.currentValue("gameTime"))
    sendEvent(name: "gameTimeStr", value: child.currentValue("gameTimeStr"))
    sendEvent(name: "tile", value: child.currentValue("tile"))
    sendEvent(name: "schedule", value: child.currentValue("schedule"))
    sendEvent(name: "status", value: child.currentValue("status"))
    sendEvent(name: "opponent", value: child.currentValue("opponent"))
    sendEvent(name: "homeOrAway", value: child.currentValue("homeOrAway"))
    sendEvent(name: "switch", value: child.currentValue("switch"))    
}

def createChild(appID, name, isLowPriority, lowPriorityThreshold)
{
    def child = getChildDevice("GameTimeChildDevice${appID}")    
    if (!child) {
        String childNetworkID = "GameTimeChildDevice${appID}"
        def newChild = addChildDevice("lnjustin", "GameTime Child", childNetworkID, [label:name, isComponent:true, name:name])
        newChild.configurePriority(isLowPriority, lowPriorityThreshold)
    }
    else {
        child.setLabel(name)
        child.setName(name)
        child.configurePriority(isLowPriority, lowPriorityThreshold)
    }
}

def deleteChild(appID)
{
    deleteChildDevice("GameTimeChildDevice${appID}")
}

def deleteChildren()
{
    for(child in getChildDevices())
    {
        deleteChildDevice(child.deviceNetworkId)
    }
}

def refresh()
{

}

