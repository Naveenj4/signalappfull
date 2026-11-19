/**
 * PIT MESSENGER – CUSTOM OTP & REGISTRATION BACKEND
 * Decoupled verification microservice with MySQL 8.x persistence.
 */

const express = require('express');
const crypto = require('crypto');
const mysql = require('mysql2/promise');
require('dotenv').config();

const app = express();
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', '*');
  res.header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(200);
  next();
});
app.use(express.json());

const PORT = parseInt(process.env.PORT || '8088', 10);
const OTP_TTL_MS = (parseInt(process.env.OTP_TTL_SECONDS || '300', 10)) * 1000;
const MAX_ATTEMPTS = parseInt(process.env.MAX_ATTEMPTS || '5', 10);

const dbConfig = {
  host: process.env.DB_HOST || '127.0.0.1',
  port: parseInt(process.env.DB_PORT || '3306', 10),
  user: process.env.DB_USER || 'pituser',
  password: process.env.DB_PASSWORD || 'pitpassword!',
  database: process.env.DB_NAME || 'pit_messenger',
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0
};

let pool = null;

async function initDb() {
  try {
    pool = mysql.createPool(dbConfig);
    const [rows] = await pool.query('SELECT 1 AS ok');
    console.log('[Database] Connected to MySQL pit_messenger successfully.');
  } catch (err) {
    console.error('[Database] Connection failed:', err.message);
  }
}

// -----------------------------------------------------------------------------
// Helper: Hash code with salt
// -----------------------------------------------------------------------------
function hashCode(code, salt) {
  return crypto.createHash('sha256').update(salt + code).digest('hex');
}

// -----------------------------------------------------------------------------
// 1. Health Endpoint
// -----------------------------------------------------------------------------
app.get('/v1/health', async (req, res) => {
  let dbStatus = 'disconnected';
  if (pool) {
    try {
      await pool.query('SELECT 1');
      dbStatus = 'connected';
    } catch (e) {
      dbStatus = 'error: ' + e.message;
    }
  }

  res.json({
    service: 'pit-messenger-custom-backend',
    status: 'healthy',
    timestamp: Date.now(),
    database: dbStatus
  });
});

// -----------------------------------------------------------------------------
// 2. Request OTP Code
// POST /v1/registration/request-code
// -----------------------------------------------------------------------------
app.post('/v1/registration/request-code', async (req, res) => {
  const { e164, transport = 'sms', client_id } = req.body;

  if (!e164 || typeof e164 !== 'string') {
    return res.status(400).json({ error: 'Valid e164 phone number is required' });
  }

  // Generate 6-digit numeric OTP code
  const code = Math.floor(100000 + Math.random() * 900000).toString();
  const salt = crypto.randomBytes(16).toString('hex');
  const codeHash = hashCode(code, salt);
  const sessionId = 'sess_' + crypto.randomBytes(16).toString('hex');
  const now = Date.now();
  const expiresAt = now + OTP_TTL_MS;

  try {
    await pool.query(
      `INSERT INTO verification_sessions 
        (session_id, e164, code_hash, salt, attempts, max_attempts, verified, created_at, expires_at)
       VALUES (?, ?, ?, ?, 0, ?, FALSE, ?, ?)`,
      [sessionId, e164, codeHash, Buffer.from(salt, 'hex'), MAX_ATTEMPTS, now, expiresAt]
    );

    console.log(`[OTP] Generated verification code for ${e164}: [ ${code} ] (Session: ${sessionId})`);

    const response = {
      session_id: sessionId,
      expires_in_seconds: Math.floor(OTP_TTL_MS / 1000),
      resend_available_in_seconds: 60
    };

    // In development mode, return code in response for rapid testing
    if (process.env.NODE_ENV !== 'production') {
      response.dev_code = code;
    }

    res.status(200).json(response);
  } catch (err) {
    console.error('[OTP] Error inserting verification session:', err);
    res.status(500).json({ error: 'Failed to initiate verification session' });
  }
});

// -----------------------------------------------------------------------------
// 3. Verify OTP Code
// POST /v1/registration/verify-code
// -----------------------------------------------------------------------------
app.post('/v1/registration/verify-code', async (req, res) => {
  const { session_id, code } = req.body;

  if (!session_id || !code) {
    return res.status(400).json({ error: 'session_id and code are required' });
  }

  try {
    const [rows] = await pool.query(
      `SELECT session_id, e164, code_hash, salt, attempts, max_attempts, verified, expires_at 
       FROM verification_sessions 
       WHERE session_id = ?`,
      [session_id]
    );

    if (rows.length === 0) {
      return res.status(404).json({ error: 'Verification session not found' });
    }

    const session = rows[0];
    const now = Date.now();

    if (now > session.expires_at) {
      return res.status(410).json({ error: 'Verification code has expired. Request a new code.' });
    }

    if (session.attempts >= session.max_attempts) {
      return res.status(429).json({ error: 'Maximum verification attempts exceeded.' });
    }

    const saltHex = Buffer.from(session.salt).toString('hex');
    const inputHash = hashCode(code.toString().trim(), saltHex);

    if (inputHash !== session.code_hash) {
      await pool.query(
        `UPDATE verification_sessions SET attempts = attempts + 1 WHERE session_id = ?`,
        [session_id]
      );
      const remaining = session.max_attempts - (session.attempts + 1);
      return res.status(400).json({ 
        error: 'Invalid verification code', 
        attempts_remaining: Math.max(0, remaining) 
      });
    }

    // Success: Generate verification token
    const token = 'tok_v1_' + crypto.randomBytes(24).toString('hex');

    await pool.query(
      `UPDATE verification_sessions 
       SET verified = TRUE, verification_token = ?, attempts = attempts + 1 
       WHERE session_id = ?`,
      [token, session_id]
    );

    console.log(`[OTP] Session ${session_id} verified successfully for ${session.e164}`);

    res.status(200).json({
      verified: true,
      verification_token: token,
      e164: session.e164
    });
  } catch (err) {
    console.error('[OTP] Error during verification:', err);
    res.status(500).json({ error: 'Internal verification failure' });
  }
});

// -----------------------------------------------------------------------------
// 4. Session Inspect (Admin/Debug)
// GET /v1/sessions/:session_id
// -----------------------------------------------------------------------------
app.get('/v1/sessions/:session_id', async (req, res) => {
  try {
    const [rows] = await pool.query(
      `SELECT session_id, e164, attempts, max_attempts, verified, verification_token, created_at, expires_at 
       FROM verification_sessions WHERE session_id = ?`,
      [req.params.session_id]
    );

    if (rows.length === 0) {
      return res.status(404).json({ error: 'Not found' });
    }

    res.json(rows[0]);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// -----------------------------------------------------------------------------
// Start Server
// -----------------------------------------------------------------------------
initDb().then(() => {
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`[Server] PIT Messenger Custom Backend listening on port ${PORT}`);
  });
});
