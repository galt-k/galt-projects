
use bustub_rust::buffer::bufferpool_manager::{FrameHeader,BufferPoolManager};
use bustub_rust::include::buffer::bufferpool_manager::BufferPoolManagerImpl;
use bustub_rust::include::storage::page::b_plus_tree_internal_page::{BplusTreeInternalPage, BplusTreeInternalPageImpl, INTERNAL_PAGE_SLOT_CNT};
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
use bustub_rust::include::common::config::{IndexPageType, PAGE_SIZE, INVALID_PAGE_ID};
use bustub_rust::include::storage::page::b_plus_tree_leaf_page::{BplusTreeLeafPage, BplusTreeLeafPageImpl};
use bustub_rust::include::storage::page::b_plus_tree_page::{BplusTreePage, BplusTreePageImpl};
use bustub_rust::include::common::rid::Rid;
use bustub_rust::include::common::config::ValueType;

use bustub_rust::include::storage::page::b_plus_tree_leaf_page::{LEAF_PAGE_SLOT_CNT};
use bustub_rust::include::storage::index::b_plus_tree::{BplusTree, BplusTreeImpl};
use bustub_rust::include::storage::page::b_plus_tree_internal_page::{KeyType};
use bustub_rust::storage::index::b_plus_tree::{InsertablePage, LeafPageGuard};

fn setup_bplus_tree() -> (&'static BufferPoolManager, BplusTree<'static>) {
            
    // Allocate a header page
    

    let dm = DiskManager::new("test.db");
    let scheduler = DiskScheduler::new( dm.unwrap());
    let lru_k_replacer_impl = LRUKReplacerImpl::new(100, 3);
    let bpm = BufferPoolManager::new
        (
            10,
            Arc::new(scheduler),
            Arc::new(lru_k_replacer_impl),
        );
    let header_page_id = INVALID_PAGE_ID;
    let bpm_ref: &'static BufferPoolManager = Box::leak(Box::new(bpm));
    let bplus_tree = BplusTree::new(
        String::from("test_index"),
        bpm_ref, 
        3, 3, header_page_id);
    (bpm_ref, bplus_tree)
}

#[test]
fn test_simple_insert() {
    let (bpm, mut tree) = setup_bplus_tree();

    // Step 1: Verify the tree is empty
    assert_eq!(true, tree.is_empty(), "Tree should be empty initially");
    println!("header page id - {}", tree.header_page_id);
    println!("root page id - {}", tree.get_root_page_id());
    
    // Step 2: Insert a key-value pair
    let key1: KeyType = 42; // KeyType is i64
    let key2: KeyType = 43;
    let value = ValueType::Rid(Rid::new(1, 0)); // ValueType is Rid
    let insert_result1 = tree.insert(key1, value);
    let insert_result2 = tree.insert(key2, value);
    assert_eq!(true, insert_result1, "Insertion should succeed");
    //assert_eq!(true, insert_result2, "Insertion should succeed");
    
    // Step 3: Verify the root page ID is set
    let root_page_id = tree.get_root_page_id();
    assert_ne!(root_page_id, INVALID_PAGE_ID, "Root page ID should be set");
    println!("root page id - {}", root_page_id);

    // Step 4: Verify the key-value pair exists in the root (leaf) node
    let root_guard = bpm.read_page(root_page_id, Index);
    let root_page = unsafe {
        let data = root_guard.as_ref();
        //println!("data reff {:?}", data);
        &*(data.as_ptr() as *const BplusTreeLeafPage)
    };
    assert_eq!(root_page.base_page.get_size(), 2, "Root should contain one key");
    assert_eq!(root_page.key_array[0], key1, "Inserted key should match");
    assert_eq!(root_page.key_array[1], key2, "Inserted key should match");
    // println!("Vector: {:?}", root_page.base_page.max_size);
}

#[test]
fn test_leaf_guard() {
    let (bpm, mut tree) = setup_bplus_tree();
    
    let mut leaf_guard = LeafPageGuard::new(bpm.write_page(1000, Index));
    leaf_guard.initialize(1000, 100);
    let value = ValueType::Rid(Rid::new(1, 0)); // ValueType is Rid
    leaf_guard.insert_at(0, 42, value);
    leaf_guard.insert_at(1, 43, value);


    let leaf_ptr = leaf_guard.guard.as_mut().as_mut_ptr() as *mut BplusTreeLeafPage;
    let leaf = unsafe { &mut *leaf_ptr }; // Create a temporary reference for the insert
    assert_eq!(leaf.base_page.page_id, 1000, "Equal");
    // reinterpret to Bplustreeleaf page

    // again read the page _id from the bufferpool
    let read_guard = bpm.read_page(1000, Index);
    let page: &BplusTreeLeafPage = unsafe {
        let data = read_guard.as_ref();
        &*(data.as_ptr() as *const BplusTreeLeafPage)
    };

    assert_eq!(page.base_page.page_id, 1000, "Equal");
    assert_eq!(page.key_array[0],42,"equal");
    assert_eq!(page.key_array[1],43,"equal");
    assert_eq!(page.base_page.get_size(), 2, "equal");

}



#[test]
fn test_initialize_with_root() {
    let (bpm, mut tree) = setup_bplus_tree();

    // Step 1: Create a HeaderPageGuard
    let mut header = tree.acquire_header_guard();
    assert_eq!(header.root_page_id(), INVALID_PAGE_ID, "Header should initially have invalid root page ID");

    // Step 2: Call initialize_with_root with a key-value pair
    let key: KeyType = 42;
    let value = ValueType::Rid(Rid::new(1, 0));
    let result = tree.initialize_with_root(key, value, &mut header);
    assert!(result, "initialize_with_root should succeed");

    // Step 3: Verify the header's root_page_id
    let root_page_id = header.root_page_id();
    assert_ne!(root_page_id, INVALID_PAGE_ID, "Root page ID should be set");

    // Step 4: Read the root page and verify its contents
    let root_guard = bpm.read_page(root_page_id, Index);
    let root_page = unsafe {
        let data = root_guard.as_ref();
        &*(data.as_ptr() as *const BplusTreeLeafPage)
    };

    // Verify page type
    assert_eq!(root_page.base_page.page_type, IndexPageType::LEAF_PAGE, "Root page should be a leaf page");

    // Verify size
    //assert_eq!(root_page.base_page.get_size(), 1, "Root page should contain one key");

    // Verify key
    assert_eq!(root_page.key_array[0], key, "Inserted key should match");
}

fn setup_leaf_page_guard(bpm: &BufferPoolManager) -> LeafPageGuard {
    let new_page_id = bpm.new_page();
    println!("bpm created page id {}", new_page_id);
    LeafPageGuard::new(bpm.write_page(new_page_id, Index))
}

#[test]
fn test_leaf_insert_at() {
    let dm = DiskManager::new("test.db");
    let scheduler = DiskScheduler::new( dm.unwrap());
    let lru_k_replacer_impl = LRUKReplacerImpl::new(100, 3);
    let bpm = BufferPoolManager::new
        (
            10,
            Arc::new(scheduler),
            Arc::new(lru_k_replacer_impl),
        );
    let mut leaf = setup_leaf_page_guard(&bpm);

    // Initialize the leaf page
    leaf.initialize(1, 3); // page_id = 1, max_size = 3
    println!("After initialize - size: {}, key_array[0]: {}", 
    leaf.as_ref().base_page.get_size(), 
    leaf.as_ref().key_array[0]);

    let key: KeyType = 42;
    let value = ValueType::Rid(Rid::new(1, 0));
    let result = leaf.insert_at(0, key, value);
    println!("After insert_at - size: {}, key_array[0]: {}", 
                leaf.as_ref().base_page.get_size(), 
                leaf.as_ref().key_array[0]);

    assert!(result, "insert_at should succeed");
    assert_eq!(leaf.as_ref().base_page.get_size(), 1, "Size should be 1 after insertion");
    assert_eq!(leaf.as_ref().key_array[0], key, "Key should be 42");

    let rid = leaf.as_ref().rid_array[0];
    assert_eq!(rid.get_page_id(), 1, "Rid page_id should be 1");
    assert_eq!(rid.get_slot_num(), 0, "Rid slot_num should be 0");

    // Read the page back to confirm persistence in BPM
    let read_guard = bpm.read_page(1, Index);
    let read_page = unsafe {
        let data = read_guard.as_ref();
        &*(data.as_ptr() as *const BplusTreeLeafPage)
    };
    println!("BPM read - size: {}, key_array[0]: {}", 
                 read_page.base_page.get_size(), 
                 read_page.key_array[0]);
    assert_eq!(read_page.base_page.get_size(), 1, "BPM size should be 1");
    assert_eq!(read_page.key_array[0], key, "BPM key should be 42");
}

#[test]
fn test_page_table_after_insert() {
    let dm = DiskManager::new("test.db");
    let scheduler = DiskScheduler::new( dm.unwrap());
    let lru_k_replacer_impl = LRUKReplacerImpl::new(100, 3);
    let bpm = BufferPoolManager::new
        (
            10,
            Arc::new(scheduler),
            Arc::new(lru_k_replacer_impl),
        );
    let mut leaf = setup_leaf_page_guard(&bpm);

    // Initialize the leaf page
    leaf.initialize(0, 3); // page_id = 1, max_size = 3

    // Insert a key-value pair
    let key: KeyType = 42;
    let value = ValueType::Rid(Rid::new(0, 0));
    let result = leaf.insert_at(0, key, value);

    assert!(result, "insert_at should succeed");

    // Access the page_table (assuming BufferPoolManager has a page_table field)
    // Note: This is a placeholder; adjust based on your BPM implementation
    let page_table = bpm.page_table.lock().unwrap(); // Assuming page_table is a Mutex<HashMap<PageId, FrameId>>
    println!("last page table {:?}", page_table);
    assert!(page_table.contains_key(&0), "Page 0 should be in page_table");
}