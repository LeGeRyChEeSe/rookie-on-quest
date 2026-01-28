package com.vrpirates.rookieonquest.data

import android.util.Log

enum class InstallStatus {
    QUEUED,
    DOWNLOADING,
    EXTRACTING,
    /**
     * Story 1.7 Code Review Round 9 Note:
     * COPYING_OBB is retained for backward compatibility with existing Room databases.
     *
     * This state was planned for fine-grained OBB copy progress tracking but is not
     * actively used in the current implementation. Instead, OBB copying is reported
     * as part of the INSTALLING phase with progress messages.
     *
     * Why not remove:
     * 1. Room databases may contain COPYING_OBB values from v2.5.0+ migrations
     * 2. Removing would cause enum parsing failures for existing installations
     * 3. The state is safely mapped to INSTALLING in the UI layer (no user impact)
     *
     * Future consideration: Can be removed in a major version with proper migration.
     */
    COPYING_OBB,
    INSTALLING,
    PENDING_INSTALL,  // Story 1.7: APK staged and installer launched, waiting for user to complete
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
