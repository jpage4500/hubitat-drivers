/*
* BlinkChild-Doorbell
*
* Description:
* This Hubitat driver provides a spot to put data from Blink Doorbells
*
* Instructions for using Tile Template method (originally based on @mircolino's HTML Templates):
* 1) In "Hubitat -> Devices" select the child/sensor (not the parent) you would like to "templetize"
* 2) In "Preferences -> Tile Template" enter your template (example below) and click "Save Preferences"
*   Ex: "[font size='2'][b]Temperature:[/b] ${ temperature }°${ location.getTemperatureScale() }[/br][/font]"
* 3) In a Hubitat dashboard, add a new tile, and select the child/sensor, in the center select "Attribute", and on the right select the "Tile" attribute
* 4) Select the Add Tile button and the tile should appear
* NOTE: Should accept most HTML formatting commands with [] instead of <>
* 
* Features List:
* Ability to trigger a LiveView
* Ability to Arm/Disarm the system the Doorbell is on
* Ability to display various device data
* Ability to check a website (mine) to notify user if there is a newer version of the driver available
* 
* KNOWN ISSUE(S):
* Doorbells have a set of limitations. It is not known if these are device or API limitations:
*   Unable to run Enable or Disable Motion Detection commands
*   Unable to run the GetCameraInfo command
*   Unable to run the GetCameraSensors command
*   Unable to run the GetCameraStatus command
*
* Licensing:
* Copyright 2024 David Snell
* Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
* in compliance with the License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
* for the specific language governing permissions and limitations under the License.
*
* Version Control:
* 0.1.7 - Correction to remove Driver Status when preferences are saved
* 0.1.6 - Correction to ProcessEvent function and removal of old driver-specific attributes when Preferences are saved
* 0.1.5 - Attempts to capture a new video clip for camera
* 0.1.3 - Added ImageCapture capability and thumbnail
* 0.1.2 - Added battery capability
* 0.1.1 - Update to driver version checking method and attributes
* 0.1.0 - Initial version
* 
* Thank you(s):
* @Cobra for inspiration of how I perform driver version checking
* @mircolino for Template method for dashboard use
* https://github.com/MattTW/BlinkMonitorProtocol for providing the basis for much of what is possible with the API
*/

// Returns the driver name
def DriverName(){
    return "BlinkChild-Doorbell"
}

// Returns the driver version
def DriverVersion(){
    return "0.1.7"
}
// Driver Metadata
metadata{
	definition( name: "BlinkChild-Doorbell", namespace: "Snell", author: "David Snell", importUrl: "https://www.drdsnell.com/projects/hubitat/drivers/BlinkChild-Doorbell.groovy" ) {
        capability "Sensor"
        capability "Actuator"
        capability "Switch"
        capability "MotionSensor"
        capability "Battery"
        capability "ImageCapture"
        
        // Commands
        command "on", [ [ name: "This will Arm system Doorbell is part of" ] ]  // 
        command "off", [ [ name: "This will Disarm system Doorbell is part of" ] ]  // 
        command "SystemArm", [ [ name: "This will Arm system Doorbell is part of" ] ]  // 
        command "SystemDisarm", [ [ name: "This will Disarm system Doorbell is part of" ] ]  // 
        command "GetNewVideo", [ [ name: "This will attempt to capture a new video clip for Camera" ] ] // 
        command "GetCameraLiveView", [ [ name: "This will start a LiveView stream for Camera" ] ] // 
        //command "GetCameraInfo", [ [ name: "This will get general info for Camera" ] ] // 
        command "GetThumbnail", [ [ name: "This will capture a new thumbnail for Camera" ] ] // 
        //command "DoSomething" // For testing and development purposes only, it should not be uncommented for normal use
        
        // Attributes - Common
        attribute "ID", "number" // 
        attribute "Network ID", "number" // 
        attribute "Created", "string" // 
        attribute "Updated", "string" // 
        attribute "Deleted", "string" //
        attribute "Status", "string" // 
        attribute "Name", "string" // 
        attribute "Serial", "string" // 
        attribute "Firmware Version", "string" // 
        attribute "WiFi Strength", "number" // 
        attribute "Local Storage Compatible", "string" // 
        attribute "Local Storage Enabled", "string" // 
        attribute "Local Storage Status", "string" // 
        attribute "Join State", "number" // 
        attribute "Options", "map" // 
        attribute "Continue Warning", "number" // 
        attribute "Submit Logs", "string" //
        attribute "Join Available", "string" //
        attribute "New Command", "string" //
        attribute "Account ID", "number" // 
        attribute "Last Command ID", "number" // 
        attribute "Feature Plan ID", "string" // 
        attribute "WiFi Signal", "number" // 
        attribute "LFR Signal", "number" // 
        attribute "Armed", "string" // 
        attribute "Arm String", "string" // 
        
        // Attributes - Camera Specific
        attribute "Camera ID", "number" // 
        attribute "Media ID", "string" // 
        attribute "Type", "string" //
        attribute "Enabled", "string" // 
        attribute "Thumbnail", "string" // 
        attribute "Thumbnail File", "string" // 
        attribute "Status", "string" // 
        attribute "Usage Rate", "string" // 
        attribute "Issues", "string" // 
        attribute "LFR Strength", "number" // 
        attribute "Usage", "number" // 
        attribute "LiveView Enabled", "string" // 
        attribute "LiveView Seconds", "number" // 
        attribute "Liveview Bitrate", "number" // 
        attribute "LiveView RTSP", "string" // 
        attribute "LiveView Duration", "number" // 
        attribute "LiveView Continue Interval", "number" // 
        attribute "LiveView Rate", "number" // 
        attribute "Clip Seconds", "number" // 
        attribute "Motion Sensitivity", "number" // 
        attribute "Max Resolution", "string" // 
        attribute "Account", "number" // 
        attribute "Motion Detection Enabled", "string" // 
        attribute "Alert Repeat", "string" // 
        attribute "LFR Alert Count", "number" // 
        attribute "Video Recording Optional", "string" // 
        attribute "Alert Tone Enable", "string" // 
        attribute "Clip Bitrate", "number" // 
        attribute "Privacy Zones Compatible", "string" // 
        attribute "WiFi Timeout", "number" // 
        attribute "Last Snapshot Event", "string" // 
        attribute "Illuminator Intensity", "number" // 
        attribute "Illuminator Duration", "number" // 
        attribute "Illuminator Enable", "number" // 
        attribute "Alert Interval", "number" // 
        attribute "Clip Warning Threshold", "number" // 
        attribute "Clip Rate", "number" // 
        attribute "Clip Max Length", "number" // 
        attribute "Snapshot Enabled", "string" // 
        attribute "Snapshot Compatible", "string" //
        attribute "Motion Regions Compatible", "string" // 
        attribute "Motion Regions", "number" // 
        attribute "Early Notification Compatible", "string" // 
        attribute "Early Notification", "string" // 
        attribute "Invert Image", "string" // 
        attribute "Video Quality", "string" // 
        attribute "Video Length", "number" // 
        attribute "Video hz", "number" // 
        attribute "Video Recording Enable", "string" // 
        attribute "Flip Video", "string" // 
        attribute "Flip Video Compatible", "string" // 
        attribute "MFG Main Type", "string" // 
        attribute "MFG Main Range", "number" // 
        attribute "Retry Count", "number" // 
        attribute "Record Audio Enable", "string" // 
        attribute "Record Audio", "string" // 
        attribute "Auto Test", "string" // 
        attribute "Early Termination", "string" // 
        attribute "Flip Image", "string" // 
        attribute "Siren Enable", "string" // 
        attribute "Siren Volume", "number" // 
        attribute "Camera Key", "string" // 
        attribute "Camera Seq", "number" // 
        attribute "Night Vision Exposure Compatible", "string" // 
        attribute "Buzzer On", "string" // 
        attribute "Early PIR Compatible", "string" // 
        attribute "Alert Tone Volume", "number" // 
        attribute "Night Vision Exposure", "number" // 
        attribute "Snapshot Period Minutes Options", "list" // 
        attribute "Last LFR Alert", "string" // 
        attribute "LFR Sync Interval", "number" // 
        attribute "Unit Number", "number" // 
        attribute "MFG Mez Range", "number" // 
        attribute "MFG Mez Type", "string" // 
        attribute "Early Termination Supported", "string" // 
        attribute "Total 108 Wakeups", "number" // 
        attribute "LFR TB Wakeups", "number" // 
        attribute "Total TB Wakeups", "number" // 
        attribute "LFR 108 Wakeups", "number" // 
        attribute "Light Sensor Data Valid", "string" // 
        attribute "FW Git Hash", "string" // 
        attribute "Light Sensor CH0", "number" // 
        attribute "Light Sensor CH1", "number" // 
        attribute "MAC Address", "string" // 
        attribute "Time DHCP Lease", "number" // 
        attribute "Time First Video", "number" // 
        attribute "Time DNS Resolve", "number" // 
        attribute "Time 108 Boot", "number" // 
        attribute "Lifetime Duration", "number" // 
        attribute "WiFi Connect Failure Count", "number" // 
        attribute "DHCP Failure Count", "number" // 
        attribute "IPv", "string" //
        attribute "Light Sensor Data New", "string" // 
        attribute "Dev 1", "number" // 
        attribute "Dev 2", "number" // 
        attribute "Dev 3", "number" // 
        attribute "Time WLAN Connect", "number" // 
        attribute "Socket Failure Count", "number" // 
        attribute "PIR Rejections", "number" // 
        attribute "Lifetime Count", "number" // 
        attribute "Error Codes", "number" // 
        attribute "Snapshot Period Minutes", "number" // 
        attribute "Thumbnail Image", "image" // 
        attribute "Ring Device ID", "string" // 
        attribute "Local Connection Certificate ID", "string" // 
        attribute "First Boot", "string" // 
        attribute "Trigger Source", "string" // Source of the video trigger
        attribute "Trigger ID", "number" // ID number of the video trigger
        attribute "Trigger Time", "string" // Date/Time of the video trigger

        // Tile Template attribute
        attribute "Tile", "string"; // Ex: "[font size='2'][b]Temperature:[/b] ${ temperature }°${ location.getTemperatureScale() }[/br][/font]"
        attribute "DriverName", "string" // Identifies the driver being used for update purposes
		attribute "DriverVersion", "string" // Version number of the driver itself
        attribute "DriverStatus", "string" // Attribute to provide notice to user of driver version changes
        
    }
	preferences{
		//section{
            if( ShowAllPreferences ){
                input( name: "TileTemplate", type: "string", title: "<b>Tile Template</b>", description: "<font size='2'>Ex: [b]Temperature:[/b] \${ state.temperature }&deg;${ location.getTemperatureScale() }[/br]</font>", defaultValue: "");
    			input( type: "enum", name: "LogType", title: "<b>Enable Logging?</b>", required: false, multiple: false, options: [ "None", "Info", "Debug", "Trace" ], defaultValue: "Info" )
                input( type: "bool", name: "ShowAllPreferences", title: "<b>Show All Preferences?</b>", defaultValue: true )
            } else {
                input( type: "bool", name: "ShowAllPreferences", title: "<b>Show All Preferences?</b>", defaultValue: true )
            }
        //}
	}
}

// updated
def updated(){
    if( LogType == null ){
        LogType = "Info"
    }

    // Set the driver name and version before update checking is scheduled
    if( state."Driver Status" != null ){
        state.remove( "Driver Name" )
        state.remove( "Driver Version" )
        state.remove( "Driver Status" )
        device.deleteCurrentState( "Driver Status" )
        device.deleteCurrentState( "Driver Name" )
        device.deleteCurrentState( "Driver Version" )
    }
    ProcessEvent( "DriverName", DriverName() )
    ProcessEvent( "DriverStatus", null )
    ProcessEvent( "DriverVersion", DriverVersion() )
    
    // Schedule the daily driver version check
	schedule( new Date(), CheckForUpdate )
    Logging( "Updated", 2 )
}

// DoSomething is for testing and development purposes. It should not be uncommented for normal usage.
def DoSomething(){

}

// Call the parent device's GetCameraLiveView with this device's ID
def GetCameraLiveView(){
    def Temp = device.getDeviceNetworkId()
    Temp = Temp.split( "-" )
    Logging( "GetCameraLiveView for ${ device.getDeviceNetworkId() }.", 4 )
    parent.GetCameraLiveView( Temp[ 1 ] )
}

// Call the parent device's GetNewVideo with this device's ID
def GetNewVideo(){
    def Temp = device.getDeviceNetworkId()
    Temp = Temp.split( "-" )
    Logging( "GetNewVideo on Camera ${ Temp[ 1 ] }.", 4 )
    parent.GetNewVideo( Temp[ 1 ] )
}

// Take is just part of the imageCapture capability
def take(){
    GetThumbnail()
}

// Call the parent device's GetThumbnail with this device's ID
def GetThumbnail(){
    def Temp = device.getDeviceNetworkId()
    Temp = Temp.split( "-" )
    Logging( "Getting thumbnail on Camera ${ Temp[ 1 ] }.", 4 )
    parent.GetThumbnail( Temp[ 1 ] )
}

// Call the ArmSystem
def SystemArm(){
    ArmSystem()
}

// Call the DisarmSystem
def SystemDisarm(){
    DisarmSystem()
}

// Call the parent device's ArmSystem with this device's Network ID
def ArmSystem(){
    if( state."Network ID" != null ){
        parent.ArmSystem( state."Network ID" )
    } else {
        Logging( "Device does not know the Network ID to arm", 5 )
    }
}

// Call the parent device's DisarmSystem with this device's ID
def DisarmSystem(){
    if( state."Network ID" != null ){
        parent.DisarmSystem( state."Network ID" )
    } else {
        Logging( "Device does not know the Network ID to disarm", 5 )
    }
}

// on will trigger an ArmSystem command with this device
def on(){
    ArmSystem()
}

// off will trigger an DisarmSystem command with this device
def off(){
    DisarmSystem()
}

// installed is called when the device is installed, all it really does is run updated
def installed(){
	Logging( "Installed", 2 )
	updated()
}

// initialize is called when the device is initialized, all it really does is run updated
def initialize(){
	Logging( "Initialized", 2 )
	updated()
}

// Tile Template method based on @mircolino's HTML Template method
private void UpdateTile( String val ){
    if( settings.TileTemplate ){
        // Create special compound/html tile
        val = settings.TileTemplate.toString().replaceAll( "\\[", "<" )
        val = val.replaceAll( "\\]", ">" )
        val = val.replaceAll( ~/\$\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}/ ) { java.util.ArrayList m -> device.currentValue("${ m [ 1 ] }").toString() }
        if( device.currentValue( "Tile" ).toString() != val ){
            sendEvent( name: "Tile", value: val )
        }
    }
}

// Process data to check against current state value and then send an event if it has changed
def SetDeviceType( Type ){
    DeviceType = Type
    Logging( "DeviceType = ${ DeviceType }", 4 )
}

// Return a state value
def ReturnState( Variable ){
    return state."${ Variable }"
}

// Process data to check against current state value and then send an event if it has changed
def ProcessEvent( Variable, Value, Unit = null ){
    if( state."${ Variable }" != Value ){
        state."${ Variable }" = Value
        if( Unit != null ){
            Logging( "Event: ${ Variable } = ${ Value }${ Unit }", 4 )
            sendEvent( name: "${ Variable }", value: Value, unit: Unit, isStateChange: true )
        } else {
            Logging( "Event: ${ Variable } = ${ Value }", 4 )
            sendEvent( name: "${ Variable }", value: Value, isStateChange: true )
        }
        UpdateTile( "${ Value }" )
    }
}

// Process data to check against current state value and update if it has changed
def ProcessState( Variable, Value ){
    if( state."${ Variable }" != Value ){
        Logging( "State: ${ Variable } = ${ Value }", 4 )
        state."${ Variable }" = Value
        UpdateTile( "${ Value }" )
    }
}

// Handles whether logging is enabled and thus what to put there.
def Logging( LogMessage, LogLevel ){
	// Add all messages as info logging
    if( ( LogLevel == 2 ) && ( LogType != "None" ) ){
        log.info( "${ device.displayName } - ${ LogMessage }" )
    } else if( ( LogLevel == 3 ) && ( ( LogType == "Debug" ) || ( LogType == "Trace" ) ) ){
        log.debug( "${ device.displayName } - ${ LogMessage }" )
    } else if( ( LogLevel == 4 ) && ( LogType == "Trace" ) ){
        log.trace( "${ device.displayName } - ${ LogMessage }" )
    } else if( LogLevel == 5 ){
        log.error( "${ device.displayName } - ${ LogMessage }" )
    }
}

// Checks drdsnell.com for the latest version of the driver
// Original inspiration from @cobra's version checking
def CheckForUpdate(){
    ProcessEvent( "DriverName", DriverName() )
    ProcessEvent( "DriverVersion", DriverVersion() )
	httpGet( uri: "https://www.drdsnell.com/projects/hubitat/drivers/versions.json", contentType: "application/json" ){ resp ->
        switch( resp.status ){
            case 200:
                if( resp.data."${ DriverName() }" ){
                    CurrentVersion = DriverVersion().split( /\./ )
                    if( resp.data."${ DriverName() }".version == "REPLACED" ){
                       ProcessEvent( "DriverStatus", "Driver replaced, please use ${ resp.data."${ state.DriverName }".file }" )
                    } else if( resp.data."${ DriverName() }".version == "REMOVED" ){
                       ProcessEvent( "DriverStatus", "Driver removed and no longer supported." )
                    } else {
                        SiteVersion = resp.data."${ DriverName() }".version.split( /\./ )
                        if( CurrentVersion == SiteVersion ){
                            Logging( "Driver version up to date", 3 )
				            ProcessEvent( "DriverStatus", "Up to date" )
                        } else if( ( CurrentVersion[ 0 ] as int ) > ( SiteVersion [ 0 ] as int ) ){
                            Logging( "Major development ${ CurrentVersion[ 0 ] }.${ CurrentVersion[ 1 ] }.${ CurrentVersion[ 2 ] } version", 3 )
				            ProcessEvent( "DriverStatus", "Major development ${ CurrentVersion[ 0 ] }.${ CurrentVersion[ 1 ] }.${ CurrentVersion[ 2 ] } version" )
                        } else if( ( CurrentVersion[ 1 ] as int ) > ( SiteVersion [ 1 ] as int ) ){
                            Logging( "Minor development ${ CurrentVersion[ 0 ] }.${ CurrentVersion[ 1 ] }.${ CurrentVersion[ 2 ] } version", 3 )
				            ProcessEvent( "DriverStatus", "Minor development ${ CurrentVersion[ 0 ] }.${ CurrentVersion[ 1 ] }.${ CurrentVersion[ 2 ] } version" )
                        } else if( ( CurrentVersion[ 2 ] as int ) > ( SiteVersion [ 2 ] as int ) ){
                            Logging( "Patch development ${ CurrentVersion[ 0 ] }.${ CurrentVersion[ 1 ] }.${ CurrentVersion[ 2 ] } version", 3 )
				            ProcessEvent( "DriverStatus", "Patch development ${ CurrentVersion[ 0 ] }.${ CurrentVersion[ 1 ] }.${ CurrentVersion[ 2 ] } version" )
                        } else if( ( SiteVersion[ 0 ] as int ) > ( CurrentVersion[ 0 ] as int ) ){
                            Logging( "New major release ${ SiteVersion[ 0 ] }.${ SiteVersion[ 1 ] }.${ SiteVersion[ 2 ] } available", 2 )
				            ProcessEvent( "DriverStatus", "New major release ${ SiteVersion[ 0 ] }.${ SiteVersion[ 1 ] }.${ SiteVersion[ 2 ] } available" )
                        } else if( ( SiteVersion[ 1 ] as int ) > ( CurrentVersion[ 1 ] as int ) ){
                            Logging( "New minor release ${ SiteVersion[ 0 ] }.${ SiteVersion[ 1 ] }.${ SiteVersion[ 2 ] } available", 2 )
				            ProcessEvent( "DriverStatus", "New minor release ${ SiteVersion[ 0 ] }.${ SiteVersion[ 1 ] }.${ SiteVersion[ 2 ] } available" )
                        } else if( ( SiteVersion[ 2 ] as int ) > ( CurrentVersion[ 2 ] as int ) ){
                            Logging( "New patch ${ SiteVersion[ 0 ] }.${ SiteVersion[ 1 ] }.${ SiteVersion[ 2 ] } available", 2 )
				            ProcessEvent( "DriverStatus", "New patch ${ SiteVersion[ 0 ] }.${ SiteVersion[ 1 ] }.${ SiteVersion[ 2 ] } available" )
                        }
                    }
                } else {
                    Logging( "${ DriverName() } is not published on drdsnell.com", 2 )
                    ProcessEvent( "DriverStatus", "${ DriverName() } is not published on drdsnell.com" )
                }
                break
            default:
                Logging( "Unable to check drdsnell.com for ${ DriverName() } driver updates.", 2 )
                break
        }
    }
}