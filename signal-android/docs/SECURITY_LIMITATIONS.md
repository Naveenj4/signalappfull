# SECURECHAT – Security Limitations & Threat Boundaries

## 1. Executive Summary
This document defines the technical boundaries, attack vectors, and platform constraints of the SecureChat application modifications. Clear and honest documentation of security boundaries ensures realistic defense-in-depth guarantees.

---

## 2. Platform & OS Level Limitations

### 2.1 Rooted Devices & Kernel-Level Privilege
- **Threat**: A user operating a rooted Android device (with Magisk, KernelSU, or custom AOSP ROMs) possesses root/superuser privileges (`UID 0`).
- **Limitation**:
  - `FLAG_SECURE` can be hooked or disabled via Xposed/LSPosed modules or custom display frameworks.
  - Process memory (`RAM`) can be inspected directly via `/proc/$PID/mem` or debugger attachments.
  - Encrypted database keys in memory can be extracted by root-level memory scrapers.
- **Guarantee Boundary**: SecureChat guarantees protection on standard, unrooted Android operating systems. No client application can protect against an adversarial kernel.

### 2.2 Physical & Optical Capture (Analog Hole)
- **Threat**: Capturing the screen using an external physical camera, smartphone, or external optical device.
- **Limitation**: Optical capture of the physical display cannot be prevented by any software technology.

### 2.3 Hardware Compositors & External HDMI Displays
- On unrooted Android devices, `WindowManager.LayoutParams.FLAG_SECURE` and `SurfaceView.setSecure(true)` instruct the SurfaceFlinger hardware compositor to blank the window in screenshots, screen recorders, and non-HDCP external displays.

---

## 3. End-to-End Encryption & Protocol Trust Model

### 3.1 Client-Side vs Server-Side Enforcement
- **E2EE Boundary**:
  - Signal's server does not possess message decryption keys.
  - The server cannot differentiate between encrypted photo ciphertext, encrypted video ciphertext, or encrypted text ciphertext without architectural changes to message envelopes.
- **Rogue Client Threat**:
  - If a user modifies their own client source code, compiles a rogue binary, and attempts to send an encrypted photo message to a group where they are a regular member:
    - The server will transport the ciphertext unaware of its content type.
    - However, **compliant SecureChat recipient clients will evaluate the sender's membership role against the local authenticated `GroupRecord` upon receipt, reject the unauthorized media, and refuse to decrypt or render the photo**.
- **Server Enforcement Requirement**:
  - To achieve 100% server-enforced prevention against modified clients, the Signal server protocol would need to be extended with an authenticated capability token signed by group admins accompanying attachment upload allocations.

---

## 4. Ephemeral Storage Boundaries

- `MediaRetentionManager` ensures no plaintext media is saved to public `MediaStore` (`Pictures`, `Downloads`, `DCIM`).
- Temporary decrypted frames in GPU/RAM memory exist while an image or video is being actively viewed on screen.
- Android system low-memory killer and `onTrimMemory` hooks are used to purge decrypted memory buffers when the application transitions to background.
