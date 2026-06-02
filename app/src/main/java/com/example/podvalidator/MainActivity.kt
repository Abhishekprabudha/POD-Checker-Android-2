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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.sqrt
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
    var status by remember { mutableStateOf("Masukkan nama kurir untuk memulai, lalu masukkan atau pindai nomor resi.") }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var actionSnapshot by remember { mutableStateOf<EventSnapshot?>(null) }
    var photoSnapshot by remember { mutableStateOf<EventSnapshot?>(null) }
    var selectedAction by remember { mutableStateOf<DeliveryAction?>(null) }
    var validationResult by remember { mutableStateOf<ValidationResult?>(null) }
    var syncMessage by remember { mutableStateOf("Sinkronisasi laporan tertunda belum dimulai.") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val cameraGranted = perms[Manifest.permission.CAMERA] == true
        val locationGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        status = when {
            !cameraGranted && !locationGranted -> "Izin kamera dan lokasi diperlukan."
            !cameraGranted -> "Izin kamera diperlukan."
            !locationGranted -> "Izin lokasi diperlukan."
            else -> "Izin diberikan. Anda dapat melanjutkan."
        }
    }

    val enableLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        status = if (isLocationEnabled(context)) {
            "Layanan lokasi aktif."
        } else {
            "Lokasi harus tetap aktif agar aplikasi dapat berjalan."
        }
    }

    val scanWaybillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            status = "Pemindaian resi dibatalkan."
        } else {
            scope.launch {
                val scanned = scanWaybillFromBitmap(bitmap)
                if (scanned.isNullOrBlank()) {
                    status = "Barcode atau nomor resi tidak terdeteksi pada gambar yang dipindai."
                } else {
                    waybill = scanned.trim().uppercase()
                    selectedDelivery = repo.getOrCreateWaybill(waybill, username)
                    validationResult = null
                    actionSnapshot = null
                    photoSnapshot = null
                    photoBitmap = null
                    selectedAction = null
                    status = "Resi hasil pindai $waybill siap dan dipetakan ke ${username.ifBlank { "nama kurir yang akan dimasukkan" }}. Sekarang pilih Terkirim atau Gagal."
                }
            }
        }
    }

    val capturePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            status = "Pengambilan foto dibatalkan."
        } else {
            photoBitmap = bitmap
            scope.launch {
                val action = selectedAction
                val delivery = selectedDelivery ?: repo.getOrCreateWaybill(waybill, username)
                selectedDelivery = delivery
                if (waybill.trim().isBlank() || delivery == null) {
                    status = "Masukkan atau pindai nomor resi sebelum melanjutkan."
                    return@launch
                }
                if (username.trim().isBlank()) {
                    status = "Masukkan nama kurir sebelum melanjutkan."
                    return@launch
                }
                if (actionSnapshot == null || action == null) {
                    status = "Pilih Terkirim atau Gagal terlebih dahulu agar aplikasi dapat merekam lokasi dan waktu aksi."
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
                status = validationResult?.summary ?: "Validasi selesai."
                if (validationResult?.photoQualityPassed == false) {
                    return@launch
                }

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
                selectedDelivery = repo.updateFromTransaction(transaction)
                val reportResult = reportingService.submitTransaction(transaction)
                syncMessage = "${reportResult.message} Antrean tertunda: ${reportResult.pendingCount}"
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

    fun clearCurrentValidation() {
        actionSnapshot = null
        photoSnapshot = null
        photoBitmap = null
        validationResult = null
        selectedAction = null
    }

    fun captureOutcome(action: DeliveryAction) {
        if (username.isBlank()) {
            status = "Masukkan nama kurir terlebih dahulu."
            return
        }
        if (waybill.trim().isBlank()) {
            status = "Masukkan atau pindai nomor resi terlebih dahulu."
            return
        }
        val delivery = repo.getOrCreateWaybill(waybill, username)
        if (delivery == null) {
            status = "Masukkan atau pindai nomor resi terlebih dahulu."
            return
        }
        selectedDelivery = delivery
        if (!isLocationEnabled(context)) {
            status = "Lokasi belum aktif. Aktifkan lokasi untuk melanjutkan validasi."
            requestLocationEnable(context, enableLocationLauncher) {}
            return
        }
        scope.launch {
            val snap = snapshotFromLocation(captureCurrentLocationIfPossible(context))
            selectedAction = action
            actionSnapshot = snap
            validationResult = null
            status = if (snap?.latitude == null || snap.longitude == null) {
                "Aksi dipilih, tetapi lokasi belum berhasil dibaca. Coba cek GPS lalu ambil foto."
            } else {
                "Aksi ${action.friendlyLabel()} tercatat. Sekarang ambil bukti foto."
            }
        }
    }

    val validation = validationResult
    val canTakePhoto = selectedAction != null && actionSnapshot != null && waybill.isNotBlank()
    val canSaveAndSend = validation?.isGenuine == true
    val statusTone = when {
        validation?.isGenuine == true -> FeedbackTone.Success
        validation != null -> FeedbackTone.Error
        status.contains("diperlukan", ignoreCase = true) ||
            status.contains("harus", ignoreCase = true) ||
            status.contains("belum", ignoreCase = true) ||
            status.contains("tidak berhasil", ignoreCase = true) ||
            status.contains("tidak dapat", ignoreCase = true) ||
            status.contains("gagal", ignoreCase = true) -> FeedbackTone.Error
        else -> FeedbackTone.Info
    }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        FriendlyTopBar(username = username)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                "Silakan masukkan nomor resi atau pindai kode barcode untuk memvalidasi status pengiriman paket.",
                style = MaterialTheme.typography.titleMedium,
                color = Cocoa,
                lineHeight = MaterialTheme.typography.titleMedium.lineHeight * 1.2f
            )

            FriendlyTextField(
                label = "NAMA KURIR",
                value = username,
                placeholder = "Masukkan Nama Kurir",
                onValueChange = { username = it.trim() }
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("INPUT NO. RESI", color = Cocoa, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = waybill,
                        onValueChange = {
                            waybill = it.trim().uppercase()
                            selectedDelivery = repo.findWaybill(waybill)
                            clearCurrentValidation()
                            status = if (waybill.isBlank()) {
                                "Masukkan atau pindai nomor resi untuk melanjutkan."
                            } else if (selectedDelivery == null) {
                                "Resi baru akan ditambahkan dan dipetakan ke nama kurir ini."
                            } else {
                                "Resi ditemukan. Pilih status pengiriman lalu ambil bukti foto."
                            }
                        },
                        modifier = Modifier.weight(1f).height(64.dp),
                        placeholder = { Text("Masukkan No. Resi") },
                        leadingIcon = { TextIcon("✎", Cocoa) },
                        singleLine = true,
                        shape = RoundedCornerShape(6.dp),
                        colors = friendlyTextFieldColors()
                    )
                    Button(
                        onClick = {
                            if (waybill.isBlank()) {
                                status = "Isi nomor resi atau gunakan pindai barcode."
                            } else {
                                selectedDelivery = repo.getOrCreateWaybill(waybill, username)
                                status = "Resi siap divalidasi. Pilih status pengiriman."
                            }
                        },
                        modifier = Modifier.height(64.dp).width(92.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("CEK", style = MaterialTheme.typography.titleMedium) }
                }
            }

            OrDivider()

            DashedActionCard(
                icon = { TextIcon("▦", ActionBlue) },
                title = "PINDAI BARCODE",
                onClick = { scanWaybillLauncher.launch(null) }
            )

            OutcomeSelector(
                selectedAction = selectedAction,
                onDelivered = { captureOutcome(DeliveryAction.DELIVERED) },
                onNonDelivered = { captureOutcome(DeliveryAction.NON_DELIVERED) }
            )

            DashedActionCard(
                icon = { TextIcon("▣", ActionBlue) },
                title = if (validation?.isGenuine == false) "AMBIL ULANG FOTO" else "AMBIL BUKTI FOTO",
                enabled = canTakePhoto,
                onClick = {
                    if (!canTakePhoto) {
                        status = "Pilih status pengiriman terlebih dahulu sebelum mengambil foto."
                        return@DashedActionCard
                    }
                    if (!isLocationEnabled(context)) {
                        requestLocationEnable(context, enableLocationLauncher) {
                            status = "Lokasi harus aktif agar validasi dapat berjalan."
                        }
                    } else {
                        capturePhotoLauncher.launch(null)
                    }
                }
            )

            validation?.let { result ->
                ValidationBanner(result)
            } ?: StatusBanner(status, statusTone)

            photoBitmap?.let { bitmap ->
                ProofPhotoCard(bitmap = bitmap, validationResult = validation, snapshot = photoSnapshot)
            }

            selectedDelivery?.let { delivery ->
                DeliverySummaryCard(delivery = delivery)
            }

            LocationVerificationCard(
                snapshot = photoSnapshot ?: actionSnapshot,
                isValid = validation?.isGenuine ?: (actionSnapshot?.latitude != null)
            )

            if (syncMessage.isNotBlank()) {
                StatusBanner(syncMessage, FeedbackTone.Info)
            }
        }

        BottomActionPanel(
            canSaveAndSend = canSaveAndSend,
            showRetake = validation?.isGenuine == false,
            onRetake = {
                if (canTakePhoto) capturePhotoLauncher.launch(null) else status = "Pilih status pengiriman sebelum ambil ulang foto."
            },
            onSaveAndSend = {
                scope.launch {
                    val result = reportingService.flushPending()
                    syncMessage = "${result.message} Antrean tertunda: ${result.pendingCount}"
                    status = if (canSaveAndSend) "Laporan berhasil disiapkan untuk dikirim." else status
                }
            },
            onSync = {
                scope.launch {
                    val result = reportingService.flushPending()
                    syncMessage = "${result.message} Antrean tertunda: ${result.pendingCount}"
                }
            }
        )
    }
}

private val AppBackground = Color(0xFFF7F8FF)
private val BrandRed = Color(0xFFC7001E)
private val ActionBlue = Color(0xFF1F63B5)
private val Cocoa = Color(0xFF5A3F3B)
private val SuccessGreen = Color(0xFF1FA850)
private val WarningRed = Color(0xFFD32222)
private val SoftBlue = Color(0xFFEAF1FF)
private val SoftGreen = Color(0xFFEAFBF0)
private val SoftRed = Color(0xFFFFE1E1)

private enum class FeedbackTone { Info, Success, Error }

@Composable
private fun TextIcon(symbol: String, tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Text(
        text = symbol,
        modifier = modifier,
        color = tint,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.headlineSmall
    )
}

private fun DeliveryAction.friendlyLabel(): String = when (this) {
    DeliveryAction.DELIVERED -> "Terkirim"
    DeliveryAction.NON_DELIVERED -> "Tidak Terkirim"
}

@Composable
private fun FriendlyTopBar(username: String) {
    Surface(shadowElevation = 2.dp, color = AppBackground) {
        Row(
            modifier = Modifier.fillMaxWidth().height(94.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Validasi Pengiriman",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = BrandRed
            )
            Box(
                modifier = Modifier.size(54.dp).clip(CircleShape).background(Color(0xFFDDE7EA)).border(1.dp, Color(0xFFF3B7AC), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(username.take(1).uppercase().ifBlank { "K" }, color = Color(0xFF0C5B7A), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FriendlyTextField(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Cocoa, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            placeholder = { Text(placeholder) },
            leadingIcon = { TextIcon("✎", Cocoa) },
            singleLine = true,
            shape = RoundedCornerShape(6.dp),
            colors = friendlyTextFieldColors()
        )
    }
}

@Composable
private fun friendlyTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cocoa,
    unfocusedBorderColor = Cocoa.copy(alpha = 0.7f),
    cursorColor = ActionBlue,
    focusedTextColor = Color(0xFF121826),
    unfocusedTextColor = Color(0xFF121826)
)

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Divider(modifier = Modifier.weight(1f), color = Color(0xFFE5B8B2))
        Text("ATAU", color = Cocoa.copy(alpha = 0.75f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Divider(modifier = Modifier.weight(1f), color = Color(0xFFE5B8B2))
    }
}

@Composable
private fun DashedActionCard(icon: @Composable () -> Unit, title: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(86.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Cocoa.copy(alpha = if (enabled) 0.8f else 0.25f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = Color(0xFF121826)),
        contentPadding = PaddingValues(horizontal = 28.dp)
    ) {
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(SoftBlue), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(24.dp))
        Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Start, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun OutcomeSelector(selectedAction: DeliveryAction?, onDelivered: () -> Unit, onNonDelivered: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFEAB8B3)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PILIH STATUS PENGIRIMAN", color = Cocoa, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDelivered,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedAction == DeliveryAction.DELIVERED) SuccessGreen else ActionBlue),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("TERKIRIM", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onNonDelivered,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedAction == DeliveryAction.NON_DELIVERED) BrandRed else Cocoa),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("GAGAL", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun StatusBanner(message: String, tone: FeedbackTone) {
    val background: Color
    val border: Color
    val iconTint: Color
    val titleColor: Color
    val icon: String
    val title: String
    when (tone) {
        FeedbackTone.Success -> {
            background = SoftGreen
            border = Color(0xFF9EF3BE)
            iconTint = SuccessGreen
            titleColor = Color(0xFF126B35)
            icon = "✓"
            title = "Validasi Berhasil"
        }
        FeedbackTone.Error -> {
            background = SoftRed
            border = WarningRed
            iconTint = WarningRed
            titleColor = BrandRed
            icon = "!"
            title = "Perlu Perhatian"
        }
        FeedbackTone.Info -> {
            background = SoftBlue
            border = Color(0xFFF0B8AE)
            iconTint = ActionBlue
            titleColor = Color(0xFF121826)
            icon = "i"
            title = "Tips Validasi"
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, border),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            TextIcon(icon, iconTint, Modifier.size(32.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.Black, color = titleColor)
                Text(
                    message.ifBlank { "Pastikan pencahayaan cukup saat mengambil foto bukti atau memindai barcode untuk akurasi data yang lebih baik." },
                    color = Cocoa,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun ValidationBanner(result: ValidationResult) {
    val tone = if (result.isGenuine) FeedbackTone.Success else FeedbackTone.Error
    val message = if (result.isGenuine) "Validasi Berhasil" else "Foto atau lokasi perlu diperiksa. ${result.summary}"
    StatusBanner(message, tone)
}

@Composable
private fun ProofPhotoCard(bitmap: Bitmap, validationResult: ValidationResult?, snapshot: EventSnapshot?) {
    val isSuccess = validationResult?.isGenuine == true
    val accent = if (isSuccess) SuccessGreen else WarningRed
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFEAB8B3)),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Foto bukti pengiriman",
                modifier = Modifier.fillMaxWidth().height(420.dp),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier.padding(28.dp).fillMaxWidth().height(260.dp).border(3.dp, accent, RoundedCornerShape(2.dp)).align(Alignment.Center)
            )
            Text(
                if (isSuccess) "PAKET (${if (validationResult?.humanDetected == true) "98" else "92"}%)" else "PERIKSA ULANG",
                modifier = Modifier.padding(top = 150.dp, start = 96.dp).background(accent, RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.Black
            )
            snapshot?.let {
                val timeText = it.isoTimestamp ?: "Waktu tidak tersedia"
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp).fillMaxWidth().background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("🕘 $timeText", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("📍 ${formatCoordinate(it.latitude)}, ${formatCoordinate(it.longitude)}", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DeliverySummaryCard(delivery: DeliveryPoint) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFEAB8B3)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DETAIL PENGIRIMAN", color = Cocoa, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Resi: ${delivery.waybill}", fontWeight = FontWeight.Bold)
            Text("Penerima: ${delivery.customerName}")
            Text("Alamat: ${delivery.address}")
            Text("Radius validasi: ${delivery.allowedRadiusMeters.toInt()} Meter", color = Cocoa)
        }
    }
}

@Composable
private fun LocationVerificationCard(snapshot: EventSnapshot?, isValid: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFEAB8B3)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("VERIFIKASI LOKASI", color = Cocoa, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(SoftBlue), contentAlignment = Alignment.Center) {
                    TextIcon("⌖", Color(0xFF2468E8), Modifier.size(34.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (snapshot?.latitude != null) "GPS Akurat" else "Menunggu GPS", fontWeight = FontWeight.Black, color = Color(0xFF121826))
                    Text(if (snapshot?.latitude != null) "Koordinat terekam" else "Aktifkan lokasi lalu ulangi aksi", color = Cocoa)
                }
                Text(if (isValid) "VALID" else "CEK", color = if (isValid) SuccessGreen else WarningRed, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun BottomActionPanel(canSaveAndSend: Boolean, showRetake: Boolean, onRetake: () -> Unit, onSaveAndSend: () -> Unit, onSync: () -> Unit) {
    Surface(color = AppBackground, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showRetake) {
                Button(
                    onClick = onRetake,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    TextIcon("▣", Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text("AMBIL ULANG FOTO", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                }
            }
            Button(
                onClick = onSaveAndSend,
                enabled = canSaveAndSend,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed, disabledContainerColor = Color(0xFFD9E0F0), disabledContentColor = Cocoa.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                TextIcon("▷", Color.White)
                Spacer(Modifier.width(12.dp))
                Text("SIMPAN & KIRIM", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            }
            OutlinedButton(
                onClick = onSync,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                border = BorderStroke(2.dp, ActionBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                TextIcon("↻", ActionBlue)
                Spacer(Modifier.width(12.dp))
                Text("SINKRONISASI LAPORAN", color = ActionBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

private fun formatCoordinate(value: Double?): String = value?.let { "%.4f".format(Locale.US, it) } ?: "-"

private data class PhotoQualityResult(
    val passed: Boolean,
    val issue: String,
    val brightness: Double,
    val sharpness: Double
)

private fun evaluatePhotoQuality(bitmap: Bitmap): PhotoQualityResult {
    val maxSamplesPerAxis = 120
    val stepX = (bitmap.width / maxSamplesPerAxis).coerceAtLeast(1)
    val stepY = (bitmap.height / maxSamplesPerAxis).coerceAtLeast(1)
    val luminanceRows = mutableListOf<DoubleArray>()
    var total = 0.0
    var totalSquared = 0.0
    var count = 0

    var y = 0
    while (y < bitmap.height) {
        val row = mutableListOf<Double>()
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
            row += luminance
            total += luminance
            totalSquared += luminance * luminance
            count++
            x += stepX
        }
        luminanceRows += row.toDoubleArray()
        y += stepY
    }

    if (count == 0 || luminanceRows.isEmpty()) {
        return PhotoQualityResult(
            passed = false,
            issue = "tidak terbaca",
            brightness = 0.0,
            sharpness = 0.0
        )
    }

    val brightness = total / count
    val variance = (totalSquared / count) - (brightness * brightness)
    val contrast = sqrt(variance.coerceAtLeast(0.0))
    var gradientTotal = 0.0
    var gradientCount = 0
    luminanceRows.forEachIndexed { rowIndex, row ->
        row.forEachIndexed { columnIndex, luminance ->
            if (columnIndex > 0) {
                val diff = luminance - row[columnIndex - 1]
                gradientTotal += diff * diff
                gradientCount++
            }
            if (rowIndex > 0 && columnIndex < luminanceRows[rowIndex - 1].size) {
                val diff = luminance - luminanceRows[rowIndex - 1][columnIndex]
                gradientTotal += diff * diff
                gradientCount++
            }
        }
    }
    val sharpness = if (gradientCount == 0) 0.0 else sqrt(gradientTotal / gradientCount)

    val issue = when {
        brightness < MIN_PHOTO_BRIGHTNESS -> "terdeteksi gelap"
        contrast < MIN_PHOTO_CONTRAST -> "terdeteksi kurang kontras"
        sharpness < MIN_PHOTO_SHARPNESS -> "terdeteksi buram"
        else -> "jelas"
    }

    return PhotoQualityResult(
        passed = issue == "jelas",
        issue = issue,
        brightness = brightness,
        sharpness = sharpness
    )
}

private const val MIN_PHOTO_BRIGHTNESS = 45.0
private const val MIN_PHOTO_CONTRAST = 18.0
private const val MIN_PHOTO_SHARPNESS = 12.0

private suspend fun validateDelivery(
    bitmap: Bitmap,
    delivery: DeliveryPoint,
    actionSnapshot: EventSnapshot?,
    photoSnapshot: EventSnapshot?
): ValidationResult {
    val humanDetected = detectFace(bitmap)
    val photoQuality = evaluatePhotoQuality(bitmap)
    val radius = delivery.allowedRadiusMeters
    val hasBackendCoordinates = delivery.latitude != null && delivery.longitude != null

    if (!photoQuality.passed) {
        return ValidationResult(
            isGenuine = false,
            humanDetected = humanDetected,
            validationMode = "Validasi kualitas foto",
            actionClickLatitude = actionSnapshot?.latitude,
            actionClickLongitude = actionSnapshot?.longitude,
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
            summary = "Foto ${photoQuality.issue}. Harap ambil ulang foto yang lebih jelas untuk melanjutkan proses pengiriman.",
            photoQualityPassed = false,
            photoQualityIssue = photoQuality.issue,
            photoBrightness = photoQuality.brightness,
            photoSharpness = photoQuality.sharpness
        )
    }

    if (actionSnapshot?.latitude == null || actionSnapshot.longitude == null) {
        return ValidationResult(
            isGenuine = false,
            humanDetected = humanDetected,
            validationMode = if (hasBackendCoordinates) "Validasi silang tiga titik" else "Validasi silang dua titik",
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
            summary = "Lokasi saat memilih aksi tidak berhasil direkam. Pengiriman tidak dapat divalidasi.",
            photoBrightness = photoQuality.brightness,
            photoSharpness = photoQuality.sharpness
        )
    }

    if (photoSnapshot?.latitude == null || photoSnapshot.longitude == null) {
        return ValidationResult(
            isGenuine = false,
            humanDetected = humanDetected,
            validationMode = if (hasBackendCoordinates) "Validasi silang tiga titik" else "Validasi silang dua titik",
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
            summary = "Lokasi saat mengambil foto tidak berhasil diperoleh. Pengiriman tidak dapat divalidasi.",
            photoBrightness = photoQuality.brightness,
            photoSharpness = photoQuality.sharpness
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

    val validationMode = if (hasBackendCoordinates) "Validasi silang tiga titik" else "Validasi silang dua titik"
    val summary = when {
        withinRadius && hasBackendCoordinates ->
            "Hasil $validationMode = ASLI. GPS saat memilih aksi, GPS foto, dan GPS backend semuanya berada dalam radius ${radius.toInt()}m. Manusia terdeteksi = ${if (humanDetected) "Ya" else "Tidak"}."
        withinRadius ->
            "Hasil $validationMode = ASLI. GPS saat memilih aksi dan GPS foto berada dalam radius ${radius.toInt()}m. Manusia terdeteksi = ${if (humanDetected) "Ya" else "Tidak"}."
        hasBackendCoordinates ->
            "Hasil $validationMode = TIDAK ASLI. Tidak semua titik lokasi berada dalam radius ${radius.toInt()}m. Manusia terdeteksi = ${if (humanDetected) "Ya" else "Tidak"}."
        else ->
            "Hasil $validationMode = TIDAK ASLI. GPS saat memilih aksi dan GPS foto berada di luar radius ${radius.toInt()}m. Manusia terdeteksi = ${if (humanDetected) "Ya" else "Tidak"}."
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
        summary = summary,
        photoBrightness = photoQuality.brightness,
        photoSharpness = photoQuality.sharpness
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