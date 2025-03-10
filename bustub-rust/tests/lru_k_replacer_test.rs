use bustub_rust::{buffer::lru_k_replacer::{LRUKNode, LRUKReplacerImpl}, include::buffer::lru_k_replacer::LRUKReplacer};
use bustub_rust::include::common::config::AccessType;

//use crate::buffer
//mod buffer;

#[test]
fn test_basic() {
    assert_eq!(0, 0, "Sample tests");
}

#[test]
// Checking if the evictable size is working as expected. 
fn test_lru_k_impl_evictable_size() {
    let mut lru_k_replacer_impl: LRUKReplacerImpl = LRUKReplacerImpl::new(100,3 );
    // check if a node_store a hashmap which maps the frameid with LRUKnode is created
    // Check if a frameId is being inserted into the replacer?
    lru_k_replacer_impl.record_access(123, AccessType::Lookup);
    lru_k_replacer_impl.record_access(234, AccessType::Lookup);
    lru_k_replacer_impl.record_access(345, AccessType::Lookup);
    lru_k_replacer_impl.set_evictable(123, true);
    lru_k_replacer_impl.set_evictable(234, true);
    lru_k_replacer_impl.set_evictable(345, true);
    lru_k_replacer_impl.set_evictable(123, false); 
    assert_eq!(lru_k_replacer_impl.size(), 2, "Curr evictable size matches")
}

#[test]
// Checking if the basic evict algorithm is working as expected.
// Frame with earliest timestamp accessed is evicted. 
fn test_lru_k_impl_evictable_evict() {
    let mut lru_k_replacer_impl: LRUKReplacerImpl = LRUKReplacerImpl::new(100,3 );
    // check if a node_store a hashmap which maps the frameid with LRUKnode is created
    // Check if a frameId is being inserted into the replacer?
    lru_k_replacer_impl.record_access(123, AccessType::Lookup);
    lru_k_replacer_impl.record_access(123, AccessType::Lookup);
    lru_k_replacer_impl.record_access(123, AccessType::Lookup);
    lru_k_replacer_impl.record_access(123, AccessType::Lookup);
    lru_k_replacer_impl.set_evictable(123, true);
    lru_k_replacer_impl.record_access(234, AccessType::Lookup);
    lru_k_replacer_impl.set_evictable(234, true);
    assert_eq!(lru_k_replacer_impl.evict(),Some(123),"matches");
    lru_k_replacer_impl.remove(123);
    assert_eq!(lru_k_replacer_impl.evict(),Some(234),"matches");
    assert_eq!(lru_k_replacer_impl.size(),1,"Matches")

}
