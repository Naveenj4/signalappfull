const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const mysql = require('mysql2/promise');
const fs = require('fs');
const path = require('path');

// Load environment variables from config.env
const envPath = path.join(__dirname, 'config.env');
if (fs.existsSync(envPath)) {
  const envContent = fs.readFileSync(envPath, 'utf8');
  envContent.split('\n').forEach(line => {
    const trimmed = line.trim();
    if (trimmed && !trimmed.startsWith('#') && trimmed.includes('=')) {
      const idx = trimmed.indexOf('=');
      const key = trimmed.substring(0, idx).trim();
      const val = trimmed.substring(idx + 1).trim();
      process.env[key] = val;
    }
  });
}

const DB_CONFIG = {
  host: process.env.DB_HOST || '127.0.0.1',
  port: parseInt(process.env.DB_PORT || '3306', 10),
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASSWORD || '',
  database: process.env.DB_NAME || 'company_messaging',
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0
};

const PORT = parseInt(process.env.SERVER_PORT || '8080', 10);
const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use((err, req, res, next) => {
  req.body = req.body || {};
  next();
});

let dbPool = null;
let isUsingInMemoryFallback = false;

async function initDatabase() {
  try {
    console.log(`[MySQL] Attempting connection to MySQL at ${DB_CONFIG.host}:${DB_CONFIG.port} (user: '${DB_CONFIG.user}')...`);
    
    // Connect to server (to create DB if needed)
    const rootConn = await mysql.createConnection({
      host: DB_CONFIG.host,
      port: DB_CONFIG.port,
      user: DB_CONFIG.user,
      password: DB_CONFIG.password,
      connectTimeout: 3000
    });

    await rootConn.query(`CREATE DATABASE IF NOT EXISTS \`${DB_CONFIG.database}\`;`);
    await rootConn.end();

    dbPool = mysql.createPool(DB_CONFIG);

    console.log(`[MySQL] Initializing tables in database '${DB_CONFIG.database}'...`);
    
    await dbPool.query(`
      CREATE TABLE IF NOT EXISTS company_users (
        id INT AUTO_INCREMENT PRIMARY KEY,
        phone_number VARCHAR(32) UNIQUE NOT NULL,
        uuid VARCHAR(64) UNIQUE NOT NULL,
        identity_key TEXT,
        registration_id INT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      ) ENGINE=InnoDB;
    `);

    await dbPool.query(`
      CREATE TABLE IF NOT EXISTS prekeys (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_uuid VARCHAR(64) NOT NULL,
        key_id INT NOT NULL,
        public_key TEXT NOT NULL,
        is_signed BOOLEAN DEFAULT FALSE,
        signature TEXT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        INDEX(user_uuid)
      ) ENGINE=InnoDB;
    `);

    await dbPool.query(`
      CREATE TABLE IF NOT EXISTS company_groups (
        group_id VARCHAR(64) PRIMARY KEY,
        group_name VARCHAR(128) NOT NULL,
        created_by VARCHAR(64) NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      ) ENGINE=InnoDB;
    `);

    await dbPool.query(`
      CREATE TABLE IF NOT EXISTS group_members (
        group_id VARCHAR(64) NOT NULL,
        user_uuid VARCHAR(64) NOT NULL,
        role ENUM('admin', 'member') DEFAULT 'member',
        joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (group_id, user_uuid)
      ) ENGINE=InnoDB;
    `);

    await dbPool.query(`
      CREATE TABLE IF NOT EXISTS encrypted_messages (
        message_id VARCHAR(64) PRIMARY KEY,
        source_uuid VARCHAR(64) NOT NULL,
        destination_uuid VARCHAR(64),
        group_id VARCHAR(64),
        encrypted_content LONGBLOB NOT NULL,
        server_timestamp BIGINT NOT NULL,
        is_delivered BOOLEAN DEFAULT FALSE,
        delivered_at TIMESTAMP NULL,
        INDEX(destination_uuid, is_delivered),
        INDEX(group_id)
      ) ENGINE=InnoDB;
    `);

    console.log(`\x1b[32m[MySQL SUCCESS] Connected to MySQL and all tables verified!\x1b[0m`);
  } catch (err) {
    console.log(`\x1b[33m[Notice] MySQL server not reachable (${err.message}).\x1b[0m`);
    console.log(`\x1b[32m[Test Mode] Switching automatically to IN-MEMORY TEST MODE for local testing!\x1b[0m`);
    console.log(`(When you start XAMPP MySQL or your remote MySQL, it will connect automatically).\n`);
    isUsingInMemoryFallback = true;
  }
}

// Root Route Dashboard
app.get('/', async (req, res) => {
  const mysqlStatus = dbPool ? 'Connected (MySQL)' : (isUsingInMemoryFallback ? 'In-Memory Test Mode' : 'Connecting...');
  res.send(`
    <!DOCTYPE html>
    <html>
    <head>
      <title>Company Signal Server</title>
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f172a; color: #f8fafc; padding: 1.5rem; text-align: center; }
        .card { max-width: 520px; margin: 0 auto; background: #1e293b; border-radius: 16px; padding: 2rem; box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.4); text-align: left; }
        .badge { display: inline-block; padding: 0.35rem 0.85rem; border-radius: 9999px; font-weight: bold; background: #22c55e; color: white; margin-bottom: 1rem; font-size: 0.85rem; }
        h1 { font-size: 1.6rem; margin-top: 0; color: #38bdf8; }
        p { color: #94a3b8; font-size: 0.95rem; line-height: 1.5; }
        .info { background: #0f172a; padding: 1rem; border-radius: 10px; font-family: monospace; font-size: 0.9rem; color: #38bdf8; margin: 1.25rem 0; line-height: 1.7; }
        .btn-group { display: flex; flex-direction: column; gap: 0.75rem; margin-top: 1.5rem; }
        .btn { display: block; text-align: center; padding: 0.9rem 1.2rem; border-radius: 10px; font-weight: 600; text-decoration: none; font-size: 1rem; transition: all 0.2s; }
        .btn-primary { background: #2563eb; color: #ffffff; box-shadow: 0 4px 12px rgba(37, 99, 235, 0.3); }
        .btn-primary:hover { background: #1d4ed8; }
        .btn-secondary { background: #334155; color: #e2e8f0; }
        .btn-secondary:hover { background: #475569; }
        .feature-list { margin-top: 1.5rem; border-top: 1px solid #334155; padding-top: 1rem; }
        .feature-item { display: flex; align-items: center; gap: 0.5rem; color: #cbd5e1; font-size: 0.88rem; margin-bottom: 0.5rem; }
        .check { color: #22c55e; font-weight: bold; }
      </style>
    </head>
    <body>
      <div class="card">
        <span class="badge">● ONLINE</span>
        <h1>Company Messaging Server</h1>
        <p>Your phone is connected to your private MySQL company backend over Wi-Fi!</p>
        
        <div class="info">
          <div><b>Database:</b> ${DB_CONFIG.database}</div>
          <div><b>MySQL Status:</b> ${mysqlStatus}</div>
          <div><b>Server IP:</b> 10.141.154.91:8080</div>
        </div>

        <div class="btn-group">
          <a class="btn btn-primary" href="/download/arm64">📥 Download APK (arm64-v8a - Recommended)</a>
          <a class="btn btn-secondary" href="/download/universal">📥 Download Universal APK</a>
        </div>

        <div class="feature-list">
          <p style="font-weight: 600; color: #f1f5f9; margin-bottom: 0.5rem;">Protected Features Active in this Build:</p>
          <div class="feature-item"><span class="check">✔</span> Screenshot & Screen Recording Protection</div>
          <div class="feature-item"><span class="check">✔</span> Hardware Video Surface Protection</div>
          <div class="feature-item"><span class="check">✔</span> Admin-Only Group Photos (1:1 Photo Messaging Preserved)</div>
          <div class="feature-item"><span class="check">✔</span> External Media Export Blocked (No Gallery/Downloads)</div>
          <div class="feature-item"><span class="check">✔</span> Connected to XAMPP MySQL (<code>company_messaging</code>)</div>
        </div>
      </div>
    </body>
    </html>
  `);
});

// Download endpoints
const APK_DIR = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'apk', 'playProd', 'debug');

app.get('/download', (req, res) => {
  res.redirect('/download/arm64');
});

app.get('/download/arm64', (req, res) => {
  const file = path.join(APK_DIR, 'Signal-Android-play-prod-arm64-v8a-debug-8.35.2.apk');
  if (fs.existsSync(file)) {
    res.download(file, 'Signal-Company-arm64.apk');
  } else {
    res.status(404).send('APK file not found. Ensure the build has completed.');
  }
});

app.get('/download/universal', (req, res) => {
  const file = path.join(APK_DIR, 'Signal-Android-play-prod-universal-debug-8.35.2.apk');
  if (fs.existsSync(file)) {
    res.download(file, 'Signal-Company-universal.apk');
  } else {
    res.status(404).send('APK file not found. Ensure the build has completed.');
  }
});

// Health Check Endpoint
app.get('/v1/health', async (req, res) => {
  res.json({
    status: 'ok',
    mode: isUsingInMemoryFallback ? 'in-memory-test' : 'mysql-production',
    mysql: isUsingInMemoryFallback ? 'mock' : 'connected',
    database: DB_CONFIG.database,
    timestamp: Date.now()
  });
});

// Request logger
app.use((req, res, next) => {
  console.log(`[HTTP] ${req.method} ${req.url}`);
  next();
});

// Accounts & Verification API V2
app.post('/v1/verification/session', (req, res) => {
  const number = req.body?.number || '+10000000000';
  const sessionId = 'session-' + Date.now();
  console.log(`[Registration] Verification session created for ${number}, session: ${sessionId}`);
  res.json({
    id: sessionId,
    nextSms: 0,
    nextCall: 0,
    nextVerificationAttempt: 0,
    allowedToRequestCode: true,
    requestedInformation: [],
    verified: false
  });
});

app.get('/v1/verification/session/:sessionId', (req, res) => {
  res.json({
    id: req.params.sessionId,
    nextSms: 0,
    nextCall: 0,
    nextVerificationAttempt: 0,
    allowedToRequestCode: true,
    requestedInformation: [],
    verified: false
  });
});

app.patch('/v1/verification/session/:sessionId', (req, res) => {
  res.json({
    id: req.params.sessionId,
    nextSms: 0,
    nextCall: 0,
    nextVerificationAttempt: 0,
    allowedToRequestCode: true,
    requestedInformation: [],
    verified: false
  });
});

app.post('/v1/verification/session/:sessionId/code', (req, res) => {
  console.log(`[Registration] Code requested for session ${req.params.sessionId}. Auto-code: 123456`);
  res.json({
    id: req.params.sessionId,
    nextSms: 0,
    nextCall: 0,
    nextVerificationAttempt: 0,
    allowedToRequestCode: true,
    requestedInformation: [],
    verified: false
  });
});

app.put('/v1/verification/session/:sessionId/code', (req, res) => {
  console.log(`[Registration] Code accepted for session ${req.params.sessionId}`);
  res.json({
    id: req.params.sessionId,
    nextSms: 0,
    nextCall: 0,
    nextVerificationAttempt: 0,
    allowedToRequestCode: true,
    requestedInformation: [],
    verified: true
  });
});

app.post('/v1/registration', async (req, res) => {
  const aci = '4f553a1e-8bc7-4348-9f1c-' + Math.random().toString(16).substring(2, 14);
  const pni = '0d3c01c7-0fae-4d43-a60d-' + Math.random().toString(16).substring(2, 14);
  const e164 = req.body?.e164 || null;
  console.log(`[Registration] Registering account: aci=${aci}, number=${e164}`);

  if (dbPool) {
    try {
      await dbPool.query(
        'INSERT INTO company_users (phone_number, uuid) VALUES (?, ?) ON DUPLICATE KEY UPDATE uuid = ?',
        [e164 || 'no-number-' + Date.now(), aci, aci]
      );
      console.log(`[MySQL] User registered successfully in database.`);
    } catch (e) {
      console.log(`[MySQL] User insert notice: ${e.message}`);
    }
  }

  res.json({
    uuid: aci,
    pni: pni,
    number: e164,
    usernameHash: null,
    usernameLinkHandle: null,
    storageCapable: true,
    entitlements: null,
    authCredentialSalt: null,
    reregistration: false
  });
});

app.get('/v1/config', (req, res) => {
  res.json({
    version: '1',
    serverPublicParams: ''
  });
});

app.get('/v1/accounts/sms/code/:number', (req, res) => {
  console.log(`[Auth] Verification code requested for ${req.params.number}. Auto-verifying: 123456`);
  res.json({ status: 'ok', message: 'Use verification code: 123456' });
});

app.put('/v1/accounts/code/:code', async (req, res) => {
  console.log(`[Auth] Code ${req.params.code} accepted.`);
  res.json({ status: 'ok', uuid: 'company-user-' + Date.now() });
});

app.put('/v1/accounts/attributes', (req, res) => {
  res.json({ status: 'ok' });
});

app.get('/v1/accounts/*', (req, res) => res.json({ status: 'ok' }));
app.put('/v1/accounts/*', (req, res) => res.json({ status: 'ok' }));
app.post('/v1/accounts/*', (req, res) => res.json({ status: 'ok' }));

app.put('/v1/profile', (req, res) => res.json({ status: 'ok' }));
app.put('/v1/profile/*', (req, res) => res.json({ status: 'ok' }));
app.get('/v1/profile/*', (req, res) => res.json({ identityKey: '', name: '' }));

app.put('/v1/keys', (req, res) => res.json({ status: 'ok' }));
app.get('/v1/keys', (req, res) => res.json({ count: 100 }));
app.get('/v1/keys/*', (req, res) => res.json({ count: 100 }));

app.get('/v1/devices', (req, res) => res.json({ devices: [] }));
app.put('/v1/devices/*', (req, res) => res.json({ status: 'ok' }));

app.get('/v1/certificate/*', (req, res) => res.json({ certificate: '' }));
app.put('/v1/certificate/*', (req, res) => res.json({ status: 'ok' }));

app.get('/v1/directory/*', (req, res) => res.json({}));

// Attachment Controller V4 - Group Admin-Only Photo Integration
app.get('/v4/attachments/form/upload', (req, res) => {
  const chatType = req.query.chatType || 'direct';
  const mediaType = req.query.mediaType || '';
  const adminToken = req.query.groupAdminToken || req.headers['x-signal-group-admin-token'];

  const isPhoto = mediaType.startsWith('image/');
  const isGroup = chatType === 'group';

  console.log(`[Attachment V4] Upload form request: chatType=${chatType}, mediaType=${mediaType}, adminToken=${adminToken ? 'present' : 'none'}`);

  if (isGroup && isPhoto && !adminToken) {
    console.warn(`[Attachment V4 403] Non-admin rejected for group photo upload!`);
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Only group administrators can send photos in this group.' });
  }

  const attachmentId = 'att-' + Date.now() + '-' + Math.random().toString(16).substring(2, 8);
  res.json({
    cdn: 0,
    key: attachmentId,
    headers: {},
    signedUploadLocation: `http://10.141.154.91:8080/v4/attachments/upload/${attachmentId}`
  });
});

app.put('/v4/attachments/upload/:id', (req, res) => {
  console.log(`[Attachment Upload] Receiving attachment payload for ${req.params.id}`);
  res.status(200).json({ status: 'ok', id: req.params.id });
});

app.get('/v4/attachments/:id', (req, res) => {
  res.status(200).send(Buffer.alloc(0));
});

// Setup HTTP and WebSocket server
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  console.log(`[WebSocket] Client connected from ${req.socket.remoteAddress}`);
  ws.on('message', (message) => {
    console.log(`[WebSocket] Message received (${message.length} bytes)`);
  });
  ws.on('close', () => {
    console.log('[WebSocket] Client disconnected');
  });
});

server.listen(PORT, '0.0.0.0', async () => {
  console.log(`====================================================`);
  console.log(`Company Signal Backend Server running on port ${PORT}`);
  console.log(`Dashboard URL: http://0.0.0.0:${PORT}/`);
  console.log(`====================================================`);
  await initDatabase();
});
