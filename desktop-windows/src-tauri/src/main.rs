// Prevents additional console window on Windows in release
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod adb_manager;
mod commands;
mod hw_decoder;
mod stream_receiver;
mod virtual_cam;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use crossbeam_channel::unbounded;
use log::info;
use parking_lot::Mutex;
use tauri::Emitter;

use crate::adb_manager::AdbManager;
use crate::commands::{AppState, DecoderCommand};
use crate::hw_decoder::HardwareDecoder;
use crate::stream_receiver::StreamReceiver;
use crate::virtual_cam::VirtualCameraSink;

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    info!("Starting CAMM Virtual Webcam Desktop Host");

    let (frame_raw_tx, frame_raw_rx) = unbounded::<Vec<u8>>();
    let (decoder_tx, decoder_rx) = unbounded::<DecoderCommand>();

    let adb = Arc::new(AdbManager::new());
    let receiver = Arc::new(StreamReceiver::new());
    let preview_enabled = Arc::new(AtomicBool::new(true));
    let latest_resolution = Arc::new(Mutex::new((1920u32, 1080u32)));
    let last_connected_device = Arc::new(Mutex::new(None));
    let virtual_cam_active = Arc::new(AtomicBool::new(false));
    let active_mode = Arc::new(Mutex::new("usb".to_string()));

    // Forward raw frames into decoder command queue
    let dec_cmd_tx = decoder_tx.clone();
    std::thread::spawn(move || {
        while let Ok(frame_bytes) = frame_raw_rx.recv() {
            let _ = dec_cmd_tx.send(DecoderCommand::Frame(frame_bytes));
        }
    });

    // Start background Stream Receiver
    receiver.start(frame_raw_tx);

    // Auto-detect ADB connected devices and forward port 5555
    let device_slot = last_connected_device.clone();
    adb.start_auto_forwarder(5555, move |device| {
        *device_slot.lock() = Some(format!("{} ({})", device.model, device.serial));
    });

    let app_state = AppState {
        adb: adb.clone(),
        receiver: receiver.clone(),
        decoder_tx: decoder_tx.clone(),
        preview_enabled: preview_enabled.clone(),
        latest_resolution: latest_resolution.clone(),
        last_connected_device: last_connected_device.clone(),
        virtual_cam_active: virtual_cam_active.clone(),
        active_mode: active_mode.clone(),
    };

    tauri::Builder::default()
        .manage(app_state)
        .invoke_handler(tauri::generate_handler![
            commands::get_system_status,
            commands::set_connection_mode,
            commands::set_video_transform,
            commands::toggle_preview,
            commands::refresh_adb
        ])
        .setup(move |app| {
            let app_handle = app.handle().clone();
            let prev_flag = preview_enabled.clone();
            let res_slot = latest_resolution.clone();
            let vcam_flag = virtual_cam_active.clone();

            // Spawn dedicated real-time decoding and virtual webcam sink thread
            std::thread::spawn(move || {
                let mut decoder = HardwareDecoder::new();
                let mut vcam = VirtualCameraSink::new();
                vcam_flag.store(vcam.is_active(), Ordering::SeqCst);

                info!("Video decoding & DirectShow virtual camera injection thread started");

                while let Ok(cmd) = decoder_rx.recv() {
                    match cmd {
                        DecoderCommand::Frame(nal_bytes) => {
                            if let Some(frame) = decoder.decode_packet(&nal_bytes) {
                                *res_slot.lock() = (frame.width, frame.height);

                                // Inject into Windows Virtual Camera DirectShow shared memory
                                vcam.write_frame(frame.width, frame.height, &frame.rgba);

                                // If UI preview is enabled, emit frame event to frontend
                                if prev_flag.load(Ordering::Relaxed) {
                                    #[derive(serde::Serialize, Clone)]
                                    struct VideoFramePayload {
                                        width: u32,
                                        height: u32,
                                        data: Vec<u8>,
                                    }

                                    let payload = VideoFramePayload {
                                        width: frame.width,
                                        height: frame.height,
                                        data: frame.rgba,
                                    };
                                    let _ = app_handle.emit("video_frame", payload);
                                }
                            }
                        }
                        DecoderCommand::Transform(opt) => {
                            decoder.set_transform(opt);
                        }
                    }
                }
            });

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("Error while running CAMM Tauri application");
}
