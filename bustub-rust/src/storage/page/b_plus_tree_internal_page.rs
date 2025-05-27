use crate::include::{common::config::{IndexPageType, PageId, INVALID_PAGE_ID}, storage::page::b_plus_tree_internal_page::{BplusTreeInternalPage, BplusTreeInternalPageImpl, INTERNAL_PAGE_SLOT_CNT, KeyType}};
use crate::include::storage::page::b_plus_tree_page::{BplusTreePage, BplusTreePageImpl};

impl BplusTreeInternalPageImpl for BplusTreeInternalPage {
    /// Init method after creating a new internal page.
    /// Writes the necessary header info to a newly created page.
    // fn init(&mut self, max_size: i32) {
    //     self.base_page = BplusTreePage::new(IndexPageType::INTERNAL_PAGE, 0, max_size);                 
    //     // self.base_page.page_type = IndexPageType::INTERNAL_PAGE;
    //     // self.base_page.size_ = 0; // why zero at the start?
    //     // self.base_page.max_size = max_size;
    //     self.key_array = [0;INTERNAL_PAGE_SLOT_CNT];
    //     self.page_id_array = [INVALID_PAGE_ID; INTERNAL_PAGE_SLOT_CNT as usize];
    // }


    fn new(max_size: i32, page_id: PageId) -> Self {
        let base_page = BplusTreePage::new(IndexPageType::INTERNAL_PAGE, 0, max_size, page_id);                 
        
        let key_array = [1000;INTERNAL_PAGE_SLOT_CNT];
        //let rid_array = [Rid::new(INVALID_PAGE_ID, 0); LEAF_PAGE_SLOT_CNT as usize];
        let page_id_array = [INVALID_PAGE_ID; INTERNAL_PAGE_SLOT_CNT as usize];
        BplusTreeInternalPage {
            base_page,
            key_array,
            page_id_array
        }
    }    

    fn key_at(&self, index: i32) -> KeyType {
        self.key_array[index as usize]
    }

    fn value_at(&self, index: i32) -> PageId {
        self.page_id_array[index as usize] // is this going to return a copy of the value?
    }

    fn set_key_at(&mut self, index: i32, key: KeyType) {
        self.key_array[index as usize] = key
    }

    fn value_index(&self, value: PageId) -> i32 {
        for index in 0..self.base_page.size_ + 1 {
            if value == self.page_id_array[index as usize] {
                return index                
            }
        }
        -1
    }

    fn to_string(&self) -> String {
        let mut kstr = String::from("(");
        let mut first = true;

        for i in 1..self.base_page.get_size() {
            let key = self.key_at(i);
            if first {
                first = false;
            } else {
                kstr.push_str(",");
            }
            kstr.push_str(&key.to_string()); // Convert i64 to String
        }
        kstr.push(')');
        kstr
    }

}