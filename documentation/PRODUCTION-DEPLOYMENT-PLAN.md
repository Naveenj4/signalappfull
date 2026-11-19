# PIT Messenger – Production Deployment Plan (Phase 1)

## 1. Deployment Topologies

### 1.1. Topology A – Practical Single-Node VPS (Initial Launch / Small Scale)
- **Target Audience**: 1 to 10,000 active users.
- **Hardware Specification**: 4 vCPU, 8 GB–16 GB RAM, 100 GB NVMe SSD (e.g. Hetzner, DigitalOcean, or AWS EC2 `t4g.xlarge`).
- **OS**: Ubuntu 24.04 LTS.
- **Services Hosted on Node**:
  - Nginx Reverse Proxy (TLS 1.3, Let's Encrypt Certbot)
  - Dropwizard `TextSecureServer` (Java 21)
  - MySQL 8.0 Server (InnoDB buffer pool tuned to 4 GB)
  - Redis 7.x Cache & Pub/Sub
  - Coturn STUN/TURN Server
  - MinIO S3 Object Storage (Encrypted Media)

### 1.2. Topology B – Distributed Multi-Node Cloud (Enterprise / High Scale)
- **Application Layer**: 2x–4x Stateless Dropwizard Nodes behind AWS ALB or Cloudflare.
- **Relational Storage**: AWS Aurora MySQL Multi-AZ.
- **Cache Layer**: AWS ElastiCache Redis Cluster.
- **Object Storage**: AWS S3 with Cloudflare CDN caching for ciphertext attachments.
- **Relay Infrastructure**: Dedicated Coturn cluster in multiple geographic regions for lowest calling latency.

---

## 2. Network Ports & Firewall Rules

| Port | Protocol | Service | Exposure | Purpose |
|---|---|---|---|---|
| `443` | TCP | Nginx Reverse Proxy | **Public Internet** | Client API & WebSocket HTTPS connections |
| `80` | TCP | Let's Encrypt | **Public Internet** | ACME certificate verification & HTTP->HTTPS redirect |
| `3478` | UDP/TCP | Coturn TURN Server | **Public Internet** | WebRTC STUN/TURN call discovery & relay |
| `5349` | TCP | Coturn TURNS | **Public Internet** | Encrypted TLS WebRTC call relay |
| `49152-65535` | UDP | Coturn Media Relay | **Public Internet** | Dynamic WebRTC media packet forwarding |
| `8080` | TCP | Dropwizard App | **Internal Only** (`127.0.0.1`) | Upstream application target for Nginx |
| `8081` | TCP | Dropwizard Admin | **Internal Only** (`127.0.0.1`) | Health checks and internal metrics |
| `3306` | TCP | MySQL Server | **Internal Only** (`127.0.0.1`) | Database storage |
| `6379` | TCP | Redis Server | **Internal Only** (`127.0.0.1`) | Pub/sub and rate limiting |
| `9000` | TCP | MinIO S3 API | **Internal / Nginx** | Attachment object uploads & downloads |

---

## 3. Reverse Proxy & Service Configuration (Nginx)

```nginx
server {
    listen 443 ssl http2;
    server_name api.pitmessenger.com;

    ssl_certificate /etc/letsencrypt/live/api.pitmessenger.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.pitmessenger.com/privkey.pem;

    # Client API & Push Endpoints
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }

    # Real-Time WebSocket Connections
    location /v1/websocket {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```
