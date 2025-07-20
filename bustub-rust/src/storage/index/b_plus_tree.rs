use std::slice::Windows;
use std::collections::HashMap;

use crate::{buffer::bufferpool_manager::BufferPoolManager, include::{buffer::bufferpool_manager::BufferPoolManagerImpl, common::config::{PageId, INVALID_PAGE_ID}, storage::{index::b_plus_tree::{BplusTree, BplusTreeImpl, IndexIterator}, page::{b_plus_tree_internal_page::KeyType, b_plus_tree_leaf_page::{BplusTreeLeafPage, BplusTreeLeafPageImpl}, b_plus_tree_internal_page::{BplusTreeInternalPageImpl}, b_plus_tree_page::BplusTreePageImpl, page_guard::ReadPageGuardImpl}}}, storage::page::page_guard::WritePageGuard};
use crate::include::common::config::{AccessType, IndexPageType, ValueType};
use crate::storage::page::b_plus_tree_page::{BPlusTreeHeaderPage};
use crate::include::storage::page::b_plus_tree_page::{BplusTreePage, BplusTreePageTrait};
use crate::include::storage::page::page_guard::{PageguardImpl, WritePageGuardImpl};
use crate::include::common::rid::Rid;

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
            header_page_id,
            parent_map: HashMap::new(),
        }
        //BPlusTreeHeaderPage::new();
        
        
    }
}

impl<'a> BplusTreeImpl for BplusTree<'a> {

    fn is_empty(&mut self) -> bool {
        let header = self.acquire_header_guard();
        header.is_empty()                
    }

    fn insert(&mut self, key:KeyType, value:ValueType) -> bool {        
        // check if the tree is empty
        let mut header = self.acquire_header_guard();
        if header.is_empty() {
            // create a new page_id in BPM
            let new_page_id = self.bpm.new_page();
            header.set_root_page_id(new_page_id);
            // Create root as a leaf page 
            let mut leaf = LeafPageGuard::new(self.bpm.write_page(new_page_id, AccessType::Index));
            leaf.initialize(new_page_id, self.leaf_max_size);
        }
        // get the the leaf page
        let mut leaf_page_guard = self.find_leaf(key);
        let mut leaf_page = leaf_page_guard.as_mut();
        // check if it is safe to insert 
        if self.is_safe_to_insert(leaf_page) {
            // get the right index position to insert. 
            let index_position = leaf_page.find_insert_position(key);
            leaf_page.insert(index_position, key , value );
            return true 
        } else {
            // split the leaf and insert 
            let (mut new_leaf_page_guard, promoted_key) = self.split_leaf(leaf_page);
            // cast
            let mut new_leaf_page = new_leaf_page_guard.as_mut();
            if key < promoted_key {
                // insert into old leaf
                let index_position = leaf_page.find_insert_position(key);
                leaf_page.insert(index_position, key , value );
            } else {
                let index_position = new_leaf_page.find_insert_position(key);
                new_leaf_page.insert(index_position, key , value );
                // check if the parent require any adjustment. 
                self.insert_into_parent(leaf_page.base_page.page_id, promoted_key, new_leaf_page.base_page.page_id)
            }
            return true
        }
        false
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

    fn is_safe_to_insert(&self, page: &dyn BplusTreePageTrait) -> bool {
        if page.is_leaf() {
            return page.get_size() < self.leaf_max_size
        }
        page.get_size() < self.internal_max_size

    }

}

impl<'a> BplusTree<'a> {
    // 
    pub fn acquire_header_guard(&mut self) -> HeaderPageGuard {
        // if there is an invalid header page id, then allocate a new header page
        if self.header_page_id == INVALID_PAGE_ID {
            // Now allocate a new header page
            // Header Page will just store some metadata of the btree
            let new_page_id = self.bpm.new_page();
            // assign the new header pageid 
            self.header_page_id = new_page_id;

            // Initialize the Header page now, as until now just the headerpage id is created
            let mut guard = self.bpm.write_page(new_page_id, AccessType::Index);
            let mut header = BPlusTreeHeaderPage::new();
            // assign the root page id as invalid page id
            header.root_page_id = INVALID_PAGE_ID; 

            // Convert the guard's mutable refernce to a raw pointer, cast it
            // and write the header data directly into that memeory location.
            unsafe {
                let ptr = guard.as_mut().as_mut_ptr() as *mut BPlusTreeHeaderPage;
                std::ptr::write(ptr, header);
            } 
        }
        HeaderPageGuard::new(self.bpm.write_page(self.header_page_id, AccessType::Index))
    }
    // Just return the leaf page of a specific page id. 
    // 
    pub fn find_leaf(&mut self, key:KeyType) -> LeafPageGuard{
        // get the root page id
        let mut guard = self.bpm.read_page(self.get_root_page_id(), AccessType::Index);
        let mut target_leaf_page_ref: Option<&BplusTreePage> = None;
        loop {
            let data = guard.as_ref();
            // cast the guard as genric Tree page
            let page = unsafe { &*(data.as_ptr() as *const BplusTreePage) };

            if page.is_leaf_page() {
                target_leaf_page_ref = Some(page);
                break; // found the leaf page
            }

            let internal_page = unsafe { &mut *(data.as_ptr() as *mut BplusTreeInternalPage) };
            // find the child page ID 
            let mut index: usize = 0;
            while index < internal_page.get_size() as usize && key >= internal_page.key_array[index] {
                index += 1;
            }
            let child_page_id = internal_page.page_id_array[index];
            guard = self.bpm.read_page(child_page_id, AccessType::Index);

        }
        let target_leaf_page = target_leaf_page_ref.unwrap();
        let leaf_page_id = target_leaf_page.page_id;
        let write_guard = self.bpm.write_page(leaf_page_id, AccessType::Index);
        LeafPageGuard::new(write_guard)
        
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

    fn split_leaf(&self, leaf_page: &mut BplusTreeLeafPage) -> (LeafPageGuard, i64){
        // get the new page id from BPm
        let new_leaf_page_id = self.bpm.new_page();
        // create a new leaf page and intitalize it
        let mut leaf_guard = LeafPageGuard::new(self.bpm.write_page(new_leaf_page_id, AccessType::Index));
        leaf_guard.initialize(new_leaf_page_id, self.leaf_max_size);
        //cast the new_leaf_page guard as leaf page
        // let new_leaf_page =  unsafe {
        //     // Cast the raw byte slice to BplusTreePage for page_type() access
        //     let data = leaf_guard.as_mut();
        //     &mut *(data.as_mut_ptr() as *mut BplusTreeLeafPage)
        // };
        let new_leaf_page = leaf_guard.as_mut();
        let mid = self.leaf_max_size / 2;  
        // iterate mid to leaf.size times 
        for index in mid..leaf_page.get_size() {
            new_leaf_page.key_array[(index - mid) as usize] = leaf_page.key_array[index as usize];
            new_leaf_page.rid_array[(index - mid) as usize] = leaf_page.rid_array[index as usize];
            leaf_page.key_array[index as usize] = 0;
            leaf_page.rid_array[index as usize] = Rid::new(0, 0);
        } 
        new_leaf_page.base_page.set_size(leaf_page.base_page.size_ - mid);
        leaf_page.base_page.set_size(mid);
        let promoted_key = new_leaf_page.key_array[0];
        (leaf_guard, promoted_key as i64)
    }

    // insert the promoted key into parent page
    fn insert_into_parent(&mut self, old_leaf_page_id: PageId, promoted_key: KeyType, new_leaf_page_id: PageId ) {
        // updated parent _id 
        let parent_id = match self.parent_map.get(&old_leaf_page_id) {
            Some(&id) => id,
            None => {
                let new_root_page_id = self.bpm.new_page();
                let mut internal_guard = InternalPageGuard::new(self.bpm.write_page(new_root_page_id, AccessType::Index));
                internal_guard.initialize(new_root_page_id, self.internal_max_size);
                // get the mutable refernce
                let mut internal_page = internal_guard.as_mut();
                internal_page.key_array[0] = promoted_key;
                internal_page.page_id_array[0] = old_leaf_page_id;
                internal_page.page_id_array[1] = new_leaf_page_id;
                internal_page.base_page.set_size(1);
                // set the root page id to new root id
                // acquire the header page guard
                let mut header_guard = self.acquire_header_guard();
                header_guard.set_root_page_id(new_root_page_id);
                // Update the parent map
                self.parent_map.insert(old_leaf_page_id, new_root_page_id);
                self.parent_map.insert(new_leaf_page_id, new_root_page_id);
                return;
            }
        };
        // let parent_id = self.parent_map.get(&old_leaf_page_id);
        // if parent_id.is_none(){
        //     // do some processing
        //     // no parent exsits, need to create a new page 
        //     let new_root_page_id = self.bpm.new_page();
        //     let mut internal_guard = InternalPageGuard::new(self.bpm.write_page(new_root_page_id, AccessType::Index));
        //     internal_guard.initialize(new_root_page_id, self.internal_max_size);
        //     // get the mutable refernce
        //     let mut internal_page = internal_guard.as_mut();
        //     internal_page.key_array[0] = promoted_key;
        //     internal_page.page_id_array[0] = old_leaf_page_id;
        //     internal_page.page_id_array[1] = new_leaf_page_id;
        //     internal_page.base_page.set_size(1);
        //     // set the root page id to new root id
        //     // acquire the header page guard
        //     let mut header_guard = self.acquire_header_guard();
        //     header_guard.set_root_page_id(new_root_page_id);
        //     // Update the parent map
        //     self.parent_map.insert(old_leaf_page_id, new_root_page_id);
        //     self.parent_map.insert(new_leaf_page_id, new_root_page_id);
           
        // } else {

        // }
        // get the parent page
        let mut parent_page_guard = InternalPageGuard::new(self.bpm.write_page(parent_id, AccessType:: Index));
        let mut parent_page = parent_page_guard.as_mut();
        // check if it is safe to insert
        if !self.is_safe_to_insert(parent_page){
            // split the internal page
            let (new_internal_page_id, promoted_key) = self.split_internal(parent_page);
            // insert again into parent
            self.insert_into_parent(parent_page.base_page.page_id, promoted_key, new_internal_page_id);
            let parent_id = *self.parent_map.get(&old_leaf_page_id).unwrap();
            parent_page_guard = InternalPageGuard::new(self.bpm.write_page(parent_id, AccessType:: Index));
            parent_page = parent_page_guard.as_mut(); 
        }
        // safe to insert
        let mut index = parent_page.base_page.get_size();
        while index > 0 && promoted_key < parent_page.key_array[(index-1) as usize] {
            parent_page.key_array[index as usize] = parent_page.key_array[(index - 1) as usize];
            parent_page.page_id_array[(index + 1) as usize] = parent_page.page_id_array[index as usize];
            index -= 1;
        }
        parent_page.key_array[index as usize] = promoted_key;
        parent_page.page_id_array[(index+1) as usize] = new_leaf_page_id;
        parent_page.base_page.size_ += 1;

        self.parent_map.insert(new_leaf_page_id, parent_id);


    }

    fn split_internal(&mut self, old_internal_page: &mut BplusTreeInternalPage) -> (PageId, i64) {
        let new_internal_page_id = self.bpm.new_page();
        // create a new leaf page and intitalize it
        let mut internal_guard = InternalPageGuard::new(self.bpm.write_page(new_internal_page_id, AccessType::Index));        
        internal_guard.initialize(new_internal_page_id, self.internal_max_size);
        let mut new_internal_page = internal_guard.as_mut(); 
        let mid = self.internal_max_size / 2; 
        // get the promoted key
        let promoted_key =  old_internal_page.key_array[mid as usize];
        new_internal_page.base_page.size_ = old_internal_page.base_page.size_ - mid - 1; 

        for index in 0..new_internal_page.base_page.size_ {
            new_internal_page.key_array[index as usize] = old_internal_page.key_array[(mid + 1 + index) as usize];
            new_internal_page.page_id_array[index as usize] = old_internal_page.page_id_array[(mid + 1 + index) as usize];
            self.parent_map.insert(new_internal_page.page_id_array[index as usize], new_internal_page_id);
        }
        new_internal_page.page_id_array[new_internal_page.get_size() as usize] = old_internal_page.page_id_array[old_internal_page.get_size() as usize];
        self.parent_map.insert(new_internal_page.page_id_array[new_internal_page.get_size() as usize], new_internal_page_id);
        old_internal_page.base_page.set_size(mid);
        (new_internal_page_id, promoted_key as i64)
    }

}

pub struct HeaderPageGuard {
    guard: WritePageGuard,
}

impl HeaderPageGuard {
    fn new(guard: WritePageGuard) -> Self {
        Self { guard }
    }
    
    fn as_mut(&mut self) -> &mut BPlusTreeHeaderPage {
        unsafe { &mut *(self.guard.as_mut().as_mut_ptr() as *mut BPlusTreeHeaderPage) }
    }

    fn as_ref(&self) -> &BPlusTreeHeaderPage {
        unsafe { &*(self.guard.as_ref().as_ptr() as *const BPlusTreeHeaderPage) }
    }

    fn is_empty(&self) -> bool {
        self.as_ref().root_page_id == INVALID_PAGE_ID
    }

    fn root_page_id(&self) -> PageId {
        self.as_ref().root_page_id
    }

    fn set_root_page_id(&mut self, page_id: PageId) {
        self.as_mut().root_page_id = page_id
    }
}
pub struct InternalPageGuard {
    pub guard: WritePageGuard
}

impl InternalPageGuard {
    pub fn new( guard: WritePageGuard) -> Self {
        Self { guard }
    }

    pub fn initialize(&mut self, page_id: PageId, max_size: i32) {
        let mut internal_page = BplusTreeInternalPage::new(max_size, page_id);
        //leaf.base_page.page_id = page_id;
        //println!("initialize page id {}", leaf.base_page.page_id);
        unsafe {
            let ptr = self.guard.as_mut().as_mut_ptr() as *mut BplusTreeInternalPage;
            std::ptr::write(ptr, internal_page);            
        }
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
        //println!("initialize page id {}", leaf.base_page.page_id);
        unsafe {
            let ptr = self.guard.as_mut().as_mut_ptr() as *mut BplusTreeLeafPage;
            std::ptr::write(ptr, leaf);            
        }
    }

    // fn as_ref(&self) -> &BplusTreeLeafPage {
    //     <Self as AsRef<BplusTreeLeafPage>>::as_ref(self) // Explicitly call AsRef
    // }

    // fn as_mut(&mut self) -> &mut BplusTreeLeafPage {
    //     <Self as AsMut<BplusTreeLeafPage>>::as_mut(self) // Explicitly call AsMut, avoid recursion
    // }
}

impl AsMut<BplusTreeLeafPage> for LeafPageGuard {
    fn as_mut(&mut self) -> &mut BplusTreeLeafPage {
        unsafe {
            &mut *(self.guard.as_mut().as_mut_ptr() as *mut BplusTreeLeafPage)
        }
    }
}

impl AsMut<BplusTreeInternalPage> for InternalPageGuard {
    fn as_mut(&mut self) -> &mut BplusTreeInternalPage {
        unsafe {
            &mut *(self.guard.as_mut().as_mut_ptr() as *mut BplusTreeInternalPage)
        }
    }
}