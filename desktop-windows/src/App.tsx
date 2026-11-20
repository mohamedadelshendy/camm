import { useState, useEffect, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import {
  Usb,
  Wifi,
  FlipHorizontal,
  RotateCw,
  Pause,
  Play,
  Eye,
  EyeOff,
  Video,
  CheckCircle2,
  AlertCircle,
  Copy,
  RefreshCw,
  Zap,
} from "lucide-react";
import { SystemStatus, VideoTransform, ConnectionMode } from "./types";

export default function App() {
  const [status, setStatus] = useState<SystemStatus>({
    connected: false,
    connection_mode: "usb",
    incoming_fps: 0,
    bitrate_mbps: 0,
    latency_ms: 0,
    resolution: [1920, 1080],
    dropped_frames: 0,
    preview_enabled: true,
    virtual_cam_active: true,
    adb_device: null,
  });

  const [transform, setTransform] = useState<VideoTransform>({
    mirror: false,
    rotate: 0,
    freeze: false,
  });

  const [copiedScript, setCopiedScript] = useState(false);
  const [wifiIp, setWifiIp] = useState("192.168.1.100");
  const [wifiPort, setWifiPort] = useState(5556);
  const [isRefreshingAdb, setIsRefreshingAdb] = useState(false);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  // Poll system status from Tauri backend
  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const s = await invoke<SystemStatus>("get_system_status");
        setStatus(s);
      } catch {
        // Fallback for dev mode preview
      }
    };

    fetchStatus();
    const interval = setInterval(fetchStatus, 500);
    return () => clearInterval(interval);
  }, []);

  // Listen to incoming video frames from Tauri backend
  useEffect(() => {
    let unlistenFn: (() => void) | undefined;

    const setupListener = async () => {
      try {
        unlistenFn = await listen<{ width: number; height: number; data: number[] }>(
          "video_frame",
          (event) => {
            if (!status.preview_enabled || transform.freeze) return;
            const canvas = canvasRef.current;
            if (!canvas) return;
            const ctx = canvas.getContext("2d");
            if (!ctx) return;

            const { width, height, data } = event.payload;
            if (canvas.width !== width || canvas.height !== height) {
              canvas.width = width;
              canvas.height = height;
            }

            const imgData = new ImageData(new Uint8ClampedArray(data), width, height);
            ctx.putImageData(imgData, 0, 0);
          }
        );
      } catch {
        // Running in pure web browser preview
      }
    };

    setupListener();
    return () => {
      if (unlistenFn) unlistenFn();
    };
  }, [status.preview_enabled, transform.freeze]);

  const handleConnectionModeChange = async (mode: ConnectionMode) => {
    try {
      await invoke("set_connection_mode", {
        mode,
        ip: mode === "wifi" ? wifiIp : null,
        port: mode === "wifi" ? wifiPort : null,
      });
      setStatus((prev) => ({ ...prev, connection_mode: mode }));
    } catch (e) {
      console.error(e);
    }
  };

  const handleToggleMirror = async () => {
    const nextMirror = !transform.mirror;
    setTransform((prev) => ({ ...prev, mirror: nextMirror }));
    try {
      await invoke("set_video_transform", {
        mirror: nextMirror,
        rotate: transform.rotate,
        freeze: transform.freeze,
      });
    } catch (e) {
      console.error(e);
    }
  };

  const handleRotate = async () => {
    const nextRotate = (transform.rotate + 90) % 360;
    setTransform((prev) => ({ ...prev, rotate: nextRotate }));
    try {
      await invoke("set_video_transform", {
        mirror: transform.mirror,
        rotate: nextRotate,
        freeze: transform.freeze,
      });
    } catch (e) {
      console.error(e);
    }
  };

  const handleToggleFreeze = async () => {
    const nextFreeze = !transform.freeze;
    setTransform((prev) => ({ ...prev, freeze: nextFreeze }));
    try {
      await invoke("set_video_transform", {
        mirror: transform.mirror,
        rotate: transform.rotate,
        freeze: nextFreeze,
      });
    } catch (e) {
      console.error(e);
    }
  };

  const handleTogglePreview = async () => {
    const nextPreview = !status.preview_enabled;
    setStatus((prev) => ({ ...prev, preview_enabled: nextPreview }));
    try {
      await invoke("toggle_preview", { enabled: nextPreview });
    } catch (e) {
      console.error(e);
    }
  };

  const handleRefreshAdb = async () => {
    setIsRefreshingAdb(true);
    try {
      await invoke("refresh_adb");
    } catch (e) {
      console.error(e);
    } finally {
      setTimeout(() => setIsRefreshingAdb(false), 800);
    }
  };

  const copyRegisterScript = () => {
    navigator.clipboard.writeText("powershell -ExecutionPolicy Bypass -File .\\scripts\\register-virtual-cam.ps1");
    setCopiedScript(true);
    setTimeout(() => setCopiedScript(false), 2000);
  };

  // Compute latency color class
  const getLatencyColor = (ms: number) => {
    if (ms <= 20) return "text-emerald-400 border-emerald-500/40 bg-emerald-500/10";
    if (ms <= 40) return "text-amber-400 border-amber-500/40 bg-amber-500/10";
    return "text-rose-400 border-rose-500/40 bg-rose-500/10";
  };

  return (
    <div className="flex h-screen w-screen flex-col bg-[#070B14] text-slate-100 font-sans">
      {/* Top Header */}
      <header className="flex h-14 items-center justify-between border-b border-slate-800/80 bg-[#0B101E]/90 px-6 backdrop-blur">
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-cyan-500/20 text-cyan-400 ring-1 ring-cyan-500/40">
            <Video className="h-4 w-4" />
          </div>
          <div>
            <span className="font-bold tracking-tight text-white">CAMM</span>
            <span className="ml-2 text-xs font-medium text-slate-400">Virtual Webcam</span>
          </div>
        </div>

        {/* Status Pills */}
        <div className="flex items-center gap-3">
          {/* ADB device or transport status */}
          <div className="flex items-center gap-2 rounded-full border border-slate-700/60 bg-slate-900/60 px-3 py-1 text-xs">
            {status.connected ? (
              <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
            ) : (
              <AlertCircle className="h-3.5 w-3.5 text-amber-400 animate-pulse" />
            )}
            <span className="text-slate-300">
              {status.connected
                ? status.connection_mode === "usb"
                  ? `USB: ${status.adb_device || "Connected"}`
                  : "Wi-Fi: Streaming"
                : status.connection_mode === "usb"
                ? "USB: Awaiting Phone (Port 5555)"
                : "Wi-Fi: Listening (Port 5556)"}
            </span>
          </div>

          {/* Virtual Camera Sink Status */}
          <div className="flex items-center gap-2 rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-xs text-cyan-300">
            <Zap className="h-3.5 w-3.5" />
            <span>DirectShow Sink Active</span>
          </div>
        </div>
      </header>

      {/* Main Body */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left Video Viewport */}
        <div className="relative flex flex-1 flex-col items-center justify-center bg-[#05080F] p-4">
          <div className="relative flex h-full w-full max-w-5xl items-center justify-center overflow-hidden rounded-2xl border border-slate-800 bg-[#0B101E]/50 shadow-2xl">
            {status.preview_enabled ? (
              <canvas
                ref={canvasRef}
                style={{
                  transform: `scaleX(${transform.mirror ? -1 : 1}) rotate(${transform.rotate}deg)`,
                  transition: "transform 0.2s ease",
                }}
                className="max-h-full max-w-full rounded-lg object-contain shadow-md"
              />
            ) : (
              <div className="flex flex-col items-center gap-3 text-slate-400">
                <EyeOff className="h-10 w-10 text-slate-500" />
                <span className="text-sm font-medium">UI Preview Disabled</span>
                <span className="text-xs text-slate-500">
                  DirectShow Virtual Camera is still streaming at full 60 FPS to Zoom/Discord
                </span>
                <button
                  onClick={handleTogglePreview}
                  className="mt-2 rounded-lg bg-slate-800 px-4 py-2 text-xs font-semibold text-cyan-400 hover:bg-slate-700"
                >
                  Re-enable Preview
                </button>
              </div>
            )}

            {/* Freeze overlay */}
            {transform.freeze && status.preview_enabled && (
              <div className="absolute top-4 left-4 flex items-center gap-2 rounded-md bg-rose-500/80 px-2.5 py-1 text-xs font-bold text-white shadow backdrop-blur">
                <Pause className="h-3 w-3" />
                FREEZE ACTIVE
              </div>
            )}

            {/* Quick Over-Canvas Controls */}
            <div className="absolute bottom-4 flex items-center gap-2 rounded-xl border border-slate-700/60 bg-slate-900/80 p-1.5 shadow-xl backdrop-blur">
              <button
                onClick={handleToggleMirror}
                title="Mirror Horizontal (Flip)"
                className={`flex h-9 w-9 items-center justify-center rounded-lg transition ${
                  transform.mirror
                    ? "bg-cyan-500 text-slate-950 font-bold"
                    : "text-slate-300 hover:bg-slate-800"
                }`}
              >
                <FlipHorizontal className="h-4 w-4" />
              </button>

              <button
                onClick={handleRotate}
                title="Rotate 90° Clockwise"
                className="flex h-9 w-9 items-center justify-center rounded-lg text-slate-300 transition hover:bg-slate-800"
              >
                <RotateCw className="h-4 w-4" />
              </button>

              <button
                onClick={handleToggleFreeze}
                title="Freeze Frame"
                className={`flex h-9 w-9 items-center justify-center rounded-lg transition ${
                  transform.freeze
                    ? "bg-rose-500 text-white"
                    : "text-slate-300 hover:bg-slate-800"
                }`}
              >
                {transform.freeze ? <Play className="h-4 w-4" /> : <Pause className="h-4 w-4" />}
              </button>

              <div className="mx-1 h-5 w-px bg-slate-700" />

              <button
                onClick={handleTogglePreview}
                title={status.preview_enabled ? "Disable UI Preview (Save GPU Cycles)" : "Enable UI Preview"}
                className={`flex h-9 items-center gap-1.5 rounded-lg px-3 text-xs font-medium transition ${
                  status.preview_enabled
                    ? "text-slate-300 hover:bg-slate-800"
                    : "bg-cyan-500/20 text-cyan-300 ring-1 ring-cyan-500/50"
                }`}
              >
                {status.preview_enabled ? (
                  <>
                    <Eye className="h-4 w-4" />
                    <span>GPU Saver</span>
                  </>
                ) : (
                  <>
                    <EyeOff className="h-4 w-4" />
                    <span>Preview Off</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </div>

        {/* Right Telemetry & Control Sidebar */}
        <aside className="w-80 border-l border-slate-800/80 bg-[#0B101E]/95 p-5 flex flex-col justify-between overflow-y-auto">
          <div className="space-y-6">
            {/* Ultra-Low Latency Hero Gauge */}
            <div
              className={`flex flex-col items-center justify-center rounded-2xl border p-4 shadow-inner ${getLatencyColor(
                status.latency_ms
              )}`}
            >
              <div className="flex items-baseline gap-1">
                <span className="text-4xl font-extrabold tracking-tight font-mono">
                  {status.connected ? status.latency_ms.toFixed(1) : "--"}
                </span>
                <span className="text-sm font-semibold">ms</span>
              </div>
              <span className="mt-1 text-xs font-medium uppercase tracking-wider opacity-80">
                {status.latency_ms <= 20 ? "Ultra-Low Latency (<20ms)" : "Network Latency"}
              </span>
            </div>

            {/* Performance Telemetry Grid */}
            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-3">
                <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">
                  Incoming FPS
                </span>
                <div className="mt-1 font-mono text-lg font-bold text-white">
                  {status.connected ? status.incoming_fps.toFixed(1) : "0.0"}
                </div>
              </div>

              <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-3">
                <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">
                  Bitrate
                </span>
                <div className="mt-1 font-mono text-lg font-bold text-white">
                  {status.connected ? `${status.bitrate_mbps.toFixed(2)} M` : "0.00 M"}
                </div>
              </div>

              <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-3">
                <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">
                  Resolution
                </span>
                <div className="mt-1 font-mono text-sm font-bold text-white">
                  {status.resolution[0]}x{status.resolution[1]}
                </div>
              </div>

              <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-3">
                <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">
                  Dropped Frames
                </span>
                <div className="mt-1 font-mono text-sm font-bold text-slate-300">
                  {status.dropped_frames}
                </div>
              </div>
            </div>

            {/* Transport Mode Switch */}
            <div className="space-y-3">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-400">
                Transport Mode
              </span>

              <div className="grid grid-cols-2 gap-2 rounded-xl border border-slate-800 bg-slate-900/80 p-1">
                <button
                  onClick={() => handleConnectionModeChange("usb")}
                  className={`flex items-center justify-center gap-2 rounded-lg py-2 text-xs font-bold transition ${
                    status.connection_mode === "usb"
                      ? "bg-cyan-500 text-slate-950 shadow"
                      : "text-slate-400 hover:text-white"
                  }`}
                >
                  <Usb className="h-3.5 w-3.5" />
                  USB (ADB)
                </button>

                <button
                  onClick={() => handleConnectionModeChange("wifi")}
                  className={`flex items-center justify-center gap-2 rounded-lg py-2 text-xs font-bold transition ${
                    status.connection_mode === "wifi"
                      ? "bg-cyan-500 text-slate-950 shadow"
                      : "text-slate-400 hover:text-white"
                  }`}
                >
                  <Wifi className="h-3.5 w-3.5" />
                  Wi-Fi (UDP)
                </button>
              </div>

              {status.connection_mode === "usb" ? (
                <div className="rounded-xl border border-slate-800/80 bg-slate-900/40 p-3 text-xs space-y-2">
                  <div className="flex items-center justify-between text-slate-300">
                    <span>ADB Tunnel:</span>
                    <span className="font-mono text-cyan-400">127.0.0.1:5555</span>
                  </div>
                  <button
                    onClick={handleRefreshAdb}
                    disabled={isRefreshingAdb}
                    className="flex w-full items-center justify-center gap-2 rounded-lg border border-slate-700 bg-slate-800 py-1.5 text-xs text-slate-200 hover:bg-slate-700"
                  >
                    <RefreshCw className={`h-3 w-3 ${isRefreshingAdb ? "animate-spin text-cyan-400" : ""}`} />
                    <span>Scan USB Devices</span>
                  </button>
                </div>
              ) : (
                <div className="rounded-xl border border-slate-800/80 bg-slate-900/40 p-3 text-xs space-y-2">
                  <div className="flex items-center justify-between text-slate-300">
                    <span>Target Phone IP:</span>
                    <input
                      type="text"
                      value={wifiIp}
                      onChange={(e) => setWifiIp(e.target.value)}
                      className="w-28 rounded border border-slate-700 bg-slate-800 px-1.5 py-0.5 text-right font-mono text-cyan-400 focus:outline-none"
                    />
                  </div>
                  <div className="flex items-center justify-between text-slate-300">
                    <span>UDP Listen Port:</span>
                    <input
                      type="number"
                      value={wifiPort}
                      onChange={(e) => setWifiPort(Number(e.target.value))}
                      className="w-20 rounded border border-slate-700 bg-slate-800 px-1.5 py-0.5 text-right font-mono text-cyan-400 focus:outline-none"
                    />
                  </div>
                  <div className="text-[11px] text-slate-400">
                    On your mobile app, switch to Wi-Fi mode and stream to this PC's local IP address.
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Virtual Webcam Driver Card */}
          <div className="mt-6 rounded-xl border border-slate-800 bg-slate-900/70 p-3 text-xs space-y-2">
            <div className="flex items-center justify-between font-semibold text-slate-200">
              <span>DirectShow Virtual Cam</span>
              <span className="text-[10px] text-emerald-400 font-mono">READY</span>
            </div>
            <p className="text-[11px] text-slate-400">
              Select <strong>"Unity Video Capture"</strong> in Zoom, Discord, OBS, or Chrome.
            </p>
            <button
              onClick={copyRegisterScript}
              className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-cyan-500/30 bg-cyan-500/10 py-1 text-[11px] font-medium text-cyan-300 hover:bg-cyan-500/20"
            >
              <Copy className="h-3 w-3" />
              <span>{copiedScript ? "Copied Script Command!" : "Copy Driver Register Script"}</span>
            </button>
          </div>
        </aside>
      </div>
    </div>
  );
}
