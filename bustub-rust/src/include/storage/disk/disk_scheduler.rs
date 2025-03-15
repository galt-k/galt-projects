use crate::include::common::config::PageId;
use crate::include::storage::disk::disk_manager::DiskManager;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread;

use super::disk_manager;

#[derive(Clone)]
pub struct DiskRequest<'a> {
    pub page_id: PageId,
    pub is_write: bool,
    pub data: &'a mut [u8],
    // callback used to signal the request issuer when the request has been completed
    pub is_done: Sender<bool>,
}

pub struct DiskScheduler<'a> {
    disk_manager: DiskManager,
    request_queue_tx: Sender<Option<DiskRequest<'a>>>,
    request_queue_rx: Receiver<Option<DiskRequest<'a>>>,
    background_thread: Option<thread::JoinHandle<()>>, // handle to a spawned thread
}

pub trait DiskSchedulerTrait {
    fn new(&self, disk_manager: DiskManager);
    fn schedule(&self, disk_request: DiskRequest);
    fn start_worker_thread(&self);
    fn deallocate_page(&self);
}
