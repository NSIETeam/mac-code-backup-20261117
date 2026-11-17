"""Synthetic tests for the formal Java TLS truststore preparation tool."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest


SCRIPT = Path(__file__).with_name("prepare-java-tls.py")
SPEC = importlib.util.spec_from_file_location("prepare_java_tls", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
JAVA_HOME = Path(os.environ.get("SIYUE_TEST_JAVA17_HOME", "/private/tmp/siyue-java-local-tooling/jdk-17.0.12.jdk/Contents/Home"))


def run(command):
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise AssertionError("Synthetic certificate setup failed")
    return result


@unittest.skipUnless(JAVA_HOME.joinpath("bin/java").is_file(), "explicit Java 17 toolchain unavailable")
class PrepareJavaTlsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="siyue-java-tls-test-")
        self.root = Path(self.temp.name)
        self.openssl = Path(shutil.which("openssl") or "/usr/bin/openssl")
        self.ca_key = self.root / "ca.key"
        self.ca = self.root / "formal-ca.pem"
        self.make_ca("Test Production CA", self.ca_key, self.ca)
        self.server_key = self.root / "server.key"
        self.leaf = self.root / "localhost.pem"
        self.make_leaf("localhost", self.ca, self.ca_key, self.server_key, self.leaf)
        self.ca_fp = MODULE.validate_ca(self.openssl, self.ca)
        self.leaf_fp, _ = MODULE.validate_leaf(self.openssl, self.ca, self.leaf, None)
        self.approval = self.root / "approval.json"
        self.write_approval(self.approval, self.ca_fp, self.leaf_fp)
        self.approval.chmod(0o600)

    def tearDown(self):
        self.temp.cleanup()

    def make_ca(self, common_name, key, cert, is_ca=True):
        constraints = "critical,CA:TRUE,pathlen:0" if is_ca else "critical,CA:FALSE"
        key_usage = "critical,keyCertSign,cRLSign" if is_ca else "critical,digitalSignature,keyEncipherment"
        run([str(self.openssl), "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
             "-out", str(cert), "-days", "3", "-subj", "/CN=" + common_name,
             "-addext", "basicConstraints=" + constraints, "-addext", "keyUsage=" + key_usage])

    def make_leaf(self, name, ca, ca_key, key, cert):
        csr = self.root / (name + ".csr")
        extension = self.root / (name + ".ext")
        extension.write_text("subjectAltName=DNS:" + name + "\nbasicConstraints=critical,CA:FALSE\n"
                             "keyUsage=critical,digitalSignature,keyEncipherment\n", encoding="ascii")
        run([str(self.openssl), "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
             "-out", str(csr), "-subj", "/CN=" + name])
        run([str(self.openssl), "x509", "-req", "-in", str(csr), "-CA", str(ca), "-CAkey", str(ca_key),
             "-CAcreateserial", "-out", str(cert), "-days", "2", "-extfile", str(extension)])

    def make_expired_certificate(self, name, cert, key, *, ca=None, ca_key=None, is_ca=False):
        csr = self.root / (name + "-expired.csr")
        run([str(self.openssl), "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
             "-out", str(csr), "-subj", "/CN=" + name])
        database = self.root / (name + "-index.txt")
        database.write_text("", encoding="ascii")
        new_certs = self.root / (name + "-newcerts")
        new_certs.mkdir()
        serial = self.root / (name + "-serial")
        serial.write_text("1000\n", encoding="ascii")
        config = self.root / (name + "-ca.cnf")
        issuer_cert = ca if ca is not None else self.ca
        issuer_key = ca_key if ca_key is not None else self.ca_key
        extension = ("ca_cert" if is_ca else "server_cert")
        config.write_text(
            "[ ca ]\ndefault_ca = test_ca\n[test_ca]\n"
            f"database = {database}\nnew_certs_dir = {new_certs}\n"
            f"certificate = {issuer_cert}\nprivate_key = {issuer_key}\nserial = {serial}\n"
            "default_md = sha256\npolicy = test_policy\nunique_subject = no\n"
            "[test_policy]\ncommonName = supplied\n"
            "[ca_cert]\nbasicConstraints = critical,CA:TRUE\n"
            "keyUsage = critical,keyCertSign,cRLSign\n"
            "[server_cert]\nbasicConstraints = critical,CA:FALSE\n"
            "keyUsage = critical,digitalSignature,keyEncipherment\n"
            "subjectAltName = DNS:localhost\n", encoding="ascii")
        command = [str(self.openssl), "ca", "-config", str(config), "-batch", "-in", str(csr),
                   "-out", str(cert), "-startdate", "20200101000000Z", "-enddate", "20210101000000Z",
                   "-extensions", extension]
        if is_ca:
            command.extend(["-selfsign", "-keyfile", str(key)])
        run(command)

    def write_approval(self, path, ca_fp, leaf_fp):
        path.write_text(json.dumps({"purpose": "siyue-production-keycloak-tls", "serverName": "localhost",
                                    "caSha256": ca_fp, "leafSha256": leaf_fp}), encoding="utf-8")

    def prepare(self, ca=None, leaf=None, approval=None, install_dir=None):
        output = self.root / ("out-" + str(len(list(self.root.glob("out-*")))))
        return MODULE.prepare_trust_material(
            jdk_home=JAVA_HOME, ca_cert=ca or self.ca, leaf_cert=leaf or self.leaf,
            intermediates=None, approval_file=approval or self.approval, output_dir=output,
            install_dir=install_dir or str(output), openssl=self.openssl, require_root_approval=False)

    def test_real_jdk17_https_uses_argfile_and_preserves_public_roots(self):
        output = self.root / "out-real"
        info = MODULE.prepare_trust_material(
            jdk_home=JAVA_HOME, ca_cert=self.ca, leaf_cert=self.leaf, intermediates=None,
            approval_file=self.approval, output_dir=output, install_dir=str(output), openssl=self.openssl,
            require_root_approval=False)
        self.assertTrue(info["certChainVerified"])
        self.assertTrue(info["publicRootsPreserved"])
        self.assertGreaterEqual(info["sourceTrustedCertificateCount"], 80)
        self.assertEqual(info["resultTrustedCertificateCount"], info["sourceTrustedCertificateCount"] + 1)
        self.assertEqual((output / "java-truststore.jks").stat().st_mode & 0o777, 0o640)
        self.assertEqual((output / "java-tls.args").stat().st_mode & 0o777, 0o640)
        self.assertEqual((output / MODULE.DROPIN_NAME).stat().st_mode & 0o777, 0o644)
        args_text = (output / "java-tls.args").read_text(encoding="ascii")
        self.assertIn("-Djavax.net.ssl.trustStoreType=JKS", args_text)
        self.assertNotIn("JAVA_TOOL_OPTIONS", args_text)
        dropin = (output / MODULE.DROPIN_NAME).read_text(encoding="ascii")
        self.assertIn("ExecStart=\n", dropin)
        self.assertIn("@" + str(output / "java-tls.args"), dropin)
        self.assertIn("-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -jar " + MODULE.SERVICE_JAR, dropin)

        handler = type("SyntheticHandler", (BaseHTTPRequestHandler,), {
            "do_GET": lambda self: (self.send_response(200), self.end_headers(), self.wfile.write(b"ok")),
            "log_message": lambda *args: None,
        })
        class DualStackHTTPServer(ThreadingHTTPServer):
            address_family = socket.AF_INET6

            def server_bind(self):
                self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
                super().server_bind()

        server = DualStackHTTPServer(("::", 0), handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(self.leaf), str(self.server_key))
        server.socket = context.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            probe_source = self.root / "TlsProbe.java"
            probe_source.write_text(
                "import java.net.URI; import java.net.http.*; "
                "public class TlsProbe { public static void main(String[] a) throws Exception { "
                "var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(a[0])).GET().build(), "
                "HttpResponse.BodyHandlers.ofString()); System.out.print(r.statusCode()+\":\"+r.body()); }}",
                encoding="ascii")
            classes = self.root / "classes"
            classes.mkdir()
            run([str(JAVA_HOME / "bin/javac"), "-d", str(classes), str(probe_source)])
            url = "https://localhost:" + str(server.server_port) + "/"
            java_result = subprocess.run([str(JAVA_HOME / "bin/java"), "@" + str(output / "java-tls.args"),
                                          "-cp", str(classes), "TlsProbe", url],
                                         text=True, capture_output=True, timeout=20, check=False)
            self.assertEqual(java_result.returncode, 0, java_result.stderr)
            self.assertEqual(java_result.stdout, "200:ok")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)

    def test_rejects_wrong_hostname_and_unapproved_ca(self):
        wrong_key = self.root / "wrongname.key"
        wrong_leaf = self.root / "wrongname.pem"
        self.make_leaf("otherhost", self.ca, self.ca_key, wrong_key, wrong_leaf)
        with self.assertRaisesRegex(MODULE.Refused, "LEAF_LOCALHOST_SAN"):
            self.prepare(leaf=wrong_leaf)

        other_key = self.root / "other-ca.key"
        other_ca = self.root / "other-ca.pem"
        self.make_ca("Unapproved CA", other_key, other_ca)
        other_server_key = self.root / "other-server.key"
        other_leaf = self.root / "other-localhost.pem"
        self.make_leaf("localhost", other_ca, other_key, other_server_key, other_leaf)
        with self.assertRaisesRegex(MODULE.Refused, "CERTIFICATE_APPROVAL_MISMATCH"):
            self.prepare(ca=other_ca, leaf=other_leaf)

    def test_rejects_non_ca_and_invalid_chain(self):
        non_ca_key = self.root / "nonca.key"
        non_ca = self.root / "nonca.pem"
        self.make_ca("Not a CA", non_ca_key, non_ca, is_ca=False)
        with self.assertRaisesRegex(MODULE.Refused, "CA_BASIC_CONSTRAINTS"):
            MODULE.validate_ca(self.openssl, non_ca)

        broken_leaf = self.root / "broken.pem"
        broken_key = self.root / "broken.key"
        self.make_leaf("localhost", non_ca, non_ca_key, broken_key, broken_leaf)
        with self.assertRaises(MODULE.Refused):
            MODULE.validate_leaf(self.openssl, self.ca, broken_leaf, None)

    def test_rejects_expired_ca_and_expired_leaf(self):
        expired_ca_key = self.root / "expired-ca.key"
        expired_ca = self.root / "expired-ca.pem"
        self.make_expired_certificate("Expired Test CA", expired_ca, expired_ca_key, is_ca=True)
        with self.assertRaisesRegex(MODULE.Refused, "EXTERNAL_TOOL_FAILED_OPENSSL"):
            MODULE.validate_ca(self.openssl, expired_ca)

        expired_leaf_key = self.root / "expired-leaf.key"
        expired_leaf = self.root / "expired-leaf.pem"
        self.make_expired_certificate("localhost", expired_leaf, expired_leaf_key,
                                      ca=self.ca, ca_key=self.ca_key)
        with self.assertRaisesRegex(MODULE.Refused, "EXTERNAL_TOOL_FAILED_OPENSSL"):
            MODULE.validate_leaf(self.openssl, self.ca, expired_leaf, None)

    def test_approval_is_exact_and_fingerprints_are_pinned(self):
        altered = self.root / "altered.json"
        self.write_approval(altered, self.ca_fp, "0" * 64)
        altered.chmod(0o600)
        with self.assertRaisesRegex(MODULE.Refused, "CERTIFICATE_APPROVAL_MISMATCH"):
            self.prepare(approval=altered)
        self.assertEqual(MODULE.java_argfile("/test/store.jks", "JKS", "A" * 48),
                         "-Djavax.net.ssl.trustStore=/test/store.jks\n"
                         "-Djavax.net.ssl.trustStoreType=JKS\n"
                         "-Djavax.net.ssl.trustStorePassword=" + "A" * 48 + "\n")
        with self.assertRaisesRegex(MODULE.Refused, "ARGFILE_PATH"):
            MODULE.java_argfile("/test path/store.jks", "JKS", "A" * 48)


if __name__ == "__main__":
    unittest.main()
