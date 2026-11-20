use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use log::{info, warn, error};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AndroidDevice {
    pub serial: String,
    pub state: String,
    pub model: String,
}

pub struct AdbManager {
    adb_path: Option<PathBuf>,
    is_monitoring: Arc<AtomicBool>,
}

impl AdbManager {
    pub fn new() -> Self {
        let adb_path = Self::find_adb_binary();
        if let Some(ref path) = adb_path {
            info!("AdbManager found ADB binary at: {:?}", path);
        } else {
            warn!("AdbManager could not find ADB binary on system PATH or default locations.");
        }

        Self {
            adb_path,
            is_monitoring: Arc::new(AtomicBool::new(false)),
        }
    }

    fn find_adb_binary() -> Option<PathBuf> {
        // 1. Check relative resources path
        let mut candidates = Vec::new();
        if let Ok(current_exe) = std::env::current_exe() {
            if let Some(exe_dir) = current_exe.parent() {
                candidates.push(exe_dir.join("resources").join("adb").join("adb.exe"));
                candidates.push(exe_dir.join("adb.exe"));
            }
        }

        // 2. Check Android SDK default path in AppData
        if let Ok(local_appdata) = std::env::var("LOCALAPPDATA") {
            candidates.push(
                PathBuf::from(local_appdata)
                    .join("Android")
                    .join("Sdk")
                    .join("platform-tools")
                    .join("adb.exe"),
            );
        }

        for path in candidates {
            if path.exists() {
                return Some(path);
            }
        }

        // 3. Fallback to system PATH
        if let Ok(output) = Command::new("adb").arg("version").output() {
            if output.status.success() {
                return Some(PathBuf::from("adb"));
            }
        }

        None
    }

    pub fn list_devices(&self) -> Result<Vec<AndroidDevice>, String> {
        let adb = self.adb_path.as_ref().ok_or_else(|| "ADB binary not found".to_string())?;

        let output = Command::new(adb)
            .args(["devices", "-l"])
            .output()
            .map_err(|e| format!("Failed to execute adb devices: {e}"))?;

        if !output.status.success() {
            return Err(format!(
                "adb devices command failed with status: {}",
                output.status
            ));
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        let mut devices = Vec::new();

        for line in stdout.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with("List of devices") || line.starts_with('*') {
                continue;
            }

            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                let serial = parts[0].to_string();
                let state = parts[1].to_string();

                let mut model = "Android Device".to_string();
                for part in &parts[2..] {
                    if let Some(stripped) = part.strip_prefix("model:") {
                        model = stripped.replace('_', " ");
                        break;
                    }
                }

                devices.push(AndroidDevice {
                    serial,
                    state,
                    model,
                });
            }
        }

        Ok(devices)
    }

    pub fn forward_port(&self, local_port: u16, device_port: u16) -> Result<(), String> {
        let adb = self.adb_path.as_ref().ok_or_else(|| "ADB binary not found".to_string())?;

        let forward_arg = format!("tcp:{local_port}");
        let remote_arg = format!("tcp:{device_port}");

        let output = Command::new(adb)
            .args(["forward", &forward_arg, &remote_arg])
            .output()
            .map_err(|e| format!("Failed to execute adb forward: {e}"))?;

        if output.status.success() {
            info!("Successfully executed: adb forward {} {}", forward_arg, remote_arg);
            Ok(())
        } else {
            let stderr = String::from_utf8_lossy(&output.stderr);
            Err(format!("adb forward failed: {stderr}"))
        }
    }

    pub fn start_auto_forwarder<F>(&self, port: u16, on_device_detected: F)
    where
        F: Fn(AndroidDevice) + Send + Sync + 'static,
    {
        if self.is_monitoring.swap(true, Ordering::SeqCst) {
            return;
        }

        let is_running = self.is_monitoring.clone();
        let adb_path = self.adb_path.clone();

        std::thread::spawn(move || {
            let adb = match adb_path {
                Some(p) => p,
                None => {
                    warn!("Auto forwarder thread stopped: no ADB binary found.");
                    return;
                }
            };

            let mut last_device_serial = String::new();

            while is_running.load(Ordering::SeqCst) {
                if let Ok(output) = Command::new(&adb).args(["devices", "-l"]).output() {
                    let stdout = String::from_utf8_lossy(&output.stdout);
                    let mut found_device: Option<AndroidDevice> = None;

                    for line in stdout.lines() {
                        let line = line.trim();
                        if line.is_empty() || line.starts_with("List of devices") || line.starts_with('*') {
                            continue;
                        }
                        let parts: Vec<&str> = line.split_whitespace().collect();
                        if parts.len() >= 2 && parts[1] == "device" {
                            let mut model = "Android Device".to_string();
                            for part in &parts[2..] {
                                if let Some(stripped) = part.strip_prefix("model:") {
                                    model = stripped.replace('_', " ");
                                    break;
                                }
                            }
                            found_device = Some(AndroidDevice {
                                serial: parts[0].to_string(),
                                state: parts[1].to_string(),
                                model,
                            });
                            break;
                        }
                    }

                    if let Some(dev) = found_device {
                        if dev.serial != last_device_serial {
                            info!("Detected new USB Android device: {} ({})", dev.serial, dev.model);
                            let fwd_arg = format!("tcp:{port}");
                            let rem_arg = format!("tcp:{port}");
                            if let Ok(fwd_out) = Command::new(&adb).args(["forward", &fwd_arg, &rem_arg]).output() {
                                if fwd_out.status.success() {
                                    info!("Auto-forwarded port {} -> {}", port, port);
                                    last_device_serial = dev.serial.clone();
                                    on_device_detected(dev);
                                }
                            }
                        }
                    } else if !last_device_serial.is_empty() {
                        info!("Android USB device disconnected.");
                        last_device_serial.clear();
                    }
                }

                std::thread::sleep(Duration::from_millis(2000));
            }
        });
    }

    pub fn stop_monitoring(&self) {
        self.is_monitoring.store(false, Ordering::SeqCst);
    }
}
