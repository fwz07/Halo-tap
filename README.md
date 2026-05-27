# HaloTap Android Application

HaloTap is a modern safety and tracking application designed to work with the HaloTap hardware device. It provides real-time location monitoring, safe zone geofencing, and seamless device synchronization via Bluetooth and WiFi.

## 🚀 Features

- **Real-time Tracking**: Integrated with Firebase Realtime Database to provide live location updates from the HaloTap device.
- **Safe Zones (Geofencing)**: Create multiple custom safe zones (e.g., Home, School, Park). Receive instant alerts and notifications if the device leaves a designated area.
- **Dual Connectivity**:
    - **Bluetooth SPP**: Synchronize parent/child details and emergency numbers using a secure serial protocol.
    - **NSD (Network Service Discovery)**: Automatically discover and connect to the device over a local WiFi network (Modern Android 14+ implementation).
- **Emergency SOS**: Quick-access button to call the device's SIM number in case of emergencies.
- **Premium UI/UX**: Features a modern interface with smooth circular "Button Fill" expansion transitions and Google Maps integration with multiple map styles.

## 🛠 Tech Stack

- **Language**: Kotlin
- **Architecture**: Decoupled Helper-based design for Bluetooth and Network logic.
- **Database**: Firebase Realtime Database (with offline persistence).
- **Maps**: Google Maps SDK for Android.
- **UI Components**: Material Design 3, Android View Animations (ObjectAnimator, Circular Reveal concepts).

## 📦 Setup & Installation

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/fwz07/Halo-tap.git
   ```

2. **Firebase Configuration**:
   - Place your `google-services.json` in the `app/` directory.
   - Ensure Firebase Realtime Database is enabled with the following structure:
     ```json
     {
       "HaloTap": {
         "Locations": {
           "slot_0": { "lat": 0.0, "lng": 0.0, "timestamp": "..." }
         }
       }
     }
     ```

3. **Google Maps API**:
   - Add your Google Maps API Key to `local.properties` or directly in `AndroidManifest.xml`.

4. **Hardware Protocol**:
   - The app expects the ESP32/Hardware to communicate via Bluetooth SPP using the format: `*ParentName|ChildName|PhoneNumber#`.
   - The device should respond with `SETUP_SUCCESS` upon successful synchronization.

## 📱 Permissions

The app requires the following permissions to function correctly:
- `ACCESS_FINE_LOCATION`: For map positioning and geofencing.
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`: For device discovery and data sync.
- `POST_NOTIFICATIONS`: For Safe Zone exit alerts.
- `CALL_PHONE`: For the SOS emergency call feature.

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## Author
FAWAZ
