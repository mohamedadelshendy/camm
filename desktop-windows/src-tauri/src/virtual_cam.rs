use std::ptr;
use std::time::{SystemTime, UNIX_EPOCH};
use log::{debug, error, info, warn};
use windows::core::{PCWSTR, HSTRING};
use windows::Win32::Foundation::{CloseHandle, HANDLE, WAIT_OBJECT_0};
use windows::Win32::System::Memory::{
    CreateFileMappingW, MapViewOfFile, UnmapViewOfFile, FILE_MAP_ALL_ACCESS, MEMORY_MAPPED_VIEW_ADDRESS,
};
use windows::Win32::System::Threading::{
    CreateEventW, CreateMutexW, ReleaseMutex, SetEvent, WaitForSingleObject,
};

#[repr(C, packed)]
#[derive(Copy, Clone, Debug)]
pub struct UnityCaptureMemoryHeader {
    pub width: u32,
    pub height: u32,
    pub format: u32, // 0 = ARGB32, 1 = RGB24
    pub stride: u32,
    pub timestamp: u64, // 100ns units
}

pub struct VirtualCameraSink {
    mutex_handle: Option<HANDLE>,
    mapping_handle: Option<HANDLE>,
    event_handle: Option<HANDLE>,
    view_ptr: Option<MEMORY_MAPPED_VIEW_ADDRESS>,
    current_capacity: usize,
    is_active: bool,
}

unsafe impl Send for VirtualCameraSink {}
unsafe impl Sync for VirtualCameraSink {}

impl VirtualCameraSink {
    pub fn new() -> Self {
        let mut sink = Self {
            mutex_handle: None,
            mapping_handle: None,
            event_handle: None,
            view_ptr: None,
            current_capacity: 0,
            is_active: false,
        };
        sink.init_handles();
        sink
    }

    fn init_handles(&mut self) {
        unsafe {
            // UnityCapture named synchronization primitives
            let mutex_name = HSTRING::from("UnityCapture_Mutex_0");
            let event_name = HSTRING::from("UnityCapture_Event_0");

            let mutex = CreateMutexW(None, false, PCWSTR(mutex_name.as_ptr())).ok();
            let event = CreateEventW(None, false, false, PCWSTR(event_name.as_ptr())).ok();

            self.mutex_handle = mutex;
            self.event_handle = event;
            self.is_active = self.mutex_handle.is_some();

            if self.is_active {
                info!("VirtualCameraSink initialized UnityCapture shared memory hooks");
            } else {
                warn!("VirtualCameraSink: could not initialize shared memory mutex");
            }
        }
    }

    fn ensure_buffer_capacity(&mut self, required_size: usize) -> bool {
        if self.current_capacity >= required_size && self.view_ptr.is_some() {
            return true;
        }

        unsafe {
            // Clean up existing view
            if let Some(view) = self.view_ptr.take() {
                let _ = UnmapViewOfFile(view);
            }
            if let Some(handle) = self.mapping_handle.take() {
                let _ = CloseHandle(handle);
            }

            let mem_name = HSTRING::from("UnityCapture_Memory_0");
            let high_size = (required_size >> 32) as u32;
            let low_size = (required_size & 0xFFFFFFFF) as u32;

            let mapping = match CreateFileMappingW(
                HANDLE(ptr::null_mut()),
                None,
                windows::Win32::System::Memory::PAGE_READWRITE,
                high_size,
                low_size,
                PCWSTR(mem_name.as_ptr()),
            ) {
                Ok(m) => m,
                Err(e) => {
                    warn!("Failed to create shared memory mapping: {e}");
                    return false;
                }
            };

            let view = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, required_size);
            if view.Value.is_null() {
                warn!("Failed to map view of shared memory");
                let _ = CloseHandle(mapping);
                return false;
            }

            self.mapping_handle = Some(mapping);
            self.view_ptr = Some(view);
            self.current_capacity = required_size;
            debug!("Allocated {} bytes of shared memory for Virtual Camera", required_size);
            true
        }
    }

    pub fn write_frame(&mut self, width: u32, height: u32, rgba_data: &[u8]) -> bool {
        let stride = width * 4;
        let pixel_bytes = (stride * height) as usize;
        let total_size = std::mem::size_of::<UnityCaptureMemoryHeader>() + pixel_bytes;

        if !self.ensure_buffer_capacity(total_size) {
            return false;
        }

        let mutex = match self.mutex_handle {
            Some(m) => m,
            None => return false,
        };

        unsafe {
            // Lock mutex with 30ms timeout
            let wait_res = WaitForSingleObject(mutex, 30);
            if wait_res != WAIT_OBJECT_0 {
                return false;
            }

            if let Some(view) = self.view_ptr {
                let base_ptr = view.Value as *mut u8;

                // Calculate 100ns DirectShow timestamp
                let now_nanos = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_nanos();
                let timestamp_100ns = (now_nanos / 100) as u64;

                // 1. Write Header
                let header = UnityCaptureMemoryHeader {
                    width,
                    height,
                    format: 0, // ARGB32
                    stride,
                    timestamp: timestamp_100ns,
                };
                ptr::copy_nonoverlapping(
                    &header as *const _ as *const u8,
                    base_ptr,
                    std::mem::size_of::<UnityCaptureMemoryHeader>(),
                );

                // 2. Write pixel payload (convert RGBA to ARGB/BGRA layout expected by DirectShow)
                let payload_dst = base_ptr.add(std::mem::size_of::<UnityCaptureMemoryHeader>());
                let copy_len = pixel_bytes.min(rgba_data.len());
                ptr::copy_nonoverlapping(rgba_data.as_ptr(), payload_dst, copy_len);
            }

            let _ = ReleaseMutex(mutex);

            // Signal frame ready event to DirectShow filter
            if let Some(event) = self.event_handle {
                let _ = SetEvent(event);
            }

            true
        }
    }

    pub fn is_active(&self) -> bool {
        self.is_active
    }
}

impl Drop for VirtualCameraSink {
    fn drop(&mut self) {
        unsafe {
            if let Some(view) = self.view_ptr.take() {
                let _ = UnmapViewOfFile(view);
            }
            if let Some(handle) = self.mapping_handle.take() {
                let _ = CloseHandle(handle);
            }
            if let Some(handle) = self.mutex_handle.take() {
                let _ = CloseHandle(handle);
            }
            if let Some(handle) = self.event_handle.take() {
                let _ = CloseHandle(handle);
            }
        }
    }
}
