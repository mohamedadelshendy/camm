use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use byteorder::{BigEndian, ByteOrder};
use crossbeam_channel::Sender;
use log::{error, info, warn};
use parking_lot::Mutex;
use tokio::io::AsyncReadExt;
use tokio::net::{TcpStream, UdpSocket};

const MAGIC_BYTES: u32 = 0x43414D4D; // "CAMM"
const TCP_HEADER_SIZE: usize = 17;
const UDP_HEADER_SIZE: usize = 12;

#[derive(Debug, Clone)]
pub struct StreamStats {
    pub connected: bool,
    pub incoming_fps: f32,
    pub bitrate_mbps: f32,
    pub latency_ms: f32,
    pub dropped_frames: u32,
}

struct UdpFrameAssembly {
    total_packets: u8,
    received_packets: HashMap<u8, Vec<u8>>,
    creation_time: Instant,
}

pub struct StreamReceiver {
    is_running: Arc<AtomicBool>,
    active_mode: Arc<Mutex<String>>, // "usb" or "wifi"
    wifi_ip: Arc<Mutex<String>>,
    wifi_port: Arc<AtomicU32>,

    // Statistics
    frame_counter: Arc<AtomicU32>,
    byte_counter: Arc<AtomicU64>,
    dropped_counter: Arc<AtomicU32>,
    latest_latency_ms: Arc<Mutex<f32>>,
    latest_fps: Arc<Mutex<f32>>,
    latest_bitrate: Arc<Mutex<f32>>,
    is_connected: Arc<AtomicBool>,
}

impl StreamReceiver {
    pub fn new() -> Self {
        Self {
            is_running: Arc::new(AtomicBool::new(false)),
            active_mode: Arc::new(Mutex::new("usb".to_string())),
            wifi_ip: Arc::new(Mutex::new("127.0.0.1".to_string())),
            wifi_port: Arc::new(AtomicU32::new(5556)),
            frame_counter: Arc::new(AtomicU32::new(0)),
            byte_counter: Arc::new(AtomicU64::new(0)),
            dropped_counter: Arc::new(AtomicU32::new(0)),
            latest_latency_ms: Arc::new(Mutex::new(0.0)),
            latest_fps: Arc::new(Mutex::new(0.0)),
            latest_bitrate: Arc::new(Mutex::new(0.0)),
            is_connected: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn start(&self, frame_sender: Sender<Vec<u8>>) {
        if self.is_running.swap(true, Ordering::SeqCst) {
            return;
        }

        self.spawn_stats_calculator();

        let is_running = self.is_running.clone();
        let active_mode = self.active_mode.clone();
        let wifi_port = self.wifi_port.clone();
        let frame_counter = self.frame_counter.clone();
        let byte_counter = self.byte_counter.clone();
        let dropped_counter = self.dropped_counter.clone();
        let latest_latency_ms = self.latest_latency_ms.clone();
        let is_connected = self.is_connected.clone();

        tokio::spawn(async move {
            info!("StreamReceiver orchestrator task started");

            while is_running.load(Ordering::SeqCst) {
                let mode = active_mode.lock().clone();

                if mode == "usb" {
                    Self::run_usb_tcp_client(
                        is_running.clone(),
                        frame_sender.clone(),
                        frame_counter.clone(),
                        byte_counter.clone(),
                        latest_latency_ms.clone(),
                        is_connected.clone(),
                    )
                    .await;
                } else {
                    let port = wifi_port.load(Ordering::SeqCst) as u16;
                    Self::run_wifi_udp_receiver(
                        is_running.clone(),
                        port,
                        frame_sender.clone(),
                        frame_counter.clone(),
                        byte_counter.clone(),
                        dropped_counter.clone(),
                        latest_latency_ms.clone(),
                        is_connected.clone(),
                    )
                    .await;
                }

                tokio::time::sleep(Duration::from_millis(500)).await;
            }

            info!("StreamReceiver orchestrator task stopped");
        });
    }

    async fn run_usb_tcp_client(
        is_running: Arc<AtomicBool>,
        frame_sender: Sender<Vec<u8>>,
        frame_counter: Arc<AtomicU32>,
        byte_counter: Arc<AtomicU64>,
        latest_latency_ms: Arc<Mutex<f32>>,
        is_connected: Arc<AtomicBool>,
    ) {
        let addr = "127.0.0.1:5555";
        match TcpStream::connect(addr).await {
            Ok(mut stream) => {
                let _ = stream.set_nodelay(true);
                info!("Connected to USB TCP Stream on {}", addr);
                is_connected.store(true, Ordering::SeqCst);

                let mut header_buf = [0u8; TCP_HEADER_SIZE];

                while is_running.load(Ordering::SeqCst) {
                    if let Err(e) = stream.read_exact(&mut header_buf).await {
                        warn!("TCP Header read error: {e}");
                        break;
                    }

                    let magic = BigEndian::read_u32(&header_buf[0..4]);
                    if magic != MAGIC_BYTES {
                        warn!("TCP invalid magic header: 0x{:08X}", magic);
                        break;
                    }

                    let payload_len = BigEndian::read_u32(&header_buf[4..8]) as usize;
                    let _frame_type = header_buf[8];
                    let timestamp_ms = BigEndian::read_u64(&header_buf[9..17]);

                    // Calculate live latency
                    let now_ms = SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_millis() as u64;
                    if now_ms >= timestamp_ms {
                        let latency = (now_ms - timestamp_ms) as f32;
                        *latest_latency_ms.lock() = latency;
                    }

                    let mut payload = vec![0u8; payload_len];
                    if let Err(e) = stream.read_exact(&mut payload).await {
                        warn!("TCP Payload read error: {e}");
                        break;
                    }

                    frame_counter.fetch_add(1, Ordering::Relaxed);
                    byte_counter.fetch_add(
                        (TCP_HEADER_SIZE + payload_len) as u64,
                        Ordering::Relaxed,
                    );

                    let _ = frame_sender.send(payload);
                }

                is_connected.store(false, Ordering::SeqCst);
                info!("Disconnected from USB TCP Stream");
            }
            Err(_) => {
                is_connected.store(false, Ordering::SeqCst);
                tokio::time::sleep(Duration::from_millis(800)).await;
            }
        }
    }

    async fn run_wifi_udp_receiver(
        is_running: Arc<AtomicBool>,
        port: u16,
        frame_sender: Sender<Vec<u8>>,
        frame_counter: Arc<AtomicU32>,
        byte_counter: Arc<AtomicU64>,
        dropped_counter: Arc<AtomicU32>,
        latest_latency_ms: Arc<Mutex<f32>>,
        is_connected: Arc<AtomicBool>,
    ) {
        let bind_addr = format!("0.0.0.0:{port}");
        let socket = match UdpSocket::bind(&bind_addr).await {
            Ok(s) => {
                info!("Listening for UDP video packets on {}", bind_addr);
                is_connected.store(true, Ordering::SeqCst);
                s
            }
            Err(e) => {
                error!("Failed to bind UDP socket on {}: {e}", bind_addr);
                is_connected.store(false, Ordering::SeqCst);
                tokio::time::sleep(Duration::from_millis(1000)).await;
                return;
            }
        };

        let mut recv_buf = [0u8; 2048];
        let mut active_assemblies: HashMap<u32, UdpFrameAssembly> = HashMap::new();
        let mut latest_frame_id: u32 = 0;

        while is_running.load(Ordering::SeqCst) {
            match socket.recv_from(&mut recv_buf).await {
                Ok((len, _src)) => {
                    if len < UDP_HEADER_SIZE {
                        continue;
                    }

                    let magic = BigEndian::read_u32(&recv_buf[0..4]);
                    if magic != MAGIC_BYTES {
                        continue;
                    }

                    let frame_id = BigEndian::read_u32(&recv_buf[4..8]);
                    let packet_idx = recv_buf[8];
                    let total_packets = recv_buf[9];
                    let payload_len = BigEndian::read_u16(&recv_buf[10..12]) as usize;

                    if UDP_HEADER_SIZE + payload_len > len {
                        continue;
                    }

                    byte_counter.fetch_add(len as u64, Ordering::Relaxed);

                    // Drop older incomplete frames if a significantly newer frame arrived
                    if frame_id > latest_frame_id {
                        if frame_id - latest_frame_id > 1 && latest_frame_id != 0 {
                            dropped_counter.fetch_add(1, Ordering::Relaxed);
                        }
                        latest_frame_id = frame_id;

                        // Clean up expired assemblies
                        active_assemblies.retain(|&id, assembly| {
                            id >= frame_id.saturating_sub(2)
                                && assembly.creation_time.elapsed() < Duration::from_millis(200)
                        });
                    }

                    let entry = active_assemblies
                        .entry(frame_id)
                        .or_insert_with(|| UdpFrameAssembly {
                            total_packets,
                            received_packets: HashMap::new(),
                            creation_time: Instant::now(),
                        });

                    let chunk = recv_buf[UDP_HEADER_SIZE..UDP_HEADER_SIZE + payload_len].to_vec();
                    entry.received_packets.insert(packet_idx, chunk);

                    // Check if complete frame is assembled
                    if entry.received_packets.len() == entry.total_packets as usize {
                        let mut full_frame = Vec::new();
                        for idx in 0..entry.total_packets {
                            if let Some(data) = entry.received_packets.get(&idx) {
                                full_frame.extend_from_slice(data);
                            }
                        }

                        let latency = entry.creation_time.elapsed().as_secs_f32() * 1000.0;
                        *latest_latency_ms.lock() = latency;

                        active_assemblies.remove(&frame_id);
                        frame_counter.fetch_add(1, Ordering::Relaxed);
                        let _ = frame_sender.send(full_frame);
                    }
                }
                Err(e) => {
                    warn!("UDP receive error: {e}");
                    break;
                }
            }
        }

        is_connected.store(false, Ordering::SeqCst);
    }

    fn spawn_stats_calculator(&self) {
        let is_running = self.is_running.clone();
        let frame_counter = self.frame_counter.clone();
        let byte_counter = self.byte_counter.clone();
        let latest_fps = self.latest_fps.clone();
        let latest_bitrate = self.latest_bitrate.clone();

        tokio::spawn(async move {
            let mut last_tick = Instant::now();

            while is_running.load(Ordering::SeqCst) {
                tokio::time::sleep(Duration::from_millis(1000)).await;
                let elapsed = last_tick.elapsed().as_secs_f32();
                last_tick = Instant::now();

                let frames = frame_counter.swap(0, Ordering::Relaxed);
                let bytes = byte_counter.swap(0, Ordering::Relaxed);

                let fps = frames as f32 / elapsed;
                let bitrate_mbps = (bytes as f32 * 8.0) / (elapsed * 1_000_000.0);

                *latest_fps.lock() = fps;
                *latest_bitrate.lock() = bitrate_mbps;
            }
        });
    }

    pub fn set_mode(&self, mode: &str, wifi_ip: Option<String>, wifi_port: Option<u16>) {
        *self.active_mode.lock() = mode.to_string();
        if let Some(ip) = wifi_ip {
            *self.wifi_ip.lock() = ip;
        }
        if let Some(port) = wifi_port {
            self.wifi_port.store(port as u32, Ordering::SeqCst);
        }
    }

    pub fn get_stats(&self) -> StreamStats {
        StreamStats {
            connected: self.is_connected.load(Ordering::SeqCst),
            incoming_fps: *self.latest_fps.lock(),
            bitrate_mbps: *self.latest_bitrate.lock(),
            latency_ms: *self.latest_latency_ms.lock(),
            dropped_frames: self.dropped_counter.load(Ordering::Relaxed),
        }
    }

    pub fn stop(&self) {
        self.is_running.store(false, Ordering::SeqCst);
    }
}
