package com.medicabinet.app.features.scan

/**
 * What a recognised code does. Always [ADD] when the Scan tab opens and at launch: Remove is never remembered
 * (requirement R1), so a box is never taken out by a scan the user meant as an intake.
 */
enum class ScanMode { ADD, REMOVE }
