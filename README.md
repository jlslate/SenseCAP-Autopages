# SenseCAP Auto-Pages Monitor

**Version 5.0.0**

A Hubitat app + driver pair for the [SenseCAP Indicator D1](https://www.seeedstudio.com/SenseCAP-Indicator-D1-p-5643.html) (480×480 touchscreen) running [openHASP](https://openhasp.haswitchplate.com/) firmware. Displays up to 6 pages of sensor states via MQTT, with tap-to-toggle light control.

---

## Requirements

- Hubitat Elevation hub
- SenseCAP Indicator D1 flashed with openHASP firmware (`sensecap-indicator-d1` build)
- Hubitat MQTT Export integration with built-in broker enabled (required for tap events)

---

## Features

- **Up to 6 pages**, each independently configured with a sensor type and device list
- **Sensor types**: Smoke · Motion · Water · Contact · Light (switch/tap-to-toggle)
- **Auto-sized grids**: 1×1 through 7×7 based on device count
- **Page order**: Move Up / Move Down buttons reorder pages; Remove button deletes pages
- **Light tap-to-toggle**: openHASP button events toggle lights directly from the display
- **Periodic light re-sync**: configurable interval (5 / 10 / 30 min / Never) corrects drift
- **Clean rendering**: each page fully built before being shown; per-page drain timer waits for openHASP MQTT queue to clear before page flip
- **Event gating**: all sensor events and sync blocked during layout push via `pushInProgress` attribute and `appPushInProgress` app state flag
- **Alphabetical sorting**: devices sorted by name (emoji-stripped) within each page
- **Backlight management**: optional motion-triggered backlight with timeout
- **rebootDisplay command**: sends openHASP reboot via MQTT from the device page

---

## Installation

1. In Hubitat, go to **Apps Code** → **New App** → paste `sensecap-autopages-app.groovy`
2. Go to **Drivers Code** → **New Driver** → paste `sensecap-autopages-driver.groovy`
3. Go to **Devices** → **Add Virtual Device** → select **SenseCAP Auto-Pages Monitor** as the driver
4. Go to **Apps** → **Add User App** → **SenseCAP Auto-Pages Monitor**
5. In the app, select the driver device and configure pages

---

## Configuration

### Pages
Each page has:
- **Sensor type** dropdown (smoke / motion / water / contact / light)
- **Device picker** — select any number of devices of that type
- **Grid info** — shows detected device count and resulting grid size
- **Move Up / Move Down** — reorder pages
- **Remove** — delete the page (not available on the first page)

An **Add page N** toggle appears at the bottom of the last page section to add more pages (up to 6).

### Options
| Setting | Description |
|---|---|
| Sync all states on startup/save | Run syncAllSensors after every render |
| Re-sync light states every | Cron interval to correct light state drift |
| Rotation interval | Seconds between auto-page rotation (0 = no rotation) |
| Logging level | Info only, or Info + Debug |

---

## How Rendering Works

When you hit **Done** in the app (or the display reboots):

1. App sends `rebootDisplay` to the SenseCAP if MQTT is connected
2. LWT `online` event triggers `pushSlotTypesAndLayouts`
3. Slot types, labels, and grid layouts are pushed to driver state
4. `pushAllLayouts` is called — pages render one at a time:
   - `clearpage N` wipes the page
   - Layout JSONL (tiles, nav buttons, page indicator) is published
   - Colors, icons, labels published per slot (light tiles use full btn jsonl with `click:true`)
   - `navigatePageN` fires after a drain wait (scaled to slot count) — navigates to page N, chains to next page
5. After last page: `returnToPage1AndStartRotation` fires 12 seconds later
   - Navigates to page 1
   - Fires `layoutPushComplete` → `syncAllSensors` corrects all sensor states
   - Starts rotation timer

---

## MQTT Topics

| Direction | Topic | Purpose |
|---|---|---|
| Subscribe | `hasp/{node}/state/statusupdate` | Detect reboots via uptime |
| Subscribe | `hasp/{node}/state/idle` | Screen wake detection |
| Subscribe | `hasp/{node}/LWT` | Display online/offline |
| Subscribe | `hasp/+/state/+` | Button tap events for light toggle |
| Publish | `hasp/{node}/command/jsonl` | Layout and tile objects |
| Publish | `hasp/{node}/command/page` | Page navigation |
| Publish | `hasp/{node}/command/clearpage` | Clear page before render |
| Publish | `hasp/{node}/command` | Reboot command |
| Publish | `hasp/{node}/command/backlight` | Backlight control |

---

## Driver Commands

| Command | Description |
|---|---|
| `pushAllLayouts` | Re-render all pages |
| `rebootDisplay` | Send openHASP reboot command |
| `reconnectMqtt` | Disconnect and reconnect MQTT |
| `setPage1..6GridLayout` | Set grid for a display page |
| `setPage1..6MotionActive/Inactive` | Update slot state |
| `setPage1..6SlotEmpty` | Mark slot as empty |

---

## Version History

| Version | Notes |
|---|---|
| 5.0.0 | Per-page drain timers (navigatePage1..6); light tile atomic jsonl rebuild; pushInProgress event attribute; appPushInProgress app gate; Remove page button; Add page 6 toggle fix; activePageOrder() for driver ops; blank page transition fix |
| 4.4.0 | Light tap-to-toggle; alphabetical sorting; 6 pages; periodic light resync; rendering overlay; page order buttons; rebootDisplay command |

---

## License

This is free and unencumbered software released into the public domain. See [unlicense.org](https://unlicense.org).

**Author**: jlslate (slate)
