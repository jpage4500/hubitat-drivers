## VeSync: Levoit Air Purifier Drivers

These drivers add support for Levoit Air Core 200S, 300S, 400S, and 600S air purifiers. Installing the integration driver, and configuring with the VeSync account information will automatically discover existing equipment, as long as they have been setup with a VeSync account using the VeSync app.

Equipment found will be added as child devices under the VeSync Integration device, and will have the same name and initial label as what is configures in the VeSync account. Pressing 'Resync Equipment' will discover all newly added devices.

When discovered, a Core 200S purifier  will result in two devices being installed in Hubitat, one to control the operation of the purifier fan and display, one to control the night light. A Core 300S, 400S, or 600S purifier will result in one device being installed.

The purifiers show up as fans, switches, and dimmers. And there's also an 'info' attribute that is useful for displaying in a dashboard tile as HTML. The child devices are actuators, so their public methods may be invoked from rules.

## Installation

There are six files to install, all as Hubitat drivers. Use the Hubitat package manager to install the drivers. The manifest file is located at: https://raw.githubusercontent.com/NiklasGustafsson/Hubitat/master/levoitManifest.json.

If you do not use the HPM, then copy and paste into the Hubitat UI under driver code. Remember to use the 'Raw' view of the code in GitHub before copying. You only need to install drivers for the kinds of devices you have, plus the integration driver, which is the parent device. Not that the 200S requires two drivers, since there's also night light to control, which shows up as its own switch device.

1. VeSyncIntegration.groovy -- this is the parent device. It represents the VeSync account.<br/> Configure with account email and password, plus a refresh (polling) interval.
2. LevoitCore200S.groovy -- the driver for the 200S purifier.
3. LevoitCore200S Light.groovy -- the driver for the 200S night light.
4. LevoitCore400S.groovy -- the driver for the 300S purifier.
5. LevoitCore400S.groovy -- the driver for the 400S purifier.
6. LevoitCore600S.groovy -- the driver for the 600S purifier.

After installing the drivers, add a virtual device with the VeSync Integration driver, then configure it with your credentials and the desired data refresh internal, and hit 'Save Preferences.' Once that is done, press the 'Resync Equipment' button on the device page and if your credentials are correct, you should see child devices come online. 

The refresh interval determines how often the drivers will poll the status of your equipment. If you are planning on mostly use automation or the Hubitat dashboards to control things, then it can be relatively high.

__Note__: 

If you rename a device in the VeSync app, the device will automatically be renamed in Hubitat when you hit 'Resync Equipment' in the device page for the parent device. That does not change its device identity, just the label and name, so no rules are affected.

## Events

The main driver, i.e. the VeSync only has one event attribute: `heartbeat`, which has the possible values `syncing`, `synced`, and `not synced`. The last one may be used in a rule to detect that the hub has failed to contact the VeSync servers for some time.

The devices themselves have more attributes:

### 200S

|event|Values|Description|
| --- | --- | --- |
|filter|0-100|The remaining filter life as a percentage.|
|mode|manual,sleep|The current mode of the purifier.|
|info|HTML TEXT|HTML suitable for displaying in a Hubitat dashboard tile.|
|switch|on,off|Whether the purifier is turned on or off.|
|speed|off,low,medium,high|The fan speed.|

### 300S

|event|Values|Description|
| --- | --- | --- |
|filter|0-100|The remaining filter life as a percentage.|
|mode|manual,sleep,auto|The current mode of the purifier.|
|auto_mode|default,quiet,efficient|The Levoit auto-mode setting.|
|aqi|0-500|The Air Quality Index, computed by the formula used in the United States.|
|aqiDanger|Various|A risk level string suitable for displaying in a Hubitat dashboard tile.|
|aqiColor|Hexadecimal Number|A color code suitable for use in HTML|
|info|HTML TEXT|HTML suitable for displaying in a Hubitat dashboard tile.|
|switch|on,off|Whether the purifier is turned on or off.|
|speed|off,sleep,low,medium,high|The fan speed.|

### 400S

|event|Values|Description|
| --- | --- | --- |
|filter|0-100|The remaining filter life as a percentage.|
|mode|manual,sleep,auto|The current mode of the purifier.|
|auto_mode|default,quiet,efficient|The Levoit auto-mode setting.|
|aqi|0-500|The Air Quality Index, computed by the formula used in the United States.|
|aqiDanger|Various|A risk level string suitable for displaying in a Hubitat dashboard tile.|
|aqiColor|Hexadecimal Number|A color code suitable for use in HTML|
|info|HTML TEXT|HTML suitable for displaying in a Hubitat dashboard tile.|
|switch|on,off|Whether the purifier is turned on or off.|
|speed|off,sleep,low,medium,high,max|The fan speed.|

### 600S

|event|Values|Description|
| --- | --- | --- |
|filter|0-100|The remaining filter life as a percentage.|
|mode|manual,sleep,auto|The current mode of the purifier.|
|auto_mode|default,quiet,eco,efficient|The Levoit auto-mode setting.|
|aqi|0-500|The Air Quality Index, computed by the formula used in the United States.|
|aqiDanger|Various|A risk level string suitable for displaying in a Hubitat dashboard tile.|
|aqiColor|Hexadecimal Number|A color code suitable for use in HTML|
|info|HTML TEXT|HTML suitable for displaying in a Hubitat dashboard tile.|
|switch|on,off|Whether the purifier is turned on or off.|
|speed|off,sleep,low,medium,high,max|The fan speed.|

### Acknowledgements

The Groovy code is loosely based on the Etekcity Python library at: https://github.com/webdjoe/pyvesync

Thank you to elfege, who added setLevel() and figured out that the 'max' speed was missing.
