/*
 * This is free and unencumbered software released into the public domain.
 * For more information, please refer to <https://unlicense.org>
 */

/**
 * SenseCAP Auto-Pages App v5.0.1
 *
 * Hubitat app companion to SenseCAP Auto-Pages Driver.
 * Manages up to 6 display pages on the SenseCAP Indicator D1 via openHASP/MQTT.
 *
 * Key features:
 *   - Up to 6 pages, each with independent sensor type and device selection
 *   - Supported types: smoke / motion / water / contact / light (switch)
 *   - Grid auto-sized 1x1 to 7x7 based on device count
 *   - Page display order adjustable via Move Up / Move Down buttons
 *   - Remove button on each page (except first) to delete pages
 *   - Add Page toggle appears at bottom of last page section
 *   - Devices sorted alphabetically (emoji-stripped) within each page
 *   - Light pages: tap-to-toggle via MQTT button events; periodic re-sync (configurable)
 *   - Sensor events gated during layout push via appPushInProgress state flag
 *   - syncAllSensors fires after returnToPage1 so all states correct after render
 *   - On save/reboot: rebootDisplay sent if MQTT connected, LWT triggers re-push
 *   - getPageOrder() for UI (all pages); activePageOrder() for driver operations
 *
 * Author: jlslate (slate)
 * Version: 5.0.1
 */

definition(
    name:        "SenseCAP Auto-Pages Monitor",
    namespace:   "jlslate",
    author:      "jlslate (slate)",
    description: "Auto-assigns sensors to pages by type on a SenseCAP Indicator display via MQTT",
    category:    "Integration",
    iconUrl:     "",
    iconX2Url:   ""
)

preferences {
    page(name: "mainPage")
}

//  UI 

def mainPage() {
    dynamicPage(name: "mainPage", title: "SenseCAP Auto-Pages Monitor", install: true, uninstall: true) {

        section("<b>App Name</b>") {
            label title: "Rename this app (optional)", required: false
        }

        section("<b>SenseCAP Indicator Device</b>") {
            input name:     "indicatorDevice",
                  type:     "capability.actuator",
                  title:    "Select your SenseCAP Auto-Pages device",
                  required: true,
                  multiple: false
        }

        List<Integer> ord = getPageOrder()
        int totalPages = numberOfPages()

        //  Pages -- shown in current display order with inline move buttons 
        ord.eachWithIndex { srcPage, dispIdx ->
            int dispPos = dispIdx + 1
            boolean canUp = (dispPos > 1)
            boolean canDn = (dispPos < ord.size())

            if (srcPage == 1 ||
                (srcPage == 2 && settings.addPage2) ||
                (srcPage == 3 && settings.addPage2 && settings.addPage3) ||
                (srcPage == 4 && settings.addPage2 && settings.addPage3 && settings.addPage4) ||
                (srcPage == 5 && settings.addPage2 && settings.addPage3 && settings.addPage4 && settings.addPage5) ||
                (srcPage == 6 && settings.addPage2 && settings.addPage3 && settings.addPage4 && settings.addPage5 && settings.addPage6)) {

                section("<b>Page ${dispPos}</b>") {
                    // Sensor type + move buttons on the same row using width

                    input name:           "page${srcPage}Type",
                          type:           "enum",
                          title:          "Sensor type",
                          options:        sensorTypeOptions(),
                          required:       false,
                          defaultValue:   null,
                          submitOnChange: true,
                          width:          6
                    if (canUp) {
                        input name:  "moveUp_${dispPos}",
                              type:  "button",
                              title: "&#9650; Move Up",
                              width: canDn ? 2 : 3
                    }
                    if (canDn) {
                        input name:  "moveDn_${dispPos}",
                              type:  "button",
                              title: "&#9660; Move Down",
                              width: canUp ? 2 : 3
                    }
                    if (dispPos > 1) {
                        input name:  "deletePage_${srcPage}",
                              type:  "button",
                              title: "&#10006; Remove",
                              width: 2
                    }
                    String pType = settings["page${srcPage}Type"] ?: ""
                    if (pType) {
                        // Use type-scoped input name so switching types starts fresh
                        String devInputName = "page${srcPage}Devices_${pType}"
                        input name:           devInputName,
                              type:           capabilityFor(pType),
                              title:          "Select devices",
                              required:       false,
                              multiple:       true,
                              submitOnChange: true
                        int cnt = settings[devInputName]?.size() ?: 0
                        int n   = gridSizeFor(cnt)
                        paragraph "Devices: <b>${cnt}</b> -> Grid: <b>${n}x${n}</b>"
                    }
                    // Show add-page toggle at the last DISPLAYED page
                    int activePages = ord.size()
                    boolean isLastDisplayed = (dispPos == activePages)
                    if (isLastDisplayed && activePages < 6) {
                        switch (activePages) {
                            case 1: input name: "addPage2", type: "bool", title: "Add page 2", defaultValue: false, submitOnChange: true; break
                            case 2: input name: "addPage3", type: "bool", title: "Add page 3", defaultValue: false, submitOnChange: true; break
                            case 3: input name: "addPage4", type: "bool", title: "Add page 4", defaultValue: false, submitOnChange: true; break
                            case 4: input name: "addPage5", type: "bool", title: "Add page 5", defaultValue: false, submitOnChange: true; break
                            case 5: input name: "addPage6", type: "bool", title: "Add page 6", defaultValue: false, submitOnChange: true; break
                        }
                    }
                }
            }
        }

        section("<b>Options</b>") {
            input name:         "syncOnStartup",
                  type:         "bool",
                  title:        "Sync all sensor states on startup/save",
                  defaultValue: true
            input name:         "lightSyncInterval",
                  type:         "enum",
                  title:        "Re-sync light states every",
                  options:      ["0":"Never","5":"5 minutes","10":"10 minutes","30":"30 minutes"],
                  defaultValue: "10"
            input name:         "logLevel",
                  type:         "enum",
                  title:        "Logging Level",
                  options:      ["0":"None","1":"Info only","2":"Info + Debug"],
                  defaultValue: "1",
                  required:     true
        }

        section("<b>Status</b>") {
            int total = activePageOrder().size()
            int devices = activePageOrder().sum { pg -> pageDevices(pg)?.size() ?: 0 } as int
            paragraph "Pages: <b>${total}</b> -- Total devices: <b>${devices}</b>"
            if (settings.indicatorDevice) {
                paragraph "MQTT status: <b>${settings.indicatorDevice.currentValue('mqttStatus') ?: 'unknown'}</b>"
            }
        }
    }
}

//  Helpers 

private Map sensorTypeOptions() {
    ["smoke":"Smoke detectors", "motion":"Motion sensors",
     "water":"Water sensors",   "contact":"Contact sensors",
     "light":"Lights (switch)"]
}

private String capabilityFor(String sType) {
    switch (sType) {
        case "smoke":   return "capability.smokeDetector"
        case "motion":  return "capability.motionSensor"
        case "water":   return "capability.waterSensor"
        case "contact": return "capability.contactSensor"
        case "light":   return "capability.switch"
        default:        return "capability.sensor"
    }
}

private String attributeFor(String sType) {
    switch (sType) {
        case "smoke":   return "smoke"
        case "motion":  return "motion"
        case "water":   return "water"
        case "contact": return "contact"
        case "light":   return "switch"
        default:        return "motion"
    }
}

private String activeValueFor(String sType) {
    switch (sType) {
        case "smoke":   return "detected"
        case "motion":  return "active"
        case "water":   return "wet"
        case "contact": return "open"
        case "light":   return "on"
        default:        return "active"
    }
}

private int numberOfPages() {
    if (!settings.addPage2) return 1
    if (!settings.addPage3) return 2
    if (!settings.addPage4) return 3
    if (!settings.addPage5) return 4
    if (!settings.addPage6) return 5
    return 6
}

private List pageDevices(int page) {
    String sType = settings["page${page}Type"] ?: ""
    if (!sType) return []
    return (settings["page${page}Devices_${sType}"] ?: []) as List
}

private String pageType(int page) {
    return (settings["page${page}Type"] ?: "motion") as String
}

// Returns the current display order -- list of source page indices in display order
private List<Integer> displayOrder() {
    return getPageOrder()
}

private List<Integer> getPageOrder() {
    // Returns ALL configured pages (including "none" type) for UI rendering
    int total = numberOfPages()
    List stored = state.pageOrder ?: []
    List<Integer> order = stored.findAll { it >= 1 && it <= total }.collect { it as int }
    (1..total).each { pg -> if (!order.contains(pg)) order << pg }
    return order
}

private List<Integer> activePageOrder() {
    // Returns configured pages for driver operations (same as getPageOrder now)
    return getPageOrder()
}

def appButtonHandler(String buttonName) {
    List<Integer> order = getPageOrder()
    boolean changed = false
    if (buttonName.startsWith("deletePage_")) {
        int srcPage = buttonName.replace("deletePage_", "").toInteger()
        int total = numberOfPages()
        // Shift type settings down; clear devices for all shifted slots
        // (capability inputs can't be reliably shifted via updateSetting --
        //  user will re-select devices; prevType state clear ensures clean picker)
        for (int i = srcPage; i < total; i++) {
            int next = i + 1
            String nextType = settings["page${next}Type"] ?: ""
            if (nextType) {
                app.updateSetting("page${i}Type", [value: nextType, type: "enum"])
            } else {
                app.clearSetting("page${i}Type")
            }
            state["prevType${i}"] = nextType
        }
        // Clear the last page slot (now vacated after shift)
        app.clearSetting("page${total}Type")
        app.clearSetting("page${total}Devices")
        state["prevType${total}"] = ""
        // Turn off the addPage toggle for the old last page to reduce total by 1
        switch (total) {
            case 2: app.updateSetting("addPage2", [value: false, type: "bool"]); break
            case 3: app.updateSetting("addPage3", [value: false, type: "bool"]); break
            case 4: app.updateSetting("addPage4", [value: false, type: "bool"]); break
            case 5: app.updateSetting("addPage5", [value: false, type: "bool"]); break
            case 6: app.updateSetting("addPage6", [value: false, type: "bool"]); break
        }
        // Reset pageOrder so it rebuilds from scratch with new total
        state.pageOrder = null
        infoLog "[AutoPages] Deleted page at position ${srcPage}, shifted pages down, new total ${total - 1}"
        return
    }
    if (buttonName.startsWith("moveUp_")) {
        int pos = buttonName.replace("moveUp_", "").toInteger()
        if (pos > 1 && pos <= order.size()) {
            int tmp = order[pos - 2]
            order[pos - 2] = order[pos - 1]
            order[pos - 1] = tmp
            changed = true
        }
    } else if (buttonName.startsWith("moveDn_")) {
        int pos = buttonName.replace("moveDn_", "").toInteger()
        if (pos >= 1 && pos < order.size()) {
            int tmp = order[pos - 1]
            order[pos - 1] = order[pos]
            order[pos] = tmp
            changed = true
        }
    }
    if (changed) {
        state.pageOrder = order
        infoLog "[AutoPages] Page order: ${order.collect { pageType(it) }.join(' -> ')}"
    }
}

//  Lifecycle 

def installed() {
    infoLog "[AutoPages] App installed"
    initialize()
}

def updated() {
    infoLog "[AutoPages] App updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    unsubscribe()
}

def initialize() {
    if (!settings.indicatorDevice) {
        infoLog "[AutoPages] No indicator device selected"
        return
    }

int total = numberOfPages()
    List<Integer> order = displayOrder()
    state.pageOrder = order
    infoLog "[AutoPages] Display order: ${order.collect { pageType(it) }.join(' -> ')}"

    // Push grid layouts in display order -- display slot 1 gets order[0]'s grid etc.
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs = pageDevices(srcPage)
        String grid = nxnString(devs.size())
        try {
            settings.indicatorDevice."setPage${dispPage}GridLayout"(grid)
        } catch (Exception e) {
            infoLog "[AutoPages] WARN -- setPage${dispPage}GridLayout failed: ${e.message}"
        }
    }
    // Use filtered page count (excludes "none" pages) for the driver
    int activePages = activePageOrder().size()
    try {
        settings.indicatorDevice.setNumberOfPages(activePages)
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- setNumberOfPages failed: ${e.message}"
    }

    // Build slot maps keyed by DISPLAY page number (not source page number)
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs    = sortDevicesByName(pageDevices(srcPage))
        String sType = pageType(srcPage)
        Map slotMap  = buildSlotMap(devs)
        state["slotMap${dispPage}"]  = slotMap
        state["pageType${dispPage}"] = sType
        subscribePageDevices(dispPage, devs, sType)
    }

    // Subscribe to display reboot
    subscribe(settings.indicatorDevice, "displayRebooted",    displayRebootedHandler)
    subscribe(settings.indicatorDevice, "layoutPushComplete", layoutPushCompleteHandler)
    subscribe(settings.indicatorDevice, "lightTapped",        lightTappedHandler)

    // Reboot display to clear stale state, then wait for LWT before pushing layouts
    String mqttSt = settings.indicatorDevice.currentValue("mqttStatus") ?: ""
    if (mqttSt.startsWith("Connected")) {
        infoLog "[AutoPages] Rebooting display to clear stale state"
        try { settings.indicatorDevice.rebootDisplay() } catch (Exception e) { infoLog "[AutoPages] WARN -- rebootDisplay: ${e.message}" }
        // LWT online will trigger displayRebootedHandler -> pushSlotTypesAndLayouts
        // Fallback in case LWT is missed
        runIn(35, "pushSlotTypesAndLayouts")
    } else {
        runIn(2, "pushSlotTypesAndLayouts")
    }
    // Schedule periodic light state resync
    unschedule("syncLightStates")
    int syncMins = (settings.lightSyncInterval ?: "10") as int
    if (syncMins > 0) schedule("0 */${syncMins} * ? * *", syncLightStates)

    infoLog "[AutoPages] Initialized -- ${total} page(s)"
}

private void subscribePageDevices(int page, List devices, String sType) {
    String attr = attributeFor(sType)
    String handler = "${sType}Handler"
    // Use generic handler with page/type encoded in state
    devices.each { dev ->
        if (!dev) return
        switch (sType) {
            case "smoke":   subscribe(dev, "smoke",   smokeHandler);   break
            case "motion":  subscribe(dev, "motion",  motionHandler);  break
            case "water":   subscribe(dev, "water",   waterHandler);   break
            case "contact": subscribe(dev, "contact", contactHandler); break
            case "light":   subscribe(dev, "switch",  lightHandler);   break
        }
    }
}

//  Push layout 

def pushSlotTypesAndLayouts() {
    int total = numberOfPages()
    // Push slot types AND labels into driver state before any layout rendering starts.
    // Use display order so display slot 1 gets the right page's data.
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs    = sortDevicesByName(pageDevices(srcPage))
        String sType = pageType(srcPage)
        pushPageSlotTypes(dispPage, devs, sType)
        pauseExecution(100)
        pushPageLabels(dispPage, devs)
        pauseExecution(100)
    }
    // Extra pause to ensure all driver state writes have persisted
    pauseExecution(500)
    int activePages = activePageOrder().size()
    try { settings.indicatorDevice.setNumberOfPages(activePages) } catch (Exception e) { }
    pauseExecution(500)
    state.appPushInProgress = true
    try {
        settings.indicatorDevice.pushAllLayouts(activePages)
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- pushAllLayouts failed: ${e.message}"
    }
    // Sync is triggered by displayRebooted event fired by driver after last page renders
}

def pushAllLabels() {
    int total = numberOfPages()
    (1..total).each { pg ->
        List devs = pageDevices(pg)
        pushPageLabels(pg, devs)
        pauseExecution(500)
    }
}

private void pushPageSlotTypes(int page, List devices, String sType) {
    int n = gridSizeFor(devices.size())
    Map types = [:]
    (1..n*n).each { slot ->
        types[slot] = (slot <= devices.size()) ? sType : "none"
    }
    try {
        switch (page) {
            case 1: settings.indicatorDevice.updatePage1SlotTypes(types); break
            case 2: settings.indicatorDevice.updatePage2SlotTypes(types); break
            case 3: settings.indicatorDevice.updatePage3SlotTypes(types); break
            case 4: settings.indicatorDevice.updatePage4SlotTypes(types); break
            case 5: settings.indicatorDevice.updatePage5SlotTypes(types); break
            case 6: settings.indicatorDevice.updatePage6SlotTypes(types); break
        }
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- updatePage${page}SlotTypes failed: ${e.message}"
    }
}

private void pushPageLabels(int page, List devices) {
    if (!devices) return
    int n        = gridSizeFor(devices.size())
    int maxChars = maxCharsForGrid(n)
    Map labels   = [:]
    devices.eachWithIndex { dev, idx ->
        if (!dev) return
        String name = stripEmoji(dev.displayName ?: "")
        if (name) labels[idx + 1] = wrapLabel(name, maxChars)
    }
    if (!labels) return
    try {
        switch (page) {
            case 1: settings.indicatorDevice.updatePage1Labels(labels); break
            case 2: settings.indicatorDevice.updatePage2Labels(labels); break
            case 3: settings.indicatorDevice.updatePage3Labels(labels); break
            case 4: settings.indicatorDevice.updatePage4Labels(labels); break
            case 5: settings.indicatorDevice.updatePage5Labels(labels); break
            case 6: settings.indicatorDevice.updatePage6Labels(labels); break
        }
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- updatePage${page}Labels failed: ${e.message}"
    }
}

//  Event handlers 
// Each handler searches all pages for the device, since a device could
// theoretically appear on any page of its type.

def lightHandler(evt) {
    handleEvent(evt, "light", "on")
}

def smokeHandler(evt) {
    handleEvent(evt, "smoke", "detected")
}

def motionHandler(evt) {
    handleEvent(evt, "motion", "active")
}

def waterHandler(evt) {
    handleEvent(evt, "water", "wet")
}

def contactHandler(evt) {
    handleEvent(evt, "contact", "open")
}

private void handleEvent(evt, String sType, String activeValue) {
    // Don't update display during layout push -- tiles not built yet
    if (state.appPushInProgress) {
        debugLog "[AutoPages] Skipping event during push: ${evt.displayName} ${evt.value}"
        return
    }
    String deviceId = evt.device.id.toString()
    int total       = numberOfPages()
    boolean found   = false
    (1..total).each { pg ->
        if ((state["pageType${pg}"] ?: "") != sType) return
        Map slotMap = state["slotMap${pg}"] ?: [:]
        int slot    = (slotMap[deviceId] ?: 0) as int
        if (slot < 1) return
        found = true
        debugLog "Event p${pg}s${slot} ${sType} (${evt.displayName}): ${evt.value}"
        if (evt.value == activeValue) {
            settings.indicatorDevice."setPage${pg}MotionActive"(slot)
        } else {
            settings.indicatorDevice."setPage${pg}MotionInactive"(slot)
        }
    }
    if (!found) infoLog "[AutoPages] WARN -- device ${deviceId} not found in any ${sType} slot map"
}

//  Display reboot 

def lightTappedHandler(evt) {
    infoLog "[AutoPages] lightTappedHandler fired: ${evt.value}"
    List parts = evt.value?.split(",")
    if (!parts || parts.size() < 2) { infoLog "[AutoPages] WARN bad lightTapped value"; return }
    int dispPage = parts[0] as int
    int slot     = parts[1] as int
    List<Integer> order = getPageOrder()
    infoLog "[AutoPages] dispPage=${dispPage} slot=${slot} order=${order}"
    if (dispPage < 1 || dispPage > order.size()) { infoLog "[AutoPages] WARN dispPage out of range"; return }
    int srcPage  = order[dispPage - 1]
    String sType = pageType(srcPage)
    infoLog "[AutoPages] srcPage=${srcPage} sType=${sType}"
    if (sType != "light") { infoLog "[AutoPages] WARN not a light page"; return }
    List devs = pageDevices(srcPage)
    infoLog "[AutoPages] devs.size=${devs?.size()} slot=${slot}"
    if (slot < 1 || slot > (devs?.size() ?: 0)) { infoLog "[AutoPages] WARN slot out of range"; return }
    def dev = devs[slot - 1]
    if (!dev) { infoLog "[AutoPages] WARN dev is null"; return }
    String curState = dev.currentValue("switch") ?: "off"
    infoLog "[AutoPages] Toggling light: ${dev.displayName} (p${dispPage}s${slot}) currently ${curState}"
    if (curState == "on") { dev.off() } else { dev.on() }
}

def layoutPushCompleteHandler(evt) {
    state.appPushInProgress = false
    infoLog "[AutoPages] Layout push complete -- syncing sensor states"
    syncAllSensors()
}

def displayRebootedHandler(evt) {
    state.appPushInProgress = false
    infoLog "[AutoPages] Display rebooted -- repushing everything"
    int total = numberOfPages()
    List<Integer> ord = activePageOrder()
    state.pageOrder = ord
    ord.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs = pageDevices(srcPage)
        try { settings.indicatorDevice."setPage${dispPage}GridLayout"(nxnString(devs.size())) } catch (Exception e) { }
    }
    try { settings.indicatorDevice.setNumberOfPages(getPageOrder().size()) } catch (Exception e) { }
    runIn(2, "pushSlotTypesAndLayouts")
}

//  State sync 

def syncLightStates() {
    int total = numberOfPages()
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        if (pageType(srcPage) != "light") return
        List devs = sortDevicesByName(pageDevices(srcPage))
        devs.eachWithIndex { dev, idx ->
            if (!dev) return
            int slot   = idx + 1
            String cur = dev.currentValue("switch") ?: "off"
            if (cur == "on") {
                settings.indicatorDevice."setPage${dispPage}MotionActive"(slot)
            } else {
                settings.indicatorDevice."setPage${dispPage}MotionInactive"(slot)
            }
            pauseExecution(30)
        }
    }
}

def syncAllSensors() {
    if (state.appPushInProgress) {
        infoLog "[AutoPages] Skipping sync -- layout push in progress"
        return
    }
    infoLog "[AutoPages] Syncing all sensor states"
    int total = numberOfPages()
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage  = dispIdx + 1
        List devs     = sortDevicesByName(pageDevices(srcPage))
        String sType  = pageType(srcPage)
        String attr   = attributeFor(sType)
        String actVal = activeValueFor(sType)
        int n          = gridSizeFor(devs.size())
        int totalSlots = n * n
        devs.eachWithIndex { dev, idx ->
            if (!dev) return
            int slot   = idx + 1
            String cur = dev.currentValue(attr) ?: ""
            debugLog "Sync p${dispPage}s${slot} ${sType} (${dev.displayName}) = ${cur}"
            if (cur == actVal) {
                settings.indicatorDevice."setPage${dispPage}MotionActive"(slot)
            } else {
                settings.indicatorDevice."setPage${dispPage}MotionInactive"(slot)
            }
            pauseExecution(40)
        }
        if (devs.size() < totalSlots) {
            ((devs.size() + 1)..totalSlots).each { slot ->
                settings.indicatorDevice."setPage${dispPage}SlotEmpty"(slot)
                pauseExecution(30)
            }
        }
    }
}

//  Slot map 

private Map buildSlotMap(List devices) {
    Map m = [:]
    if (!devices) return m
    devices.eachWithIndex { dev, idx ->
        if (dev) m[dev.id.toString()] = idx + 1
    }
    return m
}

//  Grid sizing 

private int gridSizeFor(int count) {
    if (count <= 1)  return 1
    if (count <= 4)  return 2
    if (count <= 9)  return 3
    if (count <= 16) return 4
    if (count <= 25) return 5
    if (count <= 36) return 6
    return 7
}

private String nxnString(int count) {
    int n = gridSizeFor(count)
    return "${n}x${n}"
}

private int maxCharsForGrid(int n) {
    switch (n) {
        case 1:  return 30
        case 2:  return 16
        case 3:  return 11
        case 4:  return 7
        case 5:  return 6
        case 6:  return 5
        default: return 4
    }
}

//  Label helpers 

private String stripEmoji(String text) {
    if (!text) return ""
    return text.replaceAll(/[^\x20-\x7E]/, "").replaceAll(/\s+/, " ").trim()
}

private List sortDevicesByName(List devices) {
    if (!devices) return []
    return devices.findAll { it != null }
                  .sort { a, b -> stripEmoji(a.displayName ?: "").compareToIgnoreCase(stripEmoji(b.displayName ?: "")) }
}

private String wrapLabel(String text, int maxChars) {
    if (!text || text.length() <= maxChars) return text ?: ""
    List<String> words = text.split(" ") as List
    List<String> lines = []
    String current = ""
    words.each { word ->
        if (current.isEmpty()) {
            current = word
        } else if ((current + " " + word).length() <= maxChars) {
            current += " " + word
        } else {
            lines << current
            current = word
        }
    }
    if (current) lines << current
    return lines.join("\n")
}

//  Logging 

private void infoLog(String msg) {
    if ((settings.logLevel ?: "1") != "0") log.info msg
}

private void debugLog(String msg) {
    if ((settings.logLevel ?: "1") == "2") log.debug msg
}
 * Version: 5.0.0
 */

definition(
    name:        "SenseCAP Auto-Pages Monitor",
    namespace:   "jlslate",
    author:      "jlslate (slate)",
    description: "Auto-assigns sensors to pages by type on a SenseCAP Indicator display via MQTT",
    category:    "Integration",
    iconUrl:     "",
    iconX2Url:   ""
)

preferences {
    page(name: "mainPage")
}

//  UI 

def mainPage() {
    dynamicPage(name: "mainPage", title: "SenseCAP Auto-Pages Monitor", install: true, uninstall: true) {

        section("<b>App Name</b>") {
            label title: "Rename this app (optional)", required: false
        }

        section("<b>SenseCAP Indicator Device</b>") {
            input name:     "indicatorDevice",
                  type:     "capability.actuator",
                  title:    "Select your SenseCAP Auto-Pages device",
                  required: true,
                  multiple: false
        }

        List<Integer> ord = getPageOrder()
        int totalPages = numberOfPages()

        //  Pages -- shown in current display order with inline move buttons 
        ord.eachWithIndex { srcPage, dispIdx ->
            int dispPos = dispIdx + 1
            boolean canUp = (dispPos > 1)
            boolean canDn = (dispPos < ord.size())

            if (srcPage == 1 ||
                (srcPage == 2 && settings.addPage2) ||
                (srcPage == 3 && settings.addPage2 && settings.addPage3) ||
                (srcPage == 4 && settings.addPage2 && settings.addPage3 && settings.addPage4) ||
                (srcPage == 5 && settings.addPage2 && settings.addPage3 && settings.addPage4 && settings.addPage5) ||
                (srcPage == 6 && settings.addPage2 && settings.addPage3 && settings.addPage4 && settings.addPage5 && settings.addPage6)) {

                section("<b>Page ${dispPos}</b>") {
                    // Sensor type + move buttons on the same row using width
                    input name:           "page${srcPage}Type",
                          type:           "enum",
                          title:          "Sensor type",
                          options:        sensorTypeOptions(),
                          required:       true,
                          defaultValue:   "smoke",
                          submitOnChange: true,
                          width:          6
                    if (canUp) {
                        input name:  "moveUp_${dispPos}",
                              type:  "button",
                              title: "&#9650; Move Up",
                              width: canDn ? 2 : 3
                    }
                    if (canDn) {
                        input name:  "moveDn_${dispPos}",
                              type:  "button",
                              title: "&#9660; Move Down",
                              width: canUp ? 2 : 3
                    }
                    if (dispPos > 1) {
                        input name:  "deletePage_${srcPage}",
                              type:  "button",
                              title: "&#10006; Remove",
                              width: 2
                    }
                    String pType = settings["page${srcPage}Type"] ?: ""
                    if (pType) {
                        input name:           "page${srcPage}Devices",
                              type:           capabilityFor(pType),
                              title:          "Select devices",
                              required:       false,
                              multiple:       true,
                              submitOnChange: true
                        int cnt = settings["page${srcPage}Devices"]?.size() ?: 0
                        int n   = gridSizeFor(cnt)
                        paragraph "Devices: <b>${cnt}</b> -> Grid: <b>${n}x${n}</b>"
                    }
                    // Show add-page toggle at the last DISPLAYED page
                    int activePages = ord.size()
                    boolean isLastDisplayed = (dispPos == activePages)
                    if (isLastDisplayed && activePages < 6) {
                        switch (activePages) {
                            case 1: input name: "addPage2", type: "bool", title: "Add page 2", defaultValue: false, submitOnChange: true; break
                            case 2: input name: "addPage3", type: "bool", title: "Add page 3", defaultValue: false, submitOnChange: true; break
                            case 3: input name: "addPage4", type: "bool", title: "Add page 4", defaultValue: false, submitOnChange: true; break
                            case 4: input name: "addPage5", type: "bool", title: "Add page 5", defaultValue: false, submitOnChange: true; break
                            case 5: input name: "addPage6", type: "bool", title: "Add page 6", defaultValue: false, submitOnChange: true; break
                        }
                    }
                }
            }
        }

        section("<b>Options</b>") {
            input name:         "syncOnStartup",
                  type:         "bool",
                  title:        "Sync all sensor states on startup/save",
                  defaultValue: true
            input name:         "lightSyncInterval",
                  type:         "enum",
                  title:        "Re-sync light states every",
                  options:      ["0":"Never","5":"5 minutes","10":"10 minutes","30":"30 minutes"],
                  defaultValue: "10"
            input name:         "logLevel",
                  type:         "enum",
                  title:        "Logging Level",
                  options:      ["0":"None","1":"Info only","2":"Info + Debug"],
                  defaultValue: "1",
                  required:     true
        }

        section("<b>Status</b>") {
            int total = activePageOrder().size()
            int devices = activePageOrder().sum { pg -> pageDevices(pg)?.size() ?: 0 } as int
            paragraph "Pages: <b>${total}</b> -- Total devices: <b>${devices}</b>"
            if (settings.indicatorDevice) {
                paragraph "MQTT status: <b>${settings.indicatorDevice.currentValue('mqttStatus') ?: 'unknown'}</b>"
            }
        }
    }
}

//  Helpers 

private Map sensorTypeOptions() {
    ["smoke":"Smoke detectors", "motion":"Motion sensors",
     "water":"Water sensors",   "contact":"Contact sensors",
     "light":"Lights (switch)"]
}

private String capabilityFor(String sType) {
    switch (sType) {
        case "smoke":   return "capability.smokeDetector"
        case "motion":  return "capability.motionSensor"
        case "water":   return "capability.waterSensor"
        case "contact": return "capability.contactSensor"
        case "light":   return "capability.switch"
        default:        return "capability.sensor"
    }
}

private String attributeFor(String sType) {
    switch (sType) {
        case "smoke":   return "smoke"
        case "motion":  return "motion"
        case "water":   return "water"
        case "contact": return "contact"
        case "light":   return "switch"
        default:        return "motion"
    }
}

private String activeValueFor(String sType) {
    switch (sType) {
        case "smoke":   return "detected"
        case "motion":  return "active"
        case "water":   return "wet"
        case "contact": return "open"
        case "light":   return "on"
        default:        return "active"
    }
}

private int numberOfPages() {
    if (!settings.addPage2) return 1
    if (!settings.addPage3) return 2
    if (!settings.addPage4) return 3
    if (!settings.addPage5) return 4
    if (!settings.addPage6) return 5
    return 6
}

private List pageDevices(int page) {
    return (settings["page${page}Devices"] ?: []) as List
}

private String pageType(int page) {
    return (settings["page${page}Type"] ?: "motion") as String
}

// Returns the current display order -- list of source page indices in display order
private List<Integer> displayOrder() {
    return getPageOrder()
}

private List<Integer> getPageOrder() {
    // Returns ALL configured pages (including "none" type) for UI rendering
    int total = numberOfPages()
    List stored = state.pageOrder ?: []
    List<Integer> order = stored.findAll { it >= 1 && it <= total }.collect { it as int }
    (1..total).each { pg -> if (!order.contains(pg)) order << pg }
    return order
}

private List<Integer> activePageOrder() {
    // Returns configured pages for driver operations (same as getPageOrder now)
    return getPageOrder()
}

def appButtonHandler(String buttonName) {
    List<Integer> order = getPageOrder()
    boolean changed = false
    if (buttonName.startsWith("deletePage_")) {
        int srcPage = buttonName.replace("deletePage_", "").toInteger()
        // Turn off the addPage toggle for this source page number and above
        // This collapses the page out of the numbering
        switch (srcPage) {
            case 2: app.updateSetting("addPage2", [value: false, type: "bool"]); break
            case 3: app.updateSetting("addPage3", [value: false, type: "bool"]); break
            case 4: app.updateSetting("addPage4", [value: false, type: "bool"]); break
            case 5: app.updateSetting("addPage5", [value: false, type: "bool"]); break
            case 6: app.updateSetting("addPage6", [value: false, type: "bool"]); break
        }
        // Also clear devices and type for this page
        app.clearSetting("page${srcPage}Devices")
        app.clearSetting("page${srcPage}Type")
        infoLog "[AutoPages] Deleted source page ${srcPage}"
        return
    }
    if (buttonName.startsWith("moveUp_")) {
        int pos = buttonName.replace("moveUp_", "").toInteger()
        if (pos > 1 && pos <= order.size()) {
            int tmp = order[pos - 2]
            order[pos - 2] = order[pos - 1]
            order[pos - 1] = tmp
            changed = true
        }
    } else if (buttonName.startsWith("moveDn_")) {
        int pos = buttonName.replace("moveDn_", "").toInteger()
        if (pos >= 1 && pos < order.size()) {
            int tmp = order[pos - 1]
            order[pos - 1] = order[pos]
            order[pos] = tmp
            changed = true
        }
    }
    if (changed) {
        state.pageOrder = order
        infoLog "[AutoPages] Page order: ${order.collect { pageType(it) }.join(' -> ')}"
    }
}

//  Lifecycle 

def installed() {
    infoLog "[AutoPages] App installed"
    initialize()
}

def updated() {
    infoLog "[AutoPages] App updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    unsubscribe()
}

def initialize() {
    if (!settings.indicatorDevice) {
        infoLog "[AutoPages] No indicator device selected"
        return
    }

    int total = numberOfPages()
    List<Integer> order = displayOrder()
    state.pageOrder = order
    infoLog "[AutoPages] Display order: ${order.collect { pageType(it) }.join(' -> ')}"

    // Push grid layouts in display order -- display slot 1 gets order[0]'s grid etc.
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs = pageDevices(srcPage)
        String grid = nxnString(devs.size())
        try {
            settings.indicatorDevice."setPage${dispPage}GridLayout"(grid)
        } catch (Exception e) {
            infoLog "[AutoPages] WARN -- setPage${dispPage}GridLayout failed: ${e.message}"
        }
    }
    // Use filtered page count (excludes "none" pages) for the driver
    int activePages = activePageOrder().size()
    try {
        settings.indicatorDevice.setNumberOfPages(activePages)
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- setNumberOfPages failed: ${e.message}"
    }

    // Build slot maps keyed by DISPLAY page number (not source page number)
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs    = sortDevicesByName(pageDevices(srcPage))
        String sType = pageType(srcPage)
        Map slotMap  = buildSlotMap(devs)
        state["slotMap${dispPage}"]  = slotMap
        state["pageType${dispPage}"] = sType
        subscribePageDevices(dispPage, devs, sType)
    }

    // Subscribe to display reboot
    subscribe(settings.indicatorDevice, "displayRebooted",    displayRebootedHandler)
    subscribe(settings.indicatorDevice, "layoutPushComplete", layoutPushCompleteHandler)
    subscribe(settings.indicatorDevice, "lightTapped",        lightTappedHandler)

    // Reboot display to clear stale state, then wait for LWT before pushing layouts
    String mqttSt = settings.indicatorDevice.currentValue("mqttStatus") ?: ""
    if (mqttSt.startsWith("Connected")) {
        infoLog "[AutoPages] Rebooting display to clear stale state"
        try { settings.indicatorDevice.rebootDisplay() } catch (Exception e) { infoLog "[AutoPages] WARN -- rebootDisplay: ${e.message}" }
        // LWT online will trigger displayRebootedHandler -> pushSlotTypesAndLayouts
        // Fallback in case LWT is missed
        runIn(35, "pushSlotTypesAndLayouts")
    } else {
        runIn(2, "pushSlotTypesAndLayouts")
    }
    // Schedule periodic light state resync
    unschedule("syncLightStates")
    int syncMins = (settings.lightSyncInterval ?: "10") as int
    if (syncMins > 0) schedule("0 */${syncMins} * ? * *", syncLightStates)

    infoLog "[AutoPages] Initialized -- ${total} page(s)"
}

private void subscribePageDevices(int page, List devices, String sType) {
    String attr = attributeFor(sType)
    String handler = "${sType}Handler"
    // Use generic handler with page/type encoded in state
    devices.each { dev ->
        if (!dev) return
        switch (sType) {
            case "smoke":   subscribe(dev, "smoke",   smokeHandler);   break
            case "motion":  subscribe(dev, "motion",  motionHandler);  break
            case "water":   subscribe(dev, "water",   waterHandler);   break
            case "contact": subscribe(dev, "contact", contactHandler); break
            case "light":   subscribe(dev, "switch",  lightHandler);   break
        }
    }
}

//  Push layout 

def pushSlotTypesAndLayouts() {
    int total = numberOfPages()
    // Push slot types AND labels into driver state before any layout rendering starts.
    // Use display order so display slot 1 gets the right page's data.
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs    = sortDevicesByName(pageDevices(srcPage))
        String sType = pageType(srcPage)
        pushPageSlotTypes(dispPage, devs, sType)
        pauseExecution(100)
        pushPageLabels(dispPage, devs)
        pauseExecution(100)
    }
    // Extra pause to ensure all driver state writes have persisted
    pauseExecution(500)
    int activePages = activePageOrder().size()
    try { settings.indicatorDevice.setNumberOfPages(activePages) } catch (Exception e) { }
    pauseExecution(500)
    state.appPushInProgress = true
    try {
        settings.indicatorDevice.pushAllLayouts(activePages)
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- pushAllLayouts failed: ${e.message}"
    }
    // Sync is triggered by displayRebooted event fired by driver after last page renders
}

def pushAllLabels() {
    int total = numberOfPages()
    (1..total).each { pg ->
        List devs = pageDevices(pg)
        pushPageLabels(pg, devs)
        pauseExecution(500)
    }
}

private void pushPageSlotTypes(int page, List devices, String sType) {
    int n = gridSizeFor(devices.size())
    Map types = [:]
    (1..n*n).each { slot ->
        types[slot] = (slot <= devices.size()) ? sType : "none"
    }
    try {
        switch (page) {
            case 1: settings.indicatorDevice.updatePage1SlotTypes(types); break
            case 2: settings.indicatorDevice.updatePage2SlotTypes(types); break
            case 3: settings.indicatorDevice.updatePage3SlotTypes(types); break
            case 4: settings.indicatorDevice.updatePage4SlotTypes(types); break
            case 5: settings.indicatorDevice.updatePage5SlotTypes(types); break
            case 6: settings.indicatorDevice.updatePage6SlotTypes(types); break
        }
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- updatePage${page}SlotTypes failed: ${e.message}"
    }
}

private void pushPageLabels(int page, List devices) {
    if (!devices) return
    int n        = gridSizeFor(devices.size())
    int maxChars = maxCharsForGrid(n)
    Map labels   = [:]
    devices.eachWithIndex { dev, idx ->
        if (!dev) return
        String name = stripEmoji(dev.displayName ?: "")
        if (name) labels[idx + 1] = wrapLabel(name, maxChars)
    }
    if (!labels) return
    try {
        switch (page) {
            case 1: settings.indicatorDevice.updatePage1Labels(labels); break
            case 2: settings.indicatorDevice.updatePage2Labels(labels); break
            case 3: settings.indicatorDevice.updatePage3Labels(labels); break
            case 4: settings.indicatorDevice.updatePage4Labels(labels); break
            case 5: settings.indicatorDevice.updatePage5Labels(labels); break
            case 6: settings.indicatorDevice.updatePage6Labels(labels); break
        }
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- updatePage${page}Labels failed: ${e.message}"
    }
}

//  Event handlers 
// Each handler searches all pages for the device, since a device could
// theoretically appear on any page of its type.

def lightHandler(evt) {
    handleEvent(evt, "light", "on")
}

def smokeHandler(evt) {
    handleEvent(evt, "smoke", "detected")
}

def motionHandler(evt) {
    handleEvent(evt, "motion", "active")
}

def waterHandler(evt) {
    handleEvent(evt, "water", "wet")
}

def contactHandler(evt) {
    handleEvent(evt, "contact", "open")
}

private void handleEvent(evt, String sType, String activeValue) {
    // Don't update display during layout push -- tiles not built yet
    if (state.appPushInProgress) {
        debugLog "[AutoPages] Skipping event during push: ${evt.displayName} ${evt.value}"
        return
    }
    String deviceId = evt.device.id.toString()
    int total       = numberOfPages()
    boolean found   = false
    (1..total).each { pg ->
        if ((state["pageType${pg}"] ?: "") != sType) return
        Map slotMap = state["slotMap${pg}"] ?: [:]
        int slot    = (slotMap[deviceId] ?: 0) as int
        if (slot < 1) return
        found = true
        debugLog "Event p${pg}s${slot} ${sType} (${evt.displayName}): ${evt.value}"
        if (evt.value == activeValue) {
            settings.indicatorDevice."setPage${pg}MotionActive"(slot)
        } else {
            settings.indicatorDevice."setPage${pg}MotionInactive"(slot)
        }
    }
    if (!found) infoLog "[AutoPages] WARN -- device ${deviceId} not found in any ${sType} slot map"
}

//  Display reboot 

def lightTappedHandler(evt) {
    infoLog "[AutoPages] lightTappedHandler fired: ${evt.value}"
    List parts = evt.value?.split(",")
    if (!parts || parts.size() < 2) { infoLog "[AutoPages] WARN bad lightTapped value"; return }
    int dispPage = parts[0] as int
    int slot     = parts[1] as int
    List<Integer> order = getPageOrder()
    infoLog "[AutoPages] dispPage=${dispPage} slot=${slot} order=${order}"
    if (dispPage < 1 || dispPage > order.size()) { infoLog "[AutoPages] WARN dispPage out of range"; return }
    int srcPage  = order[dispPage - 1]
    String sType = pageType(srcPage)
    infoLog "[AutoPages] srcPage=${srcPage} sType=${sType}"
    if (sType != "light") { infoLog "[AutoPages] WARN not a light page"; return }
    List devs = pageDevices(srcPage)
    infoLog "[AutoPages] devs.size=${devs?.size()} slot=${slot}"
    if (slot < 1 || slot > (devs?.size() ?: 0)) { infoLog "[AutoPages] WARN slot out of range"; return }
    def dev = devs[slot - 1]
    if (!dev) { infoLog "[AutoPages] WARN dev is null"; return }
    String curState = dev.currentValue("switch") ?: "off"
    infoLog "[AutoPages] Toggling light: ${dev.displayName} (p${dispPage}s${slot}) currently ${curState}"
    if (curState == "on") { dev.off() } else { dev.on() }
}

def layoutPushCompleteHandler(evt) {
    state.appPushInProgress = false
    infoLog "[AutoPages] Layout push complete -- syncing sensor states"
    syncAllSensors()
}

def displayRebootedHandler(evt) {
    state.appPushInProgress = false
    infoLog "[AutoPages] Display rebooted -- repushing everything"
    int total = numberOfPages()
    List<Integer> ord = activePageOrder()
    state.pageOrder = ord
    ord.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs = pageDevices(srcPage)
        try { settings.indicatorDevice."setPage${dispPage}GridLayout"(nxnString(devs.size())) } catch (Exception e) { }
    }
    try { settings.indicatorDevice.setNumberOfPages(getPageOrder().size()) } catch (Exception e) { }
    runIn(2, "pushSlotTypesAndLayouts")
}

//  State sync 

def syncLightStates() {
    int total = numberOfPages()
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        if (pageType(srcPage) != "light") return
        List devs = sortDevicesByName(pageDevices(srcPage))
        devs.eachWithIndex { dev, idx ->
            if (!dev) return
            int slot   = idx + 1
            String cur = dev.currentValue("switch") ?: "off"
            if (cur == "on") {
                settings.indicatorDevice."setPage${dispPage}MotionActive"(slot)
            } else {
                settings.indicatorDevice."setPage${dispPage}MotionInactive"(slot)
            }
            pauseExecution(30)
        }
    }
}

def syncAllSensors() {
    if (state.appPushInProgress) {
        infoLog "[AutoPages] Skipping sync -- layout push in progress"
        return
    }
    infoLog "[AutoPages] Syncing all sensor states"
    int total = numberOfPages()
    List<Integer> order = activePageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage  = dispIdx + 1
        List devs     = sortDevicesByName(pageDevices(srcPage))
        String sType  = pageType(srcPage)
        String attr   = attributeFor(sType)
        String actVal = activeValueFor(sType)
        int n          = gridSizeFor(devs.size())
        int totalSlots = n * n
        devs.eachWithIndex { dev, idx ->
            if (!dev) return
            int slot   = idx + 1
            String cur = dev.currentValue(attr) ?: ""
            debugLog "Sync p${dispPage}s${slot} ${sType} (${dev.displayName}) = ${cur}"
            if (cur == actVal) {
                settings.indicatorDevice."setPage${dispPage}MotionActive"(slot)
            } else {
                settings.indicatorDevice."setPage${dispPage}MotionInactive"(slot)
            }
            pauseExecution(40)
        }
        if (devs.size() < totalSlots) {
            ((devs.size() + 1)..totalSlots).each { slot ->
                settings.indicatorDevice."setPage${dispPage}SlotEmpty"(slot)
                pauseExecution(30)
            }
        }
    }
}

//  Slot map 

private Map buildSlotMap(List devices) {
    Map m = [:]
    if (!devices) return m
    devices.eachWithIndex { dev, idx ->
        if (dev) m[dev.id.toString()] = idx + 1
    }
    return m
}

//  Grid sizing 

private int gridSizeFor(int count) {
    if (count <= 1)  return 1
    if (count <= 4)  return 2
    if (count <= 9)  return 3
    if (count <= 16) return 4
    if (count <= 25) return 5
    if (count <= 36) return 6
    return 7
}

private String nxnString(int count) {
    int n = gridSizeFor(count)
    return "${n}x${n}"
}

private int maxCharsForGrid(int n) {
    switch (n) {
        case 1:  return 30
        case 2:  return 16
        case 3:  return 11
        case 4:  return 7
        case 5:  return 6
        case 6:  return 5
        default: return 4
    }
}

//  Label helpers 

private String stripEmoji(String text) {
    if (!text) return ""
    return text.replaceAll(/[^\x20-\x7E]/, "").replaceAll(/\s+/, " ").trim()
}

private List sortDevicesByName(List devices) {
    if (!devices) return []
    return devices.findAll { it != null }
                  .sort { a, b -> stripEmoji(a.displayName ?: "").compareToIgnoreCase(stripEmoji(b.displayName ?: "")) }
}

private String wrapLabel(String text, int maxChars) {
    if (!text || text.length() <= maxChars) return text ?: ""
    List<String> words = text.split(" ") as List
    List<String> lines = []
    String current = ""
    words.each { word ->
        if (current.isEmpty()) {
            current = word
        } else if ((current + " " + word).length() <= maxChars) {
            current += " " + word
        } else {
            lines << current
            current = word
        }
    }
    if (current) lines << current
    return lines.join("\n")
}

//  Logging 

private void infoLog(String msg) {
    if ((settings.logLevel ?: "1") != "0") log.info msg
}

private void debugLog(String msg) {
    if ((settings.logLevel ?: "1") == "2") log.debug msg
}
ices.
 *
 * Author: jlslate (slate)
 * Version: 4.4.0/*
 * This is free and unencumbered software released into the public domain.
 * For more information, please refer to <https://unlicense.org>
 */

/**
 * SenseCAP Auto-Pages App
 *
 * Up to 4 pages, each with a user-selected sensor type and device list.
 * Supported types per page: smoke, motion, water, contact.
 * Grid size is automatically sized to fit the selected devices.
 *
 * Author: jlslate (slate)
 * Version: 4.4.0
 */

definition(
    name:        "SenseCAP Auto-Pages Monitor",
    namespace:   "jlslate",
    author:      "jlslate (slate)",
    description: "Auto-assigns sensors to pages by type on a SenseCAP Indicator display via MQTT",
    category:    "Integration",
    iconUrl:     "",
    iconX2Url:   ""
)

preferences {
    page(name: "mainPage")
}

//  UI 

def mainPage() {
    dynamicPage(name: "mainPage", title: "SenseCAP Auto-Pages Monitor", install: true, uninstall: true) {

        section("<b>App Name</b>") {
            label title: "Rename this app (optional)", required: false
        }

        section("<b>SenseCAP Indicator Device</b>") {
            input name:     "indicatorDevice",
                  type:     "capability.actuator",
                  title:    "Select your SenseCAP Auto-Pages device",
                  required: true,
                  multiple: false
        }

        List<Integer> ord = getPageOrder()
        int totalPages = numberOfPages()

        //  Pages -- shown in current display order with inline move buttons 
        ord.eachWithIndex { srcPage, dispIdx ->
            int dispPos = dispIdx + 1
            boolean canUp = (dispPos > 1)
            boolean canDn = (dispPos < totalPages)

            if (srcPage == 1 ||
                (srcPage == 2 && settings.addPage2) ||
                (srcPage == 3 && settings.addPage2 && settings.addPage3) ||
                (srcPage == 4 && settings.addPage2 && settings.addPage3 && settings.addPage4) ||
                (srcPage == 5 && settings.addPage2 && settings.addPage3 && settings.addPage4 && settings.addPage5) ||
                (srcPage == 6 && settings.addPage2 && settings.addPage3 && settings.addPage4 && settings.addPage5 && settings.addPage6)) {

                section("<b>Page ${dispPos}</b>") {
                    // Sensor type + move buttons on the same row using width
                    input name:           "page${srcPage}Type",
                          type:           "enum",
                          title:          "Sensor type",
                          options:        sensorTypeOptions(),
                          required:       true,
                          defaultValue:   "smoke",
                          submitOnChange: true,
                          width:          6
                    if (canUp) {
                        input name:  "moveUp_${dispPos}",
                              type:  "button",
                              title: "&#9650; Move Up",
                              width: canDn ? 3 : 6
                    }
                    if (canDn) {
                        input name:  "moveDn_${dispPos}",
                              type:  "button",
                              title: "&#9660; Move Down",
                              width: canUp ? 3 : 6
                    }
                    if (settings["page${srcPage}Type"]) {
                        input name:           "page${srcPage}Devices",
                              type:           capabilityFor(settings["page${srcPage}Type"]),
                              title:          "Select devices",
                              required:       false,
                              multiple:       true,
                              submitOnChange: true
                        int cnt = settings["page${srcPage}Devices"]?.size() ?: 0
                        int n   = gridSizeFor(cnt)
                        paragraph "Devices: <b>${cnt}</b> -> Grid: <b>${n}x${n}</b>"
                    }
                    // Add-next-page toggle belongs to the last display position, not a specific source page
                    if (dispPos == 1 && totalPages == 1) {
                        input name: "addPage2", type: "bool", title: "Add page 2", defaultValue: false, submitOnChange: true
                    } else if (dispPos == 2 && totalPages == 2) {
                        input name: "addPage3", type: "bool", title: "Add page 3", defaultValue: false, submitOnChange: true
                    } else if (dispPos == 3 && totalPages == 3) {
                        input name: "addPage4", type: "bool", title: "Add page 4", defaultValue: false, submitOnChange: true
                    } else if (dispPos == 4 && totalPages == 4) {
                        input name: "addPage5", type: "bool", title: "Add page 5", defaultValue: false, submitOnChange: true
                    } else if (dispPos == 5 && totalPages == 5) {
                        input name: "addPage6", type: "bool", title: "Add page 6", defaultValue: false, submitOnChange: true
                    }
                }
            }
        }

        section("<b>Options</b>") {
            input name:         "syncOnStartup",
                  type:         "bool",
                  title:        "Sync all sensor states on startup/save",
                  defaultValue: true
            input name:         "lightSyncInterval",
                  type:         "enum",
                  title:        "Re-sync light states every",
                  options:      ["0":"Never","5":"5 minutes","10":"10 minutes","30":"30 minutes"],
                  defaultValue: "10"
            input name:         "logLevel",
                  type:         "enum",
                  title:        "Logging Level",
                  options:      ["0":"None","1":"Info only","2":"Info + Debug"],
                  defaultValue: "1",
                  required:     true
        }

        section("<b>Status</b>") {
            int total = numberOfPages()
            int devices = (0..<total).sum { pg -> pageDevices(pg+1)?.size() ?: 0 } as int
            paragraph "Pages: <b>${total}</b> -- Total devices: <b>${devices}</b>"
            if (settings.indicatorDevice) {
                paragraph "MQTT status: <b>${settings.indicatorDevice.currentValue('mqttStatus') ?: 'unknown'}</b>"
            }
        }
    }
}

//  Helpers 

private Map sensorTypeOptions() {
    ["smoke":"Smoke detectors", "motion":"Motion sensors",
     "water":"Water sensors",   "contact":"Contact sensors",
     "light":"Lights (switch)"]
}

private String capabilityFor(String sType) {
    switch (sType) {
        case "smoke":   return "capability.smokeDetector"
        case "motion":  return "capability.motionSensor"
        case "water":   return "capability.waterSensor"
        case "contact": return "capability.contactSensor"
        case "light":   return "capability.switch"
        default:        return "capability.sensor"
    }
}

private String attributeFor(String sType) {
    switch (sType) {
        case "smoke":   return "smoke"
        case "motion":  return "motion"
        case "water":   return "water"
        case "contact": return "contact"
        case "light":   return "switch"
        default:        return "motion"
    }
}

private String activeValueFor(String sType) {
    switch (sType) {
        case "smoke":   return "detected"
        case "motion":  return "active"
        case "water":   return "wet"
        case "contact": return "open"
        case "light":   return "on"
        default:        return "active"
    }
}

private int numberOfPages() {
    if (!settings.addPage2) return 1
    if (!settings.addPage3) return 2
    if (!settings.addPage4) return 3
    if (!settings.addPage5) return 4
    if (!settings.addPage6) return 5
    return 6
}

private List pageDevices(int page) {
    return (settings["page${page}Devices"] ?: []) as List
}

private String pageType(int page) {
    return (settings["page${page}Type"] ?: "motion") as String
}

// Returns the current display order -- list of source page indices in display order
private List<Integer> displayOrder() {
    return getPageOrder()
}

private List<Integer> getPageOrder() {
    int total = numberOfPages()
    List stored = state.pageOrder ?: []
    // Validate stored order -- remove any pages no longer configured, add any new ones
    List<Integer> order = stored.findAll { it >= 1 && it <= total }.collect { it as int }
    (1..total).each { pg -> if (!order.contains(pg)) order << pg }
    return order
}

def appButtonHandler(String buttonName) {
    List<Integer> order = getPageOrder()
    boolean changed = false
    if (buttonName.startsWith("moveUp_")) {
        int pos = buttonName.replace("moveUp_", "").toInteger()
        if (pos > 1 && pos <= order.size()) {
            int tmp = order[pos - 2]
            order[pos - 2] = order[pos - 1]
            order[pos - 1] = tmp
            changed = true
        }
    } else if (buttonName.startsWith("moveDn_")) {
        int pos = buttonName.replace("moveDn_", "").toInteger()
        if (pos >= 1 && pos < order.size()) {
            int tmp = order[pos - 1]
            order[pos - 1] = order[pos]
            order[pos] = tmp
            changed = true
        }
    }
    if (changed) {
        state.pageOrder = order
        infoLog "[AutoPages] Page order: ${order.collect { pageType(it) }.join(' -> ')}"
    }
}

//  Lifecycle 

def installed() {
    infoLog "[AutoPages] App installed"
    initialize()
}

def updated() {
    infoLog "[AutoPages] App updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    unsubscribe()
}

def initialize() {
    if (!settings.indicatorDevice) {
        infoLog "[AutoPages] No indicator device selected"
        return
    }

    int total = numberOfPages()
    List<Integer> order = displayOrder()
    state.pageOrder = order
    infoLog "[AutoPages] Display order: ${order.collect { pageType(it) }.join(' -> ')}"

    // Push grid layouts in display order -- display slot 1 gets order[0]'s grid etc.
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs = pageDevices(srcPage)
        String grid = nxnString(devs.size())
        try {
            settings.indicatorDevice."setPage${dispPage}GridLayout"(grid)
        } catch (Exception e) {
            infoLog "[AutoPages] WARN -- setPage${dispPage}GridLayout failed: ${e.message}"
        }
    }
    try {
        settings.indicatorDevice.setNumberOfPages(total)
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- setNumberOfPages failed: ${e.message}"
    }

    // Build slot maps keyed by DISPLAY page number (not source page number)
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs    = sortDevicesByName(pageDevices(srcPage))
        String sType = pageType(srcPage)
        Map slotMap  = buildSlotMap(devs)
        state["slotMap${dispPage}"]  = slotMap
        state["pageType${dispPage}"] = sType
        subscribePageDevices(dispPage, devs, sType)
    }

    // Subscribe to display reboot
    subscribe(settings.indicatorDevice, "displayRebooted",    displayRebootedHandler)
    subscribe(settings.indicatorDevice, "layoutPushComplete", layoutPushCompleteHandler)
    subscribe(settings.indicatorDevice, "lightTapped",        lightTappedHandler)

    // Push slot types and layouts (labels included per-page, sync at end)
    runIn(2, "pushSlotTypesAndLayouts")
    // Schedule periodic light state resync
    unschedule("syncLightStates")
    int syncMins = (settings.lightSyncInterval ?: "10") as int
    if (syncMins > 0) schedule("0 */${syncMins} * ? * *", syncLightStates)

    infoLog "[AutoPages] Initialized -- ${total} page(s)"
}

private void subscribePageDevices(int page, List devices, String sType) {
    String attr = attributeFor(sType)
    String handler = "${sType}Handler"
    // Use generic handler with page/type encoded in state
    devices.each { dev ->
        if (!dev) return
        switch (sType) {
            case "smoke":   subscribe(dev, "smoke",   smokeHandler);   break
            case "motion":  subscribe(dev, "motion",  motionHandler);  break
            case "water":   subscribe(dev, "water",   waterHandler);   break
            case "contact": subscribe(dev, "contact", contactHandler); break
            case "light":   subscribe(dev, "switch",  lightHandler);   break
        }
    }
}

//  Push layout 

def pushSlotTypesAndLayouts() {
    int total = numberOfPages()
    // Push slot types AND labels into driver state before any layout rendering starts.
    // Use display order so display slot 1 gets the right page's data.
    List<Integer> order = getPageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs    = sortDevicesByName(pageDevices(srcPage))
        String sType = pageType(srcPage)
        pushPageSlotTypes(dispPage, devs, sType)
        pauseExecution(300)
        pushPageLabels(dispPage, devs)
        pauseExecution(300)
    }
    // Extra pause to ensure all driver state writes have persisted
    pauseExecution(2000)
    try {
        settings.indicatorDevice.pushAllLayouts(total)
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- pushAllLayouts failed: ${e.message}"
    }
    // Sync is triggered by displayRebooted event fired by driver after last page renders
}

def pushAllLabels() {
    int total = numberOfPages()
    (1..total).each { pg ->
        List devs = pageDevices(pg)
        pushPageLabels(pg, devs)
        pauseExecution(500)
    }
}

private void pushPageSlotTypes(int page, List devices, String sType) {
    int n = gridSizeFor(devices.size())
    Map types = [:]
    (1..n*n).each { slot ->
        types[slot] = (slot <= devices.size()) ? sType : "none"
    }
    try {
        switch (page) {
            case 1: settings.indicatorDevice.updatePage1SlotTypes(types); break
            case 2: settings.indicatorDevice.updatePage2SlotTypes(types); break
            case 3: settings.indicatorDevice.updatePage3SlotTypes(types); break
            case 4: settings.indicatorDevice.updatePage4SlotTypes(types); break
            case 5: settings.indicatorDevice.updatePage5SlotTypes(types); break
            case 6: settings.indicatorDevice.updatePage6SlotTypes(types); break
        }
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- updatePage${page}SlotTypes failed: ${e.message}"
    }
}

private void pushPageLabels(int page, List devices) {
    if (!devices) return
    int n        = gridSizeFor(devices.size())
    int maxChars = maxCharsForGrid(n)
    Map labels   = [:]
    devices.eachWithIndex { dev, idx ->
        if (!dev) return
        String name = stripEmoji(dev.displayName ?: "")
        if (name) labels[idx + 1] = wrapLabel(name, maxChars)
    }
    if (!labels) return
    try {
        switch (page) {
            case 1: settings.indicatorDevice.updatePage1Labels(labels); break
            case 2: settings.indicatorDevice.updatePage2Labels(labels); break
            case 3: settings.indicatorDevice.updatePage3Labels(labels); break
            case 4: settings.indicatorDevice.updatePage4Labels(labels); break
            case 5: settings.indicatorDevice.updatePage5Labels(labels); break
            case 6: settings.indicatorDevice.updatePage6Labels(labels); break
        }
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- updatePage${page}Labels failed: ${e.message}"
    }
}

//  Event handlers 
// Each handler searches all pages for the device, since a device could
// theoretically appear on any page of its type.

def lightHandler(evt) {
    handleEvent(evt, "light", "on")
}

def smokeHandler(evt) {
    handleEvent(evt, "smoke", "detected")
}

def motionHandler(evt) {
    handleEvent(evt, "motion", "active")
}

def waterHandler(evt) {
    handleEvent(evt, "water", "wet")
}

def contactHandler(evt) {
    handleEvent(evt, "contact", "open")
}

private void handleEvent(evt, String sType, String activeValue) {
    String deviceId = evt.device.id.toString()
    int total       = numberOfPages()
    boolean found   = false
    (1..total).each { pg ->
        if ((state["pageType${pg}"] ?: "") != sType) return
        Map slotMap = state["slotMap${pg}"] ?: [:]
        int slot    = (slotMap[deviceId] ?: 0) as int
        if (slot < 1) return
        found = true
        debugLog "Event p${pg}s${slot} ${sType} (${evt.displayName}): ${evt.value}"
        if (evt.value == activeValue) {
            settings.indicatorDevice."setPage${pg}MotionActive"(slot)
        } else {
            settings.indicatorDevice."setPage${pg}MotionInactive"(slot)
        }
    }
    if (!found) infoLog "[AutoPages] WARN -- device ${deviceId} not found in any ${sType} slot map"
}

//  Display reboot 

def lightTappedHandler(evt) {
    infoLog "[AutoPages] lightTappedHandler fired: ${evt.value}"
    List parts = evt.value?.split(",")
    if (!parts || parts.size() < 2) { infoLog "[AutoPages] WARN bad lightTapped value"; return }
    int dispPage = parts[0] as int
    int slot     = parts[1] as int
    List<Integer> order = getPageOrder()
    infoLog "[AutoPages] dispPage=${dispPage} slot=${slot} order=${order}"
    if (dispPage < 1 || dispPage > order.size()) { infoLog "[AutoPages] WARN dispPage out of range"; return }
    int srcPage  = order[dispPage - 1]
    String sType = pageType(srcPage)
    infoLog "[AutoPages] srcPage=${srcPage} sType=${sType}"
    if (sType != "light") { infoLog "[AutoPages] WARN not a light page"; return }
    List devs = pageDevices(srcPage)
    infoLog "[AutoPages] devs.size=${devs?.size()} slot=${slot}"
    if (slot < 1 || slot > (devs?.size() ?: 0)) { infoLog "[AutoPages] WARN slot out of range"; return }
    def dev = devs[slot - 1]
    if (!dev) { infoLog "[AutoPages] WARN dev is null"; return }
    String curState = dev.currentValue("switch") ?: "off"
    infoLog "[AutoPages] Toggling light: ${dev.displayName} (p${dispPage}s${slot}) currently ${curState}"
    if (curState == "on") { dev.off() } else { dev.on() }
}

def layoutPushCompleteHandler(evt) {
    infoLog "[AutoPages] Layout push complete -- syncing sensor states"
    syncAllSensors()
}

def displayRebootedHandler(evt) {
    infoLog "[AutoPages] Display rebooted -- repushing everything"
    int total = numberOfPages()
    List<Integer> ord = displayOrder()
    state.pageOrder = ord
    ord.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs = pageDevices(srcPage)
        try { settings.indicatorDevice."setPage${dispPage}GridLayout"(nxnString(devs.size())) } catch (Exception e) { }
    }
    try { settings.indicatorDevice.setNumberOfPages(total) } catch (Exception e) { }
    runIn(2, "pushSlotTypesAndLayouts")
}

//  State sync 

def syncLightStates() {
    int total = numberOfPages()
    List<Integer> order = getPageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        if (pageType(srcPage) != "light") return
        List devs = sortDevicesByName(pageDevices(srcPage))
        devs.eachWithIndex { dev, idx ->
            if (!dev) return
            int slot   = idx + 1
            String cur = dev.currentValue("switch") ?: "off"
            if (cur == "on") {
                settings.indicatorDevice."setPage${dispPage}MotionActive"(slot)
            } else {
                settings.indicatorDevice."setPage${dispPage}MotionInactive"(slot)
            }
            pauseExecution(30)
        }
    }
}

def syncAllSensors() {
    infoLog "[AutoPages] Syncing all sensor states"
    int total = numberOfPages()
    List<Integer> order = getPageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage  = dispIdx + 1
        List devs     = sortDevicesByName(pageDevices(srcPage))
        String sType  = pageType(srcPage)
        String attr   = attributeFor(sType)
        String actVal = activeValueFor(sType)
        int n          = gridSizeFor(devs.size())
        int totalSlots = n * n
        devs.eachWithIndex { dev, idx ->
            if (!dev) return
            int slot   = idx + 1
            String cur = dev.currentValue(attr) ?: ""
            debugLog "Sync p${dispPage}s${slot} ${sType} (${dev.displayName}) = ${cur}"
            if (cur == actVal) {
                settings.indicatorDevice."setPage${dispPage}MotionActive"(slot)
            } else {
                settings.indicatorDevice."setPage${dispPage}MotionInactive"(slot)
            }
            pauseExecution(40)
        }
        if (devs.size() < totalSlots) {
            ((devs.size() + 1)..totalSlots).each { slot ->
                settings.indicatorDevice."setPage${dispPage}SlotEmpty"(slot)
                pauseExecution(30)
            }
        }
    }
}

//  Slot map 

private Map buildSlotMap(List devices) {
    Map m = [:]
    if (!devices) return m
    devices.eachWithIndex { dev, idx ->
        if (dev) m[dev.id.toString()] = idx + 1
    }
    return m
}

//  Grid sizing 

private int gridSizeFor(int count) {
    if (count <= 1)  return 1
    if (count <= 4)  return 2
    if (count <= 9)  return 3
    if (count <= 16) return 4
    if (count <= 25) return 5
    if (count <= 36) return 6
    return 7
}

private String nxnString(int count) {
    int n = gridSizeFor(count)
    return "${n}x${n}"
}

private int maxCharsForGrid(int n) {
    switch (n) {
        case 1:  return 30
        case 2:  return 16
        case 3:  return 11
        case 4:  return 7
        case 5:  return 6
        case 6:  return 5
        default: return 4
    }
}

//  Label helpers 

private String stripEmoji(String text) {
    if (!text) return ""
    return text.replaceAll(/[^\x20-\x7E]/, "").replaceAll(/\s+/, " ").trim()
}

private List sortDevicesByName(List devices) {
    if (!devices) return []
    return devices.findAll { it != null }
                  .sort { a, b -> stripEmoji(a.displayName ?: "").compareToIgnoreCase(stripEmoji(b.displayName ?: "")) }
}

private String wrapLabel(String text, int maxChars) {
    if (!text || text.length() <= maxChars) return text ?: ""
    List<String> words = text.split(" ") as List
    List<String> lines = []
    String current = ""
    words.each { word ->
        if (current.isEmpty()) {
            current = word
        } else if ((current + " " + word).length() <= maxChars) {
            current += " " + word
        } else {
            lines << current
            current = word
        }
    }
    if (current) lines << current
    return lines.join("\n")
}

//  Logging 

private void infoLog(String msg) {
    if ((settings.logLevel ?: "1") != "0") log.info msg
}

private void debugLog(String msg) {
    if ((settings.logLevel ?: "1") == "2") log.debug msg
}

 */

definition(
    name:        "SenseCAP Auto-Pages Monitor",
    namespace:   "community",
    author:      "jlslate (slate)",
    description: "Auto-assigns sensors to pages by type on a SenseCAP Indicator display via MQTT",
    category:    "Integration",
    iconUrl:     "",
    iconX2Url:   ""
)

preferences {
    page(name: "mainPage")
}

//  UI 

def mainPage() {
    dynamicPage(name: "mainPage", title: "SenseCAP Auto-Pages Monitor", install: true, uninstall: true) {

        section("<b>App Name</b>") {
            label title: "Rename this app (optional)", required: false
        }

        section("<b>SenseCAP Indicator Device</b>") {
            input name:     "indicatorDevice",
                  type:     "capability.actuator",
                  title:    "Select your SenseCAP Auto-Pages device",
                  required: true,
                  multiple: false
        }

        List<Integer> ord = getPageOrder()
        int totalPages = numberOfPages()

        //  Pages -- shown in current display order with inline move buttons 
        ord.eachWithIndex { srcPage, dispIdx ->
            int dispPos = dispIdx + 1
            boolean canUp = (dispPos > 1)
            boolean canDn = (dispPos < totalPages)

            if (srcPage == 1 ||
                (srcPage == 2 && settings.addPage2) ||
                (srcPage == 3 && settings.addPage2 && settings.addPage3) ||
                (srcPage == 4 && settings.addPage2 && settings.addPage3 && settings.addPage4) ||
                (srcPage == 5 && settings.addPage2 && settings.addPage3 && settings.addPage4 && settings.addPage5) ||
                (srcPage == 6 && settings.addPage2 && settings.addPage3 && settings.addPage4 && settings.addPage5 && settings.addPage6)) {

                section("<b>Page ${dispPos}</b>") {
                    // Sensor type + move buttons on the same row using width
                    input name:           "page${srcPage}Type",
                          type:           "enum",
                          title:          "Sensor type",
                          options:        sensorTypeOptions(),
                          required:       true,
                          defaultValue:   "smoke",
                          submitOnChange: true,
                          width:          6
                    if (canUp) {
                        input name:  "moveUp_${dispPos}",
                              type:  "button",
                              title: "&#9650; Move Up",
                              width: canDn ? 3 : 6
                    }
                    if (canDn) {
                        input name:  "moveDn_${dispPos}",
                              type:  "button",
                              title: "&#9660; Move Down",
                              width: canUp ? 3 : 6
                    }
                    if (settings["page${srcPage}Type"]) {
                        input name:           "page${srcPage}Devices",
                              type:           capabilityFor(settings["page${srcPage}Type"]),
                              title:          "Select devices",
                              required:       false,
                              multiple:       true,
                              submitOnChange: true
                        int cnt = settings["page${srcPage}Devices"]?.size() ?: 0
                        int n   = gridSizeFor(cnt)
                        paragraph "Devices: <b>${cnt}</b> -> Grid: <b>${n}x${n}</b>"
                    }
                    // Add-next-page toggle belongs to the last display position, not a specific source page
                    if (dispPos == 1 && totalPages == 1) {
                        input name: "addPage2", type: "bool", title: "Add page 2", defaultValue: false, submitOnChange: true
                    } else if (dispPos == 2 && totalPages == 2) {
                        input name: "addPage3", type: "bool", title: "Add page 3", defaultValue: false, submitOnChange: true
                    } else if (dispPos == 3 && totalPages == 3) {
                        input name: "addPage4", type: "bool", title: "Add page 4", defaultValue: false, submitOnChange: true
                    } else if (dispPos == 4 && totalPages == 4) {
                        input name: "addPage5", type: "bool", title: "Add page 5", defaultValue: false, submitOnChange: true
                    } else if (dispPos == 5 && totalPages == 5) {
                        input name: "addPage6", type: "bool", title: "Add page 6", defaultValue: false, submitOnChange: true
                    }
                }
            }
        }

        section("<b>Options</b>") {
            input name:         "syncOnStartup",
                  type:         "bool",
                  title:        "Sync all sensor states on startup/save",
                  defaultValue: true
            input name:         "logLevel",
                  type:         "enum",
                  title:        "Logging Level",
                  options:      ["0":"None","1":"Info only","2":"Info + Debug"],
                  defaultValue: "1",
                  required:     true
        }

        section("<b>Status</b>") {
            int total = numberOfPages()
            int devices = (0..<total).sum { pg -> pageDevices(pg+1)?.size() ?: 0 } as int
            paragraph "Pages: <b>${total}</b> -- Total devices: <b>${devices}</b>"
            if (settings.indicatorDevice) {
                paragraph "MQTT status: <b>${settings.indicatorDevice.currentValue('mqttStatus') ?: 'unknown'}</b>"
            }
        }
    }
}

//  Helpers 

private Map sensorTypeOptions() {
    ["smoke":"Smoke detectors", "motion":"Motion sensors",
     "water":"Water sensors",   "contact":"Contact sensors"]
}

private String capabilityFor(String sType) {
    switch (sType) {
        case "smoke":   return "capability.smokeDetector"
        case "motion":  return "capability.motionSensor"
        case "water":   return "capability.waterSensor"
        case "contact": return "capability.contactSensor"
        default:        return "capability.sensor"
    }
}

private String attributeFor(String sType) {
    switch (sType) {
        case "smoke":   return "smoke"
        case "motion":  return "motion"
        case "water":   return "water"
        case "contact": return "contact"
        default:        return "motion"
    }
}

private String activeValueFor(String sType) {
    switch (sType) {
        case "smoke":   return "detected"
        case "motion":  return "active"
        case "water":   return "wet"
        case "contact": return "open"
        default:        return "active"
    }
}

private int numberOfPages() {
    if (!settings.addPage2) return 1
    if (!settings.addPage3) return 2
    if (!settings.addPage4) return 3
    if (!settings.addPage5) return 4
    if (!settings.addPage6) return 5
    return 6
}

private List pageDevices(int page) {
    return (settings["page${page}Devices"] ?: []) as List
}

private String pageType(int page) {
    return (settings["page${page}Type"] ?: "motion") as String
}

// Returns the current display order -- list of source page indices in display order
private List<Integer> displayOrder() {
    return getPageOrder()
}

private List<Integer> getPageOrder() {
    int total = numberOfPages()
    List stored = state.pageOrder ?: []
    // Validate stored order -- remove any pages no longer configured, add any new ones
    List<Integer> order = stored.findAll { it >= 1 && it <= total }.collect { it as int }
    (1..total).each { pg -> if (!order.contains(pg)) order << pg }
    return order
}

def appButtonHandler(String buttonName) {
    List<Integer> order = getPageOrder()
    boolean changed = false
    if (buttonName.startsWith("moveUp_")) {
        int pos = buttonName.replace("moveUp_", "").toInteger()
        if (pos > 1 && pos <= order.size()) {
            int tmp = order[pos - 2]
            order[pos - 2] = order[pos - 1]
            order[pos - 1] = tmp
            changed = true
        }
    } else if (buttonName.startsWith("moveDn_")) {
        int pos = buttonName.replace("moveDn_", "").toInteger()
        if (pos >= 1 && pos < order.size()) {
            int tmp = order[pos - 1]
            order[pos - 1] = order[pos]
            order[pos] = tmp
            changed = true
        }
    }
    if (changed) {
        state.pageOrder = order
        infoLog "[AutoPages] Page order: ${order.collect { pageType(it) }.join(' -> ')}"
    }
}

//  Lifecycle 

def installed() {
    infoLog "[AutoPages] App installed"
    initialize()
}

def updated() {
    infoLog "[AutoPages] App updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    unsubscribe()
}

def initialize() {
    if (!settings.indicatorDevice) {
        infoLog "[AutoPages] No indicator device selected"
        return
    }

    int total = numberOfPages()
    List<Integer> order = displayOrder()
    state.pageOrder = order
    infoLog "[AutoPages] Display order: ${order.collect { pageType(it) }.join(' -> ')}"

    // Push grid layouts in display order -- display slot 1 gets order[0]'s grid etc.
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs = pageDevices(srcPage)
        String grid = nxnString(devs.size())
        try {
            settings.indicatorDevice."setPage${dispPage}GridLayout"(grid)
        } catch (Exception e) {
            infoLog "[AutoPages] WARN -- setPage${dispPage}GridLayout failed: ${e.message}"
        }
    }
    try {
        settings.indicatorDevice.setNumberOfPages(total)
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- setNumberOfPages failed: ${e.message}"
    }

    // Build slot maps keyed by DISPLAY page number (not source page number)
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs    = pageDevices(srcPage)
        String sType = pageType(srcPage)
        Map slotMap  = buildSlotMap(devs)
        state["slotMap${dispPage}"]  = slotMap
        state["pageType${dispPage}"] = sType
        subscribePageDevices(dispPage, devs, sType)
    }

    // Subscribe to display reboot
    subscribe(settings.indicatorDevice, "displayRebooted",    displayRebootedHandler)
    subscribe(settings.indicatorDevice, "layoutPushComplete", layoutPushCompleteHandler)

    // Push slot types and layouts (labels included per-page, sync at end)
    runIn(2, "pushSlotTypesAndLayouts")

    infoLog "[AutoPages] Initialized -- ${total} page(s)"
}

private void subscribePageDevices(int page, List devices, String sType) {
    String attr = attributeFor(sType)
    String handler = "${sType}Handler"
    // Use generic handler with page/type encoded in state
    devices.each { dev ->
        if (!dev) return
        switch (sType) {
            case "smoke":   subscribe(dev, "smoke",   smokeHandler);   break
            case "motion":  subscribe(dev, "motion",  motionHandler);  break
            case "water":   subscribe(dev, "water",   waterHandler);   break
            case "contact": subscribe(dev, "contact", contactHandler); break
        }
    }
}

//  Push layout 

def pushSlotTypesAndLayouts() {
    int total = numberOfPages()
    // Push slot types AND labels into driver state before any layout rendering starts.
    // Use display order so display slot 1 gets the right page's data.
    List<Integer> order = getPageOrder()
    order.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs    = pageDevices(srcPage)
        String sType = pageType(srcPage)
        pushPageSlotTypes(dispPage, devs, sType)
        pauseExecution(300)
        pushPageLabels(dispPage, devs)
        pauseExecution(300)
    }
    // Extra pause to ensure all driver state writes have persisted
    pauseExecution(2000)
    try {
        settings.indicatorDevice.pushAllLayouts(total)
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- pushAllLayouts failed: ${e.message}"
    }
    // Sync is triggered by displayRebooted event fired by driver after last page renders
}

def pushAllLabels() {
    int total = numberOfPages()
    (1..total).each { pg ->
        List devs = pageDevices(pg)
        pushPageLabels(pg, devs)
        pauseExecution(500)
    }
}

private void pushPageSlotTypes(int page, List devices, String sType) {
    int n = gridSizeFor(devices.size())
    Map types = [:]
    (1..n*n).each { slot ->
        types[slot] = (slot <= devices.size()) ? sType : "none"
    }
    try {
        switch (page) {
            case 1: settings.indicatorDevice.updatePage1SlotTypes(types); break
            case 2: settings.indicatorDevice.updatePage2SlotTypes(types); break
            case 3: settings.indicatorDevice.updatePage3SlotTypes(types); break
            case 4: settings.indicatorDevice.updatePage4SlotTypes(types); break
            case 5: settings.indicatorDevice.updatePage5SlotTypes(types); break
            case 6: settings.indicatorDevice.updatePage6SlotTypes(types); break
        }
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- updatePage${page}SlotTypes failed: ${e.message}"
    }
}

private void pushPageLabels(int page, List devices) {
    if (!devices) return
    int n        = gridSizeFor(devices.size())
    int maxChars = maxCharsForGrid(n)
    Map labels   = [:]
    devices.eachWithIndex { dev, idx ->
        if (!dev) return
        String name = stripEmoji(dev.displayName ?: "")
        if (name) labels[idx + 1] = wrapLabel(name, maxChars)
    }
    if (!labels) return
    try {
        switch (page) {
            case 1: settings.indicatorDevice.updatePage1Labels(labels); break
            case 2: settings.indicatorDevice.updatePage2Labels(labels); break
            case 3: settings.indicatorDevice.updatePage3Labels(labels); break
            case 4: settings.indicatorDevice.updatePage4Labels(labels); break
            case 5: settings.indicatorDevice.updatePage5Labels(labels); break
            case 6: settings.indicatorDevice.updatePage6Labels(labels); break
        }
    } catch (Exception e) {
        infoLog "[AutoPages] WARN -- updatePage${page}Labels failed: ${e.message}"
    }
}

//  Event handlers 
// Each handler searches all pages for the device, since a device could
// theoretically appear on any page of its type.

def smokeHandler(evt) {
    handleEvent(evt, "smoke", "detected")
}

def motionHandler(evt) {
    handleEvent(evt, "motion", "active")
}

def waterHandler(evt) {
    handleEvent(evt, "water", "wet")
}

def contactHandler(evt) {
    handleEvent(evt, "contact", "open")
}

private void handleEvent(evt, String sType, String activeValue) {
    String deviceId = evt.device.id.toString()
    int total       = numberOfPages()
    boolean found   = false
    (1..total).each { pg ->
        if ((state["pageType${pg}"] ?: "") != sType) return
        Map slotMap = state["slotMap${pg}"] ?: [:]
        int slot    = (slotMap[deviceId] ?: 0) as int
        if (slot < 1) return
        found = true
        debugLog "Event p${pg}s${slot} ${sType} (${evt.displayName}): ${evt.value}"
        if (evt.value == activeValue) {
            settings.indicatorDevice."setPage${pg}MotionActive"(slot)
        } else {
            settings.indicatorDevice."setPage${pg}MotionInactive"(slot)
        }
    }
    if (!found) infoLog "[AutoPages] WARN -- device ${deviceId} not found in any ${sType} slot map"
}

//  Display reboot 

def layoutPushCompleteHandler(evt) {
    infoLog "[AutoPages] Layout push complete -- syncing sensor states"
    syncAllSensors()
}

def displayRebootedHandler(evt) {
    infoLog "[AutoPages] Display rebooted -- repushing everything"
    int total = numberOfPages()
    List<Integer> ord = displayOrder()
    state.pageOrder = ord
    ord.eachWithIndex { srcPage, dispIdx ->
        int dispPage = dispIdx + 1
        List devs = pageDevices(srcPage)
        try { settings.indicatorDevice."setPage${dispPage}GridLayout"(nxnString(devs.size())) } catch (Exception e) { }
    }
    try { settings.indicatorDevice.setNumberOfPages(total) } catch (Exception e) { }
    runIn(2, "pushSlotTypesAndLayouts")
}

//  State sync 

def syncAllSensors() {
    infoLog "[AutoPages] Syncing all sensor states"
    int total = numberOfPages()
    (1..total).each { pg ->
        List devs    = pageDevices(pg)
        String sType = pageType(pg)
        String attr  = attributeFor(sType)
        String actVal = activeValueFor(sType)
        int n         = gridSizeFor(devs.size())
        int totalSlots = n * n
        devs.eachWithIndex { dev, idx ->
            if (!dev) return
            int slot     = idx + 1
            String cur   = dev.currentValue(attr) ?: ""
            debugLog "Sync p${pg}s${slot} ${attr} (${dev.displayName}) = ${cur}"
            if (cur == actVal) {
                settings.indicatorDevice."setPage${pg}MotionActive"(slot)
            } else {
                settings.indicatorDevice."setPage${pg}MotionInactive"(slot)
            }
            pauseExecution(40)
        }
        if (devs.size() < totalSlots) {
            ((devs.size() + 1)..totalSlots).each { slot ->
                settings.indicatorDevice."setPage${pg}SlotEmpty"(slot)
                pauseExecution(30)
            }
        }
    }
}

//  Slot map 

private Map buildSlotMap(List devices) {
    Map m = [:]
    if (!devices) return m
    devices.eachWithIndex { dev, idx ->
        if (dev) m[dev.id.toString()] = idx + 1
    }
    return m
}

//  Grid sizing 

private int gridSizeFor(int count) {
    if (count <= 1)  return 1
    if (count <= 4)  return 2
    if (count <= 9)  return 3
    if (count <= 16) return 4
    if (count <= 25) return 5
    if (count <= 36) return 6
    return 7
}

private String nxnString(int count) {
    int n = gridSizeFor(count)
    return "${n}x${n}"
}

private int maxCharsForGrid(int n) {
    switch (n) {
        case 1:  return 30
        case 2:  return 16
        case 3:  return 11
        case 4:  return 7
        case 5:  return 6
        case 6:  return 5
        default: return 4
    }
}

//  Label helpers 

private String stripEmoji(String text) {
    if (!text) return ""
    return text.replaceAll(/[^\x20-\x7E]/, "").replaceAll(/\s+/, " ").trim()
}

private String wrapLabel(String text, int maxChars) {
    if (!text || text.length() <= maxChars) return text ?: ""
    List<String> words = text.split(" ") as List
    List<String> lines = []
    String current = ""
    words.each { word ->
        if (current.isEmpty()) {
            current = word
        } else if ((current + " " + word).length() <= maxChars) {
            current += " " + word
        } else {
            lines << current
            current = word
        }
    }
    if (current) lines << current
    return lines.join("\n")
}

//  Logging 

private void infoLog(String msg) {
    if ((settings.logLevel ?: "1") != "0") log.info msg
}

private void debugLog(String msg) {
    if ((settings.logLevel ?: "1") == "2") log.debug msg
}
