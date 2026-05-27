# HaloTap Android Application 🛡️

HaloTap is a robust, modern safety and tracking ecosystem. This Android application serves as the primary interface for parents and guardians to monitor the real-time location of the HaloTap wearable device, manage safe zones, and maintain emergency configurations.

## 🚀 Key Features

### 📍 Intelligent Tracking
- **Real-time Live GPS**: High-precision tracking via Firebase Realtime Database.
- **Location History**: Persistent tracking even when the app is offline.
- **Multi-Map Styles**: Toggle between Standard, Satellite, and Terrain views.

### 🛡️ Safe Zone Management (Geofencing)
- **Custom Geofences**: Create multiple circular safe zones (Home, School, Play) with custom radii.
- **Instant Alerts**: Receive `POST_NOTIFICATIONS` and vibration alerts immediately when the device exits a safe zone.
- **Zone Management**: Interactive dashboard to view, zoom to, or delete active zones.

### 📶 Advanced Connectivity (Modern Implementation)
- **NSD (Network Service Discovery)**: Migrated to **Android 14 (API 34)** standards using `registerServiceInfoCallback` for reliable local discovery.
- **Bluetooth SPP Sync**: Secure synchronization of parent/child names and SOS numbers to the hardware.
- **Seamless Handover**: Intelligent logic handles the transition from Bluetooth setup to WiFi/Firebase tracking.

### 🎨 Premium UI/UX
- **Modern Dashboard**: Clean, Material Design 3 interface.
- **Interactive Animations**: Premium "Button Fill" expansion effects and map control animations.
- **Emergency SOS**: Quick-access calling feature directly from the dashboard.

## 🛠 Tech Stack

- **Kotlin**: Primary development language.
- **Firebase**: Realtime Database for location syncing.
- **Google Maps SDK**: For spatial visualization and geofencing.
- **Modern Android APIs**: Utilizing the latest Lifecycle, Permission, and Discovery APIs.

## 🔌 Hardware Wiring (ESP32)

To build the wearable device, connect the components as follows. Ensure a **Common Ground** between all modules.

### **Connection Table**

| Component | Pin / Wire | Connects To | Important Notes |
| :--- | :--- | :--- | :--- |
| **SOS Button** | Leg 1 | ESP32 GPIO 4 | |
| | Leg 2 | ESP32 GND | No resistor needed (code uses internal pullup). |
| **Green LED** | Anode (Long Leg) | ESP32 GPIO 2 | Add a 220Ω or 330Ω resistor in series to prevent burnout. |
| | Cathode (Short Leg) | ESP32 GND | |
| **Red LED** | Anode (Long Leg) | ESP32 GPIO 15 | Add a 220Ω or 330Ω resistor in series. |
| | Cathode (Short Leg) | ESP32 GND | |
| **Neo-6M GPS** | VCC | ESP32 3.3V | Most Neo-6M modules prefer 3.3V, but check your specific board. |
| | GND | ESP32 GND | |
| | TX | ESP32 GPIO 16 | This is the ESP32's Hardware RX2 pin. |
| | RX | ESP32 GPIO 17 | This is the ESP32's Hardware TX2 pin. |
| **SIM800L (GSM)** | VCC | External Power (+) | MUST be 4.0V at 2 Amps. (e.g., from a DC-DC buck converter or Li-Ion battery). |
| | GND | External GND (-) & ESP32 GND | CRITICAL: All grounds must be tied together for data to transfer. |
| | TX | ESP32 GPIO 26 | |
| | RX | ESP32 GPIO 27 | |
| **Microphone** | Positive (Red) | SIM800L MIC+ | Standard electret condenser mic. |
| | Negative (Black) | SIM800L MIC- | |

### **Power Architecture**
- **SIM800L**: ⚠️ **Must be powered directly by a 3.7V - 4.2V Li-ion Battery.** The ESP32 3.3V/5V pins cannot handle the 2A current spikes during network transmission.
- **Neo-6M GPS**: Can be powered via the ESP32 **3.3V** pin.
- **Common Ground**: All GND pins from the Battery, SIM800L, GPS, and ESP32 must be connected together for stable data transfer.

## 📦 Setup & Installation

1. **Clone the Project**:
   ```bash
   git clone https://github.com/fwz07/Halo-tap.git
   ```

2. **Security & API Configuration**:
   - **Google Maps**: Add your API key to `local.properties` (this file is git-ignored):
     ```properties
     MAPS_API_KEY=your_actual_key_here
     ```
   - **Firebase**: Download `google-services.json` from your Firebase Console and place it in the `/app` folder.

3. **Database Schema**:
   The app expects the following structure in Firebase:
   ```json
   {
     "HaloTap": {
       "Locations": {
         "slot_0": {
           "lat": 25.2048,
           "lng": 55.2708,
           "timestamp": 1712832000000
         }
       }
     }
   }
   ```

4. **Hardware Synchronization Protocol**:
   Data is sent to the ESP32 via Bluetooth SPP in the following format:
   `*ParentName|ChildName|PhoneNumber#`
   The device confirms receipt with: `SETUP_SUCCESS`

## 📱 Permissions
The app strictly adheres to Android security best practices by requesting:
- `ACCESS_FINE_LOCATION` & `ACCESS_BACKGROUND_LOCATION`
- `BLUETOOTH_SCAN` & `BLUETOOTH_CONNECT` (Android 12+)
- `POST_NOTIFICATIONS` (Android 13+)
- `CALL_PHONE` (For SOS functionality)

## 📄 License
This project is licensed under the MIT License.

---
**Author**: [FAWAZ](https://github.com/fwz07)  
*Safety, just a tap away.*
