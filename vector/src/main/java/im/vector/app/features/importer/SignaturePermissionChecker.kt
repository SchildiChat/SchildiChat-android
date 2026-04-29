/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.importer

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import im.vector.app.BuildConfig
import timber.log.Timber
import java.security.MessageDigest

class SignaturePermissionChecker {
    /**
     * Check if the calling UID is allowed to access the service.
     * @param sendingUid The UID of the calling process.
     * @param pm The PackageManager to use to get package info.
     * @return True if the calling UID is allowed, false otherwise.
     */
    fun check(sendingUid: Int, pm: PackageManager): Boolean {
        Timber.w("ImporterService: callingUid: $sendingUid")
        val pkgs = pm.getPackagesForUid(sendingUid) ?: return false
        for (pkg in pkgs) {
            Timber.w("ImporterService: checking package: $pkg")
            BuildConfig.ALLOWED_APP_SIGNATURES.find { it.appId == pkg }?.let { allowedApp ->
                Timber.d("ImporterService: found allowed appId $pkg, checking signature")
                if (isSignatureAllowed(allowedApp, pm)) {
                    Timber.d("ImporterService: signature allowed for package $pkg")
                    return true
                } else {
                    Timber.w("ImporterService: signature not allowed for package $pkg")
                }
            }
        }
        Timber.e("ImporterService: Unauthorized attempt, denying")
        return false
    }

    private fun isSignatureAllowed(applicationFingerprint: ApplicationFingerprint, pm: PackageManager): Boolean {
        try {
            val fingerprints = getSignatureFingerprints(pm, applicationFingerprint.appId)
            // The fingerprint list must exactly match the expected one
            Timber.d("isSignatureAllowed: checking fingerprints $fingerprints")
            return fingerprints == applicationFingerprint.fingerprints
        } catch (e: Exception) {
            Timber.w(e, "signature check failed for ${applicationFingerprint.appId}")
        }
        Timber.w("isSignatureAllowed: not allowed")
        return false
    }

    private fun getSignatureFingerprints(pm: PackageManager, packageName: String): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pkgInfo: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            if (pkgInfo.signingInfo?.hasMultipleSigners() == true) {
                pkgInfo.signingInfo?.apkContentsSigners
            } else {
                pkgInfo.signingInfo?.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            val pkgInfo: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
        if (signatures.isNullOrEmpty()) {
            Timber.w("ImporterService: isSignatureAllowed: no signatures found for package $packageName")
        }
        return signatures.orEmpty().map { sig ->
            sha256Hex(sig.toByteArray())
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
