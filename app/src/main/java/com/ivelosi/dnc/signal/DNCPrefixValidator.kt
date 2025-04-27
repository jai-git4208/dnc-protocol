package com.ivelosi.dnc.signal

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 *
 * DNCPrefixValidator provides validation functionality for device names
 * to determine if they match valid prefixes for the application.
 */
class DNCPrefixValidator {

    companion object {
        // Pool of valid prefixes for device name filtering
        private val VALID_PREFIXES = listOf(
            "DNC-",   // Standard DNC prefix
            "Ive-",   // Ivelosi prefix
            "IVT-",   // Ivelosi Technologies prefix
            "DNCS-"   // DNC Super Node prefix
        )

        /**
         * Checks if the given device name starts with any valid prefix.
         *
         * @param deviceName The name of the device to check
         * @return True if the device name starts with any valid prefix, false otherwise
         */
        fun hasValidPrefix(deviceName: String?): Boolean {
            if (deviceName == null) return false

            return VALID_PREFIXES.any { prefix ->
                deviceName.startsWith(prefix)
            }
        }

        /**
         * Returns the matching prefix for the given device name, or null if no match.
         *
         * @param deviceName The name of the device to check
         * @return The matched prefix or null if no match
         */
        fun getMatchingPrefix(deviceName: String?): String? {
            if (deviceName == null) return null

            return VALID_PREFIXES.firstOrNull { prefix ->
                deviceName.startsWith(prefix)
            }
        }

        /**
         * Gets the list of all valid prefixes.
         *
         * @return List of all valid prefixes
         */
        fun getValidPrefixes(): List<String> {
            return VALID_PREFIXES
        }

        /**
         * Extracts the username part from a device name with a valid prefix.
         * For example, "DNC-User123" would return "User123".
         *
         * @param deviceName The name of the device
         * @return The username part or null if no valid prefix found
         */
        fun extractUsername(deviceName: String?): String? {
            if (deviceName == null) return null

            val matchingPrefix = getMatchingPrefix(deviceName) ?: return null
            return deviceName.substring(matchingPrefix.length)
        }
    }
}