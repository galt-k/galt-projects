use std::sync::{Arc, Mutex};
use crate::buffer::bufferpool_manager::{BufferPoolManager, FrameHeader};
use crate::include::buffer::bufferpool_manager::{BufferPoolManagerImpl, FrameHeaderImpl};
use crate::include::common::config::{AccessType, FrameId, PageId};
use crate::include::storage::page::page_guard::{PageguardImpl,ReadPageGuardImpl,WritePageGuardImpl};
use crate::buffer::lru_k_replacer::LRUKReplacerImpl;
use crate::storage::disk::disk_scheduler::DiskScheduler;
use crate::include::buffer::lru_k_replacer::LRUKReplacer;
use crate::include::storage::disk::disk_scheduler::DiskRequest;
use crate::include::storage::disk::disk_scheduler::DiskSchedulerTrait;
use std::sync::mpsc::channel;


//use std::alloc::Global;
pub struct BasicPageGuard {
    bpm: Arc<BufferPoolManager>,
    frame: Arc<FrameHeader>,
    frame_id: FrameId,
    page_id: PageId,
    is_valid: bool,
}

impl BasicPageGuard {
    pub fn new (
        bpm : Arc<BufferPoolManager>,
        frame: Arc<FrameHeader>,
        frame_id: FrameId,
        page_id: PageId,
    ) -> Self {
        frame.increment_pin_count();// Why is this required here?
        BasicPageGuard {
            bpm,
            frame,
            frame_id,
            page_id,
            is_valid: true,
        }
    }

    pub(crate) fn frame(&self) -> &Arc<FrameHeader> {
        &self.frame
    }
}

impl PageguardImpl for BasicPageGuard {
    fn get_page_id(&self) -> PageId {
        self.page_id
    }

    fn drop_guard(&mut self) {
        if self.is_valid {
            self.frame.decrement_pin_count();
            self.is_valid = false;
        }
    }
    // not needed for now. 
    fn get_frame_id(&self) -> FrameId {
        self.frame_id
    }
}

impl Drop for BasicPageGuard {
    fn drop(&mut self) {
        self.drop_guard();
    }
}

// ReadPageGuard implementation 
pub struct ReadPageGuard {
    guard: BasicPageGuard,
    replacer: Arc<LRUKReplacerImpl>,
    bpm_latch: Arc<Mutex<()>>,// What is the purpose of this?
    disk_scheduler: Arc<DiskScheduler>,
    is_valid: bool,
    //bpm:  Arc<BufferPoolManager>
}

impl ReadPageGuard {
    pub fn new(
        page_id: PageId,
        frame_id: FrameId,
        frame: Arc<FrameHeader>, 
        replacer: Arc<LRUKReplacerImpl>,
        bpm_latch: Arc<Mutex<()>>,
        disk_scheduler: Arc<DiskScheduler>,
        bpm: Arc<BufferPoolManager>,
    ) -> Self {
        let guard = BasicPageGuard::new(bpm, frame,frame_id, page_id);
        replacer.record_access(guard.get_frame_id(), AccessType::Unknown);
        ReadPageGuard {
            guard,
            replacer,
            bpm_latch,
            disk_scheduler,
            is_valid: true,
        }
    }
}
impl PageguardImpl for ReadPageGuard {
    fn get_page_id(&self) -> PageId {
        self.guard.get_page_id()
    }

    fn get_frame_id(&self) -> FrameId {
        self.guard.get_frame_id()
    }

    fn drop_guard(&mut self) {
        if self.is_valid {
            self.guard.drop_guard();
            self.is_valid = false;
        }
    }
}
impl ReadPageGuardImpl for ReadPageGuard {
    fn as_ref(&self) -> &[u8] {
        //add the lru k record access here. 
        //let mut replacer= &self.replacer.lock().unwrap();
        //replacer.record_access(&self.get_frame_id(), AccessType::Unknown);
        self.guard.frame.get_data()
    }

    fn is_dirty(&self) -> bool {
        self.guard.frame.is_dirty()
    }

    fn flush(&self) {
        // Placeholder: use disk scduler to flush the frame data to disk. 
        if self.is_dirty() {
            let data = self.guard.frame().get_data();
            let request = DiskRequest {
                is_write: true,
                page_id: self.get_page_id(),
                data: Arc::new(Mutex::new(data.to_vec())),
                is_done: channel().0,
            };
            self.disk_scheduler.schedule(request);
        }
    }   
}

impl Drop for ReadPageGuard {
    fn drop(&mut self) {
        self.drop_guard()
    }
    
}

pub struct WritePageGuard {
    guard: BasicPageGuard,
    replacer: Arc<LRUKReplacerImpl>,
    bpm_latch: Arc<Mutex<()>>,
    disk_scheduler: Arc<DiskScheduler>,
    is_valid: bool,
}

impl WritePageGuard {
    pub fn new(
        page_id: PageId,
        frame_id: FrameId,
        frame: Arc<FrameHeader>,
        replacer: Arc<LRUKReplacerImpl>,
        bpm_latch: Arc<Mutex<()>>,
        disk_scheduler: Arc<DiskScheduler>,
        bpm: Arc<BufferPoolManager>,
    ) -> Self {
        let guard = BasicPageGuard::new(bpm, frame,frame_id, page_id);
        replacer.record_access(guard.get_frame_id(), AccessType::Unknown);
        WritePageGuard {
            guard,
            replacer,
            bpm_latch,
            disk_scheduler,
            is_valid: true,
        }
    }
}

impl PageguardImpl for WritePageGuard {
    fn get_page_id(&self) -> PageId {
        self.guard.get_page_id()
    }

    fn get_frame_id(&self) -> FrameId {
        self.guard.get_frame_id()
    }

    fn drop_guard(&mut self) {
        if self.is_valid {
            self.guard.drop_guard();
            self.is_valid = false;
        }
    }
}

impl WritePageGuardImpl for WritePageGuard {
    fn as_ref(&self) -> &[u8] {
        self.guard.frame().get_data()
    }

    fn as_mut(&mut self) -> &mut [u8] {
        self.guard.frame().get_data_mut()
    }

    fn is_dirty(&self) -> bool {
        self.guard.frame().is_dirty()
    }

    fn flush(&self) {
        // Placeholder: Use disk_scheduler to flush frame data
        if self.is_dirty() {
            let data = self.guard.frame().get_data();
            let request = DiskRequest {
                is_write: true,
                page_id: self.get_page_id(),
                data: Arc::new(Mutex::new(data.to_vec())),
                is_done: channel().0,
            };
            self.disk_scheduler.schedule(request);
        }
    }
}

impl Drop for WritePageGuard {
    fn drop(&mut self) {
        self.drop_guard();
    }
}