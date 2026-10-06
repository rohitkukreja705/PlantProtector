/*
 * Smart Plant Care — wiring test for ESP32-C3 Super Mini
 * ------------------------------------------------------
 * Flash this, open Serial Monitor at 115200, and read the report.
 *  1. Prints the raw analog value on every ADC pin (GPIO0-4) every second.
 *     -> touch / dip / cover each sensor and see which pin moves.
 *  2. Searches every safe GPIO for a DHT11 and says where it found one.
 *  3. Clicks the relay (GPIO6) and beeps the buzzer (GPIO7) once at start.
 *
 * Needs: "DHT sensor library" + "Adafruit Unified Sensor".
 * Board: ESP32C3 Dev Module, USB CDC On Boot: Enabled.
 */
#include <DHT.h>

const int ADC_PINS[]  = {0, 1, 2, 3, 4};
// Pins we try for the DHT11. Skips 6/7 (relay/buzzer), 8 (LED), 9 (BOOT), 18/19 (USB).
const int DHT_PINS[]  = {10, 0, 1, 2, 3, 4, 5, 20, 21};
const int PIN_RELAY   = 6;
const int PIN_BUZZER  = 7;

void findDht() {
  Serial.println("\n--- Searching for DHT11 ---");
  bool found = false;
  for (int p : DHT_PINS) {
    DHT d(p, DHT11);
    d.begin();
    delay(1200);                       // DHT11 needs >1 s after power/begin
    float t = d.readTemperature();
    float h = d.readHumidity();
    if (!isnan(t) && !isnan(h)) {
      Serial.printf("  FOUND on GPIO%d  ->  %.1f C, %.0f %%\n", p, t, h);
      if (p != 10) Serial.println("  ** Firmware expects GPIO10: move the DATA wire to pin 10. **");
      found = true;
    } else {
      Serial.printf("  GPIO%-2d : no reply\n", p);
    }
    pinMode(p, INPUT);
  }
  if (!found) {
    Serial.println("  Not found on any pin. Check:");
    Serial.println("   - module VCC(+) -> 3V3, GND(-) -> GND, DATA(S/OUT) -> GPIO10");
    Serial.println("   - wires firmly seated; try another jumper for DATA");
    Serial.println("   - bare 4-pin DHT11 needs a 10k resistor DATA->3V3");
  }
}

void setup() {
  // Relay off (active-LOW module) and buzzer off before enabling outputs
  digitalWrite(PIN_RELAY, HIGH);  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_BUZZER, LOW);  pinMode(PIN_BUZZER, OUTPUT);

  Serial.begin(115200);
  delay(1500);
  Serial.println("\n===== Smart Plant wiring test =====");

  Serial.println("Relay ON for 1 s (listen for 2 clicks)...");
  digitalWrite(PIN_RELAY, LOW);  delay(1000);  digitalWrite(PIN_RELAY, HIGH);

  Serial.println("Buzzer beep...");
  digitalWrite(PIN_BUZZER, HIGH); delay(200); digitalWrite(PIN_BUZZER, LOW);

  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);

  findDht();

  Serial.println("\n--- Analog pins (0 = 0 V, 4095 = 3.3 V) ---");
  Serial.println("Soil should be on GPIO3, LDR on GPIO4.");
  Serial.println("Dip the probe in water / shine a torch on the LDR and watch which column changes.\n");
  Serial.println(" GPIO0  GPIO1  GPIO2  GPIO3  GPIO4");
}

void loop() {
  for (int p : ADC_PINS) Serial.printf("%6d ", analogRead(p));
  Serial.println();
  delay(1000);
}
