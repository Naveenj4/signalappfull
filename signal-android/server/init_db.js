const mysql = require('mysql2/promise');

async function init() {
  try {
    const conn = await mysql.createConnection({
      host: '127.0.0.1',
      port: 3306,
      user: 'root',
      password: '',
      database: 'company_messaging'
    });
    console.log('[SUCCESS] CONNECTED TO MySQL DATABASE: company_messaging');

    await conn.query(`
      CREATE TABLE IF NOT EXISTS company_users (
        id INT AUTO_INCREMENT PRIMARY KEY,
        phone_number VARCHAR(32) UNIQUE NOT NULL,
        uuid VARCHAR(64) UNIQUE NOT NULL,
        identity_key TEXT,
        registration_id INT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      ) ENGINE=InnoDB;
    `);

    await conn.query(`
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

    await conn.query(`
      CREATE TABLE IF NOT EXISTS company_groups (
        group_id VARCHAR(64) PRIMARY KEY,
        group_name VARCHAR(128) NOT NULL,
        created_by VARCHAR(64) NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      ) ENGINE=InnoDB;
    `);

    await conn.query(`
      CREATE TABLE IF NOT EXISTS group_members (
        group_id VARCHAR(64) NOT NULL,
        user_uuid VARCHAR(64) NOT NULL,
        role ENUM('admin', 'member') DEFAULT 'member',
        joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (group_id, user_uuid)
      ) ENGINE=InnoDB;
    `);

    await conn.query(`
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

    const [tables] = await conn.query('SHOW TABLES;');
    console.log('[SUCCESS] TABLES CREATED IN MySQL:');
    tables.forEach(t => console.log('  -> ' + Object.values(t)[0]));
    await conn.end();
  } catch(e) {
    console.error('[ERROR]', e.message);
  }
}

init();

