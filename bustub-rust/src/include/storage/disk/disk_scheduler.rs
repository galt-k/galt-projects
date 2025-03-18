use crate::include::common::config::PageId;
//use crate::storage::disk::disk_manager::DiskManager;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread;
use std::sync::{Arc, Mutex};

#[derive(Clone)]
pub struct DiskRequest {
    pub page_id: PageId,
    pub is_write: bool,
    pub data: Arc<Mutex<Vec<u8>>>,
    // callback used to signal the request issuer when the request has been completed
    pub is_done: Sender<bool>,
}

pub trait DiskSchedulerTrait {
    fn schedule(&self, disk_request: DiskRequest);
    fn start_worker_thread(&mut self);
    fn deallocate_page(&self);
}
