use std::slice::Windows;

use crate::{buffer::bufferpool_manager::BufferPoolManager, include::{buffer::bufferpool_manager::BufferPoolManagerImpl, common::config::{PageId, INVALID_PAGE_ID}, storage::{index::b_plus_tree::{BplusTree, BplusTreeImpl, IndexIterator}, page::{b_plus_tree_internal_page::KeyType, b_plus_tree_leaf_page::{BplusTreeLeafPage, BplusTreeLeafPageImpl}, b_plus_tree_internal_page::{BplusTreeInternalPageImpl}, b_plus_tree_page::BplusTreePageImpl, page_guard::ReadPageGuardImpl}}}, storage::page::page_guard::WritePageGuard};
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
        }
        // get the leaf node, if the tree is not empty. 
        let leaf_page_id = self.locate_leaf_for_key(key, header.root_page_id());
        // acquire the page guard
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
        // until i reach the leaf page, continue going deep into the tree. 
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

    // Determines the next page to traverse in a B+tree based on a given key
    // and current page Id. Reads the internal page from the buffer pool, check the number of keys
    // , and uses binary search to find the appropriate child page range. 
    fn next_page_for_key(&self, key: KeyType, page_id: PageId) -> PageId {

        //INVALID_PAGE_ID // TODO: Implement key range check
        let guard = self.bpm.read_page(page_id, AccessType::Index);
        let internal_page = unsafe {
            // Cast the raw page data to BplusTreeInternalPage
            let data = guard.as_ref();
            &*(data.as_ptr() as *const BplusTreeInternalPage)
        };

        let num_keys = internal_page.base_page.get_size() as usize;
        // if num_keys == 0 {
        //     return internal_page.page_id_array[0]; // First child if no keys
        // }

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
        // if left == num_keys {
        //     return internal_page.page_id_array[num_keys];
        // }
        //internal_page.page_id_array[left]

    }

    fn perform_insertion(&mut self, leaf: &mut LeafPageGuard, key: KeyType, value: ValueType) -> bool {
        let index = leaf.find_insert_position(key);
        if leaf.can_insert() {
            leaf.insert_at(index, key, value);
            true
        } else {
            // step 1 : split the leaf page
            let (new_leaf_page_id, split_key) = self.split_leaf(leaf);

            // step2 : determine where to insert the new key
            let mut target_leaf = if key < split_key {
                // Insert into the original leaf
                //(*leaf).clone()  // What are the effects of this?
                LeafPageGuard::new(self.bpm.write_page(leaf.guard.guard.page_id, AccessType::Index))
            } else {
                LeafPageGuard::new(self.bpm.write_page(new_leaf_page_id, AccessType::Index))            
            };

            // step 3: Insert the new key-value pair
            let new_index = target_leaf.find_insert_position(key);
            target_leaf.insert_at(new_index, key, value);

            // step 4: Update or create the parent
            self.update_parent_after_split(leaf, new_leaf_page_id, split_key);

            // TODO: mark pages as dirty

            true
        }
    }

    fn split_leaf(&self, leaf: &mut LeafPageGuard) -> (PageId, KeyType) {
        // get the Current size and calculate the split point
        let size = leaf.as_ref().base_page.get_size() as usize; 
        let split_point = size / 2;
        let split_key = leaf.as_ref().key_array[split_point];

        // Step 1: Create a new leaf page
        let new_leaf_page_id = self.bpm.new_page();
        let mut new_leaf = LeafPageGuard::new(self.bpm.write_page(new_leaf_page_id, AccessType::Index));
        new_leaf.initialize(new_leaf_page_id, leaf.as_ref().base_page.get_max_size());

        // Step 2: Move keys and values from split_point to the new leaf
        for i in split_point..size {
            new_leaf.as_mut().key_array[i - split_point] = leaf.as_ref().key_array[i];
            new_leaf.as_mut().rid_array[i - split_point] = leaf.as_ref().rid_array[i];
        }
        new_leaf.as_mut().base_page.set_size((size - split_point) as i32);

        // Step 3: Update the original leaf's size
        leaf.as_mut().base_page.set_size(split_point as i32);

        // Step 4: Link the leaf pages (update next_page_id)
        new_leaf.as_mut().next_page_id = leaf.as_ref().next_page_id;
        leaf.as_mut().next_page_id = new_leaf_page_id;

        (new_leaf_page_id, split_key)
    }

    fn update_parent_after_split(&mut self, leaf: &LeafPageGuard, new_leaf_page_id: PageId, split_key: KeyType) {
        let current_page_id = leaf.as_ref().base_page.page_id;
        let header = self.acquire_header_guard();
        let root_page_id = header.root_page_id();

        if root_page_id == INVALID_PAGE_ID || current_page_id == root_page_id {
            // Case 1: The leaf is the root (no parent exists)
            self.create_new_root(current_page_id, new_leaf_page_id, split_key);
        }else {
            // Case 2: The leaf has a parent (find and update it)
            let parent_page_id = self.find_parent_page(current_page_id);
            let mut parent = InternalPageGuard::new(self.bpm.write_page(parent_page_id, AccessType::Index));
            self.insert_into_internal(&mut parent, split_key, current_page_id, new_leaf_page_id);
            //self.bpm.mark_page_as_dirty(parent_page_id);  TODO making page dirty? 
        }

    }

    fn create_new_root(&mut self, left_page_id: PageId, right_page_id: PageId, split_key: KeyType) {
        // Create a new internal page as the root
        let new_root_page_id = self.bpm.new_page();
        let mut new_root = InternalPageGuard::new(self.bpm.write_page(new_root_page_id, AccessType::Index));
        new_root.initialize(new_root_page_id, self.internal_max_size);

        // Set up the new root with two children
        new_root.as_mut().key_array[0] = split_key;
        new_root.as_mut().page_id_array[0] = left_page_id;
        new_root.as_mut().page_id_array[1] = right_page_id;
        new_root.as_mut().base_page.set_size(1);

        // Update the header to point to the new root
        let mut header = self.acquire_header_guard();
        header.set_root_page_id(new_root_page_id);

        //self.bpm.mark_page_as_dirty(new_root_page_id); TODO making page as dirty??
    }

    fn find_parent_page(&mut self, page_id: PageId) -> PageId {
        println!("In find_parent_page function for page_id: {}", page_id);
        
        // Get the root page ID
        let header = self.acquire_header_guard();
        let root_page_id = header.root_page_id();
        drop(header);
    
        if root_page_id == INVALID_PAGE_ID || root_page_id == page_id {
            panic!("Cannot find parent: tree is empty or page_id {} is the root", page_id);
        }
    
        // Use a stack to track the path and find the parent
        let mut current_page_id = root_page_id;
        let mut parent_page_id = INVALID_PAGE_ID;
    
        loop {
            // Read the current internal page
            let guard = self.bpm.read_page(current_page_id, AccessType::Index);
            let internal_page = unsafe {
                &*(guard.as_ref().as_ptr() as *const BplusTreeInternalPage)
            };
            let num_keys = internal_page.base_page.get_size() as usize;
    
            // Check if this is the parent by looking at its children
            for i in 0..=num_keys {
                if internal_page.page_id_array[i] == page_id {
                    return current_page_id; // Found the parent
                }
            }
    
            // Traverse to the next level to find the page_id
            let next_page_id = self.next_page_for_key(
                // Use a sentinel key to find the page; need the min key of the target page
                {
                    let child_guard = self.bpm.read_page(page_id, AccessType::Index);
                    let child_page = unsafe { &*(child_guard.as_ref().as_ptr() as *const BplusTreePage) };
                    if child_page.page_type == IndexPageType::LEAF_PAGE {
                        let leaf = unsafe { &*(child_guard.as_ref().as_ptr() as *const BplusTreeLeafPage) };
                        if leaf.base_page.get_size() > 0 {
                            leaf.key_array[0] // Min key of the leaf
                        } else {
                            KeyType::MAX // Empty leaf, use max as sentinel
                        }
                    } else {
                        let internal = unsafe { &*(child_guard.as_ref().as_ptr() as *const BplusTreeInternalPage) };
                        if internal.base_page.get_size() > 0 {
                            internal.key_array[0] // Min key of the internal page
                        } else {
                            KeyType::MAX
                        }
                    }
                },
                current_page_id,
            );
    
            if next_page_id == INVALID_PAGE_ID || next_page_id == current_page_id {
                panic!("Invalid traversal: could not find path to page_id {}", page_id);
            }
    
            parent_page_id = current_page_id;
            current_page_id = next_page_id;
    
            // If we've reached the target page_id, the last parent_page_id is the answer
            if current_page_id == page_id {
                return parent_page_id;
            }
        }
    }

    fn insert_into_internal(&self, parent: &mut InternalPageGuard, key: KeyType, left_page_id: PageId, right_page_id: PageId) {
        let size = parent.as_ref().base_page.get_size() as usize;
        let index = parent.find_insert_position(key);

        // Shift keys and children to make room
        for i in (index..size).rev() {
            parent.as_mut().key_array[i + 1] = parent.as_ref().key_array[i];
            parent.as_mut().page_id_array[i + 2] = parent.as_ref().page_id_array[i + 1];
        }

        // Insert the new key and children
        parent.as_mut().key_array[index] = key;
        parent.as_mut().page_id_array[index] = left_page_id;
        parent.as_mut().page_id_array[index + 1] = right_page_id;
        parent.as_mut().base_page.set_size((size + 1) as i32);

        // Check if the parent needs to split
        if !parent.can_insert() {
            // Split the internal page (recursive splitting)
            self.split_internal(parent);
        }


    }

    fn split_internal(&self, internal: &mut InternalPageGuard) {
        // Similar to split_leaf, but for internal pages
        // Split the internal page into two, move half the keys and children to the new page
        // Update the parent (or create a new root if this is the root)
        unimplemented!("split_internal needs to be implemented");
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
        println!("leaf key array- {:?}",leaf.key_array);
        println!("inserted into the leaf- {}", result);
        //println!("Leaf page size - {}", leaf.base_page.size_);
        

        // No need to write back; changes are already in-place
        result    
    }

    fn can_insert(&self) -> bool {
        let leaf = self.as_ref();
        leaf.base_page.get_size() < leaf.base_page.get_max_size()
    }

    fn find_insert_position(&self, key: KeyType) -> i32 {
        // binary search 
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

pub struct InternalPageGuard {
    pub guard: WritePageGuard,
}

impl InternalPageGuard {
    pub fn new(guard: WritePageGuard) -> Self {
        InternalPageGuard { guard }
    }

    pub fn as_ref(&self) -> &BplusTreeInternalPage {
        unsafe {
            let data = self.guard.as_ref();
            &*(data.as_ptr() as *const BplusTreeInternalPage)
        }
    }

    pub fn as_mut(&mut self) -> &mut BplusTreeInternalPage {
        unsafe {
            let data = self.guard.as_mut();
            &mut *(data.as_mut_ptr() as *mut BplusTreeInternalPage)
        }
    }

    // pub fn page_id(&self) -> PageId {
    //     self.guard.page_id
    // }

    pub fn find_insert_position(&self, key: KeyType) -> usize {
        let page = self.as_ref();
        let size = page.base_page.get_size() as usize;
        let mut index = 0;
        while index < size && page.key_array[index] < key {
            index += 1;
        }
        index
    }

    pub fn can_insert(&self) -> bool {
        let page = self.as_ref();
        page.base_page.get_size() < page.base_page.get_max_size()
    }

    pub fn initialize(&mut self, page_id: PageId, max_size: i32) {
        
        let mut internal = BplusTreeInternalPage::new(max_size, page_id);
        println!("initialize internal page id {}", internal.base_page.page_id);
        unsafe {
            let ptr = self.guard.as_mut().as_mut_ptr() as *mut BplusTreeInternalPage;
            std::ptr::write(ptr, internal);
        }
    }
}

impl Drop for InternalPageGuard {
    fn drop(&mut self) {
        // Unpin the page if needed (adjust based on your BufferPoolManager's API)
        // If WritePageGuard handles unpinning automatically, this can be empty
    }
}