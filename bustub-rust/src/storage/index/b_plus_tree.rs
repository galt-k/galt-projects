use std::slice::Windows;

use crate::{buffer::bufferpool_manager::BufferPoolManager, include::{buffer::bufferpool_manager::BufferPoolManagerImpl, common::config::{PageId, INVALID_PAGE_ID}, storage::{index::b_plus_tree::{BplusTree, BplusTreeImpl, IndexIterator}, page::{b_plus_tree_internal_page::KeyType, b_plus_tree_leaf_page::{BplusTreeLeafPage, BplusTreeLeafPageImpl}, b_plus_tree_page::BplusTreePageImpl, page_guard::ReadPageGuardImpl}}}, storage::page::page_guard::WritePageGuard};
use crate::include::common::config::{AccessType, IndexPageType, ValueType};
use crate::storage::page::b_plus_tree_page::{BPlusTreeHeaderPage};
use crate::include::storage::page::b_plus_tree_page::BplusTreePage;
use crate::include::storage::page::page_guard::{PageguardImpl, WritePageGuardImpl};

use crate::include::storage::page::b_plus_tree_internal_page::BplusTreeInternalPage;

impl<'a> BplusTree<'a> {
    pub fn new(
        index_name: String, 
        bpm: &'a BufferPoolManager, 
        leaf_max_size: i32, 
        internal_max_size: i32, 
        header_page_id: PageId
    ) -> Self {
        BplusTree {
            index_name,
            bpm,
            log: Vec::new(),
            leaf_max_size,
            internal_max_size,
            header_page_id
        }        
    }
}

impl<'a> BplusTreeImpl for BplusTree<'a> {

    fn is_empty(&mut self) -> bool {
        let header = self.acquire_header_guard();
        header.is_empty()
    }

    fn insert(&mut self, key:KeyType, value:ValueType) -> bool {

        //acquire the header page and check 
        let mut header = self.acquire_header_guard();
        if header.is_empty() {
            println!("header is empty");
            // create a new leaf node as root and return it. 
            let result = self.initialize_with_root(key, value, &mut header); 
            return result
            ////

        }
        println!("After header empty 1");
        // get the leaf node, if the tree is not empty. 
        let leaf_page_id = self.locate_leaf_for_key(key, header.root_page_id());
        // acquire the page guard
        println!("After header empty 2");
        let mut leaf = self.acquire_page_guard(leaf_page_id);
        // perform the insertion
        self.perform_insertion(&mut leaf, key, value)         
    }

    fn remove(&mut self, _key: KeyType) {
        // TODO: Implement removal
    }

    fn get_value(&self, _key: KeyType) {
         // TODO: Implement value retrieval
    }

    fn get_root_page_id(&mut self) -> PageId {
        let header = self.acquire_header_guard();
        header.root_page_id()
    }

    fn begin(&self) {
         // TODO: Implement iterator
    }

}

impl<'a> BplusTree<'a> {
    pub fn acquire_header_guard(&mut self) -> HeaderPageGuard {
        if self.header_page_id == INVALID_PAGE_ID {
            // Allocate a new page for the header
            let new_page_id = self.bpm.new_page();
            println!("Setting the header page id- {}", new_page_id);
            self.header_page_id = new_page_id;
    
            // Initialize the header page
            let mut guard = self.bpm.write_page(new_page_id, AccessType::Index);
            let mut header = BPlusTreeHeaderPage::new();
            header.root_page_id = INVALID_PAGE_ID; // Initial root is invalid
            unsafe {
                let ptr = guard.as_mut().as_mut_ptr() as *mut BPlusTreeHeaderPage;
                std::ptr::write(ptr, header);
            }
        }
        // Acquire the header page (now guaranteed to be valid)
        HeaderPageGuard::new(self.bpm.write_page(self.header_page_id, AccessType::Index))        
    }

    // 
    pub fn initialize_with_root(&mut self, key: KeyType, value: ValueType, header: &mut HeaderPageGuard) -> bool {
        // create a  new pageid
        let new_page_id = self.bpm.new_page();
        println!("setting root page id - {}", new_page_id);
        header.set_root_page_id(new_page_id);

        println!("Root page id created");
        // create a leaf pageguard 
        let mut leaf = LeafPageGuard::new(self.bpm.write_page(new_page_id, AccessType::Index));
        leaf.initialize(new_page_id, self.leaf_max_size);
        println!("leaf initialized");
        // insert the key value 
        leaf.insert_at(0, key, value);

        //println!("After insert at status {:?}", leaf.as_ref());
        // Write the updated leaf page back to the BPM

        true

    }

    fn locate_leaf_for_key(&self, key: KeyType, mut page_id: PageId) -> PageId {
        while !self.is_leaf_page(page_id) {
            page_id = self.next_page_for_key(key, page_id);
        }
        page_id
    }

    fn is_leaf_page(&self, page_id: PageId) -> bool {
        let guard = self.bpm.read_page(page_id, AccessType::Index);
        let page = unsafe {
            // Cast the raw byte slice to BplusTreePage for page_type() access
            let data = guard.as_ref();
            &*(data.as_ptr() as *const BplusTreePage)
        };
        page.page_type == IndexPageType::LEAF_PAGE
    }

    fn acquire_page_guard(&mut self, page_id: PageId) -> LeafPageGuard {
        LeafPageGuard::new(self.bpm.write_page(page_id, AccessType::Index))
    }

    fn next_page_for_key(&self, key: KeyType, page_id: PageId) -> PageId {
        //INVALID_PAGE_ID // TODO: Implement key range check
        let guard = self.bpm.read_page(page_id, AccessType::Index);
        let internal_page = unsafe {
            // Cast the raw page data to BplusTreeInternalPage
            let data = guard.as_ref();
            &*(data.as_ptr() as *const BplusTreeInternalPage)
        };

        let num_keys = internal_page.base_page.get_size() as usize;
        if num_keys == 0 {
            return internal_page.page_id_array[0]; // First child if no keys
        }

        // Binary search to find the correct child range
        let mut left = 0;
        let mut right = num_keys;
        while left < right {
            let mid = left + (right - left) / 2;
            if key < internal_page.key_array[mid] {
                right = mid;
            } else {
                left = mid + 1;
            }
        }

        // Return the child page_id for the range
        internal_page.page_id_array[left.min(num_keys - 1)]

    }

    fn perform_insertion(&self, leaf: &mut LeafPageGuard, key: KeyType, value: ValueType) -> bool {
        let index = leaf.find_insert_position(key);
        if leaf.can_insert() {
            leaf.insert_at(index, key, value);
            true
        } else {
            false // TODO: Trigger splitting
        }
    }
}






pub struct HeaderPageGuard {
    guard: WritePageGuard,
}

impl HeaderPageGuard {
    fn new(guard: WritePageGuard) -> Self {
        Self { guard }
    }

    fn set_root_page_id(&mut self, page_id: PageId) {
        let header = self.as_mut();
        header.root_page_id = page_id;
    }

    pub fn root_page_id(&self) -> PageId {
        self.as_ref().root_page_id
    }
    
    fn as_ref(&self) -> &BPlusTreeHeaderPage {
        unsafe { &*(self.guard.as_ref().as_ptr() as *const BPlusTreeHeaderPage) }
    }

    fn as_mut(&mut self) -> &mut BPlusTreeHeaderPage {
        unsafe { &mut *(self.guard.as_mut().as_mut_ptr() as *mut BPlusTreeHeaderPage) }
    }

    fn is_empty(&self) -> bool {
        self.as_ref().root_page_id == INVALID_PAGE_ID
    }
}


pub struct LeafPageGuard {
    pub guard: WritePageGuard
}

impl LeafPageGuard {
    pub fn new( guard: WritePageGuard) -> Self {
        Self { guard }
    }

    // it is just interpreting the existing page to a Bplustree Leaf page
    pub fn initialize(&mut self, page_id: PageId, max_size: i32) {
        let mut leaf = BplusTreeLeafPage::new(max_size, page_id);
        //leaf.base_page.page_id = page_id;
        println!("initialize page id {}", leaf.base_page.page_id);
        unsafe {
            let ptr = self.guard.as_mut().as_mut_ptr() as *mut BplusTreeLeafPage;
            std::ptr::write(ptr, leaf);            
        }
    }


    fn as_ref(&self) -> &BplusTreeLeafPage {
        <Self as AsRef<BplusTreeLeafPage>>::as_ref(self) // Explicitly call AsRef
    }

    fn as_mut(&mut self) -> &mut BplusTreeLeafPage {
        <Self as AsMut<BplusTreeLeafPage>>::as_mut(self) // Explicitly call AsMut, avoid recursion
    }
}

// Trait defining page insertion behavior
pub trait InsertablePage {
    fn insert_at(&mut self, index: i32, key: KeyType, value: ValueType) -> bool;
    fn can_insert(&self) -> bool;
    fn find_insert_position(&self, key: KeyType) -> i32;
}

impl InsertablePage for LeafPageGuard {
    fn insert_at(&mut self, index: i32, key: KeyType, value: ValueType) -> bool {
        println!("getting the guard");

        // Get a raw pointer to the leaf page
        let leaf_ptr = self.guard.as_mut().as_mut_ptr() as *mut BplusTreeLeafPage;
        let leaf = unsafe { &mut *leaf_ptr }; // Create a temporary reference for the insert
        println!("Trying to insert into the leaf");
        //println!("leaf inside insert at{:?}", leaf);
        let result = leaf.insert(index, key, value);
        println!("inserted into the leaf- {}", result);
        println!("Leaf page size - {}", leaf.base_page.size_);

        // No need to write back; changes are already in-place
        result    
    }

    fn can_insert(&self) -> bool {
        let leaf = self.as_ref();
        leaf.base_page.get_size() < leaf.base_page.get_max_size()
    }

    fn find_insert_position(&self, key: KeyType) -> i32 {
        let leaf = self.as_ref();
        let mut left = 0;
        let mut right = leaf.base_page.get_size() as usize;
        while left < right {
            let mid = left + (right - left) / 2;
            if key > leaf.key_array[mid] {
                left = mid + 1;
            } else {
                right = mid;
            }
        }
        left as i32
    }
}


// Implement AsMut for LeafPageGuard to delegate to WritePageGuard
impl AsMut<BplusTreeLeafPage> for LeafPageGuard {
    fn as_mut(&mut self) -> &mut BplusTreeLeafPage {
        unsafe { &mut *(self.guard.as_mut().as_mut_ptr() as *mut BplusTreeLeafPage) }
    }
}

// Implement AsRef for consistency (optional but recommended)
impl AsRef<BplusTreeLeafPage> for LeafPageGuard {
    fn as_ref(&self) -> &BplusTreeLeafPage {
        unsafe { &*(self.guard.as_ref().as_ptr() as *const BplusTreeLeafPage) }
    }
}