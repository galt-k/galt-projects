

use crate::include::common::config::{PageId, ValueType, PAGE_SIZE};
use crate::include::storage::page::b_plus_tree_internal_page::KeyType;
use crate::include::common::rid::Rid;
use crate::include::storage::page::b_plus_tree_page::BplusTreePage;

pub const LEAF_PAGE_HEADER_SIZE: usize = 16;

// INTERNAL_PAGE_SLOT_CNT
const KEY_SIZE: usize = 8; // 8BYTES
const VALUE_SIZE: usize = 4; // 4BYTES
const PAIR_SIZE: usize = KEY_SIZE + VALUE_SIZE;
pub const LEAF_PAGE_SLOT_CNT: usize =
    (PAGE_SIZE - LEAF_PAGE_HEADER_SIZE) / (KEY_SIZE+ std::mem::size_of::<Rid>());

#[repr(C)]
#[derive(Debug, PartialEq)]
pub struct BplusTreeLeafPage {
    pub base_page: BplusTreePage,
    pub next_page_id: PageId,
    pub key_array: [KeyType; LEAF_PAGE_SLOT_CNT],
    pub rid_array: [Rid; LEAF_PAGE_SLOT_CNT],
}

pub trait BplusTreeLeafPageImpl {
    fn new(max_size: i32, page_id: PageId) -> Self;
    fn get_next_page_id(&self) -> PageId;
    fn set_next_page_id(&mut self, page_id: PageId);
    fn key_at(&self, index: i32) -> KeyType;
    fn to_string(&self)-> String;
    fn insert(&mut self, index: i32, key: KeyType, value: ValueType)-> bool;
}