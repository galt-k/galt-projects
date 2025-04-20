use crate::include::common::config::PageId;
use crate::include::storage::disk::disk_scheduler::{ DiskRequest, DiskSchedulerTrait};
use crate::storage::disk::disk_manager::DiskManager;
use std::sync::mpsc::{channel, Sender, Receiver};
use std::thread;
use std::sync::Arc;
use std::sync::Mutex;

pub struct DiskScheduler {
    pub disk_manager: DiskManager,
    pub request_queue_tx: Arc<Mutex<Sender<Option<DiskRequest>>>>,
    pub request_queue_rx: Arc<Mutex<Receiver<Option<DiskRequest>>>>, // making the receiver a mutex
    pub background_threads: Vec<thread::JoinHandle<()>>, // handle to a spawned thread
}

impl DiskScheduler {
    pub fn new(disk_manager: DiskManager) -> Self {
        let (tx, rx) = channel();
        let rx = Arc::new(Mutex::new(rx)); // putting the receiver into a mutex
         
        let mut scheduler = Self {
            disk_manager,
            request_queue_tx: Arc::new(Mutex::new(tx)),
            request_queue_rx: rx,
            background_threads: Vec::new()
        };
        scheduler.start_worker_thread();
        scheduler

    }

}

impl DiskSchedulerTrait for DiskScheduler {
    fn schedule(&self, disk_request: DiskRequest) {
        self.request_queue_tx.lock().unwrap().send(Some(disk_request)).unwrap();
    }

    fn start_worker_thread(&mut self) {
        if self.background_threads.len() == 0 {
            let max_num_threads = 10;
            // start only if the thread is not running
            let dm = Arc::new(self.disk_manager.clone()); //Arc for sharing across multiple threads
            //let rx: Receiver<Option<DiskRequest>> = std::mem::replace(&mut self.request_queue_rx, channel().1); // Move rx—replace with dummy
            //let rx_new: std::sync::MutexGuard<'_, Receiver<Option<DiskRequest>>> = self.request_queue_rx.lock().unwrap();
            let clone = self.request_queue_rx.clone();
            let dm_clone = dm.clone();
            

            


            for _ in 0..max_num_threads {
                let clone = clone.clone();
                let dm_clone = dm_clone.clone();

                let handle = thread::spawn(move || {

                    // new change
                    
                    let rx_new: std::sync::MutexGuard<'_, Receiver<Option<DiskRequest>>> = clone.lock().unwrap();
                    //
                    while let Ok(Some(req)) = rx_new.recv() {
                        if req.is_write {
                            let data = req.data.lock().unwrap();
                            dm_clone.write_page(req.page_id, &data);
                        } else {
                            let mut data = req.data.lock().unwrap();
                            dm_clone.read_page(req.page_id, &mut data);
                        }
                        req.is_done.send(true).unwrap();
                    }
                });
                self.background_threads.push(handle)

            }
            
            // unsafe {
            //     let mt = self as *const Self as *mut Self;
            //     (*mt).background_threads = Some(handle);
            // }
        }
    }

    fn deallocate_page(&self) {
        // In this implementation, we don't actually deallocate pages
        // as we're using a simple append-only file structure
        // In a real implementation, you might want to:
        // 1. Mark the page as deleted in a free space map
        // 2. Add the page to a free list
        // 3. Or implement actual file space reclamation
    }
}
