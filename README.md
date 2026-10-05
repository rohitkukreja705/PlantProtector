# 🪴 Smart Plant Care — ESP32-C3 + Android

Monitors a potted plant's **soil moisture, air temperature/humidity (DHT11) and sunlight (LDR)**, waters it automatically with a 5 V pump, and talks to an Android app over **Bluetooth LE**. Pick the plant in the app (🌹 Rose, 🍅 Tomato, 🌿 Tulsi, 🍋 Lemon) and the device switches to that plant's moisture window; the app shows its sunlight, temperature and care needs and raises alerts.

```
SmartPlantCare/
├── firmware/SmartPlantCare/SmartPlantCare.ino   ESP32-C3 sketch
├── android/                                      Kotlin + Jetpack Compose app
└── .github/workflows/build.yml                   Builds firmware .bin + APK in the cloud
```

---

## 1. Parts

| Have | Notes |
|---|---|
| ESP32-C3 dev board | Any C3 board (DevKitM-1, C3 Super Mini, etc.) |
| Soil moisture sensor | Capacitive v1.2/v2.0 or resistive fork + comparator board — use the **AO** pin |
| DHT11 | 3-pin module (with pull-up) or bare 4-pin sensor + 10 kΩ pull-up on DATA |
| LDR module | 3/4-pin module — use the **AO** pin |
| Buzzer module | Active buzzer (beeps when powered) |
| 1-channel relay | 5 V coil, opto-isolated blue module |
| 5 V DC pump + tube | Submersible mini pump |
| 9 V battery + cap | ⚠️ see power section |
| 20 × F-F jumpers | |

**Strongly recommended extras (cheap):**
- **LM2596 / MP1584 buck converter** set to **5.0 V** — the 9 V battery must not feed the pump or the ESP32's 5 V pin directly.
- **Mini breadboard (or a small strip of perfboard)** — C3 boards have only one or two 3V3/GND pins, but 5 modules need power. F-F jumpers can't split a pin.
- 100–470 µF capacitor across the 5 V rail (absorbs pump start-up dips that can reset the ESP32).

---

## 2. Power — read this before connecting the battery

- A **PP3 9 V battery is a poor fit**: it holds ~400–550 mAh and sags heavily under the pump's 100–300 mA load. Expect only a few hours of runtime with BLE on.
- **Never connect 9 V to the pump or to the board's 5V pin.** A 5 V pump on 9 V overheats; 9 V on the 5V pin can destroy the board.
- **Best:** power everything from a 5 V USB phone charger (USB-C into the ESP32, take 5V from the board's 5V pin for relay + pump).
- **If you must use the 9 V battery:** 9 V → buck converter (adjusted to 5.0 V with a multimeter *before* connecting anything) → 5 V rail → ESP32 5V pin, relay VCC, pump.

```
 9V battery (+) ──► Buck IN+        Buck OUT+ (5.0V) ──► 5V RAIL ──► ESP32 5V pin
 9V battery (−) ──► Buck IN−        Buck OUT− ─────────► GND RAIL ─► ESP32 GND
```

---

## 3. Wiring

ESP32-C3 has analog (ADC1) only on **GPIO0–4**, so both analog sensors use those. GPIO2/8/9 (boot strapping) and 18/19 (USB) are avoided.

| Module | Module pin | Connect to |
|---|---|---|
| **Soil sensor** | VCC | **3V3** (not 5 V — protects the ADC) |
| | GND | GND |
| | AO | **GPIO3** |
| **LDR module** | VCC | 3V3 |
| | GND | GND |
| | AO | **GPIO4** |
| **DHT11** | VCC (+) | 3V3 |
| | GND (−) | GND |
| | DATA (OUT/S) | **GPIO10** |
| **Buzzer module** | VCC | 3V3 |
| | GND | GND |
| | I/O (S) | **GPIO7** |
| **Relay** | VCC | **5V rail** |
| | GND | GND |
| | IN | **GPIO6** |
| **Relay contacts** | COM | 5V rail |
| | NO | Pump **+** (red) |
| **Pump** | − (black) | GND rail |

All grounds must be common (ESP32, sensors, relay, pump, buck converter).

**Configurable in the sketch (top of file)** if your modules behave differently:
- `RELAY_ACTIVE_LOW` — `true` for typical blue modules (IN pulled LOW = ON). If the pump runs when it should be off, flip it.
- `BUZZER_ACTIVE_LOW` — set `true` if your buzzer beeps constantly at boot.
- `LDR_INVERT` — set `false` if the light % goes *down* when you shine a torch on the LDR.

---

## 4. How it works

**Firmware**
- Reads soil (16-sample average) every 1 s, DHT11 every 3 s, LDR every 1 s.
- **Auto-watering:** when moisture < plant minimum → pump runs a **4 s burst**, waits **45 s** for water to soak in, re-checks, repeats until moisture reaches the middle of the plant's range.
- **Dry-run / empty-tank protection:** if 6 bursts don't raise moisture by at least 3 %, it stops auto mode, beeps long, and the app shows *"Water tank may be empty"* with a **Resume** button.
- **Manual watering** from the app is capped at 30 s per press.
- **Sunlight:** every minute, if light % ≥ threshold it adds one "sun minute". The counter resets at local midnight (the app syncs the clock on connect).
- **Buzzer:** 1 beep at boot / pump start, 2 beeps when the phone connects, triple beep every minute if soil is very dry, long beeps every 30 s if tank is empty, "find me" melody from the app. Can be muted from the app.
- Plant profile, calibration, auto/mute settings and the sun counter are saved to flash.

**App (4 tabs)**
- **Dashboard** — moisture gauge with the plant's ideal band, sunlight now + hours today vs requirement, temperature/humidity vs plant's ideal range, pump status and controls (Water 5 s / 10 s / Stop), auto-watering and buzzer switches, care tips.
- **Plants** — pick Rose / Tomato / Tulsi / Lemon; the profile is pushed to the device instantly.
- **Alerts** — active issues + history log.
- **Settings** — connect/disconnect, soil calibration, sunlight threshold, wiring reference.

**In-app alerts** (banner on Dashboard + badge on Alerts tab + Android notification for warnings/critical):

| Alert | Trigger |
|---|---|
| Soil very dry 🔴 | moisture < plant min − 15 % |
| Soil getting dry 🟠 | moisture < plant min (and not currently watering) |
| Soil too wet 🟠 | moisture > plant max + 15 % |
| Tank may be empty 🔴 | dry-run protection tripped |
| Check soil sensor 🔴 | soil reading stuck near 0 |
| Too hot / too cold 🟠 | air temp outside the plant's range |
| Air too dry 🔵 | humidity well below the plant's range |
| Not enough sunlight 🟠 | after 5 pm, sun today < 75 % of requirement |
| Watering started 🔵 | pump turned on |
| Device disconnected 🟠 | BLE link lost (auto-reconnects) |

Critical alerts re-notify every 30 min while they persist; warnings every 3 h.

**Plant profiles**

| Plant | Moisture | Sun | Temp | Humidity |
|---|---|---|---|---|
| 🌹 Rose | 40–60 % | 6+ h full sun | 15–30 °C | 40–70 % |
| 🍅 Tomato | 60–80 % | 8 h full sun | 18–32 °C | 50–75 % |
| 🌿 Tulsi | 45–65 % | 4–6 h sun | 18–35 °C | 40–70 % |
| 🍋 Lemon | 30–50 % | 8+ h full sun | 15–32 °C | 50–70 % |

Moisture % depends on the calibration below — calibrate once for meaningful numbers. Edit `data/Plant.kt` to tune or add plants.

---

## 5. Build (no local setup needed)

Push this folder to a GitHub repo. The **Build firmware + APK** workflow runs on every push to `main` (or manually from the Actions tab) and produces two artifacts:
- `SmartPlant-apk` → `app-debug.apk` (signed with the committed `android/app/debug.keystore`, so updates install over the old version)
- `SmartPlantCare-firmware` → `.bin` files including `SmartPlantCare.ino.merged.bin`

A ready-to-flash build is also included at `firmware/prebuilt/SmartPlantCare-esp32c3-merged.bin` (Arduino-ESP32 core 3.3.12).

### Flashing the ESP32-C3 from a browser
1. Open **https://espressif.github.io/esptool-js/** in Chrome/Edge on a laptop.
2. Plug in the board by USB → **Connect** → choose the port. (If it won't connect: hold **BOOT**, tap **RESET**, release BOOT.)
3. Flash address **0x0**, file **`SmartPlantCare.ino.merged.bin`** (or the prebuilt one) → **Program**.
4. Press **RESET**. You'll hear one beep.

(Arduino IDE also works: board **ESP32C3 Dev Module**, *USB CDC On Boot: Enabled*, install libraries **DHT sensor library** + **Adafruit Unified Sensor**.)

---

## 6. First-time setup

1. Install the APK, open it, allow Bluetooth + notifications. It finds **SmartPlant** automatically (2 beeps on connect).
2. **Plants** tab → choose your plant.
3. **Settings → Soil calibration:** probe in dry air → **Set DRY**; probe in a glass of water (up to the line, not the electronics) → **Set WET**.
4. **Settings → Sunlight:** put the LDR in direct sun and tap *use current light* (or set the slider) so the sun counter knows what "direct sun" looks like for your module.
5. Put the pump in the water tank, route the tube to the pot, push the probe into the soil near the roots — away from where the tube drips.

---

## 7. BLE protocol (for reference)

Service `7a3e1000-5f2c-4b8e-9d41-0c8a5e6f1a01`

| Characteristic | UUID suffix | Use |
|---|---|---|
| Status | `…1001…` | Read/notify, JSON every 2 s: `{"m":52,"raw":2210,"t":29.4,"h":61,"l":78,"sun":184,"pump":0,"soak":0,"auto":1,"plant":"rose","min":40,"max":60,"al":0,…}` |
| Config | `…1002…` | Write `{"plant":"tomato","min":60,"max":80,"sunH":8,"sunTh":60}` |
| Command | `…1003…` | Write `{"cmd":"pump","on":1,"sec":10}` · `{"cmd":"auto","on":0}` · `{"cmd":"mute","on":1}` · `{"cmd":"beep"}` · `{"cmd":"calDry"}` · `{"cmd":"calWet"}` · `{"cmd":"calReset"}` · `{"cmd":"time","epoch":…,"tz":19800}` |

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| ESP32 resets when the pump starts | Power from a 5 V charger / buck converter, add a 100–470 µF cap on 5 V, keep pump wires away from sensor wires |
| Pump on when it should be off | Flip `RELAY_ACTIVE_LOW` |
| Relay never clicks | Some relays need 5 V logic on IN; use a module with an optocoupler/JD-VCC jumper or a small NPN transistor driver |
| Moisture stuck at 0 % or 100 % | Re-calibrate; check AO → GPIO3; sensor VCC on 3V3 |
| "Temperature sensor not responding" | DATA → GPIO10; bare 4-pin DHT11 needs a 10 kΩ pull-up DATA→3V3 |
| Light % goes the wrong way | Flip `LDR_INVERT` |
| App can't find the device | Phone Bluetooth on, within ~10 m, ESP32 powered; on Android 11 or older Location must be on for BLE scanning |

**Note:** alerts reach your phone while the app process is alive (foreground or recently backgrounded). The device itself keeps auto-watering and beeping regardless of the phone.
