
use crate::include::common::config::{PageId, PAGE_SIZE};
use crate::include::storage::page::b_plus_tree_page::BplusTreePage;
const INTERNAL_PAGE_HEADER_SIZE: usize = 12;
// INTERNAL_PAGE_SLOT_CNT
const KEY_SIZE: usize = 8; // 8BYTES
const VALUE_SIZE: usize = 4; // 4BYTES
const PAIR_SIZE: usize = KEY_SIZE + VALUE_SIZE;
pub const INTERNAL_PAGE_SLOT_CNT: usize = (PAGE_SIZE - INTERNAL_PAGE_HEADER_SIZE ) / PAIR_SIZE;


pub type KeyType = i64;

#[repr(C)]
#[derive(Debug, PartialEq)]
pub struct BplusTreeInternalPage {
    pub base_page: BplusTreePage,
    pub key_array: [i64; INTERNAL_PAGE_SLOT_CNT],
    pub page_id_array: [PageId; INTERNAL_PAGE_SLOT_CNT],
}
pub trait BplusTreeInternalPageImpl {
    fn new(max_size: i32, page_id: PageId) -> Self;
    /// returns the key at the specified index. 
    fn key_at(&self, index: i32) -> KeyType;
    /// Sets the key at the specified index
    fn set_key_at(&mut self, index: i32, key: KeyType);
    fn set_page_id_at(&mut self, index: i32, page_id: PageId); 
    /// returns the index of the page id
    fn value_index(&self, value: PageId) -> i32;
    /// returns the child page id at the specfied index
    fn page_id_value_at(&self, index: i32) -> PageId;
    //fn index_value_at(&self, index:i32) -> i32;
    fn to_string(&self) -> String;
    fn is_leaf(&self) -> bool;
}

