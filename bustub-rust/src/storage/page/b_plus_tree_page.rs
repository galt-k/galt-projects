use crate::include::{common::config::IndexPageType, storage::page::b_plus_tree_page::{BplusTreePage, BplusTreePageImpl}};
use crate::include::common::config::{PageId, INVALID_PAGE_ID};

impl BplusTreePage {
    pub fn new(page_type: IndexPageType, size: i32, max_size: i32, page_id: PageId)-> Self {
        let size_ = size;
        let max_size = max_size;
        BplusTreePage {
            page_type,
            size_,
            max_size,
            page_id
        }
    }
}

impl BplusTreePageImpl for BplusTreePage {
    fn set_page_type(&mut self, index_page_type: IndexPageType) {
        self.page_type = index_page_type
    }

    fn get_size(&self) -> i32 {
        self.size_
    }

    fn get_max_size(&self)-> i32 {
        self.max_size
    }

    fn is_leaf_page(&self) -> bool {
        self.page_type == IndexPageType::LEAF_PAGE
        
    }
    
    fn set_size(&mut self, size: i32) {
        if size>=0 && size <= self.max_size {
            self.size_ = size;
        }
    }

    fn change_size_by( &mut self, amount: i32) {
        if (self.size_ + amount) <= self.max_size || self.size_ + amount > 0
        {
            self.size_ += amount
        }

    }

    fn get_min_size(&self) -> i32 {
        // minimum size should atleast the half. 
        (self.max_size as f64 / 2.0 ).ceil() as i32
    }

    fn set_max_size(&mut self, max_size: i32) {
        self.max_size = max_size
    }
}


#[repr(C)]
#[derive(Debug, PartialEq)]
pub struct BPlusTreeHeaderPage {
    pub root_page_id: PageId,
}

impl BPlusTreeHeaderPage {
    pub fn new() -> Self {
        BPlusTreeHeaderPage {
            root_page_id: INVALID_PAGE_ID,
        }
    }
}