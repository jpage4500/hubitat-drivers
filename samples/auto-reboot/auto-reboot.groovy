/**
 *  Hub Health Monitor & Auto Reboot
 *
 *  Description:
 *  Monitors hub health (memory usage and critical events) and automatically reboots
 *  when free memory falls below a configured threshold or critical events are detected.
 *
 *  Features:
 *  - Configurable minimum free memory threshold
 *  - Configurable reboot time window
 *  - Manual test reboot function
 *  - Optional database rebuild on reboot
 *  - Periodic scheduled reboots (weekly, fortnightly, monthly)
 *  - Hub event monitoring (zigbeeOff, zwaveCrashed, severeLoad)
 *  - Hub uptime display
 *  - Detailed logging
 *  - Memory status tracking
 *
 *  Version: 1.3.0
 *  Author: Derek Osborn
 *  Date: 2026-02-06
 *
 *  v1.3.0 - Code quality improvements: extracted reboot helper, eliminated duplicated
 *           frequency/uptime logic, added event reboot cooldown, fixed state updates
 *           on failed reboots, added unsubscribe to uninstalled(), replaced deprecated
 *           BigDecimal.ROUND_HALF_UP, centralised version constant, added HR helper.
 *  v1.2.3 - Added hub model detection using getHubVersion and display in info panel. Total RAM now determined by hub model.
 *  v1.2.2 - Improved debug mode for periodic reboots - uses exact reboot time on current day,
 *           bypassing day-of-week and uptime checks. Removed hourly option.
 *  v1.2.1 - Fixed BigDecimal.round() compatibility issue for Hubitat
 *  v1.2.0 - Renamed to "Hub Health Monitor & Auto Reboot" and added hub event monitoring
 *           for zigbeeOff, zwaveCrashed, and severeLoad events with 5-minute startup grace period
 *  v1.1.5 - Updated default memory threshold to 200 MB and minor improvements to scheduler logic
 *  v1.1.4 - Added uptime check during polling - reschedules reboot if uptime < 70% of interval
 *  v1.1.3 - Finally got the Rebuild Database on Reboot function working
 *  v1.1.2 - Fixed periodic reboot scheduling, reverted to /hub/rebuildDatabaseAndReboot endpoint
 *  v1.1.1 - Fixed periodic reboot scheduling to properly calculate next occurrence
 *  v1.1.0 - Simplified memory detection to use actual total RAM from hub data
 *  v1.0.9 - Updated memory detection - only C-8 Pro has 2GB RAM
 *  v1.0.8 - Fixed uptime parsing to correctly handle CSV format from memory history
 *  v1.0.7 - Fixed uptime calculation to use memory history endpoint
 *  v1.0.6 - Fixed namespace and improved uptime display
 *  v1.0.5 - Added hub uptime display and periodic scheduled reboot feature
 *  v1.0.4 - Added import url and updated endpoint for reboot with db rebuild
 *  v1.0.2 - Added option to rebuild the Database on reboot
 *  v1.0.1 - Removed Hub Security as no longer required
 *  v1.0.0 - First public release
 */

import groovy.transform.Field

@Field static final String VERSION = "1.3.0"
@Field static final int STARTUP_GRACE_SECONDS = 300  // 5 minutes
@Field static final long EVENT_REBOOT_COOLDOWN_MS = 60000  // 1 minute cooldown between event reboots

definition(
    name: "Hub Health Monitor & Auto Reboot",
    namespace: "dJOS",
    author: "Derek Osborn",
    description: "Monitors hub health (memory and critical events) and automatically reboots when thresholds are exceeded or critical events detected",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/dJOS1475/Hubitat-Memory-Monitor-Auto-Reboot/refs/heads/main/memory-monitor-reboot.groovy"
)

preferences {
    page(name: "mainPage")
}

// ──────────────────────────────────────────────
//  UI
// ──────────────────────────────────────────────

def mainPage() {
    dynamicPage(name: "mainPage", title: "Hub Health Monitor & Auto Reboot", install: true, uninstall: true) {
        sectionHR()

        section("<b>Memory Monitoring</b>") {
            paragraph "<b>Version:</b> ${VERSION}"
            def hubModel = getHubModel()
            paragraph "<b>Hub Model:</b> ${hubModel ?: 'Unknown'}"
            paragraph "Current Hub Memory Status:"
            def memInfo = getMemoryInfo()
            if (memInfo) {
                paragraph "<b>Free Memory:</b> ${memInfo.free} MB<br>" +
                         "<b>Total Memory:</b> ${memInfo.total} MB<br>" +
                         "<b>Used Memory:</b> ${memInfo.used} MB<br>" +
                         "<b>Usage:</b> ${memInfo.percentUsed}%"
            } else {
                paragraph "Unable to retrieve memory information"
            }

            def uptime = getHubUptime()
            if (uptime) {
                paragraph "<br><b>Hub Uptime:</b> ${uptime}"
            }
        }

        sectionHR()

        section("<b>Reboot Settings</b>") {
            input "memoryThreshold", "number", 
                title: "Minimum Free Memory Threshold (MB)", 
                description: "Reboot when free memory falls below this value",
                required: true, 
                defaultValue: 200,
                range: "10..500"
            
            input "enableAutoReboot", "bool",
                title: "Enable Automatic Reboot",
                description: "Allow app to automatically reboot hub when threshold is reached",
                defaultValue: false,
                submitOnChange: true
            
            input "rebuildDatabase", "bool",
                title: "Rebuild Database on Reboot",
                description: "Perform database rebuild when rebooting (may take longer)",
                defaultValue: false
        }

        if (enableAutoReboot) {
            sectionHR()

            section("<b>Reboot Time Window</b>") {
                paragraph "The hub will only reboot within this time window when the memory threshold is reached"
                
                input "rebootStartTime", "time",
                    title: "Window Start Time",
                    description: "Start of allowed reboot window",
                    required: true
                
                input "rebootEndTime", "time",
                    title: "Window End Time", 
                    description: "End of allowed reboot window",
                    required: true
            }
        }

        sectionHR()

        section("<b>Hub Event Monitoring</b>") {
            paragraph "Monitor for critical hub events and automatically reboot when detected<br><i>(Critical events are ignored for the first ${STARTUP_GRACE_SECONDS / 60 as int} minutes after hub startup)</i>"

            input "enableEventMonitoring", "bool",
                title: "Enable Hub Event Monitoring",
                description: "Monitor for critical hub events and reboot when detected",
                defaultValue: false,
                submitOnChange: true

            if (enableEventMonitoring) {
                paragraph "<div style='margin-left: 20px;'>"
                input "monitorZigbeeOff", "bool",
                    title: "Monitor for zigbeeOff Event",
                    description: "Reboot when Zigbee radio goes offline",
                    defaultValue: true

                input "monitorZwaveCrashed", "bool",
                    title: "Monitor for zwaveCrashed Event",
                    description: "Reboot when Z-Wave radio crashes",
                    defaultValue: true

                input "monitorSevereLoad", "bool",
                    title: "Monitor for severeLoad Event",
                    description: "Reboot when hub experiences severe load",
                    defaultValue: true
                paragraph "</div>"
            }
        }

        sectionHR()

        section("<b>Periodic Reboot Schedule</b>") {
            input "enablePeriodicReboot", "bool",
                title: "Enable Periodic Scheduled Reboot",
                description: "Reboot hub on a regular schedule",
                defaultValue: false,
                submitOnChange: true
            
            if (enablePeriodicReboot) {
                input "periodicFrequency", "enum",
                    title: "Reboot Frequency",
                    description: "How often to perform scheduled reboot",
                    options: [
                        "daily": "Daily",
                        "weekly": "Weekly",
                        "fortnightly": "Fortnightly (Every 2 weeks)",
                        "monthly": "Monthly (Every 4 weeks)"
                    ],
                    required: true,
                    defaultValue: "weekly"

                if (enableDebug) {
                    paragraph "<i>Debug Mode: Reboot will be scheduled for today at the specified time, regardless of day selection or hub uptime.</i>"
                }
                
                input "periodicDayOfWeek", "enum",
                    title: "Day of Week",
                    description: "Which day to perform the reboot",
                    options: [
                        "SUN": "Sunday",
                        "MON": "Monday",
                        "TUE": "Tuesday",
                        "WED": "Wednesday",
                        "THU": "Thursday",
                        "FRI": "Friday",
                        "SAT": "Saturday"
                    ],
                    required: true,
                    defaultValue: "SUN"
                
                input "periodicRebootTime", "time",
                    title: "Reboot Time",
                    description: "Time to perform the scheduled reboot",
                    required: true
                
                paragraph "<i>Note: Periodic reboots will use the database rebuild setting configured above.</i>"
            }
        }

        sectionHR()

        section("<b>Monitoring Schedule</b>") {
            input "checkInterval", "enum",
                title: "Memory Check Interval",
                description: "How often to check memory usage",
                options: [
                    "1": "Every 1 minute",
                    "5": "Every 5 minutes",
                    "10": "Every 10 minutes",
                    "15": "Every 15 minutes",
                    "30": "Every 30 minutes",
                    "60": "Every 1 hour"
                ],
                defaultValue: "15",
                required: true
        }

        sectionHR()

        section("<b>Notifications</b>") {
            input "notifyBeforeReboot", "bool",
                title: "Log Warning Before Reboot",
                description: "Log a warning message before initiating reboot",
                defaultValue: true
        }

        sectionHR()

        section("<b>Test Reboot</b>") {
            paragraph "<b>Warning:</b> This will immediately reboot your hub!"
            input "testReboot", "button", title: "Test Reboot Now"
        }

        sectionHR()

        section("<b>Logging</b>") {
            input "enableDebug", "bool",
                title: "Enable Debug Logging",
                defaultValue: false
        }

        sectionHR()

        section("<b>Statistics</b>") {
            if (state.lastCheck) {
                paragraph "<b>Last Check:</b> ${new Date(state.lastCheck).format('yyyy-MM-dd HH:mm:ss')}"
            }
            if (state.lastReboot) {
                paragraph "<b>Last Auto Reboot:</b> ${new Date(state.lastReboot).format('yyyy-MM-dd HH:mm:ss')}"
            }
            if (state.lastEventReboot) {
                paragraph "<b>Last Event-Triggered Reboot:</b> ${new Date(state.lastEventReboot).format('yyyy-MM-dd HH:mm:ss')}"
            }
            if (state.lastEventType) {
                paragraph "<b>Last Event Type:</b> ${state.lastEventType}"
            }
            if (state.lastPeriodicReboot) {
                paragraph "<b>Last Periodic Reboot:</b> ${new Date(state.lastPeriodicReboot).format('yyyy-MM-dd HH:mm:ss')}"
            }
            if (state.nextPeriodicReboot && enablePeriodicReboot) {
                paragraph "<b>Next Scheduled Reboot:</b> ${new Date(state.nextPeriodicReboot).format('yyyy-MM-dd HH:mm:ss')}"
            }
            if (state.rebootCount) {
                paragraph "<b>Total Auto Reboots:</b> ${state.rebootCount}"
            }
            if (state.eventRebootCount) {
                paragraph "<b>Total Event-Triggered Reboots:</b> ${state.eventRebootCount}"
            }
            if (state.periodicRebootCount) {
                paragraph "<b>Total Periodic Reboots:</b> ${state.periodicRebootCount}"
            }
        }
    }
}

/** Renders a styled horizontal rule inside its own section. */
private sectionHR() {
    section() {
        paragraph "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    }
}

// ──────────────────────────────────────────────
//  Lifecycle
// ──────────────────────────────────────────────

def installed() {
    log.info "Hub Health Monitor & Auto Reboot installed"
    initialize()
}

def updated() {
    log.info "Hub Health Monitor & Auto Reboot updated"
    unsubscribe()
    unschedule()
    initialize()
}

def uninstalled() {
    log.info "Hub Health Monitor & Auto Reboot uninstalled"
    unsubscribe()
    unschedule()
}

def initialize() {
    state.rebootCount = state.rebootCount ?: 0
    state.periodicRebootCount = state.periodicRebootCount ?: 0
    state.eventRebootCount = state.eventRebootCount ?: 0

    // Subscribe to hub events if monitoring is enabled
    if (enableEventMonitoring) {
        if (monitorZigbeeOff) {
            subscribe(location, "zigbeeOff", hubEventHandler)
            log.info "Subscribed to zigbeeOff events"
        }
        if (monitorZwaveCrashed) {
            subscribe(location, "zwaveCrashed", hubEventHandler)
            log.info "Subscribed to zwaveCrashed events"
        }
        if (monitorSevereLoad) {
            subscribe(location, "severeLoad", hubEventHandler)
            log.info "Subscribed to severeLoad events"
        }
    }

    // Schedule memory checks based on configured interval
    def interval = (checkInterval ?: "15").toInteger()
    
    switch(interval) {
        case 1:
            runEvery1Minute(checkMemory)
            break
        case 5:
            runEvery5Minutes(checkMemory)
            break
        case 10:
            runEvery10Minutes(checkMemory)
            break
        case 15:
            runEvery15Minutes(checkMemory)
            break
        case 30:
            runEvery30Minutes(checkMemory)
            break
        case 60:
            runEvery1Hour(checkMemory)
            break
        default:
            runEvery15Minutes(checkMemory)
    }
    
    log.info "Memory monitoring initialized - checking every ${interval} minute(s)"
    log.info "Threshold: ${memoryThreshold} MB free memory"
    
    if (enableAutoReboot) {
        log.info "Auto-reboot enabled for time window: ${rebootStartTime} to ${rebootEndTime}"
    } else {
        log.info "Auto-reboot is DISABLED"
    }
    
    // Schedule periodic reboots
    if (enablePeriodicReboot && periodicDayOfWeek && periodicRebootTime) {
        schedulePeriodicReboot()
    }
    
    // Do an initial check
    runIn(5, checkMemory)
}

// ──────────────────────────────────────────────
//  Shared helpers
// ──────────────────────────────────────────────

/**
 * Returns the number of days for a given periodic frequency string.
 * Defaults to 7 (weekly) if the value is null or unrecognised.
 */
private int getFrequencyDays(String freq = null) {
    switch(freq ?: periodicFrequency ?: "weekly") {
        case "daily":       return 1
        case "weekly":      return 7
        case "fortnightly": return 14
        case "monthly":     return 28
        default:            return 7
    }
}

/**
 * Returns the required uptime in seconds (70 % of the frequency interval).
 */
private long getRequiredUptimeSeconds(String freq = null) {
    return (long)((getFrequencyDays(freq) * 24L * 60L * 60L) * 0.7)
}

/**
 * Formats a seconds value as a day count with one decimal place,
 * avoiding the deprecated BigDecimal.ROUND_HALF_UP constant.
 */
private String formatDays(Number seconds) {
    return String.valueOf(Math.round(seconds / 8640.0) / 10.0)
}

/**
 * Sends the reboot (or rebuild-and-reboot) HTTP command to the hub.
 * Returns true if the command was sent successfully, false otherwise.
 */
private boolean executeReboot() {
    try {
        def params = [
            uri: "http://127.0.0.1:8080",
            path: "/hub/reboot"
        ]
        if (rebuildDatabase) {
            params.headers = ["Content-Type": "application/x-www-form-urlencoded"]
            params.body = [rebuildDatabase: "true"]
        }
        httpPost(params) { resp -> }
        log.info "Reboot command sent successfully${rebuildDatabase ? ' (with DB rebuild)' : ''}"
        return true
    } catch (Exception e) {
        log.error "Error sending reboot command: ${e.message}"
        return false
    }
}

/**
 * Advances a Calendar by the appropriate number of days for the
 * current periodic frequency and returns the resulting Date.
 */
private Date advanceByFrequency(Calendar calendar, String freq = null) {
    calendar.add(Calendar.DAY_OF_MONTH, getFrequencyDays(freq))
    return calendar.time
}

// ──────────────────────────────────────────────
//  Button handler
// ──────────────────────────────────────────────

def appButtonHandler(btn) {
    switch(btn) {
        case "testReboot":
            log.warn "TEST REBOOT button pressed - rebooting hub NOW"
            performReboot(true)
            break
    }
}

// ──────────────────────────────────────────────
//  Hub event monitoring
// ──────────────────────────────────────────────

def hubEventHandler(evt) {
    def eventName = evt.name
    def eventValue = evt.value

    log.warn "═══════════════════════════════════════"
    log.warn "CRITICAL HUB EVENT DETECTED: ${eventName}"
    log.warn "Event Value: ${eventValue}"
    log.warn "═══════════════════════════════════════"

    // Ignore all critical events if hub has been online for less than the grace period
    def hubUptimeSeconds = location.hub.uptime
    if (hubUptimeSeconds < STARTUP_GRACE_SECONDS) {
        def uptimeMinutes = Math.round(hubUptimeSeconds / 60)
        log.info "Ignoring ${eventName} event - hub uptime is only ${uptimeMinutes} minute(s) (waiting ${STARTUP_GRACE_SECONDS / 60 as int} minutes)"
        return
    }

    // Cooldown: prevent duplicate reboots if multiple events fire in quick succession
    def lastAttempt = state.lastRebootAttempt ?: 0
    if ((now() - lastAttempt) < EVENT_REBOOT_COOLDOWN_MS) {
        log.warn "Reboot already in progress or recently attempted, ignoring duplicate ${eventName} event"
        return
    }
    state.lastRebootAttempt = now()

    // Perform reboot due to hub event
    def dbAction = rebuildDatabase ? " with Database Rebuild" : ""
    log.warn "Initiating hub reboot${dbAction} due to ${eventName} event"

    // Pause briefly to ensure log messages are written
    pauseExecution(2000)

    if (executeReboot()) {
        // Only record the reboot in state if the command was actually sent
        state.lastEventReboot = now()
        state.lastEventType = eventName
        state.eventRebootCount = (state.eventRebootCount ?: 0) + 1
    } else {
        log.error "You may need to reboot manually from Settings > Reboot"
    }
}

// ──────────────────────────────────────────────
//  Memory monitoring
// ──────────────────────────────────────────────

def checkMemory() {
    state.lastCheck = now()
    
    def memInfo = getMemoryInfo()
    
    if (!memInfo) {
        log.error "Unable to retrieve memory information"
        return
    }
    
    logDebug "Memory check - Free: ${memInfo.free} MB, Used: ${memInfo.used} MB (${memInfo.percentUsed}%)"
    
    // Check if periodic reboot needs to be rescheduled based on uptime
    if (enablePeriodicReboot && state.nextPeriodicReboot) {
        checkAndUpdatePeriodicReboot()
    }
    
    // Check if we're below threshold
    if (memInfo.free < memoryThreshold) {
        log.warn "Free memory (${memInfo.free} MB) is below threshold (${memoryThreshold} MB)"
        
        if (enableAutoReboot) {
            if (isWithinRebootWindow()) {
                log.warn "Within reboot time window - initiating reboot"
                performReboot(false)
            } else {
                log.info "Below threshold but outside reboot time window - will reboot when window opens"
            }
        } else {
            log.info "Auto-reboot is disabled - no action taken"
        }
    } else {
        logDebug "Memory levels OK - ${memInfo.free} MB free (threshold: ${memoryThreshold} MB)"
    }
}

def checkAndUpdatePeriodicReboot() {
    // Skip uptime check in debug mode
    if (enableDebug) {
        return
    }

    // Check if hub uptime is at least 70% of scheduled interval
    def hubUptimeSeconds = location.hub.uptime
    def requiredSeconds = getRequiredUptimeSeconds()

    // Check if next scheduled reboot is coming up soon (within 24 hours)
    def nowMs = new Date().time
    def nextReboot = state.nextPeriodicReboot
    def hoursUntilReboot = (nextReboot - nowMs) / (1000 * 60 * 60)

    // Only check if reboot is scheduled within next 24 hours and uptime is insufficient
    if (hoursUntilReboot <= 24 && hoursUntilReboot > 0 && hubUptimeSeconds < requiredSeconds) {
        def uptimeDays = formatDays(hubUptimeSeconds)
        def requiredDays = formatDays(requiredSeconds)

        log.info "Upcoming periodic reboot check: Hub uptime (${uptimeDays} days) is less than required (${requiredDays} days)"
        log.info "Next reboot will be skipped - rescheduling for next ${periodicFrequency ?: 'weekly'} occurrence"

        // Reschedule to next occurrence
        def calendar = Calendar.getInstance(location.timeZone)
        calendar.setTime(new Date(nextReboot))
        def newNextReboot = advanceByFrequency(calendar)

        state.nextPeriodicReboot = newNextReboot.time
        unschedule(performPeriodicReboot)
        runOnce(newNextReboot, performPeriodicReboot)

        log.info "Periodic reboot rescheduled to ${newNextReboot.format('yyyy-MM-dd HH:mm:ss')}"
    }
}

// ──────────────────────────────────────────────
//  Hub info helpers
// ──────────────────────────────────────────────

def getHubModel() {
    try {
        // getHubVersion() is a built-in Hubitat method that returns the hub model
        // Returns strings like "C-7", "C-8", "C-8 Pro", etc.
        return getHubVersion()
    } catch (Exception e) {
        log.error "Error getting hub model: ${e.message}"
        return null
    }
}

def getTotalMemoryForModel(String model) {
    // C-8 Pro and higher models have 2GB RAM
    // All other models (C-1 through C-8) have 1GB RAM
    if (model != null) {
        def modelUpper = model.toUpperCase()
        if (modelUpper.contains("PRO")) {
            return 2048 // 2GB for C-8 Pro and higher
        }
    }
    return 1024 // 1GB for all other hubs (C-1 through C-8)
}

def getMemoryInfo() {
    try {
        // Get memory information from history endpoint
        def params = [
            uri: "http://127.0.0.1:8080",
            path: "/hub/advanced/freeOSMemoryHistory",
            timeout: 5
        ]

        def freeMemKB = null
        def totalMemKB = null

        httpGet(params) { resp ->
            if (resp.success) {
                String historyData = resp.data.text
                def lines = historyData.split('\n')

                // Get the last data line (skip header)
                // Format: Date/time,Free OS,5m CPU avg,Total Java,Free Java,Direct Java
                def dataLines = lines.findAll { line ->
                    line.trim() && !line.startsWith('Date/time')
                }

                if (dataLines.size() > 0) {
                    def lastLine = dataLines.last()
                    def values = lastLine.split(',')

                    if (values.size() >= 4) {
                        // Column 1 = Free OS memory (KB)
                        freeMemKB = values[1].trim().toLong()
                        // Column 3 = Total Java memory (KB)
                        totalMemKB = values[3].trim().toLong()
                    }
                }
            }
        }

        if (freeMemKB != null && totalMemKB != null) {
            // Convert KB to MB
            def freeMemMB = Math.round(freeMemKB / 1024)

            // Determine total OS RAM based on hub model
            def hubModel = getHubModel()
            def totalMemMB = getTotalMemoryForModel(hubModel)

            def usedMemMB = totalMemMB - freeMemMB
            def percentUsed = Math.round((usedMemMB / totalMemMB) * 100)

            return [
                free: freeMemMB,
                total: totalMemMB,
                used: usedMemMB,
                percentUsed: percentUsed
            ]
        }
    } catch (Exception e) {
        log.error "Error getting memory stats: ${e.message}"
        logDebug "Memory error details: ${e}"
    }

    return null
}

def getHubUptime() {
    try {
        def uptimeSeconds = location.hub.uptime
        if (uptimeSeconds == null || uptimeSeconds < 0) return null

        def days = (uptimeSeconds / 86400) as int
        def hours = ((uptimeSeconds % 86400) / 3600) as int
        def minutes = ((uptimeSeconds % 3600) / 60) as int

        def uptimeStr = ""
        if (days > 0) {
            uptimeStr += "${days} day${days != 1 ? 's' : ''}, "
        }
        if (hours > 0 || days > 0) {
            uptimeStr += "${hours} hour${hours != 1 ? 's' : ''}, "
        }
        uptimeStr += "${minutes} minute${minutes != 1 ? 's' : ''}"

        return uptimeStr
    } catch (Exception e) {
        log.error "Error getting hub uptime: ${e.message}"
        logDebug "Uptime error details: ${e}"
    }

    return null
}

// ──────────────────────────────────────────────
//  Reboot window & execution
// ──────────────────────────────────────────────

def isWithinRebootWindow() {
    if (!rebootStartTime || !rebootEndTime) {
        return false
    }
    
    def now = new Date()
    def start = timeToday(rebootStartTime, location.timeZone)
    def end = timeToday(rebootEndTime, location.timeZone)
    
    // Handle time window spanning midnight
    if (end < start) {
        return (now >= start || now <= end)
    } else {
        return (now >= start && now <= end)
    }
}

def performReboot(isTest) {
    def memInfo = getMemoryInfo()

    if (notifyBeforeReboot || isTest) {
        def reason = isTest ? "TEST REBOOT" : "Low Memory (${memInfo?.free} MB free)"
        def dbAction = rebuildDatabase ? " with Database Rebuild" : ""
        log.warn "═══════════════════════════════════════"
        log.warn "REBOOTING HUB${dbAction} - Reason: ${reason}"
        log.warn "═══════════════════════════════════════"
    }

    if (!isTest) {
        state.lastReboot = now()
        state.rebootCount = (state.rebootCount ?: 0) + 1
    }

    // Pause briefly to ensure log message is written
    pauseExecution(2000)
    
    if (!executeReboot()) {
        log.error "You may need to reboot manually from Settings > Reboot"
    }
}

// ──────────────────────────────────────────────
//  Periodic reboot scheduling
// ──────────────────────────────────────────────

def schedulePeriodicReboot() {
    def now = new Date()
    def rebootTime = timeToday(periodicRebootTime, location.timeZone)

    // Handle debug mode - schedule for today at the specified time
    if (enableDebug) {
        def nextReboot
        if (rebootTime <= now) {
            // Time has already passed today, schedule for tomorrow
            def calendar = Calendar.getInstance(location.timeZone)
            calendar.setTime(rebootTime)
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            nextReboot = calendar.time
        } else {
            nextReboot = rebootTime
        }

        state.nextPeriodicReboot = nextReboot.time
        runOnce(nextReboot, performPeriodicReboot)

        log.info "Periodic reboot scheduled for ${nextReboot.format('yyyy-MM-dd HH:mm:ss')} (debug mode - ignoring day/uptime checks)"
        return
    }

    // For daily reboots, simply schedule for today's time or tomorrow if time has passed
    if (periodicFrequency == "daily") {
        def nextReboot
        if (rebootTime <= now) {
            // Time has already passed today, schedule for tomorrow
            def calendar = Calendar.getInstance(location.timeZone)
            calendar.setTime(rebootTime)
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            nextReboot = calendar.time
        } else {
            nextReboot = rebootTime
        }

        state.nextPeriodicReboot = nextReboot.time
        runOnce(nextReboot, performPeriodicReboot)

        log.info "Periodic reboot scheduled for ${nextReboot.format('yyyy-MM-dd HH:mm:ss')} (daily)"
        return
    }

    // Calculate next reboot time based on frequency and day of week
    // Start with today's reboot time
    def calendar = Calendar.getInstance(location.timeZone)
    calendar.setTime(rebootTime)

    def targetDayOfWeek = getDayOfWeekNumber(periodicDayOfWeek)
    def currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

    // Calculate days until target day of week
    def daysToAdd = targetDayOfWeek - currentDayOfWeek
    if (daysToAdd < 0) {
        daysToAdd += 7
    }

    // If target day is today but time has passed, add days based on frequency
    if (daysToAdd == 0 && rebootTime <= now) {
        daysToAdd = getFrequencyDays()
    }

    calendar.add(Calendar.DAY_OF_MONTH, daysToAdd)
    def nextReboot = calendar.time

    // Store next reboot time
    state.nextPeriodicReboot = nextReboot.time

    // Schedule the reboot
    runOnce(nextReboot, performPeriodicReboot)

    log.info "Periodic reboot scheduled for ${nextReboot.format('yyyy-MM-dd HH:mm:ss')} (${periodicFrequency ?: 'weekly'}, ${periodicDayOfWeek})"
}

def performPeriodicReboot() {
    log.warn "═══════════════════════════════════════"
    log.warn "PERFORMING PERIODIC SCHEDULED REBOOT"
    def freqDisplay = periodicFrequency ?: 'weekly'
    def dayDisplay = periodicFrequency == 'daily' ? '' : ", Day: ${periodicDayOfWeek}"
    log.warn "Frequency: ${freqDisplay}${dayDisplay}${enableDebug ? ' (debug mode)' : ''}"
    log.warn "═══════════════════════════════════════"

    // Skip uptime check in debug mode
    if (!enableDebug) {
        // Final uptime check (should have already been checked during polling)
        def hubUptimeSeconds = location.hub.uptime
        def requiredSeconds = getRequiredUptimeSeconds()

        if (hubUptimeSeconds < requiredSeconds) {
            def uptimeDays = formatDays(hubUptimeSeconds)
            def requiredDays = formatDays(requiredSeconds)
            log.warn "Skipping periodic reboot - Hub uptime (${uptimeDays} days) is less than 70% of ${periodicFrequency ?: 'weekly'} interval (${requiredDays} days required)"

            // Reschedule for next occurrence
            def calendar = Calendar.getInstance(location.timeZone)
            calendar.setTime(new Date(state.nextPeriodicReboot))
            def nextReboot = advanceByFrequency(calendar)

            state.nextPeriodicReboot = nextReboot.time
            runOnce(nextReboot, performPeriodicReboot)

            log.info "Next periodic reboot check scheduled for ${nextReboot.format('yyyy-MM-dd HH:mm:ss')}"
            return
        }

        log.warn "Hub uptime is sufficient (${formatDays(hubUptimeSeconds)} days) - proceeding with reboot"
    }

    state.lastPeriodicReboot = now()
    state.periodicRebootCount = (state.periodicRebootCount ?: 0) + 1

    // Schedule next periodic reboot BEFORE rebooting
    def calendar = Calendar.getInstance(location.timeZone)
    calendar.setTime(new Date(state.nextPeriodicReboot))

    if (enableDebug) {
        // In debug mode, schedule for tomorrow at the same time
        calendar.add(Calendar.DAY_OF_MONTH, 1)
    } else {
        advanceByFrequency(calendar)
    }

    def nextReboot = calendar.time

    state.nextPeriodicReboot = nextReboot.time
    runOnce(nextReboot, performPeriodicReboot)

    log.info "Next periodic reboot scheduled for ${nextReboot.format('yyyy-MM-dd HH:mm:ss')}"
    
    // Pause briefly to ensure log messages and state are written
    pauseExecution(3000)
    
    if (!executeReboot()) {
        log.error "Periodic reboot command failed - you may need to reboot manually"
    }
}

// ──────────────────────────────────────────────
//  Utility
// ──────────────────────────────────────────────

def getDayOfWeekNumber(dayCode) {
    switch(dayCode) {
        case "SUN": return Calendar.SUNDAY
        case "MON": return Calendar.MONDAY
        case "TUE": return Calendar.TUESDAY
        case "WED": return Calendar.WEDNESDAY
        case "THU": return Calendar.THURSDAY
        case "FRI": return Calendar.FRIDAY
        case "SAT": return Calendar.SATURDAY
        default: return Calendar.SUNDAY
    }
}

def logDebug(msg) {
    if (enableDebug) {
        log.debug msg
    }
}
