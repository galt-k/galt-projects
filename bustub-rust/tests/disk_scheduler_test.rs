use bustub_rust::{
    storage::disk::disk_scheduler::DiskScheduler,
    include::buffer::lru_k_replacer::LRUKReplacer,
};
use std::sync::mpsc::channel;
#[cfg(test)]
mod tests {
    use std::sync::mpsc::channel;
    use std::sync::{Arc, Mutex};

    use bustub_rust::include::storage::disk::disk_scheduler::DiskRequest;
    use bustub_rust::include::storage::disk::{disk_scheduler::DiskSchedulerTrait};
    use bustub_rust::storage::disk::disk_manager::DiskManager;

    use super::*;

    #[test]
    fn test_disk_scheduler_initialization() {
        let dm = DiskManager::new("test.db");
        let scheduler = DiskScheduler::new( dm.unwrap());
        assert_eq!(1,1,"test passed");
    }

    #[test]
    fn test_disk_read_page() {
        let dm = DiskManager::new("test.db").unwrap();
        let mut scheduler = DiskScheduler::new(dm);
        let (tx, rx) = channel();
    
        // Write
        let write_data = vec![0xFF; 4096];
        let write_data2 = vec![0xFF; 4096];
        let write_req = DiskRequest {
            page_id: 1,
            data: Arc::new(Mutex::new(write_data)),
            is_write: true,
            is_done: tx.clone(),
        };
        let write_req2 = DiskRequest {
            page_id: 2,
            data: Arc::new(Mutex::new(write_data2)),
            is_write: true,
            is_done: tx.clone(),
        };
        scheduler.schedule(write_req);
        scheduler.schedule(write_req2);
        rx.recv().unwrap();
        rx.recv().unwrap();
    
        // 10 Parallel Reads
        let read_data = Arc::new(Mutex::new(vec![0; 4096]));
        for _ in 0..10 {
            let read_req = DiskRequest {
                page_id: 1,
                data: read_data.clone(),
                is_write: false,
                is_done: tx.clone(),
            };
            scheduler.schedule(read_req);
        }
        for _ in 0..10 {
            rx.recv().unwrap(); // Wait for all
        }
    
        let result = read_data.lock().unwrap();
        assert_eq!(result.len(), 4096, "Read data length matched");
        assert_eq!(&result[..], &[0xFF; 4096], "Content match");
    }

    // Add more test cases here as needed
} 