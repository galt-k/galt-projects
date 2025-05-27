use crate::include::buffer::bufferpool_manager::BufferPoolManagerImpl;
use crate::buffer::bufferpool_manager::BufferPoolManager;
use crate::include::common::config::{PageId, ValueType};
use crate::include::storage::page::b_plus_tree_internal_page::KeyType;

pub struct BplusTree<'a> {
    pub index_name: String,
    pub bpm: &'a BufferPoolManager,
    pub log: Vec<String>,
    pub leaf_max_size: i32,
    pub internal_max_size: i32,
    pub header_page_id: PageId
}

pub trait BplusTreeImpl {
    // Returns true if this B+ tree has no keys and values. 
    fn is_empty(&mut self) -> bool;
    // Insert a key-value pair into this B+tree
    fn insert(&mut self, key:KeyType, value:ValueType) -> bool;
    // Remove a key and its value from this B+tree
    fn remove(&mut self, key: KeyType);
    // Return the value associated with a given key
    fn get_value(&self, key: KeyType, );
    // Return the pageid of the root node
    fn get_root_page_id(&mut self) -> PageId;
    // Index Iterator
    fn begin(&self); // Index Iterator Type?????

}

pub struct IndexIterator {

}