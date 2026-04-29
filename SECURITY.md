# 🛡️ Security Policy & Cryptographic Model [SECURITY-STABLE]

## 🏗️ Architectural Integrity (OWASP MASVS Aligned)
SeekPrivacy v3.0 implements a "Zero-Trust" storage architecture designed to survive "All-Files Access" permission abuse and physical device seizure. This project follows high-standard security protocols to ensure activist-grade data protection.

### 1. Supported Versions
| Version | Supported          | Status             |
| ------- | ------------------ | ------------------ |
| 3.0.x   | ✅ Activist Grade Security System  | Production Ready   |
| 2.0.x   | ❌ End of Life (EOL)   |    |
| 1.5.x   | ❌ End of Life (EOL)| Security Risk      |

### 2. Cryptographic Implementation (TEE-Backed)
* **Symmetric Encryption (GCM):** Uses `AES-256-GCM` (Galois/Counter Mode). Every file is encrypted with a unique 12-byte IV (Initialization Vector) to ensure authenticated integrity and prevent replay attacks.
* **Asymmetric Key Wrapping (RSA-2048):** Master keys are wrapped using the **Android KeyStore System**. Private keys are non-exportable and reside within the hardware-backed **Trusted Execution Environment (TEE)**.
* **RAM Hardening:** Sensitive `CharArrays` are explicitly zeroed out and nullified after use to prevent heap-dump leaks.


**🚫 No Internet, No Leaks:** SeekPrivacy requests zero network permissions—total offline isolation means your data never leaves your device.

### 3. Forensic Countermeasures
* **Header Shredder:** Standard `File.delete()` is bypassed. SeekPrivacy uses `RandomAccessFile` to overwrite the initial 1MB of data with null bytes (`0x00`) before unlinking, defeating forensic "undelete" signatures used by tools like Cellebrite.
* **Anti-Surveillance:** Hardware-level `FLAG_SECURE` is active on all sensitive activities to block screenshots, screen recordings, and recent-app snapshots.

### 4. Zero-Tension Developer Recovery (Security Bridge)
In high-stress activist environments, losing a password is a common point of failure. SeekPrivacy provides a world-first **Hardware-Anchored Bypass UI**. 
* **Mechanism:** Uses the non-reversible hardware fingerprint (`ANDROID_ID`) to generate a secure recovery request.
* **Security Benefit:** Eliminates the catastrophic risk of permanent data loss due to trauma or forgotten patterns. It provides a secure, transparent recovery bridge without implementing a universal backdoor.


## 🛡️ Reporting a Vulnerability
Please do not report a **vulnerability** through public GitHub issues. 

1. **Preferred Method:** Use the [GitHub Security Advisory](https://github.com/duckniii/SeekPrivacy/security/advisories/new) feature to report privately.
2. **Alternative:** Email the developer at `mytuta05@tutamail.com` with the subject `[SECURITY-VULNERABILITY] SeekPrivacy`.

### How to Report:
* **Description:** Detail the flaw and the specific security layer affected (e.g., GCM bypass, Shredding failure).
* **PoC:** Provide a Proof of Concept (PoC) or steps to reproduce the bypass.
* **Benefit of Secure Dev Last Resort:** Our world-first recovery bridge eliminates the catastrophic tension of permanent data loss. Without it, losing a password in a high-stress activist environment means your evidence is gone forever. This bridge ensures that even if trauma or stress causes a credential loss, the owner (and only the owner) maintains a secure path back to their data via hardware-anchored `ANDROID_ID` verification.
