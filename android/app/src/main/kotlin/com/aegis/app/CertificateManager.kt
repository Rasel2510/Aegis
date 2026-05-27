package com.aegis.app

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * DAY 6 — CertificateManager
 *
 * Generates and persists a self-signed RSA-2048 CA certificate.
 * This CA is used by LocalHttpsProxy to sign per-host certificates
 * on the fly, enabling TLS MITM without hard-coding any site.
 *
 * The private key lives only in app private storage (filesDir).
 * The user must manually install the CA cert via:
 *   Settings → Security → Encryption & credentials → Install a certificate → CA certificate
 *
 * Without installation the HTTPS proxy is bypassed gracefully —
 * TLS connections just pass through unfiltered (no breakage).
 *
 * Per-host cert cache: we generate a cert for each SNI the first time
 * we see it, then cache it in memory for the session lifetime.
 */
object CertificateManager {

    private const val TAG          = "CertificateManager"
    private const val KEY_FILE     = "aegis_ca.key"
    private const val CERT_FILE    = "aegis_ca.crt"
    private const val EXPORT_FILE  = "aegis_ca_install.crt"   // user-facing export
    private const val CA_SUBJECT   = "CN=Aegis CA, O=Aegis AdBlocker, C=US"
    private const val VALIDITY_DAYS = 3650  // 10 years

    // In-memory cache: SNI hostname → (certificate, privateKey)
    private val hostCertCache = HashMap<String, Pair<X509Certificate, PrivateKey>>(64)

    private var caCert: X509Certificate? = null
    private var caKey:  PrivateKey?      = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Load existing CA from disk, or generate a new one on first run.
     * Must be called once before any other method.
     */
    fun init(context: Context) {
        val keyFile  = File(context.filesDir, KEY_FILE)
        val certFile = File(context.filesDir, CERT_FILE)

        if (keyFile.exists() && certFile.exists()) {
            try {
                loadFromDisk(keyFile, certFile)
                Log.i(TAG, "CA loaded from disk")
                return
            } catch (e: Exception) {
                Log.w(TAG, "CA load failed, regenerating: ${e.message}")
            }
        }

        generate(context, keyFile, certFile)
        Log.i(TAG, "New CA generated")
    }

    /** Returns the CA certificate, or null if init() hasn't been called. */
    fun getCaCert(): X509Certificate? = caCert

    /** Returns the CA private key. */
    fun getCaKey(): PrivateKey? = caKey

    /**
     * Returns true if the CA cert is installed in the system trust store.
     * We check by looking at the system's TrustManager — if our CA is
     * trusted, HTTPS filtering is available.
     */
    fun isCaInstalled(): Boolean {
        val cert = caCert ?: return false
        return try {
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(null as java.security.KeyStore?)
            val tms = tmf.trustManagers
            for (tm in tms) {
                if (tm is javax.net.ssl.X509TrustManager) {
                    for (accepted in tm.acceptedIssuers) {
                        if (accepted.subjectX500Principal == cert.subjectX500Principal) {
                            return true
                        }
                    }
                }
            }
            false
        } catch (_: Exception) { false }
    }

    /**
     * Export the CA cert as a .crt file to a user-accessible location.
     * Returns the file path the user should install from.
     */
    fun exportCertForInstall(context: Context): File? {
        val cert = caCert ?: return null
        return try {
            val export = File(context.filesDir, EXPORT_FILE)
            val pem = buildString {
                append("-----BEGIN CERTIFICATE-----\n")
                append(Base64.encodeToString(cert.encoded, Base64.DEFAULT))
                append("-----END CERTIFICATE-----\n")
            }
            export.writeText(pem)
            export
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            null
        }
    }

    /**
     * Get-or-create a certificate for [hostname], signed by our CA.
     * Cached in memory — generated once per session per hostname.
     */
    fun getOrCreateHostCert(hostname: String): Pair<X509Certificate, PrivateKey>? {
        hostCertCache[hostname]?.let { return it }

        val ca   = caCert ?: return null
        val caKy = caKey  ?: return null

        return try {
            val pair = generateHostCert(hostname, ca, caKy)
            hostCertCache[hostname] = pair
            pair
        } catch (e: Exception) {
            Log.e(TAG, "Host cert generation failed for $hostname: ${e.message}")
            null
        }
    }

    // ── Certificate generation ─────────────────────────────────────────────

    private fun generate(context: Context, keyFile: File, certFile: File) {
        // Generate RSA-2048 key pair
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), SecureRandom())
        val kp = kpg.generateKeyPair()

        val now     = Date()
        val expiry  = Date(now.time + VALIDITY_DAYS * 86_400_000L)
        val subject = X500Principal(CA_SUBJECT)
        val serial  = BigInteger.valueOf(System.currentTimeMillis())

        val certBuilder = JcaX509v3CertificateBuilder(
            subject, serial, now, expiry, subject, kp.public
        )

        // Mark as CA: basicConstraints = CA:TRUE, pathLen=0
        certBuilder.addExtension(
            Extension.basicConstraints, true,
            BasicConstraints(0)
        )
        // Key usage: keyCertSign, cRLSign
        certBuilder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )
        // Subject key identifier
        val pubKeyInfo = SubjectPublicKeyInfo.getInstance(kp.public.encoded)
        certBuilder.addExtension(
            Extension.subjectKeyIdentifier, false,
            org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
                .createSubjectKeyIdentifier(pubKeyInfo)
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert   = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        // Persist to disk
        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(cert.encoded)

        caCert = cert
        caKey  = kp.private

        // Also export user-installable copy
        exportCertForInstall(context)
    }

    private fun loadFromDisk(keyFile: File, certFile: File) {
        val keyBytes  = keyFile.readBytes()
        val certBytes = certFile.readBytes()

        val kf = KeyFactory.getInstance("RSA")
        caKey  = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))

        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        caCert = cf.generateCertificate(certBytes.inputStream()) as X509Certificate
    }

    private fun generateHostCert(
        hostname: String,
        ca: X509Certificate,
        caKey: PrivateKey,
    ): Pair<X509Certificate, PrivateKey> {
        // Generate a new RSA-2048 key for this host
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val now    = Date()
        val expiry = Date(now.time + 365 * 86_400_000L)  // 1 year
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val subject  = X500Principal("CN=$hostname")
        val issuer   = ca.subjectX500Principal

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer, serial, now, expiry, subject, kp.public
        )

        // SAN: DNS name — required by modern TLS clients
        val san = GeneralNames(GeneralName(GeneralName.dNSName, hostname))
        certBuilder.addExtension(Extension.subjectAlternativeName, false, san)

        // Not a CA
        certBuilder.addExtension(
            Extension.basicConstraints, true, BasicConstraints(false)
        )
        // Key usage: digitalSignature, keyEncipherment
        certBuilder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        // Extended key usage: serverAuth
        certBuilder.addExtension(
            Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(caKey)
        val cert   = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        return Pair(cert, kp.private)
    }
}
