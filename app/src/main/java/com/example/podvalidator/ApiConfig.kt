package com.example.podvalidator

object ApiConfig {
    /**
     * IMPORTANT:
     * Replace this with a Google Apps Script Web App URL or any secured HTTPS endpoint
     * that writes rows into the Google Sheet.
     *
     * The raw Google Sheet edit URL itself is not a writable ingestion endpoint for Android clients.
     */
    const val REPORTING_WEBHOOK_URL: String = BuildConfig.REPORTING_WEBHOOK_URL

    const val WEBHOOK_NOT_CONFIGURED_HINT: String =
        "Webhook pelaporan belum dikonfigurasi. Tambahkan URL Google Apps Script Web App di BuildConfig."
}
