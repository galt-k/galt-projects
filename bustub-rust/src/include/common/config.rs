use crate::include::common::rid::Rid;
pub type FrameId = i32;
pub type PageId = i32;
pub const INVALID_FRAME_ID: i32 = -1;  // invalid frame id
pub const INVALID_PAGE_ID: i32 = -1;   // invalid page id
pub const PAGE_SIZE: usize = 4096;
pub enum AccessType {
    Unknown = 0,
    Lookup = 1,
    Scan = 2,
    Index = 3,
}

#[derive(Debug, PartialEq)]
//enum class IndexPageType { INVALID_INDEX_PAGE = 0, LEAF_PAGE, INTERNAL_PAGE };
pub enum IndexPageType {
    INVALID_INDEX_PAGE = 0,
    LEAF_PAGE = 1,
    INTERNAL_PAGE = 2,
}

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum ValueType {
    PageId(PageId), // For internal nodes (child page pointers)
    Rid(Rid),       // For leaf nodes (references to heap tuples)
}