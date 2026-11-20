export type ConnectionMode = "usb" | "wifi";

export interface SystemStatus {
  connected: boolean;
  connection_mode: ConnectionMode;
  incoming_fps: number;
  bitrate_mbps: number;
  latency_ms: number;
  resolution: [number, number];
  dropped_frames: number;
  preview_enabled: boolean;
  virtual_cam_active: boolean;
  adb_device: string | null;
}

export interface VideoTransform {
  mirror: boolean;
  rotate: number; // 0, 90, 180, 270
  freeze: boolean;
}
