use std::sync::mpsc::{Sender, channel, Receiver};
use crate::include::common::config::{PageId};
use std::thread;
use crate::include::storage::disk::disk_manager::DiskManager;

pub struct DiskRequest<'a> {
    pub page_id: PageId,
    pub is_write: bool,
    pub data: &'a mut [u8],  
    // callback used to signal the request issuer when the request has been completed
    pub is_done: Sender<bool>    
}

pub struct DiskScheduler<'a>{
    disk_manager: DiskManager,
    request_queue_tx: Sender<Option<DiskRequest<'a>>>,
    request_queue_rx: Receiver<Option<DiskRequest<'a>>>,
    background_thread: Option<thread::JoinHandle<()>> // handle to a spawned thread
}

pub trait DiskSchedulerTrait {
    fn schedule(&self, disk_request: DiskRequest);
    fn start_worker_thread(&self);
    fn deallocate_page(&self);
}