
use crate::include::common::config::{PageId, PAGE_SIZE};

const INTERNAL_PAGE_HEADER_SIZE: usize = 12;
// INTERNAL_PAGE_SLOT_CNT
const KEY_SIZE: usize = 8; // 8BYTES
const VALUE_SIZE: usize = 4; // 4BYTES
const PAIR_SIZE: usize = KEY_SIZE + VALUE_SIZE;
const INTERNAL_PAGE_SLOT_CNT: usize = (PAGE_SIZE - INTERNAL_PAGE_HEADER_SIZE ) / PAIR_SIZE;


pub type KeyType = i64;

pub struct BplusTreeInternalPage {
    key_array: [i64; INTERNAL_PAGE_SLOT_CNT],
    page_id_array: [PageId; INTERNAL_PAGE_SLOT_CNT],
}
pub trait BplusTreeInternalPageImpl {
    fn init(&mut self, max_size: i32);
    /// returns the key at the specified index. 
    fn key_at(&self, index: i32) -> KeyType;
    /// Sets the key at the specified index
    fn set_key_at(&mut self, index: i32, key: KeyType); 
    /// returns the index of the page id
    fn value_index(&self, value: PageId) -> i32;
    /// returns the child page id at the specfied index
    fn value_at(&self, index: i32) -> PageId;
    fn to_string(&self) -> String;

}

