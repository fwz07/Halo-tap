#include <HardwareSerial.h>
#include <TinyGPS++.h>
#include <BluetoothSerial.h>
#include <EEPROM.h>

// --- Objects ---
BluetoothSerial SerialBT;
HardwareSerial sim800(2);
HardwareSerial gpsSerial(1);
TinyGPSPlus gps;

// --- Pin Definitions ---
#define BUTTON_PIN 4
#define GREEN_LED 2
#define RED_LED 15

// --- Global Data ---
String parentName = "";
String childName = "";
String phone = "";
const String APN = "airtelgprs.com";
const String FIREBASE_URL_BASE = "https://halo-tap-default-rtdb.asia-southeast1.firebasedatabase.app/HaloTap/Locations";

// --- Timers & States ---
unsigned long lastFirebaseUpdate = 0;
const long firebaseInterval = 30000;
int locationIndex = 0;
unsigned long lastStatusCheck = 0;
unsigned long disconnectTimer = 0;

bool isSetupDone = false;
bool callIsActive = false;
unsigned long lastCallActivity = 0;
bool showingDisconnect = false;
String incomingMsg = "";

// --- Button Variables ---
unsigned long buttonPressStartTime = 0;
bool emergencyCallTriggered = false;

// --- Strict Phone Cleaner ---
String sanitizePhone(String input) {
  String clean = "";
  for (int i = 0; i < input.length(); i++) {
    if (isDigit(input[i]) || input[i] == '+') clean += input[i];
  }
  return clean;
}

// --- EEPROM Helpers ---
void writeStringToEEPROM(int addr, String str) {
  byte len = str.length();
  EEPROM.write(addr, len);
  for (int i = 0; i < len; i++) EEPROM.write(addr + 1 + i, str[i]);
}

String readStringFromEEPROM(int addr) {
  int len = EEPROM.read(addr);
  if (len <= 0 || len > 50) return "";
  char data[len + 1];
  for (int i = 0; i < len; i++) data[i] = EEPROM.read(addr + 1 + i);
  data[len] = '\0';
  return String(data);
}

void setup() {
  Serial.begin(115200);
  EEPROM.begin(512);

  sim800.begin(9600, SERIAL_8N1, 26, 27);
  gpsSerial.begin(9600, SERIAL_8N1, 16, 17);

  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(GREEN_LED, OUTPUT);
  pinMode(RED_LED, OUTPUT);

  digitalWrite(GREEN_LED, LOW);
  digitalWrite(RED_LED, LOW);

  // --- FACTORY RESET OVERRIDE ---
  if (digitalRead(BUTTON_PIN) == LOW) {
    Serial.println("FACTORY RESET DETECTED! Wiping Memory...");
    for (int i = 0; i < 512; i++) EEPROM.write(i, 0);
    EEPROM.commit();
    digitalWrite(RED_LED, HIGH);
    delay(2000);
  }

  SerialBT.begin("halotap");
  SerialBT.setTimeout(50);

  // Load and sanitize stored data
  phone = readStringFromEEPROM(100);
  phone = sanitizePhone(phone);

  if (phone.length() >= 8) {
    isSetupDone = true;
    parentName = readStringFromEEPROM(0);
    childName = readStringFromEEPROM(50);
    Serial.println("Stored Config Found! Phone: " + phone);

    // Shut down BT to save battery
    SerialBT.end();
    Serial.println("Bluetooth Antenna Disabled (Power Saving Mode Active)");
  } else {
    Serial.println("WAITING FOR APP SETUP (BT: halotap)...");
  }

  sim800.println("AT");
  delay(500);
  sim800.println("AT+CLIP=1");
  delay(500);
  sim800.println("AT+ATS0=1"); // Hardware Auto-Answer
  delay(500);

  sim800.println("AT+SAPBR=3,1,\"Contype\",\"GPRS\"");
  delay(500);
  sim800.println("AT+SAPBR=3,1,\"APN\",\"" + APN + "\"");
  delay(500);
  sim800.println("AT+SAPBR=1,1");
  delay(2000);
}

void handleBluetoothSetup() {
  if (SerialBT.available()) {
    String data = SerialBT.readStringUntil('#');

    // --- THE "READ" COMMAND ---
    if (data.startsWith("READ")) {
      String currentData = "*" + parentName + "|" + childName + "|" + phone + "#";
      SerialBT.println(currentData);
      Serial.println("App requested data. Sent to phone: " + currentData);
    }
    // --- THE "WRITE" COMMAND ---
    else if (data.startsWith("*")) {
      data.remove(0, 1);
      int p1 = data.indexOf('|');
      int p2 = data.indexOf('|', p1 + 1);

      parentName = data.substring(0, p1);
      childName = data.substring(p1 + 1, p2);

      String rawPhone = data.substring(p2 + 1);
      phone = sanitizePhone(rawPhone);

      writeStringToEEPROM(0, parentName);
      writeStringToEEPROM(50, childName);
      writeStringToEEPROM(100, phone);
      EEPROM.commit();

      isSetupDone = true;
      digitalWrite(RED_LED, LOW);
      digitalWrite(GREEN_LED, HIGH);

      SerialBT.println("SETUP_SUCCESS");
      delay(1000);
      SerialBT.end();

      digitalWrite(GREEN_LED, LOW);
      Serial.println("Setup Completed! Bluetooth Disabled. Phone saved as: " + phone);
    }
  }
}

// --- FLAWLESS NON-BLOCKING 2-SECOND SOS BUTTON ---
void checkSOSButton() {
  if (digitalRead(BUTTON_PIN) == LOW) {
    if (buttonPressStartTime == 0) {
      buttonPressStartTime = millis();
      Serial.println("SOS Button Pressed. Counting to 2...");
    }
    else if ((millis() - buttonPressStartTime > 2000) && !callIsActive && !emergencyCallTriggered) {
      Serial.println("\n!!! 2 SECONDS REACHED. TRIGGERING CALL !!!");
      emergencyCallTriggered = true;
      buttonPressStartTime = millis() + 5000;
    }
  } else {
    if (buttonPressStartTime != 0 && millis() > buttonPressStartTime && !emergencyCallTriggered) {
      buttonPressStartTime = 0;
    }
  }
}

bool smartDelay(unsigned long ms) {
  unsigned long start = millis();
  while (millis() - start < ms) {
    while (gpsSerial.available() > 0) gps.encode(gpsSerial.read());
    checkSOSButton();
    if (emergencyCallTriggered) return false;
    delay(10);
  }
  return true;
}

void pushLocationToFirebase(float lat, float lng) {
  if (!isSetupDone) return;

  Serial.println("\n[FIREBASE] Starting upload process...");

  String fullUrl = FIREBASE_URL_BASE + "/" + String(locationIndex) + ".json";

  // --- THE FIREBASE SERVER TIME FIX ---
  // Tells Google to stamp the entry with their own atomic clock!
  String payload = "{\"lat\":" + String(lat, 6) + ",\"lng\":" + String(lng, 6) + ",\"child\":\"" + childName + "\",\"timestamp\":{\".sv\":\"timestamp\"}}";

  sim800.println("AT+HTTPTERM");
  if (!smartDelay(200)) return;
  sim800.println("AT+HTTPINIT");
  if (!smartDelay(500)) return;
  sim800.println("AT+HTTPSSL=1");
  if (!smartDelay(500)) return;
  sim800.println("AT+HTTPPARA=\"CID\",1");
  if (!smartDelay(300)) return;
  sim800.println("AT+HTTPPARA=\"URL\",\"" + fullUrl + "\"");
  if (!smartDelay(300)) return;
  sim800.println("AT+HTTPPARA=\"CONTENT\",\"application/json\"");
  if (!smartDelay(300)) return;
  sim800.println("AT+HTTPPARA=\"USERDATA\",\"X-HTTP-Method-Override: PUT\"");
  if (!smartDelay(300)) return;

  sim800.print("AT+HTTPDATA=");
  sim800.print(payload.length());
  sim800.println(",5000");
  if (!smartDelay(500)) return;
  sim800.println(payload);
  if (!smartDelay(500)) return;

  Serial.println("[FIREBASE] Payload sent. Waiting for Google's response...");
  sim800.println("AT+HTTPACTION=1");

  // --- PRINT THE ACTUAL SERVER RESPONSE ---
  unsigned long waitStart = millis();
  while (millis() - waitStart < 5000) {
    while (sim800.available()) {
      Serial.print((char)sim800.read());
    }
    checkSOSButton();
    if (emergencyCallTriggered) return;
    delay(10);
  }
  Serial.println("[FIREBASE] Upload attempt finished.\n");

  sim800.println("AT+HTTPTERM");

  locationIndex++;
  if (locationIndex >= 10) locationIndex = 0;
}

void loop() {
  if (!isSetupDone) {
    if (millis() % 1000 < 500) digitalWrite(RED_LED, HIGH);
    else digitalWrite(RED_LED, LOW);

    handleBluetoothSetup();
    return;
  }

  if (!callIsActive && !showingDisconnect) digitalWrite(RED_LED, LOW);

  while (gpsSerial.available() > 0) gps.encode(gpsSerial.read());

  checkSOSButton();

  // --- THE SOS DIAL LOGIC WITH CRITICAL DELAYS & DIAGNOSTICS ---
  if (emergencyCallTriggered) {
    digitalWrite(GREEN_LED, HIGH);
    digitalWrite(RED_LED, HIGH);

    Serial.println("\n=========================================");
    Serial.println("       !!! TRIGGERING SOS !!!            ");
    Serial.print("EXACT NUMBER LOADED: [");
    Serial.print(phone);
    Serial.println("]");
    Serial.print("FULL COMMAND SENT:   [ATD");
    Serial.print(phone);
    Serial.println(";]");
    Serial.println("=========================================\n");

    sim800.println("AT+HTTPTERM");
    delay(300);
    sim800.println("AT+SAPBR=0,1");

    // --- THE CRITICAL FIX: LET THE MODEM BREATHE ---
    Serial.println("Closing GPRS... Waiting 1.5s for tower to switch modes...");
    delay(1500);

    while(sim800.available()) sim800.read();

    sim800.println("ATD" + phone + ";");

    callIsActive = true;
    lastCallActivity = millis();
    lastStatusCheck = millis() + 2000;
    emergencyCallTriggered = false;
  }

  // --- FIREBASE 30-SECOND TIMER WITH DIAGNOSTICS ---
  if (!callIsActive && !emergencyCallTriggered && (millis() - lastFirebaseUpdate > firebaseInterval)) {
    Serial.println("\n--- 30 SECOND TIMER HIT ---");
    if (gps.location.isValid()) {
      sim800.println("AT+SAPBR=1,1");
      delay(1000);
      pushLocationToFirebase(gps.location.lat(), gps.location.lng());
    } else {
      Serial.println("[WARNING] Upload skipped: GPS module cannot see satellites right now.");
    }
    lastFirebaseUpdate = millis();
  }

  // Request status every 1 second, but wait only 4s after dial to prevent hanging up
  if (callIsActive && (millis() - lastCallActivity > 4000) && (millis() - lastStatusCheck > 1000)) {
    sim800.println("AT+CLCC");
    lastStatusCheck = millis();
  }

  while (sim800.available()) {
    char c = sim800.read();
    incomingMsg += c;
    if (c == '\n') {

      if (incomingMsg.indexOf("RING") != -1) {
        sim800.println("ATA");
        callIsActive = true;

        // --- THE INCOMING CALL SPEED FIX ---
        // Trick the ESP32 into bypassing the 4-second dialing delay!
        lastCallActivity = millis() - 4000;

        digitalWrite(GREEN_LED, HIGH);
        digitalWrite(RED_LED, HIGH);
      }

      if (incomingMsg.indexOf("+CLCC:") != -1) {
        lastCallActivity = millis();

        // Exact match for Active (Answered) calls only
        if (incomingMsg.indexOf(",0,0,0,0") != -1 || incomingMsg.indexOf(",1,0,0,0") != -1) {
          digitalWrite(GREEN_LED, HIGH);
          digitalWrite(RED_LED, LOW);
        } else {
          digitalWrite(GREEN_LED, HIGH);
          digitalWrite(RED_LED, HIGH);
        }
      }

      if (incomingMsg.indexOf("NO CARRIER") != -1 || incomingMsg.indexOf("BUSY") != -1) {
        callIsActive = false;
        digitalWrite(GREEN_LED, LOW);
        digitalWrite(RED_LED, HIGH);
        showingDisconnect = true;
        disconnectTimer = millis();
      }
      incomingMsg = "";
    }
  }

  if (showingDisconnect && (millis() - disconnectTimer > 2000)) {
    digitalWrite(RED_LED, LOW);
    showingDisconnect = false;
  }

  if (callIsActive && (millis() - lastCallActivity > 30000)) {
    callIsActive = false;
    digitalWrite(GREEN_LED, LOW);
    digitalWrite(RED_LED, HIGH);
    showingDisconnect = true;
    disconnectTimer = millis();
  }
}
