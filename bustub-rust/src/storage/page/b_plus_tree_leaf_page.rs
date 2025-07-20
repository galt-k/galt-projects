use crate::include::storage::page::b_plus_tree_leaf_page::{BplusTreeLeafPage, BplusTreeLeafPageImpl, LEAF_PAGE_HEADER_SIZE, LEAF_PAGE_SLOT_CNT};
use crate::include::storage::page::b_plus_tree_page::BplusTreePage;
use crate::include::common::config::{IndexPageType, PageId, ValueType, INVALID_PAGE_ID};
use crate::include::common::rid::Rid;
use crate::include::storage::page::b_plus_tree_internal_page::KeyType;
use crate::include::storage::page::b_plus_tree_page::{BplusTreePageImpl, BplusTreePageTrait};
use crate::include::storage::page::page::Page;



impl BplusTreeLeafPageImpl for BplusTreeLeafPage {
    fn new(max_size: i32, page_id: PageId) -> Self {
        let base_page = BplusTreePage::new(IndexPageType::LEAF_PAGE, 0, max_size, page_id);                 
        let next_page_id = INVALID_PAGE_ID;
        let key_array = [0;LEAF_PAGE_SLOT_CNT];
        let leaf_slot_count = LEAF_PAGE_SLOT_CNT;
        let rid_array = [Rid::new(INVALID_PAGE_ID, 0); LEAF_PAGE_SLOT_CNT as usize];

        BplusTreeLeafPage {
            base_page,
            next_page_id,
            key_array,
            rid_array
        }
    }

    fn get_next_page_id(&self) -> PageId {
        self.next_page_id
    }

    fn set_next_page_id(&mut self, page_id: PageId) {
        self.next_page_id = page_id
    }

    fn key_at(&self, index: i32) -> KeyType {
        self.key_array[index as usize]
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

    fn insert(&mut self, index: i32, key: KeyType, value: ValueType) -> bool {

        // check if the index isn't full 
        if index < 0 || index > self.base_page.get_size() || self.base_page.get_size() >= self.base_page.get_max_size() {
            return false
        }
        // Shifting the elements by one position. 
        if index < self.base_page.get_size() {
            for i in (index as usize..self.base_page.get_size() as usize).rev() {
                self.key_array[i + 1] = self.key_array[i];
                self.rid_array[i + 1] = self.rid_array[i];
            }
        }
        self.key_array[index as usize] = key;
        self.rid_array[index as usize] = match value {
            ValueType::Rid(rid) => rid,
            _ => panic!("Invalid value type for leaf page"),
        };
        println!("Setting the size");
        self.base_page.set_size(self.base_page.get_size() + 1);
        println!("size is {}", self.base_page.get_size() );
        true           

    }

    fn find_insert_position(&self, key: KeyType) -> i32 {
        // binary search
        let mut left = 0;
        let mut right = self.get_size() - 1; 
        while left <= right {
            // calcuate the mid point
            let mid = left + (right -left) / 2;
            if key < self.key_array[mid as usize] {
                right = mid - 1;
            } else {
                left = mid + 1;
            }
        }
        left as i32
    }

    fn is_leaf(&self) -> bool {
        true
    }
}

impl BplusTreePageTrait for BplusTreeLeafPage {
    fn is_leaf(&self) -> bool {
        true
    }

    fn max_size(&self) -> i32 {
        self.base_page.get_max_size()
    }

    fn get_size(&self) -> i32 {
        self.base_page.get_size()
    }
}
