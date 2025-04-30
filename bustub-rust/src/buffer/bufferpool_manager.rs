use crate::include::buffer::bufferpool_manager::{FrameHeaderImpl,BufferPoolManagerImpl};
use crate::include::common::config::{PAGE_SIZE,PageId,FrameId};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{RwLock, RwLockReadGuard, RwLockWriteGuard};
pub struct BufferPoolManager;
pub struct FrameHeader {
    frame_id: FrameId,
    page_id: Option<PageId>, // There can be a frame with no page in it. 
    rwlatch: RwLock<()>,
    pin_count: AtomicUsize,
    is_dirty: bool,
    data: [u8; PAGE_SIZE],
}


impl FrameHeaderImpl for FrameHeader {
    fn new(frame_id: FrameId) -> Self {
        FrameHeader {
            frame_id,
            page_id: None,
            rwlatch: RwLock::new(()),
            pin_count: AtomicUsize::new(0),
            is_dirty: false,
            data: [0; PAGE_SIZE],

        }

    }

    fn get_data(&self) -> &[u8] {
        let _gaurd = self.rwlatch.read().unwrap();
        &self.data
    }

    fn get_data_mut(&mut self) -> &mut  [u8] {
        let _guard = self.rwlatch.write().unwrap();
        self.is_dirty = true;
        &mut self.data
    }

    fn reset(&mut self) {
        let _guard = self.rwlatch.write().unwrap();
        self.page_id = None;
        self.pin_count.store(0,Ordering::SeqCst);
        self.is_dirty = false;
        self.data = [0; PAGE_SIZE];
    }

    fn get_frame_id(&self) -> FrameId {
        self.frame_id
    }

    fn get_page_id(&self) -> Option<PageId> {
        self.page_id
    }

    fn set_page_id(&mut self, page_id: PageId) {
        self.page_id = Some(page_id);
    } 

    fn get_pin_count(&self) -> usize {
        self.pin_count.load(Ordering::SeqCst)
    }

    fn increment_pin_count(&self) {
        self.pin_count.fetch_add(1, Ordering::SeqCst);
    }

    fn decrement_pin_count(&self) {
        self.pin_count.fetch_sub(1, Ordering::SeqCst);
    }

    fn is_dirty(&self) -> bool {
        self.is_dirty
    }

    fn set_is_dirty(&mut self, is_dirty: bool) {
        self.is_dirty = is_dirty
    }

    fn read_latch(&self) -> RwLockReadGuard<()> {
        self.rwlatch.read().unwrap()        
    }

    fn write_latch(&self) -> RwLockWriteGuard<()> {
        self.rwlatch.write().unwrap()
    }

}