package com.example.podvalidator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.location.Location as AndroidLocation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeliveryValidatorApp()
                }
            }
        }
    }
}

@Composable
fun DeliveryValidatorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { DeliveryRepository(context) }
    val reportingService = remember { ReportingService(context) }
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0" }

    var username by remember { mutableStateOf("") }
    var waybill by remember { mutableStateOf("") }
    var selectedDelivery by remember { mutableStateOf<DeliveryPoint?>(null) }
    var status by remember { mutableStateOf("Enter username to start, then enter or scan a waybill.") }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var actionSnapshot by remember { mutableStateOf<EventSnapshot?>(null) }
    var photoSnapshot by remember { mutableStateOf<EventSnapshot?>(null) }
    var selectedAction by remember { mutableStateOf<DeliveryAction?>(null) }
    var validationResult by remember { mutableStateOf<ValidationResult?>(null) }
    var syncMessage by remember { mutableStateOf("Pending report sync has not started.") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val cameraGranted = perms[Manifest.permission.CAMERA] == true
        val locationGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        status = when {
            !cameraGranted && !locationGranted -> "Camera and location permissions are required."
            !cameraGranted -> "Camera permission is required."
            !locationGranted -> "Location permission is required."
            else -> "Permissions granted. You can proceed."
        }
    }

    val enableLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        status = if (isLocationEnabled(context)) {
            "Location services enabled."
        } else {
            "Location must stay enabled for this app to work."
        }
    }

    val scanWaybillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            status = "Waybill scan cancelled."
        } else {
            scope.launch {
                val scanned = scanWaybillFromBitmap(bitmap)
                if (scanned.isNullOrBlank()) {
                    status = "No barcode/waybill detected in the scanned image."
                } else {
                    waybill = scanned
                    selectedDelivery = repo.findWaybill(scanned)
                    validationResult = null
                    actionSnapshot = null
                    photoSnapshot = null
                    photoBitmap = null
                    selectedAction = null
                    status = if (selectedDelivery == null) {
                        "Scanned waybill $scanned, but it was not found in mapped delivery data."
                    } else {
                        "Scanned waybill $scanned successfully. Now choose Delivered or Non-Delivered."
                    }
                }
            }
        }
    }

    val capturePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            status = "Photo capture cancelled."
        } else {
            photoBitmap = bitmap
            scope.launch {
                val delivery = selectedDelivery
                val action = selectedAction
                if (delivery == null) {
                    status = "Select a valid waybill first."
                    return@launch
                }
                if (username.trim().isBlank()) {
                    status = "Enter username before proceeding."
                    return@launch
                }
                if (actionSnapshot == null || action == null) {
                    status = "Choose Delivered or Non-Delivered first so the app can capture click location and time."
                    return@launch
                }

                val photoLocation = captureCurrentLocationIfPossible(context)
                photoSnapshot = snapshotFromLocation(photoLocation)

                validationResult = validateDelivery(
                    bitmap = bitmap,
                    delivery = delivery,
                    actionSnapshot = actionSnapshot,
                    photoSnapshot = photoSnapshot
                )
                status = validationResult?.summary ?: "Validation complete."

                val transaction = reportingService.buildTransaction(
                    username = username.trim(),
                    waybill = waybill.trim().uppercase(),
                    action = action,
                    delivery = delivery,
                    actionSnapshot = actionSnapshot,
                    photoSnapshot = photoSnapshot,
                    validationResult = validationResult!!,
                    versionName = versionName
                )
                val reportResult = reportingService.submitTransaction(transaction)
                syncMessage = "${reportResult.message} Pending queue: ${reportResult.pendingCount}"
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.INTERNET
            )
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("POD Delivery Validator - Advanced", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Workflow: username → enter/scan waybill → click Delivered/Non-Delivered → capture live proof photo → app validates action-click GPS against photo GPS within 100m, records human presence, and posts transaction for reporting.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Courier Username") },
            singleLine = true
        )

        OutlinedTextField(
            value = waybill,
            onValueChange = {
                waybill = it.trim().uppercase()
                selectedDelivery = repo.findWaybill(waybill)
                resetForNewWaybill(
                    onReset = {
                        actionSnapshot = null
                        photoSnapshot = null
                        photoBitmap = null
                        validationResult = null
                        selectedAction = null
                    }
                )
                status = if (selectedDelivery == null) {
                    "Waybill not found yet."
                } else {
                    "Waybill found. Now choose Delivered or Non-Delivered."
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Waybill Number") },
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scanWaybillLauncher.launch(null) }) { Text("Scan Waybill") }
            Button(onClick = {
                scope.launch {
                    val result = reportingService.flushPending()
                    syncMessage = "${result.message} Pending queue: ${result.pendingCount}"
                }
            }) { Text("Sync Reports") }
        }

        selectedDelivery?.let { delivery ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Mapped Delivery", fontWeight = FontWeight.SemiBold)
                    Text("Waybill: ${delivery.waybill}")
                    Text("Customer: ${delivery.customerName}")
                    Text("Address: ${delivery.address}")
                    Text("Expected GPS: ${delivery.latitude ?: "-"}, ${delivery.longitude ?: "-"}")
                    Text("Allowed radius: ${delivery.allowedRadiusMeters.toInt()} meters")
                }
            }
        }

        Text("Select shipment outcome:", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (username.isBlank()) {
                    status = "Enter courier username first."
                    return@Button
                }
                if (selectedDelivery == null) {
                    status = "Enter or scan a valid waybill first."
                    return@Button
                }
                if (!isLocationEnabled(context)) {
                    status = "Location is OFF. Turn it on to continue."
                    requestLocationEnable(context, enableLocationLauncher) {}
                    return@Button
                }
                scope.launch {
                    val snap = snapshotFromLocation(captureCurrentLocationIfPossible(context))
                    selectedAction = DeliveryAction.DELIVERED
                    actionSnapshot = snap
                    validationResult = null
                    status = if (snap?.latitude == null || snap.longitude == null) {
                        "Delivered click captured, but location could not be fetched."
                    } else {
                        "Delivered click captured with location and time. Now take the proof photo."
                    }
                }
            }) { Text("Delivered") }

            Button(onClick = {
                if (username.isBlank()) {
                    status = "Enter courier username first."
                    return@Button
                }
                if (selectedDelivery == null) {
                    status = "Enter or scan a valid waybill first."
                    return@Button
                }
                if (!isLocationEnabled(context)) {
                    status = "Location is OFF. Turn it on to continue."
                    requestLocationEnable(context, enableLocationLauncher) {}
                    return@Button
                }
                scope.launch {
                    val snap = snapshotFromLocation(captureCurrentLocationIfPossible(context))
                    selectedAction = DeliveryAction.NON_DELIVERED
                    actionSnapshot = snap
                    validationResult = null
                    status = if (snap?.latitude == null || snap.longitude == null) {
                        "Non-Delivered click captured, but location could not be fetched."
                    } else {
                        "Non-Delivered click captured with location and time. Now take the proof photo."
                    }
                }
            }) { Text("Non-Delivered") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!isLocationEnabled(context)) {
                    requestLocationEnable(context, enableLocationLauncher) {
                        status = "Location must be enabled. The app will not work otherwise."
                    }
                } else {
                    status = "Location is already enabled."
                }
            }) { Text("Check Location") }

            Button(onClick = {
                if (selectedDelivery == null) {
                    status = "Enter or scan a valid waybill first."
                    return@Button
                }
                if (selectedAction == null || actionSnapshot == null) {
                    status = "Click Delivered or Non-Delivered first."
                    return@Button
                }
                if (!isLocationEnabled(context)) {
                    status = "Location is OFF. Turn it on to continue."
                    requestLocationEnable(context, enableLocationLauncher) {}
                    return@Button
                }
                capturePhotoLauncher.launch(null)
            }) { Text("Take Proof Photo") }
        }

        actionSnapshot?.let { snap ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Action Click Snapshot", fontWeight = FontWeight.SemiBold)
                    Text("Action: ${selectedAction?.name ?: "-"}")
                    Text("GPS: ${snap.latitude ?: "-"}, ${snap.longitude ?: "-"}")
                    Text("Time: ${snap.isoTimestamp ?: "-"}")
                }
            }
        }

        photoSnapshot?.let { snap ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Photo Snapshot", fontWeight = FontWeight.SemiBold)
                    Text("GPS: ${snap.latitude ?: "-"}, ${snap.longitude ?: "-"}")
                    Text("Time: ${snap.isoTimestamp ?: "-"}")
                }
            }
        }

        Text(status, style = MaterialTheme.typography.bodyLarge)
        Text(syncMessage, style = MaterialTheme.typography.bodyMedium)

        photoBitmap?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Latest Captured Photo", fontWeight = FontWeight.SemiBold)
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Captured live proof photo",
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
                }
            }
        }

        validationResult?.let { result ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Validation Result", fontWeight = FontWeight.Bold)
                    Text("Decision: ${if (result.isGenuine) "GENUINE" else "NOT GENUINE / NEED REVIEW"}")
                    Text("Outcome button selected: ${selectedAction?.name ?: "-"}")
                    Text("Human detected in photo: ${if (result.humanDetected) "Yes" else "No"}")
                    Text("Validation mode: ${result.validationMode}")
                    Text("Action click GPS: ${result.actionClickLatitude}, ${result.actionClickLongitude}")
                    Text("Photo GPS: ${result.photoLatitude}, ${result.photoLongitude}")
                    Text("Expected GPS: ${result.expectedLatitude}, ${result.expectedLongitude}")
                    result.actionToPhotoDistanceMeters?.let {
                        Text("Distance (action click ↔ photo): ${"%.2f".format(it)} meters")
                    }
                    result.actionToBackendDistanceMeters?.let {
                        Text("Distance (action click ↔ backend): ${"%.2f".format(it)} meters")
                    }
                    result.photoToBackendDistanceMeters?.let {
                        Text("Distance (photo ↔ backend): ${"%.2f".format(it)} meters")
                    }
                    Text("Rule threshold: ${result.allowedRadiusMeters.toInt()} meters")
                    Text(result.summary)
                }
            }
        }
    }
}

private fun resetForNewWaybill(onReset: () -> Unit) = onReset()

private suspend fun validateDelivery(
    bitmap: Bitmap,
    delivery: DeliveryPoint,
    actionSnapshot: EventSnapshot?,
    photoSnapshot: EventSnapshot?
): ValidationResult {
    val humanDetected = detectFace(bitmap)
    val radius = delivery.allowedRadiusMeters
    val hasBackendCoordinates = delivery.latitude != null && delivery.longitude != null

    if (actionSnapshot?.latitude == null || actionSnapshot.longitude == null) {
        return ValidationResult(
            isGenuine = false,
            humanDetected = humanDetected,
            validationMode = if (hasBackendCoordinates) "Three-way cross-validation" else "Two-way cross-validation",
            actionClickLatitude = null,
            actionClickLongitude = null,
            actionClickTimestampEpochMillis = actionSnapshot?.timestampEpochMillis,
            photoLatitude = photoSnapshot?.latitude,
            photoLongitude = photoSnapshot?.longitude,
            photoTimestampEpochMillis = photoSnapshot?.timestampEpochMillis,
            expectedLatitude = delivery.latitude,
            expectedLongitude = delivery.longitude,
            actionToPhotoDistanceMeters = null,
            actionToBackendDistanceMeters = null,
            photoToBackendDistanceMeters = null,
            allowedRadiusMeters = radius,
            summary = "Action click location could not be captured. Delivery cannot be validated."
        )
    }

    if (photoSnapshot?.latitude == null || photoSnapshot.longitude == null) {
        return ValidationResult(
            isGenuine = false,
            humanDetected = humanDetected,
            validationMode = if (hasBackendCoordinates) "Three-way cross-validation" else "Two-way cross-validation",
            actionClickLatitude = actionSnapshot.latitude,
            actionClickLongitude = actionSnapshot.longitude,
            actionClickTimestampEpochMillis = actionSnapshot.timestampEpochMillis,
            photoLatitude = null,
            photoLongitude = null,
            photoTimestampEpochMillis = photoSnapshot?.timestampEpochMillis,
            expectedLatitude = delivery.latitude,
            expectedLongitude = delivery.longitude,
            actionToPhotoDistanceMeters = null,
            actionToBackendDistanceMeters = null,
            photoToBackendDistanceMeters = null,
            allowedRadiusMeters = radius,
            summary = "Photo capture location could not be fetched. Delivery cannot be validated."
        )
    }

    val actionToPhotoDistance = distanceMeters(
        actionSnapshot.latitude,
        actionSnapshot.longitude,
        photoSnapshot.latitude,
        photoSnapshot.longitude
    )

    val actionToBackendDistance = if (hasBackendCoordinates) {
        distanceMeters(
            actionSnapshot.latitude,
            actionSnapshot.longitude,
            delivery.latitude!!,
            delivery.longitude!!
        )
    } else null

    val photoToBackendDistance = if (hasBackendCoordinates) {
        distanceMeters(
            photoSnapshot.latitude,
            photoSnapshot.longitude,
            delivery.latitude!!,
            delivery.longitude!!
        )
    } else null

    val actionAndPhotoWithinRadius = actionToPhotoDistance <= radius
    val withinRadius = if (hasBackendCoordinates) {
        actionAndPhotoWithinRadius &&
            (actionToBackendDistance ?: Double.MAX_VALUE) <= radius &&
            (photoToBackendDistance ?: Double.MAX_VALUE) <= radius
    } else {
        actionAndPhotoWithinRadius
    }

    val validationMode = if (hasBackendCoordinates) "Three-way cross-validation" else "Two-way cross-validation"
    val summary = when {
        withinRadius && hasBackendCoordinates ->
            "$validationMode result = GENUINE. Action-click GPS, photo GPS, and backend GPS are all within ${radius.toInt()}m. Human detected = ${if (humanDetected) "Yes" else "No"}."
        withinRadius ->
            "$validationMode result = GENUINE. Action-click GPS and photo GPS are within ${radius.toInt()}m. Human detected = ${if (humanDetected) "Yes" else "No"}."
        hasBackendCoordinates ->
            "$validationMode result = NON GENUINE. Not all location points are within ${radius.toInt()}m. Human detected = ${if (humanDetected) "Yes" else "No"}."
        else ->
            "$validationMode result = NON GENUINE. Action-click GPS and photo GPS are outside ${radius.toInt()}m. Human detected = ${if (humanDetected) "Yes" else "No"}."
    }

    return ValidationResult(
        isGenuine = withinRadius,
        humanDetected = humanDetected,
        validationMode = validationMode,
        actionClickLatitude = actionSnapshot.latitude,
        actionClickLongitude = actionSnapshot.longitude,
        actionClickTimestampEpochMillis = actionSnapshot.timestampEpochMillis,
        photoLatitude = photoSnapshot.latitude,
        photoLongitude = photoSnapshot.longitude,
        photoTimestampEpochMillis = photoSnapshot.timestampEpochMillis,
        expectedLatitude = delivery.latitude,
        expectedLongitude = delivery.longitude,
        actionToPhotoDistanceMeters = actionToPhotoDistance,
        actionToBackendDistanceMeters = actionToBackendDistance,
        photoToBackendDistanceMeters = photoToBackendDistance,
        allowedRadiusMeters = radius,
        summary = summary
    )
}

private suspend fun captureCurrentLocationIfPossible(context: Context): AndroidLocation? {
    return if (isLocationEnabled(context)) getCurrentLocation(context) else null
}

private fun snapshotFromLocation(location: AndroidLocation?): EventSnapshot? {
    val now = System.currentTimeMillis()
    return EventSnapshot(
        latitude = location?.latitude,
        longitude = location?.longitude,
        timestampEpochMillis = now,
        isoTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(now))
    )
}

private suspend fun detectFace(bitmap: Bitmap): Boolean {
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )
    val image = InputImage.fromBitmap(bitmap, 0)
    val faces = detector.process(image).await()
    detector.close()
    return faces.isNotEmpty()
}

private fun isLocationEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}

private fun requestLocationEnable(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>,
    onFailure: () -> Unit
) {
    val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
    val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
    val settingsClient = LocationServices.getSettingsClient(context)
    val task = settingsClient.checkLocationSettings(builder.build())
    task.addOnSuccessListener {}
    task.addOnFailureListener { exception ->
        if (exception is ResolvableApiException) {
            try {
                launcher.launch(IntentSenderRequest.Builder(exception.resolution).build())
            } catch (_: IntentSender.SendIntentException) {
                onFailure()
            }
        } else {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
private suspend fun getCurrentLocation(context: Context): AndroidLocation? {
    val client = LocationServices.getFusedLocationProviderClient(context)
    return try {
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
    } catch (_: SecurityException) {
        null
    }
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val results = FloatArray(1)
    AndroidLocation.distanceBetween(lat1, lon1, lat2, lon2, results)
    return results[0].toDouble()
}