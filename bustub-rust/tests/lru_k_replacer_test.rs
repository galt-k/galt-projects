use bustub_rust::{buffer::lru_k_replacer::{LRUKNode, LRUKReplacerImpl}, include::buffer::lru_k_replacer::LRUKReplacer};

//use crate::buffer
//mod buffer;

#[test]
fn test_basic() {
    assert_eq!(0, 0, "Sample tests");
}

#[test]
fn test_lru_k_impl() {
    let lru_k_replacer_impl: LRUKReplacerImpl = LRUKReplacerImpl::new(100,3 );
    assert_eq!(lru_k_replacer_impl.size(), 0, "Creating 100 frames")
}