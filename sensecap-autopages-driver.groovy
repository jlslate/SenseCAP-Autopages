/*
 * This is free and unencumbered software released into the public domain.
 * For more information, please refer to <https://unlicense.org>
 */

/**
 * SenseCAP Auto-Pages Driver v5.0.0
 *
 * Hubitat driver for the SenseCAP Indicator D1 (480x480) running openHASP firmware.
 * Communicates via MQTT. Up to 6 pages, each with a user-selected sensor type and
 * auto-sized grid layout.
 *
 * Key features:
 *   - Up to 6 pages: smoke / motion / water / contact / light (switch)
 *   - Grid auto-sized from 1x1 to 7x7 based on device count
 *   - Pages render sequentially -- each page fully built before showing
 *   - Per-page drain timer (navigatePage1..6) waits for openHASP queue to clear
 *     before flipping to the rendered page, eliminating partial-render flashes
 *   - Light tiles: tap-to-toggle via openHASP button events; full btn jsonl
 *     rebuild on every state change so color + label + click:true arrive atomically
 *   - pushInProgress attribute + appPushInProgress app state gate all sensor
 *     event handlers and syncAllSensors during layout push
 *   - Page order adjustable via Move Up / Move Down buttons in app UI
 *   - Rotation auto-starts after all pages rendered and page 1 is shown
 *   - rebootDisplay command sends openHASP reboot via MQTT
 *   - Backlight and idle management
 *
 * Rendering sequence per page:
 *   1. clearpage N
 *   2. Push layout JSONL (btn tiles + icon overlay labels + nav buttons + page indicator)
 *   3. Push slot colors, icons, labels (light tiles use full btn jsonl with click:true)
 *   4. navigatePageN fires via runIn after drain wait -- navigates to page, chains next
 *   5. After last page: returnToPage1AndStartRotation fires via runIn(12)
 *
 * Author: jlslate (slate)
 * Version: 5.0.0
 */

import groovy.transform.Field

metadata {
    definition(
        name:        "SenseCAP Auto-Pages Monitor",
        namespace:   "jlslate",
        author:      "jlslate (slate)",
        description: "Auto-paged sensor monitor -- Smoke/Motion/Water/Contact each get their own page"
    ) {
        capability "Initialize"
        capability "Actuator"

        command "reconnectMqtt"
        command "rebootDisplay"
        command "pushAllLayouts", [[name:"numberOfPages", type:"NUMBER"]]
        command "setNumberOfPages", [[name:"n", type:"NUMBER"]]

        command "setPage1GridLayout", [[name:"g", type:"STRING"]]
        command "setPage2GridLayout", [[name:"g", type:"STRING"]]
        command "setPage3GridLayout", [[name:"g", type:"STRING"]]
        command "setPage4GridLayout", [[name:"g", type:"STRING"]]
        command "setPage5GridLayout", [[name:"g", type:"STRING"]]
        command "setPage6GridLayout", [[name:"g", type:"STRING"]]

        command "setPage1MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]

        command "updatePage1Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage2Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage3Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage4Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage5Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage6Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage1SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage2SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage3SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage4SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage5SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage6SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]

        attribute "mqttStatus",      "string"
        attribute "displayRebooted",    "string"
        attribute "layoutPushComplete", "string"
        attribute "pushInProgress", "string"
        attribute "lightTapped",        "string"
        (1..6).each { pg ->
            attribute "page${pg}GridLayout", "string"
        }
    }

    preferences {
        input name: "mqttBroker",   type: "text",     title: "<b>MQTT Broker</b> (host:port)", required: true, defaultValue: "tcp://127.0.0.1:1883"
        input name: "mqttPassword", type: "password", title: "MQTT Password", required: true,
              description: "Found in Hubitat -> Integrations -> MQTT Broker"
        input name: "mqttClientId", type: "text",     title: "MQTT Client ID", required: true, defaultValue: "hubitat-sensecap-autopages"
        input name: "haspNode",     type: "text",     title: "<b>openHASP Node Name</b>", required: true, defaultValue: "plate"

        input name: "colorActive",          type: "enum", title: "<b>Active color</b>",     options: activeColorOptions(),   defaultValue: "#FF0000", required: true
        input name: "colorInactive",        type: "enum", title: "Inactive -- Motion",        options: colorOptions(), defaultValue: "#008000", required: true
        input name: "colorContactInactive", type: "enum", title: "Inactive -- Contact",       options: colorOptions(), defaultValue: "#00FFFF", required: true
        input name: "colorWaterInactive",   type: "enum", title: "Inactive -- Water",         options: colorOptions(), defaultValue: "#0000FF", required: true
        input name: "colorSmokeInactive",   type: "enum", title: "Inactive -- Smoke",         options: colorOptions(), defaultValue: "#FFFF00", required: true
        input name: "colorLightInactive",   type: "enum", title: "Inactive -- Light off",    options: colorOptions(), defaultValue: "#808080", required: true
        input name: "colorLightActive",     type: "enum", title: "Active -- Light on",      options: colorOptions(), defaultValue: "#FFFF00", required: true

        input name: "fadeDuration",      type: "number", title: "Fade duration (seconds)", defaultValue: 30, required: true
        input name: "showPageIndicator",  type: "bool",   title: "Show page indicator (e.g. 1/4)", defaultValue: true
        input name: "rotationInterval",   type: "number", title: "Auto-scroll pages every (seconds, 0 = off)", defaultValue: 10

        input name: "backlightOnMotion",      type: "bool",   title: "<b>Backlight ON</b> when sensor active",            defaultValue: true
        input name: "backlightOffDelay",      type: "number", title: "Backlight OFF after all clear (seconds, 0=never)",   defaultValue: 0
        input name: "motionBacklightTimeout", type: "number", title: "Backlight OFF after active for (minutes, 0=never)",  defaultValue: 1
        input name: "touchBacklightTimeout",  type: "number", title: "Backlight OFF after screen tap (seconds, 0=never)",  defaultValue: 30

        input name: "logLevel", type: "enum", title: "Logging Level",
              options: ["0":"None","1":"Info only","2":"Info + Debug"], defaultValue: "1", required: true
    }
}

private Map activeColorOptions() {
    ["#FF0000":"Red","#FF4500":"Orange-red","#FF8C00":"Dark orange","#FF1493":"Deep pink",
     "#8B0000":"Dark red","#FF6347":"Tomato","#DC143C":"Crimson","#FF0080":"Hot magenta"]
}
private Map colorOptions() {
    ["#F8F8FF":"Ghost White","#D3D3D3":"Light Gray","#808080":"Gray","#800000":"Maroon",
     "#FF00FF":"Magenta","#800080":"Purple","#0000FF":"Blue","#000080":"Navy","#00FFFF":"Cyan",
     "#008080":"Teal","#00FF00":"Lime","#008000":"Green","#FFFF00":"Yellow","#808000":"Olive"]
}

// -- Object ID helpers ----------------------------------------------------------
private int bgId(int slot)   { slot }
private int iconId(int slot) { slot + 50 }

// -- State key helpers ----------------------------------------------------------
private String stateKey(int page, int idx) { "p${page}sensor${idx}" }
private String typeKey(int page, int idx)  { "p${page}slotType${idx}" }
private String labelKey(int page, int idx) { "p${page}label${idx}" }

// -- Lifecycle ------------------------------------------------------------------

def installed() {
    infoLog "[AutoPages] Driver installed"
    initialize()
}

def updated() {
    infoLog "[AutoPages] Preferences updated"
    initialize()
}

def initialize() {
    String mqttSt = device.currentValue("mqttStatus") ?: ""
    if (!mqttSt.startsWith("Connected")) {
        connectMqtt()
    } else {
        infoLog "[AutoPages] MQTT already connected -- skipping reconnect"
    }
    unschedule("sendHeartbeat")
    runEvery5Minutes("sendHeartbeat")
}

def uninstalled() { disconnectMqtt() }

// -- Grid config ----------------------------------------------------------------

def setNumberOfPages(n) {
    int num = Math.min(4, Math.max(1, (n as int)))
    state.numberOfPages = num
    infoLog "[AutoPages] Number of pages set to ${num}"
}

def setPage1GridLayout(String g) {
    state.page1GridLayout = g
    switch (g) {
        case "1x1": state.page1MaxSlots = 1;  break
        case "3x3": state.page1MaxSlots = 9;  break
        case "4x4": state.page1MaxSlots = 16; break
        case "5x5": state.page1MaxSlots = 25; break
        case "6x6": state.page1MaxSlots = 36; break
        case "7x7": state.page1MaxSlots = 49; break
        default:    state.page1MaxSlots = 4
    }
    sendEvent(name: "page1GridLayout", value: g)
    infoLog "[AutoPages] Page 1 grid -> ${g}"
    (1..49).each { s -> state.remove("p1label${s}") }
}

def setPage2GridLayout(String g) {
    state.page2GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page2MaxSlots = 1;  break
        case "3x3": state.page2MaxSlots = 9;  break
        case "4x4": state.page2MaxSlots = 16; break
        case "5x5": state.page2MaxSlots = 25; break
        case "6x6": state.page2MaxSlots = 36; break
        case "7x7": state.page2MaxSlots = 49; break
        default:    state.page2MaxSlots = 4
    }
    sendEvent(name: "page2GridLayout", value: g)
    infoLog "[AutoPages] Page 2 grid -> ${g}"
    (1..49).each { s -> state.remove("p2label${s}") }
}

def setPage3GridLayout(String g) {
    state.page3GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page3MaxSlots = 1;  break
        case "3x3": state.page3MaxSlots = 9;  break
        case "4x4": state.page3MaxSlots = 16; break
        case "5x5": state.page3MaxSlots = 25; break
        case "6x6": state.page3MaxSlots = 36; break
        case "7x7": state.page3MaxSlots = 49; break
        default:    state.page3MaxSlots = 4
    }
    sendEvent(name: "page3GridLayout", value: g)
    infoLog "[AutoPages] Page 3 grid -> ${g}"
    (1..49).each { s -> state.remove("p3label${s}") }
}

def setPage4GridLayout(String g) {
    state.page4GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page4MaxSlots = 1;  break
        case "3x3": state.page4MaxSlots = 9;  break
        case "4x4": state.page4MaxSlots = 16; break
        case "5x5": state.page4MaxSlots = 25; break
        case "6x6": state.page4MaxSlots = 36; break
        case "7x7": state.page4MaxSlots = 49; break
        default:    state.page4MaxSlots = 4
    }
    sendEvent(name: "page4GridLayout", value: g)
    infoLog "[AutoPages] Page 4 grid -> ${g}"
    (1..49).each { s -> state.remove("p4label${s}") }
}
def setPage5GridLayout(String g) {
    state.page5GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page5MaxSlots = 1;  break
        case "3x3": state.page5MaxSlots = 9;  break
        case "4x4": state.page5MaxSlots = 16; break
        case "5x5": state.page5MaxSlots = 25; break
        case "6x6": state.page5MaxSlots = 36; break
        case "7x7": state.page5MaxSlots = 49; break
        default:    state.page5MaxSlots = 4
    }
    sendEvent(name: "page5GridLayout", value: g)
    infoLog "[AutoPages] Page 5 grid -> ${g}"
    (1..49).each { s -> state.remove("p5label${s}") }
}
def setPage6GridLayout(String g) {
    state.page6GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page6MaxSlots = 1;  break
        case "3x3": state.page6MaxSlots = 9;  break
        case "4x4": state.page6MaxSlots = 16; break
        case "5x5": state.page6MaxSlots = 25; break
        case "6x6": state.page6MaxSlots = 36; break
        case "7x7": state.page6MaxSlots = 49; break
        default:    state.page6MaxSlots = 4
    }
    sendEvent(name: "page6GridLayout", value: g)
    infoLog "[AutoPages] Page 6 grid -> ${g}"
    (1..49).each { s -> state.remove("p6label${s}") }
}

private String activeGrid(int page) {
    return (state["page${page}GridLayout"] ?: "2x2") as String
}

private int maxSensors(int page) {
    // Use stored maxSlots if available (set by setPageXGridLayout) for reliability
    int stored = (state["page${page}MaxSlots"] ?: 0) as int
    if (stored > 0) return stored
    switch (activeGrid(page)) {
        case "1x1": return 1;  case "3x3": return 9;   case "4x4": return 16
        case "5x5": return 25; case "6x6": return 36;  case "7x7": return 49
        default:    return 4
    }
}

// -- MQTT -----------------------------------------------------------------------

def connectMqtt() {
    if (!settings.mqttPassword) { infoLog "[AutoPages] MQTT password not set"; return }
    try {
        String broker   = settings.mqttBroker   ?: "tcp://127.0.0.1:1883"
        String clientId = settings.mqttClientId ?: "hubitat-sensecap-autopages-${device.id}"
        interfaces.mqtt.connect(broker, clientId, "hubitat", settings.mqttPassword)
        infoLog "[AutoPages] MQTT connected -> ${broker}"
        sendEvent(name: "mqttStatus", value: "Connected")
        String node = settings.haspNode ?: "plate"
        interfaces.mqtt.subscribe("hasp/${node}/state/statusupdate")
        interfaces.mqtt.subscribe("hasp/${node}/state/idle")
        interfaces.mqtt.subscribe("hasp/${node}/idle")
        interfaces.mqtt.subscribe("hasp/${node}/state/backlight")
        interfaces.mqtt.subscribe("hasp/${node}/backlight")
        interfaces.mqtt.subscribe("hasp/+/LWT")
        interfaces.mqtt.subscribe("hasp/+/state/statusupdate")
        interfaces.mqtt.subscribe("hasp/+/state/+")
        infoLog "[AutoPages] Subscribed -- node: ${node}"
    } catch (Exception e) {
        infoLog "[AutoPages] ERROR -- MQTT connect failed: ${e.message}"
        sendEvent(name: "mqttStatus", value: "Error: ${e.message}")
        runIn(30, "connectMqtt")
    }
}

def disconnectMqtt() {
    try { interfaces.mqtt.disconnect() } catch (Exception e) { }
    sendEvent(name: "mqttStatus", value: "Disconnected")
}

def reconnectMqtt() { disconnectMqtt(); pauseExecution(1000); connectMqtt() }

def rebootDisplay() {
    String node = settings.haspNode ?: "plate"
    infoLog "[AutoPages] Sending reboot command to display"
    try { interfaces.mqtt.publish("hasp/${node}/command", "reboot", 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- reboot failed: ${e.message}" }
}

def mqttClientStatus(String status) {
    infoLog "[AutoPages] MQTT status: ${status}"
    sendEvent(name: "mqttStatus", value: status)
    if (status.startsWith("Error") || status.contains("lost")) runIn(30, "connectMqtt")
}

def sendHeartbeat() {
    state.lastHeartbeatMs = now()
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command", "statusupdate", 1, false) }
    catch (Exception e) { infoLog "[AutoPages] Heartbeat failed -- reconnecting"; reconnectMqtt() }
}

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    debugLog "MQTT: topic=${msg.topic} payload=${msg.payload}"

    if (msg.topic.endsWith("/LWT")) {
        String actualNode = msg.topic.split("/")[1]
        String configNode = settings.haspNode ?: "plate"
        if (actualNode != configNode) {
            log.warn "[AutoPages] Node name mismatch! Device is '${actualNode}' but preference is '${configNode}'"
            sendEvent(name: "mqttStatus", value: "Wrong node name -- should be '${actualNode}'")
        }
        if (msg.payload?.trim() == "online") {
            infoLog "[AutoPages] LWT online (${actualNode}) -- display rebooted, pushing all layouts"
            state.pushInProgress = false
            state.suppressNavigation = false
            sendEvent(name: "pushInProgress", value: "false")
            unschedule("rotatePage")
            unschedule("returnToPage1AndStartRotation")
            runIn(5, "fireDisplayRebooted")
        }
        return
    }

    if (msg.topic.contains("statusupdate")) {
        if (!msg.payload?.trim()) return
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.uptime == null) return
            int uptime = (json.uptime) as int
            if (uptime < 30) {
                infoLog "[AutoPages] Display rebooted (uptime ${uptime}s)"
                state.pushInProgress = false
                state.suppressNavigation = false
                unschedule("rotatePage")
                unschedule("returnToPage1AndStartRotation")
                runIn(5, "fireDisplayRebooted")
            } else {
                infoLog "[AutoPages] Display woke from idle -- resyncing"
                if (!state.pushInProgress) { runIn(2, "resyncStates") }
                startBacklightTimer()
            }
        } catch (Exception e) { infoLog "[AutoPages] WARN -- Could not parse statusupdate: ${e.message}" }
        return
    }

    if (msg.topic.contains("state/idle") || msg.topic.endsWith("/idle")) {
        String v = msg.payload?.trim()
        if (v == "short" || v == "long") {
            state.screenIdle = true
        } else if (v == "off") {
            long ms = now() - (state.lastHeartbeatMs ?: 0L)
            if (ms >= 3000) { state.screenIdle = false; infoLog "[AutoPages] Screen woke from touch"; startBacklightTimer() }
        }
        return
    }

    if (msg.topic.contains("state/backlight") || msg.topic.endsWith("/backlight")) {
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.state == "off")     { state.screenIdle = true }
            else if (json.state == "on" && state.screenIdle) { state.screenIdle = false; startBacklightTimer() }
        } catch (Exception e) { if (msg.payload?.trim() == "off") state.screenIdle = true }
        return
    }

    // Button tap events: topic = hasp/{node}/state/pXbY, payload = {"event":"down"} or {"event":"up"}
    String cfgNode = settings.haspNode ?: "plate"
    if (msg.topic.contains("/state/p") && msg.topic.contains("b") && msg.topic.contains(cfgNode)) {
        debugLog "[AutoPages] Button topic: ${msg.topic} payload: ${msg.payload}"
        handleButtonTap(msg.topic, msg.payload)
        return
    }
}

// -- Button tap handler --------------------------------------------------------

private void handleButtonTap(String topic, String payload) {
    if (!payload?.contains('"up"')) return
    def matcher = topic =~ /state\/p(\d+)b(\d+)$/
    if (!matcher) return
    int page  = matcher[0][1] as int
    int btnId = matcher[0][2] as int
    if (btnId < 1 || btnId > 49) return
    int slot  = btnId
    String sType = state[typeKey(page, slot)] ?: "none"
    if (sType != "light") return
    debugLog "[AutoPages] Light tile tapped: page ${page} slot ${slot}"
    sendEvent(name: "lightTapped", value: "${page},${slot},${now()}")
}

// -- Backlight ------------------------------------------------------------------

private void startBacklightTimer() {
    if (!settings.backlightOnMotion) return
    unschedule("backlightOff")
    if (!allInactive()) {
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
    } else {
        int delay = (settings.touchBacklightTimeout ?: 30) as int
        if (delay > 0) runIn(delay, "backlightOff")
    }
}

def backlightOff() { publishBacklight(false); state.screenIdle = true }

def backlightOnAfterFade() {
    if (!settings.backlightOnMotion || !allInactive()) return
    state.screenIdle = false; publishBacklight(true)
    int delay = (settings.backlightOffDelay ?: 0) as int
    if (delay > 0) runIn(delay, "backlightOff")
}

def motionTimeoutBacklightOff() {
    if (!settings.backlightOnMotion) return
    if (!allInactive()) { backlightOff() }
}

private boolean allInactive() {
    int numPg = (state.numberOfPages ?: 6) as int
    (1..numPg).every { pg ->
        (1..maxSensors(pg)).every { idx ->
            String sType = state[typeKey(pg, idx)] ?: "none"
            // Lights being on should not count as "active" for rotation/backlight purposes
            if (sType == "light") return true
            return state[stateKey(pg, idx)] != "active"
        }
    }
}

private void publishBacklight(boolean on) {
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/backlight", on ? '{"state":"on","brightness":255}' : '{"state":"off"}', 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- Backlight publish failed: ${e.message}" }
}

// -- Resync ---------------------------------------------------------------------

def resyncStates() {
    if (state.pushInProgress) { infoLog "[AutoPages] Skipping resync -- layout push in progress"; return }
    infoLog "[AutoPages] Resyncing all page states"
    int numPg = (state.numberOfPages ?: 6) as int
    (1..numPg).each { pg ->
        (1..maxSensors(pg)).each { idx ->
            String st = state[stateKey(pg, idx)] ?: "inactive"
            if (st == "empty")       { setSlotEmptyForPage(pg, idx) }
            else if (st == "active") { setMotionActiveForPage(pg, idx) }
            else {
                publishColor(pg, idx, inactiveColorFor(pg, idx))
                publishTextColor(pg, idx, inactiveColorFor(pg, idx))
                publishIcon(pg, idx, inactiveIconFor(pg, idx))
            }
            pauseExecution(30)
        }
    }
}

def fireDisplayRebooted() {
    sendEvent(name: "displayRebooted", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

// -- Layout push -------------------------------------------------------------

private int pageRenderSeconds(int page) {
    // Estimate render time per page:
    // Layout objects: (slots x 2) x 50ms
    // Slot render: slots x 160ms (color + icon + label + pauses)
    // Light tile recreation: slots x 70ms extra (worst case all lights)
    // Pauses + buffer: 3s
    int slots = maxSensors(page)
    int ms = (slots * 2 * 50) + (slots * 160) + (slots * 70) + 3000
    return Math.ceil(ms / 1000.0) as int
}

def pushAllLayouts(numberOfPages) {
    int np = Math.min(6, Math.max(1, (numberOfPages as int)))
    state.numberOfPages = np
    state.lastPushMs    = now()
    unschedule("rotatePage")
    state.rotationPage = 1
    state.pushInProgress = true
    state.suppressNavigation = true
    sendEvent(name: "pushInProgress", value: "true")
    unschedule("rotatePage")
    unschedule("returnToPage1AndStartRotation")
    infoLog "[AutoPages] pushAllLayouts -- ${np} page(s)"
    sendEvent(name: "mqttStatus", value: "Building layouts...")

    // Clear ALL pages first so stale objects from prior sessions are gone
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command", "clearpage 0", 1, false); pauseExecution(500) }
    catch (Exception e) { infoLog "[AutoPages] WARN -- clearpage 0 failed: ${e.message}" }

    // Fixed 15-second gaps between pages -- covers worst case 5x5 light page (25 slots x ~400ms each = 10s + buffer)
    runIn(2, "pushPage1Layout")
}

def pushPage1Layout() {
    publishBacklight(true)
    pushPageLayout(1)
}

def pushPage2Layout() {
    int np2 = (state.numberOfPages ?: 6) as int
    if (np2 >= 2) pushPageLayout(2)
}

def pushPage3Layout() {
    int np3 = (state.numberOfPages ?: 6) as int
    if (np3 >= 3) pushPageLayout(3)
}

def pushPage4Layout() {
    int np4 = (state.numberOfPages ?: 6) as int
    if (np4 >= 4) pushPageLayout(4)
}
def pushPage5Layout() {
    int np5 = (state.numberOfPages ?: 6) as int
    if (np5 >= 5) pushPageLayout(5)
}
def pushPage6Layout() {
    int np6 = (state.numberOfPages ?: 6) as int
    if (np6 >= 6) pushPageLayout(6)
}

private void pushPageLayout(int page) {
    String grid  = activeGrid(page)
    int total    = (state.numberOfPages ?: 6) as int
    String node  = settings.haspNode ?: "plate"

    infoLog "[AutoPages] Pushing page ${page}/${total}: ${grid}"
    sendEvent(name: "mqttStatus", value: "Pushing page ${page}/${total}...")

    // Clear this page in background
    try { interfaces.mqtt.publish("hasp/${node}/command", "clearpage ${page}", 1, false); pauseExecution(100) }
    catch (Exception e) { }

    // Cancel any in-progress fades for this page before re-rendering
    (1..maxSensors(page)).each { s ->
        unschedule("p${page}fadeStep${s}")
        state.remove("p${page}fadeStep${s}")
    }

    // 1. Push tile layout (btns + icon overlays + nav buttons + page indicator)
    layoutJsonl(grid, page, total).each { jsonl ->
        try { interfaces.mqtt.publish("hasp/${node}/command/jsonl", jsonl, 1, false); pauseExecution(30) }
        catch (Exception e) { infoLog "[AutoPages] ERROR -- layout push p${page}: ${e.message}" }
    }

    // 2. Push colors, icons and labels for every slot immediately after layout
    // Adaptive pre-slot pause: larger grids need more time for layout objects to render
    int prePause = Math.min(500, 100 + (maxSensors(page) * 5))
    pauseExecution(prePause)
    (1..maxSensors(page)).each { idx ->
        String slotType = state[typeKey(page, idx)] ?: "none"
        if (!slotType || slotType == "none") {
            publishColor(page, idx, "#708090")
            publishTextColor(page, idx, "#708090")
            publishIcon(page, idx, "")
        } else if (slotType == "light") {
            // For lights use actual current state -- avoids wrong color before syncAllSensors runs
            String lightState = state[stateKey(page, idx)] ?: "inactive"
            if (lightState == "active") {
                String lc = settings.colorLightActive ?: "#FFFF00"
                publishColor(page, idx, lc)
                publishTextColor(page, idx, lc)
            } else {
                String ic = inactiveColorFor(page, idx)
                publishColor(page, idx, ic)
                publishTextColor(page, idx, ic)
            }
            publishIcon(page, idx, ICON_LIGHTBULB_OFF)
            pauseExecution(20)
            // Re-define the btn object with click:true, passing current color and label
            // so the rebuild doesn't flash blank
            String lightColor = (lightState == "active") ? (settings.colorLightActive ?: "#FFFF00") : inactiveColorFor(page, idx)
            String lightLabel = state[labelKey(page, idx)] ?: ""
            String clickJsonl = buildLightTileJsonl(page, idx, grid, lightColor, lightLabel)
            if (clickJsonl) {
                try { interfaces.mqtt.publish("hasp/${node}/command/jsonl", clickJsonl, 1, false) }
                catch (Exception e) { infoLog "[AutoPages] WARN -- light click enable failed: ${e.message}" }
            }
        } else {
            String ic = inactiveColorFor(page, idx)
            publishColor(page, idx, ic)
            publishTextColor(page, idx, ic)
            publishIcon(page, idx, inactiveIconFor(page, idx))
        }
        // Publish label for non-light slots (lights include label in click jsonl)
        if (slotType != "light") {
            String lbl = state[labelKey(page, idx)] ?: ""
            if (lbl) {
                publishJsonl(node, page, bgId(idx), [text: lbl])
                pauseExecution(20)
            }
        }
        pauseExecution(20)
    }

    // Schedule per-page navigate using literal method names (no shared state)
    int drainSecs = 3 + (int)(maxSensors(page) * 0.12) // ~3s small, ~6s for 5x5
    infoLog "[AutoPages] Scheduling navigate for page ${page}/${total} in ${drainSecs}s"
    switch (page) {
        case 1: runIn(drainSecs, "navigatePage1"); break
        case 2: runIn(drainSecs, "navigatePage2"); break
        case 3: runIn(drainSecs, "navigatePage3"); break
        case 4: runIn(drainSecs, "navigatePage4"); break
        case 5: runIn(drainSecs, "navigatePage5"); break
        case 6: runIn(drainSecs, "navigatePage6"); break
    }
}

def navigatePage1() { doNavigate(1) }
def navigatePage2() { doNavigate(2) }
def navigatePage3() { doNavigate(3) }
def navigatePage4() { doNavigate(4) }
def navigatePage5() { doNavigate(5) }
def navigatePage6() { doNavigate(6) }

private void doNavigate(int page) {
    int total = (state.numberOfPages ?: 6) as int
    String node = settings.haspNode ?: "plate"
    infoLog "[AutoPages] Navigating to page ${page}/${total}"
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${page}", 1, false) }
    catch (Exception e) { }
    if (page < total) {
        switch (page) {
            case 1: runIn(1, "pushPage2Layout"); break
            case 2: runIn(1, "pushPage3Layout"); break
            case 3: runIn(1, "pushPage4Layout"); break
            case 4: runIn(1, "pushPage5Layout"); break
            case 5: runIn(1, "pushPage6Layout"); break
        }
    } else {
        state.pushInProgress = false
        infoLog "[AutoPages] All ${total} page(s) pushed"
        runIn(12, "returnToPage1AndStartRotation")
    }
}

def returnToPage1AndStartRotation() {
    state.suppressNavigation = false
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "1", 1, false) } catch (Exception e) { }
    state.pushInProgress = false
    sendEvent(name: "pushInProgress", value: "false")
    sendEvent(name: "mqttStatus", value: "Connected")
    // Now that we are back on page 1, notify app to sync sensor states
    sendEvent(name: "layoutPushComplete", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    int total = (state.numberOfPages ?: 1) as int
    if (total > 1) {
        int rotInt = (settings.rotationInterval ?: 0) as int
        if (rotInt > 0) {
            state.rotationPage = 1
            unschedule("rotatePage")
            runIn(rotInt, "rotatePage")
        }
    }
}

// -- Layout JSONL generators ----------------------------------------------------

private List<String> layoutJsonl(String grid, int page, int totalPages) {
    List<String> out
    switch (grid) {
        case "1x1": out = layout1x1(page); break
        case "3x3": out = layout3x3(page); break
        case "4x4": out = layout4x4(page); break
        case "5x5": out = layoutNxN(page, 5, 94, 2, 12, 2, 24); break
        case "6x6": out = layoutNxN(page, 6, 78, 2, 12, 2, 12); break
        case "7x7": out = layoutNxN(page, 7, 67, 1, 12, 1, 12); break
        default:    out = layout2x2(page)
    }

    if (totalPages > 1) {
        int prevPage = (page == 1) ? totalPages : page - 1
        int nextPage = (page == totalPages) ? 1 : page + 1
        // Full-height invisible edge tap zones -- 40px wide, full 480px height, ~8% opacity
        out << """{"page":${page},"id":201,"obj":"btn","x":0,"y":0,"w":30,"h":480,"bg_color":"#000000","bg_opa":20,"border_width":0,"radius":0,"text":"","text_font":8,"toggle":false,"action":"p${prevPage}"}"""
        out << """{"page":${page},"id":202,"obj":"btn","x":450,"y":0,"w":30,"h":480,"bg_color":"#000000","bg_opa":20,"border_width":0,"radius":0,"text":"","text_font":8,"toggle":false,"action":"p${nextPage}"}"""
        if (settings.showPageIndicator == true) {
            out << """{"page":${page},"id":200,"obj":"label","x":424,"y":4,"w":54,"h":22,"bg_color":"#000000","bg_opa":180,"border_width":0,"radius":4,"text":"${page}/${totalPages}","text_font":16,"text_color":"white","align":"center","click":false}"""
        } else {
            out << """{"page":${page},"id":200,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        }
    } else {
        // Single page -- erase any stale nav objects from prior layouts
        out << """{"page":${page},"id":200,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        out << """{"page":${page},"id":201,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        out << """{"page":${page},"id":202,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
    }
    return out
}

private List<String> layout1x1(int page) {[
    """{"page":${page},"id":1,"obj":"btn","x":2,"y":2,"w":476,"h":476,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false}""",
    """{"page":${page},"id":51,"obj":"label","parentid":0,"x":8,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
]}

private List<String> layout2x2(int page) {
    List<String> out = []
    [[1,2,2,236,236],[2,242,2,236,236],[3,2,242,236,236],[4,242,242,236,236]].each { r ->
        out << """{"page":${page},"id":${r[0]},"obj":"btn","x":${r[1]},"y":${r[2]},"w":${r[3]},"h":${r[4]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }
    [[51,8,8],[52,248,8],[53,8,248],[54,248,248]].each { r ->
        out << """{"page":${page},"id":${r[0]},"obj":"label","parentid":0,"x":${r[1]},"y":${r[2]},"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
    }
    return out
}

private List<String> layout3x3(int page) {
    List<String> out = []
    int[][] cells = [[2,2,157,157],[161,2,157,157],[320,2,158,157],[2,161,157,157],[161,161,157,157],[320,161,158,157],[2,320,157,158],[161,320,157,158],[320,320,158,158]]
    cells.eachWithIndex { c, i ->
        out << """{"page":${page},"id":${i+1},"obj":"btn","x":${c[0]},"y":${c[1]},"w":${c[2]},"h":${c[3]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }
    int[][] icons = [[8,8],[167,8],[326,8],[8,167],[167,167],[326,167],[8,326],[167,326],[326,326]]
    icons.eachWithIndex { c, i ->
        out << """{"page":${page},"id":${i+51},"obj":"label","parentid":0,"x":${c[0]},"y":${c[1]},"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
    }
    return out
}

private List<String> layout4x4(int page) {
    List<String> out = []; int cols = 4; int w = 117; int gap = 2
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }}
    int id2 = 51
    (0..<cols).each { row -> (0..<cols).each { col ->
        int x = col*(w+gap)+gap+4; int y = row*(w+gap)+gap+4
        out << """{"page":${page},"id":${id2},"obj":"label","parentid":0,"x":${x},"y":${y},"w":24,"h":24,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
        id2++
    }}
    return out
}

private List<String> layoutNxN(int page, int cols, int w, int gap, int tf, int iconOff, int iconFont) {
    List<String> out = []
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":${tf},"align":"center","text_color":"black","toggle":false,"click":false}"""
    }}
    int id2 = 51
    (0..<cols).each { row -> (0..<cols).each { col ->
        int x = col*(w+gap)+gap+iconOff; int y = row*(w+gap)+gap+iconOff
        out << """{"page":${page},"id":${id2},"obj":"label","parentid":0,"x":${x},"y":${y},"w":${iconFont},"h":${iconFont},"bg_opa":0,"border_width":0,"text":"","text_font":${iconFont},"text_color":"black","click":false}"""
        id2++
    }}
    return out
}

// -- Page commands --------------------------------------------------------------

def setPage1MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(1)) setMotionActiveForPage(1,i) }
def setPage1MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(1)) setMotionInactiveForPage(1,i) }
def setPage1SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(1)) setSlotEmptyForPage(1,i) }
def setPage2MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(2)) setMotionActiveForPage(2,i) }
def setPage2MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(2)) setMotionInactiveForPage(2,i) }
def setPage2SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(2)) setSlotEmptyForPage(2,i) }
def setPage3MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(3)) setMotionActiveForPage(3,i) }
def setPage3MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(3)) setMotionInactiveForPage(3,i) }
def setPage3SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(3)) setSlotEmptyForPage(3,i) }
def setPage4MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(4)) setMotionActiveForPage(4,i) }
def setPage4MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(4)) setMotionInactiveForPage(4,i) }
def setPage4SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(4)) setSlotEmptyForPage(4,i) }
def setPage5MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(5)) setMotionActiveForPage(5,i) }
def setPage5MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(5)) setMotionInactiveForPage(5,i) }
def setPage5SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(5)) setSlotEmptyForPage(5,i) }
def setPage6MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(6)) setMotionActiveForPage(6,i) }
def setPage6MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(6)) setMotionInactiveForPage(6,i) }
def setPage6SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(6)) setSlotEmptyForPage(6,i) }

private void setMotionActiveForPage(int page, int idx) {
    String sk = stateKey(page, idx)
    state[sk] = "active"
    unschedule("p${page}fadeStep${idx}")
    state.remove("p${page}fadeStep${idx}")
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    if (sType == "light") {
        // Lights: rebuild full btn with click:true, color, label so nothing gets wiped
        String lc = settings.colorLightActive ?: "#FFFF00"
        String grid = activeGrid(page)
        String lbl  = state[labelKey(page, idx)] ?: ""
        String clickJsonl = buildLightTileJsonl(page, idx, grid, lc, lbl)
        if (clickJsonl) {
            String node = settings.haspNode ?: "plate"
            try { interfaces.mqtt.publish("hasp/${node}/command/jsonl", clickJsonl, 1, false) }
            catch (Exception e) { infoLog "[AutoPages] WARN -- light active jsonl failed: ${e.message}" }
        }
        publishIcon(page, idx, ICON_LIGHTBULB_ON)
        return
    }
    String ac = settings.colorActive ?: "#FF0000"
    publishColor(page, idx, ac)
    publishTextColor(page, idx, ac)
    publishIcon(page, idx, activeIconFor(page, idx))
    // Navigate display to the page containing the active sensor and stop rotation
    // Skip navigation if a layout push just completed (suppress during post-push sync)
    if (!state.suppressNavigation) {
        unschedule("rotatePage")
        String node = settings.haspNode ?: "plate"
        try { interfaces.mqtt.publish("hasp/${node}/command/page", "${page}", 1, false) }
        catch (Exception e) { infoLog "[AutoPages] ERROR -- page nav on active failed: ${e.message}" }
    }
    if (settings.backlightOnMotion) {
        unschedule("backlightOff")
        unschedule("motionTimeoutBacklightOff")
        state.screenIdle = false
        publishBacklight(true)
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
    }
}

private void setMotionInactiveForPage(int page, int idx) {
    String sk      = stateKey(page, idx)
    String fadeKey = "p${page}fadeStep${idx}"
    boolean wasActive = (state[sk] == "active")
    state[sk] = "inactive"
    // Lights skip fade -- just snap directly to inactive color
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    if (sType == "light") {
        unschedule(fadeKey); state.remove(fadeKey)
        String ic   = inactiveColorFor(page, idx)
        String grid = activeGrid(page)
        String lbl  = state[labelKey(page, idx)] ?: ""
        String clickJsonl = buildLightTileJsonl(page, idx, grid, ic, lbl)
        if (clickJsonl) {
            String node = settings.haspNode ?: "plate"
            try { interfaces.mqtt.publish("hasp/${node}/command/jsonl", clickJsonl, 1, false) }
            catch (Exception e) { infoLog "[AutoPages] WARN -- light inactive jsonl failed: ${e.message}" }
        }
        publishIcon(page, idx, ICON_LIGHTBULB_OFF)
        return
    }
    if (wasActive) {
        unschedule(fadeKey); state[fadeKey] = 0
        publishIcon(page, idx, inactiveIconFor(page, idx))
        scheduleFadeStep(page, idx)
        if (settings.backlightOnMotion) {
            unschedule("motionTimeoutBacklightOff")
            if (!allInactive()) {
                int mins = (settings.motionBacklightTimeout ?: 1) as int
                if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
            } else {
                runIn((FADE_STEPS + 1) * fadeInterval() + 2, "backlightOnAfterFade")
            }
        }
    } else {
        String ic = inactiveColorFor(page, idx)
        publishColor(page, idx, ic)
        publishTextColor(page, idx, ic)
        publishIcon(page, idx, inactiveIconFor(page, idx))
        if (settings.backlightOnMotion && allInactive()) {
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) { unschedule("backlightOff"); runIn(delay, "backlightOff") }
        }
    }
}

private void setSlotEmptyForPage(int page, int idx) {
    String sk = stateKey(page, idx); String fadeKey = "p${page}fadeStep${idx}"
    state[sk] = "empty"
    unschedule(fadeKey); state.remove(fadeKey)
    publishColor(page, idx, "#708090")
    publishTextColor(page, idx, "#708090")
    publishIcon(page, idx, "")
    String node = settings.haspNode ?: "plate"
    publishJsonl(node, page, bgId(idx),   [text: ""])
    publishJsonl(node, page, iconId(idx), [text: ""])
}

// -- Label / type updates -------------------------------------------------------

def updatePage1Labels(labels)    { applyLabels(labels, 1) }
def updatePage2Labels(labels)    { applyLabels(labels, 2) }
def updatePage3Labels(labels)    { applyLabels(labels, 3) }
def updatePage4Labels(labels)    { applyLabels(labels, 4) }
def updatePage5Labels(labels)    { applyLabels(labels, 5) }
def updatePage6Labels(labels)    { applyLabels(labels, 6) }
def updatePage1SlotTypes(types)  { applySlotTypes(types, 1) }
def updatePage2SlotTypes(types)  { applySlotTypes(types, 2) }
def updatePage3SlotTypes(types)  { applySlotTypes(types, 3) }
def updatePage4SlotTypes(types)  { applySlotTypes(types, 4) }
def updatePage5SlotTypes(types)  { applySlotTypes(types, 5) }
def updatePage6SlotTypes(types)  { applySlotTypes(types, 6) }

private void applyLabels(labels, int page) {
    if (!(labels instanceof Map)) {
        try { labels = new groovy.json.JsonSlurper().parseText(labels.toString()) }
        catch (Exception e) { infoLog "[AutoPages] WARN -- bad labels JSON: ${e.message}"; return }
    }
    // Store labels in state only -- publishing happens during pushPageLayout
    labels.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > 49) return
        String lbl = v?.toString() ?: ""
        state[labelKey(page, idx)] = lbl
    }
    infoLog "[AutoPages] Labels stored for page ${page}: ${labels.size()} entries"
}

private void applySlotTypes(slotTypes, int page) {
    if (!(slotTypes instanceof Map)) {
        try { slotTypes = new groovy.json.JsonSlurper().parseText(slotTypes.toString()) }
        catch (Exception e) { infoLog "[AutoPages] WARN -- bad slotTypes JSON: ${e.message}"; return }
    }
    Map typeCounts = [:]
    slotTypes.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > 49) return
        String t = (v?.toString() ?: "none")
        state[typeKey(page, idx)] = t
        if (t != "none") typeCounts[t] = ((typeCounts[t] ?: 0) as int) + 1
    }
    // Store dominant type as page-level fallback so inactiveIconFor/ColorFor always work
    if (typeCounts) {
        String dominant = typeCounts.max { it.value }.key
        state["pageType${page}"] = dominant
        infoLog "[AutoPages] Page ${page} type: ${dominant}"
    }
}

// -- Light tile helper -------------------------------------------------------

private String buildLightTileJsonl(int page, int slot, String grid, String bgColor = "#000000", String label = "") {
    // Build the x,y,w,h for this slot based on grid layout
    // This mirrors the layout methods but for a single slot
    int col0, row0, tw, th
    switch (grid) {
        case "1x1":
            col0=0; row0=0; tw=476; th=476; break
        case "2x2":
            int[][] c2 = [[2,2,236,236],[242,2,236,236],[2,242,236,236],[242,242,236,236]]
            if (slot<1||slot>4) return null
            col0=c2[slot-1][0]; row0=c2[slot-1][1]; tw=c2[slot-1][2]; th=c2[slot-1][3]; break
        default:
            // For NxN grids calculate position
            int n
            switch (grid) {
                case "3x3": n=3; break; case "4x4": n=4; break; case "5x5": n=5; break
                case "6x6": n=6; break; case "7x7": n=7; break; default: n=2
            }
            int w = (grid=="3x3")?157:(grid=="4x4")?117:(grid=="5x5")?94:(grid=="6x6")?78:67
            int gap = (grid=="7x7")?1:2
            int r = (slot-1).intdiv(n); int c = (slot-1)%n
            col0 = c*(w+gap)+gap; row0 = r*(w+gap)+gap
            tw = (c==n-1)?(480-col0-gap):w
            th = (r==n-1)?(480-row0-gap):w
    }
    int tf = (grid=="3x3"||grid=="2x2"||grid=="1x1")?24:((grid=="4x4")?16:12)
    String textColor = textColorFor(bgColor)
    String escapedLabel = label.replace('"', '\\"')
    return """{"page":${page},"id":${slot},"obj":"btn","x":${col0},"y":${row0},"w":${tw},"h":${th},"bg_color":"${bgColor}","border_color":"black","border_width":2,"radius":8,"text":"${escapedLabel}","text_font":${tf},"align":"center","text_color":"${textColor}","toggle":false,"click":true}"""
}

// -- MQTT publish helpers -------------------------------------------------------

private void publishColor(int page, int slot, String hex) {
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/p${page}b${bgId(slot)}.bg_color", hex, 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- Color publish failed: ${e.message}" }
}

private void publishTextColor(int page, int slot, String bgHex) {
    String node  = settings.haspNode ?: "plate"
    String color = textColorFor(bgHex)
    String lbl   = state[labelKey(page, slot)] ?: ""
    if (lbl) {
        publishJsonl(node, page, bgId(slot), [text_color: color, text: lbl])
    } else {
        publishJsonl(node, page, bgId(slot), [text_color: color])
    }
    if (useLetterIcon(page)) publishJsonl(node, page, iconId(slot), [text_color: color])
}

private void publishIcon(int page, int slot, String glyph) {
    String node  = settings.haspNode ?: "plate"
    int fontPt   = useLetterIcon(page) ? 12 : 24
    publishJsonl(node, page, iconId(slot), [text: glyph, text_font: fontPt])
}

private void publishJsonl(String node, int page, int objId, Map props) {
    String json  = groovy.json.JsonOutput.toJson(props)
    String topic = "hasp/${node}/command/p${page}b${objId}.jsonl"
    try { interfaces.mqtt.publish(topic, json, 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- JSONL publish failed: ${e.message}" }
}

// -- Color / icon helpers -------------------------------------------------------

private String inactiveColorFor(int page, int idx) {
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    switch (sType) {
        case "contact": return settings.colorContactInactive ?: "#00FFFF"
        case "water":   return settings.colorWaterInactive   ?: "#0000FF"
        case "smoke":   return settings.colorSmokeInactive   ?: "#FFFF00"
        case "light":   return settings.colorLightInactive   ?: "#808080"
        default:        return settings.colorInactive        ?: "#008000"
    }
}

private String textColorFor(String hex) {
    String h = hex.replace("#", "")
    int r = Integer.parseInt(h[0..1], 16)
    int g = Integer.parseInt(h[2..3], 16)
    int b = Integer.parseInt(h[4..5], 16)
    return (0.2126*(r/255.0) + 0.7152*(g/255.0) + 0.0722*(b/255.0)) > 0.35 ? "black" : "white"
}

@Field static final String ICON_ALERT   = "\uE026"
@Field static final String ICON_MOTION  = "\uE70E"
@Field static final String ICON_CONTACT = "\uE2DC"
@Field static final String ICON_WATER   = "\uE58C"
@Field static final String ICON_SMOKE      = "\uE238"
@Field static final String ICON_LIGHTBULB_OFF = "\uE335"
@Field static final String ICON_LIGHTBULB_ON  = "\uE335"

private boolean useLetterIcon(int page) { activeGrid(page) in ["6x6", "7x7"] }

private String activeIconFor(int page, int idx) {
    if (useLetterIcon(page)) return letterIconFor(page, idx)
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    if (sType == "light") return ICON_LIGHTBULB_ON
    return ICON_ALERT
}

private String inactiveIconFor(int page, int idx) {
    if (useLetterIcon(page)) return letterIconFor(page, idx)
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    switch (sType) {
        case "contact": return ICON_CONTACT
        case "water":   return ICON_WATER
        case "smoke":   return ICON_SMOKE
        case "light":   return ICON_LIGHTBULB_OFF
        default:        return ICON_MOTION
    }
}

private String letterIconFor(int page, int idx) {
    switch (state[typeKey(page, idx)] ?: "motion") {
        case "contact": return "C"; case "water": return "W"
        case "smoke":   return "S"; case "none":  return ""; case "light": return "L"
        default:        return "M"
    }
}

// -- Fade -----------------------------------------------------------------------

@Field static final int FADE_STEPS = 6

private int fadeInterval() {
    Math.max(1, Math.round(((settings.fadeDuration ?: 30) as int) / FADE_STEPS) as int)
}

private void scheduleFadeStep(int page, int idx) {
    runIn(fadeInterval(), "p${page}fadeStep${idx}")
}

private void doFadeStep(int page, int idx) {
    String sk      = stateKey(page, idx)
    String fadeKey = "p${page}fadeStep${idx}"
    if (state[sk] == "active") return
    int step   = (state[fadeKey] ?: 0) as int
    double t   = step / (FADE_STEPS as double)
    String fromHex = (settings.colorActive ?: "#FF0000").replace("#", "")
    String toHex   = inactiveColorFor(page, idx).replace("#", "")
    int fR = Integer.parseInt(fromHex[0..1], 16); int fG = Integer.parseInt(fromHex[2..3], 16); int fB = Integer.parseInt(fromHex[4..5], 16)
    int tR = Integer.parseInt(toHex[0..1], 16);   int tG = Integer.parseInt(toHex[2..3], 16);   int tB = Integer.parseInt(toHex[4..5], 16)
    int r = Math.max(0, Math.min(255, Math.round(fR+(tR-fR)*t) as int))
    int g = Math.max(0, Math.min(255, Math.round(fG+(tG-fG)*t) as int))
    int b = Math.max(0, Math.min(255, Math.round(fB+(tB-fB)*t) as int))
    publishColor(page, idx, sprintf("#%02X%02X%02X", r, g, b))
    if (step < FADE_STEPS) {
        state[fadeKey] = step + 1; scheduleFadeStep(page, idx)
    } else {
        state.remove(fadeKey)
        String snap = inactiveColorFor(page, idx)
        publishColor(page, idx, snap); publishTextColor(page, idx, snap)
        if (settings.backlightOnMotion && allInactive() && !state.screenIdle) {
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) { unschedule("backlightOff"); runIn(delay, "backlightOff") }
        }
        // Resume page rotation now that all sensors are clear
        if (allInactive()) {
            int rotInt = (settings.rotationInterval ?: 0) as int
            if (rotInt > 0) {
                unschedule("rotatePage")
                runIn(rotInt, "rotatePage")
            }
        }
    }
}

// Fade step methods -- 4 pages x 49 slots
def p1fadeStep1(){doFadeStep(1,1)}
def p1fadeStep2(){doFadeStep(1,2)}
def p1fadeStep3(){doFadeStep(1,3)}
def p1fadeStep4(){doFadeStep(1,4)}
def p1fadeStep5(){doFadeStep(1,5)}
def p1fadeStep6(){doFadeStep(1,6)}
def p1fadeStep7(){doFadeStep(1,7)}
def p1fadeStep8(){doFadeStep(1,8)}
def p1fadeStep9(){doFadeStep(1,9)}
def p1fadeStep10(){doFadeStep(1,10)}
def p1fadeStep11(){doFadeStep(1,11)}
def p1fadeStep12(){doFadeStep(1,12)}
def p1fadeStep13(){doFadeStep(1,13)}
def p1fadeStep14(){doFadeStep(1,14)}
def p1fadeStep15(){doFadeStep(1,15)}
def p1fadeStep16(){doFadeStep(1,16)}
def p1fadeStep17(){doFadeStep(1,17)}
def p1fadeStep18(){doFadeStep(1,18)}
def p1fadeStep19(){doFadeStep(1,19)}
def p1fadeStep20(){doFadeStep(1,20)}
def p1fadeStep21(){doFadeStep(1,21)}
def p1fadeStep22(){doFadeStep(1,22)}
def p1fadeStep23(){doFadeStep(1,23)}
def p1fadeStep24(){doFadeStep(1,24)}
def p1fadeStep25(){doFadeStep(1,25)}
def p1fadeStep26(){doFadeStep(1,26)}
def p1fadeStep27(){doFadeStep(1,27)}
def p1fadeStep28(){doFadeStep(1,28)}
def p1fadeStep29(){doFadeStep(1,29)}
def p1fadeStep30(){doFadeStep(1,30)}
def p1fadeStep31(){doFadeStep(1,31)}
def p1fadeStep32(){doFadeStep(1,32)}
def p1fadeStep33(){doFadeStep(1,33)}
def p1fadeStep34(){doFadeStep(1,34)}
def p1fadeStep35(){doFadeStep(1,35)}
def p1fadeStep36(){doFadeStep(1,36)}
def p1fadeStep37(){doFadeStep(1,37)}
def p1fadeStep38(){doFadeStep(1,38)}
def p1fadeStep39(){doFadeStep(1,39)}
def p1fadeStep40(){doFadeStep(1,40)}
def p1fadeStep41(){doFadeStep(1,41)}
def p1fadeStep42(){doFadeStep(1,42)}
def p1fadeStep43(){doFadeStep(1,43)}
def p1fadeStep44(){doFadeStep(1,44)}
def p1fadeStep45(){doFadeStep(1,45)}
def p1fadeStep46(){doFadeStep(1,46)}
def p1fadeStep47(){doFadeStep(1,47)}
def p1fadeStep48(){doFadeStep(1,48)}
def p1fadeStep49(){doFadeStep(1,49)}

def p2fadeStep1(){doFadeStep(2,1)}
def p2fadeStep2(){doFadeStep(2,2)}
def p2fadeStep3(){doFadeStep(2,3)}
def p2fadeStep4(){doFadeStep(2,4)}
def p2fadeStep5(){doFadeStep(2,5)}
def p2fadeStep6(){doFadeStep(2,6)}
def p2fadeStep7(){doFadeStep(2,7)}
def p2fadeStep8(){doFadeStep(2,8)}
def p2fadeStep9(){doFadeStep(2,9)}
def p2fadeStep10(){doFadeStep(2,10)}
def p2fadeStep11(){doFadeStep(2,11)}
def p2fadeStep12(){doFadeStep(2,12)}
def p2fadeStep13(){doFadeStep(2,13)}
def p2fadeStep14(){doFadeStep(2,14)}
def p2fadeStep15(){doFadeStep(2,15)}
def p2fadeStep16(){doFadeStep(2,16)}
def p2fadeStep17(){doFadeStep(2,17)}
def p2fadeStep18(){doFadeStep(2,18)}
def p2fadeStep19(){doFadeStep(2,19)}
def p2fadeStep20(){doFadeStep(2,20)}
def p2fadeStep21(){doFadeStep(2,21)}
def p2fadeStep22(){doFadeStep(2,22)}
def p2fadeStep23(){doFadeStep(2,23)}
def p2fadeStep24(){doFadeStep(2,24)}
def p2fadeStep25(){doFadeStep(2,25)}
def p2fadeStep26(){doFadeStep(2,26)}
def p2fadeStep27(){doFadeStep(2,27)}
def p2fadeStep28(){doFadeStep(2,28)}
def p2fadeStep29(){doFadeStep(2,29)}
def p2fadeStep30(){doFadeStep(2,30)}
def p2fadeStep31(){doFadeStep(2,31)}
def p2fadeStep32(){doFadeStep(2,32)}
def p2fadeStep33(){doFadeStep(2,33)}
def p2fadeStep34(){doFadeStep(2,34)}
def p2fadeStep35(){doFadeStep(2,35)}
def p2fadeStep36(){doFadeStep(2,36)}
def p2fadeStep37(){doFadeStep(2,37)}
def p2fadeStep38(){doFadeStep(2,38)}
def p2fadeStep39(){doFadeStep(2,39)}
def p2fadeStep40(){doFadeStep(2,40)}
def p2fadeStep41(){doFadeStep(2,41)}
def p2fadeStep42(){doFadeStep(2,42)}
def p2fadeStep43(){doFadeStep(2,43)}
def p2fadeStep44(){doFadeStep(2,44)}
def p2fadeStep45(){doFadeStep(2,45)}
def p2fadeStep46(){doFadeStep(2,46)}
def p2fadeStep47(){doFadeStep(2,47)}
def p2fadeStep48(){doFadeStep(2,48)}
def p2fadeStep49(){doFadeStep(2,49)}

def p3fadeStep1(){doFadeStep(3,1)}
def p3fadeStep2(){doFadeStep(3,2)}
def p3fadeStep3(){doFadeStep(3,3)}
def p3fadeStep4(){doFadeStep(3,4)}
def p3fadeStep5(){doFadeStep(3,5)}
def p3fadeStep6(){doFadeStep(3,6)}
def p3fadeStep7(){doFadeStep(3,7)}
def p3fadeStep8(){doFadeStep(3,8)}
def p3fadeStep9(){doFadeStep(3,9)}
def p3fadeStep10(){doFadeStep(3,10)}
def p3fadeStep11(){doFadeStep(3,11)}
def p3fadeStep12(){doFadeStep(3,12)}
def p3fadeStep13(){doFadeStep(3,13)}
def p3fadeStep14(){doFadeStep(3,14)}
def p3fadeStep15(){doFadeStep(3,15)}
def p3fadeStep16(){doFadeStep(3,16)}
def p3fadeStep17(){doFadeStep(3,17)}
def p3fadeStep18(){doFadeStep(3,18)}
def p3fadeStep19(){doFadeStep(3,19)}
def p3fadeStep20(){doFadeStep(3,20)}
def p3fadeStep21(){doFadeStep(3,21)}
def p3fadeStep22(){doFadeStep(3,22)}
def p3fadeStep23(){doFadeStep(3,23)}
def p3fadeStep24(){doFadeStep(3,24)}
def p3fadeStep25(){doFadeStep(3,25)}
def p3fadeStep26(){doFadeStep(3,26)}
def p3fadeStep27(){doFadeStep(3,27)}
def p3fadeStep28(){doFadeStep(3,28)}
def p3fadeStep29(){doFadeStep(3,29)}
def p3fadeStep30(){doFadeStep(3,30)}
def p3fadeStep31(){doFadeStep(3,31)}
def p3fadeStep32(){doFadeStep(3,32)}
def p3fadeStep33(){doFadeStep(3,33)}
def p3fadeStep34(){doFadeStep(3,34)}
def p3fadeStep35(){doFadeStep(3,35)}
def p3fadeStep36(){doFadeStep(3,36)}
def p3fadeStep37(){doFadeStep(3,37)}
def p3fadeStep38(){doFadeStep(3,38)}
def p3fadeStep39(){doFadeStep(3,39)}
def p3fadeStep40(){doFadeStep(3,40)}
def p3fadeStep41(){doFadeStep(3,41)}
def p3fadeStep42(){doFadeStep(3,42)}
def p3fadeStep43(){doFadeStep(3,43)}
def p3fadeStep44(){doFadeStep(3,44)}
def p3fadeStep45(){doFadeStep(3,45)}
def p3fadeStep46(){doFadeStep(3,46)}
def p3fadeStep47(){doFadeStep(3,47)}
def p3fadeStep48(){doFadeStep(3,48)}
def p3fadeStep49(){doFadeStep(3,49)}

def p4fadeStep1(){doFadeStep(4,1)}
def p4fadeStep2(){doFadeStep(4,2)}
def p4fadeStep3(){doFadeStep(4,3)}
def p4fadeStep4(){doFadeStep(4,4)}
def p4fadeStep5(){doFadeStep(4,5)}
def p4fadeStep6(){doFadeStep(4,6)}
def p4fadeStep7(){doFadeStep(4,7)}
def p4fadeStep8(){doFadeStep(4,8)}
def p4fadeStep9(){doFadeStep(4,9)}
def p4fadeStep10(){doFadeStep(4,10)}
def p4fadeStep11(){doFadeStep(4,11)}
def p4fadeStep12(){doFadeStep(4,12)}
def p4fadeStep13(){doFadeStep(4,13)}
def p4fadeStep14(){doFadeStep(4,14)}
def p4fadeStep15(){doFadeStep(4,15)}
def p4fadeStep16(){doFadeStep(4,16)}
def p4fadeStep17(){doFadeStep(4,17)}
def p4fadeStep18(){doFadeStep(4,18)}
def p4fadeStep19(){doFadeStep(4,19)}
def p4fadeStep20(){doFadeStep(4,20)}
def p4fadeStep21(){doFadeStep(4,21)}
def p4fadeStep22(){doFadeStep(4,22)}
def p4fadeStep23(){doFadeStep(4,23)}
def p4fadeStep24(){doFadeStep(4,24)}
def p4fadeStep25(){doFadeStep(4,25)}
def p4fadeStep26(){doFadeStep(4,26)}
def p4fadeStep27(){doFadeStep(4,27)}
def p4fadeStep28(){doFadeStep(4,28)}
def p4fadeStep29(){doFadeStep(4,29)}
def p4fadeStep30(){doFadeStep(4,30)}
def p4fadeStep31(){doFadeStep(4,31)}
def p4fadeStep32(){doFadeStep(4,32)}
def p4fadeStep33(){doFadeStep(4,33)}
def p4fadeStep34(){doFadeStep(4,34)}
def p4fadeStep35(){doFadeStep(4,35)}
def p4fadeStep36(){doFadeStep(4,36)}
def p4fadeStep37(){doFadeStep(4,37)}
def p4fadeStep38(){doFadeStep(4,38)}
def p4fadeStep39(){doFadeStep(4,39)}
def p4fadeStep40(){doFadeStep(4,40)}
def p4fadeStep41(){doFadeStep(4,41)}
def p4fadeStep42(){doFadeStep(4,42)}
def p4fadeStep43(){doFadeStep(4,43)}
def p4fadeStep44(){doFadeStep(4,44)}
def p4fadeStep45(){doFadeStep(4,45)}
def p4fadeStep46(){doFadeStep(4,46)}
def p4fadeStep47(){doFadeStep(4,47)}
def p4fadeStep48(){doFadeStep(4,48)}
def p4fadeStep49(){doFadeStep(4,49)}

// -- Page rotation

def rotatePage() {
    int rotInt = (settings.rotationInterval ?: 0) as int
    if (rotInt <= 0) return
    // Do not rotate if any sensor is active
    if (!allInactive()) {
        infoLog "[AutoPages] Rotation paused -- sensor active"
        return
    }
    int total = (state.numberOfPages ?: 4) as int
    if (total <= 1) return
    int current = (state.rotationPage ?: 1) as int
    int next = (current >= total) ? 1 : current + 1
    state.rotationPage = next
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${next}", 1, false) }
    catch (Exception e) { infoLog "[AutoPages] Rotation publish failed: ${e.message}" }
    runIn(rotInt, "rotatePage")
}

def p5fadeStep1(){doFadeStep(5,1)}
def p5fadeStep2(){doFadeStep(5,2)}
def p5fadeStep3(){doFadeStep(5,3)}
def p5fadeStep4(){doFadeStep(5,4)}
def p5fadeStep5(){doFadeStep(5,5)}
def p5fadeStep6(){doFadeStep(5,6)}
def p5fadeStep7(){doFadeStep(5,7)}
def p5fadeStep8(){doFadeStep(5,8)}
def p5fadeStep9(){doFadeStep(5,9)}
def p5fadeStep10(){doFadeStep(5,10)}
def p5fadeStep11(){doFadeStep(5,11)}
def p5fadeStep12(){doFadeStep(5,12)}
def p5fadeStep13(){doFadeStep(5,13)}
def p5fadeStep14(){doFadeStep(5,14)}
def p5fadeStep15(){doFadeStep(5,15)}
def p5fadeStep16(){doFadeStep(5,16)}
def p5fadeStep17(){doFadeStep(5,17)}
def p5fadeStep18(){doFadeStep(5,18)}
def p5fadeStep19(){doFadeStep(5,19)}
def p5fadeStep20(){doFadeStep(5,20)}
def p5fadeStep21(){doFadeStep(5,21)}
def p5fadeStep22(){doFadeStep(5,22)}
def p5fadeStep23(){doFadeStep(5,23)}
def p5fadeStep24(){doFadeStep(5,24)}
def p5fadeStep25(){doFadeStep(5,25)}
def p5fadeStep26(){doFadeStep(5,26)}
def p5fadeStep27(){doFadeStep(5,27)}
def p5fadeStep28(){doFadeStep(5,28)}
def p5fadeStep29(){doFadeStep(5,29)}
def p5fadeStep30(){doFadeStep(5,30)}
def p5fadeStep31(){doFadeStep(5,31)}
def p5fadeStep32(){doFadeStep(5,32)}
def p5fadeStep33(){doFadeStep(5,33)}
def p5fadeStep34(){doFadeStep(5,34)}
def p5fadeStep35(){doFadeStep(5,35)}
def p5fadeStep36(){doFadeStep(5,36)}
def p5fadeStep37(){doFadeStep(5,37)}
def p5fadeStep38(){doFadeStep(5,38)}
def p5fadeStep39(){doFadeStep(5,39)}
def p5fadeStep40(){doFadeStep(5,40)}
def p5fadeStep41(){doFadeStep(5,41)}
def p5fadeStep42(){doFadeStep(5,42)}
def p5fadeStep43(){doFadeStep(5,43)}
def p5fadeStep44(){doFadeStep(5,44)}
def p5fadeStep45(){doFadeStep(5,45)}
def p5fadeStep46(){doFadeStep(5,46)}
def p5fadeStep47(){doFadeStep(5,47)}
def p5fadeStep48(){doFadeStep(5,48)}
def p5fadeStep49(){doFadeStep(5,49)}
def p6fadeStep1(){doFadeStep(6,1)}
def p6fadeStep2(){doFadeStep(6,2)}
def p6fadeStep3(){doFadeStep(6,3)}
def p6fadeStep4(){doFadeStep(6,4)}
def p6fadeStep5(){doFadeStep(6,5)}
def p6fadeStep6(){doFadeStep(6,6)}
def p6fadeStep7(){doFadeStep(6,7)}
def p6fadeStep8(){doFadeStep(6,8)}
def p6fadeStep9(){doFadeStep(6,9)}
def p6fadeStep10(){doFadeStep(6,10)}
def p6fadeStep11(){doFadeStep(6,11)}
def p6fadeStep12(){doFadeStep(6,12)}
def p6fadeStep13(){doFadeStep(6,13)}
def p6fadeStep14(){doFadeStep(6,14)}
def p6fadeStep15(){doFadeStep(6,15)}
def p6fadeStep16(){doFadeStep(6,16)}
def p6fadeStep17(){doFadeStep(6,17)}
def p6fadeStep18(){doFadeStep(6,18)}
def p6fadeStep19(){doFadeStep(6,19)}
def p6fadeStep20(){doFadeStep(6,20)}
def p6fadeStep21(){doFadeStep(6,21)}
def p6fadeStep22(){doFadeStep(6,22)}
def p6fadeStep23(){doFadeStep(6,23)}
def p6fadeStep24(){doFadeStep(6,24)}
def p6fadeStep25(){doFadeStep(6,25)}
def p6fadeStep26(){doFadeStep(6,26)}
def p6fadeStep27(){doFadeStep(6,27)}
def p6fadeStep28(){doFadeStep(6,28)}
def p6fadeStep29(){doFadeStep(6,29)}
def p6fadeStep30(){doFadeStep(6,30)}
def p6fadeStep31(){doFadeStep(6,31)}
def p6fadeStep32(){doFadeStep(6,32)}
def p6fadeStep33(){doFadeStep(6,33)}
def p6fadeStep34(){doFadeStep(6,34)}
def p6fadeStep35(){doFadeStep(6,35)}
def p6fadeStep36(){doFadeStep(6,36)}
def p6fadeStep37(){doFadeStep(6,37)}
def p6fadeStep38(){doFadeStep(6,38)}
def p6fadeStep39(){doFadeStep(6,39)}
def p6fadeStep40(){doFadeStep(6,40)}
def p6fadeStep41(){doFadeStep(6,41)}
def p6fadeStep42(){doFadeStep(6,42)}
def p6fadeStep43(){doFadeStep(6,43)}
def p6fadeStep44(){doFadeStep(6,44)}
def p6fadeStep45(){doFadeStep(6,45)}
def p6fadeStep46(){doFadeStep(6,46)}
def p6fadeStep47(){doFadeStep(6,47)}
def p6fadeStep48(){doFadeStep(6,48)}
def p6fadeStep49(){doFadeStep(6,49)}

// -- Logging --------------------------------------------------------------------
private void infoLog(String msg)  { if ((settings.logLevel ?: "1") != "0") log.info msg }
private void debugLog(String msg) { if ((settings.logLevel ?: "1") == "2") log.debug msg }
        command "setNumberOfPages", [[name:"n", type:"NUMBER"]]

        command "setPage1GridLayout", [[name:"g", type:"STRING"]]
        command "setPage2GridLayout", [[name:"g", type:"STRING"]]
        command "setPage3GridLayout", [[name:"g", type:"STRING"]]
        command "setPage4GridLayout", [[name:"g", type:"STRING"]]
        command "setPage5GridLayout", [[name:"g", type:"STRING"]]
        command "setPage6GridLayout", [[name:"g", type:"STRING"]]

        command "setPage1MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]

        command "updatePage1Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage2Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage3Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage4Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage5Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage6Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage1SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage2SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage3SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage4SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage5SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage6SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]

        attribute "mqttStatus",      "string"
        attribute "displayRebooted",    "string"
        attribute "layoutPushComplete", "string"
        attribute "lightTapped",        "string"
        (1..6).each { pg ->
            attribute "page${pg}GridLayout", "string"
        }
    }

    preferences {
        input name: "mqttBroker",   type: "text",     title: "<b>MQTT Broker</b> (host:port)", required: true, defaultValue: "tcp://127.0.0.1:1883"
        input name: "mqttPassword", type: "password", title: "MQTT Password", required: true,
              description: "Found in Hubitat -> Integrations -> MQTT Broker"
        input name: "mqttClientId", type: "text",     title: "MQTT Client ID", required: true, defaultValue: "hubitat-sensecap-autopages"
        input name: "haspNode",     type: "text",     title: "<b>openHASP Node Name</b>", required: true, defaultValue: "plate"

        input name: "colorActive",          type: "enum", title: "<b>Active color</b>",     options: activeColorOptions(),   defaultValue: "#FF0000", required: true
        input name: "colorInactive",        type: "enum", title: "Inactive -- Motion",        options: colorOptions(), defaultValue: "#008000", required: true
        input name: "colorContactInactive", type: "enum", title: "Inactive -- Contact",       options: colorOptions(), defaultValue: "#00FFFF", required: true
        input name: "colorWaterInactive",   type: "enum", title: "Inactive -- Water",         options: colorOptions(), defaultValue: "#0000FF", required: true
        input name: "colorSmokeInactive",   type: "enum", title: "Inactive -- Smoke",         options: colorOptions(), defaultValue: "#FFFF00", required: true
        input name: "colorLightInactive",   type: "enum", title: "Inactive -- Light off",    options: colorOptions(), defaultValue: "#808080", required: true
        input name: "colorLightActive",     type: "enum", title: "Active -- Light on",      options: colorOptions(), defaultValue: "#FFFF00", required: true

        input name: "fadeDuration",      type: "number", title: "Fade duration (seconds)", defaultValue: 30, required: true
        input name: "showPageIndicator",  type: "bool",   title: "Show page indicator (e.g. 1/4)", defaultValue: true
        input name: "rotationInterval",   type: "number", title: "Auto-scroll pages every (seconds, 0 = off)", defaultValue: 10

        input name: "backlightOnMotion",      type: "bool",   title: "<b>Backlight ON</b> when sensor active",            defaultValue: true
        input name: "backlightOffDelay",      type: "number", title: "Backlight OFF after all clear (seconds, 0=never)",   defaultValue: 0
        input name: "motionBacklightTimeout", type: "number", title: "Backlight OFF after active for (minutes, 0=never)",  defaultValue: 1
        input name: "touchBacklightTimeout",  type: "number", title: "Backlight OFF after screen tap (seconds, 0=never)",  defaultValue: 30

        input name: "logLevel", type: "enum", title: "Logging Level",
              options: ["0":"None","1":"Info only","2":"Info + Debug"], defaultValue: "1", required: true
    }
}

private Map activeColorOptions() {
    ["#FF0000":"Red","#FF4500":"Orange-red","#FF8C00":"Dark orange","#FF1493":"Deep pink",
     "#8B0000":"Dark red","#FF6347":"Tomato","#DC143C":"Crimson","#FF0080":"Hot magenta"]
}
private Map colorOptions() {
    ["#F8F8FF":"Ghost White","#D3D3D3":"Light Gray","#808080":"Gray","#800000":"Maroon",
     "#FF00FF":"Magenta","#800080":"Purple","#0000FF":"Blue","#000080":"Navy","#00FFFF":"Cyan",
     "#008080":"Teal","#00FF00":"Lime","#008000":"Green","#FFFF00":"Yellow","#808000":"Olive"]
}

// -- Object ID helpers ----------------------------------------------------------
private int bgId(int slot)   { slot }
private int iconId(int slot) { slot + 50 }

// -- State key helpers ----------------------------------------------------------
private String stateKey(int page, int idx) { "p${page}sensor${idx}" }
private String typeKey(int page, int idx)  { "p${page}slotType${idx}" }
private String labelKey(int page, int idx) { "p${page}label${idx}" }

// -- Lifecycle ------------------------------------------------------------------

def installed() {
    infoLog "[AutoPages] Driver installed"
    initialize()
}

def updated() {
    infoLog "[AutoPages] Preferences updated"
    initialize()
}

def initialize() {
    String mqttSt = device.currentValue("mqttStatus") ?: ""
    if (!mqttSt.startsWith("Connected")) {
        connectMqtt()
    } else {
        infoLog "[AutoPages] MQTT already connected -- skipping reconnect"
    }
    unschedule("sendHeartbeat")
    runEvery5Minutes("sendHeartbeat")
}

def uninstalled() { disconnectMqtt() }

// -- Grid config ----------------------------------------------------------------

def setNumberOfPages(n) {
    int num = Math.min(4, Math.max(1, (n as int)))
    state.numberOfPages = num
    infoLog "[AutoPages] Number of pages set to ${num}"
}

def setPage1GridLayout(String g) {
    state.page1GridLayout = g
    switch (g) {
        case "1x1": state.page1MaxSlots = 1;  break
        case "3x3": state.page1MaxSlots = 9;  break
        case "4x4": state.page1MaxSlots = 16; break
        case "5x5": state.page1MaxSlots = 25; break
        case "6x6": state.page1MaxSlots = 36; break
        case "7x7": state.page1MaxSlots = 49; break
        default:    state.page1MaxSlots = 4
    }
    sendEvent(name: "page1GridLayout", value: g)
    infoLog "[AutoPages] Page 1 grid -> ${g}"
    (1..49).each { s -> state.remove("p1label${s}") }
}

def setPage2GridLayout(String g) {
    state.page2GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page2MaxSlots = 1;  break
        case "3x3": state.page2MaxSlots = 9;  break
        case "4x4": state.page2MaxSlots = 16; break
        case "5x5": state.page2MaxSlots = 25; break
        case "6x6": state.page2MaxSlots = 36; break
        case "7x7": state.page2MaxSlots = 49; break
        default:    state.page2MaxSlots = 4
    }
    sendEvent(name: "page2GridLayout", value: g)
    infoLog "[AutoPages] Page 2 grid -> ${g}"
    (1..49).each { s -> state.remove("p2label${s}") }
}

def setPage3GridLayout(String g) {
    state.page3GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page3MaxSlots = 1;  break
        case "3x3": state.page3MaxSlots = 9;  break
        case "4x4": state.page3MaxSlots = 16; break
        case "5x5": state.page3MaxSlots = 25; break
        case "6x6": state.page3MaxSlots = 36; break
        case "7x7": state.page3MaxSlots = 49; break
        default:    state.page3MaxSlots = 4
    }
    sendEvent(name: "page3GridLayout", value: g)
    infoLog "[AutoPages] Page 3 grid -> ${g}"
    (1..49).each { s -> state.remove("p3label${s}") }
}

def setPage4GridLayout(String g) {
    state.page4GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page4MaxSlots = 1;  break
        case "3x3": state.page4MaxSlots = 9;  break
        case "4x4": state.page4MaxSlots = 16; break
        case "5x5": state.page4MaxSlots = 25; break
        case "6x6": state.page4MaxSlots = 36; break
        case "7x7": state.page4MaxSlots = 49; break
        default:    state.page4MaxSlots = 4
    }
    sendEvent(name: "page4GridLayout", value: g)
    infoLog "[AutoPages] Page 4 grid -> ${g}"
    (1..49).each { s -> state.remove("p4label${s}") }
}
def setPage5GridLayout(String g) {
    state.page5GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page5MaxSlots = 1;  break
        case "3x3": state.page5MaxSlots = 9;  break
        case "4x4": state.page5MaxSlots = 16; break
        case "5x5": state.page5MaxSlots = 25; break
        case "6x6": state.page5MaxSlots = 36; break
        case "7x7": state.page5MaxSlots = 49; break
        default:    state.page5MaxSlots = 4
    }
    sendEvent(name: "page5GridLayout", value: g)
    infoLog "[AutoPages] Page 5 grid -> ${g}"
    (1..49).each { s -> state.remove("p5label${s}") }
}
def setPage6GridLayout(String g) {
    state.page6GridLayout = g
    // Also store maxSlots so maxSensors() never depends on string parse timing
    switch (g) {
        case "1x1": state.page6MaxSlots = 1;  break
        case "3x3": state.page6MaxSlots = 9;  break
        case "4x4": state.page6MaxSlots = 16; break
        case "5x5": state.page6MaxSlots = 25; break
        case "6x6": state.page6MaxSlots = 36; break
        case "7x7": state.page6MaxSlots = 49; break
        default:    state.page6MaxSlots = 4
    }
    sendEvent(name: "page6GridLayout", value: g)
    infoLog "[AutoPages] Page 6 grid -> ${g}"
    (1..49).each { s -> state.remove("p6label${s}") }
}

private String activeGrid(int page) {
    return (state["page${page}GridLayout"] ?: "2x2") as String
}

private int maxSensors(int page) {
    // Use stored maxSlots if available (set by setPageXGridLayout) for reliability
    int stored = (state["page${page}MaxSlots"] ?: 0) as int
    if (stored > 0) return stored
    switch (activeGrid(page)) {
        case "1x1": return 1;  case "3x3": return 9;   case "4x4": return 16
        case "5x5": return 25; case "6x6": return 36;  case "7x7": return 49
        default:    return 4
    }
}

// -- MQTT -----------------------------------------------------------------------

def connectMqtt() {
    if (!settings.mqttPassword) { infoLog "[AutoPages] MQTT password not set"; return }
    try {
        String broker   = settings.mqttBroker   ?: "tcp://127.0.0.1:1883"
        String clientId = settings.mqttClientId ?: "hubitat-sensecap-autopages-${device.id}"
        interfaces.mqtt.connect(broker, clientId, "hubitat", settings.mqttPassword)
        infoLog "[AutoPages] MQTT connected -> ${broker}"
        sendEvent(name: "mqttStatus", value: "Connected")
        String node = settings.haspNode ?: "plate"
        interfaces.mqtt.subscribe("hasp/${node}/state/statusupdate")
        interfaces.mqtt.subscribe("hasp/${node}/state/idle")
        interfaces.mqtt.subscribe("hasp/${node}/idle")
        interfaces.mqtt.subscribe("hasp/${node}/state/backlight")
        interfaces.mqtt.subscribe("hasp/${node}/backlight")
        interfaces.mqtt.subscribe("hasp/+/LWT")
        interfaces.mqtt.subscribe("hasp/+/state/statusupdate")
        interfaces.mqtt.subscribe("hasp/+/state/+")
        infoLog "[AutoPages] Subscribed -- node: ${node}"
    } catch (Exception e) {
        infoLog "[AutoPages] ERROR -- MQTT connect failed: ${e.message}"
        sendEvent(name: "mqttStatus", value: "Error: ${e.message}")
        runIn(30, "connectMqtt")
    }
}

def disconnectMqtt() {
    try { interfaces.mqtt.disconnect() } catch (Exception e) { }
    sendEvent(name: "mqttStatus", value: "Disconnected")
}

def reconnectMqtt() { disconnectMqtt(); pauseExecution(1000); connectMqtt() }

def mqttClientStatus(String status) {
    infoLog "[AutoPages] MQTT status: ${status}"
    sendEvent(name: "mqttStatus", value: status)
    if (status.startsWith("Error") || status.contains("lost")) runIn(30, "connectMqtt")
}

def sendHeartbeat() {
    state.lastHeartbeatMs = now()
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command", "statusupdate", 1, false) }
    catch (Exception e) { infoLog "[AutoPages] Heartbeat failed -- reconnecting"; reconnectMqtt() }
}

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    debugLog "MQTT: topic=${msg.topic} payload=${msg.payload}"

    if (msg.topic.endsWith("/LWT")) {
        String actualNode = msg.topic.split("/")[1]
        String configNode = settings.haspNode ?: "plate"
        if (actualNode != configNode) {
            log.warn "[AutoPages] Node name mismatch! Device is '${actualNode}' but preference is '${configNode}'"
            sendEvent(name: "mqttStatus", value: "Wrong node name -- should be '${actualNode}'")
        }
        if (msg.payload?.trim() == "online") {
            infoLog "[AutoPages] LWT online (${actualNode}) -- display rebooted, pushing all layouts"
            runIn(5, "fireDisplayRebooted")
        }
        return
    }

    if (msg.topic.contains("statusupdate")) {
        if (!msg.payload?.trim()) return
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.uptime == null) return
            int uptime = (json.uptime) as int
            if (uptime < 30) {
                infoLog "[AutoPages] Display rebooted (uptime ${uptime}s)"
                runIn(5, "fireDisplayRebooted")
            } else {
                infoLog "[AutoPages] Display woke from idle -- resyncing"
                runIn(2, "resyncStates")
                startBacklightTimer()
            }
        } catch (Exception e) { infoLog "[AutoPages] WARN -- Could not parse statusupdate: ${e.message}" }
        return
    }

    if (msg.topic.contains("state/idle") || msg.topic.endsWith("/idle")) {
        String v = msg.payload?.trim()
        if (v == "short" || v == "long") {
            state.screenIdle = true
        } else if (v == "off") {
            long ms = now() - (state.lastHeartbeatMs ?: 0L)
            if (ms >= 3000) { state.screenIdle = false; infoLog "[AutoPages] Screen woke from touch"; startBacklightTimer() }
        }
        return
    }

    if (msg.topic.contains("state/backlight") || msg.topic.endsWith("/backlight")) {
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.state == "off")     { state.screenIdle = true }
            else if (json.state == "on" && state.screenIdle) { state.screenIdle = false; startBacklightTimer() }
        } catch (Exception e) { if (msg.payload?.trim() == "off") state.screenIdle = true }
        return
    }

    // Button tap events: topic = hasp/{node}/state/pXbY, payload = {"event":"down"} or {"event":"up"}
    String cfgNode = settings.haspNode ?: "plate"
    if (msg.topic.contains("/state/p") && msg.topic.contains("b") && msg.topic.contains(cfgNode)) {
        debugLog "[AutoPages] Button topic: ${msg.topic} payload: ${msg.payload}"
        handleButtonTap(msg.topic, msg.payload)
        return
    }
}

// -- Button tap handler --------------------------------------------------------

private void handleButtonTap(String topic, String payload) {
    if (!payload?.contains('"up"')) return
    def matcher = topic =~ /state\/p(\d+)b(\d+)$/
    if (!matcher) return
    int page  = matcher[0][1] as int
    int btnId = matcher[0][2] as int
    if (btnId < 1 || btnId > 49) return
    int slot  = btnId
    String sType = state[typeKey(page, slot)] ?: "none"
    if (sType != "light") return
    debugLog "[AutoPages] Light tile tapped: page ${page} slot ${slot}"
    sendEvent(name: "lightTapped", value: "${page},${slot},${now()}")
}

// -- Backlight ------------------------------------------------------------------

private void startBacklightTimer() {
    if (!settings.backlightOnMotion) return
    unschedule("backlightOff")
    if (!allInactive()) {
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
    } else {
        int delay = (settings.touchBacklightTimeout ?: 30) as int
        if (delay > 0) runIn(delay, "backlightOff")
    }
}

def backlightOff() { publishBacklight(false); state.screenIdle = true }

def backlightOnAfterFade() {
    if (!settings.backlightOnMotion || !allInactive()) return
    state.screenIdle = false; publishBacklight(true)
    int delay = (settings.backlightOffDelay ?: 0) as int
    if (delay > 0) runIn(delay, "backlightOff")
}

def motionTimeoutBacklightOff() {
    if (!settings.backlightOnMotion) return
    if (!allInactive()) { backlightOff() }
}

private boolean allInactive() {
    int numPg = (state.numberOfPages ?: 6) as int
    (1..numPg).every { pg ->
        (1..maxSensors(pg)).every { idx ->
            String sType = state[typeKey(pg, idx)] ?: "none"
            // Lights being on should not count as "active" for rotation/backlight purposes
            if (sType == "light") return true
            return state[stateKey(pg, idx)] != "active"
        }
    }
}

private void publishBacklight(boolean on) {
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/backlight", on ? '{"state":"on","brightness":255}' : '{"state":"off"}', 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- Backlight publish failed: ${e.message}" }
}

// -- Resync ---------------------------------------------------------------------

def resyncStates() {
    infoLog "[AutoPages] Resyncing all page states"
    int numPg = (state.numberOfPages ?: 6) as int
    (1..numPg).each { pg ->
        (1..maxSensors(pg)).each { idx ->
            String st = state[stateKey(pg, idx)] ?: "inactive"
            if (st == "empty")       { setSlotEmptyForPage(pg, idx) }
            else if (st == "active") { setMotionActiveForPage(pg, idx) }
            else {
                publishColor(pg, idx, inactiveColorFor(pg, idx))
                publishTextColor(pg, idx, inactiveColorFor(pg, idx))
                publishIcon(pg, idx, inactiveIconFor(pg, idx))
            }
            pauseExecution(30)
        }
    }
}

def fireDisplayRebooted() {
    sendEvent(name: "displayRebooted", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

// -- Layout push -------------------------------------------------------------

private int pageRenderSeconds(int page) {
    // Estimate render time per page:
    // Layout objects: (slots x 2) x 50ms
    // Slot render: slots x 160ms (color + icon + label + pauses)
    // Light tile recreation: slots x 70ms extra (worst case all lights)
    // Pauses + buffer: 3s
    int slots = maxSensors(page)
    int ms = (slots * 2 * 50) + (slots * 160) + (slots * 70) + 3000
    return Math.ceil(ms / 1000.0) as int
}

def pushAllLayouts(numberOfPages) {
    int np = Math.min(6, Math.max(1, (numberOfPages as int)))
    state.numberOfPages = np
    state.lastPushMs    = now()
    infoLog "[AutoPages] pushAllLayouts -- ${np} page(s)"
    sendEvent(name: "mqttStatus", value: "Building layouts...")

    // Clear ALL pages first so stale objects from prior sessions are gone
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command", "clearpage 0", 1, false); pauseExecution(500) }
    catch (Exception e) { infoLog "[AutoPages] WARN -- clearpage 0 failed: ${e.message}" }

    // Fixed 15-second gaps between pages -- covers worst case 5x5 light page (25 slots x ~400ms each = 10s + buffer)
    runIn(2,  "pushPage1Layout")
    if (np >= 2) runIn(17, "pushPage2Layout")
    if (np >= 3) runIn(32, "pushPage3Layout")
    if (np >= 4) runIn(47, "pushPage4Layout")
    if (np >= 5) runIn(62, "pushPage5Layout")
    if (np >= 6) runIn(77, "pushPage6Layout")
}

def pushPage1Layout() {
    publishBacklight(true)
    pushPageLayout(1)
}

def pushPage2Layout() {
    int np2 = (state.numberOfPages ?: 6) as int
    if (np2 >= 2) pushPageLayout(2)
}

def pushPage3Layout() {
    int np3 = (state.numberOfPages ?: 6) as int
    if (np3 >= 3) pushPageLayout(3)
}

def pushPage4Layout() {
    int np4 = (state.numberOfPages ?: 6) as int
    if (np4 >= 4) pushPageLayout(4)
}
def pushPage5Layout() {
    int np5 = (state.numberOfPages ?: 6) as int
    if (np5 >= 5) pushPageLayout(5)
}
def pushPage6Layout() {
    int np6 = (state.numberOfPages ?: 6) as int
    if (np6 >= 6) pushPageLayout(6)
}

private void pushPageLayout(int page) {
    String grid  = activeGrid(page)
    int total    = (state.numberOfPages ?: 4) as int
    String node  = settings.haspNode ?: "plate"

    infoLog "[AutoPages] Pushing page ${page}/${total}: ${grid}"
    sendEvent(name: "mqttStatus", value: "Pushing page ${page}/${total}...")

    // Clear this page in background (user still sees previous page)
    try { interfaces.mqtt.publish("hasp/${node}/command", "clearpage ${page}", 1, false); pauseExecution(200) }
    catch (Exception e) { }

    // Cancel any in-progress fades for this page before re-rendering
    (1..maxSensors(page)).each { s ->
        unschedule("p${page}fadeStep${s}")
        state.remove("p${page}fadeStep${s}")
    }

    // 1. Push tile layout (btns + icon overlays + nav buttons + page indicator)
    layoutJsonl(grid, page, total).each { jsonl ->
        try { interfaces.mqtt.publish("hasp/${node}/command/jsonl", jsonl, 1, false); pauseExecution(50) }
        catch (Exception e) { infoLog "[AutoPages] ERROR -- layout push p${page}: ${e.message}" }
    }

    // 2. Push colors, icons and labels for every slot immediately after layout
    pauseExecution(300)
    (1..maxSensors(page)).each { idx ->
        String slotType = state[typeKey(page, idx)] ?: "none"
        if (!slotType || slotType == "none") {
            publishColor(page, idx, "#708090")
            publishTextColor(page, idx, "#708090")
            publishIcon(page, idx, "")
        } else if (slotType == "light") {
            // For lights use actual current state -- avoids wrong color before syncAllSensors runs
            String lightState = state[stateKey(page, idx)] ?: "inactive"
            if (lightState == "active") {
                String lc = settings.colorLightActive ?: "#FFFF00"
                publishColor(page, idx, lc)
                publishTextColor(page, idx, lc)
            } else {
                String ic = inactiveColorFor(page, idx)
                publishColor(page, idx, ic)
                publishTextColor(page, idx, ic)
            }
            publishIcon(page, idx, ICON_LIGHTBULB)
            pauseExecution(20)
            // Re-define the btn object with click:true so openHASP registers taps
            String clickJsonl = buildLightTileJsonl(page, idx, grid)
            if (clickJsonl) {
                try { interfaces.mqtt.publish("hasp/${node}/command/jsonl", clickJsonl, 1, false) }
                catch (Exception e) { infoLog "[AutoPages] WARN -- light click enable failed: ${e.message}" }
            }
        } else {
            String ic = inactiveColorFor(page, idx)
            publishColor(page, idx, ic)
            publishTextColor(page, idx, ic)
            publishIcon(page, idx, inactiveIconFor(page, idx))
        }
        String lbl = state[labelKey(page, idx)] ?: ""
        if (lbl) {
            publishJsonl(node, page, bgId(idx), [text: lbl])
            pauseExecution(30)
        }
        pauseExecution(30)
    }

    // Navigate to this page now that it is fully rendered
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${page}", 1, false); pauseExecution(200) }
    catch (Exception e) { }

    if (page == total) {
        pauseExecution(5000)
        try { interfaces.mqtt.publish("hasp/${node}/command/page", "1", 1, false) } catch (Exception e) { }
        sendEvent(name: "mqttStatus", value: "Connected")
        infoLog "[AutoPages] All ${total} page(s) pushed"
        sendEvent(name: "layoutPushComplete", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        // Start auto-rotation
        if (total > 1) {
            int rotInt = (settings.rotationInterval ?: 0) as int
            if (rotInt > 0) {
                state.rotationPage = 1
                unschedule("rotatePage")
                runIn(rotInt, "rotatePage")
            }
        }
    }
}

// -- Layout JSONL generators ----------------------------------------------------

private List<String> layoutJsonl(String grid, int page, int totalPages) {
    List<String> out
    switch (grid) {
        case "1x1": out = layout1x1(page); break
        case "3x3": out = layout3x3(page); break
        case "4x4": out = layout4x4(page); break
        case "5x5": out = layoutNxN(page, 5, 94, 2, 12, 2, 24); break
        case "6x6": out = layoutNxN(page, 6, 78, 2, 12, 2, 12); break
        case "7x7": out = layoutNxN(page, 7, 67, 1, 12, 1, 12); break
        default:    out = layout2x2(page)
    }

    if (totalPages > 1) {
        int prevPage = (page == 1) ? totalPages : page - 1
        int nextPage = (page == totalPages) ? 1 : page + 1
        // Full-height invisible edge tap zones -- 40px wide, full 480px height, ~8% opacity
        out << """{"page":${page},"id":201,"obj":"btn","x":0,"y":0,"w":30,"h":480,"bg_color":"#000000","bg_opa":20,"border_width":0,"radius":0,"text":"","text_font":8,"toggle":false,"action":"p${prevPage}"}"""
        out << """{"page":${page},"id":202,"obj":"btn","x":450,"y":0,"w":30,"h":480,"bg_color":"#000000","bg_opa":20,"border_width":0,"radius":0,"text":"","text_font":8,"toggle":false,"action":"p${nextPage}"}"""
        if (settings.showPageIndicator == true) {
            out << """{"page":${page},"id":200,"obj":"label","x":424,"y":4,"w":54,"h":22,"bg_color":"#000000","bg_opa":180,"border_width":0,"radius":4,"text":"${page}/${totalPages}","text_font":16,"text_color":"white","align":"center","click":false}"""
        } else {
            out << """{"page":${page},"id":200,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        }
    } else {
        // Single page -- erase any stale nav objects from prior layouts
        out << """{"page":${page},"id":200,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        out << """{"page":${page},"id":201,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        out << """{"page":${page},"id":202,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
    }
    return out
}

private List<String> layout1x1(int page) {[
    """{"page":${page},"id":1,"obj":"btn","x":2,"y":2,"w":476,"h":476,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false}""",
    """{"page":${page},"id":51,"obj":"label","parentid":0,"x":8,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
]}

private List<String> layout2x2(int page) {
    List<String> out = []
    [[1,2,2,236,236],[2,242,2,236,236],[3,2,242,236,236],[4,242,242,236,236]].each { r ->
        out << """{"page":${page},"id":${r[0]},"obj":"btn","x":${r[1]},"y":${r[2]},"w":${r[3]},"h":${r[4]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }
    [[51,8,8],[52,248,8],[53,8,248],[54,248,248]].each { r ->
        out << """{"page":${page},"id":${r[0]},"obj":"label","parentid":0,"x":${r[1]},"y":${r[2]},"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
    }
    return out
}

private List<String> layout3x3(int page) {
    List<String> out = []
    int[][] cells = [[2,2,157,157],[161,2,157,157],[320,2,158,157],[2,161,157,157],[161,161,157,157],[320,161,158,157],[2,320,157,158],[161,320,157,158],[320,320,158,158]]
    cells.eachWithIndex { c, i ->
        out << """{"page":${page},"id":${i+1},"obj":"btn","x":${c[0]},"y":${c[1]},"w":${c[2]},"h":${c[3]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }
    int[][] icons = [[8,8],[167,8],[326,8],[8,167],[167,167],[326,167],[8,326],[167,326],[326,326]]
    icons.eachWithIndex { c, i ->
        out << """{"page":${page},"id":${i+51},"obj":"label","parentid":0,"x":${c[0]},"y":${c[1]},"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
    }
    return out
}

private List<String> layout4x4(int page) {
    List<String> out = []; int cols = 4; int w = 117; int gap = 2
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }}
    int id2 = 51
    (0..<cols).each { row -> (0..<cols).each { col ->
        int x = col*(w+gap)+gap+4; int y = row*(w+gap)+gap+4
        out << """{"page":${page},"id":${id2},"obj":"label","parentid":0,"x":${x},"y":${y},"w":24,"h":24,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
        id2++
    }}
    return out
}

private List<String> layoutNxN(int page, int cols, int w, int gap, int tf, int iconOff, int iconFont) {
    List<String> out = []
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":${tf},"align":"center","text_color":"black","toggle":false,"click":false}"""
    }}
    int id2 = 51
    (0..<cols).each { row -> (0..<cols).each { col ->
        int x = col*(w+gap)+gap+iconOff; int y = row*(w+gap)+gap+iconOff
        out << """{"page":${page},"id":${id2},"obj":"label","parentid":0,"x":${x},"y":${y},"w":${iconFont},"h":${iconFont},"bg_opa":0,"border_width":0,"text":"","text_font":${iconFont},"text_color":"black","click":false}"""
        id2++
    }}
    return out
}

// -- Page commands --------------------------------------------------------------

def setPage1MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(1)) setMotionActiveForPage(1,i) }
def setPage1MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(1)) setMotionInactiveForPage(1,i) }
def setPage1SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(1)) setSlotEmptyForPage(1,i) }
def setPage2MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(2)) setMotionActiveForPage(2,i) }
def setPage2MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(2)) setMotionInactiveForPage(2,i) }
def setPage2SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(2)) setSlotEmptyForPage(2,i) }
def setPage3MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(3)) setMotionActiveForPage(3,i) }
def setPage3MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(3)) setMotionInactiveForPage(3,i) }
def setPage3SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(3)) setSlotEmptyForPage(3,i) }
def setPage4MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(4)) setMotionActiveForPage(4,i) }
def setPage4MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(4)) setMotionInactiveForPage(4,i) }
def setPage4SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(4)) setSlotEmptyForPage(4,i) }
def setPage5MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(5)) setMotionActiveForPage(5,i) }
def setPage5MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(5)) setMotionInactiveForPage(5,i) }
def setPage5SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(5)) setSlotEmptyForPage(5,i) }
def setPage6MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(6)) setMotionActiveForPage(6,i) }
def setPage6MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(6)) setMotionInactiveForPage(6,i) }
def setPage6SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(6)) setSlotEmptyForPage(6,i) }

private void setMotionActiveForPage(int page, int idx) {
    String sk = stateKey(page, idx)
    state[sk] = "active"
    unschedule("p${page}fadeStep${idx}")
    state.remove("p${page}fadeStep${idx}")
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    if (sType == "light") {
        // Lights: update tile color/icon only -- no page nav, no backlight, no rotation stop
        String lc = settings.colorLightActive ?: "#FFFF00"
        publishColor(page, idx, lc)
        publishTextColor(page, idx, lc)
        publishIcon(page, idx, ICON_LIGHTBULB)
        return
    }
    String ac = settings.colorActive ?: "#FF0000"
    publishColor(page, idx, ac)
    publishTextColor(page, idx, ac)
    publishIcon(page, idx, activeIconFor(page, idx))
    // Navigate display to the page containing the active sensor and stop rotation
    unschedule("rotatePage")
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${page}", 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- page nav on active failed: ${e.message}" }
    if (settings.backlightOnMotion) {
        unschedule("backlightOff")
        unschedule("motionTimeoutBacklightOff")
        state.screenIdle = false
        publishBacklight(true)
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
    }
}

private void setMotionInactiveForPage(int page, int idx) {
    String sk      = stateKey(page, idx)
    String fadeKey = "p${page}fadeStep${idx}"
    boolean wasActive = (state[sk] == "active")
    state[sk] = "inactive"
    // Lights skip fade -- just snap directly to inactive color
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    if (sType == "light") {
        unschedule(fadeKey); state.remove(fadeKey)
        String ic = inactiveColorFor(page, idx)
        publishColor(page, idx, ic)
        publishTextColor(page, idx, ic)
        publishIcon(page, idx, ICON_LIGHTBULB)
        return
    }
    if (wasActive) {
        unschedule(fadeKey); state[fadeKey] = 0
        publishIcon(page, idx, inactiveIconFor(page, idx))
        scheduleFadeStep(page, idx)
        if (settings.backlightOnMotion) {
            unschedule("motionTimeoutBacklightOff")
            if (!allInactive()) {
                int mins = (settings.motionBacklightTimeout ?: 1) as int
                if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
            } else {
                runIn((FADE_STEPS + 1) * fadeInterval() + 2, "backlightOnAfterFade")
            }
        }
    } else {
        String ic = inactiveColorFor(page, idx)
        publishColor(page, idx, ic)
        publishTextColor(page, idx, ic)
        publishIcon(page, idx, inactiveIconFor(page, idx))
        if (settings.backlightOnMotion && allInactive()) {
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) { unschedule("backlightOff"); runIn(delay, "backlightOff") }
        }
    }
}

private void setSlotEmptyForPage(int page, int idx) {
    String sk = stateKey(page, idx); String fadeKey = "p${page}fadeStep${idx}"
    state[sk] = "empty"
    unschedule(fadeKey); state.remove(fadeKey)
    publishColor(page, idx, "#708090")
    publishTextColor(page, idx, "#708090")
    publishIcon(page, idx, "")
    String node = settings.haspNode ?: "plate"
    publishJsonl(node, page, bgId(idx),   [text: ""])
    publishJsonl(node, page, iconId(idx), [text: ""])
}

// -- Label / type updates -------------------------------------------------------

def updatePage1Labels(labels)    { applyLabels(labels, 1) }
def updatePage2Labels(labels)    { applyLabels(labels, 2) }
def updatePage3Labels(labels)    { applyLabels(labels, 3) }
def updatePage4Labels(labels)    { applyLabels(labels, 4) }
def updatePage5Labels(labels)    { applyLabels(labels, 5) }
def updatePage6Labels(labels)    { applyLabels(labels, 6) }
def updatePage1SlotTypes(types)  { applySlotTypes(types, 1) }
def updatePage2SlotTypes(types)  { applySlotTypes(types, 2) }
def updatePage3SlotTypes(types)  { applySlotTypes(types, 3) }
def updatePage4SlotTypes(types)  { applySlotTypes(types, 4) }
def updatePage5SlotTypes(types)  { applySlotTypes(types, 5) }
def updatePage6SlotTypes(types)  { applySlotTypes(types, 6) }

private void applyLabels(labels, int page) {
    if (!(labels instanceof Map)) {
        try { labels = new groovy.json.JsonSlurper().parseText(labels.toString()) }
        catch (Exception e) { infoLog "[AutoPages] WARN -- bad labels JSON: ${e.message}"; return }
    }
    // Store labels in state only -- publishing happens during pushPageLayout
    labels.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > 49) return
        String lbl = v?.toString() ?: ""
        state[labelKey(page, idx)] = lbl
    }
    infoLog "[AutoPages] Labels stored for page ${page}: ${labels.size()} entries"
}

private void applySlotTypes(slotTypes, int page) {
    if (!(slotTypes instanceof Map)) {
        try { slotTypes = new groovy.json.JsonSlurper().parseText(slotTypes.toString()) }
        catch (Exception e) { infoLog "[AutoPages] WARN -- bad slotTypes JSON: ${e.message}"; return }
    }
    Map typeCounts = [:]
    slotTypes.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > 49) return
        String t = (v?.toString() ?: "none")
        state[typeKey(page, idx)] = t
        if (t != "none") typeCounts[t] = ((typeCounts[t] ?: 0) as int) + 1
    }
    // Store dominant type as page-level fallback so inactiveIconFor/ColorFor always work
    if (typeCounts) {
        String dominant = typeCounts.max { it.value }.key
        state["pageType${page}"] = dominant
        infoLog "[AutoPages] Page ${page} type: ${dominant}"
    }
}

// -- Light tile helper -------------------------------------------------------

private String buildLightTileJsonl(int page, int slot, String grid) {
    // Build the x,y,w,h for this slot based on grid layout
    // This mirrors the layout methods but for a single slot
    int col0, row0, tw, th
    switch (grid) {
        case "1x1":
            col0=0; row0=0; tw=476; th=476; break
        case "2x2":
            int[][] c2 = [[2,2,236,236],[242,2,236,236],[2,242,236,236],[242,242,236,236]]
            if (slot<1||slot>4) return null
            col0=c2[slot-1][0]; row0=c2[slot-1][1]; tw=c2[slot-1][2]; th=c2[slot-1][3]; break
        default:
            // For NxN grids calculate position
            int n
            switch (grid) {
                case "3x3": n=3; break; case "4x4": n=4; break; case "5x5": n=5; break
                case "6x6": n=6; break; case "7x7": n=7; break; default: n=2
            }
            int w = (grid=="3x3")?157:(grid=="4x4")?117:(grid=="5x5")?94:(grid=="6x6")?78:67
            int gap = (grid=="7x7")?1:2
            int r = (slot-1).intdiv(n); int c = (slot-1)%n
            col0 = c*(w+gap)+gap; row0 = r*(w+gap)+gap
            tw = (c==n-1)?(480-col0-gap):w
            th = (r==n-1)?(480-row0-gap):w
    }
    int tf = (grid=="3x3"||grid=="2x2"||grid=="1x1")?24:((grid=="4x4")?16:12)
    return """{"page":${page},"id":${slot},"obj":"btn","x":${col0},"y":${row0},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":2,"radius":8,"text":"","text_font":${tf},"align":"center","text_color":"black","toggle":false,"click":true}"""
}

// -- MQTT publish helpers -------------------------------------------------------

private void publishColor(int page, int slot, String hex) {
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/p${page}b${bgId(slot)}.bg_color", hex, 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- Color publish failed: ${e.message}" }
}

private void publishTextColor(int page, int slot, String bgHex) {
    String node  = settings.haspNode ?: "plate"
    String color = textColorFor(bgHex)
    String lbl   = state[labelKey(page, slot)] ?: ""
    if (lbl) {
        publishJsonl(node, page, bgId(slot), [text_color: color, text: lbl])
    } else {
        publishJsonl(node, page, bgId(slot), [text_color: color])
    }
    if (useLetterIcon(page)) publishJsonl(node, page, iconId(slot), [text_color: color])
}

private void publishIcon(int page, int slot, String glyph) {
    String node  = settings.haspNode ?: "plate"
    int fontPt   = useLetterIcon(page) ? 12 : 24
    publishJsonl(node, page, iconId(slot), [text: glyph, text_font: fontPt])
}

private void publishJsonl(String node, int page, int objId, Map props) {
    String json  = groovy.json.JsonOutput.toJson(props)
    String topic = "hasp/${node}/command/p${page}b${objId}.jsonl"
    try { interfaces.mqtt.publish(topic, json, 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- JSONL publish failed: ${e.message}" }
}

// -- Color / icon helpers -------------------------------------------------------

private String inactiveColorFor(int page, int idx) {
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    switch (sType) {
        case "contact": return settings.colorContactInactive ?: "#00FFFF"
        case "water":   return settings.colorWaterInactive   ?: "#0000FF"
        case "smoke":   return settings.colorSmokeInactive   ?: "#FFFF00"
        case "light":   return settings.colorLightInactive   ?: "#808080"
        default:        return settings.colorInactive        ?: "#008000"
    }
}

private String textColorFor(String hex) {
    String h = hex.replace("#", "")
    int r = Integer.parseInt(h[0..1], 16)
    int g = Integer.parseInt(h[2..3], 16)
    int b = Integer.parseInt(h[4..5], 16)
    return (0.2126*(r/255.0) + 0.7152*(g/255.0) + 0.0722*(b/255.0)) > 0.35 ? "black" : "white"
}

@Field static final String ICON_ALERT   = "\uE026"
@Field static final String ICON_MOTION  = "\uE70E"
@Field static final String ICON_CONTACT = "\uE2DC"
@Field static final String ICON_WATER   = "\uE58C"
@Field static final String ICON_SMOKE      = "\uE238"
@Field static final String ICON_LIGHTBULB  = "\uE335"

private boolean useLetterIcon(int page) { activeGrid(page) in ["6x6", "7x7"] }

private String activeIconFor(int page, int idx) {
    if (useLetterIcon(page)) return letterIconFor(page, idx)
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    if (sType == "light") return ICON_LIGHTBULB
    return ICON_ALERT
}

private String inactiveIconFor(int page, int idx) {
    if (useLetterIcon(page)) return letterIconFor(page, idx)
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    switch (sType) {
        case "contact": return ICON_CONTACT
        case "water":   return ICON_WATER
        case "smoke":   return ICON_SMOKE
        case "light":   return ICON_LIGHTBULB
        default:        return ICON_MOTION
    }
}

private String letterIconFor(int page, int idx) {
    switch (state[typeKey(page, idx)] ?: "motion") {
        case "contact": return "C"; case "water": return "W"
        case "smoke":   return "S"; case "none":  return ""; case "light": return "L"
        default:        return "M"
    }
}

// -- Fade -----------------------------------------------------------------------

@Field static final int FADE_STEPS = 6

private int fadeInterval() {
    Math.max(1, Math.round(((settings.fadeDuration ?: 30) as int) / FADE_STEPS) as int)
}

private void scheduleFadeStep(int page, int idx) {
    runIn(fadeInterval(), "p${page}fadeStep${idx}")
}

private void doFadeStep(int page, int idx) {
    String sk      = stateKey(page, idx)
    String fadeKey = "p${page}fadeStep${idx}"
    if (state[sk] == "active") return
    int step   = (state[fadeKey] ?: 0) as int
    double t   = step / (FADE_STEPS as double)
    String fromHex = (settings.colorActive ?: "#FF0000").replace("#", "")
    String toHex   = inactiveColorFor(page, idx).replace("#", "")
    int fR = Integer.parseInt(fromHex[0..1], 16); int fG = Integer.parseInt(fromHex[2..3], 16); int fB = Integer.parseInt(fromHex[4..5], 16)
    int tR = Integer.parseInt(toHex[0..1], 16);   int tG = Integer.parseInt(toHex[2..3], 16);   int tB = Integer.parseInt(toHex[4..5], 16)
    int r = Math.max(0, Math.min(255, Math.round(fR+(tR-fR)*t) as int))
    int g = Math.max(0, Math.min(255, Math.round(fG+(tG-fG)*t) as int))
    int b = Math.max(0, Math.min(255, Math.round(fB+(tB-fB)*t) as int))
    publishColor(page, idx, sprintf("#%02X%02X%02X", r, g, b))
    if (step < FADE_STEPS) {
        state[fadeKey] = step + 1; scheduleFadeStep(page, idx)
    } else {
        state.remove(fadeKey)
        String snap = inactiveColorFor(page, idx)
        publishColor(page, idx, snap); publishTextColor(page, idx, snap)
        if (settings.backlightOnMotion && allInactive() && !state.screenIdle) {
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) { unschedule("backlightOff"); runIn(delay, "backlightOff") }
        }
        // Resume page rotation now that all sensors are clear
        if (allInactive()) {
            int rotInt = (settings.rotationInterval ?: 0) as int
            if (rotInt > 0) {
                unschedule("rotatePage")
                runIn(rotInt, "rotatePage")
            }
        }
    }
}

// Fade step methods -- 4 pages x 49 slots
def p1fadeStep1(){doFadeStep(1,1)}
def p1fadeStep2(){doFadeStep(1,2)}
def p1fadeStep3(){doFadeStep(1,3)}
def p1fadeStep4(){doFadeStep(1,4)}
def p1fadeStep5(){doFadeStep(1,5)}
def p1fadeStep6(){doFadeStep(1,6)}
def p1fadeStep7(){doFadeStep(1,7)}
def p1fadeStep8(){doFadeStep(1,8)}
def p1fadeStep9(){doFadeStep(1,9)}
def p1fadeStep10(){doFadeStep(1,10)}
def p1fadeStep11(){doFadeStep(1,11)}
def p1fadeStep12(){doFadeStep(1,12)}
def p1fadeStep13(){doFadeStep(1,13)}
def p1fadeStep14(){doFadeStep(1,14)}
def p1fadeStep15(){doFadeStep(1,15)}
def p1fadeStep16(){doFadeStep(1,16)}
def p1fadeStep17(){doFadeStep(1,17)}
def p1fadeStep18(){doFadeStep(1,18)}
def p1fadeStep19(){doFadeStep(1,19)}
def p1fadeStep20(){doFadeStep(1,20)}
def p1fadeStep21(){doFadeStep(1,21)}
def p1fadeStep22(){doFadeStep(1,22)}
def p1fadeStep23(){doFadeStep(1,23)}
def p1fadeStep24(){doFadeStep(1,24)}
def p1fadeStep25(){doFadeStep(1,25)}
def p1fadeStep26(){doFadeStep(1,26)}
def p1fadeStep27(){doFadeStep(1,27)}
def p1fadeStep28(){doFadeStep(1,28)}
def p1fadeStep29(){doFadeStep(1,29)}
def p1fadeStep30(){doFadeStep(1,30)}
def p1fadeStep31(){doFadeStep(1,31)}
def p1fadeStep32(){doFadeStep(1,32)}
def p1fadeStep33(){doFadeStep(1,33)}
def p1fadeStep34(){doFadeStep(1,34)}
def p1fadeStep35(){doFadeStep(1,35)}
def p1fadeStep36(){doFadeStep(1,36)}
def p1fadeStep37(){doFadeStep(1,37)}
def p1fadeStep38(){doFadeStep(1,38)}
def p1fadeStep39(){doFadeStep(1,39)}
def p1fadeStep40(){doFadeStep(1,40)}
def p1fadeStep41(){doFadeStep(1,41)}
def p1fadeStep42(){doFadeStep(1,42)}
def p1fadeStep43(){doFadeStep(1,43)}
def p1fadeStep44(){doFadeStep(1,44)}
def p1fadeStep45(){doFadeStep(1,45)}
def p1fadeStep46(){doFadeStep(1,46)}
def p1fadeStep47(){doFadeStep(1,47)}
def p1fadeStep48(){doFadeStep(1,48)}
def p1fadeStep49(){doFadeStep(1,49)}

def p2fadeStep1(){doFadeStep(2,1)}
def p2fadeStep2(){doFadeStep(2,2)}
def p2fadeStep3(){doFadeStep(2,3)}
def p2fadeStep4(){doFadeStep(2,4)}
def p2fadeStep5(){doFadeStep(2,5)}
def p2fadeStep6(){doFadeStep(2,6)}
def p2fadeStep7(){doFadeStep(2,7)}
def p2fadeStep8(){doFadeStep(2,8)}
def p2fadeStep9(){doFadeStep(2,9)}
def p2fadeStep10(){doFadeStep(2,10)}
def p2fadeStep11(){doFadeStep(2,11)}
def p2fadeStep12(){doFadeStep(2,12)}
def p2fadeStep13(){doFadeStep(2,13)}
def p2fadeStep14(){doFadeStep(2,14)}
def p2fadeStep15(){doFadeStep(2,15)}
def p2fadeStep16(){doFadeStep(2,16)}
def p2fadeStep17(){doFadeStep(2,17)}
def p2fadeStep18(){doFadeStep(2,18)}
def p2fadeStep19(){doFadeStep(2,19)}
def p2fadeStep20(){doFadeStep(2,20)}
def p2fadeStep21(){doFadeStep(2,21)}
def p2fadeStep22(){doFadeStep(2,22)}
def p2fadeStep23(){doFadeStep(2,23)}
def p2fadeStep24(){doFadeStep(2,24)}
def p2fadeStep25(){doFadeStep(2,25)}
def p2fadeStep26(){doFadeStep(2,26)}
def p2fadeStep27(){doFadeStep(2,27)}
def p2fadeStep28(){doFadeStep(2,28)}
def p2fadeStep29(){doFadeStep(2,29)}
def p2fadeStep30(){doFadeStep(2,30)}
def p2fadeStep31(){doFadeStep(2,31)}
def p2fadeStep32(){doFadeStep(2,32)}
def p2fadeStep33(){doFadeStep(2,33)}
def p2fadeStep34(){doFadeStep(2,34)}
def p2fadeStep35(){doFadeStep(2,35)}
def p2fadeStep36(){doFadeStep(2,36)}
def p2fadeStep37(){doFadeStep(2,37)}
def p2fadeStep38(){doFadeStep(2,38)}
def p2fadeStep39(){doFadeStep(2,39)}
def p2fadeStep40(){doFadeStep(2,40)}
def p2fadeStep41(){doFadeStep(2,41)}
def p2fadeStep42(){doFadeStep(2,42)}
def p2fadeStep43(){doFadeStep(2,43)}
def p2fadeStep44(){doFadeStep(2,44)}
def p2fadeStep45(){doFadeStep(2,45)}
def p2fadeStep46(){doFadeStep(2,46)}
def p2fadeStep47(){doFadeStep(2,47)}
def p2fadeStep48(){doFadeStep(2,48)}
def p2fadeStep49(){doFadeStep(2,49)}

def p3fadeStep1(){doFadeStep(3,1)}
def p3fadeStep2(){doFadeStep(3,2)}
def p3fadeStep3(){doFadeStep(3,3)}
def p3fadeStep4(){doFadeStep(3,4)}
def p3fadeStep5(){doFadeStep(3,5)}
def p3fadeStep6(){doFadeStep(3,6)}
def p3fadeStep7(){doFadeStep(3,7)}
def p3fadeStep8(){doFadeStep(3,8)}
def p3fadeStep9(){doFadeStep(3,9)}
def p3fadeStep10(){doFadeStep(3,10)}
def p3fadeStep11(){doFadeStep(3,11)}
def p3fadeStep12(){doFadeStep(3,12)}
def p3fadeStep13(){doFadeStep(3,13)}
def p3fadeStep14(){doFadeStep(3,14)}
def p3fadeStep15(){doFadeStep(3,15)}
def p3fadeStep16(){doFadeStep(3,16)}
def p3fadeStep17(){doFadeStep(3,17)}
def p3fadeStep18(){doFadeStep(3,18)}
def p3fadeStep19(){doFadeStep(3,19)}
def p3fadeStep20(){doFadeStep(3,20)}
def p3fadeStep21(){doFadeStep(3,21)}
def p3fadeStep22(){doFadeStep(3,22)}
def p3fadeStep23(){doFadeStep(3,23)}
def p3fadeStep24(){doFadeStep(3,24)}
def p3fadeStep25(){doFadeStep(3,25)}
def p3fadeStep26(){doFadeStep(3,26)}
def p3fadeStep27(){doFadeStep(3,27)}
def p3fadeStep28(){doFadeStep(3,28)}
def p3fadeStep29(){doFadeStep(3,29)}
def p3fadeStep30(){doFadeStep(3,30)}
def p3fadeStep31(){doFadeStep(3,31)}
def p3fadeStep32(){doFadeStep(3,32)}
def p3fadeStep33(){doFadeStep(3,33)}
def p3fadeStep34(){doFadeStep(3,34)}
def p3fadeStep35(){doFadeStep(3,35)}
def p3fadeStep36(){doFadeStep(3,36)}
def p3fadeStep37(){doFadeStep(3,37)}
def p3fadeStep38(){doFadeStep(3,38)}
def p3fadeStep39(){doFadeStep(3,39)}
def p3fadeStep40(){doFadeStep(3,40)}
def p3fadeStep41(){doFadeStep(3,41)}
def p3fadeStep42(){doFadeStep(3,42)}
def p3fadeStep43(){doFadeStep(3,43)}
def p3fadeStep44(){doFadeStep(3,44)}
def p3fadeStep45(){doFadeStep(3,45)}
def p3fadeStep46(){doFadeStep(3,46)}
def p3fadeStep47(){doFadeStep(3,47)}
def p3fadeStep48(){doFadeStep(3,48)}
def p3fadeStep49(){doFadeStep(3,49)}

def p4fadeStep1(){doFadeStep(4,1)}
def p4fadeStep2(){doFadeStep(4,2)}
def p4fadeStep3(){doFadeStep(4,3)}
def p4fadeStep4(){doFadeStep(4,4)}
def p4fadeStep5(){doFadeStep(4,5)}
def p4fadeStep6(){doFadeStep(4,6)}
def p4fadeStep7(){doFadeStep(4,7)}
def p4fadeStep8(){doFadeStep(4,8)}
def p4fadeStep9(){doFadeStep(4,9)}
def p4fadeStep10(){doFadeStep(4,10)}
def p4fadeStep11(){doFadeStep(4,11)}
def p4fadeStep12(){doFadeStep(4,12)}
def p4fadeStep13(){doFadeStep(4,13)}
def p4fadeStep14(){doFadeStep(4,14)}
def p4fadeStep15(){doFadeStep(4,15)}
def p4fadeStep16(){doFadeStep(4,16)}
def p4fadeStep17(){doFadeStep(4,17)}
def p4fadeStep18(){doFadeStep(4,18)}
def p4fadeStep19(){doFadeStep(4,19)}
def p4fadeStep20(){doFadeStep(4,20)}
def p4fadeStep21(){doFadeStep(4,21)}
def p4fadeStep22(){doFadeStep(4,22)}
def p4fadeStep23(){doFadeStep(4,23)}
def p4fadeStep24(){doFadeStep(4,24)}
def p4fadeStep25(){doFadeStep(4,25)}
def p4fadeStep26(){doFadeStep(4,26)}
def p4fadeStep27(){doFadeStep(4,27)}
def p4fadeStep28(){doFadeStep(4,28)}
def p4fadeStep29(){doFadeStep(4,29)}
def p4fadeStep30(){doFadeStep(4,30)}
def p4fadeStep31(){doFadeStep(4,31)}
def p4fadeStep32(){doFadeStep(4,32)}
def p4fadeStep33(){doFadeStep(4,33)}
def p4fadeStep34(){doFadeStep(4,34)}
def p4fadeStep35(){doFadeStep(4,35)}
def p4fadeStep36(){doFadeStep(4,36)}
def p4fadeStep37(){doFadeStep(4,37)}
def p4fadeStep38(){doFadeStep(4,38)}
def p4fadeStep39(){doFadeStep(4,39)}
def p4fadeStep40(){doFadeStep(4,40)}
def p4fadeStep41(){doFadeStep(4,41)}
def p4fadeStep42(){doFadeStep(4,42)}
def p4fadeStep43(){doFadeStep(4,43)}
def p4fadeStep44(){doFadeStep(4,44)}
def p4fadeStep45(){doFadeStep(4,45)}
def p4fadeStep46(){doFadeStep(4,46)}
def p4fadeStep47(){doFadeStep(4,47)}
def p4fadeStep48(){doFadeStep(4,48)}
def p4fadeStep49(){doFadeStep(4,49)}

// -- Page rotation

def rotatePage() {
    int rotInt = (settings.rotationInterval ?: 0) as int
    if (rotInt <= 0) return
    // Do not rotate if any sensor is active
    if (!allInactive()) {
        infoLog "[AutoPages] Rotation paused -- sensor active"
        return
    }
    int total = (state.numberOfPages ?: 4) as int
    if (total <= 1) return
    int current = (state.rotationPage ?: 1) as int
    int next = (current >= total) ? 1 : current + 1
    state.rotationPage = next
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${next}", 1, false) }
    catch (Exception e) { infoLog "[AutoPages] Rotation publish failed: ${e.message}" }
    runIn(rotInt, "rotatePage")
}

def p5fadeStep1(){doFadeStep(5,1)}
def p5fadeStep2(){doFadeStep(5,2)}
def p5fadeStep3(){doFadeStep(5,3)}
def p5fadeStep4(){doFadeStep(5,4)}
def p5fadeStep5(){doFadeStep(5,5)}
def p5fadeStep6(){doFadeStep(5,6)}
def p5fadeStep7(){doFadeStep(5,7)}
def p5fadeStep8(){doFadeStep(5,8)}
def p5fadeStep9(){doFadeStep(5,9)}
def p5fadeStep10(){doFadeStep(5,10)}
def p5fadeStep11(){doFadeStep(5,11)}
def p5fadeStep12(){doFadeStep(5,12)}
def p5fadeStep13(){doFadeStep(5,13)}
def p5fadeStep14(){doFadeStep(5,14)}
def p5fadeStep15(){doFadeStep(5,15)}
def p5fadeStep16(){doFadeStep(5,16)}
def p5fadeStep17(){doFadeStep(5,17)}
def p5fadeStep18(){doFadeStep(5,18)}
def p5fadeStep19(){doFadeStep(5,19)}
def p5fadeStep20(){doFadeStep(5,20)}
def p5fadeStep21(){doFadeStep(5,21)}
def p5fadeStep22(){doFadeStep(5,22)}
def p5fadeStep23(){doFadeStep(5,23)}
def p5fadeStep24(){doFadeStep(5,24)}
def p5fadeStep25(){doFadeStep(5,25)}
def p5fadeStep26(){doFadeStep(5,26)}
def p5fadeStep27(){doFadeStep(5,27)}
def p5fadeStep28(){doFadeStep(5,28)}
def p5fadeStep29(){doFadeStep(5,29)}
def p5fadeStep30(){doFadeStep(5,30)}
def p5fadeStep31(){doFadeStep(5,31)}
def p5fadeStep32(){doFadeStep(5,32)}
def p5fadeStep33(){doFadeStep(5,33)}
def p5fadeStep34(){doFadeStep(5,34)}
def p5fadeStep35(){doFadeStep(5,35)}
def p5fadeStep36(){doFadeStep(5,36)}
def p5fadeStep37(){doFadeStep(5,37)}
def p5fadeStep38(){doFadeStep(5,38)}
def p5fadeStep39(){doFadeStep(5,39)}
def p5fadeStep40(){doFadeStep(5,40)}
def p5fadeStep41(){doFadeStep(5,41)}
def p5fadeStep42(){doFadeStep(5,42)}
def p5fadeStep43(){doFadeStep(5,43)}
def p5fadeStep44(){doFadeStep(5,44)}
def p5fadeStep45(){doFadeStep(5,45)}
def p5fadeStep46(){doFadeStep(5,46)}
def p5fadeStep47(){doFadeStep(5,47)}
def p5fadeStep48(){doFadeStep(5,48)}
def p5fadeStep49(){doFadeStep(5,49)}
def p6fadeStep1(){doFadeStep(6,1)}
def p6fadeStep2(){doFadeStep(6,2)}
def p6fadeStep3(){doFadeStep(6,3)}
def p6fadeStep4(){doFadeStep(6,4)}
def p6fadeStep5(){doFadeStep(6,5)}
def p6fadeStep6(){doFadeStep(6,6)}
def p6fadeStep7(){doFadeStep(6,7)}
def p6fadeStep8(){doFadeStep(6,8)}
def p6fadeStep9(){doFadeStep(6,9)}
def p6fadeStep10(){doFadeStep(6,10)}
def p6fadeStep11(){doFadeStep(6,11)}
def p6fadeStep12(){doFadeStep(6,12)}
def p6fadeStep13(){doFadeStep(6,13)}
def p6fadeStep14(){doFadeStep(6,14)}
def p6fadeStep15(){doFadeStep(6,15)}
def p6fadeStep16(){doFadeStep(6,16)}
def p6fadeStep17(){doFadeStep(6,17)}
def p6fadeStep18(){doFadeStep(6,18)}
def p6fadeStep19(){doFadeStep(6,19)}
def p6fadeStep20(){doFadeStep(6,20)}
def p6fadeStep21(){doFadeStep(6,21)}
def p6fadeStep22(){doFadeStep(6,22)}
def p6fadeStep23(){doFadeStep(6,23)}
def p6fadeStep24(){doFadeStep(6,24)}
def p6fadeStep25(){doFadeStep(6,25)}
def p6fadeStep26(){doFadeStep(6,26)}
def p6fadeStep27(){doFadeStep(6,27)}
def p6fadeStep28(){doFadeStep(6,28)}
def p6fadeStep29(){doFadeStep(6,29)}
def p6fadeStep30(){doFadeStep(6,30)}
def p6fadeStep31(){doFadeStep(6,31)}
def p6fadeStep32(){doFadeStep(6,32)}
def p6fadeStep33(){doFadeStep(6,33)}
def p6fadeStep34(){doFadeStep(6,34)}
def p6fadeStep35(){doFadeStep(6,35)}
def p6fadeStep36(){doFadeStep(6,36)}
def p6fadeStep37(){doFadeStep(6,37)}
def p6fadeStep38(){doFadeStep(6,38)}
def p6fadeStep39(){doFadeStep(6,39)}
def p6fadeStep40(){doFadeStep(6,40)}
def p6fadeStep41(){doFadeStep(6,41)}
def p6fadeStep42(){doFadeStep(6,42)}
def p6fadeStep43(){doFadeStep(6,43)}
def p6fadeStep44(){doFadeStep(6,44)}
def p6fadeStep45(){doFadeStep(6,45)}
def p6fadeStep46(){doFadeStep(6,46)}
def p6fadeStep47(){doFadeStep(6,47)}
def p6fadeStep48(){doFadeStep(6,48)}
def p6fadeStep49(){doFadeStep(6,49)}

// -- Logging --------------------------------------------------------------------
private void infoLog(String msg)  { if ((settings.logLevel ?: "1") != "0") log.info msg }
private void debugLog(String msg) { if ((settings.logLevel ?: "1") == "2") log.debug msg }
        command "setNumberOfPages", [[name:"n", type:"NUMBER"]]

        command "setPage1GridLayout", [[name:"g", type:"STRING"]]
        command "setPage2GridLayout", [[name:"g", type:"STRING"]]
        command "setPage3GridLayout", [[name:"g", type:"STRING"]]
        command "setPage4GridLayout", [[name:"g", type:"STRING"]]
        command "setPage5GridLayout", [[name:"g", type:"STRING"]]
        command "setPage6GridLayout", [[name:"g", type:"STRING"]]

        command "setPage1MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage6SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]

        command "updatePage1Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage2Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage3Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage4Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage5Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage6Labels",    [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage1SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage2SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage3SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage4SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage5SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage6SlotTypes", [[name:"slotTypes", type:"JSON_OBJECT"]]

        attribute "mqttStatus",      "string"
        attribute "displayRebooted",    "string"
        attribute "layoutPushComplete", "string"
        (1..6).each { pg ->
            attribute "page${pg}GridLayout", "string"
        }
    }

    preferences {
        input name: "mqttBroker",   type: "text",     title: "<b>MQTT Broker</b> (host:port)", required: true, defaultValue: "tcp://127.0.0.1:1883"
        input name: "mqttPassword", type: "password", title: "MQTT Password", required: true,
              description: "Found in Hubitat -> Integrations -> MQTT Broker"
        input name: "mqttClientId", type: "text",     title: "MQTT Client ID", required: true, defaultValue: "hubitat-sensecap-autopages"
        input name: "haspNode",     type: "text",     title: "<b>openHASP Node Name</b>", required: true, defaultValue: "plate"

        input name: "colorActive",          type: "enum", title: "<b>Active color</b>",     options: activeColorOptions(),   defaultValue: "#FF0000", required: true
        input name: "colorInactive",        type: "enum", title: "Inactive -- Motion",        options: colorOptions(), defaultValue: "#008000", required: true
        input name: "colorContactInactive", type: "enum", title: "Inactive -- Contact",       options: colorOptions(), defaultValue: "#00FFFF", required: true
        input name: "colorWaterInactive",   type: "enum", title: "Inactive -- Water",         options: colorOptions(), defaultValue: "#0000FF", required: true
        input name: "colorSmokeInactive",   type: "enum", title: "Inactive -- Smoke",         options: colorOptions(), defaultValue: "#FFFF00", required: true

        input name: "fadeDuration",      type: "number", title: "Fade duration (seconds)", defaultValue: 30, required: true
        input name: "showPageIndicator",  type: "bool",   title: "Show page indicator (e.g. 1/4)", defaultValue: true
        input name: "rotationInterval",   type: "number", title: "Auto-scroll pages every (seconds, 0 = off)", defaultValue: 10

        input name: "backlightOnMotion",      type: "bool",   title: "<b>Backlight ON</b> when sensor active",            defaultValue: true
        input name: "backlightOffDelay",      type: "number", title: "Backlight OFF after all clear (seconds, 0=never)",   defaultValue: 0
        input name: "motionBacklightTimeout", type: "number", title: "Backlight OFF after active for (minutes, 0=never)",  defaultValue: 1
        input name: "touchBacklightTimeout",  type: "number", title: "Backlight OFF after screen tap (seconds, 0=never)",  defaultValue: 30

        input name: "logLevel", type: "enum", title: "Logging Level",
              options: ["0":"None","1":"Info only","2":"Info + Debug"], defaultValue: "1", required: true
    }
}

private Map activeColorOptions() {
    ["#FF0000":"Red","#FF4500":"Orange-red","#FF8C00":"Dark orange","#FF1493":"Deep pink",
     "#8B0000":"Dark red","#FF6347":"Tomato","#DC143C":"Crimson","#FF0080":"Hot magenta"]
}
private Map colorOptions() {
    ["#F8F8FF":"Ghost White","#D3D3D3":"Light Gray","#808080":"Gray","#800000":"Maroon",
     "#FF00FF":"Magenta","#800080":"Purple","#0000FF":"Blue","#000080":"Navy","#00FFFF":"Cyan",
     "#008080":"Teal","#00FF00":"Lime","#008000":"Green","#FFFF00":"Yellow","#808000":"Olive"]
}

// -- Object ID helpers ----------------------------------------------------------
private int bgId(int slot)   { slot }
private int iconId(int slot) { slot + 50 }

// -- State key helpers ----------------------------------------------------------
private String stateKey(int page, int idx) { "p${page}sensor${idx}" }
private String typeKey(int page, int idx)  { "p${page}slotType${idx}" }
private String labelKey(int page, int idx) { "p${page}label${idx}" }

// -- Lifecycle ------------------------------------------------------------------

def installed() {
    infoLog "[AutoPages] Driver installed"
    initialize()
}

def updated() {
    infoLog "[AutoPages] Preferences updated"
    initialize()
}

def initialize() {
    String mqttSt = device.currentValue("mqttStatus") ?: ""
    if (!mqttSt.startsWith("Connected")) {
        connectMqtt()
    } else {
        infoLog "[AutoPages] MQTT already connected -- skipping reconnect"
    }
    unschedule("sendHeartbeat")
    runEvery5Minutes("sendHeartbeat")
}

def uninstalled() { disconnectMqtt() }

// -- Grid config ----------------------------------------------------------------

def setNumberOfPages(n) {
    int num = Math.min(4, Math.max(1, (n as int)))
    state.numberOfPages = num
    infoLog "[AutoPages] Number of pages set to ${num}"
}

def setPage1GridLayout(String g) {
    state.page1GridLayout = g
    sendEvent(name: "page1GridLayout", value: g)
    infoLog "[AutoPages] Page 1 grid -> ${g}"
    // Clear all label state for this page so stale labels from prior layout never resurface
    (1..49).each { s -> state.remove("p1label${s}") }
}

def setPage2GridLayout(String g) {
    state.page2GridLayout = g
    sendEvent(name: "page2GridLayout", value: g)
    infoLog "[AutoPages] Page 2 grid -> ${g}"
    (1..49).each { s -> state.remove("p2label${s}") }
}

def setPage3GridLayout(String g) {
    state.page3GridLayout = g
    sendEvent(name: "page3GridLayout", value: g)
    infoLog "[AutoPages] Page 3 grid -> ${g}"
    (1..49).each { s -> state.remove("p3label${s}") }
}

def setPage4GridLayout(String g) {
    state.page4GridLayout = g
    sendEvent(name: "page4GridLayout", value: g)
    infoLog "[AutoPages] Page 4 grid -> ${g}"
    (1..49).each { s -> state.remove("p4label${s}") }
}
def setPage5GridLayout(String g) {
    state.page5GridLayout = g
    sendEvent(name: "page5GridLayout", value: g)
    infoLog "[AutoPages] Page 5 grid -> ${g}"
    (1..49).each { s -> state.remove("p5label${s}") }
}
def setPage6GridLayout(String g) {
    state.page6GridLayout = g
    sendEvent(name: "page6GridLayout", value: g)
    infoLog "[AutoPages] Page 6 grid -> ${g}"
    (1..49).each { s -> state.remove("p6label${s}") }
}

private String activeGrid(int page) {
    return (state["page${page}GridLayout"] ?: "2x2") as String
}

private int maxSensors(int page) {
    switch (activeGrid(page)) {
        case "1x1": return 1;  case "3x3": return 9;   case "4x4": return 16
        case "5x5": return 25; case "6x6": return 36;  case "7x7": return 49
        default:    return 4
    }
}

// -- MQTT -----------------------------------------------------------------------

def connectMqtt() {
    if (!settings.mqttPassword) { infoLog "[AutoPages] MQTT password not set"; return }
    try {
        String broker   = settings.mqttBroker   ?: "tcp://127.0.0.1:1883"
        String clientId = settings.mqttClientId ?: "hubitat-sensecap-autopages-${device.id}"
        interfaces.mqtt.connect(broker, clientId, "hubitat", settings.mqttPassword)
        infoLog "[AutoPages] MQTT connected -> ${broker}"
        sendEvent(name: "mqttStatus", value: "Connected")
        String node = settings.haspNode ?: "plate"
        interfaces.mqtt.subscribe("hasp/${node}/state/statusupdate")
        interfaces.mqtt.subscribe("hasp/${node}/state/idle")
        interfaces.mqtt.subscribe("hasp/${node}/idle")
        interfaces.mqtt.subscribe("hasp/${node}/state/backlight")
        interfaces.mqtt.subscribe("hasp/${node}/backlight")
        interfaces.mqtt.subscribe("hasp/+/LWT")
        interfaces.mqtt.subscribe("hasp/+/state/statusupdate")
        infoLog "[AutoPages] Subscribed -- node: ${node}"
    } catch (Exception e) {
        infoLog "[AutoPages] ERROR -- MQTT connect failed: ${e.message}"
        sendEvent(name: "mqttStatus", value: "Error: ${e.message}")
        runIn(30, "connectMqtt")
    }
}

def disconnectMqtt() {
    try { interfaces.mqtt.disconnect() } catch (Exception e) { }
    sendEvent(name: "mqttStatus", value: "Disconnected")
}

def reconnectMqtt() { disconnectMqtt(); pauseExecution(1000); connectMqtt() }

def mqttClientStatus(String status) {
    infoLog "[AutoPages] MQTT status: ${status}"
    sendEvent(name: "mqttStatus", value: status)
    if (status.startsWith("Error") || status.contains("lost")) runIn(30, "connectMqtt")
}

def sendHeartbeat() {
    state.lastHeartbeatMs = now()
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command", "statusupdate", 1, false) }
    catch (Exception e) { infoLog "[AutoPages] Heartbeat failed -- reconnecting"; reconnectMqtt() }
}

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    debugLog "MQTT: topic=${msg.topic} payload=${msg.payload}"

    if (msg.topic.endsWith("/LWT")) {
        String actualNode = msg.topic.split("/")[1]
        String configNode = settings.haspNode ?: "plate"
        if (actualNode != configNode) {
            log.warn "[AutoPages] Node name mismatch! Device is '${actualNode}' but preference is '${configNode}'"
            sendEvent(name: "mqttStatus", value: "Wrong node name -- should be '${actualNode}'")
        }
        if (msg.payload?.trim() == "online") {
            infoLog "[AutoPages] LWT online (${actualNode}) -- display rebooted, pushing all layouts"
            runIn(5, "fireDisplayRebooted")
        }
        return
    }

    if (msg.topic.contains("statusupdate")) {
        if (!msg.payload?.trim()) return
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.uptime == null) return
            int uptime = (json.uptime) as int
            if (uptime < 30) {
                infoLog "[AutoPages] Display rebooted (uptime ${uptime}s)"
                runIn(5, "fireDisplayRebooted")
            } else {
                infoLog "[AutoPages] Display woke from idle -- resyncing"
                runIn(2, "resyncStates")
                startBacklightTimer()
            }
        } catch (Exception e) { infoLog "[AutoPages] WARN -- Could not parse statusupdate: ${e.message}" }
        return
    }

    if (msg.topic.contains("state/idle") || msg.topic.endsWith("/idle")) {
        String v = msg.payload?.trim()
        if (v == "short" || v == "long") {
            state.screenIdle = true
        } else if (v == "off") {
            long ms = now() - (state.lastHeartbeatMs ?: 0L)
            if (ms >= 3000) { state.screenIdle = false; infoLog "[AutoPages] Screen woke from touch"; startBacklightTimer() }
        }
        return
    }

    if (msg.topic.contains("state/backlight") || msg.topic.endsWith("/backlight")) {
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.state == "off")     { state.screenIdle = true }
            else if (json.state == "on" && state.screenIdle) { state.screenIdle = false; startBacklightTimer() }
        } catch (Exception e) { if (msg.payload?.trim() == "off") state.screenIdle = true }
    }
}

// -- Backlight ------------------------------------------------------------------

private void startBacklightTimer() {
    if (!settings.backlightOnMotion) return
    unschedule("backlightOff")
    if (!allInactive()) {
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
    } else {
        int delay = (settings.touchBacklightTimeout ?: 30) as int
        if (delay > 0) runIn(delay, "backlightOff")
    }
}

def backlightOff() { publishBacklight(false); state.screenIdle = true }

def backlightOnAfterFade() {
    if (!settings.backlightOnMotion || !allInactive()) return
    state.screenIdle = false; publishBacklight(true)
    int delay = (settings.backlightOffDelay ?: 0) as int
    if (delay > 0) runIn(delay, "backlightOff")
}

def motionTimeoutBacklightOff() {
    if (!settings.backlightOnMotion) return
    if (!allInactive()) { backlightOff() }
}

private boolean allInactive() {
    int numPg = (state.numberOfPages ?: 6) as int
    (1..numPg).every { pg -> (1..maxSensors(pg)).every { state[stateKey(pg, it)] != "active" } }
}

private void publishBacklight(boolean on) {
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/backlight", on ? '{"state":"on","brightness":255}' : '{"state":"off"}', 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- Backlight publish failed: ${e.message}" }
}

// -- Resync ---------------------------------------------------------------------

def resyncStates() {
    infoLog "[AutoPages] Resyncing all page states"
    int numPg = (state.numberOfPages ?: 6) as int
    (1..numPg).each { pg ->
        (1..maxSensors(pg)).each { idx ->
            String st = state[stateKey(pg, idx)] ?: "inactive"
            if (st == "empty")       { setSlotEmptyForPage(pg, idx) }
            else if (st == "active") { setMotionActiveForPage(pg, idx) }
            else {
                publishColor(pg, idx, inactiveColorFor(pg, idx))
                publishTextColor(pg, idx, inactiveColorFor(pg, idx))
                publishIcon(pg, idx, inactiveIconFor(pg, idx))
            }
            pauseExecution(30)
        }
    }
}

def fireDisplayRebooted() {
    sendEvent(name: "displayRebooted", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

// -- Layout push -------------------------------------------------------------

private int pageRenderSeconds(int page) {
    // Estimate render time in seconds for a page based on its grid size.
    // Formula: (2 x slots x 50ms) + 300ms + (slots x 60ms) rounded up + 2s buffer
    int slots = maxSensors(page)
    int ms = (2 * slots * 50) + 300 + (slots * 60) + 2000
    return Math.ceil(ms / 1000.0) as int
}

def pushAllLayouts(numberOfPages) {
    int np = Math.min(6, Math.max(1, (numberOfPages as int)))
    state.numberOfPages = np
    state.lastPushMs    = now()
    infoLog "[AutoPages] pushAllLayouts -- ${np} page(s)"
    sendEvent(name: "mqttStatus", value: "Building layouts...")

    // Clear ALL pages first so stale objects from prior sessions are gone
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command", "clearpage 0", 1, false); pauseExecution(500) }
    catch (Exception e) { infoLog "[AutoPages] WARN -- clearpage 0 failed: ${e.message}" }

    // Schedule all pages via runIn so each fires after the previous one fully completes.
    // 10s per page covers worst case 7x7 grid (72 objects x 50ms + 36 slots x 120ms = ~8s).
    // Schedule each page with a gap based on the worst-case render time of the previous page.
    // Render time per page: (2 x gridN^2 objects x 50ms) + 300ms + (gridN^2 slots x 60ms)
    // 1x1=0.5s 2x2=1s 3x3=2s 4x4=4s 5x5=7s 6x6=10s 7x7=13s
    // Use pageRenderSeconds() to calculate gap dynamically.
    int gap1 = pageRenderSeconds(1)
    int gap2 = gap1 + pageRenderSeconds(2)
    int gap3 = gap2 + pageRenderSeconds(3)
    int gap4 = gap3 + pageRenderSeconds(4)
    int gap5 = gap4 + pageRenderSeconds(5)
    runIn(2, "pushPage1Layout")
    if (np >= 2) runIn(2 + gap1, "pushPage2Layout")
    if (np >= 3) runIn(2 + gap2, "pushPage3Layout")
    if (np >= 4) runIn(2 + gap3, "pushPage4Layout")
    if (np >= 5) runIn(2 + gap4, "pushPage5Layout")
    if (np >= 6) runIn(2 + gap5, "pushPage6Layout")
}

def pushPage1Layout() {
    publishBacklight(true)
    pushPageLayout(1)
}

def pushPage2Layout() {
    int np2 = (state.numberOfPages ?: 6) as int
    if (np2 >= 2) pushPageLayout(2)
}

def pushPage3Layout() {
    int np3 = (state.numberOfPages ?: 6) as int
    if (np3 >= 3) pushPageLayout(3)
}

def pushPage4Layout() {
    int np4 = (state.numberOfPages ?: 6) as int
    if (np4 >= 4) pushPageLayout(4)
}
def pushPage5Layout() {
    int np5 = (state.numberOfPages ?: 6) as int
    if (np5 >= 5) pushPageLayout(5)
}
def pushPage6Layout() {
    int np6 = (state.numberOfPages ?: 6) as int
    if (np6 >= 6) pushPageLayout(6)
}

private void pushPageLayout(int page) {
    String grid  = activeGrid(page)
    int total    = (state.numberOfPages ?: 4) as int
    String node  = settings.haspNode ?: "plate"

    infoLog "[AutoPages] Pushing page ${page}/${total}: ${grid}"
    sendEvent(name: "mqttStatus", value: "Pushing page ${page}/${total}...")

    // Clear this page in background (user still sees previous page)
    try { interfaces.mqtt.publish("hasp/${node}/command", "clearpage ${page}", 1, false); pauseExecution(200) }
    catch (Exception e) { }

    // Cancel any in-progress fades for this page before re-rendering
    (1..maxSensors(page)).each { s ->
        unschedule("p${page}fadeStep${s}")
        state.remove("p${page}fadeStep${s}")
    }

    // 1. Push tile layout (btns + icon overlays + nav buttons + page indicator)
    layoutJsonl(grid, page, total).each { jsonl ->
        try { interfaces.mqtt.publish("hasp/${node}/command/jsonl", jsonl, 1, false); pauseExecution(50) }
        catch (Exception e) { infoLog "[AutoPages] ERROR -- layout push p${page}: ${e.message}" }
    }

    // 2. Push colors, icons and labels for every slot immediately after layout
    pauseExecution(300)
    (1..maxSensors(page)).each { idx ->
        String slotType = state[typeKey(page, idx)] ?: "none"
        // During layout push always render inactive/empty -- syncAllSensors corrects active states after
        if (!slotType || slotType == "none") {
            publishColor(page, idx, "#708090")
            publishTextColor(page, idx, "#708090")
            publishIcon(page, idx, "")
        } else {
            String ic = inactiveColorFor(page, idx)
            publishColor(page, idx, ic)
            publishTextColor(page, idx, ic)
            publishIcon(page, idx, inactiveIconFor(page, idx))
        }
        String lbl = state[labelKey(page, idx)] ?: ""
        if (lbl) {
            publishJsonl(node, page, bgId(idx), [text: lbl])
            pauseExecution(30)
        }
        pauseExecution(30)
    }

    // Navigate to this page now that it is fully rendered
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${page}", 1, false); pauseExecution(200) }
    catch (Exception e) { }

    if (page == total) {
        pauseExecution(5000)
        try { interfaces.mqtt.publish("hasp/${node}/command/page", "1", 1, false) } catch (Exception e) { }
        sendEvent(name: "mqttStatus", value: "Connected")
        infoLog "[AutoPages] All ${total} page(s) pushed"
        sendEvent(name: "layoutPushComplete", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        // Start auto-rotation
        if (total > 1) {
            int rotInt = (settings.rotationInterval ?: 0) as int
            if (rotInt > 0) {
                state.rotationPage = 1
                unschedule("rotatePage")
                runIn(rotInt, "rotatePage")
            }
        }
    }
}

// -- Layout JSONL generators ----------------------------------------------------

private List<String> layoutJsonl(String grid, int page, int totalPages) {
    List<String> out
    switch (grid) {
        case "1x1": out = layout1x1(page); break
        case "3x3": out = layout3x3(page); break
        case "4x4": out = layout4x4(page); break
        case "5x5": out = layoutNxN(page, 5, 94, 2, 12, 2, 24); break
        case "6x6": out = layoutNxN(page, 6, 78, 2, 12, 2, 12); break
        case "7x7": out = layoutNxN(page, 7, 67, 1, 12, 1, 12); break
        default:    out = layout2x2(page)
    }

    if (totalPages > 1) {
        int prevPage = (page == 1) ? totalPages : page - 1
        int nextPage = (page == totalPages) ? 1 : page + 1
        // Full-height invisible edge tap zones -- 40px wide, full 480px height, ~8% opacity
        out << """{"page":${page},"id":201,"obj":"btn","x":0,"y":0,"w":30,"h":480,"bg_color":"#000000","bg_opa":20,"border_width":0,"radius":0,"text":"","text_font":8,"toggle":false,"action":"p${prevPage}"}"""
        out << """{"page":${page},"id":202,"obj":"btn","x":450,"y":0,"w":30,"h":480,"bg_color":"#000000","bg_opa":20,"border_width":0,"radius":0,"text":"","text_font":8,"toggle":false,"action":"p${nextPage}"}"""
        if (settings.showPageIndicator == true) {
            out << """{"page":${page},"id":200,"obj":"label","x":424,"y":4,"w":54,"h":22,"bg_color":"#000000","bg_opa":180,"border_width":0,"radius":4,"text":"${page}/${totalPages}","text_font":16,"text_color":"white","align":"center","click":false}"""
        } else {
            out << """{"page":${page},"id":200,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        }
    } else {
        // Single page -- erase any stale nav objects from prior layouts
        out << """{"page":${page},"id":200,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        out << """{"page":${page},"id":201,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
        out << """{"page":${page},"id":202,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"click":false}"""
    }
    return out
}

private List<String> layout1x1(int page) {[
    """{"page":${page},"id":1,"obj":"btn","x":2,"y":2,"w":476,"h":476,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false}""",
    """{"page":${page},"id":51,"obj":"label","parentid":0,"x":8,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
]}

private List<String> layout2x2(int page) {
    List<String> out = []
    [[1,2,2,236,236],[2,242,2,236,236],[3,2,242,236,236],[4,242,242,236,236]].each { r ->
        out << """{"page":${page},"id":${r[0]},"obj":"btn","x":${r[1]},"y":${r[2]},"w":${r[3]},"h":${r[4]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }
    [[51,8,8],[52,248,8],[53,8,248],[54,248,248]].each { r ->
        out << """{"page":${page},"id":${r[0]},"obj":"label","parentid":0,"x":${r[1]},"y":${r[2]},"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
    }
    return out
}

private List<String> layout3x3(int page) {
    List<String> out = []
    int[][] cells = [[2,2,157,157],[161,2,157,157],[320,2,158,157],[2,161,157,157],[161,161,157,157],[320,161,158,157],[2,320,157,158],[161,320,157,158],[320,320,158,158]]
    cells.eachWithIndex { c, i ->
        out << """{"page":${page},"id":${i+1},"obj":"btn","x":${c[0]},"y":${c[1]},"w":${c[2]},"h":${c[3]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }
    int[][] icons = [[8,8],[167,8],[326,8],[8,167],[167,167],[326,167],[8,326],[167,326],[326,326]]
    icons.eachWithIndex { c, i ->
        out << """{"page":${page},"id":${i+51},"obj":"label","parentid":0,"x":${c[0]},"y":${c[1]},"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
    }
    return out
}

private List<String> layout4x4(int page) {
    List<String> out = []; int cols = 4; int w = 117; int gap = 2
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }}
    int id2 = 51
    (0..<cols).each { row -> (0..<cols).each { col ->
        int x = col*(w+gap)+gap+4; int y = row*(w+gap)+gap+4
        out << """{"page":${page},"id":${id2},"obj":"label","parentid":0,"x":${x},"y":${y},"w":24,"h":24,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
        id2++
    }}
    return out
}

private List<String> layoutNxN(int page, int cols, int w, int gap, int tf, int iconOff, int iconFont) {
    List<String> out = []
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":${tf},"align":"center","text_color":"black","toggle":false,"click":false}"""
    }}
    int id2 = 51
    (0..<cols).each { row -> (0..<cols).each { col ->
        int x = col*(w+gap)+gap+iconOff; int y = row*(w+gap)+gap+iconOff
        out << """{"page":${page},"id":${id2},"obj":"label","parentid":0,"x":${x},"y":${y},"w":${iconFont},"h":${iconFont},"bg_opa":0,"border_width":0,"text":"","text_font":${iconFont},"text_color":"black","click":false}"""
        id2++
    }}
    return out
}

// -- Page commands --------------------------------------------------------------

def setPage1MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(1)) setMotionActiveForPage(1,i) }
def setPage1MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(1)) setMotionInactiveForPage(1,i) }
def setPage1SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(1)) setSlotEmptyForPage(1,i) }
def setPage2MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(2)) setMotionActiveForPage(2,i) }
def setPage2MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(2)) setMotionInactiveForPage(2,i) }
def setPage2SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(2)) setSlotEmptyForPage(2,i) }
def setPage3MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(3)) setMotionActiveForPage(3,i) }
def setPage3MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(3)) setMotionInactiveForPage(3,i) }
def setPage3SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(3)) setSlotEmptyForPage(3,i) }
def setPage4MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(4)) setMotionActiveForPage(4,i) }
def setPage4MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(4)) setMotionInactiveForPage(4,i) }
def setPage4SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(4)) setSlotEmptyForPage(4,i) }
def setPage5MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(5)) setMotionActiveForPage(5,i) }
def setPage5MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(5)) setMotionInactiveForPage(5,i) }
def setPage5SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(5)) setSlotEmptyForPage(5,i) }
def setPage6MotionActive(n)   { int i = (n as int); if (i>=1 && i<=maxSensors(6)) setMotionActiveForPage(6,i) }
def setPage6MotionInactive(n) { int i = (n as int); if (i>=1 && i<=maxSensors(6)) setMotionInactiveForPage(6,i) }
def setPage6SlotEmpty(n)      { int i = (n as int); if (i>=1 && i<=maxSensors(6)) setSlotEmptyForPage(6,i) }

private void setMotionActiveForPage(int page, int idx) {
    String sk = stateKey(page, idx)
    state[sk] = "active"
    unschedule("p${page}fadeStep${idx}")
    state.remove("p${page}fadeStep${idx}")
    String ac = settings.colorActive ?: "#FF0000"
    publishColor(page, idx, ac)
    publishTextColor(page, idx, ac)
    publishIcon(page, idx, activeIconFor(page, idx))
    // Navigate display to the page containing the active sensor and stop rotation
    unschedule("rotatePage")
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${page}", 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- page nav on active failed: ${e.message}" }
    if (settings.backlightOnMotion) {
        unschedule("backlightOff")
        unschedule("motionTimeoutBacklightOff")
        state.screenIdle = false
        publishBacklight(true)
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
    }
}

private void setMotionInactiveForPage(int page, int idx) {
    String sk      = stateKey(page, idx)
    String fadeKey = "p${page}fadeStep${idx}"
    boolean wasActive = (state[sk] == "active")
    state[sk] = "inactive"
    if (wasActive) {
        unschedule(fadeKey); state[fadeKey] = 0
        publishIcon(page, idx, inactiveIconFor(page, idx))
        scheduleFadeStep(page, idx)
        if (settings.backlightOnMotion) {
            unschedule("motionTimeoutBacklightOff")
            if (!allInactive()) {
                int mins = (settings.motionBacklightTimeout ?: 1) as int
                if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
            } else {
                runIn((FADE_STEPS + 1) * fadeInterval() + 2, "backlightOnAfterFade")
            }
        }
    } else {
        String ic = inactiveColorFor(page, idx)
        publishColor(page, idx, ic)
        publishTextColor(page, idx, ic)
        publishIcon(page, idx, inactiveIconFor(page, idx))
        if (settings.backlightOnMotion && allInactive()) {
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) { unschedule("backlightOff"); runIn(delay, "backlightOff") }
        }
    }
}

private void setSlotEmptyForPage(int page, int idx) {
    String sk = stateKey(page, idx); String fadeKey = "p${page}fadeStep${idx}"
    state[sk] = "empty"
    unschedule(fadeKey); state.remove(fadeKey)
    publishColor(page, idx, "#708090")
    publishTextColor(page, idx, "#708090")
    publishIcon(page, idx, "")
    String node = settings.haspNode ?: "plate"
    publishJsonl(node, page, bgId(idx),   [text: ""])
    publishJsonl(node, page, iconId(idx), [text: ""])
}

// -- Label / type updates -------------------------------------------------------

def updatePage1Labels(labels)    { applyLabels(labels, 1) }
def updatePage2Labels(labels)    { applyLabels(labels, 2) }
def updatePage3Labels(labels)    { applyLabels(labels, 3) }
def updatePage4Labels(labels)    { applyLabels(labels, 4) }
def updatePage5Labels(labels)    { applyLabels(labels, 5) }
def updatePage6Labels(labels)    { applyLabels(labels, 6) }
def updatePage1SlotTypes(types)  { applySlotTypes(types, 1) }
def updatePage2SlotTypes(types)  { applySlotTypes(types, 2) }
def updatePage3SlotTypes(types)  { applySlotTypes(types, 3) }
def updatePage4SlotTypes(types)  { applySlotTypes(types, 4) }
def updatePage5SlotTypes(types)  { applySlotTypes(types, 5) }
def updatePage6SlotTypes(types)  { applySlotTypes(types, 6) }

private void applyLabels(labels, int page) {
    if (!(labels instanceof Map)) {
        try { labels = new groovy.json.JsonSlurper().parseText(labels.toString()) }
        catch (Exception e) { infoLog "[AutoPages] WARN -- bad labels JSON: ${e.message}"; return }
    }
    // Store labels in state only -- publishing happens during pushPageLayout
    labels.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > 49) return
        String lbl = v?.toString() ?: ""
        state[labelKey(page, idx)] = lbl
    }
    infoLog "[AutoPages] Labels stored for page ${page}: ${labels.size()} entries"
}

private void applySlotTypes(slotTypes, int page) {
    if (!(slotTypes instanceof Map)) {
        try { slotTypes = new groovy.json.JsonSlurper().parseText(slotTypes.toString()) }
        catch (Exception e) { infoLog "[AutoPages] WARN -- bad slotTypes JSON: ${e.message}"; return }
    }
    slotTypes.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > 49) return
        // Store ALL slot types regardless of current grid -- grid may not be set yet
        state[typeKey(page, idx)] = (v?.toString() ?: "none")
    }
}

// -- MQTT publish helpers -------------------------------------------------------

private void publishColor(int page, int slot, String hex) {
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/p${page}b${bgId(slot)}.bg_color", hex, 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- Color publish failed: ${e.message}" }
}

private void publishTextColor(int page, int slot, String bgHex) {
    String node  = settings.haspNode ?: "plate"
    String color = textColorFor(bgHex)
    String lbl   = state[labelKey(page, slot)] ?: ""
    if (lbl) {
        publishJsonl(node, page, bgId(slot), [text_color: color, text: lbl])
    } else {
        publishJsonl(node, page, bgId(slot), [text_color: color])
    }
    if (useLetterIcon(page)) publishJsonl(node, page, iconId(slot), [text_color: color])
}

private void publishIcon(int page, int slot, String glyph) {
    String node  = settings.haspNode ?: "plate"
    int fontPt   = useLetterIcon(page) ? 12 : 24
    publishJsonl(node, page, iconId(slot), [text: glyph, text_font: fontPt])
}

private void publishJsonl(String node, int page, int objId, Map props) {
    String json  = groovy.json.JsonOutput.toJson(props)
    String topic = "hasp/${node}/command/p${page}b${objId}.jsonl"
    try { interfaces.mqtt.publish(topic, json, 1, false) }
    catch (Exception e) { infoLog "[AutoPages] ERROR -- JSONL publish failed: ${e.message}" }
}

// -- Color / icon helpers -------------------------------------------------------

private String inactiveColorFor(int page, int idx) {
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    switch (sType) {
        case "contact": return settings.colorContactInactive ?: "#00FFFF"
        case "water":   return settings.colorWaterInactive   ?: "#0000FF"
        case "smoke":   return settings.colorSmokeInactive   ?: "#FFFF00"
        default:        return settings.colorInactive        ?: "#008000"
    }
}

private String textColorFor(String hex) {
    String h = hex.replace("#", "")
    int r = Integer.parseInt(h[0..1], 16)
    int g = Integer.parseInt(h[2..3], 16)
    int b = Integer.parseInt(h[4..5], 16)
    return (0.2126*(r/255.0) + 0.7152*(g/255.0) + 0.0722*(b/255.0)) > 0.35 ? "black" : "white"
}

@Field static final String ICON_ALERT   = "\uE026"
@Field static final String ICON_MOTION  = "\uE70E"
@Field static final String ICON_CONTACT = "\uE2DC"
@Field static final String ICON_WATER   = "\uE58C"
@Field static final String ICON_SMOKE   = "\uE238"

private boolean useLetterIcon(int page) { activeGrid(page) in ["6x6", "7x7"] }

private String activeIconFor(int page, int idx) {
    if (useLetterIcon(page)) return letterIconFor(page, idx)
    return ICON_ALERT
}

private String inactiveIconFor(int page, int idx) {
    if (useLetterIcon(page)) return letterIconFor(page, idx)
    String sType = state[typeKey(page, idx)] ?: state["pageType${page}"] ?: "motion"
    switch (sType) {
        case "contact": return ICON_CONTACT
        case "water":   return ICON_WATER
        case "smoke":   return ICON_SMOKE
        default:        return ICON_MOTION
    }
}

private String letterIconFor(int page, int idx) {
    switch (state[typeKey(page, idx)] ?: "motion") {
        case "contact": return "C"; case "water": return "W"
        case "smoke":   return "S"; case "none":  return ""
        default:        return "M"
    }
}

// -- Fade -----------------------------------------------------------------------

@Field static final int FADE_STEPS = 6

private int fadeInterval() {
    Math.max(1, Math.round(((settings.fadeDuration ?: 30) as int) / FADE_STEPS) as int)
}

private void scheduleFadeStep(int page, int idx) {
    runIn(fadeInterval(), "p${page}fadeStep${idx}")
}

private void doFadeStep(int page, int idx) {
    String sk      = stateKey(page, idx)
    String fadeKey = "p${page}fadeStep${idx}"
    if (state[sk] == "active") return
    int step   = (state[fadeKey] ?: 0) as int
    double t   = step / (FADE_STEPS as double)
    String fromHex = (settings.colorActive ?: "#FF0000").replace("#", "")
    String toHex   = inactiveColorFor(page, idx).replace("#", "")
    int fR = Integer.parseInt(fromHex[0..1], 16); int fG = Integer.parseInt(fromHex[2..3], 16); int fB = Integer.parseInt(fromHex[4..5], 16)
    int tR = Integer.parseInt(toHex[0..1], 16);   int tG = Integer.parseInt(toHex[2..3], 16);   int tB = Integer.parseInt(toHex[4..5], 16)
    int r = Math.max(0, Math.min(255, Math.round(fR+(tR-fR)*t) as int))
    int g = Math.max(0, Math.min(255, Math.round(fG+(tG-fG)*t) as int))
    int b = Math.max(0, Math.min(255, Math.round(fB+(tB-fB)*t) as int))
    publishColor(page, idx, sprintf("#%02X%02X%02X", r, g, b))
    if (step < FADE_STEPS) {
        state[fadeKey] = step + 1; scheduleFadeStep(page, idx)
    } else {
        state.remove(fadeKey)
        String snap = inactiveColorFor(page, idx)
        publishColor(page, idx, snap); publishTextColor(page, idx, snap)
        if (settings.backlightOnMotion && allInactive() && !state.screenIdle) {
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) { unschedule("backlightOff"); runIn(delay, "backlightOff") }
        }
        // Resume page rotation now that all sensors are clear
        if (allInactive()) {
            int rotInt = (settings.rotationInterval ?: 0) as int
            if (rotInt > 0) {
                unschedule("rotatePage")
                runIn(rotInt, "rotatePage")
            }
        }
    }
}

// Fade step methods -- 4 pages x 49 slots
def p1fadeStep1(){doFadeStep(1,1)}
def p1fadeStep2(){doFadeStep(1,2)}
def p1fadeStep3(){doFadeStep(1,3)}
def p1fadeStep4(){doFadeStep(1,4)}
def p1fadeStep5(){doFadeStep(1,5)}
def p1fadeStep6(){doFadeStep(1,6)}
def p1fadeStep7(){doFadeStep(1,7)}
def p1fadeStep8(){doFadeStep(1,8)}
def p1fadeStep9(){doFadeStep(1,9)}
def p1fadeStep10(){doFadeStep(1,10)}
def p1fadeStep11(){doFadeStep(1,11)}
def p1fadeStep12(){doFadeStep(1,12)}
def p1fadeStep13(){doFadeStep(1,13)}
def p1fadeStep14(){doFadeStep(1,14)}
def p1fadeStep15(){doFadeStep(1,15)}
def p1fadeStep16(){doFadeStep(1,16)}
def p1fadeStep17(){doFadeStep(1,17)}
def p1fadeStep18(){doFadeStep(1,18)}
def p1fadeStep19(){doFadeStep(1,19)}
def p1fadeStep20(){doFadeStep(1,20)}
def p1fadeStep21(){doFadeStep(1,21)}
def p1fadeStep22(){doFadeStep(1,22)}
def p1fadeStep23(){doFadeStep(1,23)}
def p1fadeStep24(){doFadeStep(1,24)}
def p1fadeStep25(){doFadeStep(1,25)}
def p1fadeStep26(){doFadeStep(1,26)}
def p1fadeStep27(){doFadeStep(1,27)}
def p1fadeStep28(){doFadeStep(1,28)}
def p1fadeStep29(){doFadeStep(1,29)}
def p1fadeStep30(){doFadeStep(1,30)}
def p1fadeStep31(){doFadeStep(1,31)}
def p1fadeStep32(){doFadeStep(1,32)}
def p1fadeStep33(){doFadeStep(1,33)}
def p1fadeStep34(){doFadeStep(1,34)}
def p1fadeStep35(){doFadeStep(1,35)}
def p1fadeStep36(){doFadeStep(1,36)}
def p1fadeStep37(){doFadeStep(1,37)}
def p1fadeStep38(){doFadeStep(1,38)}
def p1fadeStep39(){doFadeStep(1,39)}
def p1fadeStep40(){doFadeStep(1,40)}
def p1fadeStep41(){doFadeStep(1,41)}
def p1fadeStep42(){doFadeStep(1,42)}
def p1fadeStep43(){doFadeStep(1,43)}
def p1fadeStep44(){doFadeStep(1,44)}
def p1fadeStep45(){doFadeStep(1,45)}
def p1fadeStep46(){doFadeStep(1,46)}
def p1fadeStep47(){doFadeStep(1,47)}
def p1fadeStep48(){doFadeStep(1,48)}
def p1fadeStep49(){doFadeStep(1,49)}

def p2fadeStep1(){doFadeStep(2,1)}
def p2fadeStep2(){doFadeStep(2,2)}
def p2fadeStep3(){doFadeStep(2,3)}
def p2fadeStep4(){doFadeStep(2,4)}
def p2fadeStep5(){doFadeStep(2,5)}
def p2fadeStep6(){doFadeStep(2,6)}
def p2fadeStep7(){doFadeStep(2,7)}
def p2fadeStep8(){doFadeStep(2,8)}
def p2fadeStep9(){doFadeStep(2,9)}
def p2fadeStep10(){doFadeStep(2,10)}
def p2fadeStep11(){doFadeStep(2,11)}
def p2fadeStep12(){doFadeStep(2,12)}
def p2fadeStep13(){doFadeStep(2,13)}
def p2fadeStep14(){doFadeStep(2,14)}
def p2fadeStep15(){doFadeStep(2,15)}
def p2fadeStep16(){doFadeStep(2,16)}
def p2fadeStep17(){doFadeStep(2,17)}
def p2fadeStep18(){doFadeStep(2,18)}
def p2fadeStep19(){doFadeStep(2,19)}
def p2fadeStep20(){doFadeStep(2,20)}
def p2fadeStep21(){doFadeStep(2,21)}
def p2fadeStep22(){doFadeStep(2,22)}
def p2fadeStep23(){doFadeStep(2,23)}
def p2fadeStep24(){doFadeStep(2,24)}
def p2fadeStep25(){doFadeStep(2,25)}
def p2fadeStep26(){doFadeStep(2,26)}
def p2fadeStep27(){doFadeStep(2,27)}
def p2fadeStep28(){doFadeStep(2,28)}
def p2fadeStep29(){doFadeStep(2,29)}
def p2fadeStep30(){doFadeStep(2,30)}
def p2fadeStep31(){doFadeStep(2,31)}
def p2fadeStep32(){doFadeStep(2,32)}
def p2fadeStep33(){doFadeStep(2,33)}
def p2fadeStep34(){doFadeStep(2,34)}
def p2fadeStep35(){doFadeStep(2,35)}
def p2fadeStep36(){doFadeStep(2,36)}
def p2fadeStep37(){doFadeStep(2,37)}
def p2fadeStep38(){doFadeStep(2,38)}
def p2fadeStep39(){doFadeStep(2,39)}
def p2fadeStep40(){doFadeStep(2,40)}
def p2fadeStep41(){doFadeStep(2,41)}
def p2fadeStep42(){doFadeStep(2,42)}
def p2fadeStep43(){doFadeStep(2,43)}
def p2fadeStep44(){doFadeStep(2,44)}
def p2fadeStep45(){doFadeStep(2,45)}
def p2fadeStep46(){doFadeStep(2,46)}
def p2fadeStep47(){doFadeStep(2,47)}
def p2fadeStep48(){doFadeStep(2,48)}
def p2fadeStep49(){doFadeStep(2,49)}

def p3fadeStep1(){doFadeStep(3,1)}
def p3fadeStep2(){doFadeStep(3,2)}
def p3fadeStep3(){doFadeStep(3,3)}
def p3fadeStep4(){doFadeStep(3,4)}
def p3fadeStep5(){doFadeStep(3,5)}
def p3fadeStep6(){doFadeStep(3,6)}
def p3fadeStep7(){doFadeStep(3,7)}
def p3fadeStep8(){doFadeStep(3,8)}
def p3fadeStep9(){doFadeStep(3,9)}
def p3fadeStep10(){doFadeStep(3,10)}
def p3fadeStep11(){doFadeStep(3,11)}
def p3fadeStep12(){doFadeStep(3,12)}
def p3fadeStep13(){doFadeStep(3,13)}
def p3fadeStep14(){doFadeStep(3,14)}
def p3fadeStep15(){doFadeStep(3,15)}
def p3fadeStep16(){doFadeStep(3,16)}
def p3fadeStep17(){doFadeStep(3,17)}
def p3fadeStep18(){doFadeStep(3,18)}
def p3fadeStep19(){doFadeStep(3,19)}
def p3fadeStep20(){doFadeStep(3,20)}
def p3fadeStep21(){doFadeStep(3,21)}
def p3fadeStep22(){doFadeStep(3,22)}
def p3fadeStep23(){doFadeStep(3,23)}
def p3fadeStep24(){doFadeStep(3,24)}
def p3fadeStep25(){doFadeStep(3,25)}
def p3fadeStep26(){doFadeStep(3,26)}
def p3fadeStep27(){doFadeStep(3,27)}
def p3fadeStep28(){doFadeStep(3,28)}
def p3fadeStep29(){doFadeStep(3,29)}
def p3fadeStep30(){doFadeStep(3,30)}
def p3fadeStep31(){doFadeStep(3,31)}
def p3fadeStep32(){doFadeStep(3,32)}
def p3fadeStep33(){doFadeStep(3,33)}
def p3fadeStep34(){doFadeStep(3,34)}
def p3fadeStep35(){doFadeStep(3,35)}
def p3fadeStep36(){doFadeStep(3,36)}
def p3fadeStep37(){doFadeStep(3,37)}
def p3fadeStep38(){doFadeStep(3,38)}
def p3fadeStep39(){doFadeStep(3,39)}
def p3fadeStep40(){doFadeStep(3,40)}
def p3fadeStep41(){doFadeStep(3,41)}
def p3fadeStep42(){doFadeStep(3,42)}
def p3fadeStep43(){doFadeStep(3,43)}
def p3fadeStep44(){doFadeStep(3,44)}
def p3fadeStep45(){doFadeStep(3,45)}
def p3fadeStep46(){doFadeStep(3,46)}
def p3fadeStep47(){doFadeStep(3,47)}
def p3fadeStep48(){doFadeStep(3,48)}
def p3fadeStep49(){doFadeStep(3,49)}

def p4fadeStep1(){doFadeStep(4,1)}
def p4fadeStep2(){doFadeStep(4,2)}
def p4fadeStep3(){doFadeStep(4,3)}
def p4fadeStep4(){doFadeStep(4,4)}
def p4fadeStep5(){doFadeStep(4,5)}
def p4fadeStep6(){doFadeStep(4,6)}
def p4fadeStep7(){doFadeStep(4,7)}
def p4fadeStep8(){doFadeStep(4,8)}
def p4fadeStep9(){doFadeStep(4,9)}
def p4fadeStep10(){doFadeStep(4,10)}
def p4fadeStep11(){doFadeStep(4,11)}
def p4fadeStep12(){doFadeStep(4,12)}
def p4fadeStep13(){doFadeStep(4,13)}
def p4fadeStep14(){doFadeStep(4,14)}
def p4fadeStep15(){doFadeStep(4,15)}
def p4fadeStep16(){doFadeStep(4,16)}
def p4fadeStep17(){doFadeStep(4,17)}
def p4fadeStep18(){doFadeStep(4,18)}
def p4fadeStep19(){doFadeStep(4,19)}
def p4fadeStep20(){doFadeStep(4,20)}
def p4fadeStep21(){doFadeStep(4,21)}
def p4fadeStep22(){doFadeStep(4,22)}
def p4fadeStep23(){doFadeStep(4,23)}
def p4fadeStep24(){doFadeStep(4,24)}
def p4fadeStep25(){doFadeStep(4,25)}
def p4fadeStep26(){doFadeStep(4,26)}
def p4fadeStep27(){doFadeStep(4,27)}
def p4fadeStep28(){doFadeStep(4,28)}
def p4fadeStep29(){doFadeStep(4,29)}
def p4fadeStep30(){doFadeStep(4,30)}
def p4fadeStep31(){doFadeStep(4,31)}
def p4fadeStep32(){doFadeStep(4,32)}
def p4fadeStep33(){doFadeStep(4,33)}
def p4fadeStep34(){doFadeStep(4,34)}
def p4fadeStep35(){doFadeStep(4,35)}
def p4fadeStep36(){doFadeStep(4,36)}
def p4fadeStep37(){doFadeStep(4,37)}
def p4fadeStep38(){doFadeStep(4,38)}
def p4fadeStep39(){doFadeStep(4,39)}
def p4fadeStep40(){doFadeStep(4,40)}
def p4fadeStep41(){doFadeStep(4,41)}
def p4fadeStep42(){doFadeStep(4,42)}
def p4fadeStep43(){doFadeStep(4,43)}
def p4fadeStep44(){doFadeStep(4,44)}
def p4fadeStep45(){doFadeStep(4,45)}
def p4fadeStep46(){doFadeStep(4,46)}
def p4fadeStep47(){doFadeStep(4,47)}
def p4fadeStep48(){doFadeStep(4,48)}
def p4fadeStep49(){doFadeStep(4,49)}

// -- Page rotation

def rotatePage() {
    int rotInt = (settings.rotationInterval ?: 0) as int
    if (rotInt <= 0) return
    // Do not rotate if any sensor is active
    if (!allInactive()) {
        infoLog "[AutoPages] Rotation paused -- sensor active"
        return
    }
    int total = (state.numberOfPages ?: 4) as int
    if (total <= 1) return
    int current = (state.rotationPage ?: 1) as int
    int next = (current >= total) ? 1 : current + 1
    state.rotationPage = next
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${next}", 1, false) }
    catch (Exception e) { infoLog "[AutoPages] Rotation publish failed: ${e.message}" }
    runIn(rotInt, "rotatePage")
}

def p5fadeStep1(){doFadeStep(5,1)}
def p5fadeStep2(){doFadeStep(5,2)}
def p5fadeStep3(){doFadeStep(5,3)}
def p5fadeStep4(){doFadeStep(5,4)}
def p5fadeStep5(){doFadeStep(5,5)}
def p5fadeStep6(){doFadeStep(5,6)}
def p5fadeStep7(){doFadeStep(5,7)}
def p5fadeStep8(){doFadeStep(5,8)}
def p5fadeStep9(){doFadeStep(5,9)}
def p5fadeStep10(){doFadeStep(5,10)}
def p5fadeStep11(){doFadeStep(5,11)}
def p5fadeStep12(){doFadeStep(5,12)}
def p5fadeStep13(){doFadeStep(5,13)}
def p5fadeStep14(){doFadeStep(5,14)}
def p5fadeStep15(){doFadeStep(5,15)}
def p5fadeStep16(){doFadeStep(5,16)}
def p5fadeStep17(){doFadeStep(5,17)}
def p5fadeStep18(){doFadeStep(5,18)}
def p5fadeStep19(){doFadeStep(5,19)}
def p5fadeStep20(){doFadeStep(5,20)}
def p5fadeStep21(){doFadeStep(5,21)}
def p5fadeStep22(){doFadeStep(5,22)}
def p5fadeStep23(){doFadeStep(5,23)}
def p5fadeStep24(){doFadeStep(5,24)}
def p5fadeStep25(){doFadeStep(5,25)}
def p5fadeStep26(){doFadeStep(5,26)}
def p5fadeStep27(){doFadeStep(5,27)}
def p5fadeStep28(){doFadeStep(5,28)}
def p5fadeStep29(){doFadeStep(5,29)}
def p5fadeStep30(){doFadeStep(5,30)}
def p5fadeStep31(){doFadeStep(5,31)}
def p5fadeStep32(){doFadeStep(5,32)}
def p5fadeStep33(){doFadeStep(5,33)}
def p5fadeStep34(){doFadeStep(5,34)}
def p5fadeStep35(){doFadeStep(5,35)}
def p5fadeStep36(){doFadeStep(5,36)}
def p5fadeStep37(){doFadeStep(5,37)}
def p5fadeStep38(){doFadeStep(5,38)}
def p5fadeStep39(){doFadeStep(5,39)}
def p5fadeStep40(){doFadeStep(5,40)}
def p5fadeStep41(){doFadeStep(5,41)}
def p5fadeStep42(){doFadeStep(5,42)}
def p5fadeStep43(){doFadeStep(5,43)}
def p5fadeStep44(){doFadeStep(5,44)}
def p5fadeStep45(){doFadeStep(5,45)}
def p5fadeStep46(){doFadeStep(5,46)}
def p5fadeStep47(){doFadeStep(5,47)}
def p5fadeStep48(){doFadeStep(5,48)}
def p5fadeStep49(){doFadeStep(5,49)}
def p6fadeStep1(){doFadeStep(6,1)}
def p6fadeStep2(){doFadeStep(6,2)}
def p6fadeStep3(){doFadeStep(6,3)}
def p6fadeStep4(){doFadeStep(6,4)}
def p6fadeStep5(){doFadeStep(6,5)}
def p6fadeStep6(){doFadeStep(6,6)}
def p6fadeStep7(){doFadeStep(6,7)}
def p6fadeStep8(){doFadeStep(6,8)}
def p6fadeStep9(){doFadeStep(6,9)}
def p6fadeStep10(){doFadeStep(6,10)}
def p6fadeStep11(){doFadeStep(6,11)}
def p6fadeStep12(){doFadeStep(6,12)}
def p6fadeStep13(){doFadeStep(6,13)}
def p6fadeStep14(){doFadeStep(6,14)}
def p6fadeStep15(){doFadeStep(6,15)}
def p6fadeStep16(){doFadeStep(6,16)}
def p6fadeStep17(){doFadeStep(6,17)}
def p6fadeStep18(){doFadeStep(6,18)}
def p6fadeStep19(){doFadeStep(6,19)}
def p6fadeStep20(){doFadeStep(6,20)}
def p6fadeStep21(){doFadeStep(6,21)}
def p6fadeStep22(){doFadeStep(6,22)}
def p6fadeStep23(){doFadeStep(6,23)}
def p6fadeStep24(){doFadeStep(6,24)}
def p6fadeStep25(){doFadeStep(6,25)}
def p6fadeStep26(){doFadeStep(6,26)}
def p6fadeStep27(){doFadeStep(6,27)}
def p6fadeStep28(){doFadeStep(6,28)}
def p6fadeStep29(){doFadeStep(6,29)}
def p6fadeStep30(){doFadeStep(6,30)}
def p6fadeStep31(){doFadeStep(6,31)}
def p6fadeStep32(){doFadeStep(6,32)}
def p6fadeStep33(){doFadeStep(6,33)}
def p6fadeStep34(){doFadeStep(6,34)}
def p6fadeStep35(){doFadeStep(6,35)}
def p6fadeStep36(){doFadeStep(6,36)}
def p6fadeStep37(){doFadeStep(6,37)}
def p6fadeStep38(){doFadeStep(6,38)}
def p6fadeStep39(){doFadeStep(6,39)}
def p6fadeStep40(){doFadeStep(6,40)}
def p6fadeStep41(){doFadeStep(6,41)}
def p6fadeStep42(){doFadeStep(6,42)}
def p6fadeStep43(){doFadeStep(6,43)}
def p6fadeStep44(){doFadeStep(6,44)}
def p6fadeStep45(){doFadeStep(6,45)}
def p6fadeStep46(){doFadeStep(6,46)}
def p6fadeStep47(){doFadeStep(6,47)}
def p6fadeStep48(){doFadeStep(6,48)}
def p6fadeStep49(){doFadeStep(6,49)}

// -- Logging --------------------------------------------------------------------
private void infoLog(String msg)  { if ((settings.logLevel ?: "1") != "0") log.info msg }
private void debugLog(String msg) { if ((settings.logLevel ?: "1") == "2") log.debug msg }
