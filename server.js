const express = require('express');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { WebSocket, WebSocketServer } = require('ws');

const app = express();
const server = http.createServer(app);

// WebSocket server for frontend clients (call status updates)
const wss = new WebSocketServer({ server, path: '/ws' });
const clients = new Set();

wss.on('connection', (ws) => {
  clients.add(ws);
  ws.on('close', () => clients.delete(ws));
});

function broadcast(data) {
  const msg = JSON.stringify(data);
  for (const ws of clients) {
    if (ws.readyState === WebSocket.OPEN) ws.send(msg);
  }
}

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// --- Encrypted Settings Storage ---
const SETTINGS_FILE = path.join(__dirname, 'settings.enc.json');
const ENC_KEY_RAW = 'Gavin155!@';
const ENC_ALGORITHM = 'aes-256-gcm';

function deriveKey(password) {
  return crypto.scryptSync(password, 'callgate-salt', 32);
}

function encrypt(text) {
  const key = deriveKey(ENC_KEY_RAW);
  const iv = crypto.randomBytes(16);
  const cipher = crypto.createCipheriv(ENC_ALGORITHM, key, iv);
  let encrypted = cipher.update(text, 'utf8', 'hex');
  encrypted += cipher.final('hex');
  const authTag = cipher.getAuthTag().toString('hex');
  return JSON.stringify({ iv: iv.toString('hex'), encrypted, authTag });
}

function decrypt(json) {
  const key = deriveKey(ENC_KEY_RAW);
  const { iv, encrypted, authTag } = JSON.parse(json);
  const decipher = crypto.createDecipheriv(ENC_ALGORITHM, key, Buffer.from(iv, 'hex'));
  decipher.setAuthTag(Buffer.from(authTag, 'hex'));
  let decrypted = decipher.update(encrypted, 'hex', 'utf8');
  decrypted += decipher.final('utf8');
  return decrypted;
}

function loadSettings() {
  try {
    if (fs.existsSync(SETTINGS_FILE)) {
      const raw = fs.readFileSync(SETTINGS_FILE, 'utf8');
      return JSON.parse(decrypt(raw));
    }
  } catch (e) {
    console.error('Failed to load settings:', e.message);
  }
  return {};
}

function saveSettings(settings) {
  const encrypted = encrypt(JSON.stringify(settings));
  fs.writeFileSync(SETTINGS_FILE, encrypted);
}

// Helper: proxy request to CallGateAI Android app
async function proxyToDevice(method, apiPath, body = null) {
  const settings = loadSettings();
  if (!settings.deviceUrl) {
    throw new Error('Device URL not configured');
  }

  const url = `${settings.deviceUrl}${apiPath}`;
  const headers = { 'Content-Type': 'application/json' };

  // Add auth if device has a password
  if (settings.deviceUsername && settings.devicePassword) {
    const auth = Buffer.from(`${settings.deviceUsername}:${settings.devicePassword}`).toString('base64');
    headers['Authorization'] = `Basic ${auth}`;
  }

  const opts = { method, headers };
  if (body) opts.body = JSON.stringify(body);

  return fetch(url, opts);
}

// GET settings (masks sensitive values)
app.get('/api/settings', (req, res) => {
  const settings = loadSettings();
  res.json({
    deviceUrl: settings.deviceUrl || '',
    deviceUsername: settings.deviceUsername || '',
    devicePassword: settings.devicePassword ? '••••••••' : '',
    openaiApiKey: settings.openaiApiKey ? '••••••••' : '',
    hasDevicePassword: !!settings.devicePassword,
    hasOpenaiApiKey: !!settings.openaiApiKey,
  });
});

// POST settings (save locally AND push OpenAI key to device)
app.post('/api/settings', async (req, res) => {
  const current = loadSettings();
  const updates = req.body;

  if (updates.deviceUrl !== undefined) current.deviceUrl = updates.deviceUrl;
  if (updates.deviceUsername !== undefined) current.deviceUsername = updates.deviceUsername;
  if (updates.devicePassword && updates.devicePassword !== '••••••••') {
    current.devicePassword = updates.devicePassword;
  }
  if (updates.openaiApiKey && updates.openaiApiKey !== '••••••••') {
    current.openaiApiKey = updates.openaiApiKey;
  }

  saveSettings(current);

  // Push OpenAI key and password to the device if we have a device URL
  if (current.deviceUrl) {
    try {
      const deviceSettings = {};
      if (current.openaiApiKey) deviceSettings.openai_api_key = current.openaiApiKey;
      if (current.devicePassword) deviceSettings.api_password = current.devicePassword;

      await proxyToDevice('POST', '/api/v1/settings', deviceSettings);
    } catch (e) {
      console.log('Could not push settings to device:', e.message);
    }
  }

  res.json({ ok: true });
});

// --- Call Control (proxy to device) ---
app.post('/api/call', async (req, res) => {
  const { phoneNumber } = req.body;
  if (!phoneNumber) return res.status(400).json({ error: 'Phone number required' });

  try {
    const response = await proxyToDevice('POST', '/api/v1/calls', {
      call: { phoneNumber }
    });
    const text = await response.text();
    let data;
    try { data = JSON.parse(text); } catch { data = { message: text }; }
    res.status(response.status).json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.delete('/api/call', async (req, res) => {
  try {
    const response = await proxyToDevice('DELETE', '/api/v1/calls');
    res.status(response.status).json({
      message: response.status === 204 ? 'Call ended' : 'No active call',
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// --- AI Control (proxy to device) ---
app.post('/api/ai/start', async (req, res) => {
  try {
    const response = await proxyToDevice('POST', '/api/v1/ai/start');
    const data = await response.json();
    res.status(response.status).json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/ai/stop', async (req, res) => {
  try {
    const response = await proxyToDevice('POST', '/api/v1/ai/stop');
    const data = await response.json();
    res.status(response.status).json(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// --- Device Status ---
app.get('/api/device/status', async (req, res) => {
  try {
    // Try CallGateAI status endpoint first
    const response = await proxyToDevice('GET', '/api/v1/status');
    const text = await response.text();
    if (text && text.trim()) {
      try {
        const data = JSON.parse(text);
        return res.json(data);
      } catch (e) {
        // Not JSON — probably original CallGate
      }
    }
    // If we got here, device is reachable but doesn't have /status
    // It's likely the original CallGate app
    res.json({ app: 'CallGate (Original)', version: '?', callState: 'READY' });
  } catch (e) {
    res.json({ error: e.message, callState: 'UNKNOWN' });
  }
});

// --- Webhooks from device ---
app.post('/webhook/callgate', (req, res) => {
  console.log('Device webhook:', JSON.stringify(req.body));
  broadcast({ type: 'callgate_event', data: req.body });
  res.sendStatus(200);
});

// --- Start Server ---
const PORT = process.env.PORT || 3001;
server.listen(PORT, () => {
  console.log(`CallGate AI Control Panel running at http://localhost:${PORT}`);
});
