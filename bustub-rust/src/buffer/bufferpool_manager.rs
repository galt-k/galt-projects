use crate::include::buffer::bufferpool_manager::{FrameHeaderImpl,BufferPoolManagerImpl};
use crate::include::buffer::lru_k_replacer::LRUKReplacer;
use crate::include::common::config::{PAGE_SIZE,PageId,FrameId, AccessType};
use crate::include::storage::page::page::Page;
use crate::storage::disk::disk_scheduler::DiskScheduler;
use std::collections::{HashMap, LinkedList};
use std::sync::atomic::{AtomicUsize, Ordering, AtomicI32};
use std::sync::{Arc, Mutex, RwLock, RwLockReadGuard, RwLockWriteGuard};
use super::lru_k_replacer::LRUKReplacerImpl;

use crate::storage::page::page_guard::{ReadPageGuard,WritePageGuard};

#[derive(Debug)]
pub struct FrameHeader {
    frame_id: FrameId,
    page_id: Mutex<Option<PageId>>, // There can be a frame with no page in it. 
    rwlatch: RwLock<()>,
    pin_count: AtomicUsize,
    is_dirty: Mutex<bool>,
    data: Mutex<[u8; PAGE_SIZE]>,
}

impl FrameHeader {
    fn new(frame_id: FrameId) -> Self {
        FrameHeader {
            frame_id,
            page_id: Mutex::new(None),
            rwlatch: RwLock::new(()),
            pin_count: AtomicUsize::new(0),
            is_dirty: Mutex::new(false),
            data: Mutex::new([0; PAGE_SIZE]),

        }

    }
}
impl FrameHeaderImpl for FrameHeader {

    fn get_data(&self) -> &[u8] {
        let _gaurd = self.rwlatch.read().unwrap();
        let data = self.data.lock().unwrap();
        unsafe { &*(&*data as *const [u8; PAGE_SIZE]) }
    }

    fn get_data_mut(&self) -> &mut  [u8] {
        let _guard = self.rwlatch.write().unwrap();
        let mut data = self.data.lock().unwrap();
        *self.is_dirty.lock().unwrap()= true;
        unsafe { &mut *(&mut *data as *mut [u8; PAGE_SIZE]) }
    }

    fn reset(&self) {
        let _guard = self.rwlatch.write().unwrap();
        *self.page_id.lock().unwrap() = None;
        self.pin_count.store(0,Ordering::SeqCst);
        *self.is_dirty.lock().unwrap()= false;
        *self.data.lock().unwrap()= [0; PAGE_SIZE];
    }

    fn get_frame_id(&self) -> FrameId {
        self.frame_id
    }

    fn get_page_id(&self) -> Option<PageId> {
        *self.page_id.lock().unwrap()
    }

    fn set_page_id(&mut self, page_id: PageId)-> PageId {
        *self.page_id.lock().unwrap() = Some(page_id);
        page_id
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
        *self.is_dirty.lock().unwrap()
    }

    fn set_is_dirty(&self, is_dirty: bool) {
        *self.is_dirty.lock().unwrap() = is_dirty
    }

    fn read_latch(&self) -> RwLockReadGuard<()> {
        self.rwlatch.read().unwrap()        
    }

    fn write_latch(&self) -> RwLockWriteGuard<()> {
        self.rwlatch.write().unwrap()
    }

}

pub struct BufferPoolManager {
    num_frames: usize,
    next_page_id: AtomicI32,
    bpm_latch: Arc<Mutex<()>>,
    frames: Vec<Arc<FrameHeader>>,
    pub page_table: Mutex<HashMap<PageId, FrameId>>, 
    free_frames: Mutex<LinkedList<FrameId>>,
    replacer: Arc<LRUKReplacerImpl>,
    disk_scheduler: Arc<DiskScheduler>,
}

impl BufferPoolManager {
    pub fn new(
        num_frames: usize,
        disk_manager: Arc<DiskScheduler>,
        lru_k_replacer: Arc<LRUKReplacerImpl>,
    ) -> Self {
        let mut frames = Vec::with_capacity(num_frames);
        let mut free_frames = LinkedList::new();
        for i in 0..num_frames {
            frames.push(Arc::new(FrameHeader::new(i as FrameId)));
            free_frames.push_back(i as FrameId);
        }
        BufferPoolManager {
            num_frames,
            next_page_id: AtomicI32::new(0),
            bpm_latch: Arc::new(Mutex::new(())),
            frames,
            page_table: Mutex::new(HashMap::new()),
            free_frames: Mutex::new(free_frames),
            replacer: lru_k_replacer,
            disk_scheduler: disk_manager,
        }
    }

    fn fetch_frame(&self, page_id: PageId) -> Option<(FrameId, Arc<FrameHeader>)> {
        let mut page_table = self.page_table.lock().unwrap();
        if let Some(&frame_id) = page_table.get(&page_id) {
            // print the page table
            //println!("page table {:?}", page_table);
            //println!("frame {:?}", self.frames[frame_id as usize].clone());
            return Some((frame_id, self.frames[frame_id as usize].clone()));
        }
        //println!("page table {:?}", page_table);
        // if not create a new frame
        let mut free_frames = self.free_frames.lock().unwrap(); 
        if let Some(frame_id) = free_frames.pop_front() {
            page_table.insert(page_id, frame_id);
            return Some((frame_id, self.frames[frame_id as usize].clone()));
        
        // placeholder: use a replacer to evict a frame???

        }

        None
    }
}

impl BufferPoolManagerImpl for BufferPoolManager {
    fn size(&self) -> usize {
        self.num_frames
    }
    /// Allocates a newpage (default) on disk and in-memory. 
    fn new_page(&self) -> PageId {
        // create a new pageid
        let page_id = self.next_page_id.fetch_add(1, Ordering::SeqCst);
        // Check if we can create a new frame
        page_id
    }

    fn delete_page(&self, page_id: PageId) -> bool {
        let mut page_table = self.page_table.lock().unwrap(); 
        if let Some(frame_id) = page_table.remove(&page_id) {
            let frame = &self.frames[frame_id as usize];
            let mut free_frames = self.free_frames.lock().unwrap();
            free_frames.push_back(frame_id);
            frame.reset();
            true
        } else {
            false
        }
    }

    fn checked_read_page(&self, page_id: PageId, access_type: AccessType) -> Option<ReadPageGuard> {
        if let Some((frame_id, frame)) = self.fetch_frame(page_id) {
            //&self.replacer.record_access(frame_id, access_type);
            Some(ReadPageGuard::new(page_id,frame_id, frame, self.replacer.clone(), self.bpm_latch.clone(), self.disk_scheduler.clone(), Arc::new(self.clone()),))
        } else {
            None
        }
    }

    fn read_page(&self, page_id: PageId, access_type: AccessType) -> ReadPageGuard {
        self.checked_read_page(page_id, access_type).unwrap_or_else
        (|| {
            panic!("Failed to read page {}", page_id);
        })        
    }

    fn checked_write_page(&self, page_id: PageId, access_type: AccessType) -> Option<WritePageGuard> {
        if let Some((frame_id, frame)) = self.fetch_frame(page_id) {
            Some(WritePageGuard::new(page_id,frame_id ,frame, self.replacer.clone(), self.bpm_latch.clone(), self.disk_scheduler.clone(), Arc::new(self.clone()),))
        } else {
            None
        }
    }
    fn write_page(&self, page_id: PageId, access_type: AccessType) -> WritePageGuard {
        self.checked_write_page(page_id, access_type).unwrap_or_else(|| {
            panic!("Failed to write page {}", page_id);
        })
    }

    fn flush_page_unsafe(&self, page_id: PageId) -> bool {
        let page_table = self.page_table.lock().unwrap();
        if let Some(&frame_id) = page_table.get(&page_id) {
            let frame = &self.frames[frame_id as usize];
            // Placeholder: Flush frame data through diskscedhuler
            frame.set_is_dirty(false);
            true
        } else {
            false
        }
    }

    fn flush_page(&self, page_id: PageId) -> bool {
        let _guard = self.bpm_latch.lock().unwrap();
        self.flush_page_unsafe(page_id)
    }

    fn flush_all_pages_unsafe(&self) {
        let page_table = self.page_table.lock().unwrap();
        for (&page_id, &frame_id) in page_table.iter() {
            let frame = &self.frames[frame_id as usize];
            // Placeholder: Flush frame data via disk_scheduler
            frame.set_is_dirty(false);
        }
    }

    fn flush_all_pages(&self) {
        let _guard = self.bpm_latch.lock().unwrap();
        self.flush_all_pages_unsafe();
    }

    fn get_pin_count(&self, page_id: PageId) -> Option<usize> {
        let page_table = self.page_table.lock().unwrap();
        page_table.get(&page_id).map(|&frame_id| {
            self.frames[frame_id as usize].get_pin_count()
        })
    }

}

// Required for Arc<BufferPoolManager>
impl Clone for BufferPoolManager {
    fn clone(&self) -> Self {
        BufferPoolManager {
            num_frames: self.num_frames,
            next_page_id: AtomicI32::new(self.next_page_id.load(Ordering::SeqCst)),
            bpm_latch: self.bpm_latch.clone(),
            frames: self.frames.clone(),
            page_table: Mutex::new(self.page_table.lock().unwrap().clone()),
            free_frames: Mutex::new(self.free_frames.lock().unwrap().clone()),
            replacer: self.replacer.clone(),
            disk_scheduler: self.disk_scheduler.clone(),
        }
    }
}