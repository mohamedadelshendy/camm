const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const { execSync } = require("child_process");

function makePNG(width, height) {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

  const table = new Int32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[i] = c;
  }

  function crc32(buf) {
    let crc = -1;
    for (let i = 0; i < buf.length; i++) {
      crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xff];
    }
    return crc ^ -1;
  }

  function chunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const typeBuf = Buffer.from(type);
    const crcBuf = Buffer.alloc(4);
    const crc = crc32(Buffer.concat([typeBuf, data]));
    crcBuf.writeInt32BE(crc, 0);
    return Buffer.concat([len, typeBuf, data, crcBuf]);
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6; // RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  const rawRows = [];
  const cx = width / 2;
  const cy = height / 2;
  const r = width * 0.45;

  for (let y = 0; y < height; y++) {
    const row = Buffer.alloc(1 + width * 4);
    row[0] = 0; // Filter: None
    for (let x = 0; x < width; x++) {
      const idx = 1 + x * 4;
      const dx = x - cx;
      const dy = y - cy;
      const dist = Math.sqrt(dx * dx + dy * dy);

      if (dist <= r) {
        if (dist < r * 0.4) {
          // Camera lens center: Dark slate
          row[idx] = 15;
          row[idx + 1] = 23;
          row[idx + 2] = 42;
          row[idx + 3] = 255;
        } else {
          // Camera body: Cyan glow
          row[idx] = 6;
          row[idx + 1] = 182;
          row[idx + 2] = 212;
          row[idx + 3] = 255;
        }
      } else {
        // Transparent
        row[idx] = 0;
        row[idx + 1] = 0;
        row[idx + 2] = 0;
        row[idx + 3] = 0;
      }
    }
    rawRows.push(row);
  }

  const idatData = zlib.deflateSync(Buffer.concat(rawRows));
  return Buffer.concat([
    signature,
    chunk("IHDR", ihdr),
    chunk("IDAT", idatData),
    chunk("IEND", Buffer.alloc(0))
  ]);
}

const desktopDir = path.join(__dirname, "..", "desktop-windows");
const sourceIconPath = path.join(desktopDir, "app-icon.png");

// 1. Generate high-resolution 512x512 source icon
const iconPng = makePNG(512, 512);
fs.writeFileSync(sourceIconPath, iconPng);
console.log("Created source icon at: " + sourceIconPath);

// 2. Run Tauri's built-in icon generator to produce all canonical icons
try {
  console.log("Running Tauri icon generator...");
  execSync("npx tauri icon app-icon.png", {
    cwd: desktopDir,
    stdio: "inherit"
  });
  console.log("Successfully generated all platform icons via Tauri icon generator!");
} catch (err) {
  console.error("Tauri icon generator error:", err.message);
}
