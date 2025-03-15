use crate::include::storage::disk::disk_scheduler::{DiskScheduler,DiskRequest, DiskSchedulerTrait};
use crate::include::storage::disk::disk_manager::DiskManager;
use std::sync::mpsc::{channel, Sender, Receiver};
use std::thread;

impl DiskSchedulerTrait for DiskScheduler {
    fn new(disk_manager: DiskManager) -> Self {
        let (tx, rx) = channel();
         
        let scheduler = Self {
            disk_manager,
            request_queue_tx: tx,
            request_queue_rx: rx,
            background_thread: None
        };
        scheduler.start_worker_thread();
        scheduler

    }
    fn schedule(&self, disk_request: DiskRequest) {
        &self.request_queue_tx.send(Some(disk_request)).unwrap();
    }

    fn start_worker_thread(&self) {
        if self.background_thread.is_none() {
            // start only if the thread is not running
            let rx = self.request_queue_rx.clone();
            let dm = self.disk_manager.clone();
            let handle = thread::spawn(move || {
                while let Ok(Some(req)) = rx.recv() {
                    if req.is_write {
                        dm.write_page(req.page_id, req.data);
                    } else {
                        dm.read_page(req.page_id, req.data);
                    }
                    req.is_done.send(true).unwrap();
                }
            });
            unsafe {
                let mt = self as *const Self as *mut Self;
                (*mt).background_thread = Some(handle);
            }

        }
    }

    fn deallocate_page(&self) {

    }
}
