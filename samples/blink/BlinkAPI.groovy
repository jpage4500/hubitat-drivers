/*
* BlinkAPI
*
* Description:
* This Hubitat driver allows polling of the Blink API.
*
* Initial Setup:
* 1) Create a new virtual device for Blink.
* 2) Select this driver for the Device Type then Save Device.
* 3) Enter your Blink account email and password and Save Preferences.
* 4) Select the "Authorize" command with a false value. Blink should email or text a PIN to validate this device.
* 5) Enter the PIN received in the Verify PIN field and select the Verify PIN command.
* NOTE: If you previously did Authorize and Verify PIN, you should only use Authorize with the true value unless you
*  want to reset your ClientID.
* NOTE2: If your system starts failing with an error (maybe 406) and nothing else seems to work, Blink might be blocking
*  based on the User-Agent. Check for a newer version of the driver (I will start changing this at times) or change the
*  User-Agent manually using the driver Preferences.
*
* Features List:
* Can request a PIN be emailed to your account from Blink
* Can validate PIN received from Blink
* Can check Blink account for any "networks" of devices and pull basic information
* Can check Sync Modules, Cameras, and more for basic information including temperature and battery state
* Can arm/disarm systems individually or all for the account
* Can enable/disable motion detection on cameras individually or all for the account
* Can set custom Labels for child devices or let them be set by names received from the API
* Works with MiniCameras (also known as Owls or Hawks), Wired Floodlights (Superior Owls) and Doorbells which have additional limitations, see KNOWN ISSUES
* Works with Floodlights (Storm accessories)
* Rosie (Mini pan-tilt mount) devices have no control so this is left as a generic child
*
* KNOWN ISSUE(S):
* MiniCameras (Owls) and Floodlights (Superior Owls) have a set of limitations. It is not known if these are device or API limitations:
*   Unable to run Enable or Disable Motion Detection commands
*   Unable to run the GetCameraInfo command
*   Unable to run the GetCameraSensors command
*   Unable to run the GetCameraStatus command
* Doorbells have a set of limitations. It is not known if these are device or API limitations:
*   Unable to run Enable or Disable Motion Detection commands
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
* 0.5.22 - Correction to remove Driver Status when preferences are saved
* 0.5.21 - Added recognition of new "hawk" type of owl
* 0.5.20 - Correction to ProcessEvent function
* 0.5.19 - Handling for additional data points and removing the old driver specific variables and events
* 0.5.18 - Created a "User-Agent" Preference and set a fairly recent mobile browser if not populated and changed attribute names
*  "Driver Name" to "DriverName", "Driver Version" to "DriverVersion" and "Driver Status" to DriverStatus" to avoid some visual errors
*  on device page
* 0.5.17 - "Updated" attribute for child devices is now just for thumbnail/image updates
* 0.5.16 - More triggers for the "Updated" attribute for parent and children
* 0.5.15 - Added Doorbells to video event processing
* 0.5.14 - Attempts to allow getting new video clips from/for child devices
* 0.5.13 - Adding thumbnail capture and upload capability (finally)
* 0.5.12 - Moved some Info and Debug logging to Trace
* 0.5.11 - Attempt to cleanup Armed and Armed System(s) data and reduce event calls
* 0.5.10 - Additional attempts for getting Wired Floodlight commands to work
* 0.5.9 - Correction to Wired Floodlight child add section and another attempt at controlling the lights
* 0.5.8 - Correction to Wired Floodlight and Mini Camera identification
* 0.5.7 - Added support for Wired Floodlights, which are a variant of the owl (Mini Camera) devices
* 0.5.6 - Addition of User-Agent to headers so drivers work with API again, thanks to @tomw for that idea
* 0.5.5 - Minor change to some error logging
* 0.5.4 - Added additional attributes returned, fixed GetCameraLiveView error
* 0.5.3 - Changes to enable support for floodlights and corrections to GetNewThumbnail/Arm & Disarm System for Doorbell devices
* 0.5.2 - Minor changes to GetCameraInfo command
* 0.5.1 - Update to driver version checking method and attributes
* 0.5.0 - Separation of child driver into multiple children based on device type, newer method of version checking,
*  correction to Arm String data for cameras, and a couple additional data points handled
* 0.4.4 - Fix for error in battery section and addition of new data handling
* 0.4.3 - Minor addition to support doorbells in data returned as well as their new battery state
* 0.4.2 - Added an unschedule command to remove Authorize from the schedule when preferences are saved. This was replaced by
*  Reauthorize but appears to have been left around on some setups.
* 0.4.1 - Added an 8 hour option for authorization frequency, deprecated Verified, Authorization, and Authorization Failures
*  state variables, cleaned up MiniCamera references for response data, cleared out AuthenticationMethod remnants
* 0.4.0 - Adding motion event detection based on video events query and media data reported there, added a Preference called
*  Motion Duration to handle how long after a video event has occurred before it is no longer considered relevant for motion
* 0.3.1 - Made some changes to parts of authorization yet again, corrected the way state values were being set in most
*  places, added Reauthorization Frequency preference for when (or not) reauthorization occurs automatically, corrected some
*  checks for null values, made it so cameras removed from account will not be checked (but child devices will remain until removed)
* 0.3.0 - Add reauth for Authorize again, reauth happens every 12 hours again, change to PIN verification,
*  changed ClientID to match new style for Blink, will require users to Authorize again (Authorize with reauth = false)
*  to run the ResetClientID to get the new style
* 0.2.15 - Changed regular authorizaton to be every 18 hours
* 0.2.14 - Removed reauth flag from Authorization and removed Device Type (again)
* 0.2.13 - Changed authorization URL, changed camera thumbnail URL, removed GetSyncModuleEvents command, & handled new data fields
* 0.2.12 - Changed how scheduling is handled when saving preferences and on PIN verification
* 0.2.11 - Added additional data fields now returned by the API
* 0.2.10 - Replacement of all boolean attributes, as boolean attributes are not valid
* 0.2.9 - Additional error checking for null children
* 0.2.8 - Error check to prevent null values being compared when cameras have no Network ID yet AND allowing custom labels
* 0.2.7 - Fixed error where a "fake" device Network-Armed=false (or Network-Armed=true) might get created. This device can be removed.
* 0.2.6 - Made sure that Arm String is also updated when Armed is changed (although it could get overwritten by API data)
* 0.2.5 - Moved last_connect data from CameraInfo to an "ignored" set as it is old data that causes trouble with current data
* 0.2.4 - Fixes for temperature conversion
* 0.2.3 - Added on/off for child switches (Motion for Cameras and MiniCameras and Armed for Networks) from response
*   altered way Armed System(s) were being populated during arming, and added switches for arm/disarm to children
* 0.2.2 - Fix for Armed System(s) getting an error with removeElement after a GetHomescreen (refresh)
* 0.2.1 - Fix for Armed status being set in app not being propagated to children on refresh in driver
* 0.2.0 - Major overhaul to try to include owls (MiniCameras) in most aspects, hiding password,
*   added 1 second pauses when doing multiple requests in a row, handling odd http 202 response for arm/disarm
* 0.1.23 - Minor cleanup of logging for GetCameraStatus and another attempt at retrieving owl data
* 0.1.22 - Added GetCameraStatus (simpler than GetCameraInfo) & fix for invalid camera ID being submitted for camera info
* 0.1.21 - Fix for cameras will no thumbnail causing error while processing
* 0.1.20 - PIN no longer saved as a Preference but submitted as a value when the Verify PIN command is used
* 0.1.19 - Returns switch on/off commands when system Armed/Disarmed for Network child(ren) and motion Enabled/Disabled for Camera child(ren)
* 0.1.18 - Changed authorization schedule so it is every 12 hours based on Save Preferences, not specifically noon/midnight
* 0.1.17 - Removed Hubitat from client name during initial authorization and added an optional Preference if people want to set their own
* 0.1.16 - Corrected value for reporting Armed state to parent, process Network data from Homescreen, changed Authorize schedule
* 0.1.15 - Renamed "RequestPIN" to "Authorize", removed reauthorization, switching to authorizing every 12 hours, no longer hides email/pass/PIN
* 0.1.14 - No longer asks for a PIN if device is Verified, rework reauth AGAIN to try to conquer excessive attempts
* 0.1.13 - Tweak to GeneralResponse for data passed to it, extra error checking for some commands, added estimated battery % sent to children
* 0.1.12 - Puts refresh and daily checks on hold if Verified state is not true, added ListSchedules command
* 0.1.11 - Second fix for ProcessDevices
* 0.1.10 - Fixed error in ProcessDevices preventing most devices from processing, added arm/disarm status to cameras
* 0.1.9 - ID now posted to all child devices, minor changes to ProcessDevices list, improved Updated for arm/disarm and enable/disable
* 0.1.8 - Changes to GeneralResponse for Update, added CheckSyncModule & GetCameraLiveView commands, and handled more values from CameraInfo
* 0.1.7 - Corrected logging in Video Events, revision to general response handling, and Network Status data
* 0.1.6 - Changed ClientID generation, added state for which systems are armed and cameras enabled for motion
* 0.1.5 - Corrected some instances where there was a state NetworkID AND Network ID, both meaning the same thing
* 0.1.4 - Corrected video event logging, changed addChild, and added more (but nowhere near all) of the camera info variables
* 0.1.3 - Added actuator capability so device shows in Rules to send commands, updating status after arm/disarm and enable/disable commands
* 0.1.2 - Added support for Arm/Disarm System, Enable/Disable Motion Detection, and System/Network/Camera settings
* 0.1.1 - PIN Validation working
* 0.1.0 - Initial version
* 
* Thank you(s):
* @Cobra for inspiration on driver version checking.
* @jpage4500, @djgutheinz, @tomw, @M6TTL, @nutcracker, and others (sorry if I missed you) for samples and inspiration to get the driver working
* @tomw for idea to add User-Agent to headers so driver works again
* @user2371 for letting me know about Hubitat adding the uploadHubFile command so images can now be captured
* https://github.com/MattTW/BlinkMonitorProtocol for providing the basis for much of what is possible with the API
*/

// Returns the driver name
def DriverName(){
    return "BlinkAPI"
}

// Returns the driver version
def DriverVersion(){
    return "0.5.22"
}

// Driver Metadata
metadata{
	definition( name: "BlinkAPI", namespace: "Snell", author: "David Snell", importUrl: "https://www.drdsnell.com/projects/hubitat/drivers/BlinkAPI.groovy" ) {
		// Indicate what capabilities the device should be capable of
		capability "Sensor"
		capability "Refresh"
        //capability "Battery" // Child devices, not the parent, have this
        //capability "TemperatureMeasurement" // Child devices, not the parent, have this
        capability "Actuator"
        

        //command "DoSomething" // Just for testing and clearing out mistakes
        command "Authorize", [ [ name: "Reauthorize*", type: "ENUM", defaultValue: "true", constraints: [ "true", "false" ], description: "REQUIRED: If this is a reauthorization or not, almost always true." ] ] // ( /api/v4/account/login )
        //command "ResetClientID"
        //command "OldResetClientID"
        command "VerifyPIN", [ // ( /api/v4/account/${ AccountID }/client/${ ClientID }/pin/verify )
            [ name: "PIN*", type: "STRING", description: "REQUIRED: Enter PIN provided by Blink" ]
        ]
        command "GetHomescreen" // ( /api/v3/accounts/${ AccountID }/homescreen )
        command "GetNotifications" // Provides a list of all notification settings ( /api/v1/accounts/${ AccountID }/notifications/configuration )
        command "GetVideoEvents" // (no sample video clips yet) ( /api/v1/accounts/${ AccountID }/media/changed?since=${ VideoAsOf }&page=1 )
        command "GetNetworks" // ( /networks )
        command "GetCameraUsage" // ( /api/v1/camera/usage )
        command "EnableMotionDetection", [
            [ name: "CameraID", type: "NUMBER", description: "Enter a CameraID to enable motion detection (blank enables all)" ]
        ] // Enables motion detection on camera(s) but does not arm system ( /network/${ NetworkID }/camera/${ CameraID }/enable )
        command "DisableMotionDetection", [
            [ name: "CameraID", type: "NUMBER", description: "Enter a CameraID to disable motion detection (blank disables all)" ]
        ] // Disables motion detection on camera(s) but does not disarm system  ( /network/${ NetworkID }/camera/${ CameraID }/disable )
        command "ArmSystem", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to arm (blank arms all)" ]
        ] // ( /api/v1/accounts/${ AccountID }/networks/${ NetworkID }/state/arm )
        command "DisarmSystem", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to disarm (blank disarms all)" ]
        ] // ( /api/v1/accounts/${ AccountID }/networks/${ NetworkID }/state/disarm )
        command "GetCameraInfo", [
            [ name: "CameraID", type: "NUMBER", description: "Enter a CameraID to get info on (blank checks all)" ],
        ] // ( /network/${ NetworkID }/camera/${ CameraID }/config )
        command "GetCameraStatus", [
            [ name: "CameraID", type: "NUMBER", description: "Enter a CameraID to get status of (blank checks all)" ]
        ] // ( /network/${ NetworkID }/camera/${ CameraID } )
        command "GetNetworkStatus", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to check (blank checks all)" ]
        ] // ( /network/${ NetworkID } )
        command "CheckSyncModule", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to check (blank checks all)" ]
        ] // ( /network/${ NetworkID }/syncmodules )
        /*
        //No longer seems to work, says it requires upgrade
        command "GetEvents", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to check (blank checks all)" ]
        ] // ( /events/network/${ NetworkID } )
        */
        command "GetCameraLiveView", [
            [ name: "CameraID", type: "NUMBER", description: "Enter a CameraID to get LiveView data (blank gets all)" ]
        ] // Returns RSTP "url" that dashboard cannot display( /api/v5/accounts/${ AccountID }/networks/${ NetworkID }/cameras/${ CameraID }/liveview
        command "GetCameraSensors", [
            [ name: "CameraID", type: "NUMBER", description: "Enter a CameraID to get sensor data (blank gets all)" ]
        ] // ( /network/${ NetworkID }/camera/${ CameraID }/signals )
        command "ListSchedules", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to get schedule from (blank checks all networks)" ]
        ] // ( /api/v1/networks/{NetworkID}/programs )
        /*
        command "GetNewThumbnail", [
            [ name: "CameraID", type: "NUMBER", description: "Enter a CameraID to get a new thumbnail for (blank gets all)" ]
        ] // ( /network/${ NetworkID }/camera/${ CameraID }/thumbnail )
        */
        command "GetThumbnail", [
            [ name: "CameraID", type: "NUMBER", description: "Enter a CameraID to get thumbnail from (blank enables all)" ]
        ] // Started, but need to figure out how to handle the data received since it is a jpg ( /media/production/account/${ state.AccountID }/network/${ NetworkID }/camera/${ CameraID }/${ ThumbnailFile.jpg } )
        
        /*
        command "Network Update", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to get data on cameras (blank checks all networks)" ]
        ] // ( /network/${ NetworkID }/update )
        */
        
        command "GetNewVideo", [
            [ name: "CameraID", type: "NUMBER", description: "Enter a CameraID to get new video from (blank gets all)" ]
        ] // ( /network/${ NetworkID }/camera/${ CameraID }/clip )
        
        //command "CommandStatus" // ( /network/${ NetworkID }/command/${ CommandID } )
        //command "GetUserInfo" // Started, but does not appear to contain relevant information ( /user )
        //command "GetVideoCount" // ( /api/v2/videos/count ) (replaced by GetVideoEvents)
        /*
        command "GetCameras", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to get data on cameras (blank checks all networks)" ]
        ] // Says it needs upgrade, may be truly deprecated ( /network/${ NetworkID }/cameras )
        */
        /*
        command "EnableSchedule", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to enable schedule on", required: true ],
            [ name: "Schedule #", type: "NUMBER", description: "Enter schedule number to enable", required: true ]
        ] // ( /api/v1/networks/{NetworkID}/programs/{ProgramID}/enable )
        */
        /*
        command "DisableSchedule", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to disable schedule on", required: true ],
            [ name: "Schedule #", type: "NUMBER", description: "Enter schedule number to disable", required: true ]
        ] // ( /api/v1/networks/{NetworkID}/programs/{ProgramID}/disable )
        */
        /*
        command "UpdateSchedule", [
            [ name: "NetworkID", type: "NUMBER", description: "Enter a NetworkID to update schedule on", required: true ],
            [ name: "Schedule #", type: "NUMBER", description: "Enter schedule number to update", required: true ]
        ] // Unlikely to implement due to complexity of making the schedule correctly ( /api/v1/networks/{NetworkID}/programs/{ProgramID}/update )
        */
        
		// Attributes for the driver itself
		attribute "DriverName", "string" // Identifies the driver being used for update purposes
		attribute "DriverVersion", "string" // Version number of the driver itself
        attribute "DriverStatus", "string" // Attribute to provide notice to user of driver version changes
        attribute "ClientID", "string" // Unique identifier generated for the parent device to access Blink
        attribute "Updated", "string" // When the parent device last updated, on children it represents when a thumbnail/image updated.
        attribute "Armed System(s)", "list" // List of armed systems
        attribute "Camera(s) With Motion Enabled", "list" // List of cameras with motion detection enabled
    }
	preferences{
		section{
            if( ShowAllPreferences || ShowAllPreferences == null ){ // Show the preferences options
                input( type: "string", name: "Email", title: "<font color='FF0000'><b>Account Email</b></font>", required: true )
                input( type: "password", name: "Password", title: "<font color='FF0000'><b>Account Password</b></font>", required: true )
                input( type: "string", name: "ClientName", title: "<b>Client Name</b>", description: "(Optional) The name the device will report back to Blink during authorization", required: false )
                input( type: "string", name: "UserAgent", title: "<b>User-Agent</b>", description: "User-Agent the device will report back to Blink, uses built-in default if blank", required: false )
                input( type: "enum", name: "RefreshRate", title: "<b>Refresh Rate</b>", required: false, multiple: false, options: [ "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour", "3 hours", "Manual" ], defaultValue: "1 hour" )
    			input( type: "enum", name: "LogType", title: "<b>Enable Logging?</b>", required: true, multiple: false, options: [ "None", "Info", "Debug", "Trace" ], defaultValue: "Info" )
                input( type: "bool", name: "CustomLabels", title: "<b>Use Custom Labels for Children</b>", defaultValue: false )
                input( type: "enum", name: "MotionDuration", title: "<b>Motion Detection Duration</b>", desciption: "(Optional) How long after a video event occurs that it could be considered for triggering motion", required: false, multiple: false, options: [ "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour" ], defaultValue: "15 minutes" )
                input( type: "enum", name: "ReauthorizationRate", title: "<font color='FF0000'><b>Reauthorization Frequency</b></font>", required: true, multiple: false, options: [ "8 hours", "12 hours", "18 hours", "24 hours", "Manual" ], defaultValue: "12 hours" )
                input( type: "bool", name: "ShowAllPreferences", title: "<b>Show All Preferences?</b>", defaultValue: true )
            } else {
                input( type: "bool", name: "ShowAllPreferences", title: "<b>Show All Preferences?</b>", defaultValue: true )
            }
        }
	}
}

// Just a command to be put fixes or other oddities during development
def DoSomething(){

}

// Command to return a user agent value instead of having it hardcoded throughout
def UserAgentValue(){
    if( UserAgent == null ){
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.193 Mobile Safari/537.36"
    } else {
        return "${ UserAgent }"    
    }
}

def ProcessSomething( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Something successful: ${ resp.data }", 2 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// updated is called whenever device parameters are saved
def updated(){
    // Setting the Client ID if it is null
    if( state.ClientID == null ){
        ResetClientID()
    }
    
    // Get times to use for scheduling
    def Hour = ( new Date().format( "h" ) as int )
    def Minute = ( new Date().format( "m" ) as int )
    def Second = ( new Date().format( "s" ) as int )
    Second = ( ( Second + 5 ) % 60 )
    
    // Schedule the authorization check for every X hours
    unschedule( "Authorize" )
    if( ReauthorizationRate == null ){
        ReauthorizationRate = "12 hours"
    }
    switch( ReauthorizationRate ){
        case "8 hours":
            if( Hour == 12 ){
                schedule( "${ Second } ${ Minute } 0/8 ? * *", "Reauthorize" )
            } else {
                schedule( "${ Second } ${ Minute } ${ Hour }/8 ? * *", "Reauthorize" )
            }
            break
        case "12 hours":
            if( Hour == 12 ){
                schedule( "${ Second } ${ Minute } 0/12 ? * *", "Reauthorize" )
            } else {
                schedule( "${ Second } ${ Minute } ${ Hour }/12 ? * *", "Reauthorize" )
            }
            break
        case "18 hours":
            schedule( "${ Second } ${ Minute } ${ Hour }/18 ? * *", "Reauthorize" )
            break
        case "24 hours":
            schedule( "${ Second } ${ Minute } ${ Hour } ? * *", "Reauthorize" )
            break
        case "Manual":
            unschedule( "Reauthorize" )
            break
    }
    Second = ( ( Second + 5 ) % 60 )
    // Check what the refresh rate is set for then run it
    if( RefreshRate == null ){
        RefreshRate = "Manual"
    }
    switch( RefreshRate ){
        case "5 minutes":
            // Schedule the refresh check for every 5 minutes
            schedule( "${ Second } ${ Minute }/5 * ? * *", "refresh" )
            //runEvery5Minutes( refresh )
            break
        case "10 minutes":
            // Schedule the refresh check for every 10 minutes
            schedule( "${ Second } ${ Minute }/10 * ? * *", "refresh" )
            //runEvery10Minutes( refresh )
            break
        case "15 minutes":
            // Schedule the refresh check for every 15 minutes
            schedule( "${ Second } ${ Minute }/15 * ? * *", "refresh" )
            //runEvery15Minutes( refresh )
            break
        case "30 minutes":
            // Schedule the refresh check for every 30 minutes
            schedule( "${ Second } ${ Minute }/30 * ? * *", "refresh" )
            //runEvery30Minutes( refresh )
            break
        case "1 hour":
            // Schedule the refresh check for every hour
            schedule( "${ Second } ${ Minute } * ? * *", "refresh" )
            //runEvery1Hour( refresh )
            break
        case "3 hours":
            // Schedule the refresh check for every 3 hours
            schedule( "${ Second } ${ Minute } ${ Hour }/3 ? * *", "refresh" )
            //runEvery3Hours( refresh )
            break
        case "Manual":
            unschedule( "refresh" )
            break
    }
    Logging( "Refresh rate: ${ RefreshRate }", 4 )
        
    // Schedule the daily check of the overall system
    Minute = ( ( Minute + 5 ) % 60 )
    schedule( "${ Second } ${ Minute } ${ Hour } ? * *", "DailyCheck" )
    //schedule( new Date(), DailyCheck )
    Minute = ( ( Minute + 5 ) % 60 )
    
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
    schedule( "${ Second } ${ Minute } ${ Hour } ? * *", "CheckForUpdate" )
	//schedule( new Date(), CheckForUpdate )

    // If no state.Tier is known, default to prod
    if( state.Tier == null ){
        ProcessState( "Tier", "prod" )
    }
    
    Logging( "Saved Preferences", 2 )
}

// Reset ClientID used for generating a new client ID that will be used from that point on
// ClientID format is "00000000-0000-0000-0000-000000000000"
def OldResetClientID(){
    if( state.ClientID != null ){
        if( state.ClientID.startsWith( "BlinkCamera" ) ){
            ProcessState( "NewClientID", state.ClientID )
        }
    }
    if( state.OldClientID != null ){
        ProcessState( "ClientID", state.OldClientID )
    } else {
        def Random1 = Math.random()
        Random1 = ( Math.abs( new Random().nextInt() % 89999999 ) + 10000000 )
        def Random2 = Math.random()
        Random2 = ( Math.abs( new Random().nextInt() % 8999 ) + 1000 )
        def Random3 = Math.random()
        Random3 = ( Math.abs( new Random().nextInt() % 8999 ) + 1000 )
        def Random4 = Math.random()
        Random4 = ( Math.abs( new Random().nextInt() % 8999 ) + 1000 )
        def Random5 = Math.random()
        Random5 = ( Math.abs( new Random().nextInt() % 8999 ) + 1000 )
        def Random6 = Math.random()
        Random6= ( Math.abs( new Random().nextInt() % 89999999 ) + 10000000 )
        state.ClientID = "${ Random1 }-${ Random2 }-${ Random3 }-${ Random4 }-${ Random5 }${ Random6 }"
    }
    Logging( "ClientID = ${ state.ClientID }", 4 )
}

// Reset ClientID used for generating a new client ID that will be used from that point on
// ClientID format now is "BlinkCamera_0000-00-00-00-000000"
def ResetClientID(){
    if( state.ClientID != null ){
        if( !state.ClientID.startsWith( "BlinkCamera" ) ){
            ProcessState( "OldClientID", state.ClientID )
        }
    }
    if( state.NewClientID != null ){
        ProcessState( "ClientID", state.NewClientID )
    } else {
        def Random1 = Math.random()
        Random1 = ( Math.abs( new Random().nextInt() % 8999 ) + 1000 )
        def Random2 = Math.random()
        Random2 = ( Math.abs( new Random().nextInt() % 89 ) + 10 )
        def Random3 = Math.random()
        Random3 = ( Math.abs( new Random().nextInt() % 89 ) + 10 )
        def Random4 = Math.random()
        Random4 = ( Math.abs( new Random().nextInt() % 89 ) + 10 )
        def Random5 = Math.random()
        Random5= ( Math.abs( new Random().nextInt() % 899999 ) + 100000 )
        state.ClientID = "BlinkCamera_${ Random1 }-${ Random2 }-${ Random3 }-${ Random4 }-${ Random5 }"
    }
    Logging( "ClientID = ${ state.ClientID }", 4 )
}

// refresh performs a poll of data
def refresh(){  
    if( LogType == null ){
        LogType = "Info"
    }
    // Checking, but refresh really does not do anything yet
    if( Email != null && Password != null ){
        Logging( "Has Email and Password", 4 )
        if( state.ClientID != null ){
            Logging( "Has ClientID", 4 )
            if( state.AuthToken != null ){
                Logging( "Has AuthToken, trying GetHomescreen.", 4 )
                GetHomescreen()
                GetVideoEvents()
            } else {
                Logging( "No Authorization Token. Please Authorize and (if required) Verify PIN.", 5 )
            }
        } else {
            Logging( "Generating new ClientID.", 2 )
            ResetClientID()
        }
    } else {
        Logging( "Email and/or Password are required.", 5 )
    }
}

// Things that should not change often and are only checked once a day, such as new networks or cameras
def DailyCheck(){
    if( state.AuthToken != null ){
        Logging( "Performing DailyCheck", 4 )
        GetNetworks()
        GetCameraUsage()
        GetNotifications()
        GetCameraInfo()
        GetNetworkStatus()
    } else {
        Logging( "No Authorization Token. Please Authorize and (if required) Verify PIN.", 5 )
    }
}

// Reauthorize is for scheduled authorizations
def Reauthorize(){
    Authorize( "true" )
}

// Attempts to authorize a user, possibly reauthorize if needed although this is now in another function
def Authorize( String Reauth = "false" ){
    Logging( "Authorizing...", 4 )
    def Params
    if( state.ClientID == null ){
        ResetClientID()
    }
    if( state.Tier == null ){
        ProcessState( "Tier", "prod" )
    }
    if( Reauth == "true" ){
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v5/account/login", contentType: "application/json", body: "{\"unique_id\": \"${ state.ClientID }\",\"password\":\"${ Password }\",\"email\":\"${ Email }\",\"client_name\":\"${ ClientName }\",\"reauth\":true,\"client_id\":\"${ state.Client }\"}", headers: [ 'User-Agent'  : "${ UserAgentValue() }" ] ]        
    } else {
        ResetClientID()
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v5/account/login", contentType: "application/json", body: "{\"unique_id\": \"${ state.ClientID }\",\"password\":\"${ Password }\",\"email\":\"${ Email }\",\"client_name\":\"${ ClientName }\"}", headers: [ 'User-Agent'  : "${ UserAgentValue() }" ] ]
    }
    Logging( "Authorization Params: ${ Params }", 4 )
    asynchttpPost( "ProcessAuthorization", Params, [ "Reauth" : Reauth ] )
}

// Handle the response from an authorization request
def ProcessAuthorization( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Authorization successful: ${ resp.data }", 4 )
            Logging( "Authorization headers: ${ resp.getHeaders() }", 4 )
            ResponseData = parseJson( resp.data )
            HeaderData = resp.getHeaders()
            if( HeaderData."Client-Verified" == "1" ){
                Logging( "Reuthorization Successful", 4 )
                ProcessEvent( "Status", "Reauthorization successful, client verified." )
            } else {
                Logging( "Response successful, but still needs to verify the client.", 4 )
                ProcessEvent( "Status", "Still requires PIN verification." )
            }
            if( ResponseData.account.account_id != null ){
                ProcessState( "AccountID", ResponseData.account.account_id )
            }
            if( ResponseData.account.client_id != null ){
                ProcessState( "Client", ResponseData.account.client_id )
            }
            if( ResponseData.auth.token != null ){
                ProcessState( "AuthToken", ResponseData.auth.token )
            }
            if( ResponseData.account.tier != null ){
                ProcessState( "Tier", ResponseData.account.tier )
            }
            break
        case 400:
            Logging( "Bad Request response during authorization", 5 )
            break
        case 401:
            Logging( "Unauthorized response during authorization", 5 )
            break
        case 408:
            Logging( "Request timed out response during authorization", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink during authorization", 5 )
            break
    }
}

// Attempts to verify the PIN emailed by Blink
def VerifyPIN( PIN ){
    if( PIN != null ){
        if( state.Tier == null ){
            ProcessState( "Tier", "prod" )
        }
        Logging( "Trying to verify PIN.", 4 )
        def Params
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v4/account/${ state.AccountID }/client/${ state.Client }/pin/verify", contentType: "application/json", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent': "${ UserAgentValue() }" ], body: "{\"pin\":\"${ PIN }\"}" ]
        Logging( "PIN Verification Params: ${ Params }", 4 )
        asynchttpPost( "ProcessVerification", Params )
    } else {
        Logging( "PIN is REQUIRED to run the Verify PIN command.", 5 )
        ProcessEvent( "Status", "ERROR: PIN required to run Verify PIN command." )
    }
}

// Handle the response from PIN verification
def ProcessVerification( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Pin Verification Successful: ${ resp.data }", 4 )
            Logging( "Pin Verification Headers: ${ resp.getHeaders() }", 4 )
            ProcessEvent( "Status", "OK" )
            // Get times to use for scheduling
            def Hour = ( new Date().format( "h" ) as int )
            def Minute = ( ( ( new Date().format( "m" ) as int ) + 1 ) % 59 )
            def Second = ( new Date().format( "s" ) as int )
            schedule( "${ Second } ${ Minute } ${ Hour } ? * *", "DailyCheck" )
            //schedule( "${ Second } ${ Minute } ${ Hour }/12 ? * *", "Reauthorize" )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get network information from the account
def GetNetworks(){
    def Params
    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/networks", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
    Logging( "Network Params = ${ Params.uri }", 4 )
    asynchttpGet( "ProcessNetworks", Params )
}

// Handle the response GetNetworks
def ProcessNetworks( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Network response = ${ resp.data }", 3 )
            Data = parseJson( resp.data )
            if( Data.networks.size() > 0 ){
                for( i = 0; i < Data.networks.size(); i++ ){
                    ProcessDevices( "Network", Data.networks[ i ] )
                    //GetCameras( Data.networks[ i ].id )
                }
            }
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get cameras for a network or all of them
def GetCameras( NetworkID = null ){
    def Params
    if( NetworkID == null ){
        if( state.Network != null ){
            state.Network.each{
                Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ it }/cameras", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                Logging( "Camera Params = ${ Params.uri }", 4 )
                asynchttpGet( "ProcessCameras", Params, [ "GetCameras":"${ it }" ] )
                pauseExecution( 1000 )
            }
        } else {
            Logging( "No network listed, cannot get cameras.", 5 )
            ProcessEvent( "Status", "No network listed, cannot get cameras." )
        }
    } else {
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ NetworkID }/cameras", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        Logging( "Camera Params = ${ Params.uri }", 4 )
        asynchttpGet( "ProcessCameras", Params, [ "GetCameras":"${ NetworkID }" ] )
    }
}

// Handle the response for GetCameras
def ProcessCameras( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Camera response = ${ resp.data }", 4 )
            Data = parseJson( resp.data )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        case 426:
            Logging( "Upgrade required, likely Blink has changed things and wants a new method used", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get status of a specific camera
def GetCameraStatus( CameraID = null, NetworkID = null ){
    def Params
    def Network
    def Type
    if( CameraID == null ){
        if( state.Camera != null ){
            state.Camera.each{
                Type = "Camera"
                if( NetworkID != null ){
                    Network = NetworkID
                } else {
                    if( getChildDevice( "Camera-${ it }" ) != null ){
                        Network = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
                    }
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ it }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Camera Status Params = ${ Params.uri }", 4 )
                    Type = "Camera"
                    asynchttpGet( "ProcessCameraStatus", Params, [ "GetCameraStatus":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for camera, cannot get camera status." )
                }
                pauseExecution( 1000 )
            }
        }
        /*
        if( state.MiniCamera != null ){
            state.MiniCamera.each{
                Type = "MiniCamera"
                if( NetworkID != null ){
                    Network = NetworkID
                } else {
                    if( getChildDevice( "MiniCamera-${ it }" ) != null ){
                        Network = getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" )
                    }
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/owl/${ it }", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    Logging( "Mini Camera Status Params = ${ Params.uri }", 4 )
                    asynchttpGet( "ProcessCameraStatus", Params, [ "GetCameraStatus":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Mini Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for Mini Camera, cannot get status." )
                }
                pauseExecution( 1000 )
            }
        }
        if( state.WiredFloodlight != null ){
            state.WiredFloodlight.each{
                Type = "WiredFloodlight"
                if( NetworkID != null ){
                    Network = NetworkID
                } else {
                    if( getChildDevice( "WiredFloodlight-${ it }" ) != null ){
                        Network = getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" )
                    }
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/owl/${ it }", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    Logging( "Wired Floodlight Status Params = ${ Params.uri }", 4 )
                    asynchttpGet( "ProcessCameraStatus", Params, [ "GetCameraStatus":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Wired Floodlight must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for Wired Floodlight, cannot get status." )
                }
                pauseExecution( 1000 )
            }
        }
        */
    } else {
        if( getChildDevice( "Camera-${ CameraID }" ) != null ){
            Network = getChildDevice( "Camera-${ CameraID }" ).ReturnState( "Network ID" )
        } else if( getChildDevice( "MiniCamera-${ CameraID }" ) != null ){
            Network = getChildDevice( "MiniCamera-${ CameraID }" ).ReturnState( "Network ID" )
        } else if( getChildDevice( "WiredFloodlight-${ CameraID }" ) != null ){
            Network = getChildDevice( "WiredFloodlight-${ CameraID }" ).ReturnState( "Network ID" )
        } else if( getChildDevice( "Doorbell-${ CameraID }" ) != null ){
            Network = getChildDevice( "Doorbell-${ CameraID }" ).ReturnState( "Network ID" )
        } else {
            //if( getChildDevice( "MiniCamera-${ CameraID }" ) != null ){
            //    Network = getChildDevice( "MiniCamera-${ CameraID }" ).ReturnState( "Network ID" )
            //    MiniCamera = true
            //} else {
                Logging( "No device with ID ${ CameraID } found. Does not work with Mini Cameras, Doorbells, or Wired Floodlights.", 5 )
                ProcessEvent( "Status", "No device with ID ${ CameraID } found. Does not work with Mini Cameras, Doorbells, or Wired Floodlights." )
            //}
        }
        if( Network == null && NetworkID != null ){
            Network = NetworkID
        }
        if( Network != null ){
            Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ CameraID }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
            Logging( "Camera Status Params = ${ Params.uri }", 4 )
            asynchttpGet( "ProcessCameraStatus", Params, [ "GetCameraStatus":"${ CameraID }", "Type":Type ] )
        } else {
            Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
            ProcessEvent( "Status", "No Network ID submitted/found for camera, cannot get camera status." )
        }
    }
}

// Handle the response for GetCameraStatus
def ProcessCameraStatus( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Camera Status response = ${ resp.data }", 4 )
            Data = parseJson( resp.data )
            switch( data.Type ){
                case "MiniCamera":
                    ProcessDevices( "MiniCamera", Data.camera_status, data.GetCameraStatus )
                    break
                case "WiredFloodlight":
                    ProcessDevices( "WiredFloodlight", Data.camera_status, data.GetCameraStatus )
                    break
                case "Camera":
                    ProcessDevices( "Camera", Data.camera_status, data.GetCameraStatus )
                    break
                case "Doorbell":
                    ProcessDevices( "Doorbell", Data.camera_status, data.GetCameraStatus )
                    break
            }
            ProcessEvent( "Status", "OK" )
            break
        case 400:
            Logging( "Bad Request", 5 )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 404:
            Logging( "URL not found, likely the API changed and the function no longer works.", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get information on a specific camera
def GetCameraInfo( CameraID = null ){
    def Params
    def Network
    def Type
    if( CameraID == null ){
        if( state.Camera != null ){
            state.Camera.each{
                Type = "Camera"
                if( getChildDevice( "Camera-${ it }" ) != null ){
                    Network = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
                    if( Network != null ){
                        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ it }/config", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                        Logging( "Camera Info Params = ${ Params.uri }", 4 )
                        asynchttpGet( "ProcessCameraInfo", Params, [ "GetCameraInfo":"${ it }", "Type":Type ] )
                    } else {
                        Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                        ProcessEvent( "Status", "No Network ID submitted/found for camera, cannot get camera info." )
                    }
                } else {
                    Logging( "No camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No camera device with ID ${ it } found." )
                }

                pauseExecution( 1000 )
            }
        }
    } else {
        if( getChildDevice( "Camera-${ CameraID }" ) != null ){
            Network = getChildDevice( "Camera-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "Camera"
            if( Network != null ){
                Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ CameraID }/config", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                Logging( "Camera Info Params = ${ Params.uri }", 4 )
                asynchttpGet( "ProcessCameraInfo", Params, [ "GetCameraInfo":"${ CameraID }", "Type":Type ] )
            } else {
                Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                ProcessEvent( "Status", "No Network ID submitted/found for camera, cannot get camera info." )
            }
        } else {
            Logging( "No Camera device with ID ${ CameraID } found. Does not work with Mini Cameras.", 5 )
            ProcessEvent( "Status", "No Camera device with ID ${ CameraID } found. Does not work with Mini Cameras." )
        }

    }
}

// Handle the response for GetCameraInfo
def ProcessCameraInfo( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Camera Info response = ${ resp.data }", 4 )
            Data = parseJson( resp.data )
            switch( data.Type ){
                case "MiniCamera":
                    ProcessDevices( "MiniCamera", Data.camera[ 0 ], data.GetCameraInfo )
                    break
                case "WiredFloodlight":
                    ProcessDevices( "WiredFloodlight", Data.camera[ 0 ], data.GetCameraInfo )
                    break
                case "Camera":
                    ProcessDevices( "Camera", Data.camera[ 0 ], data.GetCameraInfo )
                    break
                case "Doorbell":
                    ProcessDevices( "Doorbell", Data.camera[ 0 ], data.GetCameraInfo )
                    break
            }
            ProcessEvent( "Status", "OK" )
            break
        case 400:
            Logging( "Bad Request", 5 )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 404:
            Logging( "URL not found, likely the API changed and the function no longer works.", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get camera usage information from the account
def GetCameraUsage(){
    def Params
    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/camera/usage", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
    Logging( "Camera Usage Params = ${ Params.uri }", 4 )
    asynchttpGet( "ProcessCameraUsage", Params )
}

// Handle the response GetCameraUsage
def ProcessCameraUsage( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Camera Usage response = ${ resp.data }", 3 )
            Json = parseJson( resp.data )
            if( Json.networks.size() > 0 ){
                for( x = 0; x < Json.networks.size(); x++ ){
                    if( Json.networks[ x ].cameras.size() > 0 ){
                        for( y = 0; y < Json.networks[ x ].cameras.size(); y++ ){
                            //ProcessDevices( "Camera", Json.networks[ x ].cameras[ y ] )
                            //PostStateToChild( "Camera-${ Json.networks[ x ].cameras[ y ].id }", "NetworkID", Json.networks[ x ].network_id )
                        }
                    }
                }
            }
            ProcessEvent( "Status", "OK" )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get User Information
def GetUserInfo(){
    def Params
    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/user", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
    Logging( "User Info Params = ${ Params.uri }", 4 )
    asynchttpGet( "ProcessUserInfo", Params )
}

// Handle the response for GetUserInfo
def ProcessUserInfo( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "User Info response = ${ resp.data }", 4 )
            Json = parseJson( resp.data )
            ProcessEvent( "Status", "OK" )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Submits a request to have the camera take a new thumbnail
// /api/v5/accounts/${ AccountID }/networks/${ NetworkID }/cameras/${ CameraID }/thumbnail
// /api/v1/accounts/${ AccountID }/networks/${ NetworkID }/owls/${ CameraID }/thumbnail
// /api/v1/accounts/${ AccountID }/networks/${ NetworkID }/doorbells/${ CameraID }/thumbnail
def GetNewThumbnail( CameraID = null ){ 
    def Params
    def Network
    def Type
    if( CameraID == null ){
        if( state.Camera != null ){
            state.Camera.each{
                Type = "Camera"
                if( getChildDevice( "Camera-${ it }" ) != null ){
                    Network = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
                    //Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v5/accounts/${ state.AccountID }/networks/${ Network }/cameras/${ it }/thumbnail", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ it }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Camera New Thumbnail Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessNewThumbnail", Params, [ "GetNewThumbnail":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No camera device with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
        if( state.MiniCamera != null ){
            state.MiniCamera.each{
                Type = "MiniCamera"
                if( getChildDevice( "MiniCamera-${ it }" ) != null ){
                    Network = getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ it }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Mini Camera New Thumbnail Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessNewThumbnail", Params, [ "GetNewThumbnail":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No mini camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No mini camera device with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
        if( state.WiredFloodlight != null ){
            state.WiredFloodlight.each{
                Type = "WiredFloodlight"
                if( getChildDevice( "WiredFloodlight-${ it }" ) != null ){
                    Network = getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" )
                    //Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/superior/${ it }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/superior/${ it }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    //Failed Attempt = Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/superior/${ it }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Wired Floodlight New Thumbnail Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessNewThumbnail", Params, [ "GetNewThumbnail":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No Wired Floodlight device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No Wired Floodlight device with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
        if( state.Doorbell != null ){
            state.Doorbell.each{
                Type = "Doorbell"
                if( getChildDevice( "Doorbell-${ it }" ) != null ){
                    Network = getChildDevice( "Doorbell-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ it }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Doorbell New Thumbnail Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessNewThumbnail", Params, [ "GetNewThumbnail":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No doorbell device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No doorbell device with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
    } else {
        if( getChildDevice( "Camera-${ CameraID }" ) != null ){
            Type = "Camera"
        } else if( getChildDevice( "WiredFloodlight-${ CameraID }" ) != null ){
            Type = "WiredFloodlight"
        } else if( getChildDevice( "MiniCamera-${ CameraID }" ) != null ){
            Type = "MiniCamera"
        } else if( getChildDevice( "Doorbell-${ CameraID }" ) != null ){
            Type = "Doorbell"
        } else {
            Logging( "No ${ Type } device with ID ${ CameraID } found.", 5 )
            ProcessEvent( "Status", "No ${ Type } device with ID ${ CameraID } found." )
        }
        if( Type != null ){
            Network = getChildDevice( "${ Type }-${ CameraID }" ).ReturnState( "Network ID" )
        }
        if( Network == null && NetworkID != null ){
            Network = NetworkID
        }
        if( Network != null ){
            switch( Type ){
                case "WiredFloodlight":
                    //Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/superior/${ it }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/superior/${ CameraID }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    //Failed Attempt! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/superior/${ it }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "MiniCamera":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ CameraID }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "Camera":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ CameraID }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    //Does not work! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/camera/${ CameraID }/thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "Doorbell":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ CameraID }thumbnail", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
            }
            Logging( "${ Type } New Thumbnail Params = ${ Params.uri }", 4 )
            asynchttpPost( "ProcessNewThumbnail", Params, [ "GetNewThumbnail":"${ CameraID }", "Type":Type ] )
        } else {
            Logging( "${ Type } must have a state \"Network ID\" or one must be submitted with this command.", 5 )
        }
    }
    pauseExecution( 5000 )
    GetHomescreen() 
}

// Handle the response from getting thumbnail
def ProcessNewThumbnail( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "NewThumbnail response for ${ data.GetNewThumbnail }= ${ resp.data }", 4 )
            Json = parseJson( resp.data )
            ProcessDevices( data.Type, Json, data.GetNewThumbnail )
            ProcessEvent( "Status", "OK" )           
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 404:
            Logging( "Requested resource not found, attempted ${ data }", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to actually get the thumbnail image, not just call for a new one
def GetThumbnail( CameraID = null ){
    GetNewThumbnail( CameraID )
    pauseExecution( 5000 )
    def Params
    def Type
    def Thumbnail
    if( CameraID == null ){
        if( state.Camera != null ){
            state.Camera.each{
                Type = "Camera"
                if( getChildDevice( "${ Type }-${ it }" ) != null ){
					Thumbnail = getChildDevice( "${ Type }-${ it }" ).ReturnState( "Thumbnail File" )
					if( Thumbnail.find( "ts=" ) ){ // New style thumbnail format
						//Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
			            Logging( "GetThumbnail Params: ${ Params }", 4 )
						try{
							httpGet( Params ){ resp ->
								switch( resp.getStatus() ){
									case 200:
										Logging( "Got thumbnail for ${ Type }-${ it }", 4 )
										ByteArrayOutputStream ByteByByte = new ByteArrayOutputStream()
										ResponseBytes = resp.data.getBytes()
										ByteByByte.write( ResponseBytes )
										FileData = ByteByByte.toByteArray()
										uploadHubFile( "${ Type }-${ it }_Image.jpg", FileData )
										PostEventToChild( "${ Type }-${ it }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"http://${ location.hub.localIP }/local/${ Type }-${ it }_Image.jpg\">" )
										PostEventToChild( "${ Type }-${ it }", "image", "<img src=\"http://${ location.hub.localIP }/local/${ Type }-${ it }_Image.jpg\">" )
                                        PostEventToChild( "${ Type }-${ it }", "Updated", "${ new Date() }" )
										//PostEventToChild( "${ Type }-${ it }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"data:image/jpeg;base64,${ resp.data }\">" )
										//PostEventToChild( "${ Type }-${ it }", "image", "<img src=\"data:image/jpeg;base64,${ resp.data }\">" )
										break
									case 401:
										Logging( "Unauthorized", 5 )
										break
									case 408:
										Logging( "Request time out", 5 )
										break
									default:
										Logging( "${ resp.getStatus() } from Blink", 5 )
										break
								}
							}
						} catch( Exception e ){
							Logging( "Exception when attempting to get thumbnail: ${ e }", 5 )
						}
                    } else {
						// Old method, no longer appears to work (404 error)
						//Thumbnail = Thumbnail.split( /\./ )[ 0 ]
						//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ it }/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ it }/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						Logging( "No valid URL for ${ Type }-${ it }\'s thumbnail present.", 4 )
					} 
				} else {
                    Logging( "No ${ Type } with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No ${ Type } with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
        if( state.MiniCamera != null ){
            state.MiniCamera.each{
                Type = "MiniCamera"
                if( getChildDevice( "${ Type }-${ it }" ) != null ){
					Thumbnail = getChildDevice( "${ Type }-${ it }" ).ReturnState( "Thumbnail File" )
					if( Thumbnail.find( "ts=" ) ){ // New style thumbnail format
						//Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
			            Logging( "GetThumbnail Params: ${ Params }", 4 )
						try{
							httpGet( Params ){ resp ->
								switch( resp.getStatus() ){
									case 200:
										Logging( "Got thumbnail for ${ Type }-${ it }", 4 )
										ByteArrayOutputStream ByteByByte = new ByteArrayOutputStream()
										ResponseBytes = resp.data.getBytes()
										ByteByByte.write( ResponseBytes )
										FileData = ByteByByte.toByteArray()
										uploadHubFile( "${ Type }-${ it }_Image.jpg", FileData )
										PostEventToChild( "${ Type }-${ it }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"http://${ location.hub.localIP }/local/${ Type }-${ it }_Image.jpg\">" )
										PostEventToChild( "${ Type }-${ it }", "image", "<img src=\"http://${ location.hub.localIP }/local/${ Type }-${ it }_Image.jpg\">" )
                                        PostEventToChild( "${ Type }-${ it }", "Updated", "${ new Date() }" )
										//PostEventToChild( "${ Type }-${ it }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"data:image/jpeg;base64,${ resp.data }\">" )
										//PostEventToChild( "${ Type }-${ it }", "image", "<img src=\"data:image/jpeg;base64,${ resp.data }\">" )
										break
									case 401:
										Logging( "Unauthorized", 5 )
										break
									case 408:
										Logging( "Request time out", 5 )
										break
									default:
										Logging( "${ resp.getStatus() } from Blink", 5 )
										break
								}
							}
						} catch( Exception e ){
							Logging( "Exception when attempting to get thumbnail: ${ e }", 5 )
						}
                    } else {
						// Old method, no longer appears to work (404 error)
						//Thumbnail = Thumbnail.split( /\./ )[ 0 ]
						//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ it }/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ it }/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						Logging( "No valid URL for ${ Type }-${ it }\'s thumbnail present.", 4 )
					} 
				} else {
                    Logging( "No ${ Type } with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No ${ Type } with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
        if( state.WiredFloodlight != null ){
            state.WiredFloodlight.each{
                Type = "WiredFloodlight"
                if( getChildDevice( "${ Type }-${ it }" ) != null ){
					Thumbnail = getChildDevice( "${ Type }-${ it }" ).ReturnState( "Thumbnail File" )
					if( Thumbnail.find( "ts=" ) ){ // New style thumbnail format
						//Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
			            Logging( "GetThumbnail Params: ${ Params }", 4 )
						try{
							httpGet( Params ){ resp ->
								switch( resp.getStatus() ){
									case 200:
										Logging( "Got thumbnail for ${ Type }-${ it }", 4 )
										ByteArrayOutputStream ByteByByte = new ByteArrayOutputStream()
										ResponseBytes = resp.data.getBytes()
										ByteByByte.write( ResponseBytes )
										FileData = ByteByByte.toByteArray()
										uploadHubFile( "${ Type }-${ it }_Image.jpg", FileData )
										PostEventToChild( "${ Type }-${ it }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"http://${ location.hub.localIP }/local/${ Type }-${ it }_Image.jpg\">" )
										PostEventToChild( "${ Type }-${ it }", "image", "<img src=\"http://${ location.hub.localIP }/local/${ Type }-${ it }_Image.jpg\">" )
                                        PostEventToChild( "${ Type }-${ it }", "Updated", "${ new Date() }" )
										//PostEventToChild( "${ Type }-${ it }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"data:image/jpeg;base64,${ resp.data }\">" )
										//PostEventToChild( "${ Type }-${ it }", "image", "<img src=\"data:image/jpeg;base64,${ resp.data }\">" )
										break
									case 401:
										Logging( "Unauthorized", 5 )
										break
									case 408:
										Logging( "Request time out", 5 )
										break
									default:
										Logging( "${ resp.getStatus() } from Blink", 5 )
										break
								}
							}
						} catch( Exception e ){
							Logging( "Exception when attempting to get thumbnail: ${ e }", 5 )
						}
                    } else {
						// Old method, no longer appears to work (404 error)
						//Thumbnail = Thumbnail.split( /\./ )[ 0 ]
						//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ it }/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ it }/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						Logging( "No valid URL for ${ Type }-${ it }\'s thumbnail present.", 4 )
					} 
				} else {
                    Logging( "No ${ Type } with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No ${ Type } with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
        if( state.Doorbell != null ){
            state.Doorbell.each{
                Type = "Doorbell"
                if( getChildDevice( "${ Type }-${ it }" ) != null ){
					Thumbnail = getChildDevice( "${ Type }-${ it }" ).ReturnState( "Thumbnail File" )
					if( Thumbnail.find( "ts=" ) ){ // New style thumbnail format
						//Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
			            Logging( "GetThumbnail Params: ${ Params }", 4 )
						try{
							httpGet( Params ){ resp ->
								switch( resp.getStatus() ){
									case 200:
										Logging( "Got thumbnail for ${ Type }-${ it }", 4 )
										ByteArrayOutputStream ByteByByte = new ByteArrayOutputStream()
										ResponseBytes = resp.data.getBytes()
										ByteByByte.write( ResponseBytes )
										FileData = ByteByByte.toByteArray()
										uploadHubFile( "${ Type }-${ it }_Image.jpg", FileData )
										PostEventToChild( "${ Type }-${ it }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"http://${ location.hub.localIP }/local/${ Type }-${ it }_Image.jpg\">" )
										PostEventToChild( "${ Type }-${ it }", "image", "<img src=\"http://${ location.hub.localIP }/local/${ Type }-${ it }_Image.jpg\">" )
                                        PostEventToChild( "${ Type }-${ it }", "Updated", "${ new Date() }" )
										//PostEventToChild( "${ Type }-${ it }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"data:image/jpeg;base64,${ resp.data }\">" )
										//PostEventToChild( "${ Type }-${ it }", "image", "<img src=\"data:image/jpeg;base64,${ resp.data }\">" )
										break
									case 401:
										Logging( "Unauthorized", 5 )
										break
									case 408:
										Logging( "Request time out", 5 )
										break
									default:
										Logging( "${ resp.getStatus() } from Blink", 5 )
										break
								}
							}
						} catch( Exception e ){
							Logging( "Exception when attempting to get thumbnail: ${ e }", 5 )
						}
                    } else {
						// Old method, no longer appears to work (404 error)
						//Thumbnail = Thumbnail.split( /\./ )[ 0 ]
						//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ it }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ it }/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ it }/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
						Logging( "No valid URL for ${ Type }-${ it }\'s thumbnail present.", 4 )
					} 
                } else {
                    Logging( "No ${ Type } with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No ${ Type } with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
    } else {
		if( getChildDevice( "Camera-${ CameraID }" ) != null ){
			Type = "Camera"
		} else if( getChildDevice( "WiredFloodlight-${ CameraID }" ) != null ){
			Type = "WiredFloodlight"
		} else if( getChildDevice( "MiniCamera-${ CameraID }" ) != null ){
			Type = "MiniCamera"
		} else if( getChildDevice( "Doorbell-${ CameraID }" ) != null ){
			Type = "Doorbell"
		} else {
			Logging( "No device with ID ${ CameraID } found.", 5 )
			ProcessEvent( "Status", "No device with ID ${ CameraID } found." )
		}
		if( Type != null ){
			Thumbnail = getChildDevice( "${ Type }-${ CameraID }" ).ReturnState( "Thumbnail File" )
			if( Thumbnail.find( "ts=" ) ){ // New style thumbnail format
				//Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ CameraID }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
				Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent' : "${ UserAgentValue() }" ] ]
			} else { // Old method, no longer appears to work (404 error)
				//Thumbnail = Thumbnail.split( /\./ )[ 0 ]
				//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ CameraID }/thumbnail/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
				//400! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/${ Type }/${ CameraID }/thumbnail/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
				//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ CameraID }/${ Thumbnail }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
				//404! Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/media/production/account/${ state.AccountID }/network/${ Network }/camera/${ CameraID }/${ Thumbnail }.jpg", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
				Logging( "No valid URL for ${ Type }-${ CameraID }\'s thumbnail present.", 4 )
			}
			Logging( "GetThumbnail Params: ${ Params }", 4 )
            try{
				httpGet( Params ){ resp ->
					switch( resp.getStatus() ){
						case 200:
							Logging( "Got thumbnail for ${ Type }-${ CameraID }", 4 )
							ByteArrayOutputStream ByteByByte = new ByteArrayOutputStream()
							ResponseBytes = resp.data.getBytes()
							ByteByByte.write( ResponseBytes )
							FileData = ByteByByte.toByteArray()
							uploadHubFile( "${ Type }-${ CameraID }_Image.jpg", FileData )
                            PostEventToChild( "${ Type }-${ CameraID }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"http://${ location.hub.localIP }/local/${ Type }-${ CameraID }_Image.jpg\">" )
                            PostEventToChild( "${ Type }-${ CameraID }", "image", "<img src=\"http://${ location.hub.localIP }/local/${ Type }-${ CameraID }_Image.jpg\">" )
                            PostEventToChild( "${ Type }-${ CameraID }", "Updated", "${ new Date() }" )
                            //PostEventToChild( "${ Type }-${ CameraID }", "Thumbnail", "<img height=\"25%\" width=\"25%\" src=\"data:image/jpeg;base64,${ resp.data }\">" )
                            //PostEventToChild( "${ Type }-${ CameraID }", "image", "<img src=\"data:image/jpeg;base64,${ resp.data }\">" )
							break
						case 401:
							Logging( "Unauthorized", 5 )
							break
						case 408:
							Logging( "Request time out", 5 )
							break
						default:
							Logging( "${ resp.getStatus() } from Blink", 5 )
							break
					}
				}
			} catch( Exception e ){
				Logging( "Exception when attempting to get thumbnail: ${ e }", 5 )
			}
		}
    }
}

// Attempts to get a new video clip for the camera /network/${ NetworkID }/camera/${ CameraID }/clip
def GetNewVideo( CameraID = null ){
        def Params
    def Network
    def Type
    if( CameraID == null ){
        if( state.Camera != null ){
            state.Camera.each{
                Type = "Camera"
                if( getChildDevice( "Camera-${ it }" ) != null ){
                    Network = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ it }/clip", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Camera New Video Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessNewVideo", Params, [ "GetNewVideo":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No camera device with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
        if( state.MiniCamera != null ){
            state.MiniCamera.each{
                Type = "MiniCamera"
                if( getChildDevice( "MiniCamera-${ it }" ) != null ){
                    Network = getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ it }/clip", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Mini Camera New Video Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessNewVideo", Params, [ "GetNewVideo":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No mini camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No mini camera device with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
        if( state.WiredFloodlight != null ){
            state.WiredFloodlight.each{
                Type = "WiredFloodlight"
                if( getChildDevice( "WiredFloodlight-${ it }" ) != null ){
                    Network = getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/superior/${ it }/clip", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Wired Floodlight New Video Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessNewVideo", Params, [ "GetNewVideo":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No Wired Floodlight device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No Wired Floodlight device with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
        if( state.Doorbell != null ){
            state.Doorbell.each{
                Type = "Doorbell"
                if( getChildDevice( "Doorbell-${ it }" ) != null ){
                    Network = getChildDevice( "Doorbell-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ it }/clip", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Doorbell New Video Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessNewVideo", Params, [ "GetNewVideo":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No doorbell device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No doorbell device with ID ${ it } found." )
                }
                pauseExecution( 5000 )
            }
        }
    } else {
        if( getChildDevice( "Camera-${ CameraID }" ) != null ){
            Type = "Camera"
        } else if( getChildDevice( "WiredFloodlight-${ CameraID }" ) != null ){
            Type = "WiredFloodlight"
        } else if( getChildDevice( "MiniCamera-${ CameraID }" ) != null ){
            Type = "MiniCamera"
        } else if( getChildDevice( "Doorbell-${ CameraID }" ) != null ){
            Type = "Doorbell"
        } else {
            Logging( "No ${ Type } device with ID ${ CameraID } found.", 5 )
            ProcessEvent( "Status", "No ${ Type } device with ID ${ CameraID } found." )
        }
        if( Type != null ){
            Network = getChildDevice( "${ Type }-${ CameraID }" ).ReturnState( "Network ID" )
        }
        if( Network == null && NetworkID != null ){
            Network = NetworkID
        }
        if( Network != null ){
            switch( Type ){
                case "WiredFloodlight":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/media/accounts/${ state.AccountID }/networks/${ Network }/superior/${ CameraID }/clip", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "MiniCamera":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ CameraID }/clip", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "Camera":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ CameraID }/clip", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "Doorbell":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ CameraID }clip", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
            }
            Logging( "${ Type } New Video Params = ${ Params.uri }", 4 )
            asynchttpPost( "ProcessNewVideo", Params, [ "GetNewVideo":"${ CameraID }", "Type":Type ] )
        } else {
            Logging( "${ Type } must have a state \"Network ID\" or one must be submitted with this command.", 5 )
        }
    }
    GetHomescreen() 
}

// Handle the response from getting new video
def ProcessNewVideo( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "New Video response for ${ data.GetNewVideo }= ${ resp.data }", 4 )
            Json = parseJson( resp.data )
            ProcessDevices( data.Type, Json, data.GetNewVideo )
            ProcessEvent( "Status", "OK" )           
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get 
def ListSchedules( NetworkID = null ){
    def Params
    if( NetworkID == null ){
        if( state.Network != null ){
            state.Network.each{
                Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/networks/${ it }/programs", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                Logging( "List Schedules Params = ${ Params.uri }", 4 )
                asynchttpGet( "ProcessListSchedules", Params, [ "ListSchedules":"${ it }" ] )
            }
        } else {
            Logging( "No networks listed, cannot check schedules.", 5 )   
        }
    } else {
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/networks/${ NetworkID }/programs", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        Logging( "List Schedules Params = ${ Params.uri }", 4 )
        asynchttpGet( "ProcessListSchedules", Params, [ "ListSchedules":"${ NetworkID }" ] )
    }
}

// Handle the response for listing schedules
def ProcessListSchedules( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "List Schedules response = ${ resp.data }", 4 )
            Json = parseJson( resp.data )
            ProcessDevices( "Network", Json[ 0 ], data.ListSchedules )
            ProcessEvent( "Status", "OK" )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get network status information
def GetNetworkStatus( NetworkID = null ){ // /network/${ NetworkID }
    def Params
    if( NetworkID == null ){
        state.Network.each{
            Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ it }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
            asynchttpGet( "ProcessNetworkStatus", Params, [ "GetNetworkStatus":"${ it }" ] )
        }
    } else {
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ NetworkID }", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        asynchttpGet( "ProcessNetworkStatus", Params, [ "GetNetworkStatus":"${ NetworkID }" ] )
    }
}

// Handle the response from getting network status
def ProcessNetworkStatus( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Network Status response = ${ resp.data }", 4 )
            Json = parseJson( resp.data )
            ProcessDevices( "Network", Json.network )
            ProcessEvent( "Status", "OK" )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to check sync module information, but sending a SyncModule's ID will NOT WORK, NetworkIDs only
def CheckSyncModule( NetworkID = null ){ // /network/${ NetworkID }
    def Params
    if( NetworkID == null ){
        state.Network.each{
            Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ it }/syncmodules", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
            asynchttpGet( "ProcessCheckSyncModule", Params, [ "CheckSyncModule":"${ it }" ] )
        }
    } else {
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ NetworkID }/syncmodules", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        asynchttpGet( "ProcessCheckSyncModule", Params, [ "CheckSyncModule":"${ NetworkID }" ] )
    }
}

// Handle the response from getting sync module status
def ProcessCheckSyncModule( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "CheckSyncModule response = ${ resp.data }", 4 )
            Json = parseJson( resp.data )
            ProcessDevices( "SyncModule", Json.syncmodule )
            ProcessEvent( "Status", "OK" )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to check events, NetworkIDs only
def GetEvents( NetworkID = null ){ // /events/network/${ NetworkID }
    def Params
    if( NetworkID == null ){
        state.Network.each{
            Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/events/network/${ it }", requestContentType: "application/json", contentType: "application/json", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
            asynchttpGet( "ProcessEvents", Params, [ "GetEvents":"${ it }" ] )
        }
    } else {
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/events/network/${ NetworkID }", requestContentType: "application/json", contentType: "application/json", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        Logging( "Get Events Params: ${ Params }", 4 )
        asynchttpGet( "ProcessEvents", Params, [ "GetEvents":"${ NetworkID }" ] )
    }
}

// Handle the response from getting events
def ProcessEvents( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "SyncModule Events response = ${ resp.data }", 4 )
            Json = parseJson( resp.data )
            ProcessDevices( "SyncModule", Json.syncmodule )
            ProcessEvent( "Status", "OK" )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        case 426:
            Logging( "GetEvents Protocol upgrade required", 4 )
            Logging( "Events response = ${ resp }", 4 )
            Logging( "Events error data = ${ resp.getErrorData() }", 4 )
            Logging( "Events headers = ${ resp.getHeaders() }", 4 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get the camera sensor information, only works for regular camera devices, not Mini/Doorbell/Wired Floodlight
def GetCameraSensors( CameraID = null, NetworkID = null ){ // /network/${ NetworkID }/camera/${ CameraID }/signals
    def Params
    def Network
    def MiniCamera = false
    if( CameraID == null ){
        if( state.Camera != null ){
            state.Camera.each{
                if( NetworkID == null ){
                    if( getChildDevice( "Camera-${ it }" ) != null ){
                        Network = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
                    } else {
                        Logging( "No camera device with ID ${ it } found.", 5 )
                        ProcessEvent( "Status", "No camera device with ID ${ it } found." )
                    }
                } else {
                    Network = NetworkID
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ it }/signals", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Camera Sensors Params = ${ Params.uri }", 4 )
                    asynchttpGet( "ProcessCameraSensors", Params, [ "GetCameraSensors":"${ it }", "MiniCamera":MiniCamera ] )
                } else {
                    Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                }
                pauseExecution( 1000 )
            }
        }
    } else {
        if( getChildDevice( "Camera-${ CameraID }" ) != null ){
            Network = getChildDevice( "Camera-${ CameraID }" ).ReturnState( "Network ID" )
        } else {
            Logging( "No camera device with ID ${ CameraID } found. Does not work with Mini/Doorbell/Wired Floodlight Cameras.", 5 )
            ProcessEvent( "Status", "No camera device with ID ${ CameraID } found. Does not work with Mini/Doorbell/Wired Floodlight Cameras." )
        }
        if( Network == null && NetworkID != null ){
            Network = NetworkID
        }
        if( Network != null ){
            Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ CameraID }/signals", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
            Logging( "Camera Sensors Params = ${ Params.uri }", 4 )
            asynchttpGet( "ProcessCameraSensors", Params, [ "GetCameraSensors":"${ CameraID }", "MiniCamera":MiniCamera ] )
        } else {
            if( MiniCamera != null ){
                Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
            } else {
                Logging( "Does not work with Mini Cameras.", 5 )
            }
        }
    }
}

// Handle the response from getting camera sensor information
def ProcessCameraSensors( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Camera Sensors response = ${ resp.data }", 4 )
            Json = parseJson( resp.data )
            ProcessDevices( "Camera", Json, data.GetCameraSensors )
            ProcessEvent( "Status", "OK" )
            break
        case 400:
            Logging( "Bad request", 5 )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Attempts to get the camera liveview
def GetCameraLiveView( CameraID = null ){
    // /api/v5/accounts/${ AccountID }/networks/${ NetworkID }/cameras/${ CameraID }/liveview
    // /api/v1/accounts/${ AccountID }/networks/${ NetworkID }/doorbells/${ CameraID }/liveview
    def Params
    def Network
    def Type = ""
    if( CameraID == null ){
        if( state.Camera != null ){
            state.Camera.each{
                Type = "Camera"
                if( getChildDevice( "Camera-${ it }" ) != null ){
                    Network = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v5/accounts/${ state.AccountID }/networks/${ Network }/cameras/${ it }/liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Camera LiveView Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessCameraLiveView", Params, [ "GetCameraLiveView":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No camera device with ID ${ it } found." )
                }
                pauseExecution( 1000 )
            }
        }
        if( state.MiniCamera != null ){
            state.MiniCamera.each{
                Type = "MiniCamera"
                if( getChildDevice( "MiniCamera-${ it }" ) != null ){
                    Network = getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ it }/liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Mini Camera LiveView Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessCameraLiveView", Params, [ "GetCameraLiveView":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No mini camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No mini camera device with ID ${ it } found." )
                }
                pauseExecution( 1000 )
            }
        }
        if( state.WiredFloodlight != null ){
            state.WiredFloodlight.each{
                Type = "WiredFloodlight"
                if( getChildDevice( "WiredFloodlight-${ it }" ) != null ){
                    Network = getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v5/accounts/${ state.AccountID }/networks/${ Network }/superior/${ it }/liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    // Failed Attempt = Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/superior/${ it }/liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Wired Floodlight LiveView Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessCameraLiveView", Params, [ "GetCameraLiveView":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No Wired Floodlight device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No Wired Floodlight device with ID ${ it } found." )
                }
                pauseExecution( 1000 )
            }
        }
        if( state.Doorbell != null ){
            state.Doorbell.each{
                Type = "Doorbell"
                if( getChildDevice( "Doorbell-${ it }" ) != null ){
                    Network = getChildDevice( "Doorbell-${ it }" ).ReturnState( "Network ID" )
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ it }/liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Doorbell LiveView Params = ${ Params.uri }", 4 )
                    asynchttpPost( "ProcessCameraLiveView", Params, [ "GetCameraLiveView":"${ it }", "Type":Type ] )
                } else {
                    Logging( "No doorbell device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No doorbell device with ID ${ it } found." )
                }
                pauseExecution( 1000 )
            }
        }
    } else {
        if( getChildDevice( "Camera-${ CameraID }" ) != null ){
            Network = getChildDevice( "Camera-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "Camera"
        } else if( getChildDevice( "WiredFloodlight-${ CameraID }" ) != null ){
            Network = getChildDevice( "WiredFloodlight-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "WiredFloodlight"
        } else if( getChildDevice( "MiniCamera-${ CameraID }" ) != null ){
            Network = getChildDevice( "MiniCamera-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "MiniCamera"
        } else if( getChildDevice( "Doorbell-${ CameraID }" ) != null ){
            Network = getChildDevice( "Doorbell-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "Doorbell"
        } else {
            Logging( "No camera device with ID ${ CameraID } found.", 5 )
            ProcessEvent( "Status", "No camera device with ID ${ CameraID } found." )
        }
        if( Network == null && NetworkID != null ){
            Network = NetworkID
        }
        if( Network != null ){
            switch( Type ){
                case "WiredFloodlight":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v5/accounts/${ state.AccountID }/networks/${ Network }/superior/${ CameraID }/liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    // Failed Attempt = Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/superior/${ CameraID }/liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "MiniCamera":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ CameraID }/liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "Camera":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ CameraID }/liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "Doorbell":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ CameraID }liveview", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
            }
            Logging( "Camera LiveView Params = ${ Params.uri }", 4 )
            asynchttpPost( "ProcessCameraLiveView", Params, [ "GetCameraLiveView":"${ CameraID }", "Type":Type ] )
        } else {
            Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
        }
    }
}

// Handle the response from getting camera liveview
def ProcessCameraLiveView( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Camera LiveView response = ${ resp.data }", 4 )
            Json = parseJson( resp.data )
            switch( data.Type ){
                case "WiredFloodlight":
                    ProcessDevices( "WiredFloodlight", Json, data.GetCameraLiveView )
                    break
                case "MiniCamera":
                    ProcessDevices( "MiniCamera", Json, data.GetCameraLiveView )
                    break
                case "Camera":
                    ProcessDevices( "Camera", Json, data.GetCameraLiveView )
                    break
                case "Doorbell":
                    ProcessDevices( "Doorbell", Json, data.GetCameraLiveView )
                    break
            }
            ProcessEvent( "Status", "OK" )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 404:
            Logging( "Requested resource not found, attempted ${ data }", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Command to disable motion detection on camera(s) does not work on mini, doorbell, or wired floodlights
def EnableMotionDetection( CameraID = null, NetworkID = null ){ // ( /network/{network}/camera/{camera_id}/enable )
    def Params
    def Network
    def Type = ""
    if( CameraID == null ){
        if( state.Camera != null ){
            state.Camera.each{
                Type = "Camera"
                if( getChildDevice( "Camera-${ it }" ) != null ){
                    if( NetworkID == null ){
                        Network = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
                    } else {
                        Network = NetworkID
                    }
                } else {
                    Logging( "No camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No camera device with ID ${ it } found." )
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ it }/enable", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Enable Motion Params = ${ Params.uri }", 4 )
                    asynchttpPost( "GeneralResponse", Params, [ "Method":"Enable Motion Detection", "ID":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for ${ it }, cannot enable motion detection." )
                }
                pauseExecution( 1000 )
            }
        }
        /*
        if( state.WiredFloodlight != null ){
            state.WiredFloodlight.each{
                Type = "WiredFloodlight"
                if( getChildDevice( "WiredFloodlight-${ it }" ) != null ){
                    if( NetworkID == null ){
                        Network = getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" )
                    } else {
                        Network = NetworkID
                    }
                } else {
                    Logging( "No Wired Floodlight device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No Wired Floodlight device with ID ${ it } found." )
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/superior/${ CameraID }/enable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    Logging( "Enable Motion Params = ${ Params.uri }", 4 )
                    asynchttpPost( "GeneralResponse", Params, [ "Method":"Enable Motion Detection", "ID":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Wired Floodlight must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for ${ it }, cannot enable motion detection." )
                }
                pauseExecution( 1000 )
            }
        }
        if( state.MiniCamera != null ){
            state.MiniCamera.each{
                Type = "MiniCamera"
                if( getChildDevice( "MiniCamera-${ it }" ) != null ){
                    if( NetworkID == null ){
                        Network = getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" )
                    } else {
                        Network = NetworkID
                    }
                } else {
                    Logging( "No mini camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No mini camera device with ID ${ it } found." )
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ CameraID }/enable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    Logging( "Enable Motion Params = ${ Params.uri }", 4 )
                    asynchttpPost( "GeneralResponse", Params, [ "Method":"Enable Motion Detection", "ID":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Mini camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for ${ it }, cannot enable motion detection." )
                }
                pauseExecution( 1000 )
            }
        }
        if( state.Doorbell != null ){
            state.Doorbell.each{
                Type = "Doorbell"
                if( getChildDevice( "Doorbell-${ it }" ) != null ){
                    if( NetworkID == null ){
                        Network = getChildDevice( "Doorbell-${ it }" ).ReturnState( "Network ID" )
                    } else {
                        Network = NetworkID
                    }
                } else {
                    Logging( "No doorbell device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No doorbell device with ID ${ it } found." )
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ CameraID }/enable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    Logging( "Enable Motion Params = ${ Params.uri }", 4 )
                    asynchttpPost( "GeneralResponse", Params, [ "Method":"Enable Motion Detection", "ID":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Doorbell must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for ${ it }, cannot enable motion detection." )
                }
                pauseExecution( 1000 )
            }
        }
        */
    } else {
        if( getChildDevice( "Camera-${ CameraID }" ) != null ){
            Network = getChildDevice( "Camera-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "Camera"
        /*
        } else if( getChildDevice( "WiredFloodlight-${ CameraID }" ) != null ){
            Network = getChildDevice( "WiredFloodlight-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "WiredFloodlight"
        } else if( getChildDevice( "MiniCamera-${ CameraID }" ) != null ){
            Network = getChildDevice( "MiniCamera-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "MiniCamera"
        } else if( getChildDevice( "Doorbell-${ CameraID }" ) != null ){
            Network = getChildDevice( "Doorbell-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "Doorbell"
        */
        } else {
            Logging( "No camera device with ID ${ CameraID } found.", 5 )
            ProcessEvent( "Status", "No camera device with ID ${ CameraID } found." )
        }
        if( Network != null ){
            switch( Type ){
                case "WiredFloodlight":
                    //Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/superior/${ CameraID }/enable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    break
                case "MiniCamera":
                    //Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ CameraID }/enable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    break
                case "Camera":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ CameraID }/enable", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "Doorbell":
                    //Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ CameraID }/enable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    break
            }
            Logging( "Enable Motion  Params = ${ Params.uri }", 4 )
            asynchttpPost( "GeneralResponse", Params, [ "Method":"Enable Motion Detection", "ID":"${ CameraID }", "Type":Type ] )
        } else {
            Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
            ProcessEvent( "Status", "No Network ID submitted/found for ${ CameraID }, cannot enable motion detection." )
        }
    }
}

// Command to disable motion detection on camera(s) does not work on mini, doorbell, or wired floodlights
def DisableMotionDetection( CameraID = null, NetworkID = null ){ // ( /network/{network}/camera/{camera_id}/disable )
    def Params
    def Network
    def Type = ""
    if( CameraID == null ){
        if( state.Camera != null ){
            state.Camera.each{
                Type = "Camera"
                if( getChildDevice( "Camera-${ it }" ) != null ){
                    if( NetworkID == null ){
                        Network = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
                    } else {
                        Network = NetworkID
                    }
                } else {
                    Logging( "No camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No camera device with ID ${ it } found." )
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ it }/disable", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    Logging( "Disable Motion Params = ${ Params.uri }", 4 )
                    asynchttpPost( "GeneralResponse", Params, [ "Method":"Disable Motion Detection", "ID":"${ it }", "MiniCamera":MiniCamera ] )
                } else {
                    Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for ${ it }, cannot disable motion detection." )
                }
                pauseExecution( 1000 )
            }
        }
        /*
        if( state.WiredFloodlight != null ){
            state.WiredFloodlight.each{
                Type = "WiredFloodlight"
                if( getChildDevice( "WiredFloodlight-${ it }" ) != null ){
                    if( NetworkID == null ){
                        Network = getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" )
                    } else {
                        Network = NetworkID
                    }
                } else {
                    Logging( "No Wired Floodlight device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No Wired Floodlight device with ID ${ it } found." )
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/superior/${ CameraID }/disable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    Logging( "Disable Motion Params = ${ Params.uri }", 4 )
                    asynchttpPost( "GeneralResponse", Params, [ "Method":"Disable Motion Detection", "ID":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Wired Floodlight must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for ${ it }, cannot disable motion detection." )
                }
                pauseExecution( 1000 )
            }
        }
        if( state.MiniCamera != null ){
            state.MiniCamera.each{
                Type = "MiniCamera"
                if( getChildDevice( "MiniCamera-${ it }" ) != null ){
                    if( NetworkID == null ){
                        Network = getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" )
                    } else {
                        Network = NetworkID
                    }
                } else {
                    Logging( "No mini camera device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No mini camera device with ID ${ it } found." )
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ CameraID }/disable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    Logging( "Disable Motion Params = ${ Params.uri }", 4 )
                    asynchttpPost( "GeneralResponse", Params, [ "Method":"Disable Motion Detection", "ID":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Mini camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for ${ it }, cannot disable motion detection." )
                }
                pauseExecution( 1000 )
            }
        }
        if( state.Doorbell != null ){
            state.Doorbell.each{
                Type = "Doorbell"
                if( getChildDevice( "Doorbell-${ it }" ) != null ){
                    if( NetworkID == null ){
                        Network = getChildDevice( "Doorbell-${ it }" ).ReturnState( "Network ID" )
                    } else {
                        Network = NetworkID
                    }
                } else {
                    Logging( "No doorbell device with ID ${ it } found.", 5 )
                    ProcessEvent( "Status", "No doorbell device with ID ${ it } found." )
                }
                if( Network != null ){
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ CameraID }/disable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    Logging( "Disable Motion Params = ${ Params.uri }", 4 )
                    asynchttpPost( "GeneralResponse", Params, [ "Method":"Disable Motion Detection", "ID":"${ it }", "Type":Type ] )
                } else {
                    Logging( "Doorbell must have a state \"Network ID\" or one must be submitted with this command.", 5 )
                    ProcessEvent( "Status", "No Network ID submitted/found for ${ it }, cannot disable motion detection." )
                }
                pauseExecution( 1000 )
            }
        }
        */
    } else {
        if( getChildDevice( "Camera-${ CameraID }" ) != null ){
            Network = getChildDevice( "Camera-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "Camera"
        /*
        } else if( getChildDevice( "WiredFloodlight-${ CameraID }" ) != null ){
            Network = getChildDevice( "WiredFloodlight-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "WiredFloodlight"
        } else if( getChildDevice( "MiniCamera-${ CameraID }" ) != null ){
            Network = getChildDevice( "MiniCamera-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "MiniCamera"
        } else if( getChildDevice( "Doorbell-${ CameraID }" ) != null ){
            Network = getChildDevice( "Doorbell-${ CameraID }" ).ReturnState( "Network ID" )
            Type = "Doorbell"
        */
        } else {
            Logging( "No camera device with ID ${ CameraID } found.", 5 )
            ProcessEvent( "Status", "No camera device with ID ${ CameraID } found." )
        }
        
        if( Network != null ){
            switch( Type ){
                case "WiredFloodlight":
                    //Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/superior/${ CameraID }/disable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    break
                case "MiniCamera":
                    //Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/owls/${ CameraID }/disable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    break
                case "Camera":
                    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/network/${ Network }/camera/${ CameraID }/disable", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
                    break
                case "Doorbell":
                    //Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ Network }/doorbells/${ CameraID }/disable", headers: ["token-auth": "${ state.AuthToken }" ] ]
                    break
            }
            Logging( "Disable Motion  Params = ${ Params.uri }", 4 )
            asynchttpPost( "GeneralResponse", Params, [ "Method":"Disable Motion Detection", "ID":"${ CameraID }", "Type":Type ] )
        } else {
            Logging( "Camera must have a state \"Network ID\" or one must be submitted with this command.", 5 )
            ProcessEvent( "Status", "No Network ID submitted/found for ${ CameraID }, cannot enable motion detection." )
        }
    }
}

// Command to turn on a wired floodlight
def WiredFloodlightOn( NetworkID, ID ){
    def Params
    if( ( NetworkID != null ) || ( ID != null ) ){
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v5/accounts/${ state.AccountID }/networks/${ NetworkID }/superior/${ ID }/lights/on", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        //Failed Attempt = Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ NetworkID }/superior/${ ID }/on", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        //Failed Attempt = Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ NetworkID }/superior/${ ID }/lights/on", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        Logging( "WiredFloodlightOn Params = ${ Params.uri }", 4 )
        asynchttpPost( "GeneralResponse", Params, [ "Method":"WiredFloodlightOn", "ID":"${ ID }", "Value": "On" ] )
    } else {
        Logging( "WiredFloodlightOn was not provided all parameters. NetworkID: ${ NetworkID } - ID = ${ ID } ", 5 )
    }
}

// Command to turn off a wired floodlight
def WiredFloodlightOff( NetworkID, ID ){
    def Params
    if( ( NetworkID != null ) || ( ID != null ) ){
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v5/accounts/${ state.AccountID }/networks/${ NetworkID }/superior/${ ID }/lights/off", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        //Failed Attempt = Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ NetworkID }/superior/${ ID }/off", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        //Failed Attempt = Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ NetworkID }/superior/${ ID }/lights/off", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        Logging( "WiredFloodlightOff Params = ${ Params.uri }", 4 )
        asynchttpPost( "GeneralResponse", Params, [ "Method":"WiredFloodlightOff", "ID":"${ ID }", "Value": "Off" ] )
    } else {
        Logging( "WiredFloodlightOff was not provided all parameters. NetworkID: ${ NetworkID } - ID = ${ ID } ", 5 )
    }
}

// Command to turn on a floodlight (Storm accessory)
def FloodlightOn( NetworkID, CameraID, StormID ){ //  /api/v1/accounts/{ AccountID }/networks/{ Network ID }/cameras/${ CameraID }/accessories/storm/${ StormID }/lights/on
    def Params
    if( ( NetworkID != null ) || ( CameraID != null ) || ( StormID != null ) ){
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ NetworkID }/cameras/${ CameraID }/accessories/storm/${ StormID }/lights/on", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        Logging( "FloodlightOn Params = ${ Params.uri }", 4 )
        asynchttpPost( "GeneralResponse", Params, [ "Method":"FloodlightOn", "ID":"${ StormID }", "Value": "On" ] )
    } else {
        Logging( "FloodlightOn was not provided all parameters. NetworkID: ${ NetworkID } - CameraID = ${ CameraID } - StormID = ${ StormID } ", 5 )
    }
}

// Command to turn off a floodlight (Storm accessory)
def FloodlightOff( NetworkID, CameraID, StormID ){ //  /api/v1/accounts/{ AccountID }/networks/{ Network ID }/cameras/${ CameraID }/accessories/storm/${ StormID }/lights/off
    def Params
    if( ( NetworkID != null ) || ( CameraID != null ) || ( StormID != null ) ){
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ NetworkID }/cameras/${ CameraID }/accessories/storm/${ StormID }/lights/off", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        Logging( "FloodlightOff Params = ${ Params.uri }", 4 )
        asynchttpPost( "GeneralResponse", Params, [ "Method":"FloodlightOff", "ID":"${ StormID }", "Value": "Off" ] )
    } else {
        Logging( "FloodlightOff was not provided all parameters. NetworkID: ${ NetworkID } - CameraID = ${ CameraID } - StormID = ${ StormID } ", 5 )
    }
}

// Command to arm all systems so detections will be active on all networks
def ArmSystem( NetworkID = null ){ // No ( /api/v1/accounts/{blink.account_id}/networks/{network}/state/arm )
    def Params
    if( NetworkID == null ){
        state.Network.each{
            Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ it }/state/arm", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
            Logging( "Arm System Params = ${ Params.uri }", 4 )
            asynchttpPost( "GeneralResponse", Params, [ "Method":"Arm System", "ID":"${ it }" ] )
            pauseExecution( 1000 )
        }
    } else {
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ NetworkID }/state/arm", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        Logging( "Arm System Params = ${ Params.uri }", 4 )
        asynchttpPost( "GeneralResponse", Params, [ "Method":"Arm System", "ID":"${ NetworkID }" ] )
    }
}

// Command to disarm all systems so detections will be inactive
def DisarmSystem( NetworkID = null ){ // No ( /api/v1/accounts/{blink.account_id}/networks/{network}/state/disarm )
    def Params
    if( NetworkID == null ){
        state.Network.each{
            Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ it }/state/disarm", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
            Logging( "Disarm System Params = ${ Params.uri }", 4 )
            asynchttpPost( "GeneralResponse", Params, [ "Method":"Disarm System", "ID":"${ it }" ] )
            pauseExecution( 1000 )
        }
    } else {
        Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/networks/${ NetworkID }/state/disarm", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
        Logging( "Disarm System Params = ${ Params.uri }", 4 )
        asynchttpPost( "GeneralResponse", Params, [ "Method":"Disarm System", "ID":"${ NetworkID }" ] )
    }
}

// Handle the General responses
def GeneralResponse( resp, data ){
    if( resp == null ){
        Logging( "No response from Blink. Tried ${ data }.", 5 )
    } else {
        Logging( "General Response: data: ${ data }, resp.data: ${ resp }", 4 )
		switch( resp.getStatus() ){
            case 202:
			case 200:
				Json = parseJson( resp.data )
				if( Json.updated_at != null ){
					ProcessEvent( "Updated", Json.updated_at )
				}
				switch( data.Method ){
					case "Arm System":
                        def TempArmedSystems = [ data.ID as int ]
                        if( state."Armed System(s)" != null ){
                            state."Armed System(s)".each{
                                TempArmedSystems.add( it.value as int )
                            }
                        }
                        TempArmedSystems = TempArmedSystems.unique()
                        ProcessEvent( "Armed System(s)", TempArmedSystems )
						ProcessEvent( "Updated", "${ new Date() }" )
						PostEventToChild( "Network-${ data.ID }", "Updated", "${ new Date() }" )
						PostEventToChild( "Network-${ data.ID }", "Armed", "true" )
						PostEventToChild( "Network-${ data.ID }", "Arm String", "Armed" )
                        PostEventToChild( "Network-${ data.ID }", "switch", "on" )
                        ProcessEvent( "Status", "Network-${ data.ID } Armed" )
						def NetworkArmed = data.ID
						if( state.Camera != null ){
							state.Camera.each{
                                if( getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" ) != null ){
								    def CameraNetwork = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
								    if( CameraNetwork != null ){
									    if( NetworkArmed == CameraNetwork ){
										    PostEventToChild( "Camera-${ it }", "Armed", "true" )
										    PostEventToChild( "Camera-${ it }", "Arm String", "Armed" )
									    }
                                    }
                                }
							}
						}
                        if( state.MiniCamera != null ){
							state.MiniCamera.each{
                                if( getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" ) != null ){
                                    def CameraNetwork = getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" )
                                    if( CameraNetwork != null ){
                                        if( NetworkArmed == CameraNetwork ){
                                            PostEventToChild( "MiniCamera-${ it }", "Armed", "true" )
                                            PostEventToChild( "MiniCamera-${ it }", "Arm String", "Armed" )
                                            PostEventToChild( "MiniCamera-${ it }", "switch", "on" )
                                        }
                                    }
                                }
							}
						}
                        if( state.WiredFloodlight != null ){
							state.WiredFloodlight.each{
                                if( getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" ) != null ){
                                    def CameraNetwork = getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" )
                                    if( CameraNetwork != null ){
                                        if( NetworkArmed == CameraNetwork ){
                                            PostEventToChild( "WiredFloodlight-${ it }", "Armed", "true" )
                                            PostEventToChild( "WiredFloodlight-${ it }", "Arm String", "Armed" )
                                            PostEventToChild( "WiredFloodlight-${ it }", "switch", "on" )
                                        }
                                    }
                                }
							}
						}
                        if( state.Doorbell != null ){
							state.Doorbell.each{
                                if( getChildDevice( "Doorbell-${ it }" ).ReturnState( "Network ID" ) != null ){
                                    def CameraNetwork = getChildDevice( "Doorbell-${ it }" ).ReturnState( "Network ID" )
                                    if( CameraNetwork != null ){
                                        if( NetworkArmed == CameraNetwork ){
                                            PostEventToChild( "Doorbell-${ it }", "Armed", "true" )
                                            PostEventToChild( "Doorbell-${ it }", "Arm String", "Armed" )
                                            PostEventToChild( "Doorbell-${ it }", "switch", "on" )
                                        }
                                    }
                                }
							}
						}
						break
					case "Disarm System":
						if( state."Armed System(s)" != null ){
                            def TempArmedSystems = []
                            state."Armed System(s)".each{
                                if( ( it.value as int ) != ( data.ID as int ) ){
                                    TempArmedSystems.add( it.value as int )
                                }
                            }
                            TempArmedSystems = TempArmedSystems.unique()
                            ProcessEvent( "Armed System(s)", TempArmedSystems )
							PostEventToChild( "Network-${ data.ID }", "Armed", "false" )
							PostEventToChild( "Network-${ data.ID }", "Arm String", "Disarmed" )
                            PostEventToChild( "Network-${ data.ID }", "switch", "off" )
						} else {
							Logging( "No armed systems(s) to disarm.", 4 )
						}
						ProcessEvent( "Updated", "${ new Date() }" )
						PostEventToChild( "Network-${ data.ID }", "Updated", "${ new Date() }" )
                        ProcessEvent( "Status", "Network-${ data.ID } Disarmed" )
						def NetworkArmed = data.ID
						if( state.Camera != null ){
							state.Camera.each{
                                if( getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" ) != null ){
								    def CameraNetwork = getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" )
								    if( CameraNetwork != null ){
									    if( NetworkArmed == CameraNetwork ){
										    PostEventToChild( "Camera-${ it }", "Armed", "false" )
										    PostEventToChild( "Camera-${ it }", "Arm String", "Disarmed" )
									    }
                                    }
                                }
							}
						}
                        if( state.WiredFloodlight != null ){
							state.WiredFloodlight.each{
                                if( getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" ) != null ){
                                    def CameraNetwork = getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" )
                                    if( CameraNetwork != null ){
                                        if( NetworkArmed == CameraNetwork ){
                                            PostEventToChild( "WiredFloodlight-${ it }", "Armed", "false" )
                                            PostEventToChild( "WiredFloodlight-${ it }", "Arm String", "Disarmed" )
                                            PostEventToChild( "WiredFloodlight-${ it }", "switch", "off" )
                                        }
                                    }
                                }
							}
						}
                        if( state.MiniCamera != null ){
							state.MiniCamera.each{
                                if( getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" ) != null ){
                                    def CameraNetwork = getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" )
                                    if( CameraNetwork != null ){
                                        if( NetworkArmed == CameraNetwork ){
                                            PostEventToChild( "MiniCamera-${ it }", "Armed", "false" )
                                            PostEventToChild( "MiniCamera-${ it }", "Arm String", "Disarmed" )
                                            PostEventToChild( "MiniCamera-${ it }", "switch", "off" )
                                        }
                                    }
                                }
							}
						}
                        if( state.Doorbell != null ){
							state.Doorbell.each{
                                if( getChildDevice( "Doorbell-${ it }" ).ReturnState( "Network ID" ) != null ){
                                    def CameraNetwork = getChildDevice( "Doorbell-${ it }" ).ReturnState( "Network ID" )
                                    if( CameraNetwork != null ){
                                        if( NetworkArmed == CameraNetwork ){
                                            PostEventToChild( "Doorbell-${ it }", "Armed", "false" )
                                            PostEventToChild( "Doorbell-${ it }", "Arm String", "Disarmed" )
                                            PostEventToChild( "Doorbell-${ it }", "switch", "off" )
                                        }
                                    }
                                }
							}
						}
						break
					case "Enable Motion Detection":
						Logging( "Responding to enable motion detection for ${ data.ID }.", 4 )
                        switch( data.Type ){
                            case "Camera":
						        Logging( "Cameras with Motion = ${ state."Camera(s) With Motion Enabled" }", 3 )
						        if( state."Camera(s) With Motion Enabled" == null ){
							        ProcessEvent( "Camera(s) With Motion Enabled", [ data.ID ] )
						        } else {
							        def Temp = state."Camera(s) With Motion Enabled".plus( [ data.ID ] )
							        ProcessEvent( "Camera(s) With Motion Enabled", Temp.unique() )
						        }
                                //PostEventToChild( "Camera-${ data.ID }", "Updated", "${ new Date() }" )
                                PostEventToChild( "Camera-${ data.ID }", "Motion Detection Enabled", "true" )
                                PostEventToChild( "Camera-${ data.ID }", "switch", "on" )
                                ProcessEvent( "Status", "Camera-${ data.ID } motion detection enabled" )
                                break
                            case "WiredFloodlight":
						        Logging( "Wired Floodlight(s) with Motion = ${ state."Wired Floodlight(s) With Motion Enabled" }", 3 )
						        if( state."Wired Floodlight(s) With Motion Enabled" == null ){
							        ProcessEvent( "Wired Floodlight(s) With Motion Enabled", [ data.ID ] )
						        } else {
							        def Temp = state."Wired Floodlight(s) With Motion Enabled".plus( [ data.ID ] )
							        ProcessEvent( "Wired Floodlight(s) With Motion Enabled", Temp.unique() )
						        }
                                //PostEventToChild( "WiredFloodlight-${ data.ID }", "Updated", "${ new Date() }" )
                                PostEventToChild( "WiredFloodlight-${ data.ID }", "Motion Detection Enabled", "true" )
                                PostEventToChild( "WiredFloodlight-${ data.ID }", "switch", "on" )
                                ProcessEvent( "Status", "WiredFloodlight-${ data.ID } motion detection enabled" )
                                break
                            case "MiniCamera":
						        Logging( "Mini camera(s) with Motion = ${ state."Mini Camera(s) With Motion Enabled" }", 3 )
						        if( state."Mini Camera(s) With Motion Enabled" == null ){
							        ProcessEvent( "Mini Camera(s) With Motion Enabled", [ data.ID ] )
						        } else {
							        def Temp = state."Mini Camera(s) With Motion Enabled".plus( [ data.ID ] )
							        ProcessEvent( "Mini Camera(s) With Motion Enabled", Temp.unique() )
						        }
                                //PostEventToChild( "MiniCamera-${ data.ID }", "Updated", "${ new Date() }" )
                                PostEventToChild( "MiniCamera-${ data.ID }", "Motion Detection Enabled", "true" )
                                PostEventToChild( "MiniCamera-${ data.ID }", "switch", "on" )
                                ProcessEvent( "Status", "MiniCamera-${ data.ID } motion detection enabled" )
                                break
                            case "Doorbell":
						        Logging( "Doorbell(s) with Motion = ${ state."Doorbell(s) With Motion Enabled" }", 3 )
						        if( state."Doorbell(s) With Motion Enabled" == null ){
							        ProcessEvent( "Doorbell(s) With Motion Enabled", [ data.ID ] )
						        } else {
							        def Temp = state."Doorbell(s) With Motion Enabled".plus( [ data.ID ] )
							        ProcessEvent( "Doorbell(s) With Motion Enabled", Temp.unique() )
						        }
                               // PostEventToChild( "Doorbell-${ data.ID }", "Updated", "${ new Date() }" )
                                PostEventToChild( "Doorbell-${ data.ID }", "Motion Detection Enabled", "true" )
                                PostEventToChild( "Doorbell-${ data.ID }", "switch", "on" )
                                ProcessEvent( "Status", "Doorbell-${ data.ID } motion detection enabled" )
                                break
                        }
						break
					case "Disable Motion Detection":
						Logging( "Responding to disable motion detection for ${ data.ID }.", 4 )
                        switch( data.Type ){
                            case "Camera":
                                if( state."Camera(s) With Motion Enabled" != null ){
							        def Temp = []
							        Temp = state."Camera(s) With Motion Enabled".removeElement( data.ID )
							        if( !Temp ){
								        ProcessEvent( "Camera(s) With Motion Enabled", [] )
							        } else {
								        ProcessEvent( "Camera(s) With Motion Enabled", Temp )
							        }
						        }
						        Logging( "Cameras with Motion = ${ state."Camera(s) With Motion Enabled" }", 3 )
                                //PostEventToChild( "Camera-${ data.ID }", "Updated", "${ new Date() }" )
                                PostEventToChild( "Camera-${ data.ID }", "Motion Detection Enabled", "false" )
                                PostEventToChild( "Camera-${ data.ID }", "switch", "off" )
                                ProcessEvent( "Status", "Camera-${ data.ID } motion detection disabled" )
                                break
                            case "WiredFloodlight":
						        if( state."Wired Floodlight(s) With Motion Enabled" != null ){
							        def Temp = []
							        Temp = state."Wired Floodlight(s) With Motion Enabled".removeElement( data.ID )
							        if( !Temp ){
								        ProcessEvent( "Wired Floodlight(s) With Motion Enabled", [] )
							        } else {
								        ProcessEvent( "Wired Floodlight(s) With Motion Enabled", Temp )
							        }
						        }
						        Logging( "Wired Floodlight with Motion = ${ state."Wired Floodlight(s) With Motion Enabled" }", 3 )
                                PostEventToChild( "WiredFloodlight-${ data.ID }", "Updated", "${ new Date() }" )
                                PostEventToChild( "WiredFloodlight-${ data.ID }", "Motion Detection Enabled", "false" )
                                PostEventToChild( "WiredFloodlight-${ data.ID }", "switch", "off" )
                                ProcessEvent( "Status", "Wired Floodlight-${ data.ID } motion detection disabled" )
                                break
                            case "MiniCamera":
						        if( state."MiniCamera(s) With Motion Enabled" != null ){
							        def Temp = []
							        Temp = state."MiniCamera(s) With Motion Enabled".removeElement( data.ID )
							        if( !Temp ){
								        ProcessEvent( "MiniCamera(s) With Motion Enabled", [] )
							        } else {
								        ProcessEvent( "MiniCamera(s) With Motion Enabled", Temp )
							        }
						        }
						        Logging( "MiniCamera with Motion = ${ state."MiniCamera(s) With Motion Enabled" }", 3 )
                                //PostEventToChild( "MiniCamera-${ data.ID }", "Updated", "${ new Date() }" )
                                PostEventToChild( "MiniCamera-${ data.ID }", "Motion Detection Enabled", "false" )
                                PostEventToChild( "MiniCamera-${ data.ID }", "switch", "off" )
                                ProcessEvent( "Status", "MiniCamera-${ data.ID } motion detection disabled" )
                                break
                            case "Doorbell":
						        if( state."Doorbell(s) With Motion Enabled" != null ){
							        def Temp = []
							        Temp = state."Doorbell(s) With Motion Enabled".removeElement( data.ID )
							        if( !Temp ){
								        ProcessEvent( "Doorbell(s) With Motion Enabled", [] )
							        } else {
								        ProcessEvent( "Doorbell(s) With Motion Enabled", Temp )
							        }
						        }
						        Logging( "Doorbell with Motion = ${ state."Doorbell(s) With Motion Enabled" }", 3 )
                                //PostEventToChild( "Doorbell-${ data.ID }", "Updated", "${ new Date() }" )
                                PostEventToChild( "Doorbell-${ data.ID }", "Motion Detection Enabled", "false" )
                                PostEventToChild( "Doorbell-${ data.ID }", "switch", "off" )
                                ProcessEvent( "Status", "Doorbell-${ data.ID } motion detection disabled" )
                                break
                        }
						break
                    case "WiredFloodlightOn":
                        if( Json.command == "accessory_lights_on" ){
                            Logging( "Success turning wired floodlight ${ data.ID } on", 4 )
                        } else {
                            Logging( "Unsuccessful at turning wired floodlight ${ data.ID } on", 4 )
                        }
                        break
                    case "WiredFloodlightOff":
                        if( Json.command == "accessory_lights_off" ){
                            Logging( "Success turning wired floodlight ${ data.ID } off", 4 )
                        } else {
                            Logging( "Unsuccessful at turning wired floodlight ${ data.ID } off", 4 )
                        }
                        break
                    case "FloodlightOn":
                        if( Json.command == "accessory_lights_on" ){
                            Logging( "Success turning floodlight ${ data.ID } on", 4 )
                        } else {
                            Logging( "Unsuccessful at turning floodlight ${ data.ID } on", 4 )
                        }
                        break
                    case "FloodlightOff":
                        if( Json.command == "accessory_lights_off" ){
                            Logging( "Success turning floodlight ${ data.ID } off", 4 )
                        } else {
                            Logging( "Unsuccessful at turning floodlight ${ data.ID } off", 4 )
                        }
                        break
					default:
						Logging( "Unknown Method ${ data.Method }, ID = ${ data.ID }, in GeneralResponse", 5 )
						break
				}
				break
			case 401:
				Logging( "Unauthorized, attempted ${ data }", 5 )
                ProcessEvent( "Status", "Unauthorized. Please perform Authorization and try ${ data.Method } again." )
				break
            case 404:
				Logging( "Requested resource not found, attempted ${ data }", 5 )
                ProcessEvent( "Status", "Blink responded with resource not found when trying ${ data.Method } for ID: ${ data.ID }" )
				break
			case 408:
				Logging( "Request time out", 5 )
                ProcessEvent( "Status", "Blink responded with a time out when trying ${ data.Method } for ID: ${ data.ID }" )
				break
            case 409:
                Logging( "Conflict detected, attempted ${ data }", 5 )
                ProcessEvent( "Status", "Blink responded with a conflict when trying ${ data.Method } for ID: ${ data.ID }" )
				break
			default:
				Logging( "${ resp.getStatus() } from Blink", 5 )
				break
		}
    }
}

// Get the homescreen
def GetHomescreen(){
    def Params
    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v3/accounts/${ state.AccountID }/homescreen", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
    Logging( "Homescreen Params = ${ Params.uri }", 4 )
    asynchttpGet( "ProcessHomescreen", Params )
}

// Handle the response from the homescreen request
def ProcessHomescreen( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Homescreen response = ${ resp.data }", 4 )
            Data = parseJson( resp.data )
            ProcessState( "Armed System(s)", [] )
            ProcessState( "Camera(s) With Motion Enabled", [] )
            if( Data.networks.size() > 0 ){
                ProcessState( "Network", [] )
                for( i = 0; i < Data.networks.size(); i++ ){
                    ProcessDevices( "Network", Data.networks[ i ] )
                }
            }
            if( Data.sync_modules.size() > 0 ){
                ProcessState( "SyncModule", [] )
                for( i = 0; i < Data.sync_modules.size(); i++ ){
                    ProcessDevices( "SyncModule", Data.sync_modules[ i ] )
                }
            }
            if( Data.cameras.size() > 0 ){
                ProcessState( "Camera", [] )
                for( i = 0; i < Data.cameras.size(); i++ ){
                    ProcessDevices( "Camera", Data.cameras[ i ] )
                }
            }
            if( Data.sirens.size() > 0 ){
                ProcessState( "Siren", [] )
                for( i = 0; i < Data.sirens.size(); i++ ){
                    ProcessDevices( "Siren", Data.sirens[ i ] )
                }
            }
            if( Data.chimes.size() > 0 ){
                ProcessState( "Chime", [] )
                for( i = 0; i < Data.chimes.size(); i++ ){
                    ProcessDevices( "Chime", Data.chimes[ i ] )
                }
            }
            if( Data.doorbell_buttons.size() > 0 ){
                ProcessState( "DoorbellButtons", [] )
                for( i = 0; i < Data.doorbell_buttons.size(); i++ ){
                    ProcessDevices( "DoorbellButtons", Data.doorbell_buttons[ i ] )
                }
            }
            if( Data.doorbells.size() > 0 ){
                ProcessState( "Doorbell", [] )
                for( i = 0; i < Data.doorbells.size(); i++ ){
                    ProcessDevices( "Doorbell", Data.doorbells[ i ] )
                }
            }
            if( Data.owls.size() > 0 ){ // Owls are mini cameras with a sub-type "superior" being the new wired floodlights
                ProcessState( "MiniCamera", [] )
                ProcessState( "WiredFloodlight", [] )
                for( i = 0; i < Data.owls.size(); i++ ){
                    if( ( Data.owls[ i ].type == "owl" ) || ( Data.owls[ i ].type == "hawk" ) ){
                        ProcessDevices( "MiniCamera", Data.owls[ i ] )
                    } else if( Data.owls[ i ].type == "superior" ){
                        ProcessDevices( "WiredFloodlight", Data.owls[ i ] )
                    }
                    Logging( "Owl detected of type ${ Data.owls[ i ].type }", 4 )
                }
            }
            if( Data.accessories.storm.size() > 0 ){ // Storms appear to be the floodlight accessory
                ProcessState( "Floodlight", [] )
                for( i = 0; i < Data.accessories.storm.size(); i++ ){
                    ProcessDevices( "Floodlight", Data.accessories.storm[ i ] )
                }
            }
            if( Data.accessories.rosie.size() > 0 ){ // Rosies are an unknown accessory
                ProcessState( "Rosie", [] )
                for( i = 0; i < Data.accessories.rosie.size(); i++ ){
                    ProcessDevices( "Rosie", Data.accessories.rosie[ i ] )
                }
            }
            if( Data.video_stats.storage != null ){
                ProcessState( "Video Storage", Data.video_stats.storage )
            }
            if( Data.video_stats.auto_delete_days != null ){
                ProcessState( "Video Auto Delete Days", Data.video_stats.auto_delete_days )
            }
            if( Data.whats_new.updated_at != null ){
                ProcessState( "Blink New Info Date", Data.whats_new.updated_at )
            }
            if( Data.whats_new.url != null ){
                ProcessState( "Blink New Info URL", Data.whats_new.url )
            }
            if( Data.device_limits != null ){
                Data.device_limits.each{
                    switch( it.key ){
                        case "camera":
                            ProcessState( "Max Cameras", it.value )
                            break
                        case "chime":
                            ProcessState( "Max Chimes", it.value )
                            break
                        case "doorbell_button":
                            ProcessState( "Max Doorbell Buttons", it.value )
                            break
                        case "doorbell":
                            ProcessState( "Max Doorbells", it.value )
                            break
                        case "owl":
                            ProcessState( "Max Owls", it.value ) // Owls are mini cameras & wired floodlights
                            break
                        case "siren":
                            ProcessState( "Max Sirens", it.value )
                            break
                        case "total_devices":
                            ProcessState( "Max All Devices", it.value )
                            break
                        // Unhandled device limit
                        default:
                            Logging( "Unhandled device data: ${ it.key } = ${ it.value }", 3 )
                            break
                    }
                }
            }
            ProcessEvent( "Status", "OK" )
            ProcessEvent( "Updated", "${ new Date() }" )
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 404:
            Logging( "Page not found, invalid URL", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink. Header = ${ resp.getHeaders() }", 5 )
            break
    }
}

//
def GetNotifications(){
    def Params
    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/notifications/configuration", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
    Logging( "Notifications Params = ${ Params.uri }", 4 )
    asynchttpGet( "ProcessNotifications", Params )
}

// Handle the response from the Notifications query
def ProcessNotifications( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Notification response = ${ resp.data }", 3 )
            ProcessEvent( "Status", "OK" )
            Data = parseJson( resp.data )
            Data.notifications.each{
                switch( it.key ){
                    case "low_battery":
                        ProcessState( "Low Battery Notification", it.value )
                        break
                    case "camera_offline":
                        ProcessState( "Camera Offline Notification", it.value )
                        break
                    case "camera_usage":
                        ProcessState( "Camera Usage Notification", it.value )
                        break
                    case "scheduling":
                        ProcessState( "Scheduling Notification", it.value )
                        break
                    case "motion":
                        ProcessState( "Motion Notification", it.value )
                        break
                    case "sync_module_offline":
                        ProcessState( "Sync Module Offline Notification", it.value )
                        break
                    case "temperature":
                        ProcessState( "Temperature Notification", it.value )
                        break
                    case "doorbell":
                        ProcessState( "Doorbell Notification", it.value )
                        break
                    case "wifi":
                        ProcessState( "WiFi Notification", it.value )
                        break
                    case "lfr":
                        ProcessState( "LFR Notification", it.value )
                        break
                    case "bandwidth":
                        ProcessState( "Bandwidth Notification", it.value )
                        break
                    case "battery_dead":
                        ProcessState( "Battery Dead Notification", it.value )
                        break
                    case "local_storage":
                        ProcessState( "Local Storage Notification", it.value )
                        break
                    // Unhandled data
                        Logging( "Unhandled notification: ${ it.key } = ${ it.value }", 3 )
                        break
                }
            }
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

//
def GetVideoEvents( VideoDate = null ){
    def Params
    def VideoAsOf
    if( VideoDate == null ){
        VideoAsOf = "1970-01-01T00:00:00+0000" // Gets all video events EVER for the account
    } else {
        VideoAsOf = VideoDate    
    }
    Params = [ uri: "https://rest-${ state.Tier }.immedia-semi.com/api/v1/accounts/${ state.AccountID }/media/changed?since=${ VideoAsOf }&page=1", headers: ["token-auth": "${ state.AuthToken }", 'User-Agent'  : "${ UserAgentValue() }" ] ]
    Logging( "Video Params = ${ Params.uri }", 4 )
    asynchttpGet( "ProcessVideoEvents", Params )
    //ProcessVideoEvents( "{\"limit\":500,\"purge_id\":2453075640,\"refresh_count\":0,\"media\":[{\"id\":2453807617,\"created_at\":\"2021-04-16T18:17:15+00:00\",\"updated_at\":\"2021-04-16T18:17:19+00:00\",\"deleted\":false,\"device\":\"camera\",\"device_id\":822025,\"device_name\":\"Sliding Door\",\"network_id\":101353,\"network_name\":\"Home\",\"type\":\"video\",\"source\":\"pir\",\"partial\":false,\"watched\":false,\"thumbnail\":\"/api/v2/accounts/2436/media/thumb/2453807617\",\"media\":\"/api/v2/accounts/2436/media/clip/2453807617.mp4\",\"metadata\":null,\"additional_devices\":[],\"time_zone\":\"America/New_York\"}]}" )
}

// Handle the response for Video Events
def ProcessVideoEvents( resp, data ){
    switch( resp.getStatus() ){
        case 200:
            Logging( "Video Event data = ${ resp.data }", 4 )
            ProcessEvent( "Status", "OK" )
            Data = parseJson( resp.data ) // change to resp.data before publishing
            ProcessState( "Video Limit", Data.limit as int )
            ProcessState( "Video Purge ID", Data.purge_id )
            ProcessState( "Video Refresh Count", Data.refresh_count as int )
            if( Data.media.size > 0 ){
                def MotionDetectionDuration
                switch( MotionDuration ){
                    case "5 minutes":
                        MotionDetectionDuration = 5
                        break
                    case "10 minutes":
                        MotionDetectionDuration = 10
                        break
                    case "15 minutes":
                        MotionDetectionDuration = 15
                        break
                    case "30 minutes":
                        MotionDetectionDuration = 30
                        break
                    case "1 hour":
                        MotionDetectionDuration = 60
                        break
                    case "3 hours":
                        MotionDetectionDuration = 180
                        break
                    default:
                        MotionDetectionDuration = 30
                        break    
                }
                Logging( "Motion Duration is: ${ MotionDetectionDuration } minutes", 4 )
                def TriggerMotionTime = new Date()
                TriggerMotionTime.setMinutes( TriggerMotionTime.getMinutes() - MotionDetectionDuration )
                Logging( "Trigger Motion time is: ${ TriggerMotionTime } or newer", 4 )
                def Triggers = []
                Data.media.each{
                    def TempUpdateDate = it.updated_at.split( 'T' )[ 0 ]
                    def TempUpdateTime = it.updated_at.split( 'T' )[ 1 ]
                    TempUpdateTime = TempUpdateTime.split( '\\+' )[ 0 ] + " GMT"
                    def UpdateTime = Date.parse( "yyyy-MM-dd'T'H:mm:ss z", "${ TempUpdateDate }T${ TempUpdateTime }" )
                    if( TriggerMotionTime < UpdateTime ){
                        if( state.Camera.indexOf( it.device_id ) != -1 ){
                            Logging( "Camera-${ it.device_id } triggered event at ${ UpdateTime }.", 4 )
                            if( Triggers.indexOf( it.device_id ) == -1 ){
                                PostEventToChild( "Camera-${ it.device_id }", "motion", "active" )
                                PostEventToChild( "Camera-${ it.device_id }", "Trigger Source", "${ it.source }" )
                                PostEventToChild( "Camera-${ it.device_id }", "Trigger ID", it.id )
                                PostEventToChild( "Camera-${ it.device_id }", "Trigger Time", "${ UpdateTime }" )
                                Triggers.add( it.device_id as int )
                            } else {
                                if( it.id > getChildDevice( "Camera-${ it.device_id }" ).ReturnState( "Trigger ID" ) ){
                                    PostEventToChild( "Camera-${ it.device_id }", "motion", "active" )
                                    PostEventToChild( "Camera-${ it.device_id }", "Trigger Source", "${ it.source }" )
                                    PostEventToChild( "Camera-${ it.device_id }", "Trigger ID", it.id )
                                    PostEventToChild( "Camera-${ it.device_id }", "Trigger Time", "${ UpdateTime }" )
                                }
                            }
                        } else if( state.Doorbell.indexOf( it.device_id ) != -1 ){
                            Logging( "Doorbell-${ it.device_id } triggered event at ${ UpdateTime }.", 4 )
                            if( Triggers.indexOf( it.device_id ) == -1 ){
                                PostEventToChild( "Doorbell-${ it.device_id }", "motion", "active" )
                                PostEventToChild( "Doorbell-${ it.device_id }", "Trigger Source", "${ it.source }" )
                                PostEventToChild( "Doorbell-${ it.device_id }", "Trigger ID", it.id )
                                PostEventToChild( "Doorbell-${ it.device_id }", "Trigger Time", "${ UpdateTime }" )
                                Triggers.add( it.device_id as int )
                            } else {
                                if( it.id > getChildDevice( "Doorbell-${ it.device_id }" ).ReturnState( "Trigger ID" ) ){
                                    PostEventToChild( "Doorbell-${ it.device_id }", "motion", "active" )
                                    PostEventToChild( "Doorbell-${ it.device_id }", "Trigger Source", "${ it.source }" )
                                    PostEventToChild( "Doorbell-${ it.device_id }", "Trigger ID", it.id )
                                    PostEventToChild( "Doorbell-${ it.device_id }", "Trigger Time", "${ UpdateTime }" )
                                }
                            }
                        } else if( state.MiniCamera.indexOf( it.device_id ) != -1 ){
                            Logging( "MiniCamera-${ it.device_id } triggered event at ${ UpdateTime }.", 4 )
                            if( Triggers.indexOf( it.device_id ) == -1 ){
                                PostEventToChild( "MiniCamera-${ it.device_id }", "motion", "active" )
                                PostEventToChild( "MiniCamera-${ it.device_id }", "Trigger Source", "${ it.source }" )
                                PostEventToChild( "MiniCamera-${ it.device_id }", "Trigger ID", it.id )
                                PostEventToChild( "MiniCamera-${ it.device_id }", "Trigger Time", "${ UpdateTime }" )
                                Triggers.add( it.device_id as int )
                            } else {
                                if( it.id > getChildDevice( "MiniCamera-${ it.device_id }" ).ReturnState( "Trigger ID" ) ){
                                    PostEventToChild( "MiniCamera-${ it.device_id }", "motion", "active" )
                                    PostEventToChild( "MiniCamera-${ it.device_id }", "Trigger Source", "${ it.source }" )
                                    PostEventToChild( "MiniCamera-${ it.device_id }", "Trigger ID", it.id )
                                    PostEventToChild( "MiniCamera-${ it.device_id }", "Trigger Time", "${ UpdateTime }" )
                                }
                            }
                        } else if( state.WiredFloodlight.indexOf( it.device_id ) != -1 ){
                            Logging( "WiredFloodlight-${ it.device_id } triggered event at ${ UpdateTime }.", 4 )
                            if( Triggers.indexOf( it.device_id ) == -1 ){
                                PostEventToChild( "WiredFloodlight-${ it.device_id }", "motion", "active" )
                                PostEventToChild( "WiredFloodlight-${ it.device_id }", "Trigger Source", "${ it.source }" )
                                PostEventToChild( "WiredFloodlight-${ it.device_id }", "Trigger ID", it.id )
                                PostEventToChild( "WiredFloodlight-${ it.device_id }", "Trigger Time", "${ UpdateTime }" )
                                Triggers.add( it.device_id as int )
                            } else {
                                if( it.id > getChildDevice( "WiredFloodlight-${ it.device_id }" ).ReturnState( "Trigger ID" ) ){
                                    PostEventToChild( "WiredFloodlight-${ it.device_id }", "motion", "active" )
                                    PostEventToChild( "WiredFloodlight-${ it.device_id }", "Trigger Source", "${ it.source }" )
                                    PostEventToChild( "WiredFloodlight-${ it.device_id }", "Trigger ID", it.id )
                                    PostEventToChild( "WiredFloodlight-${ it.device_id }", "Trigger Time", "${ UpdateTime }" )
                                }
                            }
                        } else {
                            Logging( "Unknown (non-Camera-related) Device ${ it.device_id } is the culprit.", 4 )
                        }
                    }
                }
                Logging( "Triggers were: ${ Triggers }", 4 )
                state.Camera.each{
                    if( Triggers.indexOf( it ) == -1 ){
                        PostEventToChild( "Camera-${ it }", "motion", "inactive" )
                    }
                }
                state.Doorbell.each{
                    if( Triggers.indexOf( it ) == -1 ){
                        PostEventToChild( "Doorbell-${ it }", "motion", "inactive" )
                    }
                }
                state.MiniCamera.each{
                    if( Triggers.indexOf( it ) == -1 ){
                        PostEventToChild( "MiniCamera-${ it }", "motion", "inactive" )
                    }
                }
                state.WiredFloodlight.each{
                    if( Triggers.indexOf( it ) == -1 ){
                        PostEventToChild( "WiredFloodlight-${ it }", "motion", "inactive" )
                    }
                }
            } else {
                state.Camera.each{
                    PostEventToChild( "Camera-${ it }", "motion", "inactive" )
                }
                state.Doorbell.each{
                    PostEventToChild( "Doorbell-${ it }", "motion", "inactive" )
                }
                state.MiniCamera.each{
                    PostEventToChild( "MiniCamera-${ it }", "motion", "inactive" )
                }
                state.WiredFloodlight.each{
                    PostEventToChild( "WiredFloodlight-${ it }", "motion", "inactive" )
                }
            }
            break
        case 401:
            Logging( "Unauthorized", 5 )
            break
        case 408:
            Logging( "Request time out", 5 )
            break
        default:
            Logging( "${ resp.getStatus() } from Blink", 5 )
            break
    }
}

// Process device data received, like sync modules or cameras
def ProcessDevices( ModuleType, Data, ModuleID = null ){
    def ID
    if( ModuleID == null ){
        ID = Data.id as int
    } else {
        ID = ModuleID as int
    }
    Logging( "Processing ${ ModuleType }-${ ID } Data = ${ Data }", 4 )
    PostEventToChild( "${ ModuleType }-${ ID }", "ID", ID )
    def Found = false
    state."${ ModuleType }".each{ // Do NOT want to iterate through list checking, but list find command failed a lot (false negatives)
        if( it == ID ){
            Found = true
        }
    }
    if( Found == false ){
        if( state."${ ModuleType }" != null ){
            def Temp = state."${ ModuleType }".plus( ID )
            Logging( "Did not find ${ ID }", 4 )
            ProcessState( "${ ModuleType }", Temp )
        } else {
            ProcessState( "${ ModuleType }", [ ID ] )
        }
    }
    // Make sure that the list of the IDs for the Module is correct, and only has unique values
    def TempList = []
    state."${ ModuleType }".each{
        TempList.add( it as int )
    }
    ProcessState( "${ ModuleType }", TempList.unique() )
    Logging( "${ ModuleType } List = ${ state."${ ModuleType }" }", 4 )
    
    Data.each{
        switch( it.key ){
            case "status":
                PostEventToChild( "${ ModuleType }-${ ID }", "Status", "${ it.value }" )
                break
            case "name":
                PostEventToChild( "${ ModuleType }-${ ID }", "Name", "${ it.value }" )
                if( CustomLabels ){
                    if( getChildDevice( "${ ModuleType }-${ ID }" ).label == null ){
                        getChildDevice( "${ ModuleType }-${ ID }" ).label = "${ it.value }"
                    }
                } else {
                    if( getChildDevice( "${ ModuleType }-${ ID }" ).label != "${ it.value }" ){
                        getChildDevice( "${ ModuleType }-${ ID }" ).label = "${ it.value }"
                    }
                }
                break
            case "camera_name":
                PostEventToChild( "${ ModuleType }-${ ID }", "Name", "${ it.value }" )
                if( CustomLabels ){
                    if( getChildDevice( "${ ModuleType }-${ ID }" ).label == null ){
                        getChildDevice( "${ ModuleType }-${ ID }" ).label = "${ it.value }"
                    }
                } else {
                    if( getChildDevice( "${ ModuleType }-${ ID }" ).label != "${ it.value }" ){
                        getChildDevice( "${ ModuleType }-${ ID }" ).label = "${ it.value }"
                    }
                }
                break
            case "temperature":
            case "temp":
                PostEventToChild( "${ ModuleType }-${ ID }", "temperature", ConvertTemperature( "F", it.value ), "°${ location.getTemperatureScale() }" )
                break
            case "server":
                if( ModuleType == "Camera" ){
                    PostEventToChild( "${ ModuleType }-${ ID }", "LiveView RTSP", it.value )
                } else {
                    PostStateToChild( "${ ModuleType }-${ ID }", "Server", it.value )
                }
                break
            case "type":
                PostEventToChild( "${ ModuleType }-${ ID }", "Type", "${ it.value }" )
                break
            case "battery_state":
                PostEventToChild( "${ ModuleType }-${ ID }", "Battery Status", it.value )
                break
            case "battery":
                PostEventToChild( "${ ModuleType }-${ ID }", "Battery Value", it.value )
                if( getChildDevice( "${ ModuleType }-${ ID }" ).ReturnState( "battery" ) == null ){
                    if( it.value == "ok" ){
                        PostEventToChild( "${ ModuleType }-${ ID }", "battery", 75, "%" )
                    } else {
                        PostEventToChild( "${ ModuleType }-${ ID }", "battery", 0, "%" )
                    }
                }
                break
            case "battery_voltage":
                PostEventToChild( "${ ModuleType }-${ ID }", "Battery Voltage", it.value )
                switch( it.value ){ // All values below are placeholder estimates at this time until more data is received
                    case 0..130:
                        PostEventToChild( "${ ModuleType }-${ ID }", "battery", 0, "%" )
                        break
                    case 131..140:
                        PostEventToChild( "${ ModuleType }-${ ID }", "battery", 10, "%" )
                        break
                    case 141..150:
                        PostEventToChild( "${ ModuleType }-${ ID }", "battery", 25, "%" )
                        break
                    case 151..160:
                        PostEventToChild( "${ ModuleType }-${ ID }", "battery", 50, "%" )
                        break
                    case 161..170:
                        PostEventToChild( "${ ModuleType }-${ ID }", "battery", 75, "%" )
                        break
                    case 171..180:
                        PostEventToChild( "${ ModuleType }-${ ID }", "battery", 90, "%" )
                        break
                    case { it > 180 }:
                        PostEventToChild( "${ ModuleType }-${ ID }", "battery", 100, "%" )
                        break
                    default:
                        Logging( "Unknown battery_voltage value = ${ it.value }", 3 )
                        break
                }
                break
            case "issues":
                PostEventToChild( "${ ModuleType }-${ ID }", "Issues", "${ it.value }" )
                break
            case "armed":
                PostEventToChild( "${ ModuleType }-${ ID }", "Armed", "${ it.value }" )
                def ArmedValue = "${ it.value }"
                if( ModuleType == "Network" ){
                    def TempArmedSystems = []
                    if( ArmedValue == "false" ){
                        if( state."Armed System(s)" != null ){
                            state."Armed System(s)".each{
                                if( ( it.value as int ) != ( ID as int ) ){
                                    TempArmedSystems.add( it.value as int )
                                }
                            }
                        }
                    } else {
                        TempArmedSystems = [ ID as int ]
                        if( state."Armed System(s)" != null ){
                            state."Armed System(s)".each{
                                TempArmedSystems.add( it.value as int )
                            }
                        }
                    }
                    TempArmedSystems = TempArmedSystems.unique()
                    ProcessEvent( "Armed System(s)", TempArmedSystems )
                    if( state.Camera != null ){
                        state.Camera.each{
                            if( getChildDevice( "Camera-${ it }" ) != null ){
                                if( getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" ) != null ){
                                    if( ( ID as int ) == ( getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" ) as int ) ){
                                        PostEventToChild( "Camera-${ it }", "Armed", ArmedValue )
                                        if( ArmedValue ){
                                            PostEventToChild( "Camera-${ it }", "Arm String", "Armed" )
                                        } else {
                                            PostEventToChild( "Camera-${ it }", "Arm String", "Disarmed" )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if( state.MiniCamera != null ){
                        state.MiniCamera.each{
                            if( getChildDevice( "MiniCamera-${ it }" ) != null ){
                                if( getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" ) != null ){
                                    if( ( ID as int ) == ( getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" ) as int ) ){
                                        PostEventToChild( "MiniCamera-${ it }", "Armed", ArmedValue )
                                        if( ArmedValue ){
                                            PostEventToChild( "MiniCamera-${ it }", "switch", "on" )
                                            PostEventToChild( "MiniCamera-${ it }", "Arm String", "Armed" )
                                        } else {
                                            PostEventToChild( "MiniCamera-${ it }", "switch", "off" )
                                            PostEventToChild( "MiniCamera-${ it }", "Arm String", "Disarmed" )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if( state.WiredFloodlight != null ){
                        state.WiredFloodlight.each{
                            if( getChildDevice( "WiredFloodlight-${ it }" ) != null ){
                                if( getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" ) != null ){
                                    if( ( ID as int ) == ( getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" ) as int ) ){
                                        PostEventToChild( "WiredFloodlight-${ it }", "Armed", ArmedValue )
                                        if( ArmedValue ){
                                            PostEventToChild( "WiredFloodlight-${ it }", "switch", "on" )
                                            PostEventToChild( "WiredFloodlight-${ it }", "Arm String", "Armed" )
                                        } else {
                                            PostEventToChild( "WiredFloodlight-${ it }", "switch", "off" )
                                            PostEventToChild( "WiredFloodlight-${ it }", "Arm String", "Disarmed" )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if( it.value ){
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "on" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Armed" )
                    } else {
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "off" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Disarmed" )
                    }
                } else if( ModuleType == "WiredFloodlight" ){
                    if( it.value ){
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "on" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Armed" )
                    } else {
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "off" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Disarmed" )
                    }
                } else if( ModuleType == "MiniCamera" ){
                    if( it.value ){
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "on" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Armed" )
                    } else {
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "off" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Disarmed" )
                    }
                } else if( ModuleType == "Camera" ){
                    if( it.value ){
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Armed" )
                    } else {
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Disarmed" )
                    }
                }
                break
            case "autoarm_geo_enable":
                PostEventToChild( "${ ModuleType }-${ ID }", "Auto Arm By Geo", "${ it.value }" )
                break
            case "autoarm_time_enable":
                PostEventToChild( "${ ModuleType }-${ ID }", "Auto Arm By Time", "${ it.value }" )
                break
            case "camera_error":
                PostEventToChild( "${ ModuleType }-${ ID }", "Camera Error", "${ it.value }" )
                break
            case "sync_module_error":
                PostEventToChild( "${ ModuleType }-${ ID }", "Sync Module Error", "${ it.value }" )
                break
            case "arm_string":
                PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", it.value )
                def ArmedValue = "${ it.value }"
                if( ModuleType == "Network" ){
                    def Temp = []
                    if( it.value == false ){
                        Temp = state."Armed System(s)".removeElement( ID )
                    } else {
                        if( state."Armed System(s)" == null ){
							Temp = [ ID ]
						} else {
							Temp = state."Armed System(s)".plus( [ ID ] )
							Temp = Temp.unique()
						}
                    }
                    if( !Temp ){
                        ProcessEvent( "Armed System(s)", [] )
                    } else {
                        ProcessEvent( "Armed System(s)", Temp )
                    }
                    if( state.Camera != null ){
                        state.Camera.each{
                            if( getChildDevice( "Camera-${ it }" ) != null ){
                                if( ( getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" ) as int ) != null ){
                                    if( ( ID as int ) == ( getChildDevice( "Camera-${ it }" ).ReturnState( "Network ID" ) as int ) ){
                                        PostEventToChild( "Camera-${ it }", "Arm String", ArmedValue )
                                    }
                                }
                            }
                        }
                    }
                    if( state.MiniCamera != null ){
                        state.MiniCamera.each{
                            if( getChildDevice( "MiniCamera-${ it }" ) != null ){
                                if( ( getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" ) as int ) != null ){
                                    if( ( ID as int ) == ( getChildDevice( "MiniCamera-${ it }" ).ReturnState( "Network ID" ) as int ) ){
                                        PostEventToChild( "MiniCamera-${ it }", "Arm String", ArmedValue )
                                    }
                                }
                            }
                        }
                    }
                    if( state.WiredFloodlight != null ){
                        state.WiredFloodlight.each{
                            if( getChildDevice( "WiredFloodlight-${ it }" ) != null ){
                                if( ( getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" ) as int ) != null ){
                                    if( ( ID as int ) == ( getChildDevice( "WiredFloodlight-${ it }" ).ReturnState( "Network ID" ) as int ) ){
                                        PostEventToChild( "WiredFloodlight-${ it }", "Arm String", ArmedValue )
                                    }
                                }
                            }
                        }
                    }
                } else if( ModuleType == "WiredFloodlight" ){
                    if( it.value ){
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "on" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Armed" )
                    } else {
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "off" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Disarmed" )
                    }
                } else if( ModuleType == "MiniCamera" ){
                    if( it.value ){
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "on" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Armed" )
                    } else {
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "off" )
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Disarmed" )
                    }
                } else if( ModuleType == "Camera" ){
                    if( it.value ){
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Armed" )
                    } else {
                        PostEventToChild( "${ ModuleType }-${ ID }", "Arm String", "Disarmed" )
                    }
                }
                break
            case "video_count":
                PostEventToChild( "${ ModuleType }-${ ID }", "Video Count", it.value )
                break
            case "thumbnail":
                if( it.value != null ){
                    def Temp
                    if( it.value.find( "ts=" ) ){
                        Temp = it.value
                        PostEventToChild( "${ ModuleType }-${ ID }", "Thumbnail File", "${ Temp }" )
                        Logging( "New Style Thumbnail = ${ Temp }", 4 )
                    } else {
                        Temp = it.value.split( '/' )
                        Temp = Temp[ Temp.size() - 1 ]
                        //PostEventToChild( "${ ModuleType }-${ ID }", "Thumbnail File", "${ Temp }.jpg" )
                        Logging( "Ignoring Old Style Thumbnail = ${ Temp }.jpg", 4 )
                    }
                } else {
                    Logging( "Thumbnail File Name = none", 4 )
                    PostEventToChild( "${ ModuleType }-${ ID }", "Thumbnail File", null )
                }
                break
            case "enabled":
                if( ModuleType == "Camera" ){
                    PostEventToChild( "${ ModuleType }-${ ID }", "Motion Detection Enabled", "${ it.value }" )
                    if( it.value ){
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "on" )
                    } else {
                        PostEventToChild( "${ ModuleType }-${ ID }", "switch", "off" )
                    }
                } else {
                    PostEventToChild( "${ ModuleType }-${ ID }", "Enabled", "${ it.value }" )
                }
            case "motion_alert":
                PostEventToChild( "${ ModuleType }-${ ID }", "Motion Detection Enabled", "${ it.value }" )
                break
            case "deleted_at":
                PostEventToChild( "${ ModuleType }-${ ID }", "Deleted", it.value )
                break
            case "last_wifi_alert":
                PostEventToChild( "${ ModuleType }-${ ID }", "Last Wifi Alert", it.value )
                break
            case "last_offline_alert":
                PostEventToChild( "${ ModuleType }-${ ID }", "Last Offline Alert", it.value )
                break
            case "last_battery_alert":
                PostEventToChild( "${ ModuleType }-${ ID }", "Last Battery Alert", it.value )
                break
            case "battery_alert_status":
                PostEventToChild( "${ ModuleType }-${ ID }", "Battery Alert Status", it.value )
                break
            case "network":
            case "network_id":
                PostEventToChild( "${ ModuleType }-${ ID }", "Network ID", "${ it.value }" )
                break
            case "feature_plan_id":
                PostEventToChild( "${ ModuleType }-${ ID }", "Feature Plan ID", it.value )
                break
            case "account":
            case "account_id":
                PostEventToChild( "${ ModuleType }-${ ID }", "Account ID", it.value )
                break
            case "command_id":
                PostEventToChild( "${ ModuleType }-${ ID }", "Last Command ID", it.value )
                break
            case "media_id":
                PostEventToChild( "${ ModuleType }-${ ID }", "Media ID", it.value )
                break
            case "sync_module_id":
                PostEventToChild( "${ ModuleType }-${ ID }", "Sync Module ID", it.value )
                break
            // Things that do not need to trigger Events
            case "updated_at":
                PostStateToChild( "${ ModuleType }-${ ID }", "API Data Updated", "${ it.value }" )
                break
            case "usage_rate":
                PostStateToChild( "${ ModuleType }-${ ID }", "Usage Rate", "${ it.value }" )
                break
            case "enable_temp_alerts":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temp Alerts Enabled", "${ it.value }" )
                break
            case "wifi":
            case "wifi_strength":
                PostStateToChild( "${ ModuleType }-${ ID }", "WiFi Strength", it.value as int )
                break
            case "lfr":
            case "lfr_strength":
                PostStateToChild( "${ ModuleType }-${ ID }", "LFR Strength", it.value as int )
                break
            case "max_resolution":
                PostStateToChild( "${ ModuleType }-${ ID }", "Max Resolution", "${ it.value }" )
                break
            case "motion_sensitivity":
                PostStateToChild( "${ ModuleType }-${ ID }", "Motion Sensitivity", "${ it.value }" )
                break
            case "local_storage_compatible":
				PostStateToChild( "${ ModuleType }-${ ID }", "Local Storage Compatible", "${ it.value }" )
                break
            case "local_storage_enabled":
                PostStateToChild( "${ ModuleType }-${ ID }", "Local Storage Enabled", "${ it.value }" )
                break
            case "local_storage_status":
                PostStateToChild( "${ ModuleType }-${ ID }", "Local Storage Status", "${ it.value }" )
                break
            case "created_at":
                PostStateToChild( "${ ModuleType }-${ ID }", "Created", "${ it.value }" )
                break
            case "onboarded":
                PostStateToChild( "${ ModuleType }-${ ID }", "Onboarded", "${ it.value }" )
                break
            case "serial":
                PostStateToChild( "${ ModuleType }-${ ID }", "Serial", "${ it.value }" )
                break
            case "fw_version":
                PostStateToChild( "${ ModuleType }-${ ID }", "Firmware Version", "${ it.value }" )
                break
            case "last_hb":
                PostStateToChild( "${ ModuleType }-${ ID }", "Last HB", "${ it.value }" )
                break
            case "dst":
                PostStateToChild( "${ ModuleType }-${ ID }", "Daylight Savings Time", "${ it.value }" )
                break
            case "sm_backup_enabled":
                PostStateToChild( "${ ModuleType }-${ ID }", "SM Backup Enabled", "${ it.value }" )
                break
            case "storage_total":
                PostStateToChild( "${ ModuleType }-${ ID }", "Storage Total", it.value )
                break
            case "lv_mode":
                PostStateToChild( "${ ModuleType }-${ ID }", "LiveView Mode", it.value )
                break
            case "video_destination":
                PostStateToChild( "${ ModuleType }-${ ID }", "Video Description", it.value )
                break
            case "ping_interval":
                PostStateToChild( "${ ModuleType }-${ ID }", "Ping Interval", it.value )
                break
            case "network_key":
                PostStateToChild( "${ ModuleType }-${ ID }", "Network Key", it.value )
                break
            case "busy":
                PostStateToChild( "${ ModuleType }-${ ID }", "Busy", "${ it.value }" )
                break
            case "network_origin":
                PostStateToChild( "${ ModuleType }-${ ID }", "Network Origin", it.value )
                break
            case "time_zone":
                PostStateToChild( "${ ModuleType }-${ ID }", "Time Zone", it.value )
                break
            case "lfr_channel":
                PostStateToChild( "${ ModuleType }-${ ID }", "LFR Channel", it.value )
                break
            case "encryption_key":
                PostStateToChild( "${ ModuleType }-${ ID }", "Encryption Key", it.value )
                break
            case "video_history_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "Video History Count", it.value )
                break
            case "storage_used":
                PostStateToChild( "${ ModuleType }-${ ID }", "Storage Used", it.value )
                break
            case "usage":
                PostStateToChild( "${ ModuleType }-${ ID }", "Usage", it.value )
                break
            case "lv_seconds":
                PostStateToChild( "${ ModuleType }-${ ID }", "LiveView Seconds", it.value )
                break
            case "clip_seconds":
                PostStateToChild( "${ ModuleType }-${ ID }", "Clip Seconds", it.value )
                break
            case "locale":
                PostStateToChild( "${ ModuleType }-${ ID }", "Locale", it.value )
                break
            case "description":
                PostStateToChild( "${ ModuleType }-${ ID }", "Description", it.value )
                break
            case "battery_alert_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "Battery Alert Count", it.value )
                break
            case "alert_repeat":
                PostStateToChild( "${ ModuleType }-${ ID }", "Alert Repeat", it.value )
                break
            case "liveview_bitrate":
                PostStateToChild( "${ ModuleType }-${ ID }", "Liveview Bitrate", it.value )
                break
            case "temp_alert_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temperature Alert Count", it.value )
                break
            case "lfr_alert_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "LFR Alert Count", it.value )
                break
            case "video_recording_optional":
                PostStateToChild( "${ ModuleType }-${ ID }", "Video Recording Optional", "${ it.value }" )
                break
            case "alert_tone_enable":
                PostStateToChild( "${ ModuleType }-${ ID }", "Alert Tone Enable", "${ it.value }" )
                break
            case "clip_bitrate":
                PostStateToChild( "${ ModuleType }-${ ID }", "Clip Bitrate", it.value )
                break
            case "privacy_zones_compatible":
                PostStateToChild( "${ ModuleType }-${ ID }", "Privacy Zones Compatible", "${ it.value }" )
                break
            case "wifi_timeout":
                PostStateToChild( "${ ModuleType }-${ ID }", "WiFi Timeout", it.value )
                break
            case "last_snapshot_event":
                PostStateToChild( "${ ModuleType }-${ ID }", "Last Snapshot Event", it.value )
                break
            case "illuminator_intensity":
                PostStateToChild( "${ ModuleType }-${ ID }", "Illuminator Intensity", it.value )
                break
            case "illuminator_duration":
                PostStateToChild( "${ ModuleType }-${ ID }", "Illuminator Duration", it.value )
                break
            case "temp_adjust":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temperature Adjust", it.value )
                break
            case "video_length":
                PostStateToChild( "${ ModuleType }-${ ID }", "Video Length", it.value )
                break
            case "record_audio_enable":
                PostStateToChild( "${ ModuleType }-${ ID }", "Record Audio Enable", "${ it.value }" )
                break
            case "video_quality":
                PostStateToChild( "${ ModuleType }-${ ID }", "Video Quality", it.value )
                break
            case "mfg_mez_type":
                PostStateToChild( "${ ModuleType }-${ ID }", "MFG Mez Type", it.value )
                break
            case "alert_interval":
                PostStateToChild( "${ ModuleType }-${ ID }", "Alert Interval", it.value )
                break
            case "clip_warning_threshold":
                PostStateToChild( "${ ModuleType }-${ ID }", "Clip Warning Threshold", it.value )
                break
            case "snapshot_enabled":
                PostStateToChild( "${ ModuleType }-${ ID }", "Snapshot Enabled", "${ it.value }" )
                break
            case "battery_check_time":
                PostStateToChild( "${ ModuleType }-${ ID }", "Battery Check Time", it.value )
                break
            case "last_backup_started":
                PostStateToChild( "${ ModuleType }-${ ID }", "Last Backup Started", it.value )
                break
            case "table_update_sequence":
                PostStateToChild( "${ ModuleType }-${ ID }", "Table Update Sequence", it.value )
                break
            case "last_activity":
                PostStateToChild( "${ ModuleType }-${ ID }", "Last Activity", it.value )
                break
            case "wifi_alert_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "Wifi Alert Count", it.value )
                break
            case "backfill_in_progress":
                PostStateToChild( "${ ModuleType }-${ ID }", "Backfill In Progress", it.value )
                break
            case "last_backfill_completed":
                PostStateToChild( "${ ModuleType }-${ ID }", "Last Backfill Completed", it.value )
                break
            case "os_version":
                PostStateToChild( "${ ModuleType }-${ ID }", "OS Version", it.value )
                break
            case "lfr_frequency":
                PostStateToChild( "${ ModuleType }-${ ID }", "LFR Frequency", it.value )
                break
            case "offline_alert_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "Offline Alert Count", it.value )
                break
            case "last_backup_completed":
                PostStateToChild( "${ ModuleType }-${ ID }", "Last Backup Completed", it.value )
                break
            case "duration":
                PostStateToChild( "${ ModuleType }-${ ID }", "LiveView Duration", it.value )
                break
            case "continue_interval":
                PostStateToChild( "${ ModuleType }-${ ID }", "LiveView Continue Interval", it.value )
                break
            case "join_state":
                PostStateToChild( "${ ModuleType }-${ ID }", "Join State", it.value )
                break
            case "options":
                PostStateToChild( "${ ModuleType }-${ ID }", "Options", it.value )
                break
            case "continue_warning":
                PostStateToChild( "${ ModuleType }-${ ID }", "Continue Warning", it.value )
                break
            case "submit_logs":
                PostStateToChild( "${ ModuleType }-${ ID }", "Submit Logs", "${ it.value }" )
                break
            case "join_available":
                PostStateToChild( "${ ModuleType }-${ ID }", "Join Available", "${ it.value }" )
                break
            case "new_command":
                PostStateToChild( "${ ModuleType }-${ ID }", "New Command", "${ it.value }" )
                break
            case "motion_regions_compatible":
                PostStateToChild( "${ ModuleType }-${ ID }", "Motion Regions Compatible", "${ it.value }" )
                break
            case "liveview_rate":
                PostStateToChild( "${ ModuleType }-${ ID }", "LiveView Rate", it.value )
                break
            case "early_notification_compatible":
                PostStateToChild( "${ ModuleType }-${ ID }", "Early Notification Compatible", "${ it.value }" )
                break
            case "early_notification":
                PostStateToChild( "${ ModuleType }-${ ID }", "Early Notification", "${ it.value }" )
                break
            case "invert_image":
                PostStateToChild( "${ ModuleType }-${ ID }", "Invert Image", "${ it.value }" )
                break
            case "video_recording_enable":
                PostStateToChild( "${ ModuleType }-${ ID }", "Video Recording Enable", "${ it.value }" )
                break
            case "mfg_main_type":
                PostStateToChild( "${ ModuleType }-${ ID }", "MFG Main Type", it.value )
                break
            case "retry_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "Retry Count", it.value )
                break
            case "clip_rate":
                PostStateToChild( "${ ModuleType }-${ ID }", "Clip Rate", it.value )
                break
            case "liveview_enabled":
                PostStateToChild( "${ ModuleType }-${ ID }", "LiveView Enabled", "${ it.value }" )
                break
            case "video_50_60hz":
                PostStateToChild( "${ ModuleType }-${ ID }", "Video hz", it.value )
                break
            case "last_temp_alert":
                PostStateToChild( "${ ModuleType }-${ ID }", "Last Temp Alert", it.value )
                break
            case "mfg_main_range":
                PostStateToChild( "${ ModuleType }-${ ID }", "MFG Main Range", it.value )
                break
            case "record_audio":
                PostStateToChild( "${ ModuleType }-${ ID }", "Record Audio", "${ it.value }" )
                break
            case "auto_test":
                PostStateToChild( "${ ModuleType }-${ ID }", "Auto Test", "${ it.value }" )
                break
            case "early_termination":
                PostStateToChild( "${ ModuleType }-${ ID }", "Early Termination", "${ it.value }" )
                break
            case "temp_alarm_enable":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temp Alarm Enable", "${ it.value }" )
                break
            case "flip_video":
                PostStateToChild( "${ ModuleType }-${ ID }", "Flip Video", "${ it.value }" )
                break
            case "snapshot_compatible":
                PostStateToChild( "${ ModuleType }-${ ID }", "Snapshot Compatible", "${ it.value }" )
                break
            case "flip_image":
                PostStateToChild( "${ ModuleType }-${ ID }", "Flip Image", "${ it.value }" )
                break
            case "temp_alert_state":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temp Alert State", it.value )
                break
            case "siren_volume":
                PostStateToChild( "${ ModuleType }-${ ID }", "Siren Volume", it.value )
                break
            case "temp_hysteresis":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temp Hysteresis", it.value )
                break
            case "temp_max":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temp Max", it.value )
                break
            case "temp_min":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temp Min", it.value )
                break
            case "battery_voltage_threshold":
                PostStateToChild( "${ ModuleType }-${ ID }", "Battery Voltage Threshold", it.value )
                break
            case "camera_key":
                PostStateToChild( "${ ModuleType }-${ ID }", "Camera Key", it.value )
                break
            case "camera_seq":
                PostStateToChild( "${ ModuleType }-${ ID }", "Camera Seq", it.value )
                break
            case "night_vision_exposure_compatible":
                PostStateToChild( "${ ModuleType }-${ ID }", "Night Vision Exposure Compatible", "${ it.value }" )
                break
            case "illuminator_enable":
                PostStateToChild( "${ ModuleType }-${ ID }", "Illuminator Enable", it.value )
                break
            case "motion_regions":
                PostStateToChild( "${ ModuleType }-${ ID }", "Motion Regions", it.value )
                break
            case "battery_voltage_hysteresis":
                PostStateToChild( "${ ModuleType }-${ ID }", "Battery Voltage Hysteresis", it.value )
                break
            case "siren_enable":
                PostStateToChild( "${ ModuleType }-${ ID }", "Siren Enable", "${ it.value }" )
                break
            case "battery_alarm_enable":
                PostStateToChild( "${ ModuleType }-${ ID }", "Battery Alarm Enable", "${ it.value }" )
                break
            case "buzzer_on":
                PostStateToChild( "${ ModuleType }-${ ID }", "Buzzer On", "${ it.value }" )
                break
            case "battery_voltage_interval":
                PostStateToChild( "${ ModuleType }-${ ID }", "Battery Voltage Interval", it.value )
                break
            case "early_pir_compatible":
                PostStateToChild( "${ ModuleType }-${ ID }", "Early PIR Compatible", "${ it.value }" )
                break
            case "alert_tone_volume":
                PostStateToChild( "${ ModuleType }-${ ID }", "Alert Tone Volume", it.value )
                break
            case "night_vision_exposure":
                PostStateToChild( "${ ModuleType }-${ ID }", "Night Vision Exposure", it.value )
                break
            case "snapshot_period_minutes_options":
                PostStateToChild( "${ ModuleType }-${ ID }", "Snapshot Period Minutes Options", it.value )
                break
            case "last_lfr_alert":
                PostStateToChild( "${ ModuleType }-${ ID }", "Last LFR Alert", it.value )
                break
            case "temp_interval":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temp Interval", it.value )
                break
            case "lfr_sync_interval":
                PostStateToChild( "${ ModuleType }-${ ID }", "LFR Sync Interval", it.value )
                break
            case "clip_max_length":
                PostStateToChild( "${ ModuleType }-${ ID }", "Clip Max Length", it.value )
                break
            case "unit_number":
                PostStateToChild( "${ ModuleType }-${ ID }", "Unit Number", it.value )
                break
            case "mfg_mez_range":
                PostStateToChild( "${ ModuleType }-${ ID }", "MFG Mez Range", it.value )
                break
            case "early_termination_supported":
                PostStateToChild( "${ ModuleType }-${ ID }", "Early Termination Supported", "${ it.value }" )
                break
            case "flip_video_compatible":
                PostStateToChild( "${ ModuleType }-${ ID }", "Flip Video Compatible", "${ it.value }" )
                break
            case "total_108_wakeups":
                PostStateToChild( "${ ModuleType }-${ ID }", "Total 108 Wakeups", it.value )
                break
            case "lfr_tb_wakeups":
                PostStateToChild( "${ ModuleType }-${ ID }", "LFR TB Wakeups", it.value )
                break
            case "total_tb_wakeups":
                PostStateToChild( "${ ModuleType }-${ ID }", "Total TB Wakeups", it.value )
                break
            case "lfr_108_wakeups":
                PostStateToChild( "${ ModuleType }-${ ID }", "LFR 108 Wakeups", it.value )
                break
            case "light_sensor_data_valid":
                PostStateToChild( "${ ModuleType }-${ ID }", "Light Sensor Data Valid", "${ it.value }" )
                break
            case "fw_git_hash":
                PostStateToChild( "${ ModuleType }-${ ID }", "FW Git Hash", it.value )
                break
            case "light_sensor_ch0":
                PostStateToChild( "${ ModuleType }-${ ID }", "Light Sensor CH0", it.value )
                break
            case "light_sensor_ch1":
                PostStateToChild( "${ ModuleType }-${ ID }", "Light Sensor CH1", it.value )
                break
            case "mac":
                PostStateToChild( "${ ModuleType }-${ ID }", "MAC Address", it.value )
                break
            case "time_dhcp_lease":
                PostStateToChild( "${ ModuleType }-${ ID }", "Time DHCP Lease", it.value )
                break
            case "time_first_video":
                PostStateToChild( "${ ModuleType }-${ ID }", "Time First Video", it.value )
                break
            case "time_dns_resolve":
                PostStateToChild( "${ ModuleType }-${ ID }", "Time DNS Resolve", it.value )
                break
            case "temp_alert_status":
                PostStateToChild( "${ ModuleType }-${ ID }", "Temp Alert Status", "${ it.value }" )
                break
            case "time_108_boot":
                PostStateToChild( "${ ModuleType }-${ ID }", "Time 108 Boot", it.value )
                break
            case "lifetime_duration":
                PostStateToChild( "${ ModuleType }-${ ID }", "Lifetime Duration", it.value )
                break
            case "wifi_connect_failure_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "WiFi Connect Failure Count", it.value )
                break
            case "camera_id":
                PostStateToChild( "${ ModuleType }-${ ID }", "Camera ID", it.value )
                break
            case "dhcp_failure_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "DHCP Failure Count", it.value )
                break
            case "ipv":
                PostStateToChild( "${ ModuleType }-${ ID }", "IPv", it.value )
                break
            case "light_sensor_data_new":
                PostStateToChild( "${ ModuleType }-${ ID }", "Light Sensor Data New", "${ it.value }" )
                break
            case "dev_1":
                PostStateToChild( "${ ModuleType }-${ ID }", "Dev 1", it.value )
                break
            case "dev_2":
                PostStateToChild( "${ ModuleType }-${ ID }", "Dev 2", it.value )
                break
            case "dev_3":
                PostStateToChild( "${ ModuleType }-${ ID }", "Dev 3", it.value )
                break
            case "time_wlan_connect":
                PostStateToChild( "${ ModuleType }-${ ID }", "Time WLAN Connect", it.value )
                break
            case "socket_failure_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "Socket Failure Count", it.value )
                break
            case "pir_rejections":
                PostStateToChild( "${ ModuleType }-${ ID }", "PIR Rejections", it.value )
                break
            case "lifetime_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "Lifetime Count", it.value )
                break
            case "error_codes":
                PostStateToChild( "${ ModuleType }-${ ID }", "Error Codes", it.value )
                break
            case "ac_power":
                PostStateToChild( "${ ModuleType }-${ ID }", "AC Power", "${ it.value }" )
                break
            case "snapshot_period_minutes":
                PostStateToChild( "${ ModuleType }-${ ID }", "Snapshot Period Minutes", it.value )
                break
            case "format":
                PostStateToChild( "${ ModuleType }-${ ID }", "Format", it.value )
                break
            case "schedule":
                PostStateToChild( "${ ModuleType }-${ ID }", "Schedule", it.value )
                break
            case "lv_save":
                PostStateToChild( "${ ModuleType }-${ ID }", "LV Save", "${ it.value }" )
                break
            case "ring_device_id":
                PostStateToChild( "${ ModuleType }-${ ID }", "Ring Device ID", "${ it.value }" )
                break
            case "local_connection_certificate_id":
                PostStateToChild( "${ ModuleType }-${ ID }", "Local Connection Certificate ID", "${ it.value }" )
                break
            case "location_id":
                PostStateToChild( "${ ModuleType }-${ ID }", "Location ID", "${ it.value }" )
                break
            case "first_boot":
                PostStateToChild( "${ ModuleType }-${ ID }", "First Boot", "${ it.value }" )
                break
            case "state":
                PostStateToChild( "${ ModuleType }-${ ID }", "State", "${ it.value }" )
                break
            case "command":
                PostStateToChild( "${ ModuleType }-${ ID }", "Command", "${ it.value }" )
                break
            case "stage_vs":
                PostStateToChild( "${ ModuleType }-${ ID }", "Stage VS", "${ it.value }" )
                break
            case "firmware_id":
                PostStateToChild( "${ ModuleType }-${ ID }", "Firmware ID", "${ it.value }" )
                break
            case "stage_rest":
                PostStateToChild( "${ ModuleType }-${ ID }", "Stage Rest ", "${ it.value }" )
                break
            case "stage_cs_db":
                PostStateToChild( "${ ModuleType }-${ ID }", "Stage CS DB", "${ it.value }" )
                break
            case "sm_ack":
                PostStateToChild( "${ ModuleType }-${ ID }", "SM ACK", "${ it.value }" )
                break
            case "stage_is":
                PostStateToChild( "${ ModuleType }-${ ID }", "Stage IS", "${ it.value }" )
                break
            case "stage_cs_sent":
                PostStateToChild( "${ ModuleType }-${ ID }", "Stage CS Sent", "${ it.value }" )
                break
            case "stage_dev":
                PostStateToChild( "${ ModuleType }-${ ID }", "Stage Dev", "${ it.value }" )
                break
            case "execute_time":
                PostStateToChild( "${ ModuleType }-${ ID }", "Execute Time", "${ it.value }" )
                break
            case "by_whom":
                PostStateToChild( "${ ModuleType }-${ ID }", "By Whom", "${ it.value }" )
                break
            case "opts_1":
                PostStateToChild( "${ ModuleType }-${ ID }", "OPTS 1", "${ it.value }" )
                break
            case "diagnostic":
                PostStateToChild( "${ ModuleType }-${ ID }", "Diagnostic", "${ it.value }" )
                break
            case "player_transaction":
                PostStateToChild( "${ ModuleType }-${ ID }", "Player Transaction", "${ it.value }" )
                break
            case "attempts":
                PostStateToChild( "${ ModuleType }-${ ID }", "Attempts", "${ it.value }" )
                break
            case "lfr_ack":
                PostStateToChild( "${ ModuleType }-${ ID }", "LFR ACK", "${ it.value }" )
                break
            case "stage_sm":
                PostStateToChild( "${ ModuleType }-${ ID }", "Stage SM", "${ it.value }" )
                break
            case "debug":
                PostStateToChild( "${ ModuleType }-${ ID }", "Debug", "${ it.value }" )
                break
            case "state_stage":
                PostStateToChild( "${ ModuleType }-${ ID }", "State Stage", "${ it.value }" )
                break
            case "target_id":
                PostStateToChild( "${ ModuleType }-${ ID }", "Target ID", "${ it.value }" )
                break
            case "target":
                PostStateToChild( "${ ModuleType }-${ ID }", "Target", "${ it.value }" )
                break
            case "stage_lv":
                PostStateToChild( "${ ModuleType }-${ ID }", "Stage LV", "${ it.value }" )
                break
            case "sequence":
                PostStateToChild( "${ ModuleType }-${ ID }", "Sequence", "${ it.value }" )
                break
            case "state_condition":
                PostStateToChild( "${ ModuleType }-${ ID }", "State Condition", "${ it.value }" )
                break
            case "siren_id":
                PostStateToChild( "${ ModuleType }-${ ID }", "Siren ID", "${ it.value }" )
                break
            case "parent_command_id":
                PostStateToChild( "${ ModuleType }-${ ID }", "Parent Command ID", "${ it.value }" )
                break
            case "transaction":
                PostStateToChild( "${ ModuleType }-${ ID }", "Transaction", "${ it.value }" )
                break
            case "subtype":
                PostStateToChild( "${ ModuleType }-${ ID }", "Subtype", "${ it.value }" )
                break
            case "revision":
                PostStateToChild( "${ ModuleType }-${ ID }", "Revisions", "${ it.value }" )
                break
            case "country_id":
                PostStateToChild( "${ ModuleType }-${ ID }", "Country ID", "${ it.value }" )
                break
            case "snooze":
                PostStateToChild( "${ ModuleType }-${ ID }", "Snooze", "${ it.value }" )
                break
            case "snooze_till":
                PostStateToChild( "${ ModuleType }-${ ID }", "Snooze Till", "${ it.value }" )
                break
            case "snooze_time_remaining":
                PostStateToChild( "${ ModuleType }-${ ID }", "Snooze", "${ it.value }" )
                break
            case "usage_alert_count":
                PostStateToChild( "${ ModuleType }-${ ID }", "Usage Alert Count", it.value )
                break
            case "power_type":
                PostStateToChild( "${ ModuleType }-${ ID }", "Power Type", "${ it.value }" )
                break
            case "connected":
                PostStateToChild( "${ ModuleType }-${ ID }", "Connected", "${ it.value }" )
                break
            case "zone_version":
                PostStateToChild( "${ ModuleType }-${ ID }", "Zone Version", "${ it.value }" )
                break
            case "color":
                PostStateToChild( "${ ModuleType }-${ ID }", "Color", "${ it.value }" )
                break
            case "last_usage_alert":
                PostStateToChild( "${ ModuleType }-${ ID }", "Last Usage Alert", "${ it.value }" )
                break
            case "camera_key_type":
                PostStateToChild( "${ ModuleType }-${ ID }", "Camera Key Type", "${ it.value }" )
                break
            case "calibrated":
                PostStateToChild( "${ ModuleType }-${ ID }", "Calibrated", "${ it.value }" )
                break
            // Signals, special case, contained within Homescreen
            case "signals":
                PostStateToChild( "${ ModuleType }-${ ID }", "WiFi Signal", Data.signals.wifi )
                PostEventToChild( "${ ModuleType }-${ ID }", "temperature", ConvertTemperature( "F", Data.signals.temp ), "°${ location.getTemperatureScale() }" )
                PostStateToChild( "${ ModuleType }-${ ID }", "LFR Signal", Data.signals.lfr )
                PostStateToChild( "${ ModuleType }-${ ID }", "Battery Signal", Data.signals.battery )
                //ProcessDevices( ModuleType, Data.signals, ID )
                break
            // Ignored data for reasons...
            case "last_connect": // Ignored because it is "historical" data that interferes with current data
            case "id": // Already populated at the very beginning as an Event, so this is a duplication
            case "video_quality_support":
            case "mac_address":
            case "a1":
            case "ip_address":
            case "trace_parent":
            case "network_type":
            case "vo9_strength":
                Logging( "Ignored ${ it.key } = ${ it.value }", 4 )
                break
            // Unknown data
            default:
                Logging( "Unhandled ${ ModuleType } data: ${ it.key } = ${ it.value }", 3 )
                break
        }
    }
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

// uninstalling device so make sure to clean up children
void uninstalled() {
    // Delete all children
    getChildDevices().each{
        deleteChildDevice( it.deviceNetworkId )
    }
    Logging( "Uninstalled", 2 )
}

// parse appears to be one of those "special" methods for when data is returned 
def parse( String description ){
    Logging( "Parse = ${ description }", 3 )
}

// Used to convert epoch values to text dates
def String ConvertEpochToDate( Number Epoch ){
    def date = use( groovy.time.TimeCategory ) {
          new Date( 0 ) + Epoch.seconds
    }
    return date
}

// Checks the location.getTemperatureScale() to convert temperature values
def ConvertTemperature( String Scale, Number Value ){
    if( Value != null ){
        def ReturnValue = Value as double
        if( location.getTemperatureScale() == "C" && Scale.toUpperCase() == "F" ){
            ReturnValue = ( ( ( Value - 32 ) * 5 ) / 9 )
            Logging( "Temperature Conversion ${ Value }°F to ${ ReturnValue }°C", 4 )
        } else if( location.getTemperatureScale() == "F" && Scale.toUpperCase() == "C" ) {
            ReturnValue = ( ( ( Value * 9 ) / 5 ) + 32 )
            Logging( "Temperature Conversion ${ Value }°C to ${ ReturnValue }°F", 4 )
        } else if( ( location.getTemperatureScale() == "C" && Scale.toUpperCase() == "C" ) || ( location.getTemperatureScale() == "F" && Scale.toUpperCase() == "F" ) ){
            ReturnValue = Value
            Logging( "Temperature NOT Converted, same scale in use as Hubitat", 4 )
        }
        def TempInt = ( ReturnValue * 100 ) as int
        ReturnValue = ( TempInt / 100 )
        return ReturnValue
    }
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
    }
}

// Process data to check against current state value and then send an event if it has changed
def ProcessState( Variable, Value ){
    if( state."${ Variable }" != Value ){
        Logging( "State: ${ Variable } = ${ Value }", 4 )
        state."${ Variable }" = Value
    }
}

// Post data to child device
def PostEventToChild( Child, Variable, Value, Unit = null ){
    if( "${ Child }" != null ){
        if( getChildDevice( "${ Child }" ) == null ){
            TempChild = Child.split( "-" )
            def ChildType = ""
            switch( TempChild[ 0 ] ){
                case "Camera":
                    ChildType = "Camera"
                    break
                case "MiniCamera":
                    ChildType = "MiniCamera"
                    break
                case "Doorbell":
                    ChildType = "Doorbell"
                    break
                case "Network":
                    ChildType = "Network"
                    break
                case "SyncModule":
                    ChildType = "SyncModule"
                    break
                case "Floodlight":
                    ChildType = "Floodlight"
                    break
                case "WiredFloodlight":
                    ChildType = "WiredFloodlight"
                    break
                case "Rosie":
                default:
                    ChildType = "Generic"
                    break
            }
            addChild( "${ Child }", ChildType )
        }
        if( getChildDevice( "${ Child }" ) != null ){
            if( Unit != null ){
                getChildDevice( "${ Child }" ).ProcessEvent( "${ Variable }", Value, "${ Unit }" )
                Logging( "${ Child } Event: ${ Variable } = ${ Value }${ Unit }", 4 )
            } else {
                getChildDevice( "${ Child }" ).ProcessEvent( "${ Variable }", Value )
                Logging( "${ Child } Event: ${ Variable } = ${ Value }", 4 )
            }
        } else {
            if( Unit != null ){
                Logging( "Failure to add ${ Child } and post ${ Variable }=${ Value }${ Unit }", 5 )
            } else {
                Logging( "Failure to add ${ Child } and post ${ Variable }=${ Value }", 5 )
            }
        }
    } else {
        Logging( "Failure to add child because child name was null", 5 )
    }
}

// Post data to child device
def PostStateToChild( Child, Variable, Value ){
    if( "${ Child }" != null ){
        if( getChildDevice( "${ Child }" ) == null ){
            TempChild = Child.split( "-" )
            def ChildType = ""
            switch( TempChild[ 0 ] ){
                case "Camera":
                    ChildType = "Camera"
                    break
                case "MiniCamera":
                    ChildType = "MiniCamera"
                    break
                case "Doorbell":
                    ChildType = "Doorbell"
                    break
                case "Network":
                    ChildType = "Network"
                    break
                case "SyncModule":
                    ChildType = "SyncModule"
                    break
                case "Floodlight":
                    ChildType = "Floodlight"
                    break
                case "WiredFloodlight":
                    ChildType = "WiredFloodlight"
                    break
                case "Rosie":
                default:
                    ChildType = "Generic"
                    break
            }
            addChild( "${ Child }", ChildType )
        }
        if( getChildDevice( "${ Child }" ) != null ){
            Logging( "${ Child } State: ${ Variable } = ${ Value }", 4 )
            getChildDevice( "${ Child }" ).ProcessState( "${ Variable }", Value )
        } else {
            Logging( "Failure to add ${ ChildParent } and post ${ Variable }=${ Value }", 5 )
        }
    } else {
        Logging( "Failure to add child because child name was null", 5 )
    }
}

// Adds a BlinkChild child device
// Based on @mircolino's method for child sensors
def addChild( String DNI, String ChildType ){
    try{
        Logging( "Adding ${ DNI } of type ${ ChildType }", 3 )
        switch( ChildType ){
            case "Camera":
                addChildDevice( "BlinkChild-Camera", DNI, [ name: "${ DNI }" ] )
                break
            case "MiniCamera":
                addChildDevice( "BlinkChild-MiniCamera", DNI, [ name: "${ DNI }" ] )
                break
            case "Doorbell":
                addChildDevice( "BlinkChild-Doorbell", DNI, [ name: "${ DNI }" ] )
                break
            case "Network":
                addChildDevice( "BlinkChild-Network", DNI, [ name: "${ DNI }" ] )
                break
            case "SyncModule":
                addChildDevice( "BlinkChild-SyncModule", DNI, [ name: "${ DNI }" ] )
                break
            case "Floodlight":
                addChildDevice( "BlinkChild-Floodlight", DNI, [ name: "${ DNI }" ] )
                break
            case "WiredFloodlight":
                addChildDevice( "BlinkChild-WiredFloodlight", DNI, [ name: "${ DNI }" ] )
                break
            case "Rosie":
            default:
                addChildDevice( "BlinkChild", DNI, [ name: "${ DNI }" ] )
                break
        }
    }
    catch( Exception e ){
        def Temp = e as String
        if( Temp.contains( "not found" ) ){
            Logging( "BlinkChild-${ ChildType } driver is not loaded for ${ DNI }", 5 )
        } else {
            Logging( "addChild Error, likely ${ DNI } child already exists: ${ Temp }", 5 )
        }
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