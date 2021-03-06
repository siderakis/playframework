/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server.netty

import play.api.{ Play, Logger, Application }
import java.security.{ KeyStore, SecureRandom, KeyPairGenerator, KeyPair }
import sun.security.x509._
import java.util.Date
import java.math.BigInteger
import java.security.cert.X509Certificate
import java.io.{ File, FileInputStream, FileOutputStream }
import javax.net.ssl.KeyManagerFactory
import scala.util.control.NonFatal
import scala.util.Properties.isJavaAtLeast
import scala.util.{ Failure, Success, Try }
import play.utils.PlayIO

/**
 * A fake key store
 */
object FakeKeyStore {
  val GeneratedKeyStore = "conf/generated.keystore"
  val DnName = "CN=localhost, OU=Unit Testing, O=Mavericks, L=Moon Base 1, ST=Cyberspace, C=CY"

  def keyManagerFactory(appPath: File): Try[KeyManagerFactory] = {
    try {
      val keyStore = KeyStore.getInstance("JKS")
      val keyStoreFile = new File(appPath, GeneratedKeyStore)
      if (!keyStoreFile.exists()) {

        Play.logger.info("Generating HTTPS key pair in " + keyStoreFile.getAbsolutePath + " - this may take some time. If nothing happens, try moving the mouse/typing on the keyboard to generate some entropy.")

        // Generate the key pair
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(1024)
        val keyPair = keyPairGenerator.generateKeyPair()

        // Generate a self signed certificate
        val cert = createSelfSignedCertificate(keyPair)

        // Create the key store, first set the store pass
        keyStore.load(null, "".toCharArray)
        keyStore.setKeyEntry("playgenerated", keyPair.getPrivate, "".toCharArray, Array(cert))
        keyStore.setCertificateEntry("playgeneratedtrusted", cert)
        val out = new FileOutputStream(keyStoreFile)
        try {
          keyStore.store(out, "".toCharArray)
        } finally {
          PlayIO.closeQuietly(out)
        }
      } else {
        val in = new FileInputStream(keyStoreFile)
        try {
          keyStore.load(in, "".toCharArray)
        } finally {
          PlayIO.closeQuietly(in)
        }
      }

      // Load the key and certificate into a key manager factory
      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(keyStore, "".toCharArray)
      Success(kmf)
    } catch {
      case NonFatal(e) => {
        Failure(new Exception("Error loading fake key store", e))
      }
    }
  }

  def createSelfSignedCertificate(keyPair: KeyPair): X509Certificate = {
    val certInfo = new X509CertInfo()

    // Serial number and version
    certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(64, new SecureRandom())))
    certInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3))

    // Validity
    val validFrom = new Date()
    val validTo = new Date(validFrom.getTime + 50l * 365l * 24l * 60l * 60l * 1000l)
    val validity = new CertificateValidity(validFrom, validTo)
    certInfo.set(X509CertInfo.VALIDITY, validity)

    // Subject and issuer
    // Note: CertificateSubjectName and CertificateIssuerName are removed in Java 8
    // and when setting the subject or issuer just the X500Name should be used.
    val owner = new X500Name(DnName)
    val justName = isJavaAtLeast("1.8")
    certInfo.set(X509CertInfo.SUBJECT, if (justName) owner else new CertificateSubjectName(owner))
    certInfo.set(X509CertInfo.ISSUER, if (justName) owner else new CertificateIssuerName(owner))

    // Key and algorithm
    certInfo.set(X509CertInfo.KEY, new CertificateX509Key(keyPair.getPublic))
    val algorithm = new AlgorithmId(AlgorithmId.sha1WithRSAEncryption_oid)
    certInfo.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algorithm))

    // Create a new certificate and sign it
    val cert = new X509CertImpl(certInfo)
    cert.sign(keyPair.getPrivate, "SHA1withRSA")

    // Since the SHA1withRSA provider may have a different algorithm ID to what we think it should be,
    // we need to reset the algorithm ID, and resign the certificate
    val actualAlgorithm = cert.get(X509CertImpl.SIG_ALG).asInstanceOf[AlgorithmId]
    certInfo.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, actualAlgorithm)
    val newCert = new X509CertImpl(certInfo)
    newCert.sign(keyPair.getPrivate, "SHA1withRSA")
    newCert
  }
}
