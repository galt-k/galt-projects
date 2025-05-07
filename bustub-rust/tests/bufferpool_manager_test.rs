use bustub_rust::buffer::bufferpool_manager::{FrameHeader,BufferPoolManager};
use bustub_rust::include::buffer::bufferpool_manager::BufferPoolManagerImpl;
use bustub_rust::include::storage::page::page_guard::{PageguardImpl, ReadPageGuardImpl, WritePageGuardImpl};
use bustub_rust::storage::disk::disk_manager::DiskManager;
use bustub_rust::include::storage::disk::disk_scheduler::DiskRequest;
use bustub_rust::{
    storage::disk::disk_scheduler::DiskScheduler,
    storage::page::page_guard::ReadPageGuard,
    include::buffer::lru_k_replacer::LRUKReplacer,
    buffer::lru_k_replacer::{LRUKReplacerImpl},
};
use std::sync::Arc;
use bustub_rust::include::common::config::AccessType::{Index,Unknown,Lookup, Scan};
use bustub_rust::include::common::config::PAGE_SIZE;


#[test]
fn test_basic_bpm() {
    assert_eq!(0, 0, "Sample tests");
}

#[test]
fn test_bpm_initialization(){
    let dm = DiskManager::new("test.db");
    let scheduler = DiskScheduler::new( dm.unwrap());
    let lru_k_replacer_impl = LRUKReplacerImpl::new(100, 3);
    let bpm = BufferPoolManager::new
        (
            10,
            Arc::new(scheduler),
            Arc::new(lru_k_replacer_impl),
        );
    assert_eq!(bpm.size(),10, "Test failed. Values must match");
}

#[test]
fn test_bpm_read_page(){
    let dm = DiskManager::new("test.db");
    let scheduler = DiskScheduler::new( dm.unwrap());
    let lru_k_replacer_impl = LRUKReplacerImpl::new(100, 3);
    let bpm = BufferPoolManager::new
        (
            10,
            Arc::new(scheduler),
            Arc::new(lru_k_replacer_impl),
        );

    // Create Write request
    {
        let mut write_page_guard= bpm.write_page(0, Index);
        assert_eq!(write_page_guard.get_page_id(), 0, "Test failed");
        assert_eq!(write_page_guard.get_frame_id(),0, "Test failed");
        let page_data_ref=write_page_guard.as_mut();
        assert_eq!(page_data_ref.len(), PAGE_SIZE);
        let new_value = [1u8; PAGE_SIZE];
        page_data_ref.copy_from_slice(&new_value);
        write_page_guard.flush();
    }
    // Create a read request
    {
        let expected_value = [1u8; PAGE_SIZE];
        let read_page_guard = bpm.read_page(0, Index);
        let read_page_ref = read_page_guard.as_ref();
        read_page_guard.flush();
        assert_eq!(read_page_ref.len(), PAGE_SIZE, "length doesnt match");
        assert_eq!(read_page_ref, expected_value, "Values are equal");
        assert_eq!(read_page_ref[0], 1,"Value at index 0 is 1");

    }
}