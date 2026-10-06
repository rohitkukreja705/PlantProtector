/*
 * Smart Plant Care — ESP32-C3 firmware
 * ------------------------------------
 * Sensors : Soil moisture (analog), DHT11 (air temp/humidity), LDR module (analog light)
 * Outputs : 1-channel relay -> 5V water pump, buzzer module
 * Link    : Bluetooth Low Energy (GATT) to the "Smart Plant" Android app
 *
 * Features
 *  - Per-plant moisture window (min/max %) pushed from the app, stored in flash
 *  - Auto-watering in short bursts with soak time (no flooding)
 *  - Dry-run / empty-tank protection: stops auto mode if bursts don't raise moisture
 *  - Manual pump control from the app (hard-capped run time)
 *  - Sunlight-minutes counter (resets daily) vs the plant's sun-hours requirement
 *  - Soil sensor calibration (dry / wet) from the app
 *  - Buzzer alerts: very dry soil, empty tank, BLE connect, pump start
 *
 * Board   : "ESP32C3 Dev Module" (Arduino-ESP32 core 3.x; 2.0.x also works)
 *           Tools > USB CDC On Boot: Enabled (for Serial Monitor over USB)
 * Libraries: "DHT sensor library" + "Adafruit Unified Sensor" (Adafruit)
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <DHT.h>
#include <sys/time.h>
#include <time.h>

// ============================== PINS ==============================
// ESP32-C3: only GPIO0-4 have ADC1 (use these for analog sensors).
// Avoid GPIO2/8/9 (strapping) and GPIO18/19 (USB) for peripherals.
#define PIN_SOIL     3    // Soil moisture AO  (ADC1_CH3)
#define PIN_LDR      4    // LDR module AO     (ADC1_CH4)
#define PIN_RELAY    6    // Relay IN
#define PIN_BUZZER   7    // Buzzer I/O (or + of a bare active buzzer)
#define PIN_DHT      10   // DHT11 DATA

#define DHT_TYPE     DHT11

// Most blue 1-channel relay modules switch ON when IN is pulled LOW.
#define RELAY_ACTIVE_LOW   true
// 3-pin "active buzzer modules" are often LOW-trigger; bare buzzers are HIGH.
#define BUZZER_ACTIVE_LOW  false
// Typical LDR modules: AO voltage DROPS as light increases -> invert.
#define LDR_INVERT         true

// ============================ TUNING ==============================
#define PUMP_BURST_MS        4000UL    // one auto-watering burst
#define SOAK_MS              45000UL   // wait for water to spread before re-checking
#define MAX_BURSTS           6         // bursts without improvement => tank empty?
#define MIN_RISE_PCT         3         // moisture must rise this much over the cycle
#define MANUAL_MAX_MS        30000UL   // hard cap for manual pump runs
#define MANUAL_HOLD_MS       1800000UL // pause auto-watering 30 min after manual Stop/Water
#define SENSOR_PERIOD_MS     1000UL
#define DHT_PERIOD_MS        3000UL    // DHT11 needs >= 1 s between reads
#define NOTIFY_PERIOD_MS     2000UL
#define SUN_SAMPLE_MS        60000UL   // sunlight counted per minute
#define PERSIST_PERIOD_MS    600000UL  // save sun counter every 10 min
#define DRY_ALARM_REPEAT_MS  60000UL
#define TANK_ALARM_REPEAT_MS 30000UL

// ============================== BLE ===============================
#define DEVICE_NAME   "SmartPlant"
#define SERVICE_UUID  "7a3e1000-5f2c-4b8e-9d41-0c8a5e6f1a01"
#define STATUS_UUID   "7a3e1001-5f2c-4b8e-9d41-0c8a5e6f1a01"  // read + notify (JSON)
#define CONFIG_UUID   "7a3e1002-5f2c-4b8e-9d41-0c8a5e6f1a01"  // write (JSON plant profile)
#define COMMAND_UUID  "7a3e1003-5f2c-4b8e-9d41-0c8a5e6f1a01"  // write (JSON command)

// ============================= STATE ==============================
DHT dht(PIN_DHT, DHT_TYPE);
Preferences prefs;

BLEServer*         bleServer   = nullptr;
BLECharacteristic* statusChar  = nullptr;
volatile bool      bleConnected = false;
volatile bool      justConnected = false;

// Plant profile (sent by app, persisted)
String   plantId     = "rose";
int      moistMin    = 40;     // %
int      moistMax    = 60;     // %
float    sunHoursReq = 6.0;    // hours/day
int      sunThresh   = 60;     // light % that counts as "direct sun"

// Calibration (raw ADC, 12-bit)
int calDry = 3300;             // raw value in dry air / bone-dry soil
int calWet = 1300;             // raw value in a glass of water / saturated soil

// Live readings
int   soilRaw = 0, soilPct = 0;
int   ldrRaw = 0, lightPct = 0;
float airTemp = NAN, airHum = NAN;
bool  dhtOk = false;
bool  soilFault = false;

// Modes / flags
bool autoMode   = true;
bool muted      = false;
bool tankEmpty  = false;       // set by dry-run protection
bool autoHold   = false;       // auto paused after manual pump control
unsigned long autoHoldUntil = 0;


// Pump state machine
enum PumpState { PUMP_IDLE, PUMP_BURST, PUMP_SOAK, PUMP_MANUAL };
PumpState pumpState = PUMP_IDLE;
unsigned long pumpStateAt = 0;
unsigned long manualRunMs = 0;
int  burstCount   = 0;
int  cycleStartPct = 0;
uint32_t pumpRunsToday = 0;

// Sunlight
uint32_t sunMinutes = 0;
long     sunDayId   = -1;      // local day number when time is synced
unsigned long bootDayStart = 0;
bool     timeSynced = false;
long     tzOffsetSec = 19800;  // IST default, app overrides

// Timers
unsigned long tSensor = 0, tDht = 0, tNotify = 0, tSun = 0, tPersist = 0;
unsigned long tDryAlarm = 0, tTankAlarm = 0;

void startAutoHold() { autoHold = true; autoHoldUntil = millis() + MANUAL_HOLD_MS; }
int  autoHoldMinutesLeft() {
  if (!autoHold) return 0;
  long left = (long)(autoHoldUntil - millis());
  return left > 0 ? (int)((left + 59999) / 60000) : 0;
}

// ============================ BUZZER ==============================
// Non-blocking pattern player: list of on/off durations in ms.
struct Beeper {
  uint16_t seq[16];
  uint8_t  len = 0, idx = 0;
  bool     on = false;
  unsigned long at = 0;
} beeper;

void buzzerWrite(bool on) {
  digitalWrite(PIN_BUZZER, (on ^ BUZZER_ACTIVE_LOW) ? HIGH : LOW);
}

// pattern: on,off,on,off... (ms)
void beep(const uint16_t* pattern, uint8_t n, bool force = false) {
  if (muted && !force) return;
  n = min<uint8_t>(n, 16);
  memcpy(beeper.seq, pattern, n * sizeof(uint16_t));
  beeper.len = n; beeper.idx = 0; beeper.on = true; beeper.at = millis();
  buzzerWrite(true);
}

void beeperTick() {
  if (beeper.len == 0) return;
  if (millis() - beeper.at < beeper.seq[beeper.idx]) return;
  beeper.idx++;
  beeper.at = millis();
  if (beeper.idx >= beeper.len) { beeper.len = 0; buzzerWrite(false); return; }
  beeper.on = !beeper.on;
  buzzerWrite(beeper.on);
}

const uint16_t BEEP_SHORT[]   = {80};
const uint16_t BEEP_DOUBLE[]  = {80, 100, 80};
const uint16_t BEEP_DRY[]     = {200, 150, 200, 150, 200};
const uint16_t BEEP_TANK[]    = {700, 200, 700};
const uint16_t BEEP_FIND[]    = {150, 100, 150, 100, 150, 100, 150, 100, 400};

// ============================= PUMP ===============================
void relayWrite(bool on) {
  digitalWrite(PIN_RELAY, (on ^ RELAY_ACTIVE_LOW) ? HIGH : LOW);
}

bool pumpIsOn() { return pumpState == PUMP_BURST || pumpState == PUMP_MANUAL; }

void pumpStart(PumpState s) {
  pumpState = s;
  pumpStateAt = millis();
  relayWrite(true);
  pumpRunsToday++;
  beep(BEEP_SHORT, 1);
  Serial.printf("[pump] ON (%s)\n", s == PUMP_MANUAL ? "manual" : "auto");
}

void pumpStop(PumpState next) {
  relayWrite(false);
  pumpState = next;
  pumpStateAt = millis();
  Serial.println("[pump] OFF");
}

// ============================ SENSORS =============================
int readAdcAvg(int pin, int samples = 16) {
  long sum = 0;
  for (int i = 0; i < samples; i++) { sum += analogRead(pin); delayMicroseconds(200); }
  return sum / samples;
}

int soilRawToPct(int raw) {
  if (calDry == calWet) return 0;
  long pct = (long)(calDry - raw) * 100L / (calDry - calWet);
  return constrain((int)pct, 0, 100);
}

void readAnalogSensors() {
  soilRaw  = readAdcAvg(PIN_SOIL);
  soilPct  = soilRawToPct(soilRaw);
  // A reading pinned near 0 almost always means a short / wrong wiring.
  soilFault = soilRaw < 20;

  ldrRaw   = readAdcAvg(PIN_LDR);
  int p    = (int)((long)ldrRaw * 100L / 4095L);
  lightPct = LDR_INVERT ? 100 - p : p;
}

void readDht() {
  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (isnan(t) || isnan(h)) { dhtOk = false; return; }
  airTemp = t; airHum = h; dhtOk = true;
}

// ========================= SUNLIGHT / TIME ========================
long currentLocalDay() {
  time_t now = time(nullptr);
  return (long)((now + tzOffsetSec) / 86400L);
}

void resetSunDay(long day) {
  sunMinutes = 0;
  pumpRunsToday = 0;
  sunDayId = day;
  prefs.putUInt("sunMin", 0);
  prefs.putLong("sunDay", day);
}

void sunTick() {
  // Day rollover
  if (timeSynced) {
    long d = currentLocalDay();
    if (d != sunDayId) resetSunDay(d);
  } else if (millis() - bootDayStart >= 86400000UL) {
    bootDayStart = millis();
    resetSunDay(-1);
  }
  if (lightPct >= sunThresh) sunMinutes++;
}

// ============================ AUTO MODE ===========================
void autoWaterTick() {
  unsigned long now = millis();

  switch (pumpState) {
    case PUMP_MANUAL:
      if (now - pumpStateAt >= manualRunMs) pumpStop(PUMP_IDLE);
      return;

    case PUMP_BURST:
      if (now - pumpStateAt >= PUMP_BURST_MS) pumpStop(PUMP_SOAK);
      return;

    case PUMP_SOAK:
      if (now - pumpStateAt < SOAK_MS) return;
      // Soak finished: evaluate
      if (soilPct >= moistMin + (moistMax - moistMin) / 2) {
        // Reached the middle of the window — cycle done
        Serial.printf("[auto] done after %d bursts (%d%%)\n", burstCount, soilPct);
        burstCount = 0;
        pumpState = PUMP_IDLE;
        return;
      }
      if (burstCount >= MAX_BURSTS) {
        if (soilPct - cycleStartPct < MIN_RISE_PCT) {
          // Water isn't reaching the soil: empty tank, kinked pipe or bad sensor
          tankEmpty = true;
          autoMode = false;
          prefs.putBool("auto", false);
          beep(BEEP_TANK, 3, true);
          Serial.println("[auto] no moisture rise — tank empty? auto disabled");
        }
        burstCount = 0;
        pumpState = PUMP_IDLE;
        return;
      }
      if (!autoMode || tankEmpty || soilFault) { burstCount = 0; pumpState = PUMP_IDLE; return; }
      burstCount++;
      pumpStart(PUMP_BURST);
      return;

    case PUMP_IDLE:
      if (!autoMode || tankEmpty || soilFault) return;
      if (autoHold) {
        if ((long)(millis() - autoHoldUntil) < 0) return;   // still paused
        autoHold = false;
      }
      if (soilPct < moistMin) {
        cycleStartPct = soilPct;
        burstCount = 1;
        pumpStart(PUMP_BURST);
      }
      return;
  }
}

// ============================= ALARMS =============================
int alarmFlags() {
  // bit0 dry, bit1 very dry, bit2 overwatered, bit3 tank empty,
  // bit4 soil sensor fault, bit5 DHT fault, bit6 hot (>40C), bit7 cold (<5C)
  int f = 0;
  if (soilPct < moistMin) f |= 1;
  if (soilPct < moistMin - 15) f |= 2;
  if (soilPct > moistMax + 15) f |= 4;
  if (tankEmpty) f |= 8;
  if (soilFault) f |= 16;
  if (!dhtOk) f |= 32;
  if (dhtOk && airTemp > 40) f |= 64;
  if (dhtOk && airTemp < 5) f |= 128;
  return f;
}

void alarmTick() {
  unsigned long now = millis();
  int f = alarmFlags();
  if (tankEmpty) {
    if (now - tTankAlarm >= TANK_ALARM_REPEAT_MS) { tTankAlarm = now; beep(BEEP_TANK, 3); }
  } else if ((f & 2) && pumpState == PUMP_IDLE) {
    if (now - tDryAlarm >= DRY_ALARM_REPEAT_MS) { tDryAlarm = now; beep(BEEP_DRY, 5); }
  }
}

// ========================== JSON HELPERS ==========================
// Tiny parsers for the flat JSON objects the app sends, e.g.
// {"plant":"rose","min":40,"max":60,"sunH":6}
bool jsonHas(const String& j, const char* key) {
  return j.indexOf(String("\"") + key + "\"") >= 0;
}

double jsonNum(const String& j, const char* key, double def) {
  int k = j.indexOf(String("\"") + key + "\"");
  if (k < 0) return def;
  int c = j.indexOf(':', k);
  if (c < 0) return def;
  int s = c + 1;
  while (s < (int)j.length() && (j[s] == ' ' || j[s] == '"')) s++;
  int e = s;
  while (e < (int)j.length() && (isdigit(j[e]) || j[e] == '-' || j[e] == '.')) e++;
  if (e == s) {
    if (j.substring(s, s + 4) == "true") return 1;
    if (j.substring(s, s + 5) == "false") return 0;
    return def;
  }
  return j.substring(s, e).toDouble();
}

String jsonStr(const String& j, const char* key, const String& def) {
  int k = j.indexOf(String("\"") + key + "\"");
  if (k < 0) return def;
  int c = j.indexOf(':', k);
  int q1 = j.indexOf('"', c + 1);
  int q2 = j.indexOf('"', q1 + 1);
  if (c < 0 || q1 < 0 || q2 < 0) return def;
  return j.substring(q1 + 1, q2);
}

// ========================== STATUS JSON ===========================
String buildStatus() {
  char buf[400];
  char tbuf[12], hbuf[12];
  if (dhtOk) { dtostrf(airTemp, 0, 1, tbuf); dtostrf(airHum, 0, 0, hbuf); }
  else { strcpy(tbuf, "null"); strcpy(hbuf, "null"); }

  snprintf(buf, sizeof(buf),
    "{\"m\":%d,\"raw\":%d,\"t\":%s,\"h\":%s,\"l\":%d,\"lraw\":%d,"
    "\"sun\":%u,\"pump\":%d,\"soak\":%d,\"auto\":%d,\"mute\":%d,"
    "\"plant\":\"%s\",\"min\":%d,\"max\":%d,\"sunH\":%.1f,\"sunTh\":%d,"
    "\"dry\":%d,\"wet\":%d,\"runs\":%u,\"al\":%d,\"ts\":%d,\"hold\":%d,\"up\":%lu}",
    soilPct, soilRaw, tbuf, hbuf, lightPct, ldrRaw,
    sunMinutes, pumpIsOn() ? 1 : 0, pumpState == PUMP_SOAK ? 1 : 0,
    autoMode ? 1 : 0, muted ? 1 : 0,
    plantId.c_str(), moistMin, moistMax, sunHoursReq, sunThresh,
    calDry, calWet, pumpRunsToday, alarmFlags(), timeSynced ? 1 : 0, autoHoldMinutesLeft(),
    millis() / 1000UL);
  return String(buf);
}

void notifyStatus() {
  if (!statusChar) return;
  String s = buildStatus();
  statusChar->setValue(s.c_str());
  if (bleConnected) statusChar->notify();
}

// ======================== BLE CALLBACKS ===========================
class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override { bleConnected = true; justConnected = true; }
  void onDisconnect(BLEServer* s) override {
    bleConnected = false;
    BLEDevice::startAdvertising();   // allow reconnect
  }
};

void saveProfile() {
  prefs.putString("plant", plantId);
  prefs.putInt("min", moistMin);
  prefs.putInt("max", moistMax);
  prefs.putFloat("sunH", sunHoursReq);
  prefs.putInt("sunTh", sunThresh);
}

// BLE writes arrive on the Bluetooth task; queue them and handle them in loop().
struct BleMsg { uint8_t type; char data[240]; };   // type 1 = config, 2 = command
QueueHandle_t bleQueue;

void enqueue(uint8_t type, BLECharacteristic* c) {
  BleMsg m; m.type = type;
  String v = String(c->getValue().c_str());
  strncpy(m.data, v.c_str(), sizeof(m.data) - 1);
  m.data[sizeof(m.data) - 1] = 0;
  xQueueSend(bleQueue, &m, 0);
}

class ConfigCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override { enqueue(1, c); }
};
class CommandCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override { enqueue(2, c); }
};

void handleConfig(const String& j) {
    Serial.println("[cfg] " + j);
    String newPlant = jsonStr(j, "plant", plantId);
    int mn = (int)jsonNum(j, "min", moistMin);
    int mx = (int)jsonNum(j, "max", moistMax);
    if (mn < 0 || mx > 100 || mn >= mx) return;     // reject nonsense
    plantId = newPlant;
    moistMin = mn;
    moistMax = mx;
    sunHoursReq = (float)jsonNum(j, "sunH", sunHoursReq);
    sunThresh   = constrain((int)jsonNum(j, "sunTh", sunThresh), 5, 100);
    saveProfile();
    burstCount = 0;
    beep(BEEP_SHORT, 1);
    notifyStatus();
}

void handleCommand(const String& j) {
    Serial.println("[cmd] " + j);
    String cmd = jsonStr(j, "cmd", "");

    if (cmd == "pump") {
      bool on = jsonNum(j, "on", 0) != 0;
      if (on) {
        unsigned long sec = (unsigned long)jsonNum(j, "sec", 5);
        manualRunMs = min(sec * 1000UL, MANUAL_MAX_MS);
        burstCount = 0;
        startAutoHold();                        // let the water soak in before auto re-checks
        pumpStart(PUMP_MANUAL);
      } else {
        burstCount = 0;
        startAutoHold();                        // Stop means stop: don't restart right away
        if (pumpIsOn() || pumpState == PUMP_SOAK) pumpStop(PUMP_IDLE);
      }
    } else if (cmd == "auto") {
      autoMode = jsonNum(j, "on", 1) != 0;
      if (autoMode) { tankEmpty = false; autoHold = false; }  // re-enabling clears lockout + pause
      prefs.putBool("auto", autoMode);
      if (!autoMode && pumpState != PUMP_MANUAL && pumpState != PUMP_IDLE) pumpStop(PUMP_IDLE);
    } else if (cmd == "resetTank") {
      tankEmpty = false;
    } else if (cmd == "mute") {
      muted = jsonNum(j, "on", 1) != 0;
      prefs.putBool("mute", muted);
      if (muted) { beeper.len = 0; buzzerWrite(false); }
    } else if (cmd == "beep") {
      beep(BEEP_FIND, 9, true);                 // "find my device"
    } else if (cmd == "calDry") {
      calDry = readAdcAvg(PIN_SOIL, 64);
      prefs.putInt("calDry", calDry);
    } else if (cmd == "calWet") {
      calWet = readAdcAvg(PIN_SOIL, 64);
      prefs.putInt("calWet", calWet);
    } else if (cmd == "calReset") {
      calDry = 3300; calWet = 1300;
      prefs.putInt("calDry", calDry); prefs.putInt("calWet", calWet);
    } else if (cmd == "time") {
      long long epoch = (long long)jsonNum(j, "epoch", 0);
      tzOffsetSec = (long)jsonNum(j, "tz", tzOffsetSec);
      if (epoch > 1600000000LL) {
        struct timeval tv = { (time_t)epoch, 0 };
        settimeofday(&tv, nullptr);
        timeSynced = true;
        long d = currentLocalDay();
        if (sunDayId == -1) {                   // first sync: keep minutes counted so far
          sunDayId = d;
          prefs.putLong("sunDay", d);
        } else if (sunDayId != d) {
          resetSunDay(d);                       // a new day started since last save
        }
      }
    }
    notifyStatus();
}

void setupBle() {
  bleQueue = xQueueCreate(6, sizeof(BleMsg));
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(517);   // status JSON is ~330 bytes; must fit in one notification
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCB());

  BLEService* svc = bleServer->createService(SERVICE_UUID);

  statusChar = svc->createCharacteristic(STATUS_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
#if defined(CONFIG_BLUEDROID_ENABLED)
  statusChar->addDescriptor(new BLE2902());   // NimBLE (core 3.3+) adds it automatically
#endif

  BLECharacteristic* cfg = svc->createCharacteristic(CONFIG_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cfg->setCallbacks(new ConfigCB());

  BLECharacteristic* cmd = svc->createCharacteristic(COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cmd->setCallbacks(new CommandCB());

  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("[ble] advertising as " DEVICE_NAME);
}

// ============================== SETUP =============================
void setup() {
  // Drive outputs to OFF *before* enabling them, so the pump never twitches at boot.
  relayWrite(false);
  pinMode(PIN_RELAY, OUTPUT);
  relayWrite(false);
  buzzerWrite(false);
  pinMode(PIN_BUZZER, OUTPUT);
  buzzerWrite(false);

  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== Smart Plant Care ===");

  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);   // full 0-3.3 V range

  dht.begin();

  prefs.begin("plant", false);
  plantId     = prefs.getString("plant", plantId);
  moistMin    = prefs.getInt("min", moistMin);
  moistMax    = prefs.getInt("max", moistMax);
  sunHoursReq = prefs.getFloat("sunH", sunHoursReq);
  sunThresh   = prefs.getInt("sunTh", sunThresh);
  calDry      = prefs.getInt("calDry", calDry);
  calWet      = prefs.getInt("calWet", calWet);
  autoMode    = prefs.getBool("auto", true);
  muted       = prefs.getBool("mute", false);
  sunMinutes  = prefs.getUInt("sunMin", 0);
  sunDayId    = prefs.getLong("sunDay", -1);

  readAnalogSensors();
  readDht();
  setupBle();

  bootDayStart = millis();
  beep(BEEP_SHORT, 1);
}

// =============================== LOOP =============================
void loop() {
  unsigned long now = millis();

  if (now - tSensor >= SENSOR_PERIOD_MS) { tSensor = now; readAnalogSensors(); }
  if (now - tDht >= DHT_PERIOD_MS)       { tDht = now; readDht(); }
  if (now - tSun >= SUN_SAMPLE_MS)       { tSun = now; sunTick(); }
  if (now - tPersist >= PERSIST_PERIOD_MS) {
    tPersist = now;
    prefs.putUInt("sunMin", sunMinutes);
    prefs.putLong("sunDay", sunDayId);
  }

  BleMsg m;
  while (xQueueReceive(bleQueue, &m, 0) == pdTRUE) {
    if (m.type == 1) handleConfig(String(m.data));
    else             handleCommand(String(m.data));
  }

  autoWaterTick();
  alarmTick();
  beeperTick();

  if (justConnected) { justConnected = false; beep(BEEP_DOUBLE, 3); notifyStatus(); }
  if (now - tNotify >= NOTIFY_PERIOD_MS) {
    tNotify = now;
    notifyStatus();
    Serial.println(buildStatus());
  }

  delay(5);
}
