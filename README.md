# POD Delivery Validator Android App - Advanced JNE Flow

This repository extends the original POD validator demo with a more operational courier workflow.

## What is included now

1. **Courier username capture**
   - User must enter a username before processing a shipment.
   - Every transaction is tagged to that username.

2. **Waybill entry or scan**
   - User can type the waybill manually.
   - User can also use **Scan Waybill** to read a barcode/waybill from the mobile camera.

3. **Shipment outcome click capture**
   - User must click either **Delivered** or **Non-Delivered**.
   - At the instant of that click, the app captures:
     - GPS coordinates
     - timestamp

4. **Proof photo capture**
   - User takes a live photo from the camera.
   - At the time of the photo, the app captures:
     - GPS coordinates
     - timestamp

5. **Validation logic**
   - The app checks whether a visible human face is detected.
   - The human result is recorded but **does not decide genuineness**.
   - Genuineness is now based on location triangulation:
     - Delivered/Non-Delivered button click GPS
     - photo capture GPS
     - backend/mapped GPS from `deliveries.json` when available
   - Default threshold is **100 meters**.

6. **Reporting integration**
   - Each transaction is converted into a reporting record.
   - Records are queued locally if sync fails.
   - Sync is attempted to a configurable HTTPS webhook endpoint.

## Important note about Google Sheets
The raw Google Sheets edit link is **not** a direct ingestion API endpoint for an Android app.

To write data from this app into the JNE Google Sheet, use one of these patterns:

- **Recommended:** Google Apps Script Web App that appends rows to the target sheet
- Or a secured middleware API that writes to Google Sheets

This repo already supports posting JSON to a configurable webhook URL.

### Where to configure the webhook
In `app/build.gradle.kts`, set:

```kotlin
buildConfigField("String", "REPORTING_WEBHOOK_URL", "\"https://your-google-apps-script-webapp-url\"")
```

If you leave it blank, the app will still work for validation, but report sync will remain queued locally.

## Reporting payload
The app sends a JSON payload shaped like:

```json
{
  "spreadsheetTarget": "JNE Courier POD Report",
  "records": [
    {
      "username": "courier001",
      "waybill": "WB1001",
      "action": "DELIVERED",
      "decision": "GENUINE",
      "humanDetected": true,
      "actionClickLatitude": -6.2,
      "actionClickLongitude": 106.8,
      "photoLatitude": -6.2002,
      "photoLongitude": 106.8003
    }
  ]
}
```

## User flow
1. Enter courier username
2. Enter or scan waybill
3. Click **Delivered** or **Non-Delivered**
4. Take proof photo
5. App evaluates genuine / not genuine
6. App stores and syncs the record for reporting

## Validation rules
### Genuine
- action-click GPS and photo GPS are within 100m
- and, where backend coordinates are available, they also fall within the allowed radius

### Not Genuine
- action-click GPS and photo GPS are outside threshold
- or one or more required GPS snapshots are missing
- or backend triangulation fails where backend coordinates are available

### Human detection
- Recorded as an attribute only
- No longer blocks a transaction from being genuine

## Files added / changed
- `MainActivity.kt` → enhanced flow for username, status buttons, reporting and sync
- `Models.kt` → new event, transaction and reporting models
- `BarcodeScanner.kt` → barcode/waybill scan helper
- `ReportingService.kt` → webhook sync + local queue
- `ApiConfig.kt` → reporting endpoint configuration
- `AndroidManifest.xml` → internet permission
- `app/build.gradle.kts` → barcode scanning + OkHttp + BuildConfig field

## GitHub web upload flow
1. Create an empty GitHub repo
2. Upload all extracted files through GitHub web
3. Let GitHub Actions build the APK
4. Download the artifact from the workflow run

## Limitation you should know
This repo is ready for:
- advanced courier flow
- transaction tagging by username
- location-based validation
- local queueing of report records

But for the **specific Google Sheet you shared**, you still need a working ingestion endpoint.
Without that endpoint, no Android app can reliably write directly into the raw sheet URL.

## Recommended next step
Create a Google Apps Script Web App bound to the target Google Sheet and use its deployment URL as `REPORTING_WEBHOOK_URL`.