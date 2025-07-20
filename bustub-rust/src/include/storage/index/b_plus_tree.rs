use crate::include::buffer::bufferpool_manager::BufferPoolManagerImpl;
use crate::buffer::bufferpool_manager::BufferPoolManager;
use crate::include::common::config::{PageId, ValueType};
use crate::include::storage::page::b_plus_tree_internal_page::KeyType;
use crate::include::storage::page::b_plus_tree_page::{BplusTreePage, BplusTreePageTrait};
use std::collections::HashMap;

pub struct BplusTree<'a> {
    pub index_name: String,
    pub bpm: &'a BufferPoolManager,
    pub log: Vec<String>,
    pub leaf_max_size: i32,
    pub internal_max_size: i32,
    pub header_page_id: PageId,
    pub parent_map: HashMap<PageId, PageId>,

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
    fn is_safe_to_insert(&self, page: &dyn BplusTreePageTrait)-> bool;
    //fn split_leaf(&self, leaf_page: &mut BplusTreeLeafPage) -> (LeafPageGuard, i64);

}

pub struct IndexIterator {

}