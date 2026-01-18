package com.vrpirates.rookieonquest.data

import android.util.Log

enum class InstallStatus {
    QUEUED,
    DOWNLOADING,
    EXTRACTING,
    COPYING_OBB,
    INSTALLING,
    PAUSED,
    COMPLETED,
    FAILED;

    companion object {
        private const val TAG = "InstallStatus"

        /**
         * Converts a string to InstallStatus enum.
         * Returns QUEUED as fallback for unknown values, with error logging.
         *
         * @param value The string value to convert
         * @return The matching InstallStatus, or QUEUED if not found
         */
        fun fromString(value: String): InstallStatus {
            return entries.find { it.name == value } ?: run {
                Log.e(TAG, "Unknown status value '$value', defaulting to QUEUED. " +
                    "Valid values: ${entries.map { it.name }}")
                QUEUED
            }
        }

        /**
         * Safely converts a string to InstallStatus, returning null if not found.
         * Use this when you need to handle unknown values explicitly.
         */
        fun fromStringOrNull(value: String): InstallStatus? {
            return entries.find { it.name == value }
        }
    }

    override fun toString(): String = name
}
