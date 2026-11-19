# PIT MESSENGER – DEPLOYMENT & LOCAL INFRASTRUCTURE

## 1. Overview
The deployment stack provides local or private VPS container orchestration for the supporting infrastructure of PIT Messenger:
- **Redis 7**: Pub/Sub routing, active WebSocket connections, rate-limiting counters.
- **MinIO**: S3-compatible private object storage for encrypted attachments.
- **MySQL 8.0**: Relational database managing accounts, devices, pre-keys, and persistent envelopes.

---

## 2. Directory Layout & Storage Anchoring
To avoid exhausting disk space on drive `C:\`, all persistent volume state is strictly contained inside `D:\signalappppp\deployment\data\`:

```
deployment/
├── docker-compose.yml
├── data/
│   ├── redis/
│   ├── minio/
│   └── mysql/
└── README.md
```

---

## 3. Launch Commands

### Starting the Stack
```powershell
cd "D:\signalappppp\deployment"
docker compose up -d
```

### Checking Container Health
```powershell
docker compose ps
```

### Stopping the Stack
```powershell
docker compose down
```
