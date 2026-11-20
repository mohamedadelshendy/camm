use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use tauri::State;
use crate::adb_manager::{AdbManager, AndroidDevice};
use crate::hw_decoder::TransformOptions;
use crate::stream_receiver::StreamReceiver;

#[derive(Debug, Serialize, Deserialize)]
pub struct SystemStatusPayload {
    pub connected: bool,
    pub connection_mode: String,
    pub incoming_fps: f32,
    pub bitrate_mbps: f32,
    pub latency_ms: f32,
    pub resolution: (u32, u32),
    pub dropped_frames: u32,
    pub preview_enabled: bool,
    pub virtual_cam_active: bool,
    pub adb_device: Option<String>,
}

pub enum DecoderCommand {
    Frame(Vec<u8>),
    Transform(TransformOptions),
}

pub struct AppState {
    pub adb: Arc<AdbManager>,
    pub receiver: Arc<StreamReceiver>,
    pub decoder_tx: crossbeam_channel::Sender<DecoderCommand>,
    pub preview_enabled: Arc<AtomicBool>,
    pub latest_resolution: Arc<Mutex<(u32, u32)>>,
    pub last_connected_device: Arc<Mutex<Option<String>>>,
    pub virtual_cam_active: Arc<AtomicBool>,
    pub active_mode: Arc<Mutex<String>>,
}

#[tauri::command]
pub fn get_system_status(state: State<AppState>) -> SystemStatusPayload {
    let stats = state.receiver.get_stats();
    let res = *state.latest_resolution.lock();
    let device = state.last_connected_device.lock().clone();
    let mode = state.active_mode.lock().clone();

    SystemStatusPayload {
        connected: stats.connected,
        connection_mode: mode,
        incoming_fps: stats.incoming_fps,
        bitrate_mbps: stats.bitrate_mbps,
        latency_ms: stats.latency_ms,
        resolution: res,
        dropped_frames: stats.dropped_frames,
        preview_enabled: state.preview_enabled.load(Ordering::SeqCst),
        virtual_cam_active: state.virtual_cam_active.load(Ordering::SeqCst),
        adb_device: device,
    }
}

#[tauri::command]
pub fn set_connection_mode(
    state: State<AppState>,
    mode: String,
    ip: Option<String>,
    port: Option<u16>,
) -> Result<(), String> {
    *state.active_mode.lock() = mode.clone();
    state.receiver.set_mode(&mode, ip, port);
    Ok(())
}

#[tauri::command]
pub fn set_video_transform(
    state: State<AppState>,
    mirror: bool,
    rotate: u32,
    freeze: bool,
) -> Result<(), String> {
    let transform = TransformOptions {
        mirror,
        rotate,
        freeze,
    };
    let _ = state.decoder_tx.send(DecoderCommand::Transform(transform));
    Ok(())
}

#[tauri::command]
pub fn toggle_preview(state: State<AppState>, enabled: bool) -> Result<(), String> {
    state.preview_enabled.store(enabled, Ordering::SeqCst);
    Ok(())
}

#[tauri::command]
pub fn refresh_adb(state: State<AppState>) -> Result<Vec<AndroidDevice>, String> {
    let devices = state.adb.list_devices()?;
    if let Some(dev) = devices.first() {
        let _ = state.adb.forward_port(5555, 5555);
        *state.last_connected_device.lock() = Some(format!("{} ({})", dev.model, dev.serial));
    }
    Ok(devices)
}
