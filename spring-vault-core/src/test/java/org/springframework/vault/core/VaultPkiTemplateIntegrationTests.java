/*
 * Copyright 2016-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.core;

import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.assertj.core.util.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.StreamUtils;
import org.springframework.vault.VaultException;
import org.springframework.vault.core.VaultPkiOperations.Encoding;
import org.springframework.vault.support.Certificate;
import org.springframework.vault.support.CertificateBundle;
import org.springframework.vault.support.VaultCertificateRequest;
import org.springframework.vault.support.VaultCertificateResponse;
import org.springframework.vault.support.VaultSignCertificateRequestResponse;
import org.springframework.vault.util.IntegrationTestSupport;
import org.springframework.vault.util.RequiresVaultVersion;
import org.springframework.vault.util.Version;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.vault.util.Settings.*;

/**
 * Integration tests for {@link VaultPkiTemplate} through {@link VaultPkiOperations}.
 *
 * @author Mark Paluch
 * @author Alex Bremora
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = VaultIntegrationTestConfiguration.class)
class VaultPkiTemplateIntegrationTests extends IntegrationTestSupport {

	private static final String NO_TTL_UNIT_REQUIRED_FROM = "0.7.3";

	private static final Version PRIVATE_KEY_TYPE_FROM = Version.parse("0.7.0");

	@Autowired
	VaultOperations vaultOperations;

	VaultPkiOperations pkiOperations;

	enum KeyType {

		rsa(2048), ec(256);

		private final int bits;

		KeyType(int bits) {
			this.bits = bits;
		}

	}

	@BeforeEach
	void before() {

		this.pkiOperations = this.vaultOperations.opsForPki();

		if (!prepare().hasSecret("pki")) {
			prepare().mountSecret("pki");
		}

		File workDir = findWorkDir(new File(System.getProperty("user.dir")));
		String caCert = Files.contentOf(new File(workDir, "ca/certs/ca.cert.pem"), "US-ASCII");
		String cert = Files.contentOf(new File(workDir, "ca/certs/intermediate.cert.pem"), "US-ASCII");
		String key = Files.contentOf(new File(workDir, "ca/private/intermediate.decrypted.key.pem"), "US-ASCII");

		Map<String, String> pembundle = Collections.singletonMap("pem_bundle", cert + key + caCert);

		this.vaultOperations.write("pki/config/ca", pembundle);

		Map<String, String> role = new HashMap<>();
		role.put("allowed_domains", "localhost,example.com");
		role.put("allow_subdomains", "true");
		role.put("allow_localhost", "true");
		role.put("allow_ip_sans", "true");
		role.put("max_ttl", "72h");

		this.vaultOperations.write("pki/roles/testrole", role);

		for (KeyType value : KeyType.values()) {
			role.put("key_type", value.name());
			role.put("key_bits", "" + value.bits);
			this.vaultOperations.write("pki/roles/testrole-" + value.name(), role);
		}

	}

	@Test
	void issueCertificateShouldCreateCertificate() throws KeyStoreException {

		VaultCertificateRequest request = VaultCertificateRequest.create("hello.example.com");

		VaultCertificateResponse certificateResponse = this.pkiOperations.issueCertificate("testrole", request);

		CertificateBundle data = certificateResponse.getRequiredData();

		assertThat(data.getPrivateKey()).isNotEmpty();

		if (prepare().getVersion().isGreaterThanOrEqualTo(PRIVATE_KEY_TYPE_FROM)) {
			assertThat(data.getPrivateKeyType()).isEqualTo("rsa");
		}

		assertThat(data.getCertificate()).isNotEmpty();
		assertThat(data.getIssuingCaCertificate()).isNotEmpty();
		assertThat(data.getSerialNumber()).isNotEmpty();
		assertThat(data.getX509Certificate().getSubjectX500Principal().getName()).isEqualTo("CN=hello.example.com");
		assertThat(data.getX509IssuerCertificates()).hasSize(2);

		KeyStore keyStore = data.createKeyStore("vault");
		assertThat(keyStore.getCertificateChain("vault")).hasSize(2);

		KeyStore keyStoreWithPassword = data.createKeyStore("vault", "mypassword");
		assertThat(keyStoreWithPassword.getCertificateChain("vault")).hasSize(2);

		KeyStore keyStoreWithPasswordChar = data.createKeyStore("vault", new char[0]);
		assertThat(keyStoreWithPasswordChar.getCertificateChain("vault")).hasSize(2);

		KeyStore keyStoreWithCaChain = data.createKeyStore("vault", true);
		assertThat(keyStoreWithCaChain.getCertificateChain("vault")).hasSize(3);

		KeyStore keyStoreWithCaChainAndPassword = data.createKeyStore("vault", true, "mypassword");
		assertThat(keyStoreWithCaChainAndPassword.getCertificateChain("vault")).hasSize(3);

		KeyStore keyStoreWithCaChainAndPasswordChar = data.createKeyStore("vault", true, new char[0]);
		assertThat(keyStoreWithCaChainAndPasswordChar.getCertificateChain("vault")).hasSize(3);
	}

	@ParameterizedTest
	@MethodSource("keyTypeFixtures")
	void issueCertificateUsingFormat(KeyFixture keyFixture) throws Exception {

		VaultCertificateRequest request = VaultCertificateRequest.builder()
				.commonName(keyFixture.format.replace('_', '-') + ".hello.example.com")
				.privateKeyFormat(keyFixture.privateKeyFormat).format(keyFixture.format).build();

		VaultCertificateResponse certificateResponse = this.pkiOperations
				.issueCertificate("testrole-" + keyFixture.keyType.name(), request);

		CertificateBundle data = certificateResponse.getRequiredData();
		assertThat(data.getX509Certificate().getSubjectX500Principal().getName())
				.isEqualTo("CN=" + request.getCommonName());
		assertThat(data.getX509IssuerCertificates()).hasSize(2);

		assertThat(data.getPrivateKeySpec()).isNotNull();

		KeyStore keyStore = data.createKeyStore("vault");
		assertThat(keyStore.getCertificateChain("vault")).hasSize(2);

		KeyStore keyStoreWithPassword = data.createKeyStore("vault", "mypassword");
		assertThat(keyStoreWithPassword.getCertificateChain("vault")).hasSize(2);

		KeyStore keyStoreWithPasswordChar = data.createKeyStore("vault", new char[0]);
		assertThat(keyStoreWithPasswordChar.getCertificateChain("vault")).hasSize(2);

		KeyStore keyStoreWithCaChain = data.createKeyStore("vault", true);
		assertThat(keyStoreWithCaChain.getCertificateChain("vault")).hasSize(3);

		KeyStore keyStoreWithCaChainAndPassword = data.createKeyStore("vault", true, "mypassword");
		assertThat(keyStoreWithCaChainAndPassword.getCertificateChain("vault")).hasSize(3);

		KeyStore keyStoreWithCaChainAndPasswordChar = data.createKeyStore("vault", true, new char[0]);
		assertThat(keyStoreWithCaChainAndPasswordChar.getCertificateChain("vault")).hasSize(3);

	}

	static Stream<KeyFixture> keyTypeFixtures() {

		List<String> formats = Arrays.asList("pem", "pem_bundle", "der");
		List<String> privateKeyFormats = Arrays.asList("der", "pkcs8");

		List<KeyFixture> fixtures = new ArrayList<>();

		for (KeyType keyType : KeyType.values()) {

			for (String privateKeyFormat : privateKeyFormats) {
				for (String format : formats) {
					fixtures.add(new KeyFixture(format, privateKeyFormat, keyType));
				}
			}

		}

		return fixtures.stream();
	}

	static class KeyFixture {

		private final String format, privateKeyFormat;

		private final KeyType keyType;

		KeyFixture(String format, String privateKeyFormat, KeyType keyType) {
			this.format = format;
			this.privateKeyFormat = privateKeyFormat;
			this.keyType = keyType;
		}

		@Override
		public String toString() {
			return String.format("[%s, %s, %s]", this.format, this.privateKeyFormat, this.keyType);
		}

	}

	@Test
	@RequiresVaultVersion(NO_TTL_UNIT_REQUIRED_FROM)
	void issueCertificateWithTtlShouldCreateCertificate() {

		VaultCertificateRequest request = VaultCertificateRequest.builder().ttl(Duration.ofHours(48))
				.commonName("hello.example.com").build();

		VaultCertificateResponse certificateResponse = this.pkiOperations.issueCertificate("testrole", request);

		X509Certificate certificate = certificateResponse.getRequiredData().getX509Certificate();

		Instant now = Instant.now();
		assertThat(certificate.getNotAfter()).isAfter(Date.from(now.plus(40, ChronoUnit.HOURS)))
				.isBefore(Date.from(now.plus(50, ChronoUnit.HOURS)));
	}

	@Test
	void signShouldSignCsr() {

		String csr = "-----BEGIN CERTIFICATE REQUEST-----\n"
				+ "MIICzTCCAbUCAQAwgYcxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpTb21lLVN0YXRl\n"
				+ "MRUwEwYDVQQHEwxTYW4gVmF1bHRpbm8xFTATBgNVBAoTDFNwcmluZyBWYXVsdDEY\n"
				+ "MBYGA1UEAxMPY3NyLmV4YW1wbGUuY29tMRswGQYJKoZIhvcNAQkBFgxzcHJpbmdA\n"
				+ "dmF1bHQwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDVlDBT1gAONIp4\n"
				+ "GQQ7BWDeqNzlscWqu5oQyfvw6oNFZzYWGVTgX/n72biv8d1Wx30MWpVYhbL0mk9m\n"
				+ "Uu15elMZHPb4F4bk8VDSiB9527SwAd/QpkNC1RsPp2h6g2LvGPJ2eidHSlLtF2To\n"
				+ "A4i6z0K0++nvYKSf9Af0sod2Z51xc9uPj/oN5z/8BQuGoCBpxJqgl7N/csMICixY\n"
				+ "2fQcCUbdPPqE9INIInUHe3mPE/yvxko9aYGZ5jnrdZyiQaRRKBdWpvbRLKXQ78Fz\n"
				+ "vXR3G33yn9JAN6wl1A916DiXzy2xHT19vyAn1hBUj2M6KFXChQ30oxTyTOqHCMLP\n"
				+ "m/BSEOsPAgMBAAGgADANBgkqhkiG9w0BAQsFAAOCAQEAYFssueiUh3YGxnXcQ4dp\n"
				+ "ZqVWeVyOuGGaFJ4BA0drwJ9Mt/iNmPUTGE2oBNnh2R7e7HwGcNysFHZZOZBEQ0Hh\n"
				+ "Vn93GO7cfaTOetK0VtDqis1VFQD0eVPWf5s6UqT/+XGrFRhwJ9hM+2FQSrUDFecs\n"
				+ "+/605n1rD7qOj3vkGrtwvEUrxyRaQaKpPLHmVHENqV6F1NsO3Z27f2FWWAZF2VKN\n"
				+ "cCQQJNc//DbIN3J3JSElpIDBDHctoBoQVnMiwpCbSA+CaAtlWYJKnAfhTKeqnNMy\n"
				+ "qf3ACZ+1sBIuqSP7dEJ2KfIezaCPQ88+PAloRB52LFa+iq3yI7F5VzkwAvQFnTi+\n" + "cQ==\n"
				+ "-----END CERTIFICATE REQUEST-----";

		VaultCertificateRequest request = VaultCertificateRequest.create("hello.example.com");

		VaultSignCertificateRequestResponse certificateResponse = this.pkiOperations.signCertificateRequest("testrole",
				csr, request);

		Certificate data = certificateResponse.getRequiredData();

		assertThat(data.getCertificate()).isNotEmpty();
		assertThat(data.getIssuingCaCertificate()).isNotEmpty();
		assertThat(data.getSerialNumber()).isNotEmpty();
		assertThat(data.getX509Certificate().getSubjectX500Principal().getName()).isEqualTo("CN=csr.example.com");
		assertThat(data.createTrustStore()).isNotNull();
	}

	@Test
	void issueCertificateFail() {

		VaultCertificateRequest request = VaultCertificateRequest.create("not.supported");

		assertThatExceptionOfType(VaultException.class)
				.isThrownBy(() -> this.pkiOperations.issueCertificate("testrole", request));
	}

	@Test
	void shouldRevokeCertificate() throws Exception {

		VaultCertificateRequest request = VaultCertificateRequest.create("foo.example.com");

		VaultCertificateResponse certificateResponse = this.pkiOperations.issueCertificate("testrole", request);

		BigInteger serial = new BigInteger(
				certificateResponse.getRequiredData().getSerialNumber().replaceAll("\\:", ""), 16);
		this.pkiOperations.revoke(certificateResponse.getRequiredData().getSerialNumber());

		try (InputStream in = this.pkiOperations.getCrl(Encoding.DER)) {

			CertificateFactory cf = CertificateFactory.getInstance("X.509");

			X509CRL crl = (X509CRL) cf.generateCRL(in);

			assertThat(crl.getRevokedCertificate(serial)).isNotNull();
		}
	}

	@Test
	void shouldReturnCrl() throws Exception {

		try (InputStream in = this.pkiOperations.getCrl(Encoding.DER)) {

			CertificateFactory cf = CertificateFactory.getInstance("X.509");

			assertThat(cf.generateCRL(in)).isInstanceOf(X509CRL.class);
		}

		try (InputStream crl = this.pkiOperations.getCrl(Encoding.PEM)) {

			byte[] bytes = StreamUtils.copyToByteArray(crl);
			assertThat(bytes).isNotEmpty();
		}
	}

}
