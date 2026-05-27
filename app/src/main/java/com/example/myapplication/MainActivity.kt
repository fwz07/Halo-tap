package com.example.myapplication

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.core.net.toUri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.graphics.toColorInt
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var layoutSetup: View
    private lateinit var layoutOnboarding: View
    private lateinit var layoutDashboard: View
    private lateinit var mapView: MapView
    private var googleMap: GoogleMap? = null
    private var deviceMarker: Marker? = null
    private var isFirstLocationUpdate = true

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val serviceType = "_halotap._tcp."
    private val serviceName = "HaloTapDevice"

    private var clientSocket: Socket? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var bluetoothHelper: BluetoothHelper

    private var currentLatLng = LatLng(25.2048, 55.2708) // Default (Dubai)
    private var pendingRadius: Double = 500.0
    private var isAddingSafeZone = false
    
    data class SafeZone(
        val name: String,
        val center: LatLng,
        val radius: Double,
        val circle: Circle?,
        val marker: Marker?
    )
    private val safeZones = mutableListOf<SafeZone>()
    
    private var isAlertActive = false

    private val requestBluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startBluetoothDiscovery()
        } else {
            Toast.makeText(this, "Bluetooth is required for setup", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            startBluetoothDiscovery()
        } else {
            Toast.makeText(this, "Permissions required for Bluetooth setup", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Initialize layouts
        layoutSetup = findViewById(R.id.layoutSetup)
        layoutOnboarding = findViewById(R.id.layoutOnboarding)
        layoutDashboard = findViewById(R.id.layoutDashboard)
        mapView = layoutDashboard.findViewById(R.id.mapView)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        setupConnectScreen()
        setupOnboardingScreen()
        setupDashboardScreen()
        createNotificationChannel()
        initializeFirebase()

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothHelper = BluetoothHelper(this, bluetoothAdapter)

        // Load saved data from persistent storage
        loadSavedData()
    }

    private fun initializeFirebase() {
        try {
            // Standard initialization uses google-services.json automatically
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            
            // Enable offline persistence so it handles reconnections better
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            
            startFirebaseLocationSync()
        } catch (_: Exception) {
            // If persistence is already enabled, it might throw an error; we can ignore it
            startFirebaseLocationSync()
        }
    }

    private fun startFirebaseLocationSync() {
        val database = FirebaseDatabase.getInstance().getReference("HaloTap/Locations")
        
        // This ensures Firebase maintains a live connection even if data doesn't change
        database.keepSynced(true)
        
        // Listen to the entire "Locations" node (slots 0-9)
        database.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var latestLat = 0.0
                var latestLng = 0.0
                var bestTimeMillis = 0L
                var latestTimeRaw = ""
                var foundData = false

                for (slot in snapshot.children) {
                    val lat = (slot.child("lat").value as? Number)?.toDouble()
                    val lng = (slot.child("lng").value as? Number)?.toDouble()
                    val timeRaw = slot.child("timestamp").value?.toString() ?: ""
                    
                    if (lat != null && lng != null && lat != 0.0) {
                        // Convert to millis for accurate "latest" comparison
                        val currentMillis = try {
                            if (timeRaw.all { it.isDigit() } && timeRaw.isNotEmpty()) {
                                timeRaw.toLong()
                            } else {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                sdf.parse(timeRaw)?.time ?: 0L
                            }
                        } catch (_: Exception) { 0L }

                        if (!foundData || currentMillis >= bestTimeMillis) {
                            latestLat = lat
                            latestLng = lng
                            bestTimeMillis = currentMillis
                            latestTimeRaw = timeRaw
                            foundData = true
                        }
                    }
                }

                if (foundData) {
                    runOnUiThread {
                        val newLatLng = LatLng(latestLat, latestLng)
                        
                        // Update the last updated text with high readability
                        val displayTime = formatGpsTimestamp(latestTimeRaw)
                        findViewById<TextView>(R.id.tvLastUpdated)?.text = getString(R.string.last_updated_at, displayTime)

                        // Only update if the position actually changed to avoid map flickering
                        if (newLatLng != currentLatLng) {
                            currentLatLng = newLatLng
                            deviceMarker?.position = currentLatLng
                            
                            // Only auto-center the camera the first time we get a location
                            if (isFirstLocationUpdate) {
                                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                                isFirstLocationUpdate = false
                            }

                            checkSafeZone(currentLatLng)
                            
                            findViewById<TextView>(R.id.tvStatus).apply {
                                text = getString(R.string.status_firebase)
                                setTextColor(ContextCompat.getColor(context, R.color.success_green))
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MainActivity, "Firebase Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun createNotificationChannel() {
        val name = "HaloTap Alerts"
        val descriptionText = "Notifications for Safe Zone exits"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel("HALOTAP_ALERTS", name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun startNsdDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Searching for HaloTap over WiFi...", Toast.LENGTH_SHORT).show() }
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == serviceType && service.serviceName.contains(serviceName)) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        nsdManager?.registerServiceInfoCallback(service, { it.run() }, object : NsdManager.ServiceInfoCallback {
                            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.hostAddresses.firstOrNull()?.hostAddress ?: return
                                connectToDevice(host, serviceInfo.port)
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "HaloTap Connected!", Toast.LENGTH_SHORT).show()
                                    showScreen("onboarding")
                                }
                                // We found what we needed, can unregister the callback
                                nsdManager?.unregisterServiceInfoCallback(this)
                            }

                            override fun onServiceLost() {}
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                runOnUiThread { Toast.makeText(this@MainActivity, "Resolve failed: $errorCode", Toast.LENGTH_SHORT).show() }
                            }

                            override fun onServiceInfoCallbackUnregistered() {}
                        })
                    } else {
                        @Suppress("DEPRECATION")
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                runOnUiThread { Toast.makeText(this@MainActivity, "Resolve failed: $errorCode", Toast.LENGTH_SHORT).show() }
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val host = serviceInfo.host?.hostAddress ?: return
                                connectToDevice(host, serviceInfo.port)
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "HaloTap Connected!", Toast.LENGTH_SHORT).show()
                                    showScreen("onboarding")
                                }
                            }
                        })
                    }
                    stopNsdDiscovery()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(regType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsdDiscovery() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsdDiscovery() }
        }

        nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopNsdDiscovery() {
        discoveryListener?.let {
            nsdManager?.stopServiceDiscovery(it)
            discoveryListener = null
        }
    }

    private fun connectToDevice(host: String, port: Int) {
        thread {
            try {
                clientSocket = Socket(host, port)
                val reader = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    val data = line.split(",")
                    
                    runOnUiThread {
                        if (data.size >= 2) {
                            val lat = data[0].toDoubleOrNull() ?: return@runOnUiThread
                            val lng = data[1].toDoubleOrNull() ?: return@runOnUiThread
                            
                            currentLatLng = LatLng(lat, lng)
                            deviceMarker?.position = currentLatLng
                            googleMap?.animateCamera(CameraUpdateFactory.newLatLng(currentLatLng))
                            checkSafeZone(currentLatLng)
                            
                            findViewById<TextView>(R.id.tvStatus).apply {
                                text = getString(R.string.online)
                                setTextColor(ContextCompat.getColor(context, R.color.success_green))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    findViewById<TextView>(R.id.tvStatus).apply {
                        text = getString(R.string.status_offline)
                        setTextColor(ContextCompat.getColor(context, R.color.safety_red))
                    }
                }
                e.printStackTrace()
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap?.isMyLocationEnabled = true
            
            // Get last known location to center map
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            val lastLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastLocation?.let {
                currentLatLng = LatLng(it.latitude, it.longitude)
                googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
            }
        }

        // Allow user to set Safe Zone center after clicking the button and choosing radius
        googleMap?.setOnMapClickListener { latLng ->
            if (isAddingSafeZone) {
                isAddingSafeZone = false
                addSafeZone(latLng, pendingRadius)
            }
        }
        
        deviceMarker = googleMap?.addMarker(
            MarkerOptions()
                .position(currentLatLng)
                .title("HaloTap Device")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        if (deviceMarker == null) {
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        try {
            clientSocket?.close()
            stopNsdDiscovery()
        } catch (_: Exception) { }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private fun formatGpsTimestamp(timestamp: String): String {
        if (timestamp.isEmpty() || timestamp.contains("Wait")) return "Just now"
        
        return try {
            val date = if (timestamp.all { it.isDigit() }) {
                java.util.Date(timestamp.toLong())
            } else {
                val sdfSource = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                sdfSource.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdfSource.parse(timestamp) ?: return "Just now"
            }
            
            val timeFormat = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
            val timeStr = timeFormat.format(date)
            
            val relativeStr = android.text.format.DateUtils.getRelativeTimeSpanString(
                date.time,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS,
                android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()

            "$timeStr ($relativeStr)"
        } catch (_: Exception) {
            "Just now"
        }
    }

    private fun setupConnectScreen() {
        val btnConnect = layoutSetup.findViewById<MaterialCardView>(R.id.btnConnect)
        val logoCard = layoutSetup.findViewById<MaterialCardView>(R.id.logoCard)

        // Premium Floating Animation for Logo
        ObjectAnimator.ofFloat(logoCard, "translationY", -15f, 15f).apply {
            duration = 2500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }.start()

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnConnect.setOnClickListener {
            // Interactive Feedback
            btnConnect.animate().scaleX(0.9f).scaleY(0.9f).setDuration(150).withEndAction {
                btnConnect.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }.start()

            // Check permissions first
            checkBluetoothPermissions()
            
            // For testing, we'll try WiFi (NSD) first
            startNsdDiscovery()
        }
    }

    private fun checkBluetoothPermissions() {
        val permissions = mutableListOf(
            @Suppress("DEPRECATION")
            Manifest.permission.ACCESS_FINE_LOCATION,
            @Suppress("InlinedApi")
            Manifest.permission.BLUETOOTH_SCAN,
            @Suppress("InlinedApi")
            Manifest.permission.BLUETOOTH_CONNECT,
            @Suppress("InlinedApi")
            Manifest.permission.POST_NOTIFICATIONS
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startBluetoothDiscovery()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startBluetoothDiscovery() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            val enableBtIntent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetoothEnableLauncher.launch(enableBtIntent)
            return
        }

        val bluetoothIcon = layoutSetup.findViewById<ImageView>(R.id.bluetoothIcon)
        
        // Visual feedback
        bluetoothIcon.animate().alpha(0.3f).setDuration(500).withEndAction {
            bluetoothIcon.animate().alpha(1.0f).setDuration(500).start()
        }.start()

        Toast.makeText(this, "Searching for halotap...", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            
            // Specifically check for LIVE connections (GATT/BLE)
            val connectedDevices = try {
                bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            } catch (_: SecurityException) {
                null
            }
            
            val isTargetConnected = connectedDevices?.any { 
                try {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        it.name?.contains("halotap", ignoreCase = true) == true
                    } else false
                } catch (_: SecurityException) {
                    false
                }
            } ?: false

            if (isTargetConnected) {
                Toast.makeText(this, "Connected to halotap", Toast.LENGTH_SHORT).show()
                showScreen("onboarding")
            } else {
                // FALLBACK: Check if it's at least paired
                val pairedDevices = try { 
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothAdapter?.bondedDevices 
                    } else null
                } catch (_: SecurityException) { null }
                
                val isPaired = pairedDevices?.any { 
                    try {
                        it.name?.contains("halotap", ignoreCase = true) == true
                    } catch (_: SecurityException) { false }
                } ?: false
                
                if (isPaired) {
                    Toast.makeText(this, "halotap recognized via Paired status", Toast.LENGTH_SHORT).show()
                    showScreen("onboarding")
                } else {
                    Toast.makeText(this, "halotap not found. Please pair it in Android Settings first.", Toast.LENGTH_LONG).show()
                }
            }
        }, 2000)
    }

    private fun setupOnboardingScreen() {
        val btnSave = layoutOnboarding.findViewById<MaterialButton>(R.id.btnSave)
        val etParentName = layoutOnboarding.findViewById<EditText>(R.id.etParentName)
        val etChildName = layoutOnboarding.findViewById<EditText>(R.id.etChildName)
        val etParentPhone = layoutOnboarding.findViewById<EditText>(R.id.etParentPhone)
        val etSosNumber = layoutOnboarding.findViewById<EditText>(R.id.etSosNumber)

        // Pre-fill fields if data was saved previously
        val sharedPref = getSharedPreferences("HaloTapPrefs", MODE_PRIVATE)
        etParentName.setText(sharedPref.getString("parent_name", ""))
        etChildName.setText(sharedPref.getString("child_name", ""))
        
        val savedParentPhone = sharedPref.getString("parent_phone", "") ?: ""
        etParentPhone.setText(savedParentPhone.ifEmpty { "+91" })

        val savedSos = sharedPref.getString("sos_number", "") ?: ""
        etSosNumber.setText(savedSos.ifEmpty { "+91" })

        btnSave.setOnClickListener {
            // Interactive Feedback
            btnSave.animate().scaleX(0.96f).scaleY(0.96f).setDuration(150).withEndAction {
                btnSave.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }.start()

            val parentName = etParentName.text.toString().trim()
            val childName = etChildName.text.toString().trim()
            var parentPhone = etParentPhone.text.toString().trim()
            var sosNumber = etSosNumber.text.toString().trim()

            // Auto-format Parent Phone
            if (parentPhone.isNotEmpty() && !parentPhone.startsWith("+91")) {
                parentPhone = if (parentPhone.startsWith("91")) "+$parentPhone" else "+91$parentPhone"
            }

            // Auto-format SOS Number (Device SIM Number)
            if (sosNumber.isNotEmpty() && !sosNumber.startsWith("+91")) {
                sosNumber = if (sosNumber.startsWith("91")) "+$sosNumber" else "+91$sosNumber"
            }

            if (parentName.isNotEmpty() && childName.isNotEmpty() && parentPhone.length > 3 && sosNumber.length > 3) {
                // Save to App Storage (Persistent)
                saveUserData(parentName, childName, parentPhone, sosNumber)

                btnSave.text = getString(R.string.syncing)
                btnSave.isEnabled = false

                Handler(Looper.getMainLooper()).postDelayed({
                    // SYNC PARENT PHONE to the HaloTap Device so IT can call YOU
                    syncDataToDevice(parentName, childName, parentPhone)
                    updateDashboardInfo(childName)
                    showScreen("dashboard")
                }, 2500)
            } else {
                Toast.makeText(this, "Please enter all fields correctly", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveUserData(parent: String, child: String, parentPhone: String, sos: String) {
        val sharedPref = getSharedPreferences("HaloTapPrefs", MODE_PRIVATE)
        sharedPref.edit().apply {
            putString("parent_name", parent)
            putString("child_name", child)
            putString("parent_phone", parentPhone)
            putString("sos_number", sos)
            apply()
        }
    }

    private fun loadSavedData() {
        val sharedPref = getSharedPreferences("HaloTapPrefs", MODE_PRIVATE)
        val parent = sharedPref.getString("parent_name", "") ?: ""
        val child = sharedPref.getString("child_name", "") ?: ""
        val sos = sharedPref.getString("sos_number", "") ?: ""

        if (parent.isNotEmpty() && child.isNotEmpty() && sos.isNotEmpty()) {
            updateDashboardInfo(child)
        }
    }

    private fun syncDataToDevice(parent: String, child: String, sos: String) {
        bluetoothHelper.syncDataToDevice(parent, child, sos, object : BluetoothHelper.SyncCallback {
            override fun onSyncStarted() {
                // UI feedback already handled in caller or can be added here
            }

            override fun onSyncSuccess() {
                Toast.makeText(this@MainActivity, "Data synced to HaloTap \u2705", Toast.LENGTH_SHORT).show()
            }

            override fun onSyncFailure(error: String) {
                Toast.makeText(this@MainActivity, "Sync Error: $error", Toast.LENGTH_LONG).show()
                // Allow the user to try again
                findViewById<MaterialButton>(R.id.btnSave).apply {
                    isEnabled = true
                    text = getString(R.string.status_retry_sync)
                }
            }
        })
    }

    private fun setupDashboardScreen() {
        val btnSyncInfo = layoutDashboard.findViewById<MaterialCardView>(R.id.btnSyncInfo)
        val btnCallSos = layoutDashboard.findViewById<MaterialButton>(R.id.btnCallSos)
        val btnMapStyleFab = layoutDashboard.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnMapStyleFab)
        
        // New FABs on the map
        val btnAddSafeZoneFab = layoutDashboard.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnAddSafeZoneFab)
        val btnMyLocationFab = layoutDashboard.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnMyLocationFab)
        val btnRefreshMapFab = layoutDashboard.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnRefreshMapFab)

        btnSyncInfo.setOnClickListener {
            showScreen("onboarding")
        }

        btnRefreshMapFab.setOnClickListener {
            // Visual feedback
            btnRefreshMapFab.animate().rotationBy(360f).setDuration(500).start()
            Toast.makeText(this, "Refreshing location...", Toast.LENGTH_SHORT).show()
            
            // Force a one-time fetch to ensure we have the absolute latest
            val database = FirebaseDatabase.getInstance().getReference("HaloTap/Locations")
            database.get().addOnSuccessListener {
                // The existing ValueEventListener in startFirebaseLocationSync() 
                // will also be triggered by this read if there's any discrepancy.
                Toast.makeText(this, "Location Sync Complete", Toast.LENGTH_SHORT).show()
            }.addOnFailureListener {
                Toast.makeText(this, "Refresh failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnMapStyleFab.setOnClickListener {
            toggleMapStyle()
        }

        btnCallSos.setOnClickListener {
            val sharedPref = getSharedPreferences("HaloTapPrefs", MODE_PRIVATE)
            val sosNumber = sharedPref.getString("sos_number", "") ?: ""
            
            if (sosNumber.isNotEmpty()) {
                val intent = android.content.Intent(android.content.Intent.ACTION_CALL)
                intent.data = "tel:$sosNumber".toUri()
                
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    startActivity(intent)
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 101)
                    Toast.makeText(this, "Please grant Call Permission", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "SOS Number not set", Toast.LENGTH_SHORT).show()
            }
        }

        btnAddSafeZoneFab.setOnClickListener {
            showInitialRadiusDialog()
        }

        layoutDashboard.findViewById<MaterialButton>(R.id.btnClearAllZones).setOnClickListener {
            if (safeZones.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Clear All Zones")
                    .setMessage("Are you sure you want to remove all safe zones?")
                    .setPositiveButton("Clear All") { _, _ -> clearAllSafeZones() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        btnMyLocationFab.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
                val lastLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                lastLocation?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                }
            }
        }
        
        // Initial refresh of the bottom bar list
        refreshSafeZonesBottomBar()
    }

    private fun refreshSafeZonesBottomBar() {
        val container = layoutDashboard.findViewById<LinearLayout>(R.id.llSafeZonesList)
        container.removeAllViews()

        if (safeZones.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = getString(R.string.no_safe_zones)
                setTextColor("#94A3B8".toColorInt()) // slate_400
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 32)
            }
            container.addView(emptyText)
            return
        }

        safeZones.forEachIndexed { index, zone ->
            val zoneView = layoutInflater.inflate(android.R.layout.simple_list_item_2, container, false)
            val text1 = zoneView.findViewById<TextView>(android.R.id.text1)
            val text2 = zoneView.findViewById<TextView>(android.R.id.text2)

            text1.text = zone.name
            text1.setTextColor("#1E293B".toColorInt()) // slate_800
            text1.setTypeface(null, android.graphics.Typeface.BOLD)

            text2.text = getString(R.string.radius_template, zone.radius.toInt())
            text2.setTextColor("#64748B".toColorInt()) // slate_500

            zoneView.setOnClickListener {
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(zone.center, 15f))
                showDeleteZoneConfirmation(index)
            }

            // Add divider
            if (index > 0) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                    setBackgroundColor("#E2E8F0".toColorInt()) // slate_200
                }
                container.addView(divider)
            }

            container.addView(zoneView)
        }
    }

    private fun showDeleteZoneConfirmation(index: Int) {
        val zone = safeZones[index]
        AlertDialog.Builder(this)
            .setTitle("Manage Zone")
            .setMessage("Zone: ${zone.name}\nRadius: ${zone.radius.toInt()}m\n\nDo you want to delete this zone?")
            .setPositiveButton("Delete") { _, _ ->
                zone.circle?.remove()
                zone.marker?.remove()
                safeZones.removeAt(index)
                refreshSafeZonesBottomBar()
                Toast.makeText(this, "Zone deleted", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Zoom to") { _, _ ->
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(zone.center, 15f))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInitialRadiusDialog() {
        val options = arrayOf("100 Meters", "500 Meters", "1 Kilometer", "Custom Radius")
        AlertDialog.Builder(this)
            .setTitle("Add Safe Zone")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showNameInputDialog(100.0)
                    1 -> showNameInputDialog(500.0)
                    2 -> showNameInputDialog(1000.0)
                    3 -> showCustomRadiusInput()
                }
            }
            .show()
    }

    private fun showCustomRadiusInput() {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = "Enter radius in meters"
        
        AlertDialog.Builder(this)
            .setTitle("Custom Radius")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val radius = input.text.toString().toDoubleOrNull()
                if (radius != null && radius > 0) {
                    showNameInputDialog(radius)
                } else {
                    Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNameInputDialog(radius: Double) {
        val input = EditText(this)
        input.hint = "e.g. Home, School, Park"
        
        AlertDialog.Builder(this)
            .setTitle("Name this Zone")
            .setView(input)
            .setPositiveButton("Set Location") { _, _ ->
                val name = input.text.toString().ifEmpty { "Zone ${safeZones.size + 1}" }
                startPlacement(name, radius)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var pendingZoneName = ""
    private fun startPlacement(name: String, radius: Double) {
        pendingRadius = radius
        pendingZoneName = name
        isAddingSafeZone = true
        Toast.makeText(this, "Now tap on the map to place '$name'", Toast.LENGTH_LONG).show()
    }

    private fun addSafeZone(center: LatLng, radius: Double) {
        val circle = googleMap?.addCircle(
            CircleOptions()
                .center(center)
                .radius(radius)
                .strokeColor(Color.argb(100, 34, 197, 94))
                .fillColor(Color.argb(30, 34, 197, 94))
                .strokeWidth(2f)
        )

        val marker = googleMap?.addMarker(
            MarkerOptions()
                .position(center)
                .title("$pendingZoneName (${radius.toInt()}m)")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )

        safeZones.add(SafeZone(pendingZoneName, center, radius, circle, marker))
        refreshSafeZonesBottomBar()
        Toast.makeText(this, "'$pendingZoneName' Added!", Toast.LENGTH_SHORT).show()
    }

    private fun toggleMapStyle() {
        googleMap?.let { map ->
            val currentType = map.mapType
            map.mapType = when (currentType) {
                GoogleMap.MAP_TYPE_NORMAL -> GoogleMap.MAP_TYPE_SATELLITE
                GoogleMap.MAP_TYPE_SATELLITE -> GoogleMap.MAP_TYPE_TERRAIN
                else -> GoogleMap.MAP_TYPE_NORMAL
            }
            
            val typeName = when (map.mapType) {
                GoogleMap.MAP_TYPE_SATELLITE -> "Satellite"
                GoogleMap.MAP_TYPE_TERRAIN -> "Terrain"
                else -> "Standard"
            }
            Toast.makeText(this, "Map Style: $typeName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAllSafeZones() {
        safeZones.forEach { 
            it.circle?.remove()
            it.marker?.remove()
        }
        safeZones.clear()
        refreshSafeZonesBottomBar()
        isAlertActive = false
        Toast.makeText(this, "All Safe Zones cleared", Toast.LENGTH_SHORT).show()
    }

    private fun checkSafeZone(childPos: LatLng) {
        if (safeZones.isEmpty()) return
        
        var isInsideAny = false

        for (zone in safeZones) {
            if (calculateDistance(zone.center.latitude, zone.center.longitude, childPos.latitude, childPos.longitude) <= zone.radius) {
                isInsideAny = true
                break
            }
        }
        
        if (!isInsideAny) {
            if (!isAlertActive) {
                showAlert()
                isAlertActive = true
            }
        } else {
            // Optional: Show which zone they entered if it's new
            isAlertActive = false
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth radius in meters
        val phi1 = lat1 * PI / 180
        val phi2 = lat2 * PI / 180
        val deltaPhi = (lat2 - lat1) * PI / 180
        val deltaLambda = (lon2 - lon1) * PI / 180

        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }

    private fun showAlert() {
        val message = "The device has left the Safe Zone!"
        // Send Notification
        val builder = NotificationCompat.Builder(this, "HALOTAP_ALERTS")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.safe_zone_alert_title))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(1000, 1000, 1000))
            .setAutoCancel(true)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationManager.notify(1, builder.build())
        }

        // Show Toast
        runOnUiThread {
            Toast.makeText(this, "⚠️ ALERT: $message", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateDashboardInfo(child: String) {
        val headerTitle = layoutDashboard.findViewById<TextView>(R.id.headerTitle)

        headerTitle.text = getString(R.string.location_header_template, child)
        deviceMarker?.title = child
    }

    private fun showScreen(screen: String) {
        val targetView = when (screen) {
            "setup" -> layoutSetup
            "onboarding" -> layoutOnboarding
            "dashboard" -> layoutDashboard
            else -> return
        }

        if (targetView.isVisible) return

        // Identify the trigger button to start the "Button Fill" expansion animation
        val currentScreen = listOf(layoutSetup, layoutOnboarding, layoutDashboard).find { it.isVisible }
        var centerX = 0
        var centerY = 0
        var color = Color.WHITE
        var hasTrigger = false

        currentScreen?.let {
            val triggerView = when (it) {
                layoutSetup -> it.findViewById<MaterialCardView>(R.id.btnConnect)
                layoutOnboarding -> it.findViewById<MaterialButton>(R.id.btnSave)
                else -> null
            }

            triggerView?.let { btn ->
                val loc = IntArray(2)
                btn.getLocationInWindow(loc)
                centerX = loc[0] + btn.width / 2
                centerY = loc[1] + btn.height / 2

                color = when (btn) {
                    is MaterialCardView -> btn.cardBackgroundColor.defaultColor
                    is MaterialButton -> btn.backgroundTintList?.defaultColor ?: "#0F172A".toColorInt()
                    else -> Color.WHITE
                }
                hasTrigger = true
            }
        }

        if (hasTrigger) {
            performExpandTransition(targetView, centerX, centerY, screen, color)
        } else {
            // Fallback for non-button transitions (e.g. app launch)
            val views = listOf(layoutSetup, layoutOnboarding, layoutDashboard)
            views.forEach { view ->
                if (view == targetView) {
                    view.visibility = View.VISIBLE
                    view.alpha = 0f
                    view.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                } else {
                    view.visibility = View.GONE
                }
            }
            if (screen == "dashboard") finalizeDashboardEntrance()
        }
    }

    private fun performExpandTransition(targetView: View, centerX: Int, centerY: Int, screenName: String, color: Int) {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        
        // Create an expansion circle overlay that matches the button's color
        val expansionView = View(this).apply {
            val size = 100
            layoutParams = android.view.ViewGroup.LayoutParams(size, size)
            x = (centerX - size/2).toFloat()
            y = (centerY - size/2).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            elevation = 2000f // Stay on top during transition
        }
        root.addView(expansionView)

        val displayMetrics = resources.displayMetrics
        val finalRadius = sqrt(max(centerX, displayMetrics.widthPixels - centerX).toDouble().pow(2.0) + 
                               max(centerY, displayMetrics.heightPixels - centerY).toDouble().pow(2.0)).toFloat()
        
        // Expand the circle to fill the entire screen
        val scale = (finalRadius * 2.2f) / 100f
        
        expansionView.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(700)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                // Instantly swap screens behind the solid color overlay
                listOf(layoutSetup, layoutOnboarding, layoutDashboard).forEach { it.visibility = View.GONE }
                targetView.visibility = View.VISIBLE
                targetView.alpha = 1f
                targetView.scaleX = 1f
                targetView.scaleY = 1f
                
                if (screenName == "dashboard") {
                    finalizeDashboardEntrance()
                } else if (screenName == "onboarding") {
                    animateOnboardingEntrance()
                }

                // Fade out the overlay to reveal the new page
                expansionView.animate()
                    .alpha(0f)
                    .setDuration(600)
                    .withEndAction {
                        root.removeView(expansionView)
                    }
                    .start()
            }
            .start()
    }

    private fun animateOnboardingEntrance() {
        val scrollView = layoutOnboarding as? androidx.core.widget.NestedScrollView ?: return
        val content = scrollView.getChildAt(0) as? android.view.ViewGroup ?: return
        
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            child.alpha = 0f
            child.translationY = 80f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(700)
                .setStartDelay(100L * i)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.7f))
                .start()
        }
    }

    private fun finalizeDashboardEntrance() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        animateDashboardEntrance()
    }

    private fun animateDashboardEntrance() {
        val bottomBar = findViewById<View>(R.id.bottomBar)
        bottomBar.translationY = 800f
        bottomBar.animate()
            .translationY(0f)
            .setDuration(1000)
            .setStartDelay(400)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.0f))
            .start()

        val syncBtn = findViewById<View>(R.id.btnSyncInfo)
        val notificationsBtn = findViewById<View>(R.id.btnNotifications)
        val headerCard = findViewById<View>(R.id.headerTitle)?.parent?.parent as? View
        
        headerCard?.let {
            it.alpha = 0f
            it.translationY = -100f
            it.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(700)
                .setStartDelay(600)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        listOf(syncBtn, notificationsBtn).forEachIndexed { index, view ->
            view.scaleX = 0f
            view.scaleY = 0f
            view.rotation = if (index == 0) -45f else 45f
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setDuration(600)
                .setStartDelay(800L + (index * 150))
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
        }
        
        animateMapControls()
        startStatusPulse()
    }

    private fun animateMapControls() {
        val fabStyle = findViewById<View>(R.id.btnMapStyleFab)
        val fabAdd = findViewById<View>(R.id.btnAddSafeZoneFab)
        val fabLoc = findViewById<View>(R.id.btnMyLocationFab)

        listOfNotNull(fabStyle, fabAdd, fabLoc).forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationX = 100f
            view.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(500)
                .setStartDelay(1000L + (index * 100))
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun startStatusPulse() {
        val statusView = findViewById<View>(R.id.tvStatus)
        val pulseContainer = statusView.parent as? View
        
        val pulseAnimation = android.view.animation.AlphaAnimation(1f, 0.4f).apply {
            duration = 1000
            repeatMode = android.view.animation.Animation.REVERSE
            repeatCount = android.view.animation.Animation.INFINITE
        }
        statusView.startAnimation(pulseAnimation)
        
        // Add a subtle scale pulse to the container too
        pulseContainer?.let {
            val scalePulse = android.view.animation.ScaleAnimation(
                1f, 1.05f, 1f, 1.05f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 2000
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
            }
            it.startAnimation(scalePulse)
        }
    }
}
