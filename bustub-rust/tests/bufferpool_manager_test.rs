use bustub_rust::buffer::bufferpool_manager::{FrameHeader,BufferPoolManager};
use bustub_rust::include::buffer::bufferpool_manager::BufferPoolManagerImpl;
use bustub_rust::include::storage::page::b_plus_tree_internal_page::{BplusTreeInternalPage, BplusTreeInternalPageImpl, KeyType, INTERNAL_PAGE_SLOT_CNT};
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
use bustub_rust::include::common::config::{IndexPageType, ValueType, INVALID_PAGE_ID, PAGE_SIZE};
use bustub_rust::include::storage::page::b_plus_tree_leaf_page::{BplusTreeLeafPage, BplusTreeLeafPageImpl};
use bustub_rust::include::storage::page::b_plus_tree_page::{BplusTreePage, BplusTreePageImpl};
use bustub_rust::include::common::rid::Rid;
use bustub_rust::include::storage::page::b_plus_tree_leaf_page::{LEAF_PAGE_SLOT_CNT};

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
        let mut write_page_guard= bpm.write_page(100, Index);
        assert_eq!(write_page_guard.get_page_id(), 100, "Test failed");
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
        let read_page_guard = bpm.read_page(100, Index);
        let read_page_ref = read_page_guard.as_ref();
        read_page_guard.flush();
        assert_eq!(read_page_ref.len(), PAGE_SIZE, "length doesnt match");
        assert_eq!(read_page_ref, expected_value, "Values are equal");
        assert_eq!(read_page_ref[0], 1,"Value at index 0 is 1");
    }
}

#[test]
fn test_bplustree_index_leaf_page(){
    // Create bplus tree leaf page 
    //let bplus_tree_page = BplusTreePage::new(IndexPageType::LEAF_PAGE, 0, 1000);
    //let rid = Rid::new(0, 1);
    let dm = DiskManager::new("test.db");
    let scheduler = DiskScheduler::new( dm.unwrap());
    let lru_k_replacer_impl = LRUKReplacerImpl::new(100, 3);
    let bpm = BufferPoolManager::new
        (
            10,
            Arc::new(scheduler),
            Arc::new(lru_k_replacer_impl),
        );

    let mut bplus_tree_leaf_page = BplusTreeLeafPage::new(1000, 10);
    assert_eq!(bplus_tree_leaf_page.next_page_id, INVALID_PAGE_ID, "Invalid page id");
    // eprintln!("here here{}", bplus_tree_leaf_page.to_string());    
    // assert_eq!(bplus_tree_leaf_page.to_string(), " ","Not equal" );
    let key: KeyType = 42;
    let value = ValueType::Rid(Rid::new(10, 0));
    let key1: KeyType = 43;
    let value1 = ValueType::Rid(Rid::new(10, 1));
    let result1 = bplus_tree_leaf_page.insert(0, key, value);
    let result2 = bplus_tree_leaf_page.insert(1, key1, value1);
    
    /////////////////////
    let mut write_page_guard= bpm.write_page(100, Index);
    let page_data_ref=write_page_guard.as_mut();
    unsafe {
        let data_ptr = page_data_ref.as_mut_ptr() as *mut BplusTreeLeafPage;
        std::ptr::write(data_ptr, bplus_tree_leaf_page);
    }
    write_page_guard.flush();

    let read_page_guard = bpm.read_page(100, Index);
    let read_data = read_page_guard.as_ref();
    assert_eq!(read_data.len(), PAGE_SIZE, "Read page size mismatch");
    let read_leaf = unsafe {
        let data_ptr = read_data.as_ptr() as *const BplusTreeLeafPage;
        &*data_ptr
    };

    assert_eq!(read_leaf.next_page_id, INVALID_PAGE_ID, "Read back failed");
    assert_eq!(read_leaf.base_page.get_max_size(), 1000, "Max size mismatch");
    //assert_eq!(read_leaf.key_array, [0;LEAF_PAGE_SLOT_CNT], " ")
    assert_eq!(read_leaf.key_array[0], key, "Inserted key should match");
    assert_eq!(read_leaf.key_array[1], key1, "Inserted key should match");
    println!("{:?}", read_leaf.key_array);

    let page_table = bpm.page_table.lock().unwrap(); // Assuming page_table is a Mutex<HashMap<PageId, FrameId>>
    println!("last page table {:?}", page_table);
    assert!(page_table.contains_key(&100), "Page 1 should be in page_table");
}





#[test]
fn test_bplustree_index_internal_page() {
    let dm = DiskManager::new("test.db");
    let scheduler = DiskScheduler::new( dm.unwrap());
    let lru_k_replacer_impl = LRUKReplacerImpl::new(100, 3);
    let bpm = BufferPoolManager::new
        (
            10,
            Arc::new(scheduler),
            Arc::new(lru_k_replacer_impl),
        );
    let bplus_tree_internal_page = BplusTreeInternalPage::new(1000, 100);
    assert_eq!(bplus_tree_internal_page.base_page.max_size, 1000, "Invalid page size");
    let mut write_page_guard= bpm.write_page(100, Index);
    let page_data_ref=write_page_guard.as_mut();
    unsafe {
        let data_ptr = page_data_ref.as_mut_ptr() as *mut BplusTreeInternalPage;
        std::ptr::write(data_ptr, bplus_tree_internal_page);
    }
    write_page_guard.flush();
    //lru_k_replacer_impl.set_evictable(123, true);

    let read_page_guard = bpm.read_page(100, Index);
    let read_data = read_page_guard.as_ref();
    assert_eq!(read_data.len(), PAGE_SIZE, "Read page size mismatch");
    let read_internal = unsafe {
        let data_ptr = read_data.as_ptr() as *const BplusTreeInternalPage;
        &*data_ptr
    };
    assert_eq!(read_internal.key_array, [0;INTERNAL_PAGE_SLOT_CNT], " ")
}