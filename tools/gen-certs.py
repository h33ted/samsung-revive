#!/usr/bin/env python3
"""Generates the Samsung Revive CA and the *.accu-weather.com leaf (assets/server.p12, assets/ca.der,
assets/ca.hash). Dates start in 2012 so phones whose clock fell back still accept them.
Re-running creates a new CA, which then has to be installed on the phones again."""
import datetime, hashlib, os
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12

os.makedirs("assets", exist_ok=True); os.makedirs("certs", exist_ok=True)
start = datetime.datetime(2012, 1, 1, tzinfo=datetime.timezone.utc)
end = datetime.datetime(2049, 12, 31, tzinfo=datetime.timezone.utc)

ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Samsung Revive Local CA"),
                     x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Samsung Revive")])
ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name)
      .public_key(ca_key.public_key()).serial_number(x509.random_serial_number())
      .not_valid_before(start).not_valid_after(end)
      .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
      .add_extension(x509.KeyUsage(digital_signature=False, content_commitment=False, key_encipherment=False,
                                   data_encipherment=False, key_agreement=False, key_cert_sign=True,
                                   crl_sign=True, encipher_only=False, decipher_only=False), critical=True)
      .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
      .sign(ca_key, hashes.SHA256()))

key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
leaf = (x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "samsungmobile.accu-weather.com")]))
        .issuer_name(ca_name).public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(start).not_valid_after(end)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("samsungmobile.accu-weather.com"),
                                                    x509.DNSName("*.accu-weather.com")]), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
        .sign(ca_key, hashes.SHA256()))

# Android 4.x only reads PKCS#12 with the legacy SHA-1/3DES algorithms.
enc = (serialization.PrivateFormat.PKCS12.encryption_builder()
       .kdf_rounds(2048).key_cert_algorithm(pkcs12.PBES.PBESv1SHA1And3KeyTripleDESCBC)
       .hmac_hash(hashes.SHA1()).build(b"samsungrevive"))
open("assets/server.p12", "wb").write(pkcs12.serialize_key_and_certificates(b"server", key, leaf, [ca], enc))
open("assets/ca.der", "wb").write(ca.public_bytes(serialization.Encoding.DER))
open("certs/ca.pem", "wb").write(ca.public_bytes(serialization.Encoding.PEM))
open("certs/ca.key", "wb").write(ca_key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))
