# PIT Messenger – Infrastructure Cost Estimation (Phase 1)

## 1. Cost Overview & Pricing Models
This document provides an itemized breakdown of recurring and operational infrastructure expenses required to host and run PIT Messenger in production.

> [!IMPORTANT]
> Verified prices are explicitly marked based on official vendor published rate cards as of late 2024 / 2025. Variable costs depend directly on active user count and messaging volume.

---

## 2. Itemized Cost Breakdown

### 2.1. SMS Verification Gateway (2Factor.in)
- **Pricing Model**: Per transactional SMS delivered (India GSM termination).
- **Rate**: ~ ₹0.18 to ₹0.22 INR per SMS (~ \$0.0022 to \$0.0026 USD).
- **KYC & Sender ID Setup**: Free to nominal DLT portal registration fee (governed by telecom operators, e.g. Jio/Airtel DLT ~ ₹5,900 INR one-time).
- **Estimated Monthly Cost (Initial 1,000 Users)**:
  - 1,000 users × 1.2 SMS average (accounting for re-requests) = 1,200 SMS
  - **Estimated Monthly**: ₹240 to ₹300 INR (~ \$3.00 USD).

### 2.2. Production Server Hosting (VPS / Cloud Node)
- **Single-Node VPS (Option A - Recommended for Staging & Initial Launch)**:
  - **Provider**: Hetzner Cloud (CPX31 - 4 vCPU, 8 GB RAM, 160 GB NVMe, 20 TB Traffic) or DigitalOcean (4 vCPU, 8 GB RAM).
  - **Verified Cost**: €13.90 EUR / month (~ ₹1,250 INR / \$15.00 USD / month).
  - **Capacity**: Easily supports 5,000 to 10,000 active users with MySQL, Dropwizard, Redis, and MinIO running locally.
- **Multi-Node Cloud Architecture (Option B - Future Scale)**:
  - 2x AWS EC2 `t4g.xlarge` instances: ~ \$95 USD / month.
  - AWS Aurora MySQL `db.t4g.medium`: ~ \$55 USD / month.
  - AWS ElastiCache Redis `cache.t4g.micro`: ~ \$12 USD / month.
  - **Total Option B**: ~ \$162 USD / month.

### 2.3. TURN / WebRTC Calling Relays (Coturn Bandwidth)
- **STUN Traversal**: Free (Negligible bandwidth).
- **TURN Relay Bandwidth**: Only consumed when direct P2P connections cannot be established (~15-20% of calls behind symmetric NAT).
- **Data Usage**: Voice call: ~30 MB/hour. Video call (720p): ~500 MB/hour.
- **Hosting**: Hosted on the primary VPS (Hetzner includes 20 TB free monthly egress bandwidth, meaning TURN bandwidth cost is **\$0.00 additional**).

### 2.4. Attachment Object Storage (MinIO vs AWS S3 / Cloudflare R2)
- **Self-Hosted MinIO on Local VPS**: **\$0.00 additional** (utilizes VPS NVMe disk space).
- **Cloudflare R2 (S3-compatible)**:
  - Storage: \$0.015 / GB-month.
  - Egress Bandwidth: **\$0.00 (Zero egress fees)**.
  - Estimated for 50 GB stored media: **\$0.75 USD / month**.

### 2.5. Domain Name & TLS Certificates
- **Domain Name (`.com` or `.in`)**: ~ \$10 to \$14 USD / year.
- **TLS/SSL Certificates**: **\$0.00 (Free via Let's Encrypt / Certbot)** with automated 90-day renewal.

### 2.6. Push Notifications (Google Firebase FCM)
- **FCM Data Messages**: **\$0.00 (Free tier, unlimited push notifications)** provided by Google Cloud.

---

## 3. Total Estimated Budget Summary

| Category | Component / Provider | Initial Launch (1,000 Users) | Growth Scale (10,000 Users) |
|---|---|---|---|
| **SMS Verification** | 2Factor.in | ~ ₹300 INR (\$3.60 USD) | ~ ₹3,000 INR (\$36.00 USD) |
| **Application & Database VPS** | Hetzner Cloud (CPX31 / CPX41) | ~ ₹1,250 INR (\$15.00 USD) | ~ ₹2,500 INR (\$30.00 USD) |
| **TURN Media Relays** | Coturn on VPS | Included (\$0.00) | Included (\$0.00) |
| **Attachment Storage** | MinIO / Cloudflare R2 | ~ ₹0 to ₹70 INR (\$0.80 USD) | ~ ₹300 INR (\$3.60 USD) |
| **Domain & SSL** | Namecheap / Let's Encrypt | ~ ₹100 INR (\$1.20 USD / mo) | ~ ₹100 INR (\$1.20 USD / mo) |
| **Push Notifications** | Google Firebase (FCM) | Free (\$0.00) | Free (\$0.00) |
| **TOTAL ESTIMATED MONTHLY** | | **~ ₹1,720 INR (~ \$20.60 USD)** | **~ ₹5,900 INR (~ \$70.80 USD)** |
