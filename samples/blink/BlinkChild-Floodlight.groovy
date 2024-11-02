/*
* BlinkChild-Floodlight
*
* Description:
* This Hubitat driver provides a spot to put data from Blink floodlights (Storm devices)
*
* Instructions for using Tile Template method (originally based on @mircolino's HTML Templates):
* 1) In "Hubitat -> Devices" select the child/sensor (not the parent) you would like to "templetize"
* 2) In "Preferences -> Tile Template" enter your template (example below) and click "Save Preferences"
*   Ex: "[font size='2'][b]Battery:[/b] ${ state.battery }[/br][/font]"
* 3) In a Hubitat dashboard, add a new tile, and select the child/sensor, in the center select "Attribute", and on the right select the "Tile" attribute
* 4) Select the Add Tile button and the tile should appear
* NOTE: Should accept most HTML formatting commands with [] instead of <>
* 
* Features List:
* Ability to display battery, and more...
* Ability to check a website (mine) to notify user if there is a newer version of the driver available
* Ability to control the on/off capability of the floodlight (although on is impacted by how long the floodlight is set to stay on for also)
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
* 0.1.11 - Correction to remove Driver Status when preferences are saved
* 0.1.10 - Correction to ProcessEvent function and removal of old driver-specific attributes when Preferences are saved
* 0.1.9 - Correction for floodlight off typo
* 0.1.8 - Removal of all on/off trial code so it can be placed in the parent driver for publishing
* 0.1.7 - Correction to auth token retrieval
* 0.1.6 - Inclusion of AccountID for on/off parameters
* 0.1.5 - Correction for typo
* 0.1.4 - Further attempt at on/off capability
* 0.1.3 - Next attempt at on/off capability for light to work
* 0.1.2 - Correction of Params testing
* 0.1.1 - Attempts at getting on/off capability for light to work
* 0.1.0 - Initial version
* 
* Thank you(s):
* @chad.andrews for providing floodlight data and being very patient with getting it working
* @Cobra for inspiration of how I perform driver version checking
* @mircolino for Template method for dashboard use
* https://github.com/MattTW/BlinkMonitorProtocol for providing the basis for much of what is possible with the API
*/

// Returns the driver name
def DriverName(){
    return "BlinkChild-Floodlight"
}

// Returns the driver version
def DriverVersion(){
    return "0.1.11"
}

// Driver Metadata
metadata{
	definition( name: "BlinkChild-Floodlight", namespace: "Snell", author: "David Snell", importUrl: "https://www.drdsnell.com/projects/hubitat/drivers/BlinkChild-Floodlight.groovy" ) {
        capability "Sensor"
        capability "Battery"
        capability "Actuator"
        capability "Switch"
        
        // Commands
        command "on", [ [ name: "This will turn on floodlight" ] ] // 
        command "off", [ [ name: "This will turn off floodlight" ] ] // 
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
        attribute "Battery Signal", "number" // 
        attribute "Armed", "string" // 
        attribute "Arm String", "string" // 
        
        // Attributes
        attribute "Type", "string" //
        attribute "Enabled", "string" // 
        attribute "Status", "string" // 
        attribute "Battery Status", "string" // 
        attribute "Battery Number", "number" // 
        attribute "Battery Check Time", "string" // 
        attribute "Battery Voltage", "number" // 
        attribute "Battery Alert Account", "string" // 
        attribute "Battery Alert Count", "number" // 
        attribute "Battery Voltage Hysteresis", "number" // 
        attribute "Battery Voltage Threshold", "number" // 
        attribute "Battery Alarm Enable", "string" // 
        attribute "Battery Alert Status", "string" // 
        attribute "Battery Voltage Interval", "number" // 
        attribute "Last Battery Alert", "string" // 
        attribute "AC Power", "string" // 
        attribute "Usage Rate", "string" // 
        attribute "Issues", "string" // 
        attribute "LFR Strength", "number" // 
        attribute "Usage", "number" // 
        attribute "Max Resolution", "string" // 
        attribute "Account", "number" // 
        attribute "Alert Repeat", "string" // 
        attribute "LFR Alert Count", "number" // 
        attribute "Alert Tone Enable", "string" // 
        attribute "Alert Interval", "number" // 
        attribute "MFG Main Type", "string" // 
        attribute "MFG Main Range", "number" // 
        attribute "Retry Count", "number" // 
        attribute "Early Termination", "string" // 
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
        attribute "FW Git Hash", "string" // 
        attribute "MAC Address", "string" // 
        attribute "Time DHCP Lease", "number" // 
        attribute "Time First Video", "number" // 
        attribute "Time DNS Resolve", "number" // 
        attribute "Time 108 Boot", "number" // 
        attribute "Lifetime Duration", "number" // 
        attribute "WiFi Connect Failure Count", "number" // 
        attribute "DHCP Failure Count", "number" // 
        attribute "IPv", "string" //
        attribute "Dev 1", "number" // 
        attribute "Dev 2", "number" // 
        attribute "Dev 3", "number" // 
        attribute "Time WLAN Connect", "number" // 
        attribute "Socket Failure Count", "number" // 
        attribute "Lifetime Count", "number" // 
        attribute "Error Codes", "number" // 
        attribute "Ring Device ID", "string" // 
        attribute "Local Connection Certificate ID", "string" // 
        attribute "First Boot", "string" // 

        // Tile Template attribute
        attribute "Tile", "string"; // Ex: "[b]Battery:[/b] ${ state.battery }[/br]"
        attribute "DriverName", "string" // Identifies the driver being used for update purposes
		attribute "DriverVersion", "string" // Version number of the driver itself
        attribute "DriverStatus", "string" // Attribute to provide notice to user of driver version changes
        
    }
	preferences{
		//section{
            if( ShowAllPreferences ){
                input( name: "TileTemplate", type: "string", title: "<b>Tile Template</b>", description: "<font size='2'>Ex: [b]Battery:[/b] \${ state.battery }[/br]</font>", defaultValue: "");
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

// on command with this device
def on(){
    parent.FloodlightOn( parent.getChildDevice( "Camera-${ state.'Target ID' }" ).ReturnState( "Network ID" ), state.'Target ID', state.ID )
}

// off command with this device
def off(){
    parent.FloodlightOff( parent.getChildDevice( "Camera-${ state.'Target ID' }" ).ReturnState( "Network ID" ), state.'Target ID', state.ID )
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
        log.info( " ${ device.displayName } - ${ LogMessage }" )
    } else if( ( LogLevel == 3 ) && ( ( LogType == "Debug" ) || ( LogType == "Trace" ) ) ){
        log.debug( " ${ device.displayName } - ${ LogMessage }" )
    } else if( ( LogLevel == 4 ) && ( LogType == "Trace" ) ){
        log.trace( " ${ device.displayName } - ${ LogMessage }" )
    } else if( LogLevel == 5 ){
        log.error( " ${ device.displayName } - ${ LogMessage }" )
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