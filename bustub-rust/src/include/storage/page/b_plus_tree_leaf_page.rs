

use crate::include::common::config::{PAGE_SIZE,PageId};
use crate::include::storage::page::b_plus_tree_internal_page::KeyType;
use crate::include::common::rid::Rid;

const LEAF_PAGE_HEADER_SIZE: usize = 16;

// INTERNAL_PAGE_SLOT_CNT
const KEY_SIZE: usize = 8; // 8BYTES
const VALUE_SIZE: usize = 4; // 4BYTES
const PAIR_SIZE: usize = KEY_SIZE + VALUE_SIZE;
const LEAF_PAGE_SLOT_CNT: usize =
    (PAGE_SIZE - LEAF_PAGE_HEADER_SIZE) / (std::mem::size_of::<KeyType>() + std::mem::size_of::<Rid>());

pub struct BplusTreeLeafPage {
    next_page_id: PageId,
    key_array: [KeyType; LEAF_PAGE_SLOT_CNT],
    rid_array: [Rid; LEAF_PAGE_SLOT_CNT],
}

pub trait BplusTreeLeafPageImpl {
    fn init(&mut self, max_size: usize);
    fn get_next_page_id(&self) -> PageId;
    fn set_next_page_id(&mut self, page_id: PageId);
    fn key_at(&self, index: i32) -> KeyType;
    fn to_string(&self)-> String;
}